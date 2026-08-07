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

#include "system/linux_bridge/socket_server.h"
#include "system/linux_bridge/vsock_framing.h"
#include <iostream>
#include <cassert>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <thread>
#include <chrono>

using namespace android::system::linux_bridge;

int testSocketFramingSerialization() {
    std::cout << "[TEST] Socket Framing Packet Serialization... " << std::flush;

    uint16_t cmdType = 0x0100; // PTY_DATA
    uint32_t transId = 42;
    std::vector<uint8_t> payload = {'H', 'e', 'l', 'l', 'o'};

    std::vector<uint8_t> packed = SocketServer::serializePacket(cmdType, transId, payload);
    assert(packed.size() == sizeof(SocketPacketHeader) + payload.size());

    SocketPacketHeader header;
    std::vector<uint8_t> unpackedPayload;
    bool parseOk = SocketServer::parsePacket(packed, header, unpackedPayload);

    assert(parseOk);
    assert(header.magic == LNXB_MAGIC);
    assert(header.cmdType == cmdType);
    assert(header.transactionId == transId);
    assert(unpackedPayload == payload);

    std::cout << "PASS" << std::endl;
    return 0;
}

int testVsockFramingPacking() {
    std::cout << "[TEST] Vsock Framing Packing & Unpacking... " << std::flush;

    uint32_t seqId = 1001;
    std::vector<uint8_t> payload = {0x00, 0x01, 0x02, 0x03};

    std::vector<uint8_t> frame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seqId, payload);
    assert(frame.size() == sizeof(VsockFrameHeader) + payload.size());

    VsockFrameHeader header;
    std::vector<uint8_t> unpackedPayload;
    bool ok = VsockFraming::unpackFrame(frame, header, unpackedPayload);

    assert(ok);
    assert(header.magic == VsockFraming::VSOK_MAGIC);
    assert(header.frameType == static_cast<uint8_t>(VsockFrameType::PTY_DATA));
    assert(header.sequenceId == seqId);
    assert(unpackedPayload == payload);

    std::cout << "PASS" << std::endl;
    return 0;
}

int testSocketServerLifecycle() {
    std::cout << "[TEST] SocketServer Lifecycle & Client Request Handling... " << std::flush;

    std::string testSockPath = "/tmp/linux_bridge_test_server.sock";
    SocketServer server(testSockPath);
    bool started = server.start();
    assert(started);
    assert(server.isRunning());

    // Connect a test client socket
    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(clientFd >= 0);

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);

    int res = connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    assert(res == 0);

    // Send CMD_VM_START
    std::vector<uint8_t> startPacket = SocketServer::serializePacket(0x0001, 100, {});
    ssize_t written = write(clientFd, startPacket.data(), startPacket.size());
    assert(written == static_cast<ssize_t>(startPacket.size()));

    // Read response (expected CMD_HANDSHAKE_COMPLETE 0x0003)
    SocketPacketHeader respHeader;
    assert(SocketServer::readFull(clientFd, &respHeader, sizeof(respHeader)));
    assert(ntohl(respHeader.magic) == LNXB_MAGIC);
    assert(ntohs(respHeader.cmdType) == 0x0003); // CMD_HANDSHAKE_COMPLETE

    close(clientFd);
    server.stop();
    assert(!server.isRunning());

    std::cout << "PASS" << std::endl;
    return 0;
}

