# AOSP Dual-OS R1 & R2 技術分析與架構設計報告 (Technical Analysis & Architectural Specification Report)

---

## 1. 參考文件與基礎資訊 (References & Baseline Metadata)

- **原始需求檔案**: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
- **技術藍圖規範**: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`
- **專案工作目錄**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/`
- **調查目標範圍**:
  - **R1**: AOSP Framework Architecture & `LinuxManagerService`
  - **R2**: AVF / crosvm / KVM Guest Setup & LUKS Storage Encryption

---

## 2. R1: AOSP Framework 架構與 LinuxManagerService 深層設計

### 2.1 API 套件結構與 Public/Platform 命名空間

AOSP Framework 層需擴充 `android.system.linux` 公開/系統級 Package API，提供應用程式與系統 UI 呼叫 Linux 虛擬化服務之統一介面：

1. **`android.system.linux.LinuxManager`** (Client Manager API)
   - 供 Android 應用（如 Terminal App、Settings、Launcher）取得 `ILinuxManager` Binder 服務代理。
   - 封裝非同步與同步 IPC 呼叫、狀態監聽器註冊、Terminal Session 建立與 Terminal 輸入輸出資料串流。
2. **`android.system.linux.LinuxAppInfo`** (Parcelable Data Object)
   - 封裝 Linux Guest 內 `.desktop` 應用之 App ID、名稱、圖示 Bitmap (ByteArray)、Exec 命令、類別與 MimeTypes。
3. **`android.system.linux.ILinuxManager.aidl`** (Core AIDL Interface)
   - 定義 Host 與 Client 之間的 Binder IPC 契約。
4. **`android.system.linux.ILinuxStatusCallback.aidl`** (Status Observer AIDL)
   - 用於回報 VM 狀態變更 (STOPPED, STARTING, RUNNING, SUSPENDING, SUSPENDED, ERROR) 及資源佔用 (RAM, CPU)。
5. **`android.system.linux.ILinuxTerminalCallback.aidl`** (Terminal Data Stream AIDL)
   - 用於從 Guest PTY 接收位元組資料 (stdout/stderr) 及 PTY 狀態事件 (Session closed, Bell, Title change)。

---

### 2.2 完整 AIDL 介面定義 (AIDL Specifications)

#### `ILinuxManager.aidl`
```idl
// frameworks/base/core/java/android/system/linux/ILinuxManager.aidl
package android.system.linux;

import android.system.linux.LinuxAppInfo;
import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.os.ParcelFileDescriptor;

/** {@hide} System-level API for controlling the Linux Guest VM and sessions. */
interface ILinuxManager {
    // Lifecycle Management
    int getState();
    boolean startVm();
    boolean stopVm(boolean force);
    boolean suspendVm();
    boolean resumeVm();
    
    // Terminal Session Management
    String createTerminalSession(int width, int height, ILinuxTerminalCallback callback);
    void resizeTerminalSession(String sessionId, int width, int height);
    void closeTerminalSession(String sessionId);
    void writeTerminalInput(String sessionId, in byte[] data);
    
    // Linux App & Storage Management
    List<LinuxAppInfo> getInstalledApps();
    boolean launchLinuxApp(String appId, int displayId);
    boolean installGuestImage(in ParcelFileDescriptor imageFd, long sizeBytes);
    
    // Status Callbacks
    void registerStatusCallback(ILinuxStatusCallback callback);
    void unregisterStatusCallback(ILinuxStatusCallback callback);
}
```

#### `ILinuxStatusCallback.aidl`
```idl
// frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl
package android.system.linux;

/** {@hide} */
oneway interface ILinuxStatusCallback {
    void onStateChanged(int oldState, int newState);
    void onError(int errorCode, String errorMessage);
    void onResourceUsageUpdated(long ramUsedBytes, float cpuUsagePercent);
}
```

#### `ILinuxTerminalCallback.aidl`
```idl
// frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl
package android.system.linux;

/** {@hide} */
oneway interface ILinuxTerminalCallback {
    void onDataReceived(in byte[] data);
    void onTitleChanged(String title);
    void onSessionClosed(int exitCode);
}
```

---

### 2.3 SystemServer 整合與系統服務架構 (`com.android.server.linux`)

