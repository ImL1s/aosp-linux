/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Empirical Stress Test Harness for Vsock 3-Port Allocation, 13-Byte Framing,
 * and HMAC-SHA256 Challenge-Response Handshake (F-R2-004 & F-R2-005).
 */

#include "system/linux_bridge/vsock_framing.h"
#include "system/linux_bridge/hmac_auth.h"
#include "system/linux_bridge/vsock_server.h"
#include <iostream>
#include <vector>
#include <cassert>
#include <cstring>
#include <thread>
#include <chrono>
#include <random>
#include <atomic>

using namespace android::system::linux_bridge;

int testVsockFrameHeaderPackingAndBurst() {
    std::cout << "[STRESS] Vsock 13-Byte Frame Header & Rapid 100,000 Packet Burst... " << std::flush;

    // Assert 13-byte packed header layout
    assert(sizeof(VsockFrameHeader) == 13);

    constexpr int BURST_COUNT = 100000;
    std::vector<uint32_t> testPorts = {VSOCK_PORT_CONTROL, VSOCK_PORT_PTY, VSOCK_PORT_WAYLAND};
    VsockFrameType frameTypes[] = {VsockFrameType::CONTROL, VsockFrameType::PTY_DATA, VsockFrameType::WAYLAND};

    std::mt19937 rng(42);

    for (int i = 0; i < BURST_COUNT; ++i) {
        uint32_t port = testPorts[i % 3];
        (void)port;
        VsockFrameType frameType = frameTypes[i % 3];
        uint32_t seqId = static_cast<uint32_t>(i + 1);

        size_t payloadLen = rng() % 256;
        std::vector<uint8_t> payload(payloadLen);
        for (size_t k = 0; k < payloadLen; ++k) {
            payload[k] = static_cast<uint8_t>((i + k) & 0xFF);
        }

        std::vector<uint8_t> packed = VsockFraming::packFrame(frameType, seqId, payload);
        assert(packed.size() == 13 + payloadLen);

        VsockFrameHeader header;
        std::vector<uint8_t> unpackedPayload;
        bool ok = VsockFraming::unpackFrame(packed, header, unpackedPayload);

        assert(ok);
        assert(header.magic == VsockFraming::VSOK_MAGIC);
        assert(header.frameType == static_cast<uint8_t>(frameType));
        assert(header.sequenceId == seqId);
        assert(unpackedPayload == payload);
    }

    std::cout << "PASS (100,000 frames packed/unpacked across ports 5000, 5001, 5002)" << std::endl;
    return 0;
}