int testPartialReadAndPayloadSanitization() {
    std::cout << "[TEST] Socket Partial Read Loop & Payload Bounds Check... " << std::flush;

    // Test 1: Verify readFull handles 1-byte writes
    int fds[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0);

    std::string secret = "partial_read_verification_token_12345";
    std::thread writer([&secret, fds]() {
        for (char c : secret) {
            write(fds[1], &c, 1);
            std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
    });

    std::vector<char> readBuffer(secret.size());
    bool fullOk = SocketServer::readFull(fds[0], readBuffer.data(), secret.size());
    assert(fullOk);
    assert(std::string(readBuffer.begin(), readBuffer.end()) == secret);
    writer.join();
    close(fds[0]);
    close(fds[1]);

    // Test 2: Verify parsePacket rejects payloads > MAX_PAYLOAD_SIZE
    SocketPacketHeader hugeHeader;
    hugeHeader.magic = htonl(LNXB_MAGIC);
    hugeHeader.cmdType = htons(0x0100);
    hugeHeader.length = htonl(MAX_PAYLOAD_SIZE + 1024);
    hugeHeader.transactionId = htonl(1);

    std::vector<uint8_t> invalidRaw(sizeof(SocketPacketHeader) + 64);
    std::memcpy(invalidRaw.data(), &hugeHeader, sizeof(SocketPacketHeader));

    SocketPacketHeader parsedHeader;
    std::vector<uint8_t> parsedPayload;
    bool result = SocketServer::parsePacket(invalidRaw, parsedHeader, parsedPayload);
    assert(!result); // Must be rejected

    // Test 3: Verify VsockFraming rejects payloads > MAX_PAYLOAD_SIZE
    VsockFrameHeader vsockHeader;
    vsockHeader.magic = htonl(VsockFraming::VSOK_MAGIC);
    vsockHeader.frameType = static_cast<uint8_t>(VsockFrameType::PTY_DATA);
    vsockHeader.payloadLength = htonl(MAX_PAYLOAD_SIZE + 512);
    vsockHeader.sequenceId = htonl(1);

    std::vector<uint8_t> vsockRaw(sizeof(VsockFrameHeader) + 32);
    std::memcpy(vsockRaw.data(), &vsockHeader, sizeof(VsockFrameHeader));

    VsockFrameHeader parsedVsockHeader;
    std::vector<uint8_t> parsedVsockPayload;
    bool vsockResult = VsockFraming::unpackFrame(vsockRaw, parsedVsockHeader, parsedVsockPayload);
    assert(!vsockResult); // Must be rejected

    std::cout << "PASS" << std::endl;
    return 0;
}

int testHighConcurrencyConnections() {
    std::cout << "[TEST] High-Concurrency Connection Burst (50 clients)... " << std::flush;

    std::string sockPath = "/tmp/linux_bridge_concurrency_test.sock";
    SocketServer server(sockPath);
    assert(server.start());

    constexpr int NUM_CLIENTS = 50;
    std::vector<std::thread> threads;
    std::atomic<int> successCount{0};
    std::atomic<int> failCount{0};

    for (int i = 0; i < NUM_CLIENTS; ++i) {
        threads.emplace_back([&sockPath, &successCount, &failCount, i]() {
            int fd = socket(AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) {
                failCount++;
                return;
            }

            struct sockaddr_un addr;
            std::memset(&addr, 0, sizeof(addr));
            addr.sun_family = AF_UNIX;
            std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

            if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
                failCount++;
                close(fd);
                return;
            }

            auto pkt = SocketServer::serializePacket(0x0001, static_cast<uint32_t>(i + 1), {});
            if (write(fd, pkt.data(), pkt.size()) == static_cast<ssize_t>(pkt.size())) {
                SocketPacketHeader respHeader;
                if (SocketServer::readFull(fd, &respHeader, sizeof(respHeader))) {
                    if (ntohl(respHeader.magic) == LNXB_MAGIC && ntohs(respHeader.cmdType) == 0x0003) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    failCount++;
                }
            } else {
                failCount++;
            }
            close(fd);
        });
    }

    for (auto& t : threads) {
        if (t.joinable()) t.join();
    }

    assert(failCount.load() == 0);
    assert(successCount.load() == NUM_CLIENTS);

    server.stop();
    assert(!server.isRunning());

    std::cout << "PASS (" << successCount.load() << "/" << NUM_CLIENTS << " succeeded)" << std::endl;
    return 0;
}

int testSocketTeardownShutdown() {
    std::cout << "[TEST] Socket Server Teardown Shutdown Handling... " << std::flush;

    std::string sockPath = "/tmp/linux_bridge_teardown_test.sock";
    SocketServer server(sockPath);
    assert(server.start());

    constexpr int NUM_CLIENTS = 10;
    std::vector<int> clientFds;
    for (int i = 0; i < NUM_CLIENTS; ++i) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        assert(fd >= 0);

        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

        assert(connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);
        clientFds.push_back(fd);
    }

    server.stop();
    assert(!server.isRunning());

    for (int fd : clientFds) {
        close(fd);
    }

    std::cout << "PASS" << std::endl;
    return 0;
}

int main() {
    std::cout << "=== Starting Native linux_bridge C++ Test Suite ===" << std::endl;
    int failures = 0;

    failures += testSocketFramingSerialization();
    failures += testVsockFramingPacking();
    failures += testSocketServerLifecycle();
    failures += testPartialReadAndPayloadSanitization();
    failures += testHighConcurrencyConnections();
    failures += testSocketTeardownShutdown();

    std::cout << "===================================================" << std::endl;
    if (failures == 0) {
        std::cout << "NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY" << std::endl;
        return 0;
    } else {
        std::cerr << "NATIVE TEST RESULT: " << failures << " TEST(S) FAILED" << std::endl;
        return 1;
    }
}
