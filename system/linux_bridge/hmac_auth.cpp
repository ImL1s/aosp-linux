/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "hmac_auth.h"
#include <random>
#include <cstring>
#include <iostream>
#include <iomanip>
#include <sstream>

#if __has_include(<openssl/hmac.h>)
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <openssl/crypto.h>
#define HAS_OPENSSL 1
#else
#define HAS_OPENSSL 0
#endif

namespace android {
namespace system {
namespace linux_bridge {

std::mutex HmacAuth::sTokenMutex;
std::unordered_set<std::string> HmacAuth::sUsedTokens;

static bool constantTimeCompare(const uint8_t* a, const uint8_t* b, size_t length) {
    uint8_t result = 0;
    for (size_t i = 0; i < length; ++i) {
        result |= a[i] ^ b[i];
    }
    return result == 0;
}

// Standalone FIPS 180-4 SHA-256 implementation
namespace sha256_internal {

static inline uint32_t rotr(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32 - n));
}

static inline uint32_t choose(uint32_t e, uint32_t f, uint32_t g) {
    return (e & f) ^ (~e & g);
}

static inline uint32_t majority(uint32_t a, uint32_t b, uint32_t c) {
    return (a & b) ^ (a & c) ^ (b & c);
}

static inline uint32_t sig0(uint32_t x) {
    return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22);
}

static inline uint32_t sig1(uint32_t x) {
    return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25);
}

static inline uint32_t gamma0(uint32_t x) {
    return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3);
}

static inline uint32_t gamma1(uint32_t x) {
    return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10);
}

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef4a3f7, 0xc67178f2
};

static std::vector<uint8_t> sha256(const uint8_t* data, size_t len) {
    uint32_t h[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    // Pre-processing (Padding)
    size_t paddedLen = len + 1 + 8;
    while (paddedLen % 64 != 0) {
        paddedLen++;
    }

    std::vector<uint8_t> msg(paddedLen, 0);
    if (len > 0 && data != nullptr) {
        std::memcpy(msg.data(), data, len);
    }
    msg[len] = 0x80;

    uint64_t bitsLen = static_cast<uint64_t>(len) * 8;
    for (int i = 0; i < 8; ++i) {
        msg[paddedLen - 1 - i] = static_cast<uint8_t>(bitsLen >> (i * 8));
    }

    // Process the message in successive 512-bit (64-byte) chunks
    for (size_t chunk = 0; chunk < paddedLen; chunk += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(msg[chunk + i * 4]) << 24) |
                   (static_cast<uint32_t>(msg[chunk + i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(msg[chunk + i * 4 + 2]) << 8) |
                   (static_cast<uint32_t>(msg[chunk + i * 4 + 3]));
        }
        for (int i = 16; i < 64; ++i) {
            w[i] = gamma1(w[i - 2]) + w[i - 7] + gamma0(w[i - 15]) + w[i - 16];
        }

        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        uint32_t e = h[4], f = h[5], g = h[6], h_val = h[7];

        for (int i = 0; i < 64; ++i) {
            uint32_t temp1 = h_val + sig1(e) + choose(e, f, g) + K[i] + w[i];
            uint32_t temp2 = sig0(a) + majority(a, b, c);

            h_val = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }

        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += h_val;
    }

    std::vector<uint8_t> digest(32);
    for (int i = 0; i < 8; ++i) {
        digest[i * 4]     = static_cast<uint8_t>(h[i] >> 24);
        digest[i * 4 + 1] = static_cast<uint8_t>(h[i] >> 16);
        digest[i * 4 + 2] = static_cast<uint8_t>(h[i] >> 8);
        digest[i * 4 + 3] = static_cast<uint8_t>(h[i]);
    }
    return digest;
}

} // namespace sha256_internal

std::vector<uint8_t> HmacAuth::generateRandomToken() {
    std::vector<uint8_t> token(32);
#if HAS_OPENSSL
    if (RAND_bytes(token.data(), 32) == 1) {
        return token;
    }
#endif
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint16_t> dis(0, 255);
    for (size_t i = 0; i < 32; ++i) {
        token[i] = static_cast<uint8_t>(dis(gen));
    }
    return token;
}

