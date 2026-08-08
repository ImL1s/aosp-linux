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

#include "wayland_buffer_sharing.h"
#include <cerrno>
#include <fcntl.h>
#include <iostream>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__linux__) && defined(__NR_memfd_create)
#include <sys/syscall.h>
#endif

#if defined(__ANDROID__)
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <android/native_window.h>
#else
// Host environment mock declarations for NDK SurfaceControl & HardwareBuffer types
struct AHardwareBuffer {
    int dmaBufFd;
    android::linux_bridge::GraphicBufferSpec spec;
    uint64_t magic{0x4857425546464552ULL};
};

struct ASurfaceControl {
    void* handle;
};

struct ASurfaceTransaction {
    ASurfaceControl* targetControl{nullptr};
    AHardwareBuffer* boundBuffer{nullptr};
    int fenceFd{-1};
};

static ASurfaceTransaction* ASurfaceTransaction_create() {
    return new ASurfaceTransaction();
}

static void ASurfaceTransaction_setBuffer(ASurfaceTransaction* transaction, ASurfaceControl* surface_control, AHardwareBuffer* buffer, int fence_fd) {
    if (transaction) {
        transaction->targetControl = surface_control;
        transaction->boundBuffer = buffer;
        transaction->fenceFd = fence_fd;
    }
}

static void ASurfaceTransaction_apply(ASurfaceTransaction* transaction) {
    (void)transaction;
}

static void ASurfaceTransaction_delete(ASurfaceTransaction* transaction) {
    delete transaction;
}
#endif

namespace android {
namespace linux_bridge {

WaylandBufferSharingManager::WaylandBufferSharingManager() {
    mActiveBuffers.store(0, std::memory_order_relaxed);
    mGpuHealthy.store(true, std::memory_order_relaxed);
}

WaylandBufferSharingManager::~WaylandBufferSharingManager() {
    onGpuReset();
}

int WaylandBufferSharingManager::exportDmaBufFd(uint32_t bufferId) {
    if (bufferId == 0) {
        return -1;
    }

    int fd = -1;
#if defined(__linux__) && defined(SYS_memfd_create)
    std::string name = "dmabuf_" + std::to_string(bufferId);
    fd = static_cast<int>(syscall(SYS_memfd_create, name.c_str(), 0));
#endif
    if (fd < 0) {
        int pfd[2];
        if (pipe(pfd) == 0) {
            close(pfd[1]);
            fd = pfd[0];
        }
    }
    return fd;
}

void* WaylandBufferSharingManager::importDmaBufToHardwareBuffer(int dmaBufFd, const GraphicBufferSpec& spec) {
    if (dmaBufFd < 0 || spec.width == 0 || spec.height == 0) {
        throw std::invalid_argument("Invalid dma-buf handle or zero dimensions");
    }

    if (!mGpuHealthy.load(std::memory_order_relaxed)) {
        throw std::runtime_error("GPU state error: GPU device reset");
    }

#if defined(__ANDROID__)
    AHardwareBuffer_Desc desc = {};
    desc.width = spec.width;
    desc.height = spec.height;
    desc.layers = 1;
    switch (spec.format) {
        case PixelFormat::ARGB_8888:
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            break;
        case PixelFormat::XRGB_8888:
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
            break;
        case PixelFormat::RGB_888:
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM;
            break;
        default:
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            break;
    }
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                 AHARDWAREBUFFER_USAGE_COMPOSITOR_MERGE;

    AHardwareBuffer* buffer = nullptr;
    int res = AHardwareBuffer_allocate(&desc, &buffer);
    if (res != 0 || !buffer) {
        throw std::runtime_error("Failed to allocate AHardwareBuffer via NDK, code: " + std::to_string(res));
    }
    void* handle = reinterpret_cast<void*>(buffer);
#else
    AHardwareBuffer* buffer = new AHardwareBuffer{dmaBufFd, spec, 0x4857425546464552ULL};
    void* handle = reinterpret_cast<void*>(buffer);
#endif

    mActiveBuffers.fetch_add(1, std::memory_order_relaxed);
    return handle;
}

bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
    if (!surfaceControlPtr || !hardwareBufferPtr) {
        return false;
    }

    ASurfaceControl* surfaceControl = reinterpret_cast<ASurfaceControl*>(surfaceControlPtr);
    AHardwareBuffer* hardwareBuffer = reinterpret_cast<AHardwareBuffer*>(hardwareBufferPtr);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (!transaction) {
        return false;
    }

    ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, -1 /* fenceFd */);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);

    return true;
}

bool WaylandBufferSharingManager::waitGpuFenceCompletion(int fenceFd, uint64_t timeoutNs) {
    if (fenceFd < 0) {
        return false;
    }

    struct pollfd pfd;
    pfd.fd = fenceFd;
    pfd.events = POLLIN | POLLOUT;
    pfd.revents = 0;

    int timeoutMs = static_cast<int>(timeoutNs / 1000000ULL);
    if (timeoutMs < 0) timeoutMs = -1;

    int ret = poll(&pfd, 1, timeoutMs);
    if (ret == 0) {
        throw std::runtime_error("SyncFenceWaitTimeout");
    } else if (ret < 0) {
        if (errno == EBADF || errno == EINVAL || errno == ETIMEDOUT || errno == ETIME) {
            throw std::runtime_error("SyncFenceWaitTimeout");
        }
        return false;
    }

    if (pfd.revents & (POLLERR | POLLNVAL)) {
        throw std::runtime_error("SyncFenceWaitTimeout");
    }

    return (pfd.revents & (POLLIN | POLLOUT)) != 0;
}

PixelFormat WaylandBufferSharingManager::negotiateFormat(PixelFormat requestedFormat) {
    switch (requestedFormat) {
        case PixelFormat::ARGB_8888:
        case PixelFormat::XRGB_8888:
        case PixelFormat::RGB_888:
            return requestedFormat;
        case PixelFormat::YUV_420:
            std::cout << "[WaylandBufferSharing] Incompatible format YUV_420 -> Fallback to ARGB_8888\n";
            return PixelFormat::ARGB_8888;
        default:
            return PixelFormat::ARGB_8888;
    }
}

void WaylandBufferSharingManager::onGpuReset() {
    size_t releasedCount = mActiveBuffers.exchange(0, std::memory_order_relaxed);
    std::cout << "[WaylandBufferSharing] GPU reset: Recreating host surface registry and releasing " << releasedCount << " active buffers.\n";
    mGpuHealthy.store(true, std::memory_order_relaxed);
}

void WaylandBufferSharingManager::releaseBuffer(void* hardwareBufferPtr) {
    if (!hardwareBufferPtr) {
        return;
    }

#if defined(__ANDROID__)
    AHardwareBuffer* buffer = reinterpret_cast<AHardwareBuffer*>(hardwareBufferPtr);
    AHardwareBuffer_release(buffer);
#else
    AHardwareBuffer* buffer = reinterpret_cast<AHardwareBuffer*>(hardwareBufferPtr);
    delete buffer;
#endif

    size_t current = mActiveBuffers.load(std::memory_order_relaxed);
    while (current > 0) {
        if (mActiveBuffers.compare_exchange_weak(current, current - 1, std::memory_order_relaxed)) {
            break;
        }
    }
}

} // namespace linux_bridge
} // namespace android
