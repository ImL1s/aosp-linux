# Handoff Report — Explorer 3 (Milestone M4: Real Wayland dma-buf & SurfaceControl Binding)

**任務目標與範疇**:
- 專注檔案: `system/linux_bridge/wayland_buffer_sharing.cpp` 及標頭檔 `system/linux_bridge/wayland_buffer_sharing.h`
- 關聯系統層服務: `LinuxWindowBridgeService.java` 與 `LinuxAppProxyActivity.java`
- 研究目標:
  1. 深入調查 C++ 原生層 `wayland_buffer_sharing.cpp` 中 `ASurfaceTransaction_setBuffer` 之綁定機制。
  2. 詳細分析 Android NDK APIs（`AHardwareBuffer_import` / `AHardwareBuffer_createFromHandle`、`ASurfaceTransaction_create`、`ASurfaceTransaction_setBuffer`、`ASurfaceTransaction_apply`）、Wayland 客戶端 dma-buf 檔案描述符（FD）傳遞以及 GPU Buffer Release Fences 機制。
  3. 提出精確的 C++ 原生實作策略、JNI / Binder 跨層橋接設計、異常處理與資源清理方案。

---

## 1. Observation (觀察事實與原始碼引用)

### 1.1 `system/linux_bridge/wayland_buffer_sharing.cpp` 現狀觀察

1. **`bindHardwareBufferToSurfaceControl` 韓數存根 (Stub Implementation)**
   - **路徑**: `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/wayland_buffer_sharing.cpp`
   - **行號 77–82**:
     ```cpp
     bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr) {
         if (!surfaceControlPtr || !hardwareBufferPtr) {
             return false;
         }
         return true;
     }
     ```
   - **觀察事實**: 該韓數目前僅檢查指標非空後即直接返回 `true`，**完全未調用** Android NDK 規範之 `ASurfaceTransaction_create()`、`ASurfaceTransaction_setBuffer()`、`ASurfaceTransaction_apply()` 等原生 SurfaceControl 交易操作。

2. **`importDmaBufToHardwareBuffer` 虛擬指標分配 (Dummy Pointer Allocation)**
   - **行號 62–75**:
     ```cpp
     void* WaylandBufferSharingManager::importDmaBufToHardwareBuffer(int dmaBufFd, const GraphicBufferSpec& spec) {
         if (dmaBufFd < 0 || spec.width == 0 || spec.height == 0) {
             throw std::invalid_argument("Invalid dma-buf handle or zero dimensions");
         }

         if (!mGpuHealthy) {
             throw std::runtime_error("GPU state error: GPU device reset");
         }

         // Allocate opaque handle simulating AHardwareBuffer pointer
         uintptr_t handle = static_cast<uintptr_t>(dmaBufFd) + 0x1000;
         mActiveBuffers++;
         return reinterpret_cast<void*>(handle);
     }
     ```
   - **觀察事實**: 韓數使用 `static_cast<uintptr_t>(dmaBufFd) + 0x1000` 模擬一個不透明指標，**並未調用** NDK `<android/hardware_buffer.h>` 之 `AHardwareBuffer_import()` 或 `AHardwareBuffer_createFromHandle()` 將傳入之 dma-buf FD 轉換為真正的 `AHardwareBuffer` 物件。

3. **`exportDmaBufFd` 模擬內存描述符 (Dummy File Descriptor)**
   - **行號 40–60**:
     ```cpp
     int WaylandBufferSharingManager::exportDmaBufFd(uint32_t bufferId) {
         if (bufferId == 0) {
             return -1;
         }

         // Create a genuine kernel file descriptor representing exported dma-buf handle
         int fd = -1;
     #if defined(__linux__) && defined(SYS_memfd_create)
         std::string name = "dmabuf_" + std::to_string(bufferId);
         fd = static_cast<int>(syscall(SYS_memfd_create, name.c_str(), 0));
     #endif
         if (fd < 0) {
             // Fallback Unix pipe descriptor for environments without memfd_create
             int pfd[2];
             if (pipe(pfd) == 0) {
                 close(pfd[1]);
                 fd = pfd[0];
             }
         }
         return fd;
     }
     ```
   - **觀察事實**: 目前僅以 `memfd_create` 或 `pipe` 建立一般的 Linux FD，尚未整合 virtio-gpu / Wayland `zwp_linux_dmabuf_v1` 協議真實分配之 dma-buf FD。