std::vector<uint8_t> HmacAuth::computeHmacSha256(const std::vector<uint8_t>& secret, const std::vector<uint8_t>& token) {
#if HAS_OPENSSL
    std::vector<uint8_t> hmacResult(32, 0);
    unsigned int len = 32;
    HMAC(EVP_sha256(), secret.data(), secret.size(), token.data(), token.size(), hmacResult.data(), &len);
    return hmacResult;
#else
    // Authentic C++ RFC 2104 HMAC-SHA256 calculation engine
    std::vector<uint8_t> k(64, 0);
    if (secret.size() > 64) {
        std::vector<uint8_t> keyHash = sha256_internal::sha256(secret.data(), secret.size());
        std::memcpy(k.data(), keyHash.data(), 32);
    } else {
        std::memcpy(k.data(), secret.data(), secret.size());
    }

    std::vector<uint8_t> ipad(64), opad(64);
    for (size_t i = 0; i < 64; ++i) {
        ipad[i] = k[i] ^ 0x36;
        opad[i] = k[i] ^ 0x5C;
    }

    // inner_hash = SHA256(ipad || token)
    std::vector<uint8_t> innerMsg;
    innerMsg.reserve(64 + token.size());
    innerMsg.insert(innerMsg.end(), ipad.begin(), ipad.end());
    innerMsg.insert(innerMsg.end(), token.begin(), token.end());
    std::vector<uint8_t> innerHash = sha256_internal::sha256(innerMsg.data(), innerMsg.size());

    // outer_hash = SHA256(opad || inner_hash)
    std::vector<uint8_t> outerMsg;
    outerMsg.reserve(64 + innerHash.size());
    outerMsg.insert(outerMsg.end(), opad.begin(), opad.end());
    outerMsg.insert(outerMsg.end(), innerHash.begin(), innerHash.end());
    return sha256_internal::sha256(outerMsg.data(), outerMsg.size());
#endif
}

void HmacAuth::markTokenUsed(const std::vector<uint8_t>& token) {
    std::lock_guard<std::mutex> lock(sTokenMutex);
    std::string hexStr;
    hexStr.reserve(token.size() * 2);
    static const char hexChars[] = "0123456789abcdef";
    for (uint8_t b : token) {
        hexStr.push_back(hexChars[(b >> 4) & 0x0F]);
        hexStr.push_back(hexChars[b & 0x0F]);
    }
    sUsedTokens.insert(hexStr);
}

bool HmacAuth::isTokenUsed(const std::vector<uint8_t>& token) {
    std::lock_guard<std::mutex> lock(sTokenMutex);
    std::string hexStr;
    hexStr.reserve(token.size() * 2);
    static const char hexChars[] = "0123456789abcdef";
    for (uint8_t b : token) {
        hexStr.push_back(hexChars[(b >> 4) & 0x0F]);
        hexStr.push_back(hexChars[b & 0x0F]);
    }
    return sUsedTokens.find(hexStr) != sUsedTokens.end();
}

void HmacAuth::clearUsedTokens() {
    std::lock_guard<std::mutex> lock(sTokenMutex);
    sUsedTokens.clear();
}

bool HmacAuth::verifyHandshake(
    const std::vector<uint8_t>& secret,
    const std::vector<uint8_t>& expectedToken,
    const AuthHandshakePayload& payload,
    std::chrono::steady_clock::time_point tokenCreatedAt
) {
    // 1. Check Handshake 5-second timeout window
    auto now = std::chrono::steady_clock::now();
    std::chrono::duration<double> elapsed = now - tokenCreatedAt;
    if (elapsed.count() > HANDSHAKE_TIMEOUT_SEC) {
        std::cerr << "[HmacAuth] Handshake timeout expired (" << elapsed.count() << "s > 5.0s)" << std::endl;
        return false;
    }

    std::vector<uint8_t> payloadToken(payload.token, payload.token + 32);

    // 2. Check token match
    if (!constantTimeCompare(payloadToken.data(), expectedToken.data(), 32)) {
        std::cerr << "[HmacAuth] Token mismatch during handshake" << std::endl;
        return false;
    }

    // 3. Single-use token enforcement (Replay Attack Prevention)
    if (isTokenUsed(payloadToken)) {
        std::cerr << "[HmacAuth] Replayed token rejected during handshake" << std::endl;
        return false;
    }

    // 4. Calculate expected HMAC-SHA256 signature
    std::vector<uint8_t> expectedSig = computeHmacSha256(secret, payloadToken);

    // 5. Constant-time signature comparison
    if (!constantTimeCompare(payload.signature, expectedSig.data(), 32)) {
        std::cerr << "[HmacAuth] SECURITY_ALERT: HMAC signature mismatch during guest handshake" << std::endl;
        return false;
    }

    // Mark token as used
    markTokenUsed(payloadToken);
    return true;
}

} // namespace linux_bridge
} // namespace system
} // namespace android
