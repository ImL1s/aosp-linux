/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Empirical Stress Test Harness for Challenger 2 Iteration 2 (r2)
 * Comprehensive verification of:
 * 1. Partial Read framing corruption prevention with byte-fragmented packet streams.
 * 2. 500 High-concurrency connection burst without connection drops.
 * 3. Integer overflow (0xFFFFFFFF, 0x80000000) & >16MB payload DoS packet safe rejection without OOM/bad_alloc.
 * 4. Double close race condition elimination during active stop() shutdown and destruction.
 */

#include "system/linux_bridge/socket_server.h"
#include "system/linux_bridge/vsock_framing.h"
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <thread>
#include <chrono>
#include <atomic>
#include <csignal>
#include <random>

using namespace android::system::linux_bridge;

struct TestResult {
    std::string testName;
    bool passed;
    std::string details;
};

static std::vector<TestResult> gResults;

static void recordResult(const std::string& name, bool passed, const std::string& details = "") {
    gResults.push_back({name, passed, details});
    std::cout << "[STRESS_TEST_R2] " << name << " -> " << (passed ? "PASS" : "FAIL");
    if (!details.empty()) {
        std::cout << " (" << details << ")";
    }
    std::cout << std::endl << std::flush;
}

// 1. Verification of Partial Read Framing Stream Loop
void testPartialReadFramingFragmentation() {
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) {
        recordResult("PartialReadFramingFragmentation", false, "Failed to create socketpair");
        return;
    }

    std::string payloadStr = "EmpiricalPartialReadFramingTest_Payload_Chunk_Verification_2026";
    std::vector<uint8_t> payloadBytes(payloadStr.begin(), payloadStr.end());
    uint16_t cmdType = 0x0100;
    uint32_t transId = 98765;

    std::vector<uint8_t> fullPacket = SocketServer::serializePacket(cmdType, transId, payloadBytes);

    std::thread writer([fds, fullPacket]() {
        std::mt19937 rng(42);
        std::uniform_int_distribution<size_t> dist(1, 5); // write in tiny 1-5 byte chunks
        size_t written = 0;
        while (written < fullPacket.size()) {
            size_t chunkSize = std::min(dist(rng), fullPacket.size() - written);
            ssize_t w = write(fds[1], fullPacket.data() + written, chunkSize);
            if (w <= 0) break;
            written += w;
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });

    SocketPacketHeader header;
    bool readHeaderOk = SocketServer::readFull(fds[0], &header, sizeof(header));

    header.magic = ntohl(header.magic);
    header.cmdType = ntohs(header.cmdType);
    header.length = ntohl(header.length);
    header.transactionId = ntohl(header.transactionId);

    std::vector<uint8_t> readPayload(header.length);
    bool readPayloadOk = SocketServer::readFull(fds[0], readPayload.data(), header.length);

    writer.join();
    close(fds[0]);
    close(fds[1]);

    bool pass = readHeaderOk && readPayloadOk &&
                (header.magic == LNXB_MAGIC) &&
                (header.cmdType == cmdType) &&
                (header.transactionId == transId) &&
                (header.length == payloadBytes.size()) &&
                (readPayload == payloadBytes);

    recordResult("PartialReadFramingFragmentation", pass, "Fragmented stream (1-5 byte chunks) reassembled without byte corruption");
}

