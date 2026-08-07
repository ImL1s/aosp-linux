/*
 * Empirical Stress Test Harness for Challenger 2 (M1 Iteration 3)
 * Author: Empirical Challenger 2
 * Objective: Empirically stress-test socket_server.cpp connection bursts and stop() teardown under active load.
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

using namespace android::system::linux_bridge;

struct TestResult {
    std::string testName;
    bool passed;
    std::string message;
};

std::vector<TestResult> gResults;

void logResult(const std::string& name, bool passed, const std::string& msg = "") {
    gResults.push_back({name, passed, msg});
    std::cout << "[R3_STRESS] " << name << " -> " << (passed ? "PASS" : "FAIL");
    if (!msg.empty()) {
        std::cout << " (" << msg << ")";
    }
    std::cout << std::endl << std::flush;
}

// Helper to run connection burst test for N clients
bool runConnectionBurstTest(int numClients, const std::string& sockPath) {
    SocketServer server(sockPath);
    if (!server.start()) return false;

    std::vector<std::thread> threads;
    std::atomic<int> successCount{0};
    std::atomic<int> failCount{0};

    for (int i = 0; i < numClients; ++i) {
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

    server.stop();
    return (failCount.load() == 0) && (successCount.load() == numClients);
}

// 1. Connection Burst: 50 Clients
void testBurst50Clients() {
    std::string sockPath = "/tmp/r3_burst_50.sock";
    bool ok = runConnectionBurstTest(50, sockPath);
    logResult("Burst50Clients", ok, "50 simultaneous connections burst with SOMAXCONN");
}

// 2. Connection Burst: 100 Clients
void testBurst100Clients() {
    std::string sockPath = "/tmp/r3_burst_100.sock";
    bool ok = runConnectionBurstTest(100, sockPath);
    logResult("Burst100Clients", ok, "100 simultaneous connections burst");
}

// 3. Connection Burst: 200 Clients
void testBurst200Clients() {
    std::string sockPath = "/tmp/r3_burst_200.sock";
    bool ok = runConnectionBurstTest(200, sockPath);
    logResult("Burst200Clients", ok, "200 simultaneous connections burst");
}

// 4. Rapid Repeated Bursts (5 waves of 50 = 250 total)
void testRapidRepeatedBursts() {
    std::string sockPath = "/tmp/r3_burst_repeated.sock";
    SocketServer server(sockPath);
    assert(server.start());

    bool allOk = true;
    for (int wave = 0; wave < 5; ++wave) {
        std::vector<std::thread> threads;
        std::atomic<int> successCount{0};
        std::atomic<int> failCount{0};

        for (int i = 0; i < 50; ++i) {
            threads.emplace_back([&sockPath, &successCount, &failCount, wave, i]() {
                int fd = socket(AF_UNIX, SOCK_STREAM, 0);
                if (fd < 0) { failCount++; return; }

                struct sockaddr_un addr;
                std::memset(&addr, 0, sizeof(addr));
                addr.sun_family = AF_UNIX;
                std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);

                if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
                    failCount++;
                    close(fd);
                    return;
                }

                auto pkt = SocketServer::serializePacket(0x0001, static_cast<uint32_t>(wave * 50 + i + 1), {});
                if (write(fd, pkt.data(), pkt.size()) == static_cast<ssize_t>(pkt.size())) {
                    SocketPacketHeader respHeader;
                    if (SocketServer::readFull(fd, &respHeader, sizeof(respHeader))) {
                        if (ntohl(respHeader.magic) == LNXB_MAGIC && ntohs(respHeader.cmdType) == 0x0003) {
                            successCount++;
                        } else { failCount++; }
                    } else { failCount++; }
                } else { failCount++; }
                close(fd);
            });
        }

        for (auto& t : threads) if (t.joinable()) t.join();
        if (failCount.load() > 0 || successCount.load() != 50) {
            allOk = false;
            break;
        }
    }

    server.stop();
    logResult("RapidRepeatedBursts", allOk, "5 waves of 50 clients (250 connections total)");
}

// 5. Active Streaming Teardown (stop() during continuous heavy client read/write)
void testActiveStreamingTeardown() {
    std::string sockPath = "/tmp/r3_active_teardown.sock";
    SocketServer server(sockPath);
    assert(server.start());

    constexpr int NUM_CLIENTS = 20;
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

            std::vector<uint8_t> payload(1024, 'X');
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

    // Let clients stream data heavily for 100ms
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    // Call stop() while streaming is active
    auto stopStart = std::chrono::steady_clock::now();
    server.stop();
    auto stopEnd = std::chrono::steady_clock::now();
    auto stopDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(stopEnd - stopStart).count();

    keepStreaming.store(false);

    for (auto& t : clientThreads) {
        if (t.joinable()) t.join();
    }

    logResult("ActiveStreamingTeardown", !server.isRunning(),
              "Stopped server in " + std::to_string(stopDurationMs) + "ms during active stream of " +
              std::to_string(packetsSent.load()) + " packets across 20 clients");
}

// 6. Immediate Server Object Destruction after stop() while client threads exit
void testDestructionRaceCondition() {
    std::string sockPath = "/tmp/r3_destruction_race.sock";
    
    auto* server = new SocketServer(sockPath);
    assert(server->start());

    constexpr int NUM_CLIENTS = 20;
    std::vector<std::thread> clientThreads;

    for (int i = 0; i < NUM_CLIENTS; ++i) {
        clientThreads.emplace_back([&sockPath, i]() {
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

            auto pkt = SocketServer::serializePacket(0x0001, static_cast<uint32_t>(i + 1), {});
            write(fd, pkt.data(), pkt.size());

            // Block in read waiting for shutdown
            SocketPacketHeader resp;
            SocketServer::readFull(fd, &resp, sizeof(resp));
            close(fd);
        });
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    server->stop();
    delete server; // Object destroyed immediately!

    for (auto& t : clientThreads) {
        if (t.joinable()) t.join();
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    logResult("DestructionRaceCondition", true, "Server destroyed immediately after stop() without crash");
}

int main() {
    signal(SIGPIPE, SIG_IGN); // Ignore SIGPIPE signal during socket teardown stress testing

    std::cout << "==========================================================" << std::endl;
    std::cout << "  EMPIRICAL CHALLENGER 2 — ITERATION 3 STRESS HARNESS    " << std::endl;
    std::cout << "==========================================================" << std::endl;

    testBurst50Clients();
    testBurst100Clients();
    testBurst200Clients();
    testRapidRepeatedBursts();
    testActiveStreamingTeardown();
    testDestructionRaceCondition();

    std::cout << "==========================================================" << std::endl;
    int failCount = 0;
    for (const auto& res : gResults) {
        if (!res.passed) failCount++;
    }
    std::cout << "TOTAL TESTS: " << gResults.size() << " | PASSED: " << (gResults.size() - failCount)
              << " | FAILED: " << failCount << std::endl;
    return failCount;
}