4. **`waitGpuFenceCompletion` Fence 輪詢與同步 (Fence Synchronization)**
   - **行號 85–114**:
     ```cpp
     bool WaylandBufferSharingManager::waitGpuFenceCompletion(int fenceFd, uint64_t timeoutNs) {
         if (fenceFd < 0) {
             return false;
         }

         // Genuine Linux poll/sync_wait GPU fence completion check
         struct pollfd pfd;
         pfd.fd = fenceFd;
         pfd.events = POLLIN | POLLOUT;
         pfd.revents = 0;

         int timeoutMs = static_cast<int>(timeoutNs / 1000000ULL);
         if (timeoutMs < 0) timeoutMs = -1;

         int ret = poll(&pfd, 1, timeoutMs);
         ...
     }
     ```
   - **觀察事實**: 目前使用 Linux `poll()` 輪詢 `fenceFd` 的 `POLLIN | POLLOUT` 事件。雖然對 `sync_file` 有效，但在 Android AOSP 原生層中應規範搭配 `sync_wait(fenceFd, timeoutMs)`（來自 `<sync/sync.h>`）以及轉移 FD 所有權與關閉機制。

5. **`releaseBuffer` 資源遞減存根 (Resource Release Stub)**
   - **行號 136–140**:
     ```cpp
     void WaylandBufferSharingManager::releaseBuffer(void* hardwareBufferPtr) {
         if (hardwareBufferPtr && mActiveBuffers > 0) {
             mActiveBuffers--;
         }
     }
     ```
   - **觀察事實**: 僅對 `mActiveBuffers` 技術器減量，未對 `AHardwareBuffer` 調用 `AHardwareBuffer_release()` 解除引用計數，也未關閉對應之 dma-buf FD，會造成記憶體與 FD 洩漏。

---

### 1.2 關聯 Java 服務與 Activity 觀察

1. **`LinuxWindowBridgeService.java` 行號 138–155 (`commitFrame`)**
   ```java
   public synchronized boolean commitFrame(int surfaceId) {
       WaylandSurface surface = mSurfaces.get(surfaceId);
       if (surface == null) return false;
       ...
       surface.lastCommitNs = nowNs;
       surface.committedFrames++;
       return true;
   }
   ```
   - **觀察事實**: Java 層僅記錄頁面提交計數與 Pacing，未將從 Wayland 接收到的 HardwareBuffer 設定至 `surface.surfaceControl` 並執行 `SurfaceControl.Transaction.apply()`。

2. **`LinuxAppProxyActivity.java` 行號 217–236 (`SurfaceHolder.Callback`)**
   ```java
   @Override
   public void surfaceCreated(SurfaceHolder holder) {
       Log.i(TAG, "Surface created for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
       updateWindowDimensions();
   }
   ```
   - **觀察事實**: `SurfaceView` 建立後，其 underlying `SurfaceControl` 或 `Surface` 未傳回給 `LinuxWindowBridgeService` 進行 `surface.surfaceControl` 的綁定。

---

## 2. Logic Chain (推理鏈)

1. **dma-buf 與 AHardwareBuffer 轉換推導**:
   - Wayland 客戶端（Linux Guest 應用如 LibreOffice/GIMP）透過 Sommelier 及 `zwp_linux_dmabuf_v1` 協議，在 Guest 端透過 virtio-gpu 分配 dma-buf。
   - 跨邊界傳遞至 Host 後，`wayland_buffer_sharing.cpp` 的 `importDmaBufToHardwareBuffer` 必須使用 Android 原生 NDK API。
   - 在 Android 中，dma-buf FD 需要透過 `AHardwareBuffer_import` 或底層 `GraphicBuffer` / `AHardwareBuffer_createFromHandle` 包裝成 `AHardwareBuffer`。目前 `dmaBufFd + 0x1000` 假指標會在傳給 NDK 或 SurfaceFlinger 時引發 `SIGSEGV` 或無效指標錯誤。

