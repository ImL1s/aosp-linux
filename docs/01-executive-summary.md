# 第一章：執行摘要

> 研究日期：2026-08-06  
> AOSP branch：refs/heads/main（android.googlesource.com）  
> 最新 commit：Merge 'pvmfw: fdt: Allow PSCI v0.2 in input DT' into main  
> tree hash：d1dfcf5819575b6ce7388c062988c9c4f00305a8

---

## 1.1 專案核心命題

本專案目標是直接修改 AOSP，打造一個新的作業系統，在單一裝置上同時具備完整的 Android 執行環境與真正的 GNU/Linux 發行版，且兩者無縫整合為一個統一的使用者體驗。

核心口號：**「一個 AOSP 產品，兩個隔離的執行環境，一個統一的使用者體驗。」**

這不是雙開機（dual-boot），不是 Termux，不是 chroot，不是 PRoot，不是在 Android userspace 模擬 Linux。這是在 Android Host 之上，透過 AVF（Android Virtualization Framework）+ crosvm + KVM，運行一個具有自己的 Linux kernel、systemd、glibc、UID 0 root、APT 套件管理器的完整 Debian 12 ARM64 虛擬機器，並透過原生 Android UI 無縫整合。

## 1.2 最終架構決策（ADR-001）

**採用：AVF + crosvm + KVM + non-protected VM + Debian 12 ARM64 + authenticated vsock bridge + Android 原生整合 UI**

理由：

1. AVF 是 AOSP 官方虛擬化框架，已有 VirtualizationService AIDL、virtmgr（Rust）、crosvm（/apex/com.android.virt/bin/crosvm）完整基礎設施
2. Non-protected VM 比 protected VM 提供更大工程彈性，允許自訂 kernel 與 rootfs，同時不需要 pvmfw 驗證鏈（pvmfw 目前僅支援 AArch64 且有嚴格限制）
3. KVM 是 Linux 原生 hypervisor，硬體加速成熟，ARM64 支援完整
4. Debian 12 提供 glibc、systemd、APT、arm64 套件庫，商業發佈條件明確（Debian Free Software Guidelines）
5. AF_VSOCK 是 VM-Host 安全通訊的正確邊界，避免 IP-based 偽造
6. Android 原生 Terminal UI 取代現有 WebView/ttyd 方案，提供真正低延遲觸控體驗

## 1.3 現有 AOSP 已證實能力（第一手原始碼驗證）

以下均為直接查詢 android.googlesource.com 原始碼確認：

**EXISTING（已存在）：**
- `packages/modules/Virtualization/android/TerminalApp/` — 40 個 Kotlin 檔案
- `VirtualizationService` AIDL：6 個命名空間，IVirtualizationService 15 個方法
- `IVirtualMachineCallback` oneway interface
- `virtmgr`（Rust）：/apex/com.android.virt/bin/crosvm
- `IVmPayloadService`（Guest-side AIDL）
- `pvmfw`：AArch64-only，load@0x7fc0_0000
- SELinux：`crosvm.te`, `early_virtmgr.te`, `linux_vm_setup.te`
- `MemBalloonController`：動態記憶體管理
- `debian_service` proto：Host-side gRPC server
- `VmInstance` lifecycle：5層架構（Java API → VirtualizationService → virtmgr → crosvm → Guest）

**EXPERIMENTAL（實驗性）：**
- GPU：virglrenderer / gfxstream（`Flags.terminalGuiSupport()`，testing only）
- Display/keyboard/mouse/touch：`Flags.terminalGuiSupport()`
- Storage balloon：`Flags.terminalStorageBalloon()`

**現有缺陷與 TODO：**
- `b/372666638`：gRPC Java 不支援 vsock，現用 TCP + NSD/mDNS（安全漏洞）
- `b/373533555`：gRPC port 發現仍用 file，TODO 改用 mDNS
- TerminalView 是 WebView subclass（非原生渲染），使用 ttyd + xterm.js
- IP-based gRPC auth（`remoteAddr?.address?.hostAddress == ipAddress`）— 僅比對 IP，無加密身份驗證
- Display size 目前在啟動時固定，TODO 支援動態調整

**NEW（本專案需新增）：**
- `LinuxManagerService`（系統框架服務）
- `LinuxBridgeService`（vsock bridge daemon）
- `LinuxPortalService`（XDG portal bridge）
- `LinuxWindowBridgeService`（Wayland window forwarding）
- `LinuxAppRegistryService`（.desktop app 同步）
- Android 原生 Terminal Renderer（取代 WebView/ttyd）
- Per-user Linux 資料加密（LUKS2 + CE key binding）
- Guest A/B 更新機制
- Bridge RPC 協議（authenticated vsock over custom framing 或 gRPC-over-vsock）

## 1.4 不可行與不建議方案

詳見第四章。以下為核心結論：

- **雙開機**：違反「同時具備」的產品要求，排除
- **chroot/PRoot/Termux**：非真正獨立 kernel，排除
- **protected VM**：過度限制，無法安裝自訂 rootfs，排除（對本產品）
- **Waydroid 反向架構（Android in Linux）**：Android-in-Linux 不符合行動裝置場景，排除
- **ChromeOS Crostini 直接移植**：依賴 Chrome UI 基礎設施，不適合 Android-first，排除
- **permissive SELinux**：正式版本絕對禁止，排除

## 1.5 工程規模與時間預期

這是一個完整平台工程專案，預期規模：

- **工程師人數**：10-15 位平台工程師，各有專業分工
- **完整 1.0 時間線**：18-24 個月（含 Cuttlefish bring-up、安全稽核、CTS 驗證）
- **MVP（Cuttlefish + 基本 Terminal）**：3-4 個月
- **核心依賴**：ARM64 KVM 裝置、AOSP Android 15/16 Mainline

所有時間與人力估算均為粗略假設，實際依團隊能力與硬體狀況大幅調整。

## 1.6 文件結構

本技術規劃共 35 章，涵蓋：

1. 執行摘要（本章）
2. 最終架構決策
3. 現有 AOSP 能力實證
4. 不可行或不建議方案
5. 完整系統架構圖
6. Trust boundary
7. AOSP 原始碼修改地圖
8. Framework API 設計
9. Linux Guest 設計
10. VM lifecycle
11. Android/Linux Bridge
12. 原生觸控 Terminal 完整設計
13. Linux GUI 與 WindowManager
14. Launcher 與 Linux App 整合
15. 檔案共享
16. 網路
17. 音訊
18. 輸入
19. Hardware Portals
20. 電源與效能
21. 安全與 SELinux
22. OTA、Guest Update 與 Rollback
23. 多使用者與企業政策
24. 建置系統
25. 測試策略
26. CI/CD
27. 可觀測性與故障恢復
28. 產品 UX
29. 完整 Roadmap
30. 團隊拆分與依賴關係
31. P0/P1/P2/P3 風險表
32. 1.0 驗收矩陣
33. Architecture Decision Records
34. 尚待實機驗證事項
35. 官方來源與實際原始碼引用
