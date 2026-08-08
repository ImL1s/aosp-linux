/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "IS AS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "../socket_server.h"
#include "../vsock_framing.h"
#include "../vsock_server.h"
#include "../hmac_auth.h"
#include <iostream>
#include <cassert>
#include <vector>
#include <cstring>
#include <cstdlib>
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
    std::cout << "[TEST] SocketServer Deferred Handshake & Real VM Lifecycle... " << std::flush;

    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_test_server.sock";
    SocketServer server(testSockPath);
    VsockServer vsockServer;
    vsockServer.start();
    server.setVsockServer(&vsockServer);

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

    // Send CMD_VM_START (0x0001)
    std::vector<uint8_t> startPacket = SocketServer::serializePacket(0x0001, 100, {});
    ssize_t written = write(clientFd, startPacket.data(), startPacket.size());
    assert(written == static_cast<ssize_t>(startPacket.size()));

    // Verify VM state is STARTING and child PID was spawned
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    assert(server.getVmState() == VmState::STARTING);
    assert(server.getVmPid() > 0);

    // Trigger Vsock handshake callback (simulating Vsock HMAC authentication success from Guest CID 3)
    server.onVsockHandshakeSuccess(3);

    // Read response on clientFd (expected CMD_HANDSHAKE_COMPLETE 0x0003)
    SocketPacketHeader respHeader;
    assert(SocketServer::readFull(clientFd, &respHeader, sizeof(respHeader)));
    assert(ntohl(respHeader.magic) == LNXB_MAGIC);
    assert(ntohs(respHeader.cmdType) == 0x0003); // CMD_HANDSHAKE_COMPLETE
    assert(server.getVmState() == VmState::RUNNING);

    // Send CMD_VM_STOP (0x0002)
    std::vector<uint8_t> stopPacket = SocketServer::serializePacket(0x0002, 101, {0x01});
    written = write(clientFd, stopPacket.data(), stopPacket.size());
    assert(written == static_cast<ssize_t>(stopPacket.size()));

    SocketPacketHeader stopRespHeader;
    assert(SocketServer::readFull(clientFd, &stopRespHeader, sizeof(stopRespHeader)));
    assert(ntohl(stopRespHeader.magic) == LNXB_MAGIC);
    assert(ntohs(stopRespHeader.cmdType) == 0x0002); // CMD_VM_STOP
    assert(server.getVmState() == VmState::STOPPED);
    assert(server.getVmPid() == -1);

    close(clientFd);
    server.stop();
    vsockServer.stop();
    assert(!server.isRunning());

    std::cout << "PASS" << std::endl;
    return 0;

}

int testPartialReadAndPayloadSanitization() {
    std::cout << "[TEST] Socket Partial Read Loop & Payload Bounds Check... " << std::flush;

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

    SocketPacketHeader hugeHeader;
    hugeHeader.magic = htonl(LNXB_MAGIC);
    hugeHeader.cmdType = htons(0x0100);
    hugeHeader.length = htonl(MAX_PAYLOAD_SIZE + 1024);
    hugeHeader.transactionId = htonl(1);

    std::vector<uint8_t> invalidRaw(sizeof(SocketPacketHeader) + 64);
    std::memcpy(invalidRaw.data(), &hugeHeader, sizeof(SocketPacketHeader));

    SocketPacketHeader parsedHeader;
    std::vector<uint8_t> payload;
    bool result = SocketServer::parsePacket(invalidRaw, parsedHeader, payload);
    assert(!result);

    std::cout << "PASS" << std::endl;
    return 0;
}

int testVsockServerAuthenticationAndBinding() {
    std::cout << "[TEST] VsockServer Handshake & UnauthenticatedBinding Restriction... " << std::flush;

    HmacAuth::clearUsedTokens();

    VsockServer server;
    server.start();

    // 1. Unauthenticated binding to Port 5001 (PTY) must be rejected
    bool bindUnauth = server.bindPort(5001);
    assert(!bindUnauth);

    // 2. Set token and secret
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();
    std::vector<uint8_t> secret = {'s', 'e', 'c', 'r', 'e', 't', '_', '1', '2', '3'};
    server.setAuthToken(token, secret);

    assert(!server.isAuthenticated());

    // 3. Process valid handshake from CID 3
    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::vector<uint8_t> sig = HmacAuth::computeHmacSha256(secret, token);
    std::memcpy(payload.signature, sig.data(), 32);

    bool authOk = server.processHandshake(3, payload);
    assert(authOk);
    assert(server.isAuthenticated());

    // 4. Authenticated binding to Port 5001 must succeed
    bool bindAuth = server.bindPort(5001);
    assert(bindAuth);

    // 5. Replaying same token must fail
    bool replayOk = server.processHandshake(3, payload);
    assert(!replayOk);

    server.stop();
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
    failures += testVsockServerAuthenticationAndBinding();

    std::cout << "=====================================================" << std::endl;
    if (failures == 0) {
        std::cout << "NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY" << std::endl;
        return 0;
    } else {
        std::cerr << "NATIVE TEST RESULT: " << failures << " TEST(S) FAILED" << std::endl;
        return 1;
    }
}
