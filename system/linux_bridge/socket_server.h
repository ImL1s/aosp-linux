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

#ifndef LINUX_BRIDGE_SOCKET_SERVER_H
#define LINUX_BRIDGE_SOCKET_SERVER_H

#include "vsock_server.h"
#include <cstdint>
#include <vector>
#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <sys/types.h>

namespace android {
namespace system {
namespace linux_bridge {

constexpr uint32_t LNXB_MAGIC = 0x4C4E5842; // "LNXB"
constexpr uint32_t MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16MB

struct SocketPacketHeader {
    uint32_t magic;         // LNXB_MAGIC
    uint16_t cmdType;       // Command type code
    uint32_t length;        // Payload length
    uint32_t transactionId; // Sequence/transaction ID
} __attribute__((packed));

enum class VmState {
    STOPPED,
    STARTING,
    RUNNING
};

class SocketServer {
public:
    explicit SocketServer(const std::string& socketPath);
    ~SocketServer();

    bool start();
    void stop();
    bool isRunning() const { return mRunning.load(); }

    void setVsockServer(VsockServer* vsockServer);
    VsockServer* getVsockServer() const { return mVsockServer; }
    void onVsockHandshakeSuccess(uint32_t cid);
    void stopVmProcess(bool force = false);

    VmState getVmState() const {
        std::lock_guard<std::mutex> lock(mVmMutex);
        return mVmState;
    }
    pid_t getVmPid() const {
        std::lock_guard<std::mutex> lock(mVmMutex);
        return mVmPid;
    }

    static std::vector<uint8_t> serializePacket(uint16_t cmdType, uint32_t transactionId, const std::vector<uint8_t>& payload);
    static bool parsePacket(const std::vector<uint8_t>& rawBuffer, SocketPacketHeader& outHeader, std::vector<uint8_t>& outPayload);
    static bool readFull(int fd, void* buf, size_t count);

private:
    void listenLoop();
    void clientLoop(int clientFd);

    std::string mSocketPath;
    std::atomic<int> mServerFd;
    std::atomic<bool> mRunning;
    std::thread mListenThread;
    std::mutex mClientsMutex;
    std::vector<int> mClientFds;

    mutable std::mutex mVmMutex;
    VmState mVmState{VmState::STOPPED};
    pid_t mVmPid{-1};
    int mPendingClientFd{-1};
    uint32_t mPendingTransactionId{0};
    VsockServer* mVsockServer{nullptr};
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_SOCKET_SERVER_H