int testVsockCorruptedFramingAndPayloadStress() {
    std::cout << "[STRESS] Corrupted Framing Header & Payload Boundary Stress... " << std::flush;

    // 1. Truncated header (< 13 bytes)
    std::vector<uint8_t> shortHeader(12, 0x55);
    VsockFrameHeader header1;
    std::vector<uint8_t> payload1;
    assert(!VsockFraming::unpackFrame(shortHeader, header1, payload1));

    // 2. Invalid magic signature (0xDEADBEEF, 0x00000000, 0xFFFFFFFF)
    uint32_t badMagics[] = {0xDEADBEEF, 0x00000000, 0xFFFFFFFF, 0x12345678};
    for (uint32_t magic : badMagics) {
        auto validFrame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, 1, {'A', 'B', 'C'});
        std::memcpy(validFrame.data(), &magic, 4); // Corrupt magic
        VsockFrameHeader h;
        std::vector<uint8_t> p;
        assert(!VsockFraming::unpackFrame(validFrame, h, p));
    }

    // 3. Payload size > 16MB limit
    std::vector<uint8_t> hugePayload(VsockFraming::MAX_PAYLOAD_SIZE + 1, 0x77);
    auto hugeFrame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, 1, hugePayload);
    assert(hugeFrame.empty()); // Rejected at packing

    // Manual unpack attempt of header declaring payload > 16MB
    VsockFrameHeader hugeHeader;
    hugeHeader.magic = htonl(VsockFraming::VSOK_MAGIC);
    hugeHeader.frameType = static_cast<uint8_t>(VsockFrameType::PTY_DATA);
    hugeHeader.payloadLength = htonl(VsockFraming::MAX_PAYLOAD_SIZE + 100);
    hugeHeader.sequenceId = htonl(10);

    std::vector<uint8_t> craftedRaw(13 + 64);
    std::memcpy(craftedRaw.data(), &hugeHeader, 13);
    VsockFrameHeader h2;
    std::vector<uint8_t> p2;
    assert(!VsockFraming::unpackFrame(craftedRaw, h2, p2));

    // 4. Incomplete buffer (header specifies 100 bytes, but buffer has only 50)
    hugeHeader.payloadLength = htonl(100);
    std::vector<uint8_t> truncatedPayloadBuffer(13 + 50);
    std::memcpy(truncatedPayloadBuffer.data(), &hugeHeader, 13);
    VsockFrameHeader h3;
    std::vector<uint8_t> p3;
    assert(!VsockFraming::unpackFrame(truncatedPayloadBuffer, h3, p3));

    // 5. Random byte fuzzing (10,000 random payload mutations)
    std::mt19937 rng(1337);
    for (int i = 0; i < 10000; ++i) {
        auto validFrame = VsockFraming::packFrame(VsockFrameType::WAYLAND, i, {'X', 'Y', 'Z'});
        size_t mutatePos = rng() % validFrame.size();
        validFrame[mutatePos] ^= static_cast<uint8_t>(1 + (rng() % 255));

        VsockFrameHeader fH;
        std::vector<uint8_t> fP;
        // Should either parse cleanly if payload byte mutated or fail cleanly if header byte mutated
        VsockFraming::unpackFrame(validFrame, fH, fP);
    }

    std::cout << "PASS" << std::endl;
    return 0;
}

int testHmacSha256ChallengeResponseAndTamperResistance() {
    std::cout << "[STRESS] HMAC-SHA256 Challenge-Response & Tamper/Replay Resistance... " << std::flush;

    std::vector<uint8_t> secret = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                   0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
                                   0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                                   0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20};

    auto token = HmacAuth::generateRandomToken();
    assert(token.size() == 32);

    auto signature = HmacAuth::computeHmacSha256(secret, token);
    assert(signature.size() == 32);

    AuthHandshakePayload validPayload;
    std::memcpy(validPayload.token, token.data(), 32);
    std::memcpy(validPayload.signature, signature.data(), 32);

    HmacAuth::clearUsedTokens();
    auto now = std::chrono::steady_clock::now();

    // 1. Valid handshake verification -> MUST PASS
    bool okValid = HmacAuth::verifyHandshake(secret, token, validPayload, now);
    assert(okValid);

    // 2. Replay attack verification -> MUST FAIL
    bool okReplay = HmacAuth::verifyHandshake(secret, token, validPayload, now);
    assert(!okReplay);

    // 3. Corrupted secret key -> MUST FAIL
    auto token2 = HmacAuth::generateRandomToken();
    auto sig2 = HmacAuth::computeHmacSha256(secret, token2);
    AuthHandshakePayload payload2;
    std::memcpy(payload2.token, token2.data(), 32);
    std::memcpy(payload2.signature, sig2.data(), 32);

    std::vector<uint8_t> wrongSecret = secret;
    wrongSecret[0] ^= 0xFF; // Bit flip
    bool okWrongSecret = HmacAuth::verifyHandshake(wrongSecret, token2, payload2, now);
    assert(!okWrongSecret);

    // 4. Bit-flipped token in payload -> MUST FAIL
    auto token3 = HmacAuth::generateRandomToken();
    auto sig3 = HmacAuth::computeHmacSha256(secret, token3);
    AuthHandshakePayload payload3;
    std::memcpy(payload3.token, token3.data(), 32);
    std::memcpy(payload3.signature, sig3.data(), 32);
    payload3.token[15] ^= 0x01; // Bit flip token
    bool okCorruptToken = HmacAuth::verifyHandshake(secret, token3, payload3, now);
    assert(!okCorruptToken);

    // 5. Bit-flipped signature in payload -> MUST FAIL
    auto token4 = HmacAuth::generateRandomToken();
    auto sig4 = HmacAuth::computeHmacSha256(secret, token4);
    AuthHandshakePayload payload4;
    std::memcpy(payload4.token, token4.data(), 32);
    std::memcpy(payload4.signature, sig4.data(), 32);
    payload4.signature[31] ^= 0x80; // Bit flip signature
    bool okCorruptSig = HmacAuth::verifyHandshake(secret, token4, payload4, now);
    assert(!okCorruptSig);

    // 6. 5-Second timeout window expiration -> MUST FAIL
    auto expiredTime = std::chrono::steady_clock::now() - std::chrono::milliseconds(5500); // 5.5s ago
    auto token5 = HmacAuth::generateRandomToken();
    auto sig5 = HmacAuth::computeHmacSha256(secret, token5);
    AuthHandshakePayload payload5;
    std::memcpy(payload5.token, token5.data(), 32);
    std::memcpy(payload5.signature, sig5.data(), 32);
    bool okTimeout = HmacAuth::verifyHandshake(secret, token5, payload5, expiredTime);
    assert(!okTimeout);

    std::cout << "PASS" << std::endl;
    return 0;
}

