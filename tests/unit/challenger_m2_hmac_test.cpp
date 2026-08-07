/*
 * Empirical Stress Test for HmacAuth C++ implementation.
 */

#include "system/linux_bridge/hmac_auth.h"
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <chrono>
#include <cstring>

using namespace android::system::linux_bridge;

int main() {
    std::cout << "=== Running HmacAuth C++ Stress Verification ===" << std::endl;

    // 1. Token generation
    auto token = HmacAuth::generateRandomToken();
    assert(token.size() == 32);

    // 2. HMAC calculation
    std::vector<uint8_t> secret(32, 0x42);
    auto sig = HmacAuth::computeHmacSha256(secret, token);
    assert(sig.size() == 32);

    // 3. Verification & Single-use Replay Rejection
    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::memcpy(payload.signature, sig.data(), 32);

    auto createdAt = std::chrono::steady_clock::now();
    HmacAuth::clearUsedTokens();

    bool ok1 = HmacAuth::verifyHandshake(secret, token, payload, createdAt);
    assert(ok1);
    std::cout << "[HmacAuth] Initial verification: PASS" << std::endl;

    bool ok2 = HmacAuth::verifyHandshake(secret, token, payload, createdAt);
    assert(!ok2);
    std::cout << "[HmacAuth] Single-use token replay rejection: PASS" << std::endl;

    // 4. Timeout expiration
    auto expiredCreatedAt = std::chrono::steady_clock::now() - std::chrono::milliseconds(6000);
    auto token2 = HmacAuth::generateRandomToken();
    auto sig2 = HmacAuth::computeHmacSha256(secret, token2);
    AuthHandshakePayload payload2;
    std::memcpy(payload2.token, token2.data(), 32);
    std::memcpy(payload2.signature, sig2.data(), 32);

    bool ok3 = HmacAuth::verifyHandshake(secret, token2, payload2, expiredCreatedAt);
    assert(!ok3);
    std::cout << "[HmacAuth] 5s timeout window expiration: PASS" << std::endl;

    std::cout << "=== HmacAuth C++ Stress Verification: ALL PASSED ===" << std::endl;
    return 0;
}
