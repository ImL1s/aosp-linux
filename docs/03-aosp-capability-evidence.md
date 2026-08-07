# 第三章：現有 AOSP 能力實證

> 研究日期：2026-08-06  
> 來源：android.googlesource.com/platform/packages/modules/Virtualization refs/heads/main  
> 所有路徑、class、方法均直接從原始碼取得，非推測

---

## 3.1 AVF 基礎設施（EXISTING）

### VirtualizationService AIDL

**路徑**：`packages/modules/Virtualization/android/virtualizationservice/aidl/android/system/`

| 命名空間 | 狀態 |
|---------|------|
| `virtualizationcommon/` | EXISTING |
| `virtualizationmaintenance/` | EXISTING |
| `virtualizationservice/` | EXISTING |
| `virtualizationservice_internal/` | EXISTING |
| `virtualmachineservice/` | EXISTING |
| `vmtethering/` | EXISTING |

`IVirtualizationService` 方法（已驗證）：
```
createVm(config, consoleOutFd, consoleInFd, osLogFd, dumpDtFd)
allocateInstanceId()
initializeWritablePartition(imageFd, sizeBytes, type)
setEncryptedStorageSize(imageFd, size)
createOrUpdateIdsigFile(inputFd, idsigFd)
debugListVms()
getAssignableDevices()
getSupportedOSList()
getDebugPolicy()
isFeatureEnabled(feature)
enableTestAttestation()
isRemoteAttestationSupported()
isUpdatableVmSupported()
removeVmInstance(instanceId)
claimVmInstance(instanceId)
```

Feature flags（已驗證）：
```
FEATURE_DICE_CHANGES
FEATURE_LLPVM_CHANGES
FEATURE_MULTI_TENANT
FEATURE_NETWORK
FEATURE_REMOTE_ATTESTATION
FEATURE_VENDOR_MODULES
```

`IVirtualMachineCallback`（oneway interface，已驗證）：
```java
void onPayloadStarted(int cid);
void onPayloadReady(int cid);
void onPayloadFinished(int cid, int exitCode);
void onError(int cid, int errorCode, String message);
void onDied(int cid, DeathReason reason);
```

### virtmgr（EXISTING）

**路徑**：`packages/modules/Virtualization/android/virtmgr/`  
**語言**：Rust  
**源碼**：`aidl.rs`, `atom.rs`, `composite.rs`, `crosvm.rs`, `debug_config.rs`, `dt_overlay.rs`, `main.rs`, `payload.rs`, `selinux.rs`

crosvm 二進位位置：`/apex/com.android.virt/bin/crosvm`

`VmInstance` 方法（已驗證）：
```rust
new(), start(), monitor_vm_exit(), monitor_payload_hangup()
update_payload_state(), kill()
get_memory_balloon() / set_memory_balloon()
suspend() / resume() / resume_full()
handle_ramdump()
```

VM 狀態（已驗證）：
```rust
enum VmState {
    NotStarted { config },
    Running { child, monitor_thread },
    Dead,
    Failed,
}
```

PayloadState（已驗證）：
```
Starting -> Started -> Ready -> Finished -> Hangup
```

Death reason（已驗證，16 種）：
```
PVM_FIRMWARE_PUBLIC_KEY_MISMATCH
PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED
MICRODROID_FAILED_TO_CONNECT
MICRODROID_PAYLOAD_HAS_CHANGED
MICRODROID_PAYLOAD_VERIFICATION_FAILED
MICRODROID_INVALID_PAYLOAD_CONFIG
MICRODROID_UNKNOWN_RUNTIME_ERROR
HANGUP, KILLED, SHUTDOWN, START_FAILED, REBOOT, CRASH
WATCHDOG_REBOOT, INFRASTRUCTURE_ERROR, UNKNOWN
```

Boot timeout：30s（real device）/ 300s（nested virt）

### pvmfw（EXISTING，AArch64 only）

**路徑**：`packages/modules/Virtualization/guest/pvmfw/`  
load address：`0x7fc0_0000`  
config versions：v1.0 (DICE+DTBO)、v1.1 (+DA DTBO)、v1.2 (+VM reference DT)、v1.3 (+reserved memory)  
僅支援 AArch64；non-protected VM 可不使用 pvmfw

---

## 3.2 Terminal App（EXISTING + EXPERIMENTAL）

**路徑**：`packages/modules/Virtualization/android/TerminalApp/java/com/android/virtualization/terminal/`

### TerminalView.kt（EXISTING，已驗證）

**實際內容**：TerminalView 是 **WebView 的 subclass**，不是原生 Terminal 渲染器

