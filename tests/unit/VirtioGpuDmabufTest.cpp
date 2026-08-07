/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "../../system/linux_bridge/wayland_buffer_sharing.h"
#include <cassert>
#include <iostream>
#include <stdexcept>

using namespace android::linux_bridge;

void testExportAndImportDmabuf() {
    WaylandBufferSharingManager manager;
    int fd = manager.exportDmaBufFd(1001);
    assert(fd > 0);

    GraphicBufferSpec spec{1001, 1920, 1080, 1920, PixelFormat::ARGB_8888, fd};
    void* hb = manager.importDmaBufToHardwareBuffer(fd, spec);
    assert(hb != nullptr);
    assert(manager.getActiveBufferCount() == 1);

    manager.releaseBuffer(hb);
    assert(manager.getActiveBufferCount() == 0);
    std::cout << "[PASS] testExportAndImportDmabuf\n";
}

void testInvalidDmabufHandle() {
    WaylandBufferSharingManager manager;
    GraphicBufferSpec spec{1002, 0, 0, 0, PixelFormat::ARGB_8888, -1};
    bool caught = false;
    try {
        manager.importDmaBufToHardwareBuffer(-1, spec);
    } catch (const std::invalid_argument& e) {
        caught = true;
    }
    assert(caught);
    std::cout << "[PASS] testInvalidDmabufHandle\n";
}

void testFormatIncompatibilityFallback() {
    WaylandBufferSharingManager manager;
    PixelFormat format = manager.negotiateFormat(PixelFormat::YUV_420);
    assert(format == PixelFormat::ARGB_8888);
    std::cout << "[PASS] testFormatIncompatibilityFallback\n";
}

void testGpuFenceCompletionTimeout() {
    WaylandBufferSharingManager manager;
    bool caught = false;
    try {
        manager.waitGpuFenceCompletion(99, 1000000);
    } catch (const std::runtime_error& e) {
        if (std::string(e.what()) == "SyncFenceWaitTimeout") {
            caught = true;
        }
    }
    assert(caught);
    std::cout << "[PASS] testGpuFenceCompletionTimeout\n";
}

void testGpuResetRecovery() {
    WaylandBufferSharingManager manager;
    int fd = manager.exportDmaBufFd(1003);
    GraphicBufferSpec spec{1003, 1280, 720, 1280, PixelFormat::ARGB_8888, fd};
    void* hb = manager.importDmaBufToHardwareBuffer(fd, spec);
    assert(manager.getActiveBufferCount() == 1);

    manager.onGpuReset();
    assert(manager.getActiveBufferCount() == 0);
    std::cout << "[PASS] testGpuResetRecovery\n";
}

int main() {
    std::cout << "Running VirtioGpuDmabufTest unit tests...\n";
    testExportAndImportDmabuf();
    testInvalidDmabufHandle();
    testFormatIncompatibilityFallback();
    testGpuFenceCompletionTimeout();
    testGpuResetRecovery();
    std::cout << "ALL VirtioGpuDmabufTest UNIT TESTS PASSED!\n";
    return 0;
}
