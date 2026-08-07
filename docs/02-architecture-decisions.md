# 第二章：最終架構決策

## 2.1 核心架構圖

```
+═══════════════════════════════════════════════════════════════════════════════════+
║              AOSP Android Host (Android 15/16 Mainline)                          ║
╠═══════════════════════════════════════════════════════════════════════════════════╣
║  ┌─────────────┐  ┌────────────┐  ┌─────────────────┐  ┌──────────────────────┐ ║
║  │  Android    │  │  Android   │  │   Android Apps  │  │   Linux GUI Apps     │ ║
║  │    Apps     │  │  Terminal  │  │   (APK/ART)     │  │   (as Android Tasks) │ ║
║  └──────┬──────┘  └─────┬──────┘  └────────┬────────┘  └──────────┬───────────┘ ║
║         │               │                  │                       │             ║
║  ┌──────▼───────────────▼──────────────────▼───────────────────────▼───────────┐ ║
║  │              Android Framework Layer                                         │ ║
║  │  ActivityManager │ WindowManager │ PackageManager │ DisplayManager          │ ║
║  │  InputManager    │ SystemUI      │ Launcher3      │ StorageManager          │ ║
║  └──────────────────────────────┬───────────────────────────────────────────────┘ ║
║                                  │                                               ║
║  ┌───────────────────────────────▼───────────────────────────────────────────┐   ║
║  │              LinuxManager Framework Services                               │   ║
║  │  LinuxManagerService  │ LinuxBridgeService  │ LinuxWindowBridgeService    │   ║
║  │  LinuxPortalService   │ LinuxAppRegistry    │ LinuxImageManager           │   ║
║  └───────────────────────────────┬───────────────────────────────────────────┘   ║
║                                  │                                               ║
║  ┌───────────────────────────────▼───────────────────────────────────────────┐   ║
║  │              AVF / VirtualizationService / virtmgr                         │   ║
║  │              crosvm (/apex/com.android.virt/bin/crosvm)                   │   ║
║  │                                                                            │   ║
║  │  virtio-vsock │ virtio-blk │ virtio-fs │ virtio-snd │ virtio-gpu          │   ║
║  └───────────────────────────────┬───────────────────────────────────────────┘   ║
║                                  │ KVM                                           ║
╠══════════════════════════════════╪════════════════════════════════════════════════╣
║  ┌───────────────────────────────▼───────────────────────────────────────────┐   ║
║  │              Debian 12 ARM64 Linux Guest VM                                │   ║
║  │                                                                            │   ║
║  │  Linux kernel (custom, ARM64, KVM guest)                                  │   ║
║  │  systemd (PID 1) │ glibc │ APT │ SSH                                      │   ║
║  │  android-bridge-agent │ pty-agent │ portal-agent │ update-agent           │   ║
║  │  Wayland compositor (Weston/Sommelier) │ XWayland                         │   ║
║  │  Linux CLI/TUI/GUI Apps                                                   │   ║
║  │                                                                            │   ║
║  │  Storage:                                                                  │   ║
║  │    EROFS base_a.img (read-only, signed)                                   │   ║
║  │    ext4 overlay_rw.img (writable)                                         │   ║
║  │    LUKS2+CE user_home.img (encrypted, per-user)                           │   ║
║  └───────────────────────────────────────────────────────────────────────────┘   ║
+═══════════════════════════════════════════════════════════════════════════════════+
```

## 2.2 架構決策一覽表