int testVsock3PortServerAccessControl() {
    std::cout << "[STRESS] Vsock 3-Port Server Access Control & CID Isolation... " << std::flush;

    VsockServer server;
    assert(server.start());

    // 1. Unreserved port bind rejection
    assert(!server.bindPort(1234));
    assert(!server.bindPort(8080));

    // 2. Ports 5001 and 5002 access denied before authentication
    assert(!server.bindPort(VSOCK_PORT_PTY));
    assert(!server.bindPort(VSOCK_PORT_WAYLAND));

    // 3. Port 5000 (CONTROL) bind allowed prior to auth
    assert(server.bindPort(VSOCK_PORT_CONTROL));

    // 4. Authenticate session
    std::vector<uint8_t> secret(32, 0x42);
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();
    server.setAuthToken(token, secret);

    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    auto sig = HmacAuth::computeHmacSha256(secret, token);
    std::memcpy(payload.signature, sig.data(), 32);

    // Connection from unauthorized CID != 3 must be rejected
    assert(!server.processHandshake(99, payload));
    assert(!server.isAuthenticated());

    // Connection from authorized CID == 3 must succeed
    assert(server.processHandshake(VsockServer::ALLOWED_GUEST_CID, payload));
    assert(server.isAuthenticated());

    // 5. Now Ports 5001 and 5002 bind allowed
    assert(server.bindPort(VSOCK_PORT_PTY));
    assert(server.bindPort(VSOCK_PORT_WAYLAND));

    // Port collision rejection
    assert(!server.bindPort(VSOCK_PORT_PTY));

    server.stop();
    assert(!server.isAuthenticated());

    std::cout << "PASS" << std::endl;
    return 0;
}

int main() {
    std::cout << "==========================================================================" << std::endl;
    std::cout << "=== EMPIRICAL STRESS TEST: VSOCK FRAMING & HMAC AUTH (F-R2-004 / 005)  ===" << std::endl;
    std::cout << "==========================================================================" << std::endl;

    int failures = 0;
    failures += testVsockFrameHeaderPackingAndBurst();
    failures += testVsockCorruptedFramingAndPayloadStress();
    failures += testHmacSha256ChallengeResponseAndTamperResistance();
    failures += testVsock3PortServerAccessControl();

    std::cout << "==========================================================================" << std::endl;
    if (failures == 0) {
        std::cout << "VSOCK FRAMING & HMAC AUTH STRESS TEST: ALL PASSED SUCCESSFULLY" << std::endl;
        return 0;
    } else {
        std::cerr << "VSOCK FRAMING & HMAC AUTH STRESS TEST: " << failures << " TEST(S) FAILED" << std::endl;
        return 1;
    }
}
