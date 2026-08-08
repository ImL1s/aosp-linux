/*
 * Challenger 2 M4 Stress Test Harness - Empirical C++ Verification
 * Workspace: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_challenger_m4_iter2_2
 */

#include <iostream>
#include <vector>
#include <thread>
#include <atomic>
#include <cassert>
#include <unistd.h>
#include <fcntl.h>
#include <sys/resource.h>
#include "system/linux_bridge/wayland_buffer_sharing.h"

using namespace android::linux_bridge;

static std::atomic<int> gErrors{0};

void logError(const std::string& msg) {
    std::cerr << "[FAIL] " << msg << std::endl;
    gErrors.fetch_add(1);
}

void testInvalidInputsAndBoundaries() {
    std::cout << "--- Test 1: Invalid Inputs & Boundary Conditions ---" << std::endl;
    WaylandBufferSharingManager mgr;

    GraphicBufferSpec spec;
    spec.bufferId = 1;
    spec.width = 1920;
    spec.height = 1080;
    spec.stride = 1920 * 4;
    spec.format = PixelFormat::ARGB_8888;
    spec.dmaBufFd = -1;

    // 1. Negative FD
    try {
        mgr.importDmaBufToHardwareBuffer(-1, spec);
        logError("Expected exception for dmaBufFd = -1");
    } catch (const std::invalid_argument& e) {
        std::cout << "  [PASS] Correctly caught invalid_argument for dmaBufFd = -1: " << e.what() << std::endl;
    } catch (...) {
        logError("Unexpected exception type for dmaBufFd = -1");
    }

    // 2. Zero width / height
    spec.dmaBufFd = 10;
    spec.width = 0;
    try {
        mgr.importDmaBufToHardwareBuffer(spec.dmaBufFd, spec);
        logError("Expected exception for width = 0");
    } catch (const std::invalid_argument& e) {
        std::cout << "  [PASS] Correctly caught invalid_argument for width = 0: " << e.what() << std::endl;
    }

    spec.width = 1920;
    spec.height = 0;
    try {
        mgr.importDmaBufToHardwareBuffer(spec.dmaBufFd, spec);
        logError("Expected exception for height = 0");
    } catch (const std::invalid_argument& e) {
        std::cout << "  [PASS] Correctly caught invalid_argument for height = 0: " << e.what() << std::endl;
    }

    // 3. Null pointer bindings
    bool res1 = mgr.bindHardwareBufferToSurfaceControl(nullptr, nullptr);
    if (res1) logError("bindHardwareBufferToSurfaceControl returned true for double nullptr");
    else std::cout << "  [PASS] bindHardwareBufferToSurfaceControl handled nullptr safely" << std::endl;

    // 4. Null pointer release
    try {
        mgr.releaseBuffer(nullptr);
        std::cout << "  [PASS] releaseBuffer(nullptr) handled safely without crash" << std::endl;
    } catch (...) {
        logError("releaseBuffer(nullptr) threw exception");
    }

    // 5. Export DMA-buf FD with bufferId = 0
    int exportedFd = mgr.exportDmaBufFd(0);
    if (exportedFd != -1) {
        logError("exportDmaBufFd(0) returned valid FD instead of -1");
    } else {
        std::cout << "  [PASS] exportDmaBufFd(0) correctly returned -1" << std::endl;
    }

    // Export DMA-buf FD with valid bufferId
    int validExportFd = mgr.exportDmaBufFd(101);
    if (validExportFd < 0) {
        logError("exportDmaBufFd(101) failed");
    } else {
        std::cout << "  [PASS] exportDmaBufFd(101) created valid FD: " << validExportFd << std::endl;
        close(validExportFd);
    }
}

