#include "system/linux_bridge/socket_server.h"
#include "system/linux_bridge/vsock_server.h"
#include "system/linux_bridge/hmac_auth.h"
#include <iostream>
#include <cassert>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <csignal>
#include <cerrno>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <thread>
#include <chrono>

using namespace android::system::linux_bridge;

void testConcurrentVmStartRejection() {
    std::cout << "[CPP STRESS 1] Testing Duplicate CMD_VM_START Rejection... " << std::flush;
    setenv("TEST_MODE", "1", 1);
    HmacAuth::clearUsedTokens();

    std::string testSockPath = "/tmp/linux_bridge_cpp_stress_1.sock";
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

    // Send 1st CMD_VM_START (0x0001)
    std::vector<uint8_t> startPacket1 = SocketServer::serializePacket(0x0001, 100, {});
    write(clientFd, startPacket1.data(), startPacket1.size());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    assert(server.getVmState() == VmState::STARTING);

    // Send 2nd CMD_VM_START while still STARTING
    std::vector<uint8_t> startPacket2 = SocketServer::serializePacket(0x0001, 101, {});
    write(clientFd, startPacket2.data(), startPacket2.size());

    // Read error response for 2nd request (expected CMD_VM_START_FAILED 0x0004)
    SocketPacketHeader errHeader;
    assert(SocketServer::readFull(clientFd, &errHeader, sizeof(errHeader)));
    assert(ntohl(errHeader.magic) == LNXB_MAGIC);
    assert(ntohs(errHeader.cmdType) == 0x0004); // CMD_VM_START_FAILED
    assert(ntohl(errHeader.transactionId) == 101);

    // Clean stop
    server.stop();
    vsockServer.stop();
    close(clientFd);
    std::cout << "PASS" << std::endl;
}

void testSIGTERMTerminationVerification() {
    std::cout << "[CPP STRESS 2] Testing Child Process Teardown & SIGTERM/SIGKILL Cleanup... " << std::flush;
    setenv("TEST_MODE", "1", 1);

    std::string testSockPath = "/tmp/linux_bridge_cpp_stress_2.sock";
    SocketServer server(testSockPath);
    assert(server.start());

    int clientFd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, testSockPath.c_str(), sizeof(addr.sun_path) - 1);
    assert(connect(clientFd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);

    std::vector<uint8_t> startPacket = SocketServer::serializePacket(0x0001, 200, {});
    write(clientFd, startPacket.data(), startPacket.size());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    pid_t spawnedPid = server.getVmPid();
    assert(spawnedPid > 0);
    assert(kill(spawnedPid, 0) == 0); // Process is running

    // Stop process via server.stopVmProcess(true)
    server.stopVmProcess(true);

    assert(server.getVmState() == VmState::STOPPED);
    assert(server.getVmPid() == -1);

    // Verify process is dead
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    assert(kill(spawnedPid, 0) == -1 && errno == ESRCH);

    server.stop();
    close(clientFd);
    std::cout << "PASS" << std::endl;
}

int main() {
    std::cout << "=== Running C++ SocketServer Adversarial Stress Harness ===" << std::endl;
    testConcurrentVmStartRejection();
    testSIGTERMTerminationVerification();
    std::cout << "=== ALL C++ STRESS TESTS PASSED SUCCESSFULLY ===" << std::endl;
    return 0;
}
