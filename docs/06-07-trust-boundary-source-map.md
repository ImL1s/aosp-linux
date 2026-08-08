# 第六章：Trust Boundary 詳細規範

## 6.1 信任層次定義

```
Layer 0 - Hardware (最高信任)
    ├── KVM hypervisor page tables
    ├── TrustZone / Secure World
    ├── KeyMint TEE
    └── Boot ROM / Verified Boot chain

Layer 1 - Android Kernel
    ├── init (PID 1, uid=0)
    ├── SELinux policy enforcement
    └── cgroup / namespace management

Layer 2 - System Services (高信任)
    ├── system_server (uid=1000)
    │   ├── LinuxManagerService [NEW]
    │   ├── ActivityManager
    │   ├── WindowManager
    │   └── ...
    ├── linux_bridge daemon (uid=linux_bridge) [NEW]
    └── crosvm (uid=crosvm) [EXISTING]

Layer 3 - Privileged Apps (中等信任)
    ├── Native Terminal App (priv-app)
    ├── Linux Settings App
    └── LinuxFiles Provider

Layer 4 - User Apps (低信任)
    └── Android Apps (APK, sandboxed)

Layer 5 - VM Boundary (Guest, 隔離)
    ├── android-bridge-agent (Guest UID 1000)
    ├── pty-agent (Guest daemon)
    ├── Guest root (UID 0, Guest-only)
    └── Linux user apps
```

## 6.2 跨邊界通訊規則

| 通訊方向 | 通道 | 認證方式 | 允許操作 | 禁止操作 |
|---------|------|---------|---------|---------|
| system_server → LinuxBridge | Binder (in-process) | SELinux | VM lifecycle, RPC routing | 無（內部） |
| LinuxBridge → Guest | AF_VSOCK + HMAC-SHA256 | 挑戰-回應 + session token | 受控 RPC | 直接 ptrace, 直接 /dev |
| Guest → LinuxBridge | AF_VSOCK + HMAC-SHA256 | 相同 | Portal 請求, 狀態上報 | 呼叫 Android Binder |
| Android App → LinuxManager | Binder (AIDL) | SELinux + signature perm | Terminal, app launch | 直接 VM config |
| Guest root → Host | 無直接通道 | N/A | N/A | 一切 Host 操作 |
| virtiofs | VirtIO device | File context in policy | 共享目錄讀寫 | 掛載 /data, /proc/sys |

## 6.3 禁止越界操作清單（NEVERALLOW）

以下操作在 SELinux policy 中必須有對應 neverallow：

```
neverallow linux_vm_domain android_data_file:dir { read write open };
neverallow linux_vm_domain radio_data_file:file { read write };
neverallow linux_vm_domain keystore_data_file:file { read write };
neverallow linux_vm_domain efs_file:file { read write };
neverallow linux_vm_domain su:process transition;
neverallow linux_vm_domain init:process transition;
neverallow linux_bridge self:capability sys_admin;
neverallow linux_bridge block_device:blk_file { read write };
neverallow { linux_bridge linux_portal } radio_service:service_manager find;
```

## 6.4 VM 邊界保證清單

1. **記憶體隔離**：KVM page table 硬體強制，Guest 無法讀取 Host 任何記憶體
2. **vsock 連線只在 VM 內有效**：每個 vsock 連線綁定到 CID，Host CID=2，Guest CID 為動態分配
3. **HMAC 挑戰**：每次 VM 啟動重新握手，防 replay attack
4. **Session token**：建立後的每個 RPC 請求必須帶 session token
5. **Guest image 簽章**：載入前必須驗證 AVB RSA-4096 簽章
6. **virtiofs path restriction**：只掛載 `/data/media/0/LinuxShared`，不掛載 `/data/data`
7. **no_new_privs**：linux_bridge 執行時設置 `PR_SET_NO_NEW_PRIVS`
8. **seccomp**：crosvm 和 linux_bridge 都有 seccomp 過濾

---

# 第七章：AOSP 原始碼修改地圖

## 7.1 完整修改目錄

### 7.1.1 新增路徑（NEW）

以下路徑**目前不存在**，需要本專案新增：

```
frameworks/base/
├── core/java/android/system/linux/          [NEW package]
│   ├── ILinuxManager.aidl
│   ├── ILinuxStatusCallback.aidl
│   ├── ILinuxTerminalCallback.aidl
│   ├── ILinuxAppCallback.aidl
│   ├── LinuxManager.java                    [Client API]
│   ├── LinuxAppInfo.java                    [Parcelable]
│   ├── LinuxVmState.java                    [enum]
│   ├── LinuxHealthStatus.java               [Parcelable]
│   └── LinuxResourceUsage.java              [Parcelable]
└── services/core/java/com/android/server/linux/    [NEW package]
    ├── LinuxManagerService.java
    ├── LinuxBridgeService.java
    ├── LinuxPortalService.java
    ├── LinuxWindowBridgeService.java
    ├── LinuxAppRegistryService.java
    ├── LinuxImageManager.java
    ├── LinuxVmController.java
    ├── LinuxPermissionManager.java
    └── LinuxNotificationManager.java

packages/apps/
├── LinuxTerminal/                           [NEW app]
│   ├── Android.bp
│   ├── AndroidManifest.xml
│   └── src/com/android/linux/terminal/
│       ├── TerminalActivity.java
│       ├── TerminalView.java                [Native Canvas, NOT WebView]
│       ├── TerminalSurface.java
│       ├── TerminalSession.java
│       ├── TerminalRenderer.java
│       ├── TouchModeController.java
│       ├── ime/TerminalInputConnection.java
│       ├── ime/CjkComposer.java
│       └── VsockPtyClient.java
└── LinuxSettings/                           [NEW app]
    └── (設定 UI)

system/
├── linux_bridge/                            [NEW daemon]
│   ├── Android.bp
│   ├── main.cpp
│   ├── vsock_server.cpp / .h
│   ├── rpc_router.cpp / .h
│   ├── auth.cpp / .h                        [HMAC-SHA256]
│   └── portal_forwarder.cpp / .h
└── sepolicy/private/
    ├── linux_manager.te                     [NEW]
    ├── linux_vm_launcher.te                 [NEW]
    ├── linux_bridge.te                      [NEW]
    ├── linux_image_manager.te               [NEW]
    ├── linux_portal.te                      [NEW]
    ├── linux_window_bridge.te               [NEW]
    ├── linux_file_bridge.te                 [NEW]
    ├── linux_terminal.te                    [NEW]
    └── linux_update_service.te              [NEW]
```