2. **ASurfaceTransaction 繪圖提交推導**:
   - Android 視窗合成系統（SurfaceFlinger）透過 `SurfaceControl` 與 `Transaction` 來接受圖像 Buffer。
   - 原生層 `ASurfaceTransaction_create()` 建立交易物件後，透過 `ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, acquireFenceFd)` 綁定 Buffer 與 GPU 同步 Fence。
   - 調用 `ASurfaceTransaction_apply(transaction)` 後，SurfaceFlinger 才能進行 Zero-copy 合成與顯示。
   - 目前 `bindHardwareBufferToSurfaceControl` 僅回傳 `true`，導致 Wayland 畫面永遠無法投射至 Android 畫面。

3. **Fence 與同步 (Acquire & Release Fences) 推導**:
   - 渲染過程涉及異步 GPU pipeline。Wayland 客戶端渲染完成後會產生 Acquire Fence（`acquireFenceFd`），必須傳給 SurfaceFlinger 確保繪製完成後才進行讀取。
   - SurfaceFlinger 顯示完畢後會產生 Release Fence，必須回傳給 Wayland 客戶端（透過 `wl_buffer.release`），告知客戶端該 Buffer 已可重新寫入。
   - 缺少 Release Fence 回傳將導致客戶端渲染卡死（Buffer Starvation）或畫面破圖（Tearing）。

4. **JNI / Native 雙向橋接推導**:
   - Java 層 `LinuxWindowBridgeService` 擁有視窗生命週期（Task ID, Surface ID, Activity 綁定）。
   - C++ 層 `wayland_buffer_sharing.cpp` 擁有高效率之 dma-buf FD 與 NDK `ASurfaceTransaction` 操作。
   - 必須建立 JNI / Binder 橋接介面，讓 Java 的 `SurfaceControl` Java 物件（透過 NDK `ASurfaceControl_fromJava`）傳遞至 C++，或是由 JNI 呼叫 C++ 原生交易函式。

---

## 3. Caveats (限制與前提假設)

1. **Android API Level 假設**:
   - `ASurfaceTransaction` 與 `AHardwareBuffer` 原生 NDK APIs 需求 Android API Level 29 (Android 10)+。
   - `ASurfaceControl_fromJava` 與完整 Release Callback 支援需求 API Level 31 (Android 12)+。本設計針對 AOSP 14/15 平台（API 34/35）。

2. **只讀研究限制**:
   - 本報告為 Milestone M4 之架構與 C++ 實作設計規範，並未直接修改 `system/linux_bridge/wayland_buffer_sharing.cpp` 原始碼，具體代碼變更將交由 Implementer 執行。

3. **Virtio-gpu 驅動依賴**:
   - 假設 Guest Linux 核心與 Host crosvm/AVF 正確支援 virtio-gpu dma-buf FD 跨虛擬化邊界導出（Export）。

---

## 4. Conclusion & Actionable Recommendations (架構設計與 C++ 實作藍圖)

### 4.1 精確 NDK C++ 韓數呼叫與流程設計 (`wayland_buffer_sharing.cpp`)

Implementer 在實作 `wayland_buffer_sharing.cpp` 時，應採用以下標準 Android NDK API 藍圖：

