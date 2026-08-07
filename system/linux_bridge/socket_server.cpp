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

#include "socket_server.h"
#include <iostream>
#include <cstring>
#include <cerrno>
#include <climits>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <algorithm>

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

        // Echo/Handle framing logic or reply
        if (header.cmdType == 0x0001) { // CMD_VM_START
            // Respond with CMD_HANDSHAKE_COMPLETE
            std::vector<uint8_t> response = serializePacket(0x0003, header.transactionId, {});
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
