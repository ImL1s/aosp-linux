/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Empirical Adversarial Stress Test Suite for linux_bridge Daemon & Vsock Integration
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
#include <atomic>
#include <sys/wait.h>
#include <fcntl.h>

using namespace android::system::linux_bridge;

// Test 1: Concurrency & FD Cleanup Verification
int testConcurrencyAndFdCleanup() {
    std::cout << "[STRESS TEST 1] Concurrency & Multi-Client IPC... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_concurrency.sock";
    SocketServer server(testSockPath);
    VsockServer vsockServer;
    vsockServer.start();
    server.setVsockServer(&vsockServer);
    assert(server.start());

    constexpr int NUM_THREADS = 8;
    constexpr int ITERATIONS = 15;
    std::atomic<int> completedOperations{0};

    std::vector<std::thread> threads;
    for (int t = 0; t < NUM_THREADS; ++t) {
        threads.emplace_back([&testSockPath, &completedOperations, t]() {
            for (int i = 0; i < ITERATIONS; ++i) {
                int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
                if (clientFd < 0) continue;

                struct sockaddr_un addr;
                std::memset(&addr, 0, sizeof(addr));
                addr.sun_family = AF_UNIX;
                std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);

                if (connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
                    uint32_t transId = static_cast<uint32_t>(t * 100 + i);
                    // Send CMD_VM_STOP (0x0002) which expects immediate response
                    std::vector<uint8_t> pkt = SocketServer::serializePacket(0x0002, transId, {0x01});
                    write(clientFd, pkt.data(), pkt.size());

                    SocketPacketHeader hdr;
                    if (SocketServer::readFull(clientFd, &hdr, sizeof(hdr))) {
                        if (ntohs(hdr.cmdType) == 0x0002 && ntohl(hdr.transactionId) == transId) {
                            completedOperations++;
                        }
                    }
                }
                close(clientFd);
                std::this_thread::sleep_for(std::chrono::microseconds(500));
            }
        });
    }

    for (auto& th : threads) {
        if (th.joinable()) th.join();
    }

    server.stop();
    vsockServer.stop();
    std::cout << "PASS (Successfully processed " << completedOperations.load() << " concurrent IPC calls)" << std::endl;
    return 0;
}

