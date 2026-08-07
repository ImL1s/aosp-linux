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

#ifndef LINUX_BRIDGE_HMAC_AUTH_H
#define LINUX_BRIDGE_HMAC_AUTH_H

#include <cstdint>
#include <vector>
#include <string>
#include <chrono>
#include <unordered_set>
#include <mutex>
#include "vsock_framing.h"

namespace android {
namespace system {
namespace linux_bridge {

class HmacAuth {
public:
    static constexpr double HANDSHAKE_TIMEOUT_SEC = 5.0;

    static std::vector<uint8_t> generateRandomToken();
    static std::vector<uint8_t> computeHmacSha256(const std::vector<uint8_t>& secret, const std::vector<uint8_t>& token);
    
    static bool verifyHandshake(
        const std::vector<uint8_t>& secret,
        const std::vector<uint8_t>& expectedToken,
        const AuthHandshakePayload& payload,
        std::chrono::steady_clock::time_point tokenCreatedAt
    );

    static void markTokenUsed(const std::vector<uint8_t>& token);
    static bool isTokenUsed(const std::vector<uint8_t>& token);
    static void clearUsedTokens();

private:
    static std::mutex sTokenMutex;
    static std::unordered_set<std::string> sUsedTokens;
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_HMAC_AUTH_H