```cpp
#include "wayland_buffer_sharing.h"
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <sync/sync.h>
#include <unistd.h>
#include <android/log.h>
#include <vndk/hardware_buffer.h> // 若使用系統層 GraphicBuffer / handle 轉換

#define LOG_TAG "WaylandBufferSharing"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace android {
namespace linux_bridge {

// 1. 真實 dma-buf Import 至 AHardwareBuffer
void* WaylandBufferSharingManager::importDmaBufToHardwareBuffer(int dmaBufFd, const GraphicBufferSpec& spec) {
    if (dmaBufFd < 0 || spec.width == 0 || spec.height == 0) {
        ALOGE("Invalid dmaBufFd (%d) or dimensions (%ux%u)", dmaBufFd, spec.width, spec.height);
        return nullptr;
    }

    if (!mGpuHealthy) {
        ALOGE("GPU state unhealthy, rejecting import");
        return nullptr;
    }

    AHardwareBuffer_Desc desc = {};
    desc.width = spec.width;
    desc.height = spec.height;
    desc.layers = 1;
    desc.format = (spec.format == PixelFormat::ARGB_8888) ? AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM : AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    desc.stride = spec.stride;

    AHardwareBuffer* outBuffer = nullptr;

    // 透過 native_handle_t 包裝 dma-buf FD 並調用 AHardwareBuffer_createFromHandle (AOSP internal / VNDK API)
    // 或使用 AHardwareBuffer_import (標準 NDK)
    native_handle_t* handle = native_handle_create(1 /* numFds */, 5 /* numInts */);
    handle->data[0] = dmaBufFd;
    handle->data[1] = spec.bufferId;
    handle->data[2] = spec.width;
    handle->data[3] = spec.height;
    handle->data[4] = spec.stride;
    handle->data[5] = static_cast<int>(spec.format);

    int res = AHardwareBuffer_createFromHandle(&desc, handle, AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_CLONE, &outBuffer);
    native_handle_delete(handle);

    if (res != 0 || !outBuffer) {
        ALOGE("AHardwareBuffer_createFromHandle failed with error %d", res);
        return nullptr;
    }

    mActiveBuffers++;
    return static_cast<void*>(outBuffer);
}

// 2. 原生 ASurfaceTransaction 綁定與 Apply
bool WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(void* surfaceControlPtr, void* hardwareBufferPtr, int acquireFenceFd) {
    if (!surfaceControlPtr || !hardwareBufferPtr) {
        ALOGE("Null surfaceControlPtr or hardwareBufferPtr passed to bindHardwareBufferToSurfaceControl");
        return false;
    }

    ASurfaceControl* surfaceControl = static_cast<ASurfaceControl*>(surfaceControlPtr);
    AHardwareBuffer* hardwareBuffer = static_cast<AHardwareBuffer*>(hardwareBufferPtr);

    // 建立 SurfaceControl Transaction
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (!transaction) {
        ALOGE("Failed to create ASurfaceTransaction");
        return false;
    }

    // 綁定 HardwareBuffer 與 Acquire Fence FD
    // 註：ASurfaceTransaction_setBuffer 會接收 acquireFenceFd 的所有權（若為 -1 則忽略）
    ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, acquireFenceFd);
    ASurfaceTransaction_setVisibility(transaction, surfaceControl, ASURFACETRANSACTION_VISIBILITY_VISIBLE);

    // 套用 Transaction 至 SurfaceFlinger
    ASurfaceTransaction_apply(transaction);

    // 銷毀 Transaction 物件（內部記憶體清理）
    ASurfaceTransaction_delete(transaction);
    return true;
}

// 3. GPU Sync Fence 等待與關閉
bool WaylandBufferSharingManager::waitGpuFenceCompletion(int fenceFd, uint64_t timeoutNs) {
    if (fenceFd < 0) return true; // 無 Fence 預設已完成

    int timeoutMs = static_cast<int>(timeoutNs / 1000000ULL);
    if (timeoutMs < 0) timeoutMs = -1;

    // 使用 AOSP <sync/sync.h> 標準 sync_wait
    int res = sync_wait(fenceFd, timeoutMs);
    if (res < 0) {
        ALOGE("sync_wait failed on fenceFd %d: %s", fenceFd, strerror(errno));
        return false;
    }
    return true;
}

// 4. 正確的資源釋放與計數器更新
void WaylandBufferSharingManager::releaseBuffer(void* hardwareBufferPtr) {
    if (hardwareBufferPtr) {
        AHardwareBuffer* buffer = static_cast<AHardwareBuffer*>(hardwareBufferPtr);
        AHardwareBuffer_release(buffer);
        if (mActiveBuffers > 0) {
            mActiveBuffers--;
        }
    }
}

} // namespace linux_bridge
} // namespace android
```

---

### 4.2 JNI / Binder 橋接層設計藍圖

為使 Java `LinuxWindowBridgeService` 與 C++ 原生 `wayland_buffer_sharing.cpp` 配合，建議實作 JNI 橋接類別 `com.android.server.linux.WaylandBufferSharingJni`:

1. **JNI 函式宣告 (Java)**:
   ```java
   package com.android.server.linux;

   import android.hardware.HardwareBuffer;
   import android.view.SurfaceControl;

   public class WaylandBufferSharingJni {
       static {
           System.loadLibrary("linux_bridge_jni");
       }

       public static native boolean nativeBindBufferToSurfaceControl(SurfaceControl surfaceControl, HardwareBuffer hardwareBuffer, int fenceFd);
       public static native long nativeImportDmaBuf(int dmaBufFd, int width, int height, int stride, int format);
       public static native void nativeReleaseBuffer(long bufferPtr);
   }
   ```

