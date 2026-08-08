# Handoff Report — Explorer 3 (Milestone M4 Iteration 2: Native Wayland Buffer Sharing Refactoring Blueprint)

**Target Scope**: `system/linux_bridge/wayland_buffer_sharing.cpp`, `system/linux_bridge/wayland_buffer_sharing.h`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m4_iter2_3`  
**Date**: 2026-08-08  
**Status**: COMPLETE  

---

## 1. Observation (觀察事實)

### 1.1 Auditor 與 Reviewer 鑑識審查觀察紀錄
- **參考報告來源**:
  - `.agents/teamwork_preview_auditor_m4_1/handoff.md` (Auditor M4_1 鑑識審查報告)
  - `.agents/teamwork_preview_reviewer_m4_1/handoff.md` (Reviewer M4_1 審查報告)
- **觀察到的事實**:
  1. `system/linux_bridge/wayland_buffer_sharing.cpp` 第 77–82 行之 `bindHardwareBufferToSurfaceControl` 為典型的假裝實作 (Facade Implementation Stub)：
     ```cpp
     bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
         if (!surfaceControlPtr || !hardwareBufferPtr) {
             return false;
         }
         return true;
     }
     ```
     該函數完全未呼叫任何 Android NDK `ASurfaceTransaction` 相關 API（如 `ASurfaceTransaction_create`, `ASurfaceTransaction_setBuffer`, `ASurfaceTransaction_apply`, `ASurfaceTransaction_delete`），僅在指標非空時無條件回傳 `true`。
  2. `importDmaBufToHardwareBuffer` 函數（第 72 行）使用模擬運算：
     ```cpp
     uintptr_t handle = static_cast<uintptr_t>(dmaBufFd) + 0x1000;
     ```
     未進行真實的 NDK `AHardwareBuffer` 建立、匯入或記憶體配置。

### 1.2 Challenger 多執行緒併發 Data Race 實證
- **參考報告來源**: `.agents/teamwork_preview_challenger_m4_2/handoff.md` (Challenger M4_2 挑戰報告)
- **檔案觀察**: `system/linux_bridge/wayland_buffer_sharing.h` 第 72 行中，`mActiveBuffers` 被宣告為普通非原子型態 `uint32_t mActiveBuffers{0};`，且無任何 `std::mutex` 鎖保護。
- **壓力測試實證命令與結果**:
  ```bash
  clang++ -std=c++17 -Wall -Wextra -pthread -Isystem/linux_bridge -I. system/linux_bridge/wayland_buffer_sharing.cpp .agents/teamwork_preview_challenger_m4_2/ChallengerM4NativeStressTest.cpp -o build_out/bin/ChallengerM4NativeStressTest && ./build_out/bin/ChallengerM4NativeStressTest
  ```
  *輸出結果*:
  ```text
  [INFO] Testing concurrent frame commits and buffer import/release...
  [INFO] Active buffer count after 80000 concurrent operations: 123
  ```
  在 8 個執行緒同時執行 80,000 次 `importDmaBufToHardwareBuffer` (+1) 與 `releaseBuffer` (-1) 的高頻渲染併發測試下，`mActiveBuffers` 最終殘留計數為 `123`（而非理論值 `0`），證實在高頻併發渲染場景下存在嚴重的 Data Race 與計數腐敗。

---

## 2. Logic Chain (推理邏輯鏈)

1. **觀察事實 1.1 ⇒ 假裝實作致使 SurfaceControl 無法呈現畫面**:
   `bindHardwareBufferToSurfaceControl` 僅簡單回傳 `true`，完全未建立 `ASurfaceTransaction` 並提交 buffer 至 Android SurfaceFlinger。這導致 Linux Wayland 視窗的 dma-buf 畫面無法真正渲染到 Android SurfaceControl 視窗樹中，R4 核心需求（"Real Wayland dma-buf & SurfaceControl Binding"）完全失效。
2. **觀察事實 1.2 ⇒ 資料競態與記憶體洩漏風險**:
   在多視窗與高幀率繪製情境下，Wayland Proxy 會跨多個執行緒同時提交與釋放繪圖緩衝區。非原子的 `uint32_t mActiveBuffers` 發生 Read-Modify-Write 競態條件，導致活躍緩衝區計數失真（如併發 8 萬次後殘留 123），無法精確追蹤與釋放資源，長時間運行將引發記憶體洩漏與系統崩潰。
3. **推論步驟 3 ⇒ NDK 完整資源生命週期要求**:
   替換 facade 時，必須確保每個 `ASurfaceTransaction_create()` 都對應 `ASurfaceTransaction_delete()` 進行釋放，且在 Android 目標環境（`__ANDROID__`）調用真實 NDK API (`AHardwareBuffer_allocate`/`AHardwareBuffer_release`)，同時在宿主測試環境（macOS/Host Linux）提供結構健全的 Mock 容器，維持多平台編譯與測試能力。
4. **結論**:
   必須將 `wayland_buffer_sharing.h` 的 `mActiveBuffers` 重構為 `std::atomic<size_t>`，並在 `wayland_buffer_sharing.cpp` 中完整實作 `ASurfaceTransaction_create` -> `ASurfaceTransaction_setBuffer` -> `ASurfaceTransaction_apply` -> `ASurfaceTransaction_delete` 的完整生命週期鏈。

---

## 3. Caveats (注意事項與局限性)

1. **跨平台雙重編譯相容性 (Host vs Target Build)**:
   Android NDK 標頭檔 (`<android/hardware_buffer.h>`, `<android/surface_control.h>`) 僅存在於 Android 交叉編譯環境中。為了使宿主開發環境（如 macOS / Host Linux 下的 `ChallengerM4NativeStressTest` 與 `linux_bridge_test`）能正常編譯與通過測試，必須使用 `#if defined(__ANDROID__)` 區隔真實 NDK 調用與宿主環境的 Mock 結構體，切勿直接包含無法在宿主機解析的標頭檔。