#### 服務清單與責任劃分
1. **`LinuxManagerService`** (`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`)
   - 繼承自 `SystemService` 並實作 `ILinuxManager.Stub`。
   - 負責核心 VM 生命週期控制、狀態機轉換、呼叫 `VirtualizationService` (AVF)、驗證 Client 的 `android.permission.MANAGE_LINUX_ENVIRONMENT` 權限。
2. **`LinuxBridgeService`** (`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`)
   - 管理底層 vsock 通訊管道（連接至獨立守護進程 `linux_bridge`）。
   - 負責分發控制 RPC 訊息、Terminal PTY 數據串流及 Wayland 視窗控制協定。
3. **`LinuxPortalService`** (`frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`)
   - 負責處理來自 Guest 的硬體 Portal 請求 (Camera, Mic, Location)。
   - 對接 Host `AppOpsManager` 與 `PermissionManager`，當 Guest App 請求硬體時調起原生 Android 授權對話框。

#### `SystemServer` 註冊機制
在 `frameworks/base/services/java/com/android/server/SystemServer.java` 的 `startOtherServices()` 方法中依序初始化並註冊服務：

```java
// SystemServer.java 整合程式碼範例
t.traceBegin("StartLinuxManagerService");
try {
    LinuxManagerService linuxManager = new LinuxManagerService(context);
    ServiceManager.addService(Context.LINUX_MANAGEMENT_SERVICE, linuxManager);
    mSystemServiceManager.startService(LinuxManagerService.class);
} catch (Throwable e) {
    reportWtf("starting LinuxManagerService", e);
}
t.traceEnd();
```

---

### 2.4 系統進程隔離與 SystemServer Crash Blast Radius 防護

為了防止與 Linux Guest VM 之 vsock 數據解析或檔案串流異常引發 `system_server` 崩潰（Crash Blast Radius）：
1. **獨立進程架構 (Daemon Isolation)**:
   - 複雜的 vsock 封包解析、協定解封裝、媒體串流傳輸由獨立的 C++/Rust 守護進程 **`linux_bridge`** 處理（執行於獨立 SELinux Domain `u:r:linux_bridge:s0`）。
   - `LinuxBridgeService` 在 `system_server` 中僅透過受控之 Local Socket / Unix Domain Socket 與 `linux_bridge` 守護進程通訊。
2. **容錯與自動復原 (Fault Tolerance & Self-Healing)**:
   - 若 `linux_bridge` 或 Guest VM 發生 Out-Of-Memory (OOM) 或程式崩潰，`system_server` 不受任何影響。
   - `LinuxManagerService` 會捕獲 Death Notification (`IBinder.DeathRecipient` / Socket Disconnect)，自動清理 Session 資源，並重新啟動 `linux_bridge` daemon 與將 VM 重置為可控狀態。

---

## 3. R2: AVF / crosvm / KVM Guest 設定與 LUKS 儲存加密深層設計

### 3.1 AVF / crosvm / KVM Hypervisor 選擇與 Non-Protected 模式分析

#### 方案對比 (Non-Protected VM vs. Protected Microdroid VM)

| 評估維度 | Protected VM (Microdroid) | Non-Protected VM (本專案選定) |
|---|---|---|
| **核心目的** | 執行極高安全性、不可變之單一任務 (DRM, Keymaster) | 執行完整 GNU/Linux (Debian 12) 桌面/開發環境 |
| **檔案系統寫入** | 僅支援唯讀 RootFS，禁止動態寫入 | 支援 OverlayFS 系統寫入與動態擴充磁碟 |
| **APT 套件安裝** | ❌ 不支援 (無法安裝新軟體) | ✅ 完整支援 `apt-get install` |
| **記憶體共享** | 記憶體強制加密 (pKVM)，無法共享 Framebuffer | ✅ 支援 virtio-gpu / dma-buf 高效視窗共享 |
| **隔離邊界** | 隔離 (Hardware/pKVM Strict Memory Protection) | 隔離 (Standard KVM Hypervisor & seccomp) |

**決策結論**: 採用 **Non-Protected VM 模式**。在確保 Hypervisor (KVM) 強度隔離的同時，滿足 Linux 完整生態系的自由擴充與 Wayland GUI 無縫轉發需求。

---

### 3.2 Debian 12 ARM64 映像檔架構與磁碟分區佈局

Linux Guest 採用分層 (Tiered) 磁碟架構，儲存於 Host 的 `/data/system/linux/` 目錄：

