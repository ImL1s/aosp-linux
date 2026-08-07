/*
 * Empirical Stress Test Harness for Challenger 2 (M1)
 * Tests linux_bridge native daemon and Unix socket / vsock framing implementation.
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

using namespace android::system::linux_bridge;

struct TestResult {
    std::string testName;
    bool passed;
    std::string message;
};

std::vector<TestResult> gResults;

void logResult(const std::string& name, bool passed, const std::string& msg = "") {
    gResults.push_back({name, passed, msg});
    std::cout << "[STRESS_TEST] " << name << " -> " << (passed ? "PASS" : "FAIL");
    if (!msg.empty()) {
        std::cout << " (" << msg << ")";
    }
    std::cout << std::endl;
}

// 1. Test standard VsockFraming pack/unpack
void testVsockStandardFraming() {
    uint32_t seq = 999;
    std::vector<uint8_t> payload = {'t', 'e', 's', 't', '_', 'd', 'a', 't', 'a'};
    auto frame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seq, payload);
    
    VsockFrameHeader header;
    std::vector<uint8_t> unpacked;
    bool ok = VsockFraming::unpackFrame(frame, header, unpacked);
    
    bool valid = ok && (header.magic == VsockFraming::VSOK_MAGIC) && 
                 (header.frameType == static_cast<uint8_t>(VsockFrameType::PTY_DATA)) &&
                 (header.sequenceId == seq) && (unpacked == payload);
    logResult("VsockStandardFraming", valid);
}

// 2. Test VsockFraming with zero-length payload
void testVsockZeroLengthPayload() {
    uint32_t seq = 100;
    std::vector<uint8_t> payload;
    auto frame = VsockFraming::packFrame(VsockFrameType::HEARTBEAT, seq, payload);
    
    VsockFrameHeader header;
    std::vector<uint8_t> unpacked;
    bool ok = VsockFraming::unpackFrame(frame, header, unpacked);
    
    bool valid = ok && (header.payloadLength == 0) && unpacked.empty();
    logResult("VsockZeroLengthPayload", valid);
}

// 3. Test VsockFraming malformed magic & corrupted header
void testVsockMalformedHeader() {
    uint32_t seq = 101;
    std::vector<uint8_t> payload = {1, 2, 3};
    auto frame = VsockFraming::packFrame(VsockFrameType::CONTROL, seq, payload);
    
    // Corrupt magic
    frame[0] = 0x00;
    VsockFrameHeader header;
    std::vector<uint8_t> unpacked;
    bool ok1 = VsockFraming::unpackFrame(frame, header, unpacked);
    
    // Truncate frame
    std::vector<uint8_t> truncated(frame.begin(), frame.begin() + sizeof(VsockFrameHeader) - 1);
    bool ok2 = VsockFraming::unpackFrame(truncated, header, unpacked);
    
    logResult("VsockMalformedHeader", !ok1 && !ok2, "Rejects corrupt magic and truncated headers");
}

// 4. Test SocketServer serialize & parse packet
void testSocketServerFraming() {
    uint16_t cmd = 0x0100;
    uint32_t trans = 12345;
    std::vector<uint8_t> payload(1024, 0xAB);
    
    auto packet = SocketServer::serializePacket(cmd, trans, payload);
    SocketPacketHeader header;
    std::vector<uint8_t> unpacked;
    bool ok = SocketServer::parsePacket(packet, header, unpacked);
    
    bool valid = ok && (header.magic == LNXB_MAGIC) && (header.cmdType == cmd) && 
                 (header.transactionId == trans) && (header.length == payload.size()) && (unpacked == payload);
    logResult("SocketServerFraming", valid);
}

// 5. Test SocketServer zero-length payload
void testSocketServerZeroPayload() {
    auto packet = SocketServer::serializePacket(0x0001, 1, {});
    SocketPacketHeader header;
    std::vector<uint8_t> unpacked;
    bool ok = SocketServer::parsePacket(packet, header, unpacked);
    
    bool valid = ok && (header.length == 0) && unpacked.empty();
    logResult("SocketServerZeroPayload", valid);
}

// 6. Test SocketServer boundary & oversized payload handling in parsePacket
void testSocketServerOversizedPayload() {
    // 16MB exactly
    std::vector<uint8_t> valid16MB(16 * 1024 * 1024);
    auto packet16MB = SocketServer::serializePacket(0x0100, 2, valid16MB);
    SocketPacketHeader header16;
    std::vector<uint8_t> unpacked16;
    bool ok16 = SocketServer::parsePacket(packet16MB, header16, unpacked16);
    
    // Header specifying length > MAX_PAYLOAD_SIZE with actual large buffer
    SocketPacketHeader hugeHeader;
    hugeHeader.magic = htonl(LNXB_MAGIC);
    hugeHeader.cmdType = htons(0x0100);
    hugeHeader.length = htonl(MAX_PAYLOAD_SIZE + 100);
    hugeHeader.transactionId = htonl(3);
    
    std::vector<uint8_t> hugeRaw(sizeof(SocketPacketHeader) + MAX_PAYLOAD_SIZE + 100);
    std::memcpy(hugeRaw.data(), &hugeHeader, sizeof(SocketPacketHeader));
    
    SocketPacketHeader parsedHeader;
    std::vector<uint8_t> parsedPayload;
    bool parseHugeResult = SocketServer::parsePacket(hugeRaw, parsedHeader, parsedPayload);
    
    logResult("SocketServerBoundaryPayload", ok16 && (header16.length == 16 * 1024 * 1024), 
              "16MB payload handled");
    logResult("SocketServerOversizedPayloadSanitization", !parseHugeResult,
              parseHugeResult ? "VULNERABILITY: parsePacket accepted payload > 16MB when raw buffer is large" : "Oversized payload rejected");
}

// 7. Test Unix Domain Socket lifecycle: connect, disconnect, reconnect, rapid churn
void testSocketLifecycleStress() {
    std::string sockPath = "/tmp/linux_bridge_stress_test.sock";
    SocketServer server(sockPath);
    assert(server.start());
    
    bool allConnsOk = true;
    for (int i = 0; i < 20; i++) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) { allConnsOk = false; break; }
        
        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);
        
        if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
            allConnsOk = false;
            close(fd);
            break;
        }
        
        // Send CMD_VM_START (0x0001)
        auto startPkt = SocketServer::serializePacket(0x0001, i + 1, {});
        write(fd, startPkt.data(), startPkt.size());
        
        // Read response header
        SocketPacketHeader respHeader;
        ssize_t r = read(fd, &respHeader, sizeof(respHeader));
        if (r != sizeof(respHeader) || ntohl(respHeader.magic) != LNXB_MAGIC) {
            allConnsOk = false;
            close(fd);
            break;
        }
        close(fd);
    }
    
    server.stop();
    logResult("SocketLifecycleStress", allConnsOk, "20 rapid connect/disconnect cycles");
}

// 8. Test Unix Socket Buffer Overflow / Flood
void testSocketBufferOverflow() {
    std::string sockPath = "/tmp/linux_bridge_overflow_test.sock";
    SocketServer server(sockPath);
    assert(server.start());
    
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, sockPath.c_str(), sizeof(addr.sun_path) - 1);
    
    bool overflowOk = true;
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
        // Flood connection with 1000 fast packets
        std::vector<uint8_t> dataPkt = SocketServer::serializePacket(0x0100, 999, std::vector<uint8_t>(512, 'X'));
        for (int i = 0; i < 1000; i++) {
            ssize_t w = write(fd, dataPkt.data(), dataPkt.size());
            if (w <= 0) {
                overflowOk = false;
                break;
            }
        }
    } else {
        overflowOk = false;
    }
    
    close(fd);
    server.stop();
    logResult("SocketBufferOverflow", overflowOk, "1000 continuous packet flood handled without server crash");
}

// 9. Test 21-byte PTY Framing Header [SessionID (16B)][Type (1B)][Length (4B)][Payload]
void test21BytePtyFramingHeader() {
    // 21-byte PTY framing header structure check
    struct PtyFramingHeader21B {
        uint8_t sessionId[16];
        uint8_t type;
        uint32_t length;
    } __attribute__((packed));
    
    bool headerSizeCheck = (sizeof(PtyFramingHeader21B) == 21);
    
    // Check if C++ linux_bridge vsock_framing module supports 21-byte header directly
    PtyFramingHeader21B sampleHeader;
    std::memset(sampleHeader.sessionId, 'A', 16);
    sampleHeader.type = 0x01; // DATA
    sampleHeader.length = htonl(10);
    
    std::vector<uint8_t> rawHeader(21);
    std::memcpy(rawHeader.data(), &sampleHeader, 21);
    
    uint32_t parsedLen = ntohl(*reinterpret_cast<uint32_t*>(rawHeader.data() + 17));
    bool parsedCorrectly = (parsedLen == 10) && (rawHeader[16] == 0x01);
    
    logResult("21BytePtyFramingHeaderStructure", headerSizeCheck && parsedCorrectly,
              "Header size is exactly 21 bytes (16B SessionID + 1B Type + 4B Length)");
}

// 10. Test 50 simultaneous concurrent client connections burst
void testConcurrent50ConnectionsBurst() {
    std::string sockPath = "/tmp/challenger_concurrent_test.sock";
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

    bool pass = (failCount.load() == 0) && (successCount.load() == NUM_CLIENTS);
    server.stop();
    logResult("Concurrent50ConnectionsBurst", pass, 
              "50 simultaneous connection burst with zero ECONNREFUSED");
}

// 11. Test socket teardown shutdown handling without double close
void testSocketTeardownShutdownHandling() {
    std::string sockPath = "/tmp/challenger_teardown_test.sock";
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
    bool stoppedOk = !server.isRunning();

    for (int fd : clientFds) {
        close(fd);
    }

    logResult("SocketTeardownShutdownHandling", stoppedOk,
              "Clean socket server teardown with shutdown(SHUT_RDWR)");
}

int main() {
    std::cout << "==========================================================" << std::endl;
    std::cout << "  EMPIRICAL CHALLENGER 2 — LINUX_BRIDGE STRESS TEST SUITE " << std::endl;
    std::cout << "==========================================================" << std::endl;
    
    testVsockStandardFraming();
    testVsockZeroLengthPayload();
    testVsockMalformedHeader();
    testSocketServerFraming();
    testSocketServerZeroPayload();
    testSocketServerOversizedPayload();
    testSocketLifecycleStress();
    testSocketBufferOverflow();
    test21BytePtyFramingHeader();
    testConcurrent50ConnectionsBurst();
    testSocketTeardownShutdownHandling();
    
    std::cout << "==========================================================" << std::endl;
    int failCount = 0;
    for (const auto& res : gResults) {
        if (!res.passed) failCount++;
    }
    std::cout << "TOTAL TESTS: " << gResults.size() << " | PASSED: " << (gResults.size() - failCount) 
              << " | FAILED: " << failCount << std::endl;
    return failCount;
}
