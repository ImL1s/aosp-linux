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
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef LINUX_BRIDGE_VSOCK_SERVER_H
#define LINUX_BRIDGE_VSOCK_SERVER_H

#include "vsock_framing.h"
#include "hmac_auth.h"
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <thread>
#include <chrono>
#include <vector>
#include <functional>

namespace android {
namespace system {
namespace linux_bridge {

class VsockServer {
public:
    static constexpr uint32_t ALLOWED_GUEST_CID = 3;

    VsockServer();
    ~VsockServer();

    bool start();
    void stop();

    bool bindPort(uint32_t port);
    void unbindPort(uint32_t port);
    bool isPortBound(uint32_t port) const;

    bool setAuthToken(const std::vector<uint8_t>& token, const std::vector<uint8_t>& secret);
    bool isAuthenticated() const;

    bool processHandshake(uint32_t cid, const AuthHandshakePayload& payload);
    void resetSession();

    void setOnHandshakeSuccessCallback(std::function<void(uint32_t)> cb);

private:
    void listenLoop(uint32_t port, int serverFd);

    std::atomic<bool> mRunning{false};
    mutable std::mutex mMutex;
    std::unordered_map<uint32_t, bool> mBoundPorts;
    std::unordered_map<uint32_t, int> mServerFds;
    std::vector<std::thread> mListenThreads;

    bool mAuthenticated{false};
    std::vector<uint8_t> mActiveToken;
    std::vector<uint8_t> mSharedSecret;
    std::chrono::steady_clock::time_point mTokenCreatedAt;

    std::function<void(uint32_t)> mOnHandshakeSuccessCb;
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_VSOCK_SERVER_H