```
/data/system/linux/
├── base_rootfs.img    (Read-Only EROFS/ext4 基礎系統, ~2.5GB)
├── custom_overlay.img (Writable ext4 OverlayFS, 記錄 /etc, /var 變更)
├── user_home.img      (Writable ext4, 掛載至 /home, 綁定 LUKS CE 加密)
└── vm_state.snapshot  (快照檔，包含 vCPU/RAM 狀態，實現 <300ms Suspend/Resume)
```

1. **`base_rootfs.img`**: 官方標籤 Debian 12 Bookworm (ARM64) 根目錄，採唯讀 (EROFS) 格式，確保系統核心檔不被竄改，亦為 A/B 無縫升級與 Rollback 的基礎。
2. **`custom_overlay.img`**: 透過 Linux OverlayFS 疊加於 `base_rootfs.img` 之上，儲存使用者在 `/etc` 或 `/usr/local` 進行的系統級設定。
3. **`user_home.img`**: 存放使用者個人資料與工作檔案 (`/home/debian`)。獨立分區並套用強加密防護。

---

### 3.3 LUKS CE (Credential Encrypted) 儲存加密與密鑰生命週期

為了防止裝置丟失或未解鎖狀態下 Linux 個人資料遭實體擷取，`user_home.img` 採用 **LUKS2 (dm-crypt)** 加密：

```
+-----------------------------+           +-----------------------------+
|   Android Keystore / TEE    |           |   Linux Guest VM (crosvm)   |
| (Master Key / CE Key Lock)  |           |                             |
+--------------+--------------+           +--------------+--------------+
               |                                         |
    [Device Unlock PIN/Bio]                              |
               |                                         |
               v                                         v
+-----------------------------+           +-----------------------------+
| LinuxManagerService (Host)  |           | user_home.img (LUKS2 Volume)|
| Derive LUKS Passphrase      |==========>| Mounts to /home/debian      |
+-----------------------------+ vsock/boot+-----------------------------+
```

#### 加密密鑰生命週期 (Encryption Key Lifecycle):
1. **金鑰生成與衍生**: 首次初始化時，`LinuxManagerService` 透過 Android Keystore 生成位元組長度為 256-bit 的 Master Key，並將其與 Android 的 CE (Credential Encrypted) Storage Key 綁定。
2. **裝置鎖定狀態 (Locked State)**: 當手機處於鎖屏/未解鎖狀態時，CE 儲存區不可讀，`LinuxManagerService` 無法存取 LUKS 密鑰。
3. **裝置解鎖與掛載 (Unlock & Mount)**:
   - 使用者解鎖 Android (PIN / 指紋 / 臉部)。
   - Android OS 解密 CE 儲存區，`LinuxManagerService` 取得 LUKS Passphrase。
   - VM 啟動或掛載分區時，Passphrase 經由安全記憶體管道傳送至 `crosvm` / LUKS mapping 驅動，解密並掛載 `user_home.img` 至 `/home/debian`。
4. **裝置鎖定與抹除 (Lock & Wipe)**: 當 Android 裝置執行原廠重置或 MDM 抹除指令時，CE Key 自動銷毀，使 `user_home.img` 變成不可解密的隨機資料。

---

### 3.4 Authenticated Vsock RPC Handshake 與安全通訊協定

Host 與 Guest 之間的唯一 IPC 通道為 **`virtio-vsock` (AF_VSOCK)**，零網路 Socket 開銷，且天然免疫外部 IP 網路掃描。

#### 埠號規劃 (Port Assignment)
- **Port 5000 (Control & RPC Channel)**: Protobuf / gRPC 控制協定，包含 VM 生命週期回報、應用程式清單同步、Portal 權限請求。
- **Port 5001 (Terminal PTY Data Channel)**: 原始位元組串流，用於 Native Terminal 與 Guest PTY 之間的 stdin/stdout/stderr 數據傳輸。
- **Port 5002 (Wayland Display Control Channel)**: 傳送 Wayland 視窗建立、調整大小、 Focus 狀態與 Surface Control 參數。

#### 雙向認證握手流程 (HMAC-SHA256 Auth Handshake Protocol)