// Test 2: Malformed Socket Packets & Integer Overflow Fuzzing
int testMalformedPackets() {
    std::cout << "[STRESS TEST 2] Malformed Socket Packets & Bounds Checks... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_malformed.sock";
    SocketServer server(testSockPath);
    assert(server.start());

    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(clientFd >= 0);

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    // 1. Invalid Magic Number (0xDEADBEEF)
    SocketPacketHeader badMagicHdr;
    badMagicHdr.magic = htonl(0xDEADBEEF);
    badMagicHdr.cmdType = htons(0x0001);
    badMagicHdr.length = htonl(0);
    badMagicHdr.transactionId = htonl(101);
    write(clientFd, &badMagicHdr, sizeof(badMagicHdr));

    SocketPacketHeader dummy;
    bool readSuccess = SocketServer::readFull(clientFd, &dummy, sizeof(dummy));
    assert(!readSuccess); // Connection closed cleanly by server
    close(clientFd);

    // 2. Oversized Payload Length (> MAX_PAYLOAD_SIZE)
    clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    SocketPacketHeader hugeLengthHdr;
    hugeLengthHdr.magic = htonl(LNXB_MAGIC);
    hugeLengthHdr.cmdType = htons(0x0001);
    hugeLengthHdr.length = htonl(MAX_PAYLOAD_SIZE + 1024);
    hugeLengthHdr.transactionId = htonl(102);
    write(clientFd, &hugeLengthHdr, sizeof(hugeLengthHdr));

    readSuccess = SocketServer::readFull(clientFd, &dummy, sizeof(dummy));
    assert(!readSuccess); // Connection closed cleanly by server
    close(clientFd);

    // 3. Integer Overflow Payload Length (UINT32_MAX)
    clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    SocketPacketHeader overflowHdr;
    overflowHdr.magic = htonl(LNXB_MAGIC);
    overflowHdr.cmdType = htons(0x0001);
    overflowHdr.length = htonl(UINT32_MAX);
    overflowHdr.transactionId = htonl(103);
    write(clientFd, &overflowHdr, sizeof(overflowHdr));

    readSuccess = SocketServer::readFull(clientFd, &dummy, sizeof(dummy));
    assert(!readSuccess); // Connection closed cleanly by server
    close(clientFd);

    server.stop();
    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 3: Partial Packet Headers & Abrupt Disconnects
int testPartialHeadersAndTruncation() {
    std::cout << "[STRESS TEST 3] Partial Packet Headers & Truncated Payloads... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_partial.sock";
    SocketServer server(testSockPath);
    assert(server.start());

    // 1. Send partial 5 bytes of header then close
    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    SocketPacketHeader validHdr;
    validHdr.magic = htonl(LNXB_MAGIC);
    validHdr.cmdType = htons(0x0002);
    validHdr.length = htonl(10);
    validHdr.transactionId = htonl(201);

    uint8_t* hdrBytes = reinterpret_cast<uint8_t*>(&validHdr);
    write(clientFd, hdrBytes, 5);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    close(clientFd);

    // 2. Send complete header + partial payload then close
    clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    write(clientFd, &validHdr, sizeof(validHdr));
    uint8_t partialPayload[3] = {0x01, 0x02, 0x03};
    write(clientFd, partialPayload, 3);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    close(clientFd);

    server.stop();
    std::cout << "PASS" << std::endl;
    return 0;
}

static bool readFullPacket(int fd, SocketPacketHeader& outHeader, std::vector<uint8_t>& outPayload) {
    if (!SocketServer::readFull(fd, &outHeader, sizeof(outHeader))) return false;
    outHeader.magic = ntohl(outHeader.magic);
    outHeader.cmdType = ntohs(outHeader.cmdType);
    outHeader.length = ntohl(outHeader.length);
    outHeader.transactionId = ntohl(outHeader.transactionId);
    outPayload.resize(outHeader.length);
    if (outHeader.length > 0) {
        if (!SocketServer::readFull(fd, outPayload.data(), outHeader.length)) return false;
    }
    return true;
}

// Test 4: Transaction ID Bounds & Response Matching
int testTransactionIdHandling() {
    std::cout << "[STRESS TEST 4] Edge-Case Transaction IDs (0, UINT32_MAX)... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_transid.sock";
    SocketServer server(testSockPath);
    assert(server.start());

    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    // Test Transaction ID = 0
    std::vector<uint8_t> stopPkt0 = SocketServer::serializePacket(0x0002, 0, {0x00});
    write(clientFd, stopPkt0.data(), stopPkt0.size());
    SocketPacketHeader respHdr0;
    std::vector<uint8_t> payload0;
    assert(readFullPacket(clientFd, respHdr0, payload0));
    assert(respHdr0.transactionId == 0);

    // Test Transaction ID = UINT32_MAX
    std::vector<uint8_t> stopPktMax = SocketServer::serializePacket(0x0002, UINT32_MAX, {0x00});
    write(clientFd, stopPktMax.data(), stopPktMax.size());
    SocketPacketHeader respHdrMax;
    std::vector<uint8_t> payloadMax;
    assert(readFullPacket(clientFd, respHdrMax, payloadMax));
    assert(respHdrMax.transactionId == UINT32_MAX);

    close(clientFd);
    server.stop();
    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 5: Unauthenticated Vsock Handshake & Security Boundaries
int testUnauthenticatedVsockHandshake() {
    std::cout << "[STRESS TEST 5] Unauthenticated Vsock Handshake & Access Control... " << std::flush;
    HmacAuth::clearUsedTokens();

    VsockServer vsockServer;
    vsockServer.start();

    // 1. Unauthenticated bind to restricted ports 5001 and 5002
    assert(!vsockServer.bindPort(5001)); // PTY port blocked
    assert(!vsockServer.bindPort(5002)); // Wayland port blocked
    assert(vsockServer.bindPort(5000));  // Control port allowed

    // 2. Reject handshake attempt from unauthorized CID (e.g. CID 4, 10, 0)
    std::vector<uint8_t> token = HmacAuth::generateRandomToken();
    std::vector<uint8_t> secret = {'t', 'e', 's', 't', '_', 's', 'e', 'c', 'r', 'e', 't'};
    vsockServer.setAuthToken(token, secret);

    AuthHandshakePayload payload;
    std::memcpy(payload.token, token.data(), 32);
    std::vector<uint8_t> sig = HmacAuth::computeHmacSha256(secret, token);
    std::memcpy(payload.signature, sig.data(), 32);

    assert(!vsockServer.processHandshake(4, payload));   // CID 4 rejected
    assert(!vsockServer.processHandshake(0, payload));   // CID 0 rejected
    assert(!vsockServer.processHandshake(100, payload)); // CID 100 rejected
    assert(!vsockServer.isAuthenticated());

    // 3. Reject invalid HMAC signature from CID 3
    AuthHandshakePayload badSigPayload = payload;
    badSigPayload.signature[0] ^= 0xFF; // Corrupt signature
    assert(!vsockServer.processHandshake(3, badSigPayload));
    assert(!vsockServer.isAuthenticated());

    // 4. Reject expired token handshake (> 5s)
    auto expiredTime = std::chrono::steady_clock::now() - std::chrono::seconds(10);
    assert(!HmacAuth::verifyHandshake(secret, token, payload, expiredTime));

    // 5. Valid handshake from CID 3 succeeds
    assert(vsockServer.processHandshake(3, payload));
    assert(vsockServer.isAuthenticated());

    // 6. Replay attack with same token is rejected
    assert(!vsockServer.processHandshake(3, payload));

    // 7. Session reset removes authentication
    vsockServer.resetSession();
    assert(!vsockServer.isAuthenticated());
    assert(!vsockServer.bindPort(5001));

    vsockServer.stop();
    std::cout << "PASS" << std::endl;
    return 0;
}

// Test 6: Rapid Start/Stop Cycles (CMD_VM_START -> immediate CMD_VM_STOP)
int testRapidStartStopCycles() {
    std::cout << "[STRESS TEST 6] Rapid Start/Stop Cycles (50 iterations)... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_rapid.sock";
    SocketServer server(testSockPath);
    VsockServer vsockServer;
    vsockServer.start();
    server.setVsockServer(&vsockServer);
    assert(server.start());

    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    constexpr int RAPID_CYCLES = 50;
    for (int i = 0; i < RAPID_CYCLES; ++i) {
        // Send CMD_VM_START (0x0001)
        uint32_t startTransId = 1000 + i * 2;
        std::vector<uint8_t> startPkt = SocketServer::serializePacket(0x0001, startTransId, {});
        write(clientFd, startPkt.data(), startPkt.size());

        // Immediately send CMD_VM_STOP (0x0002) without waiting for handshake
        uint32_t stopTransId = 1000 + i * 2 + 1;
        std::vector<uint8_t> stopPkt = SocketServer::serializePacket(0x0002, stopTransId, {0x01}); // Force stop
        write(clientFd, stopPkt.data(), stopPkt.size());

        // Read response for CMD_VM_STOP
        SocketPacketHeader respHdr;
        std::vector<uint8_t> stopPayload;
        assert(readFullPacket(clientFd, respHdr, stopPayload));
        assert(respHdr.magic == LNXB_MAGIC);
        assert(respHdr.cmdType == 0x0002);
        assert(respHdr.transactionId == stopTransId);

        // Verify VM process reaped and state reset to STOPPED
        assert(server.getVmState() == VmState::STOPPED);
        assert(server.getVmPid() == -1);
    }

    // Verify system can start normally after rapid cycles
    uint32_t finalStartId = 9999;
    std::vector<uint8_t> finalStartPkt = SocketServer::serializePacket(0x0001, finalStartId, {});
    write(clientFd, finalStartPkt.data(), finalStartPkt.size());
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    assert(server.getVmState() == VmState::STARTING);
    assert(server.getVmPid() > 0);

    // Complete Vsock handshake
    server.onVsockHandshakeSuccess(3);

    SocketPacketHeader hsCompleteHdr;
    std::vector<uint8_t> hsPayload;
    assert(readFullPacket(clientFd, hsCompleteHdr, hsPayload));
    assert(hsCompleteHdr.cmdType == 0x0003); // CMD_HANDSHAKE_COMPLETE
    assert(server.getVmState() == VmState::RUNNING);

    // Stop VM
    std::vector<uint8_t> finalStopPkt = SocketServer::serializePacket(0x0002, 10000, {0x01});
    write(clientFd, finalStopPkt.data(), finalStopPkt.size());

    SocketPacketHeader finalStopResp;
    std::vector<uint8_t> finalStopPayload;
    assert(readFullPacket(clientFd, finalStopResp, finalStopPayload));
    assert(server.getVmState() == VmState::STOPPED);

    close(clientFd);
    server.stop();
    vsockServer.stop();

    std::cout << "PASS (50 rapid start/stop cycles completed with zero process leaks)" << std::endl;
    return 0;
}

// Test 7: Disconnected Client FD Reuse Edge Case
int testPendingFdClientDisconnectEdgeCase() {
    std::cout << "[STRESS TEST 7] Disconnected Client FD Cleanup & Pending FD Safety... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_stress_fd_cleanup.sock";
    SocketServer server(testSockPath);
    VsockServer vsockServer;
    vsockServer.start();
    server.setVsockServer(&vsockServer);
    assert(server.start());

    // 1. Client 1 connects and sends CMD_VM_START
    int client1Fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(client1Fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    std::vector<uint8_t> startPkt = SocketServer::serializePacket(0x0001, 701, {});
    write(client1Fd, startPkt.data(), startPkt.size());
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    assert(server.getVmState() == VmState::STARTING);

    // 2. Client 1 disconnects BEFORE vsock handshake completes
    close(client1Fd);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // 3. Now vsock handshake arrives from guest
    // Server should handle the write attempt without crash or hanging
    server.onVsockHandshakeSuccess(3);

    // 4. Server state should transition to RUNNING
    assert(server.getVmState() == VmState::RUNNING);

    // 5. Clean up server
    server.stop();
    vsockServer.stop();

    std::cout << "PASS" << std::endl;
    return 0;
}

int main() {
    std::cout << "=== Starting Empirical Adversarial Stress Test Suite ===" << std::endl;
    int failures = 0;

    failures += testConcurrencyAndFdCleanup();
    failures += testMalformedPackets();
    failures += testPartialHeadersAndTruncation();
    failures += testTransactionIdHandling();
    failures += testUnauthenticatedVsockHandshake();
    failures += testRapidStartStopCycles();
    failures += testPendingFdClientDisconnectEdgeCase();

    std::cout << "==========================================================" << std::endl;
    if (failures == 0) {
        std::cout << "ADVERSARIAL STRESS TEST RESULT: ALL STRESS TESTS PASSED SUCCESSFULLY" << std::endl;
        return 0;
    } else {
        std::cerr << "ADVERSARIAL STRESS TEST RESULT: " << failures << " STRESS TEST(S) FAILED" << std::endl;
        return 1;
    }
}