| ADR | 決策 | 選擇 | 放棄的替代 | 理由 |
|-----|------|------|------------|------|
| ADR-001 | 虛擬化框架 | AVF + crosvm + KVM | libvirt, QEMU 獨立 | AVF 是 AOSP 官方，已有 AIDL/Java API/SELinux policy |
| ADR-002 | VM 類型 | non-protected VM | protected VM | pVM 限制 rootfs 自訂，pvmfw 不支援 Debian |
| ADR-003 | Linux 發行版 | Debian 12 ARM64 | Ubuntu, Alpine, Arch | APT 成熟、glibc、LTS 安全更新、商業條件明確 |
| ADR-004 | Host-Guest 通訊 | AF_VSOCK + 自訂 framing | TCP/IP, IP-based gRPC | vsock 不可偽造，無需 IP-based auth |
| ADR-005 | Terminal UI | Android 原生 Surface Canvas | WebView/ttyd, xterm.js | 原生可控 IME、觸控、延遲；WebView 無法完整控制輸入 |
| ADR-006 | Terminal Parser | libvterm (C library) | 自行實作 VT parser | libvterm 完整 VT220/xterm-256color 支援，已有測試集 |
| ADR-007 | Guest 根檔案系統格式 | EROFS base + ext4 overlay | squashfs, qcow2 | EROFS 支援 dm-verity，ext4 overlay 成熟；qcow2 需要 crosvm 支援 |
| ADR-008 | Guest 加密 | LUKS2 + Android CE key binding | AOSP FBE, AVF secret | LUKS2 在 Guest 中完全控制，key 來自 Android Keystore |
| ADR-009 | LinuxManagerService 架構 | system_server API + 隔離 daemon | 全部進 system_server | 降低 system_server crash blast radius |
| ADR-010 | GUI 轉發 | virtio-gpu + Wayland compositor + per-window SurfaceControl | VNC, 全螢幕 Surface | 每個 Linux App 為獨立 Android Task，支援 Recents |
| ADR-011 | Linux App 整合 | LinuxAppRegistryService + AppProxy Activity | 動態 synthetic APK | 避免破壞 PackageManager invariant |
| ADR-012 | 檔案共享 | virtiofs + DocumentsProvider | 直接 mount /data | virtiofs 有訪問控制，DocumentsProvider 符合 SAF |
| ADR-013 | 網路 | Guest NAT via crosvm TAP + Android netd | 直接 passthrough | NAT 確保 Guest 流量遵守 Android VPN policy |
| ADR-014 | Guest 更新 | EROFS A/B + apt（overlay 更新） | OSTree, apt-only | A/B 支援原子性回滾，apt 處理套件更新 |
| ADR-015 | SELinux | 新增最小權限 domain | permissive / broad allow | 正式版本必須 enforcing |

## 2.3 系統邊界與責任劃分

```
Android Host 責任：
├── 所有 Android App 的生命週期
├── 所有硬體 HAL 存取
├── Runtime Permission 判斷
├── KeyMint/Keystore 金鑰管理
├── VPN 與企業政策執行
├── 電源管理（Doze, App Standby）
├── 使用者身份與多使用者管理
└── VM 整個生命週期控制

Linux Guest 責任：
├── Guest 內所有 Linux 程序的生命週期
├── systemd 服務管理
├── APT 套件管理（受控環境）
├── Guest root（UID 0，僅在 Guest 內有效）
├── Guest 網路（透過 Host NAT）
└── Linux GUI 應用程式的 Wayland 渲染

共同責任（Bridge 協調）：
├── PTY session 管理
├── Wayland surface 轉發
├── 檔案共享（virtiofs）
├── 音訊路由（virtio-snd）
└── Hardware Portal 授權
```

## 2.4 關鍵設計原則

1. **Host 掌控一切邊界**：Linux root 只是 Guest 內的 root，無法越過 VM 邊界
2. **vsock 優先**：所有 Host-Guest 通訊使用 AF_VSOCK，不用 IP
3. **fail-closed**：任何驗證失敗、簽章錯誤、橋接認證失敗，必須拒絕而非降級
4. **per-user 隔離**：每個 Android 使用者有獨立的 Linux home 加密分區
5. **透明感**：使用者不需要知道底層是 VM，Terminal 和 Linux App 的體驗與 Android App 一致
6. **可回滾**：每個重要操作（Guest 更新、系統修改）必須有明確回滾路徑
7. **不破壞 Android**：所有修改不能影響 Android CTS/VTS 基準通過率