```
[ Host: LinuxManagerService ]                 [ Guest: android-bridge-agent ]
              |                                              |
 1. Generate 256-bit Nonce & Session Key                     |
 2. Pass Nonce via crosvm cmdline (kernel bootparam)         |
              |--------------------------------------------->|
              |                                   3. Read Nonce from cmdline
              |                                   4. Compute Response = HMAC_SHA256(Nonce, Secret)
              |<=============================================|
              | 5. Connect Port 5000 & Send Response         |
 6. Verify Response == Expected                               |
 7. Auth SUCCESS: Open Port 5001/5002                        |
```

1. **Nonce 注入**: Host 啟動 VM 時，隨機生成一個單次有效之 256-bit Nonce 及 Session Secret，並透過 `crosvm` boot parameter / cmdline 注入至 Guest Kernel 環境變數。
2. **Guest 計算回應**: Guest `systemd` 啟動 `android-bridge-agent`，讀取 Nonce 並使用預共享 HMAC-SHA256 演算法計算 Response 簽章。
3. **Vsock 握手驗證**: `android-bridge-agent` 主動連線至 Host vsock Port 5000 並傳送驗證封包。
4. **通道開啟**: Host `LinuxBridgeService` 驗證簽章成功後，方才標記 VM 為 `AUTHENTICATED` 狀態，並允許 Terminal PTY (5001) 與 Wayland Display (5002) 建立數據傳輸。

---

### 3.5 Guest 啟動流程與服務堆疊 (Guest Boot Sequence & Service Stack)

```
+-------------------------------------------------------------------+
| 1. KVM / crosvm Hypervisor Initialization                        |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
| 2. Linux Kernel 6.x Boot (virtio drivers: vsock, gpu, fs, snd)   |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
| 3. systemd (PID 1) Initialization & Multi-User Target            |
+-------------------------------------------------------------------+
                                  |
      +---------------------------+---------------------------+
      |                           |                           |
      v                           v                           v
+------------------+    +-------------------+    +------------------+
| android-bridge-  |    | pty-agent         |    | Sommelier        |
| agent            |    | (Terminal PTY)    |    | (Wayland Proxy)  |
+------------------+    +-------------------+    +------------------+
      |                           |                           |
      +---------------------------+---------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
| 4. portal-agent (Intercepts XDG Desktop Portal Requests)          |
+-------------------------------------------------------------------+
```

1. **`systemd` (PID 1)**: 標準 GNU/Linux 啟動流程，管理所有 Linux 後台服務與 PID 生命週期。
2. **`android-bridge-agent`**: vsock 通訊核心守護進程，處理 Host 的控制 RPC 與授權握手。
3. **`pty-agent`**: 監聽來自 Host 的 Terminal Session 請求，動態調用 `openpty()` 分配 `/dev/pts/N` 設備，並將輸入輸出繫結至 vsock Port 5001。
4. **`Sommelier` (Wayland Proxy)**: 轉發 Wayland 視窗命令與 Buffer 至 virtio-gpu / vsock Port 5002。
5. **`portal-agent`**: 實作 DBus 服務 `org.freedesktop.impl.portal.Desktop`，將 Linux GUI 應用的相機、定位、麥克風請求轉發至 Host `LinuxPortalService`。

---

## 4. R1 & R2 功能清單與 API 規格對照表 (Feature Inventory Matrix)

