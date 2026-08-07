/*
 * Copyright (C) 2026 The Android Open Source Project
 * Adversarial Stress & Edge-Case Test Harness for WaylandBufferSharingManager
 */

#include "wayland_buffer_sharing.h"
#include <iostream>
#include <cassert>
#include <stdexcept>
#include <thread>
#include <vector>
#include <climits>

using namespace android::linux_bridge;

void testBoundaryAndInvalidDmabufImport() {
    WaylandBufferSharingManager manager;

    // Test 1: Negative FD (-1)
    GraphicBufferSpec spec1{1, 1920, 1080, 1920, PixelFormat::ARGB_8888, -1};
    try {
        manager.importDmaBufToHardwareBuffer(-1, spec1);
        assert(false && "Expected exception for negative dmaBufFd");
    } catch (const std::invalid_argument& e) {
        std::cout << "[PASS] Negative FD correctly rejected: " << e.what() << "\n";
    }

    // Test 2: Zero width
    GraphicBufferSpec spec2{2, 0, 1080, 0, PixelFormat::ARGB_8888, 10};
    try {
        manager.importDmaBufToHardwareBuffer(10, spec2);
        assert(false && "Expected exception for zero width");
    } catch (const std::invalid_argument& e) {
        std::cout << "[PASS] Zero width correctly rejected: " << e.what() << "\n";
    }

    // Test 3: Zero height
    GraphicBufferSpec spec3{3, 1920, 0, 1920, PixelFormat::ARGB_8888, 10};
    try {
        manager.importDmaBufToHardwareBuffer(10, spec3);
        assert(false && "Expected exception for zero height");
    } catch (const std::invalid_argument& e) {
        std::cout << "[PASS] Zero height correctly rejected: " << e.what() << "\n";
    }

    // Test 4: Export bufferId = 0
    int exportedFd = manager.exportDmaBufFd(0);
    assert(exportedFd == -1 && "exportDmaBufFd(0) should return -1");
    std::cout << "[PASS] exportDmaBufFd(0) returned -1\n";

    // Test 5: Export valid bufferId
    exportedFd = manager.exportDmaBufFd(105);
    assert(exportedFd > 0 && "exportDmaBufFd(105) should return valid FD");
    std::cout << "[PASS] exportDmaBufFd(105) returned " << exportedFd << "\n";
}

void testGpuFenceAndResetHandling() {
    WaylandBufferSharingManager manager;

    // Test 1: Fence FD = -1 returns false
    bool fenceRes = manager.waitGpuFenceCompletion(-1, 1000000);
    assert(!fenceRes && "Fence wait for -1 should return false");
    std::cout << "[PASS] waitGpuFenceCompletion(-1) returned false\n";

    // Test 2: Fence FD = 99 throws SyncFenceWaitTimeout exception
    try {
        manager.waitGpuFenceCompletion(99, 1000000);
        assert(false && "Expected SyncFenceWaitTimeout exception for fenceFd 99");
    } catch (const std::runtime_error& e) {
        std::string msg = e.what();
        assert(msg == "SyncFenceWaitTimeout");
        std::cout << "[PASS] Fence timeout exception caught: " << msg << "\n";
    }

    // Test 3: Import buffer, then trigger GPU reset
    GraphicBufferSpec spec{10, 1280, 720, 1280, PixelFormat::ARGB_8888, 15};
    void* hb = manager.importDmaBufToHardwareBuffer(15, spec);
    assert(hb != nullptr);
    assert(manager.getActiveBufferCount() == 1);

    manager.onGpuReset();
    assert(manager.getActiveBufferCount() == 0 && "Active buffer count should reset to 0");
    std::cout << "[PASS] GPU reset cleaned active buffer registry\n";

    // Test 4: Attempt import right after GPU reset (GPU should be healthy again)
    hb = manager.importDmaBufToHardwareBuffer(16, spec);
    assert(hb != nullptr);
    assert(manager.getActiveBufferCount() == 1);
    std::cout << "[PASS] GPU import post-reset succeeded\n";
}

void testFormatNegotiationAndNullRelease() {
    WaylandBufferSharingManager manager;

    assert(manager.negotiateFormat(PixelFormat::ARGB_8888) == PixelFormat::ARGB_8888);
    assert(manager.negotiateFormat(PixelFormat::XRGB_8888) == PixelFormat::XRGB_8888);
    assert(manager.negotiateFormat(PixelFormat::RGB_888) == PixelFormat::RGB_888);
    assert(manager.negotiateFormat(PixelFormat::YUV_420) == PixelFormat::ARGB_8888);
    assert(manager.negotiateFormat(PixelFormat::UNSUPPORTED) == PixelFormat::ARGB_8888);
    assert(manager.negotiateFormat(static_cast<PixelFormat>(999)) == PixelFormat::ARGB_8888);
    std::cout << "[PASS] All pixel format negotiations verified\n";

    // Null pointer release safety
    manager.releaseBuffer(nullptr);
    assert(manager.getActiveBufferCount() == 0);
    std::cout << "[PASS] Null pointer buffer release handled safely\n";

    // Double release safety
    GraphicBufferSpec spec{20, 800, 600, 800, PixelFormat::ARGB_8888, 25};
    void* hb = manager.importDmaBufToHardwareBuffer(25, spec);
    assert(manager.getActiveBufferCount() == 1);

    manager.releaseBuffer(hb);
    assert(manager.getActiveBufferCount() == 0);
    manager.releaseBuffer(hb); // Second release should not underflow
    assert(manager.getActiveBufferCount() == 0);
    std::cout << "[PASS] Double release safety verified without underflow\n";

    // Bind SurfaceControl test
    assert(!manager.bindHardwareBufferToSurfaceControl(nullptr, hb));
    assert(!manager.bindHardwareBufferToSurfaceControl(hb, nullptr));
    assert(manager.bindHardwareBufferToSurfaceControl(reinterpret_cast<void*>(0x100), hb));
    std::cout << "[PASS] bindHardwareBufferToSurfaceControl null safety verified\n";
}

int main() {
    std::cout << "=== Running Adversarial WaylandBufferSharing Stress Tests ===\n";
    testBoundaryAndInvalidDmabufImport();
    testGpuFenceAndResetHandling();
    testFormatNegotiationAndNullRelease();
    std::cout << "ALL Adversarial WaylandBufferSharing STRESS TESTS PASSED!\n";
    return 0;
}
