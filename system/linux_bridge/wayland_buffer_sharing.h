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

#ifndef WAYLAND_BUFFER_SHARING_H
#define WAYLAND_BUFFER_SHARING_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace android {
namespace linux_bridge {

enum class PixelFormat {
    ARGB_8888 = 1,
    XRGB_8888 = 2,
    RGB_888 = 3,
    YUV_420 = 4,
    UNSUPPORTED = 99
};

struct GraphicBufferSpec {
    uint32_t bufferId;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    PixelFormat format;
    int dmaBufFd;
};

class WaylandBufferSharingManager {
public:
    WaylandBufferSharingManager();
    ~WaylandBufferSharingManager();

    // Export & import dma-buf file descriptor across hypervisor boundary
    int exportDmaBufFd(uint32_t bufferId);
    void* importDmaBufToHardwareBuffer(int dmaBufFd, const GraphicBufferSpec& spec);

    // Bind HardwareBuffer to SurfaceControl
    bool bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr);

    // Synchronize GPU fence completion before display read
    bool waitGpuFenceCompletion(int fenceFd, uint64_t timeoutNs);

    // Format incompatibility fallback
    PixelFormat negotiateFormat(PixelFormat requestedFormat);

    // GPU Reset & Surface Recreation Handling
    void onGpuReset();

    // Release buffer resources and avoid memory leaks
    void releaseBuffer(void* hardwareBufferPtr);

    size_t getActiveBufferCount() const { return mActiveBuffers.load(std::memory_order_relaxed); }

private:
    std::atomic<size_t> mActiveBuffers{0};
    std::atomic<bool> mGpuHealthy{true};
};

} // namespace linux_bridge
} // namespace android

#endif // WAYLAND_BUFFER_SHARING_H