| 功能編號 | 模組類別 | API / 介面名稱 | 權限 / SELinux Domain | 描述與行為規範 |
|---|---|---|---|---|
| **F-R1-01** | Framework API | `LinuxManager.getState()` | `MANAGE_LINUX_ENVIRONMENT` | 取得當前 Linux VM 狀態 (`STATE_STOPPED`, `STATE_RUNNING`, `STATE_SUSPENDED` 等)。 |
| **F-R1-02** | Framework API | `LinuxManager.startVm()` | `MANAGE_LINUX_ENVIRONMENT` | 觸發 AVF / crosvm 啟動 Guest VM，完成 vsock HMAC 認證。 |
| **F-R1-03** | Framework API | `LinuxManager.stopVm(force)` | `MANAGE_LINUX_ENVIRONMENT` | 向 Guest 發送 `poweroff` 訊號；若 `force=true` 則強制 SIGKILL crosvm。 |
| **F-R1-04** | Framework API | `LinuxManager.suspendVm()` | `MANAGE_LINUX_ENVIRONMENT` | 觸發 crosvm 產生 `vm_state.snapshot` 快照並凍結 vCPU 與記憶體。 |
| **F-R1-05** | Framework API | `LinuxManager.resumeVm()` | `MANAGE_LINUX_ENVIRONMENT` | 快速載入 `vm_state.snapshot` 快照，於 <300ms 內恢復 VM 執行。 |
| **F-R1-06** | Framework API | `createTerminalSession()` | `MANAGE_LINUX_ENVIRONMENT` | 請求 Guest `pty-agent` 建立 `/dev/pts/N` 並返回 `sessionId` 與位元組串流。 |
| **F-R1-07** | Framework API | `resizeTerminalSession()` | `MANAGE_LINUX_ENVIRONMENT` | 發送 `ioctl(TIOCSWINSZ)` 變更指定 Terminal Session 之行列數與像素大小。 |
| **F-R1-08** | System Service | `LinuxManagerService` | `u:r:system_server:s0` | 實作 `ILinuxManager.Stub`，管理全域 VM 生命週期與狀態回報。 |
| **F-R1-09** | Native Daemon | `linux_bridge` | `u:r:linux_bridge:s0` | 獨立進程，隔離 vsock 通訊解析，防止 system_server 崩潰。 |
| **F-R1-10** | System Service | `LinuxPortalService` | `u:r:system_server:s0` | 處理 Guest 硬體 Portal 請求，介接 `AppOpsManager` 與 Android 原生 UI 對話框。 |
| **F-R2-01** | VM Runtime | Non-Protected crosvm | `u:r:crosvm:s0` | 啟動 Debian 12 ARM64 獨立 Kernel (Linux 6.x)，不使用 pKVM 記憶體鎖定。 |
| **F-R2-02** | Storage Layout | `base_rootfs.img` | `linux_vm_data_file` | Read-Only EROFS 基礎系統映像檔 (~2.5GB)，支援 A/B OTA 升級。 |
| **F-R2-03** | Storage Layout | `custom_overlay.img` | `linux_vm_data_file` | 寫入型 ext4 OverlayFS 分區，保存 Guest `/etc` 與系統組態修改。 |
| **F-R2-04** | Storage Layout | `user_home.img` | `linux_vm_data_file` | ext4 分區，綁定 Android CE Key 之 LUKS2 加密，掛載至 Guest `/home`。 |
| **F-R2-05** | Security / Key | LUKS Passphrase Management | Android Keystore / CE Key | 使用者解鎖 Android 時衍生 Passphrase 解密 `user_home.img`，鎖屏時保護資料。 |
| **F-R2-06** | Transport Protocol | vsock HMAC-SHA256 Auth | `AF_VSOCK` (Port 5000) | Host 隨機 Nonce 注入，Guest 回傳 HMAC 簽章，防止未授權 vsock 存取。 |
| **F-R2-07** | Guest Daemon | `android-bridge-agent` | Guest `root` (PID < 500) | Guest 內主控制守護進程，響應 Host RPC 指令與維護 vsock 服務。 |
| **F-R2-08** | Guest Daemon | `pty-agent` | Guest `root` | 負責動態創建 Linux PTY 並提供位元組層級雙向串流。 |

---

## 5. 結論與下一步建議 (Conclusion & Next Steps)

本分析報告已為 **R1 (AOSP Framework Architecture & LinuxManagerService)** 與 **R2 (AVF / crosvm / KVM Guest Setup & LUKS Storage Encryption)** 建立了完整且嚴密的技術規範與介面定義。

1. **R1 架構結論**: 採用 `LinuxManagerService` (SystemServer) + 獨立 `linux_bridge` daemon 的混合隔離架構，既能維持高權限與系統級開機啟動，又徹底消除了 `system_server` 遭受 vsock 數據解析攻擊崩潰的風險。
2. **R2 架構結論**: 採用 Non-Protected crosvm VM 模式搭配 Debian 12 ARM64 發行版，解決了 Protected VM 無法自由寫入與安裝 APT 的痛點；同時透過 LUKS2 加密與 Android CE Storage Key 繫結，確保了高標準的個人隱私資料安全。

**建議下一步**:
請 Orchestrator 進行 Task 分派與驗收審查，接著推進 R3 (Native Touch Terminal & Custom IME InputConnection) 與 R4 (Seamless Wayland GUI Window Forwarding) 之研析與實作。
