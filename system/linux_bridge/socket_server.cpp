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

#include "socket_server.h"
#include "hmac_auth.h"
#include <iostream>
#include <cstring>
#include <cerrno>
#include <climits>
#include <csignal>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <arpa/inet.h>
#include <algorithm>
#include <chrono>
#include <thread>

namespace android {
namespace system {
namespace linux_bridge {

bool SocketServer::readFull(int fd, void* buf, size_t count) {
    uint8_t* ptr = static_cast<uint8_t*>(buf);
    size_t totalRead = 0;
    while (totalRead < count) {
        ssize_t bytesRead = read(fd, ptr + totalRead, count - totalRead);
        if (bytesRead <= 0) {
            if (bytesRead < 0 && (errno == EINTR || errno == EAGAIN)) {
                continue;
            }
            return false;
        }
        totalRead += static_cast<size_t>(bytesRead);
    }
    return true;
}

SocketServer::SocketServer(const std::string& socketPath)
    : mSocketPath(socketPath), mServerFd(-1), mRunning(false) {}

SocketServer::~SocketServer() {
    stop();
}

void SocketServer::setVsockServer(VsockServer* vsockServer) {
    std::lock_guard<std::mutex> lock(mVmMutex);
    mVsockServer = vsockServer;
    if (mVsockServer) {
        mVsockServer->setOnHandshakeSuccessCallback([this](uint32_t cid) {
            this->onVsockHandshakeSuccess(cid);
        });
    }
}

void SocketServer::onVsockHandshakeSuccess(uint32_t cid) {
    (void)cid;
    std::lock_guard<std::mutex> lock(mVmMutex);
    if (mVmState == VmState::STARTING && mPendingClientFd >= 0) {
        mVmState = VmState::RUNNING;
        std::vector<uint8_t> response = serializePacket(0x0003, mPendingTransactionId, {});
        write(mPendingClientFd, response.data(), response.size());
        std::cout << "[linux_bridge] Real VM Vsock handshake complete. CMD_HANDSHAKE_COMPLETE sent to framework." << std::endl;
        mPendingClientFd = -1;
        mPendingTransactionId = 0;
    }
}

void SocketServer::stopVmProcess(bool force) {
    std::lock_guard<std::mutex> lock(mVmMutex);
    if (mVmPid > 0) {
        std::cout << "[linux_bridge] Stopping VM child process PID: " << mVmPid << " (force=" << force << ")" << std::endl;
        kill(mVmPid, SIGTERM);
        int status = 0;
        int ret = 0;
        for (int i = 0; i < 20; i++) {
            ret = waitpid(mVmPid, &status, WNOHANG);
            if (ret > 0) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        if (ret == 0 && force) {
            kill(mVmPid, SIGKILL);
            waitpid(mVmPid, &status, 0);
        }
        mVmPid = -1;
    }
    if (mVsockServer) {
        mVsockServer->resetSession();
    }
    mVmState = VmState::STOPPED;
    if (mPendingClientFd >= 0) {
        mPendingClientFd = -1;
        mPendingTransactionId = 0;
    }
}

bool SocketServer::start() {
    if (mRunning.load()) return true;

    int serverFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (serverFd < 0) {
        std::cerr << "[linux_bridge] Failed to create AF_UNIX socket" << std::endl;
        return false;
    }

    unlink(mSocketPath.c_str());

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, mSocketPath.c_str(), sizeof(addr.sun_path) - 1);

    if (bind(serverFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "[linux_bridge] Failed to bind socket to path: " << mSocketPath << std::endl;
        close(serverFd);
        return false;
    }

    if (listen(serverFd, SOMAXCONN) < 0) {
        std::cerr << "[linux_bridge] Failed to listen on socket" << std::endl;
        close(serverFd);
        return false;
    }

    mServerFd.store(serverFd);
    mRunning.store(true);
    mListenThread = std::thread(&SocketServer::listenLoop, this);
    std::cout << "[linux_bridge] SocketServer listening on " << mSocketPath << std::endl;
    return true;
}

void SocketServer::stop() {
    if (!mRunning.exchange(false)) return;

    stopVmProcess(true);

    int serverFd = mServerFd.exchange(-1);
    if (serverFd >= 0) {
        shutdown(serverFd, SHUT_RDWR);
        close(serverFd);
    }
    unlink(mSocketPath.c_str());

    std::vector<int> clientFdsToClose;
    {
        std::lock_guard<std::mutex> lock(mClientsMutex);
        clientFdsToClose = std::move(mClientFds);
        mClientFds.clear();
    }
    for (int fd : clientFdsToClose) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }

    if (mListenThread.joinable()) {
        mListenThread.join();
    }
}

void SocketServer::listenLoop() {
    while (mRunning.load()) {
        int serverFd = mServerFd.load();
        if (serverFd < 0) break;

        struct sockaddr_un clientAddr;
        socklen_t clientLen = sizeof(clientAddr);
        int clientFd = accept(serverFd, reinterpret_cast<struct sockaddr*>(&clientAddr), &clientLen);
        if (clientFd < 0) {
            if (!mRunning.load()) break;
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            break;
        }

        {
            std::lock_guard<std::mutex> lock(mClientsMutex);
            mClientFds.push_back(clientFd);
        }

        std::thread clientThread(&SocketServer::clientLoop, this, clientFd);
        clientThread.detach();
    }
}

void SocketServer::clientLoop(int clientFd) {
    while (mRunning.load()) {
        SocketPacketHeader header;
        if (!readFull(clientFd, &header, sizeof(header))) break;

        header.magic = ntohl(header.magic);
        header.cmdType = ntohs(header.cmdType);
        header.length = ntohl(header.length);
        header.transactionId = ntohl(header.transactionId);

        if (header.magic != LNXB_MAGIC) {
            std::cerr << "[linux_bridge] Invalid packet magic: 0x" << std::hex << header.magic << std::dec << std::endl;
            break;
        }

        if (header.length > MAX_PAYLOAD_SIZE) {
            std::cerr << "[linux_bridge] Packet length exceeds MAX_PAYLOAD_SIZE: " << header.length << std::endl;
            break;
        }

        if (sizeof(SocketPacketHeader) > SIZE_MAX - header.length) {
            std::cerr << "[linux_bridge] Header + length integer overflow detected" << std::endl;
            break;
        }

        std::vector<uint8_t> payload(header.length);
        if (header.length > 0) {
            if (!readFull(clientFd, payload.data(), header.length)) break;
        }

        if (header.cmdType == 0x0001) { // CMD_VM_START
            std::lock_guard<std::mutex> lock(mVmMutex);
            if (mVmState != VmState::STOPPED) {
                std::cerr << "[linux_bridge] CMD_VM_START received while VM state is not STOPPED" << std::endl;
                std::vector<uint8_t> errResp = serializePacket(0x0004, header.transactionId, {0x00, 0x00, 0x00, 0x01});
                write(clientFd, errResp.data(), errResp.size());
                continue;
            }

            std::vector<uint8_t> token;
            if (payload.size() >= 32) {
                token.assign(payload.begin(), payload.begin() + 32);
            } else {
                token = HmacAuth::generateRandomToken();
            }
            // Extract dynamic HMAC key provided by LinuxManagerService authToken or generate securely
            std::vector<uint8_t> secret;
            if (payload.size() >= 64) {
                secret.assign(payload.begin() + 32, payload.begin() + 64);
            } else {
                secret = HmacAuth::generateRandomToken();
            }
            std::string secretHex = HmacAuth::hexEncode(secret);

            if (mVsockServer) {
                mVsockServer->setAuthToken(token, secret);
            }

            mPendingClientFd = clientFd;
            mPendingTransactionId = header.transactionId;
            mVmState = VmState::STARTING;

            pid_t pid = fork();
            if (pid < 0) {
                std::cerr << "[linux_bridge] Failed to fork process for launch_vm.sh" << std::endl;
                mVmState = VmState::STOPPED;
                mPendingClientFd = -1;
                mPendingTransactionId = 0;
                std::vector<uint8_t> errResp = serializePacket(0x0004, header.transactionId, {0x00, 0x00, 0x00, 0x02});
                write(clientFd, errResp.data(), errResp.size());
            } else if (pid == 0) {
                const char* scriptPath = "guest/scripts/launch_vm.sh";
                const char* configPath = "/data/misc/linux/vm_config.json";
                execlp("bash", "bash", scriptPath, configPath, secretHex.c_str(), nullptr);
                _exit(127);
            } else {
                mVmPid = pid;
                std::cout << "[linux_bridge] Spawned VM launch script PID: " << mVmPid << std::endl;
            }
            if (!mVsockServer) {
                mVmState = VmState::RUNNING;
                std::vector<uint8_t> response = serializePacket(0x0003, header.transactionId, {});
                write(clientFd, response.data(), response.size());
                mPendingClientFd = -1;
                mPendingTransactionId = 0;
                mVmState = VmState::STOPPED;
            }
        } else if (header.cmdType == 0x0002) { // CMD_VM_STOP
            bool force = (!payload.empty() && payload[0] == 1);
            stopVmProcess(force);
            std::vector<uint8_t> response = serializePacket(0x0002, header.transactionId, {0x00});
            write(clientFd, response.data(), response.size());
        }
    }

    bool shouldClose = false;
    {
        std::lock_guard<std::mutex> lock(mClientsMutex);
        auto it = std::find(mClientFds.begin(), mClientFds.end(), clientFd);
        if (it != mClientFds.end()) {
            mClientFds.erase(it);
            shouldClose = true;
        }
    }
    if (shouldClose) {
        shutdown(clientFd, SHUT_RDWR);
        close(clientFd);
    }
}

std::vector<uint8_t> SocketServer::serializePacket(uint16_t cmdType, uint32_t transactionId, const std::vector<uint8_t>& payload) {
    if (payload.size() > MAX_PAYLOAD_SIZE) {
        return {};
    }
    if (sizeof(SocketPacketHeader) > SIZE_MAX - payload.size()) {
        return {};
    }

    SocketPacketHeader header;
    header.magic = htonl(LNXB_MAGIC);
    header.cmdType = htons(cmdType);
    header.length = htonl(static_cast<uint32_t>(payload.size()));
    header.transactionId = htonl(transactionId);

    std::vector<uint8_t> result(sizeof(SocketPacketHeader) + payload.size());
    std::memcpy(result.data(), &header, sizeof(SocketPacketHeader));
    if (!payload.empty()) {
        std::memcpy(result.data() + sizeof(SocketPacketHeader), payload.data(), payload.size());
    }
    return result;
}

bool SocketServer::parsePacket(const std::vector<uint8_t>& rawBuffer, SocketPacketHeader& outHeader, std::vector<uint8_t>& outPayload) {
    if (rawBuffer.size() < sizeof(SocketPacketHeader)) return false;

    std::memcpy(&outHeader, rawBuffer.data(), sizeof(SocketPacketHeader));
    outHeader.magic = ntohl(outHeader.magic);
    outHeader.cmdType = ntohs(outHeader.cmdType);
    outHeader.length = ntohl(outHeader.length);
    outHeader.transactionId = ntohl(outHeader.transactionId);

    if (outHeader.magic != LNXB_MAGIC) return false;
    if (outHeader.length > MAX_PAYLOAD_SIZE) return false;
    if (sizeof(SocketPacketHeader) > SIZE_MAX - outHeader.length) return false;
    if (rawBuffer.size() < sizeof(SocketPacketHeader) + outHeader.length) return false;

    outPayload.resize(outHeader.length);
    if (outHeader.length > 0) {
        std::memcpy(outPayload.data(), rawBuffer.data() + sizeof(SocketPacketHeader), outHeader.length);
    }
    return true;
}

} // namespace linux_bridge
} // namespace system
} // namespace android
