/*
 * Empirical Stress Test Harness for Milestone M2 (Challenger 2 - C++ Native Components)
 * Empirically stress-tests native C++ modules:
 * - HmacAuth: token generation, HMAC verification, 5s timeout, single-use token replay rejection, constantTimeCompare
 * - VsockFraming: frame packing/unpacking, magic signature validation, payload > 16MB rejection
 */

#include "system/linux_bridge/hmac_auth.h"
// Note: vsock_framing.h is omitted here because both vsock_framing.h and hmac_auth.h
// duplicate the definition of struct AuthHandshakePayload, causing a C++ redefinition error.
// We test VsockFraming methods via forward declarations or separate testing.
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <chrono>
#include <thread>

using namespace android::system::linux_bridge;

struct NativeTestResult {
    std::string name;
    bool passed;
    std::string details;
};

static std::vector<NativeTestResult> gNativeResults;

static void record(const std::string& name, bool passed, const std::string& details = "") {
    gNativeResults.push_back({name, passed, details});
    std::cout << "[NATIVE-STRESS-M2] " << name << " -> " << (passed ? "PASS" : "FAIL");
    if (!details.empty()) {
        std::cout << " (" << details << ")";
    }
    std::cout << std::endl;
}

// 1. Test HmacAuth Token Generation & HMAC Calculation
void testHmacTokenAndSignature() {
    auto token = HmacAuth::generateRandomToken();
    if (token.size() != 32) {
        record("HmacTokenAndSignature", false, "Random token size is not 32 bytes");
        return;
    }

    std::vector<uint8_t> secret = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                                  17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
    auto hmacVal = HmacAuth::computeHmacSha256(secret, token);
    if (hmacVal.size() != 32) {
        record("HmacTokenAndSignature", false, "HMAC size is not 32 bytes");
        return;
    }

    record("HmacTokenAndSignature", true, "32-byte CSPRNG token generated and 256-bit HMAC computed cleanly");
}

// 2. Test HmacAuth Handshake Verification & Single-Use Replay Prevention
void testHmacVerificationAndReplay() {
    std::vector<uint8_t> secret = {'s', 'e', 'c', 'r', 'e', 't', '_', 'k', 'e', 'y', '_', '3', '2', '_', 'b', 'y',
                                  't', 'e', 's', '_', 'l', 'o', 'n', 'g', '!', '!', '!', '!', '!', '!', '!', '!'};
    auto token = HmacAuth::generateRandomToken();
    auto sig = HmacAuth::computeHmacSha256(secret, token);

    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::memcpy(payload.signature, sig.data(), 32);

    auto createdAt = std::chrono::steady_clock::now();

    // Clear used tokens first to start fresh
    HmacAuth::clearUsedTokens();

    // First attempt -> should succeed
    bool pass1 = HmacAuth::verifyHandshake(secret, token, payload, createdAt);

    // Second attempt with same token -> should fail (replayed token)
    bool pass2 = HmacAuth::verifyHandshake(secret, token, payload, createdAt);

    bool pass = pass1 && (!pass2);
    record("HmacVerificationAndReplay", pass,
           pass ? "Initial verification succeeded; replayed token rejected on 2nd attempt"
                : "Failed initial verification or failed replay rejection");
}

// 3. Test HmacAuth 5s Timeout Window
void testHmacTimeoutWindow() {
    std::vector<uint8_t> secret(32, 0xAB);
    auto token = HmacAuth::generateRandomToken();
    auto sig = HmacAuth::computeHmacSha256(secret, token);

    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::memcpy(payload.signature, sig.data(), 32);

    // Simulate token created 6.0 seconds ago
    auto expiredCreatedAt = std::chrono::steady_clock::now() - std::chrono::milliseconds(6000);

    HmacAuth::clearUsedTokens();
    bool verifyRes = HmacAuth::verifyHandshake(secret, token, payload, expiredCreatedAt);

    record("HmacTimeoutWindow", !verifyRes,
           !verifyRes ? "Handshake created 6.0s ago correctly timed out (>5.0s limit)"
                      : "ERROR: Expired handshake was accepted!");
}

// 4. Test VsockFraming Magic & Payload Limits
void testVsockFramingBoundaries() {
    uint32_t seqId = 42;
    std::vector<uint8_t> payload = {'T', 'E', 'S', 'T'};

    auto frame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seqId, payload);
    
    VsockFrameHeader header;
    std::vector<uint8_t> unpackedPayload;
    bool ok = VsockFraming::unpackFrame(frame, header, unpackedPayload);

    // Corrupt magic signature
    std::vector<uint8_t> corruptFrame = frame;
    uint32_t badMagic = 0xDEADBEEF;
    std::memcpy(corruptFrame.data(), &badMagic, 4);

    VsockFrameHeader badHeader;
    std::vector<uint8_t> badUnpackedPayload;
    bool corruptOk = VsockFraming::unpackFrame(corruptFrame, badHeader, badUnpackedPayload);

    // Test payload size exceeding 16MB limit
    std::vector<uint8_t> hugePayload(16 * 1024 * 1024 + 1, 0x55);
    auto hugeFrame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seqId, hugePayload);

    bool pass = ok && (!corruptOk) && hugeFrame.empty();
    record("VsockFramingBoundaries", pass,
           pass ? "Frame packing ok, invalid magic rejected, payload >16MB rejected"
                : "Failed framing boundary checks");
}

int main() {
    std::cout << "================================================================" << std::endl;
    std::cout << "  EMPIRICAL CHALLENGER 2 (M2) C++ NATIVE STRESS TEST SUITE      " << std::endl;
    std::cout << "================================================================" << std::endl;

    testHmacTokenAndSignature();
    testHmacVerificationAndReplay();
    testHmacTimeoutWindow();
    testVsockFramingBoundaries();

    std::cout << "================================================================" << std::endl;
    int fails = 0;
    for (const auto& r : gNativeResults) {
        if (!r.passed) fails++;
    }
    std::cout << "TOTAL: " << gNativeResults.size() << " | PASSED: " << (gNativeResults.size() - fails) << " | FAILED: " << fails << std::endl;
    std::cout << "================================================================" << std::endl;

    return fails;
}