void testMultiThreadedStress() {
    std::cout << "--- Test 2: Multi-Threaded Stress Test (8 Threads, 80,000 Ops) ---" << std::endl;
    WaylandBufferSharingManager mgr;

    const int NUM_THREADS = 8;
    const int OPS_PER_THREAD = 10000;
    std::vector<std::thread> threads;
    std::atomic<uint64_t> totalAllocated{0};
    std::atomic<uint64_t> totalReleased{0};

    // Dummy SurfaceControl pointer
    int dummySurface = 42;
    void* surfaceControlPtr = &dummySurface;

    auto worker = [&](int threadId) {
        for (int i = 0; i < OPS_PER_THREAD; ++i) {
            GraphicBufferSpec spec;
            spec.bufferId = static_cast<uint32_t>(threadId * OPS_PER_THREAD + i + 1);
            spec.width = 1280;
            spec.height = 720;
            spec.stride = 1280 * 4;
            spec.format = PixelFormat::ARGB_8888;
            spec.dmaBufFd = 1;

            try {
                void* handle = mgr.importDmaBufToHardwareBuffer(spec.dmaBufFd, spec);
                if (!handle) {
                    logError("importDmaBufToHardwareBuffer returned null handle");
                    continue;
                }
                totalAllocated.fetch_add(1, std::memory_order_relaxed);

                bool bindOk = mgr.bindHardwareBufferToSurfaceControl(surfaceControlPtr, handle);
                if (!bindOk) {
                    logError("bindHardwareBufferToSurfaceControl failed");
                }

                mgr.releaseBuffer(handle);
                totalReleased.fetch_add(1, std::memory_order_relaxed);

            } catch (const std::exception& e) {
                logError(std::string("Thread exception: ") + e.what());
            }
        }
    };

    auto startTime = std::chrono::high_resolution_clock::now();
    for (int t = 0; t < NUM_THREADS; ++t) {
        threads.emplace_back(worker, t);
    }

    for (auto& thread : threads) {
        thread.join();
    }
    auto endTime = std::chrono::high_resolution_clock::now();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    size_t finalActive = mgr.getActiveBufferCount();
    std::cout << "  Completed " << (NUM_THREADS * OPS_PER_THREAD) << " ops across " << NUM_THREADS 
              << " threads in " << elapsedMs << " ms." << std::endl;
    std::cout << "  Total Allocated: " << totalAllocated.load() << std::endl;
    std::cout << "  Total Released:  " << totalReleased.load() << std::endl;
    std::cout << "  Final Active Buffer Count: " << finalActive << std::endl;

    if (finalActive != 0) {
        logError("Active buffer count is not 0 after stress test! Active: " + std::to_string(finalActive));
    } else {
        std::cout << "  [PASS] mActiveBuffers thread-safety verified: count strictly 0!" << std::endl;
    }
}

void testGpuResetAndFormatNegotiation() {
    std::cout << "--- Test 3: GPU Reset & Format Negotiation ---" << std::endl;
    WaylandBufferSharingManager mgr;

    // Negotiate formats
    PixelFormat f1 = mgr.negotiateFormat(PixelFormat::ARGB_8888);
    if (f1 != PixelFormat::ARGB_8888) logError("negotiateFormat ARGB_8888 failed");

    PixelFormat f2 = mgr.negotiateFormat(PixelFormat::YUV_420);
    if (f2 != PixelFormat::ARGB_8888) logError("negotiateFormat YUV_420 fallback failed");

    std::cout << "  [PASS] Format negotiation verified" << std::endl;

    // Allocate buffer
    GraphicBufferSpec spec{1, 800, 600, 800 * 4, PixelFormat::ARGB_8888, 1};
    void* handle = mgr.importDmaBufToHardwareBuffer(spec.dmaBufFd, spec);
    if (mgr.getActiveBufferCount() != 1) logError("Active buffer count should be 1");

    // Trigger GPU reset
    mgr.onGpuReset();
    if (mgr.getActiveBufferCount() != 0) logError("Active buffer count should be 0 after onGpuReset");
    std::cout << "  [PASS] GPU reset handling verified" << std::endl;

    // Release allocated handle safely after reset
    mgr.releaseBuffer(handle);
}

int main() {
    std::cout << "=========================================================" << std::endl;
    std::cout << " Starting Challenger 2 M4 Native Buffer Sharing Verification " << std::endl;
    std::cout << "=========================================================" << std::endl;

    testInvalidInputsAndBoundaries();
    testMultiThreadedStress();
    testGpuResetAndFormatNegotiation();

    std::cout << "=========================================================" << std::endl;
    if (gErrors.load() == 0) {
        std::cout << " VERDICT: NATIVE SUITE PASSED ALL EMPIRICAL TESTS! " << std::endl;
        std::cout << "=========================================================" << std::endl;
        return 0;
    } else {
        std::cerr << " VERDICT: FAILED WITH " << gErrors.load() << " ERRORS! " << std::endl;
        std::cout << "=========================================================" << std::endl;
        return 1;
    }
}