2. **JNI C++ 實作 (`com_android_server_linux_WaylandBufferSharingJni.cpp`)**:
   - 利用 NDK API `ASurfaceControl_fromJava(env, surfaceControlObj)` 將 Java `SurfaceControl` 轉為 `ASurfaceControl*`。
   - 利用 NDK API `AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj)` 將 Java `HardwareBuffer` 轉為 `AHardwareBuffer*`。
   - 呼叫 `WaylandBufferSharingManager::bindHardwareBufferToSurfaceControl(...)` 完成 Transaction。

3. **`LinuxAppProxyActivity.java` 綁定**:
   - 在 `surfaceCreated(SurfaceHolder holder)` 中：
     `SurfaceControl sc = mSurfaceView.getSurfaceControl();`
     `LinuxWindowBridgeService.getInstance().registerSurfaceControl(mSurfaceId, sc);`

---

### 4.3 異常處理與資源清理策略 (Error Handling & Resource Cleanup)

1. **FD 洩漏防範 (File Descriptor Cleanup)**:
   - 所有的 `dmaBufFd` 與 `acquireFenceFd` 在傳遞給 NDK 或轉換完成後，若無移交所有權，必須透過 `close(fd)` 關閉。
   - `ASurfaceTransaction_setBuffer` 會自動接管 `acquire_fence_fd` 的關閉責任，不可重複 `close()`。

2. **GPU Reset 復原機制**:
   - 當捕捉到 GPU 重置（`onGpuReset()`）：
     1. 將 `mGpuHealthy` 設為 `false`。
     2. 遍歷目前登錄之所有 `AHardwareBuffer` 並呼叫 `AHardwareBuffer_release()`。
     3. 清空 `mActiveBuffers = 0`。
     4. 發送 Wayland 視窗重繪（Re-create Surface）事件給 Guest Sommelier Proxy。

3. **格式不相容 Fallback**:
   - 當傳入 `YUV_420` 或不支援之格式時，`negotiateFormat()` 自動 Fallback 至 `PixelFormat::ARGB_8888`，並輸出警告 Log，防止 SurfaceFlinger 合成失敗崩潰。

---

## 5. Verification Method (驗證方法與步驟)

實作完成後，可透過以下步驟與指令進行獨立驗證：

### 5.1 代碼位置檢查與驗證
1. 檢視 `system/linux_bridge/wayland_buffer_sharing.cpp`:
   - 確認 `importDmaBufToHardwareBuffer` 已移除 `dmaBufFd + 0x1000` 假指標，改為 NDK `AHardwareBuffer` 轉換。
   - 確認 `bindHardwareBufferToSurfaceControl` 已包含 `ASurfaceTransaction_create`、`ASurfaceTransaction_setBuffer` 及 `ASurfaceTransaction_apply`。
   - 確認 `releaseBuffer` 已包含 `AHardwareBuffer_release`。

2. 檢視 `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java`:
   - 確認 `commitFrame` 已呼叫 JNI 或 `SurfaceControl.Transaction.setBuffer(...)`。

3. 檢視 `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`:
   - 確認 `surfaceCreated` 已將 `mSurfaceView.getSurfaceControl()` 傳回至 `LinuxWindowBridgeService`。

### 5.2 編譯與單元測試命令
1. **編譯原生 Bridge Daemon 與 Service**:
   ```bash
   m linux_bridge
   m LinuxWindowBridgeService
   ```

2. **執行 E2E 與測試套件**:
   ```bash
   python3 tests/e2e/runner.py --module M4
   pytest tests/e2e/test_m4_wayland.py
   ```

3. **SurfaceFlinger 診斷與 Buffer 檢視**:
   ```bash
   adb shell dumpsys SurfaceFlinger --latency
   adb shell dumpsys SurfaceFlinger | grep -A 10 "LinuxAppProxyActivity"
   ```
   *驗證亮點: `LinuxAppProxyActivity` 的 Surface 是否成功附加 AHardwareBuffer 且 Frame count 持續增加。*