```kotlin
class TerminalView(context: Context, attrs: AttributeSet?) :
    WebView(context, attrs),          // ← WebView subclass
    AccessibilityManager.AccessibilityStateChangeListener,
    AccessibilityManager.TouchExplorationStateChangeListener {
    
    private val ctrlKeyHandler = readAssetAsString(context, "js/ctrl_key_handler.js")
    private val touchToMouseHandler = readAssetAsString(context, "js/touch_to_mouse_handler.js")
    // 使用 JavaScript 注入控制 ttyd/xterm.js
    
    fun mapTouchToMouseEvent() { evaluateJavascript(touchToMouseHandler, null) }
    fun enableCtrlKey() { evaluateJavascript(enableCtrlKey, null) }
    // ...
}
```

**結論**：現有 Terminal UI 透過 WebView 載入 ttyd（網頁 terminal），使用 xterm.js 渲染。這不是原生渲染，有高延遲、IME 控制受限、CJK 輸入問題等缺陷。**本專案需完整替換**。

### VmLauncherService.kt（EXISTING，已驗證）

關鍵問題點（直接引用原始碼）：

```kotlin
// TODO(b/372666638): gRPC for java doesn't support vsock for now.
// 使用 TCP + NSD/mDNS 而非 vsock
val port = 0
server = OkHttpServerBuilder.forPort(port, InsecureServerCredentials.create())
    .intercept(interceptor)    // ← 只有 IP 比對，無加密認證
    .addService(debianService)
    .build().start()
```

IP-based 認證（已驗證的安全問題）：
```kotlin
val remoteAddr = call.attributes.get<SocketAddress?>(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
    as InetSocketAddress?
if (remoteAddr?.address?.hostAddress == ipAddress) {
    // Allow the request only if it is from VM
    return next.startCall(call, headers)
}
// ← 僅比對 IP，沒有加密身份驗證
```

Service 發現（NSD/mDNS，已驗證）：
```kotlin
queryInfo.serviceType = "_http._tcp"
queryInfo.serviceName = "ttyd"
nsdManager.registerServiceInfoCallback(queryInfo, executor, ...)
```

GPU（EXPERIMENTAL，已驗證）：
```kotlin
// 需要在 sdcard 放置特殊觸發文件才啟用
if (Files.exists(ImageArchive.getSdcardPathForTesting().resolve("virglrenderer"))) {
    builder.setGpuConfig(GpuConfig.Builder()
        .setBackend("virglrenderer")
        // ...
    )
}
```

Display/keyboard/mouse/touch（EXPERIMENTAL，flag-gated）：
```kotlin
if (Flags.terminalGuiSupport() && displayInfo != null) {
    builder.setDisplayConfig(...)
        .useKeyboard(true).useMouse(true).useTouch(true)
}
```

### 其他 Terminal App 元件

| 元件 | 狀態 | 備注 |
|------|------|------|
| `MemBalloonController` | EXISTING | 動態記憶體氣球管理 |
| `StorageBalloonWorker` | EXPERIMENTAL | `Flags.terminalStorageBalloon()` |
| `InstallerService` | EXISTING | 負責下載/安裝 Debian image |
| `DebianServiceImpl` | EXISTING | Host-side gRPC server |
| `PortNotifier` | EXISTING | port forwarding 通知 |
| `SettingsPortForwardingActivity` | EXISTING | port forwarding 設定 UI |
| `SettingsRecoveryActivity` | EXISTING | recovery 功能 |

---

## 3.3 Guest 元件（EXISTING）

**路徑**：`packages/modules/Virtualization/guest/`  
包含 20 個 guest 模組：

| 路徑 | 狀態 | 說明 |
|------|------|------|
| `microdroid_manager/` | EXISTING | Microdroid 的 Guest-side 管理器 |
| `pvmfw/` | EXISTING | pVM firmware（AArch64 only） |
| `encryptedstore/` | EXISTING | 加密儲存支援 |
| `authfs/` | EXISTING | 認證文件系統 |
| `storage_balloon_agent/` | EXPERIMENTAL | 儲存氣球 Agent |
| `zipfuse/` | EXISTING | APK zip 掛載 |
| `forwarder_guest/` | EXISTING | Guest port forwarder |
| `kdump/` | EXISTING | kernel crash dump |
| `kernel/` | EXISTING | Guest kernel 設定 |

`IVmPayloadService`（Guest-side，已驗證）：
```
notifyPayloadReady()
getVmInstanceSecret(identifier, size)
writePayloadRpData(data[32])
readPayloadRpData() -> @nullable byte[32]
getDiceAttestationChain()
getDiceAttestationCdi()
requestAttestation(challenge, testMode) -> AttestationResult
isNewInstance()
```

