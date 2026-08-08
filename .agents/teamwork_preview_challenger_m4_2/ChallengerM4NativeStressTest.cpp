/*
 * Challenger 2 Stress Test Harness for wayland_buffer_sharing.cpp
 */

#include "wayland_buffer_sharing.h"
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>

using namespace android::linux_bridge;

void testInvalidFdPassing(WaylandBufferSharingManager& manager) {
    GraphicBufferSpec spec{1, 1920, 1080, 1920, PixelFormat::ARGB_8888, -1};
    
    // 1. Negative FD in import
    try {
        manager.importDmaBufToHardwareBuffer(-1, spec);
        std::cerr << "[FAIL] Negative FD should have thrown std::invalid_argument\n";
    } catch (const std::invalid_argument& e) {
        std::cout << "[PASS] Negative FD rejected: " << e.what() << "\n";
    }

    // 2. Zero dimensions
    GraphicBufferSpec zeroWidthSpec{2, 0, 1080, 1920, PixelFormat::ARGB_8888, 10};
    try {
        manager.importDmaBufToHardwareBuffer(10, zeroWidthSpec);
        std::cerr << "[FAIL] Zero width should have thrown std::invalid_argument\n";
    } catch (const std::invalid_argument& e) {
        std::cout << "[PASS] Zero width rejected: " << e.what() << "\n";
    }

    // 3. exportDmaBufFd(0)
    int badFd = manager.exportDmaBufFd(0);
    assert(badFd == -1);
    std::cout << "[PASS] exportDmaBufFd(0) returned -1\n";
}

void testFenceReleaseLogic(WaylandBufferSharingManager& manager) {
    // 1. Negative fence FD
    bool res = manager.waitGpuFenceCompletion(-1, 1000000000ULL);
    assert(!res);
    std::cout << "[PASS] waitGpuFenceCompletion(-1) returned false\n";

    // 2. Timeout on dummy pipe fence
    int pfd[2];
    if (pipe(pfd) == 0) {
        try {
            // Wait 10ms on empty read pipe (timeout)
            manager.waitGpuFenceCompletion(pfd[0], 10000000ULL);
            std::cerr << "[FAIL] Fence wait should have timed out\n";
        } catch (const std::runtime_error& e) {
            std::cout << "[PASS] Fence wait timeout exception caught: " << e.what() << "\n";
        }
        close(pfd[0]);
        close(pfd[1]);
    }
}

void testConcurrentFrameCommits(WaylandBufferSharingManager& manager) {
    std::cout << "[INFO] Testing concurrent frame commits and buffer import/release...\n";
    const int THREAD_COUNT = 8;
    const int ITERATIONS = 10000;
    std::vector<std::thread> threads;

    for (int t = 0; t < THREAD_COUNT; ++t) {
        threads.emplace_back([&manager, t]() {
            GraphicBufferSpec spec{static_cast<uint32_t>(t + 1), 1920, 1080, 1920, PixelFormat::ARGB_8888, t + 10};
            for (int i = 0; i < ITERATIONS; ++i) {
                try {
                    void* hb = manager.importDmaBufToHardwareBuffer(t + 10, spec);
                    manager.bindHardwareBufferToSurfaceControl(reinterpret_cast<void*>(0x1234), hb);
                    manager.releaseBuffer(hb);
                } catch (...) {}
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }
    std::cout << "[INFO] Active buffer count after " << (THREAD_COUNT * ITERATIONS) << " concurrent operations: " << manager.getActiveBufferCount() << "\n";
}

int main() {
    std::cout << "=== Running Challenger 2 Native Stress Tests ===\n";
    WaylandBufferSharingManager manager;
    testInvalidFdPassing(manager);
    testFenceReleaseLogic(manager);
    testConcurrentFrameCommits(manager);
    std::cout << "=== Native Stress Tests Completed ===\n";
    return 0;
}
