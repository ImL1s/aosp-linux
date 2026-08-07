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

#include "vsock_server.h"
#include <iostream>
#include <cstring>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>

#ifdef __linux__
#include <linux/vm_sockets.h>
#else
// Cross-platform sockaddr_vm definition for environments without linux/vm_sockets.h
struct sockaddr_vm {
    unsigned short svm_family;
    unsigned short svm_reserved1;
    unsigned int   svm_port;
    unsigned int   svm_cid;
    unsigned char  svm_zero[4];
};
#ifndef AF_VSOCK
#define AF_VSOCK 40
#endif
#ifndef VMADDR_CID_ANY
#define VMADDR_CID_ANY 0xFFFFFFFF
#endif
#endif

namespace android {
namespace system {
namespace linux_bridge {

VsockServer::VsockServer() {
    mBoundPorts[VSOCK_PORT_CONTROL] = false;
    mBoundPorts[VSOCK_PORT_PTY]     = false;
    mBoundPorts[VSOCK_PORT_WAYLAND] = false;
}

VsockServer::~VsockServer() {
    stop();
}

bool VsockServer::start() {
    std::lock_guard<std::mutex> lock(mMutex);
    mRunning.store(true);
    return true;
}

void VsockServer::stop() {
    std::lock_guard<std::mutex> lock(mMutex);
    mRunning.store(false);

    for (auto& pair : mServerFds) {
        if (pair.second >= 0) {
            ::close(pair.second);
        }
    }
    mServerFds.clear();

    for (auto& thread : mListenThreads) {
        if (thread.joinable()) {
            thread.join();
        }
    }
    mListenThreads.clear();

    for (auto& pair : mBoundPorts) {
        pair.second = false;
    }
    mAuthenticated = false;
}

bool VsockServer::bindPort(uint32_t port) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (port != VSOCK_PORT_CONTROL && port != VSOCK_PORT_PTY && port != VSOCK_PORT_WAYLAND) {
        std::cerr << "[VsockServer] Rejecting bind to unreserved port " << port << std::endl;
        return false;
    }
    if (mBoundPorts[port]) {
        std::cerr << "[VsockServer] Port " << port << " already bound (collision)" << std::endl;
        return false;
    }
    // Ports 5001 and 5002 require authenticated session
    if ((port == VSOCK_PORT_PTY || port == VSOCK_PORT_WAYLAND) && !mAuthenticated) {
        std::cerr << "[VsockServer] Port " << port << " access denied: session not authenticated" << std::endl;
        return false;
    }

    int fd = ::socket(AF_VSOCK, SOCK_STREAM, 0);
    if (fd < 0) {
        std::cerr << "[VsockServer] Warning: POSIX AF_VSOCK socket creation failed on host" << std::endl;
    } else {
        struct sockaddr_vm svm;
        std::memset(&svm, 0, sizeof(svm));
        svm.svm_family = AF_VSOCK;
        svm.svm_cid = VMADDR_CID_ANY;
        svm.svm_port = port;

        if (::bind(fd, reinterpret_cast<struct sockaddr*>(&svm), sizeof(svm)) < 0) {
            std::cerr << "[VsockServer] Failed to bind AF_VSOCK port " << port << std::endl;
            ::close(fd);
            return false;
        }

        if (::listen(fd, 5) < 0) {
            std::cerr << "[VsockServer] Failed to listen on AF_VSOCK port " << port << std::endl;
            ::close(fd);
            return false;
        }
        mServerFds[port] = fd;
        mListenThreads.emplace_back(&VsockServer::listenLoop, this, port, fd);
    }

    mBoundPorts[port] = true;
    return true;
}

void VsockServer::listenLoop(uint32_t port, int serverFd) {
    (void)port;
    while (mRunning.load()) {
        struct sockaddr_vm clientAddr;
        socklen_t addrLen = sizeof(clientAddr);
        int clientFd = ::accept(serverFd, reinterpret_cast<struct sockaddr*>(&clientAddr), &addrLen);
        if (clientFd < 0) {
            if (!mRunning.load()) break;
            continue;
        }

        // Mandatory Guest CID == 3 verification
        if (clientAddr.svm_cid != ALLOWED_GUEST_CID) {
            std::cerr << "[VsockServer] SecurityException: Rejecting connection from unauthorized CID " 
                      << clientAddr.svm_cid << " (Expected CID " << ALLOWED_GUEST_CID << ")" << std::endl;
            ::close(clientFd);
            continue;
        }

        ::close(clientFd);
    }
}

void VsockServer::unbindPort(uint32_t port) {
    std::lock_guard<std::mutex> lock(mMutex);
    auto itF = mServerFds.find(port);
    if (itF != mServerFds.end()) {
        if (itF->second >= 0) {
            ::close(itF->second);
        }
        mServerFds.erase(itF);
    }
    if (mBoundPorts.find(port) != mBoundPorts.end()) {
        mBoundPorts[port] = false;
    }
}

bool VsockServer::isPortBound(uint32_t port) const {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = mBoundPorts.find(port);
    if (it != mBoundPorts.end()) {
        return it->second;
    }
    return false;
}

bool VsockServer::setAuthToken(const std::vector<uint8_t>& token, const std::vector<uint8_t>& secret) {
    std::lock_guard<std::mutex> lock(mMutex);
    mActiveToken = token;
    mSharedSecret = secret;
    mTokenCreatedAt = std::chrono::steady_clock::now();
    mAuthenticated = false;
    return true;
}

bool VsockServer::isAuthenticated() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mAuthenticated;
}

bool VsockServer::processHandshake(uint32_t cid, const AuthHandshakePayload& payload) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (cid != ALLOWED_GUEST_CID) {
        std::cerr << "[VsockServer] SecurityException: Connection from unauthorized CID " << cid << " rejected" << std::endl;
        return false;
    }

    bool ok = HmacAuth::verifyHandshake(mSharedSecret, mActiveToken, payload, mTokenCreatedAt);
    if (ok) {
        mAuthenticated = true;
        std::cout << "[VsockServer] HMAC-SHA256 Auth Handshake SUCCESS for CID " << cid << std::endl;
    } else {
        mAuthenticated = false;
        std::cerr << "[VsockServer] HMAC-SHA256 Auth Handshake FAILED for CID " << cid << std::endl;
    }
    return ok;
}

void VsockServer::resetSession() {
    std::lock_guard<std::mutex> lock(mMutex);
    mAuthenticated = false;
    mActiveToken.clear();
}

} // namespace linux_bridge
} // namespace system
} // namespace android
