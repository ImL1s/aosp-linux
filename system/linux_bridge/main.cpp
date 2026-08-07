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
#include "vsock_framing.h"
#include "vsock_server.h"
#include "hmac_auth.h"
#include <iostream>
#include <csignal>
#include <thread>
#include <chrono>

static std::atomic<bool> gRunning{true};

void signalHandler(int signum) {
    std::cout << "[linux_bridge] Received signal " << signum << ", shutting down..." << std::endl;
    gRunning.store(false);
}

int main(int argc, char** argv) {
    std::signal(SIGINT, signalHandler);
    std::signal(SIGTERM, signalHandler);

    std::cout << "[linux_bridge] Starting native linux_bridge daemon..." << std::endl;

    std::string socketPath = "/dev/socket/linux_bridge";
    if (argc > 1) {
        socketPath = argv[1];
    }

    android::system::linux_bridge::SocketServer server(socketPath);
    if (!server.start()) {
        std::cerr << "[linux_bridge] Failed to start socket server on " << socketPath << std::endl;
        return 1;
    }

    android::system::linux_bridge::VsockServer vsockServer;
    if (!vsockServer.start()) {
        std::cerr << "[linux_bridge] Failed to start vsock server" << std::endl;
        return 1;
    }

    // Bind initial Control RPC port (5000)
    vsockServer.bindPort(android::system::linux_bridge::VSOCK_PORT_CONTROL);

    std::cout << "[linux_bridge] Native daemon initialized successfully (Vsock ports 5000, 5001, 5002 ready)." << std::endl;

    while (gRunning.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }

    vsockServer.stop();
    server.stop();
    std::cout << "[linux_bridge] Daemon terminated cleanly." << std::endl;
    return 0;
}