### 7.1.2 修改現有路徑（EXTEND）

```
frameworks/base/
├── services/java/com/android/server/SystemServer.java
│   ├── 修改目的: 在 startOtherServices() 中注冊 LinuxManagerService
│   ├── 修改類型: 新增幾行注冊代碼
│   ├── 影響: system_server 啟動序列
│   └── CTS 影響: 無（新服務）

├── core/java/android/app/SystemServiceRegistry.java
│   ├── 修改目的: 注冊 Context.LINUX_SERVICE
│   ├── 修改類型: 新增一個 registerService() 調用
│   └── 影響: Context.getSystemService() 返回 LinuxManager

├── core/res/AndroidManifest.xml
│   ├── 修改目的: 聲明 USE_LINUX_TERMINAL, MANAGE_LINUX_ENVIRONMENT 權限
│   ├── 修改類型: 新增 <permission> 元素
│   └── CTS 影響: 需通過 CtsSecurityHostTestCases

packages/modules/Virtualization/
├── android/TerminalApp/           [EXTEND + EVENTUALLY REPLACE]
│   ├── 短期: 可沿用 InstallerService, SettingsPortForwarding 等
│   ├── 中期: TerminalView 替換為原生 Canvas
│   └── 長期: 整體遷移到 LinuxTerminal app

Launcher3/
├── 修改目的: 顯示 Linux App 捷徑、隔離 Linux Task
├── 修改類型: 新增 LinuxAppTracker, LinuxShortcutManager
└── CTS 影響: Launcher3 CTS 需要額外覆蓋

Settings/
├── 修改目的: 新增 Linux 設定頁
├── 修改類型: 新增 Linux 設定 Fragment/Activity
└── 影響: Settings App

init scripts (product/):
├── 新增: linux_bridge.rc
├── 新增: linux_manager.rc
└── 影響: 系統服務啟動

Android.bp (top-level):
├── 新增: vendor/aosp-linux 模組引用
└── 影響: 編譯流程
```

## 7.2 修改項目詳細表

| 路徑 | 類型 | 目的 | Partition | 需 platform API | 影響 CTS | SELinux domain | 測試方式 |
|------|------|------|-----------|----------------|---------|---------------|---------|
| `frameworks/base/core/java/android/system/linux/` | NEW | Framework public API | system | ✅ | CtsSecurityTest | linux_manager | AIDL 單元測試 |
| `frameworks/base/services/core/.../linux/` | NEW | 系統服務實作 | system | ✅ | 輕微（新服務） | linux_manager | Instrumentation |
| `frameworks/base/services/java/.../SystemServer.java` | EXTEND | 服務注冊 | system | ✅ | 無 | system_server | Boot test |
| `packages/apps/LinuxTerminal/` | NEW | 原生 Terminal App | priv-app | ✅ | 輕微 | linux_terminal | UI 測試 |
| `packages/apps/LinuxSettings/` | NEW | 設定 UI | priv-app | ✅ | 無 | platform_app | UI 測試 |
| `system/linux_bridge/` | NEW | vsock bridge daemon | system | 否（native） | 無 | linux_bridge | Protocol 測試 |
| `system/sepolicy/private/*.te` | NEW | SELinux policy | system | 否 | CtsSeLinux | 多 domain | atest |
| `packages/modules/Virtualization/android/TerminalApp/` | EXTEND | 修復 auth, vsock | apex | 部份 | 輕微 | platform_app | Boot test |
| `Launcher3/` | EXTEND | Linux App 整合 | priv-app | 否 | Launcher3 CTS | launcher | atest |
| `Settings/` | EXTEND | Linux 設定 | priv-app | 否 | 無 | platform_app | atest |
| `init.rc` (product) | NEW | 服務啟動腳本 | product | 否 | 無 | init | 啟動測試 |

## 7.3 APEX / Mainline Module 邊界考量

現有 AVF 組件位於 `com.android.virt` APEX：
- 修改 AVF AIDL 介面需考慮向後相容性
- 建議：LinuxManagerService 不進 APEX，直接在 system partition
- crosvm 不直接修改，透過現有 VirtualizationService API 呼叫
- 若需擴充 VirtualizationService，需提交 upstream AOSP

## 7.4 product partition 策略

新增元件建議放在 `product` 或 `vendor` partition，避免污染 `system`：

```
product/
├── etc/init/linux_bridge.rc
├── etc/permissions/android.system.linux.xml
└── lib/linux_bridge/

# 或使用 vendor/aosp-linux/ local manifest
```

這樣可以讓 AOSP base 保持乾淨，本專案的修改隔離在 overlay。