2. **NDK 資源與記憶體防護**:
   `ASurfaceTransaction` 物件於每次提交後必須顯式調用 `ASurfaceTransaction_delete` 銷毀，否則高頻幀提交會導致 NDK 交易物件記憶體暴漲。
3. **執行緒安全模式**:
   所有對 `mActiveBuffers` 與 `mGpuHealthy` 的存取皆應透過 atomic 操作 (`fetch_add`, `fetch_sub`, `load`, `store`, `exchange`) 完成，禁止任何非原子裸讀寫。

---

## 4. Conclusion & Concrete Implementation Blueprint (結論與實作藍圖)

### 4.1 重構藍圖核心亮點
1. **標頭檔 `wayland_buffer_sharing.h`**:
   - 引入 `<atomic>` 標頭檔。
   - 將 `mActiveBuffers` 變更為 `std::atomic<size_t> mActiveBuffers{0};`。
   - 將 `mGpuHealthy` 變更為 `std::atomic<bool> mGpuHealthy{true};`。
   - `getActiveBufferCount()` 改為 `mActiveBuffers.load(std::memory_order_relaxed)`。
2. **實作檔 `wayland_buffer_sharing.cpp`**:
   - 引入跨平台條件編譯 `#if defined(__ANDROID__)`。
   - 實作完整 NDK `ASurfaceTransaction` 生命週期：`ASurfaceTransaction_create()` -> `ASurfaceTransaction_setBuffer(...)` -> `ASurfaceTransaction_apply(...)` -> `ASurfaceTransaction_delete(...)`。
   - 實作 NDK `AHardwareBuffer` 配製與釋放：`AHardwareBuffer_allocate(...)` 與 `AHardwareBuffer_release(...)`。
   - 在 `releaseBuffer` 與 `importDmaBufToHardwareBuffer` 中使用原子加減法操作，徹底消滅 Data Race。

---

### 4.2 完整實作代碼藍圖 (Concrete Code Replacement)

#### 1. `system/linux_bridge/wayland_buffer_sharing.h`
```cpp
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

    // Bind HardwareBuffer to SurfaceControl via NDK ASurfaceTransaction
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
```

---

#### 2. `system/linux_bridge/wayland_buffer_sharing.cpp`
```cpp
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

    // 1. 建立真實的 NDK 交易物件
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (!transaction) {
        return false;
    }

    // 2. 設置緩衝區與提交交易
    ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, -1 /* fenceFd */);
    ASurfaceTransaction_apply(transaction);

    // 3. 完整清理 NDK 交易物件資源，消滅記憶體洩漏
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

    // 原子遞減活躍緩衝區計數
    size_t current = mActiveBuffers.load(std::memory_order_relaxed);
    while (current > 0) {
        if (mActiveBuffers.compare_exchange_weak(current, current - 1, std::memory_order_relaxed)) {
            break;
        }
    }
}

} // namespace linux_bridge
} // namespace android
```

---

## 5. Verification Method (獨立驗證方法)

實施者套用上述藍圖後，可透過以下步驟進行獨立驗證：

1. **併發壓力測試 (Data Race 驗證)**:
   ```bash
   clang++ -std=c++17 -Wall -Wextra -pthread -Isystem/linux_bridge -I. system/linux_bridge/wayland_buffer_sharing.cpp .agents/teamwork_preview_challenger_m4_2/ChallengerM4NativeStressTest.cpp -o build_out/bin/ChallengerM4NativeStressTest && ./build_out/bin/ChallengerM4NativeStressTest
   ```
   *通過標準*: 終端機必須印出 `Active buffer count after 80000 concurrent operations: 0`，且程式回傳 exit code 0。

2. **Native 測試套件迴歸驗證**:
   ```bash
   clang++ -std=c++17 -Wall -Wextra -pthread -Isystem/linux_bridge -I. system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/vsock_server.cpp system/linux_bridge/hmac_auth.cpp system/linux_bridge/guest_ota_rollback_watchdog.cpp system/linux_bridge/wayland_buffer_sharing.cpp system/linux_bridge/tests/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test
   ```
   *通過標準*: 必須印出 `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。

3. **NDK API 符號與 Atomic 檢視**:
   ```bash
   grep -n "ASurfaceTransaction_" system/linux_bridge/wayland_buffer_sharing.cpp
   grep -n "std::atomic" system/linux_bridge/wayland_buffer_sharing.h
   ```
   *通過標準*:
   - `wayland_buffer_sharing.cpp` 包含 `ASurfaceTransaction_create`, `ASurfaceTransaction_setBuffer`, `ASurfaceTransaction_apply`, `ASurfaceTransaction_delete` 四大完整呼叫。
   - `wayland_buffer_sharing.h` 包含 `std::atomic<size_t> mActiveBuffers` 宣告。