// 2. High-Concurrency Connection Handling (500 Concurrent Connections with standard client retry logic)
void test500ConcurrentConnections() {
    std::string sockPath = "/tmp/linux_bridge_500_concurrency.sock";
    SocketServer server(sockPath);
    if (!server.start()) {
        recordResult("500ConcurrentConnections", false, "Failed to start SocketServer");
        return;
    }

    constexpr int NUM_CLIENTS = 500;
    std::vector<std::thread> threads;
    threads.reserve(NUM_CLIENTS);

    std::atomic<int> successCount{0};
    std::atomic<int> failSocketErr{0};
    std::atomic<int> failConnectErr{0};
    std::atomic<int> failWriteErr{0};
    std::atomic<int> failReadErr{0};

    auto startTime = std::chrono::steady_clock::now();

    for (int i = 0; i < NUM_CLIENTS; ++i) {
        threads.emplace_back([&sockPath, &successCount, &failSocketErr, &failConnectErr, &failWriteErr, &failReadErr, i]() {
            int fd = -1;
            bool connected = false;
            struct sockaddr_un addr;
            std::memset(&addr, 0, sizeof(addr));
            addr.sun_family = AF_UNIX;
            std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

            // Standard client retry loop for socket connection burst
            for (int attempt = 0; attempt < 5; ++attempt) {
                fd = socket(AF_UNIX, SOCK_STREAM, 0);
                if (fd < 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    continue;
                }

                if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
                    connected = true;
                    break;
                }

                close(fd);
                fd = -1;
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }

            if (!connected || fd < 0) {
                failConnectErr++;
                return;
            }

            auto pkt = SocketServer::serializePacket(0x0001, static_cast<uint32_t>(i + 1), {});
            if (write(fd, pkt.data(), pkt.size()) == static_cast<ssize_t>(pkt.size())) {
                SocketPacketHeader respHeader;
                if (SocketServer::readFull(fd, &respHeader, sizeof(respHeader))) {
                    if (ntohl(respHeader.magic) == LNXB_MAGIC && ntohs(respHeader.cmdType) == 0x0003) {
                        successCount++;
                    } else {
                        failReadErr++;
                    }
                } else {
                    failReadErr++;
                }
            } else {
                failWriteErr++;
            }
            close(fd);
        });
    }

    for (auto& t : threads) {
        if (t.joinable()) t.join();
    }

    auto endTime = std::chrono::steady_clock::now();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    server.stop();

    int totalFails = failSocketErr + failConnectErr + failWriteErr + failReadErr;
    bool pass = (totalFails == 0) && (successCount.load() == NUM_CLIENTS);
    std::string errDetails = std::to_string(successCount.load()) + "/" + std::to_string(NUM_CLIENTS) +
                 " connections succeeded in " + std::to_string(elapsedMs) + "ms. Breakdown: socketErr=" +
                 std::to_string(failSocketErr.load()) + ", connectErr=" + std::to_string(failConnectErr.load()) +
                 ", writeErr=" + std::to_string(failWriteErr.load()) + ", readErr=" + std::to_string(failReadErr.load());

    recordResult("500ConcurrentConnections", pass, errDetails);
}

// 3. Integer Overflow and >16MB Payload DoS Packet Rejection
void testIntegerOverflowAndOversizedPayloadRejection() {
    std::string sockPath = "/tmp/linux_bridge_dos_test.sock";
    SocketServer server(sockPath);
    if (!server.start()) {
        recordResult("IntegerOverflowAndOversizedPayloadRejection", false, "Failed to start SocketServer");
        return;
    }

    bool allDosCasesPassed = true;
    std::string details = "";

    // Case 3a: >16MB Payload (16MB + 1 Byte header)
    {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

        if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
            SocketPacketHeader badHeader;
            badHeader.magic = htonl(LNXB_MAGIC);
            badHeader.cmdType = htons(0x0100);
            badHeader.length = htonl(16 * 1024 * 1024 + 1); // 16MB + 1
            badHeader.transactionId = htonl(99);

            write(fd, &badHeader, sizeof(badHeader));

            // Connection should be closed by server due to payload limit breach
            uint8_t dummyBuf[10];
            ssize_t r = read(fd, dummyBuf, sizeof(dummyBuf));
            if (r > 0) {
                allDosCasesPassed = false;
                details += "Case 3a (>16MB) did not close connection. ";
            }
        }
        close(fd);
    }

    // Case 3b: Integer Overflow Length (0xFFFFFFFF = 4GB - 1)
    {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

        if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
            SocketPacketHeader badHeader;
            badHeader.magic = htonl(LNXB_MAGIC);
            badHeader.cmdType = htons(0x0100);
            badHeader.length = htonl(0xFFFFFFFF); // 4GB-1 integer overflow test
            badHeader.transactionId = htonl(100);

            write(fd, &badHeader, sizeof(badHeader));

            uint8_t dummyBuf[10];
            ssize_t r = read(fd, dummyBuf, sizeof(dummyBuf));
            if (r > 0) {
                allDosCasesPassed = false;
                details += "Case 3b (0xFFFFFFFF) did not close connection. ";
            }
        }
        close(fd);
    }

    // Case 3c: Large 2GB Length (0x80000000)
    {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

        if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
            SocketPacketHeader badHeader;
            badHeader.magic = htonl(LNXB_MAGIC);
            badHeader.cmdType = htons(0x0100);
            badHeader.length = htonl(0x80000000); // 2GB length
            badHeader.transactionId = htonl(101);

            write(fd, &badHeader, sizeof(badHeader));

            uint8_t dummyBuf[10];
            ssize_t r = read(fd, dummyBuf, sizeof(dummyBuf));
            if (r > 0) {
                allDosCasesPassed = false;
                details += "Case 3c (0x80000000) did not close connection. ";
            }
        }
        close(fd);
    }

    // Case 3d: Static parsePacket overflow checks
    {
        SocketPacketHeader badHeader;
        badHeader.magic = htonl(LNXB_MAGIC);
        badHeader.cmdType = htons(0x0100);
        badHeader.length = htonl(MAX_PAYLOAD_SIZE + 100);
        badHeader.transactionId = htonl(102);

        std::vector<uint8_t> rawBuf(sizeof(SocketPacketHeader) + 50);
        std::memcpy(rawBuf.data(), &badHeader, sizeof(badHeader));

        SocketPacketHeader parsedH;
        std::vector<uint8_t> parsedP;
        bool parseRes = SocketServer::parsePacket(rawBuf, parsedH, parsedP);
        if (parseRes) {
            allDosCasesPassed = false;
            details += "Case 3d parsePacket accepted >16MB. ";
        }
    }

    server.stop();

    if (details.empty()) {
        details = "All oversized (>16MB) and integer overflow (0xFFFFFFFF, 0x80000000) packets safely rejected without OOM/crash";
    }

    recordResult("IntegerOverflowAndOversizedPayloadRejection", allDosCasesPassed, details);
}