---

## 3.4 SELinux（EXISTING）

**路徑**：`system/sepolicy/private/`

已確認存在的 virtualization 相關 policy 文件：
- `crosvm.te` ← EXISTING
- `early_virtmgr.te` ← EXISTING
- `linux_vm_setup.te` ← EXISTING
- `microfuchsiad.te` ← EXISTING
- `compos_fd_server.te` ← EXISTING
- `composd.te` ← EXISTING
- `compos_verify.te` ← EXISTING

**不存在（需要本專案新增）**：
- `linux_manager.te` ← NEW
- `linux_bridge.te` ← NEW
- `linux_portal.te` ← NEW
- `linux_window_bridge.te` ← NEW
- `linux_terminal.te` ← NEW

---

## 3.5 debian_service Proto（EXISTING）

**路徑**：`packages/modules/Virtualization/libs/debian_service/proto/`

架構：Host 作為 gRPC server，Guest 作為 client，透過 TCP（非 vsock，因 b/372666638）。

本專案將此架構**翻轉並加強**：

| 現有 | 本專案方向 |
|------|------------|
| Host 作 server（debian_service） | 雙向 RPC，vsock 取代 TCP |
| TCP + NSD/mDNS 發現 | AF_VSOCK，無需服務發現 |
| IP-based 認證 | HMAC-SHA256 token + vsock 原生隔離 |
| 僅 Debian service（Host→Guest） | 完整雙向 Bridge（5個 port） |

---

## 3.6 libs 層（EXISTING）

| 函式庫 | 狀態 | 說明 |
|--------|------|------|
| `framework-virtualization` (Java API) | EXISTING | `android.system.virtualmachine.*` |
| `libvm_payload` (C/C++) | EXISTING | Guest payload C API |
| `libvmbase` | EXISTING | bare-metal VM base |
| `libvmclient` (Rust) | EXISTING | Rust VM client |
| `libvirtualization_jni` | EXISTING | JNI bridge |
| `android_display_backend` | EXISTING | GPU display backend |
| `debian_service` proto | EXISTING | gRPC proto definitions |
| `libforwarder` | EXISTING | port forwarder |
| `libhypervisor_backends` | EXISTING | hypervisor 抽象層 |
| `libservice_vm_comm` | EXISTING | service VM 通訊 |
| `dice` | EXISTING | DICE attestation |
| `avf_features` | EXISTING | feature flags |
| `vmconfig` | EXISTING | VM 設定結構 |
| `apkverify` | EXISTING | APK 驗證 |
| `nested_virt` | EXISTING | 巢狀虛擬化支援 |

---

## 3.7 AOSP 能力狀態總表

| 能力 | 狀態 | 說明 |
|------|------|------|
| AVF AIDL 完整定義 | EXISTING | 可直接使用 |
| crosvm 整合 | EXISTING | /apex/com.android.virt/bin/crosvm |
| VM lifecycle 管理 | EXISTING | start/stop/suspend/resume |
| MemBalloon 控制 | EXISTING | 動態記憶體 |
| virtio-vsock | EXISTING | AF_VSOCK 可用 |
| virtio-blk | EXISTING | 磁碟映像 |
| virtio-fs | EXISTING | 主機檔案共享 |
| virtio-snd（基礎）| EXISTING | 音訊虛擬化 |
| virtio-gpu（virglrenderer）| EXPERIMENTAL | Testing only |
| virtio-gpu（gfxstream）| EXPERIMENTAL | Testing only |
| Display forwarding | EXPERIMENTAL | Flags.terminalGuiSupport() |
| Keyboard/mouse/touch | EXPERIMENTAL | Flags.terminalGuiSupport() |
| Guest Debian 映像安裝 | EXISTING | InstallerService |
| Terminal WebView UI | EXISTING | 但非原生，需替換 |
| gRPC over vsock | NOT SUPPORTED | b/372666638，需用 native/C++ |
| Android 原生 Terminal UI | NEW | 需本專案新增 |
| vsock authenticated RPC | NEW | 需本專案新增 |
| Linux App Wayland forwarding | NEW | 需本專案新增 |
| LinuxManagerService | NEW | 需本專案新增 |
| XDG Portal bridge | NEW | 需本專案新增 |
| .desktop app 同步 | NEW | 需本專案新增 |
| Guest A/B update | NEW | 需本專案新增 |
| per-user CE encryption | NEW | 需本專案新增 |
