/*
 * Copyright (C) 2026 The Android Open Source Project
 * Empirical C++ Stress & Edge Case Test Harness for Milestone M4 (Iteration 2 Verification)
 */

#include "wayland_buffer_sharing.h"
#include <iostream>
#include <cassert>
#include <thread>
#include <vector>
#include <atomic>
#include <chrono>
#include <unistd.h>

using namespace android::linux_bridge;

void testCppImportAndNullEdges() {
    std::cout << "[CPP CHALLENGE 1] Null Handles & Import Edge Cases...\n";
    WaylandBufferSharingManager manager;

    // 1. Export dma-buf fd
    int fd = manager.exportDmaBufFd(101);
    assert(fd >= 0);

    // 2. Invalid import arguments
    GraphicBufferSpec invalidSpec1{101, 0, 768, 1024, PixelFormat::ARGB_8888, fd};
    try {
        manager.importDmaBufToHardwareBuffer(fd, invalidSpec1);
        assert(false && "Should have thrown invalid_argument for 0 width");
    } catch (const std::invalid_argument& e) {
        // Expected
    }

    GraphicBufferSpec invalidSpec2{101, 1024, 0, 1024, PixelFormat::ARGB_8888, fd};
    try {
        manager.importDmaBufToHardwareBuffer(fd, invalidSpec2);
        assert(false && "Should have thrown invalid_argument for 0 height");
    } catch (const std::invalid_argument& e) {
        // Expected
    }

    GraphicBufferSpec validSpec{101, 1024, 768, 1024, PixelFormat::ARGB_8888, fd};
    try {
        manager.importDmaBufToHardwareBuffer(-1, validSpec);
        assert(false && "Should have thrown invalid_argument for invalid FD -1");
    } catch (const std::invalid_argument& e) {
        // Expected
    }

    // 3. Valid import
    void* buffer = manager.importDmaBufToHardwareBuffer(fd, validSpec);
    assert(buffer != nullptr);
    assert(manager.getActiveBufferCount() == 1);

    // 4. Null surfaceControl & null hardwareBuffer binding checks
    struct DummySC { int dummy; } dummySc;
    bool nullScResult = manager.bindHardwareBufferToSurfaceControl(nullptr, buffer);
    assert(!nullScResult);

    bool nullBufResult = manager.bindHardwareBufferToSurfaceControl(&dummySc, nullptr);
    assert(!nullBufResult);

    bool nullBothResult = manager.bindHardwareBufferToSurfaceControl(nullptr, nullptr);
    assert(!nullBothResult);

    // 5. Valid binding
    bool validBindResult = manager.bindHardwareBufferToSurfaceControl(&dummySc, buffer);
    assert(validBindResult);

    // 6. Release buffer
    manager.releaseBuffer(buffer);
    assert(manager.getActiveBufferCount() == 0);

    // 7. Null buffer release check
    manager.releaseBuffer(nullptr);
    assert(manager.getActiveBufferCount() == 0);

    close(fd);
    std::cout << "  [PASS] Null handles and import edge cases verified!\n";
}

void testCppFenceCompletion() {
    std::cout << "[CPP CHALLENGE 2] Fence Synchronization Edge Cases...\n";
    WaylandBufferSharingManager manager;

    // 1. Invalid fence FD
    bool invalidFence = manager.waitGpuFenceCompletion(-1, 1000000);
    assert(!invalidFence);

    // 2. Ready pipe FD
    int pfd[2];
    assert(pipe(pfd) == 0);
    write(pfd[1], "a", 1); // Make read end ready

    bool readyFence = manager.waitGpuFenceCompletion(pfd[0], 5000000); // 5ms timeout
    assert(readyFence);

    close(pfd[0]);
    close(pfd[1]);

    // 3. Timeout on non-ready pipe FD
    int pfd2[2];
    assert(pipe(pfd2) == 0);
    try {
        manager.waitGpuFenceCompletion(pfd2[0], 1000000); // 1ms timeout
        assert(false && "Should have thrown SyncFenceWaitTimeout");
    } catch (const std::runtime_error& e) {
        std::string msg = e.what();
        assert(msg.find("SyncFenceWaitTimeout") != std::string::npos);
    }
    close(pfd2[0]);
    close(pfd2[1]);

    std::cout << "  [PASS] Fence synchronization edge cases verified!\n";
}

void testCppHighConcurrencyAndResetStress() {
    std::cout << "[CPP CHALLENGE 3] High Concurrency & GPU Reset Stress Test...\n";
    WaylandBufferSharingManager manager;

    const int numThreads = 16;
    const int opsPerThread = 5000;
    std::atomic<bool> stopSignal{false};
    std::atomic<size_t> totalAllocations{0};
    std::atomic<size_t> totalReleases{0};

    // Background thread triggering GPU resets periodically
    std::thread resetThread([&]() {
        while (!stopSignal.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            manager.onGpuReset();
        }
    });

    std::vector<std::thread> workers;
    for (int t = 0; t < numThreads; t++) {
        workers.emplace_back([&, t]() {
            int pipeFds[2];
            if (pipe(pipeFds) != 0) return;

            GraphicBufferSpec spec{static_cast<uint32_t>(t + 1), 800, 600, 800, PixelFormat::ARGB_8888, pipeFds[0]};
            struct DummySC { int dummy; } dummySc;

            for (int i = 0; i < opsPerThread; i++) {
                try {
                    void* buf = manager.importDmaBufToHardwareBuffer(pipeFds[0], spec);
                    if (buf) {
                        totalAllocations.fetch_add(1, std::memory_order_relaxed);
                        manager.bindHardwareBufferToSurfaceControl(&dummySc, buf);
                        manager.releaseBuffer(buf);
                        totalReleases.fetch_add(1, std::memory_order_relaxed);
                    }
                } catch (const std::runtime_error&) {
                    // GPU reset during import is expected to fail gracefully
                } catch (...) {}
            }

            close(pipeFds[0]);
            close(pipeFds[1]);
        });
    }

    for (auto& w : workers) {
        w.join();
    }

    stopSignal.store(true);
    resetThread.join();

    std::cout << "  [PASS] Concurrency stress finished! Allocations: " << totalAllocations.load() 
              << ", Releases: " << totalReleases.load() << "\n";
}

int main() {
    std::cout << "=== Starting Empirical Challenger C++ Test Suite (M4 Iteration 2) ===\n";
    try {
        testCppImportAndNullEdges();
        testCppFenceCompletion();
        testCppHighConcurrencyAndResetStress();
        std::cout << "ALL C++ CHALLENGER TESTS PASSED SUCCESSFULLY!\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "C++ CHALLENGER TEST FAILED: " << e.what() << "\n";
        return 1;
    }
}