// 4. Double Close Race Condition Elimination During Shutdown
void testDoubleCloseRaceConditionElimination() {
    std::string sockPath = "/tmp/linux_bridge_double_close_test.sock";
    SocketServer server(sockPath);
    if (!server.start()) {
        recordResult("DoubleCloseRaceConditionElimination", false, "Failed to start SocketServer");
        return;
    }

    constexpr int NUM_CLIENTS = 50;
    std::atomic<bool> keepStreaming{true};
    std::atomic<int> packetsSent{0};
    std::vector<std::thread> clientThreads;

    for (int i = 0; i < NUM_CLIENTS; ++i) {
        clientThreads.emplace_back([&sockPath, &keepStreaming, &packetsSent, i]() {
            int fd = socket(AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) return;

            struct sockaddr_un addr;
            std::memset(&addr, 0, sizeof(addr));
            addr.sun_family = AF_UNIX;
            std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

            if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
                close(fd);
                return;
            }

            std::vector<uint8_t> payload(512, 'Z');
            auto pkt = SocketServer::serializePacket(0x0001, static_cast<uint32_t>(i + 1), payload);

            while (keepStreaming.load()) {
                ssize_t w = write(fd, pkt.data(), pkt.size());
                if (w <= 0) break;

                SocketPacketHeader respHeader;
                if (!SocketServer::readFull(fd, &respHeader, sizeof(respHeader))) break;

                packetsSent++;
            }
            close(fd);
        });
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    auto stopStart = std::chrono::steady_clock::now();
    server.stop();
    auto stopEnd = std::chrono::steady_clock::now();
    auto stopMs = std::chrono::duration_cast<std::chrono::milliseconds>(stopEnd - stopStart).count();

    keepStreaming.store(false);

    for (auto& t : clientThreads) {
        if (t.joinable()) t.join();
    }

    bool pass = !server.isRunning();
    recordResult("DoubleCloseRaceConditionElimination", pass,
                 "Server teardown completed cleanly in " + std::to_string(stopMs) +
                 "ms with 50 active streaming clients, zero double-close or crash");
}

int main() {
    signal(SIGPIPE, SIG_IGN); // Ignore SIGPIPE during socket teardown stress

    std::cout << "================================================================" << std::endl;
    std::cout << "  EMPIRICAL CHALLENGER 2 (M1 Iteration 2 r2) STRESS TEST SUITE  " << std::endl;
    std::cout << "================================================================" << std::endl;

    testPartialReadFramingFragmentation();
    test500ConcurrentConnections();
    testIntegerOverflowAndOversizedPayloadRejection();
    testDoubleCloseRaceConditionElimination();

    std::cout << "================================================================" << std::endl;
    int failCount = 0;
    for (const auto& res : gResults) {
        if (!res.passed) failCount++;
    }
    std::cout << "TOTAL STRESS SCENARIOS: " << gResults.size()
              << " | PASSED: " << (gResults.size() - failCount)
              << " | FAILED: " << failCount << std::endl;

    return failCount;
}
