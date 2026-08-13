/*
 * Empirical Stress Test Harness for Milestone 3 (Challenger m3_2 Gate)
 * Focus: R3 Single-Secret HMAC Agreement, Vsock Handshake Verification & Edge Cases
 */

#include "system/linux_bridge/socket_server.h"
#include "system/linux_bridge/vsock_server.h"
#include "system/linux_bridge/hmac_auth.h"
#include "system/linux_bridge/vsock_framing.h"
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>
#include <chrono>
#include <thread>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

using namespace android::system::linux_bridge;

// Test 1: RFC 2104 / RFC 4231 Golden Vector HMAC-SHA256 Verification
int test_rfc2104_golden_vector() {
    std::cout << "[EMPIRICAL M3_2 TEST 1] RFC 4231 Test Case 2 Golden Vector Verification... " << std::flush;

    // Key = "Jefe" (4 bytes)
    // Data = "what do ya want for nothing?" (28 bytes)
    // HMAC-SHA256 = 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843
    std::string keyStr = "Jefe";
    std::string dataStr = "what do ya want for nothing?";
    std::vector<uint8_t> key(keyStr.begin(), keyStr.end());
    std::vector<uint8_t> data(dataStr.begin(), dataStr.end());

    std::vector<uint8_t> hmacResult = HmacAuth::computeHmacSha256(key, data);
    std::string hexResult = HmacAuth::hexEncode(hmacResult);

    std::string expectedHex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

    if (hexResult != expectedHex) {
        std::cout << "FAILED (Expected: " << expectedHex << ", Got: " << hexResult << ")" << std::endl;
        return 1;
    }

    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 2: HMAC Handshake Verification & Edge Cases (Modified Secret, Corrupted Signature, Timeout)
int test_hmac_handshake_verifications() {
    std::cout << "[EMPIRICAL M3_2 TEST 2] HMAC Handshake Verification & Edge Case Matrix... " << std::flush;

    HmacAuth::clearUsedTokens();

    std::vector<uint8_t> secret = HmacAuth::generateRandomToken();
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();
    std::vector<uint8_t> signature = HmacAuth::computeHmacSha256(secret, token);

    AuthHandshakePayload validPayload;
    std::memcpy(validPayload.token, token.data(), 32);
    std::memcpy(validPayload.signature, signature.data(), 32);

    auto now = std::chrono::steady_clock::now();

    // 1. Valid handshake
    bool validOk = HmacAuth::verifyHandshake(secret, token, validPayload, now);
    if (!validOk) {
        std::cout << "FAILED (Valid handshake rejected)" << std::endl;
        return 1;
    }

    // 2. Replay attack with same token must be rejected
    bool replayOk = HmacAuth::verifyHandshake(secret, token, validPayload, now);
    if (replayOk) {
        std::cout << "FAILED (Replayed token was accepted!)" << std::endl;
        return 1;
    }

    // 3. Corrupted signature must be rejected
    std::vector<uint8_t> token2 = HmacAuth::generateRandomToken();
    std::vector<uint8_t> signature2 = HmacAuth::computeHmacSha256(secret, token2);
    signature2[0] ^= 0xFF; // Flip bits in signature

    AuthHandshakePayload corruptedPayload;
    std::memcpy(corruptedPayload.token, token2.data(), 32);
    std::memcpy(corruptedPayload.signature, signature2.data(), 32);

    bool corruptedOk = HmacAuth::verifyHandshake(secret, token2, corruptedPayload, now);
    if (corruptedOk) {
        std::cout << "FAILED (Corrupted signature was accepted!)" << std::endl;
        return 1;
    }

    // 4. Mismatched secret key must be rejected
    std::vector<uint8_t> wrongSecret = HmacAuth::generateRandomToken();
    std::vector<uint8_t> token3 = HmacAuth::generateRandomToken();
    std::vector<uint8_t> signature3 = HmacAuth::computeHmacSha256(secret, token3);

    AuthHandshakePayload wrongSecretPayload;
    std::memcpy(wrongSecretPayload.token, token3.data(), 32);
    std::memcpy(wrongSecretPayload.signature, signature3.data(), 32);

    bool wrongSecretOk = HmacAuth::verifyHandshake(wrongSecret, token3, wrongSecretPayload, now);
    if (wrongSecretOk) {
        std::cout << "FAILED (Wrong secret key accepted signature!)" << std::endl;
        return 1;
    }

    // 5. Expired token (created > 5.0 seconds ago) must be rejected
    std::vector<uint8_t> token4 = HmacAuth::generateRandomToken();
    std::vector<uint8_t> signature4 = HmacAuth::computeHmacSha256(secret, token4);

    AuthHandshakePayload expiredPayload;
    std::memcpy(expiredPayload.token, token4.data(), 32);
    std::memcpy(expiredPayload.signature, signature4.data(), 32);

    auto expiredTime = now - std::chrono::seconds(6); // 6 seconds ago
    bool expiredOk = HmacAuth::verifyHandshake(secret, token4, expiredPayload, expiredTime);
    if (expiredOk) {
        std::cout << "FAILED (Expired handshake token was accepted!)" << std::endl;
        return 1;
    }

    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 3: VsockServer CID Security Filter & State Transition
int test_vsock_server_cid_security() {
    std::cout << "[EMPIRICAL M3_2 TEST 3] VsockServer Guest CID Security Filter... " << std::flush;

    HmacAuth::clearUsedTokens();

    VsockServer vsockServer;
    std::vector<uint8_t> secret = HmacAuth::generateRandomToken();
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();

    vsockServer.setAuthToken(token, secret);

    std::vector<uint8_t> signature = HmacAuth::computeHmacSha256(secret, token);
    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::memcpy(payload.signature, signature.data(), 32);

    // 1. Connection from unauthorized CID 4 must be rejected
    bool cid4Ok = vsockServer.processHandshake(4, payload);
    if (cid4Ok) {
        std::cout << "FAILED (Handshake accepted from unauthorized CID 4)" << std::endl;
        return 1;
    }

    // 2. Connection from unauthorized CID 1 must be rejected
    bool cid1Ok = vsockServer.processHandshake(1, payload);
    if (cid1Ok) {
        std::cout << "FAILED (Handshake accepted from unauthorized CID 1)" << std::endl;
        return 1;
    }

    // 3. Connection from ALLOWED_GUEST_CID (3) must succeed
    bool handshakeSuccessNotified = false;
    vsockServer.setOnHandshakeSuccessCallback([&](uint32_t cid) {
        if (cid == VsockServer::ALLOWED_GUEST_CID) {
            handshakeSuccessNotified = true;
        }
    });

    bool allowedOk = vsockServer.processHandshake(VsockServer::ALLOWED_GUEST_CID, payload);
    if (!allowedOk) {
        std::cout << "FAILED (Handshake rejected for ALLOWED_GUEST_CID 3)" << std::endl;
        return 1;
    }

    if (!handshakeSuccessNotified) {
        std::cout << "FAILED (Handshake success callback was not triggered)" << std::endl;
        return 1;
    }

    if (!vsockServer.isAuthenticated()) {
        std::cout << "FAILED (vsockServer.isAuthenticated() returned false after valid handshake)" << std::endl;
        return 1;
    }

    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 4: SocketServer VM Launch & Handshake Callback Integration
int test_socket_server_vm_launch_integration() {
    std::cout << "[EMPIRICAL M3_2 TEST 4] SocketServer + VsockServer State Integration... " << std::flush;

    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_m3_2_test.sock";
    SocketServer socketServer(testSockPath);
    VsockServer vsockServer;

    socketServer.setVsockServer(&vsockServer);
    assert(socketServer.start());

    // Connect client socket (simulating Framework LinuxBridgeService)
    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(clientFd >= 0);

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);

    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    // Send 64-byte payload with CMD_VM_START (0x0001)
    std::vector<uint8_t> secret = HmacAuth::generateRandomToken();
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();
    std::vector<uint8_t> startPayload(64);
    std::memcpy(startPayload.data(), token.data(), 32);
    std::memcpy(startPayload.data() + 32, secret.data(), 32);

    std::vector<uint8_t> startPkt = SocketServer::serializePacket(0x0001, 777, startPayload);
    ssize_t written = write(clientFd, startPkt.data(), startPkt.size());
    assert(written == static_cast<ssize_t>(startPkt.size()));

    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // SocketServer should now be in STARTING state waiting for vsock handshake
    if (socketServer.getVmState() != VmState::STARTING) {
        std::cout << "FAILED (SocketServer VM state is not STARTING after CMD_VM_START)" << std::endl;
        close(clientFd);
        socketServer.stop();
        return 1;
    }

    // Simulate Guest sending valid AuthHandshakePayload over Vsock to VsockServer
    std::vector<uint8_t> signature = HmacAuth::computeHmacSha256(secret, token);
    AuthHandshakePayload handshakePayload;
    std::memcpy(handshakePayload.token, token.data(), 32);
    std::memcpy(handshakePayload.signature, signature.data(), 32);

    bool vsockOk = vsockServer.processHandshake(VsockServer::ALLOWED_GUEST_CID, handshakePayload);
    if (!vsockOk) {
        std::cout << "FAILED (Vsock handshake failed during integration test)" << std::endl;
        close(clientFd);
        socketServer.stop();
        return 1;
    }

    // Check Framework socket receives CMD_HANDSHAKE_COMPLETE (0x0003)
    SocketPacketHeader respHeader;
    bool readOk = SocketServer::readFull(clientFd, &respHeader, sizeof(respHeader));
    if (!readOk) {
        std::cout << "FAILED (Framework socket failed to read response header)" << std::endl;
        close(clientFd);
        socketServer.stop();
        return 1;
    }

    if (ntohl(respHeader.magic) != LNXB_MAGIC || ntohs(respHeader.cmdType) != 0x0003 || ntohl(respHeader.transactionId) != 777) {
        std::cout << "FAILED (Invalid response header: magic=0x" << std::hex << ntohl(respHeader.magic)
                  << ", cmd=0x" << ntohs(respHeader.cmdType) << ", transId=" << std::dec << ntohl(respHeader.transactionId) << ")" << std::endl;
        close(clientFd);
        socketServer.stop();
        return 1;
    }

    if (socketServer.getVmState() != VmState::RUNNING) {
        std::cout << "FAILED (SocketServer VM state is not RUNNING after CMD_HANDSHAKE_COMPLETE)" << std::endl;
        close(clientFd);
        socketServer.stop();
        return 1;
    }

    close(clientFd);
    socketServer.stop();
    std::cout << "PASS" << std::endl;
    return 0;
}

int main() {
    std::cout << "=== Empirical Challenger M3_2: Native C++ Stress & Protocol Test Suite ===" << std::endl;
    int failures = 0;

    failures += test_rfc2104_golden_vector();
    failures += test_hmac_handshake_verifications();
    failures += test_vsock_server_cid_security();
    failures += test_socket_server_vm_launch_integration();

    std::cout << "==========================================================================" << std::endl;
    if (failures == 0) {
        std::cout << "NATIVE EMPIRICAL TEST RESULT: ALL TESTS PASSED SUCCESSFULLY" << std::endl;
        return 0;
    } else {
        std::cerr << "NATIVE EMPIRICAL TEST RESULT: " << failures << " TEST(S) FAILED" << std::endl;
        return 1;
    }
}
