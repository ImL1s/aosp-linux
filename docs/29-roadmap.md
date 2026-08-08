# 第二十九章：完整 Roadmap

## 29.1 總覽

| 階段 | 名稱 | 時間（粗估）| 目標 |
|------|------|-----------|------|
| Phase 0 | 研究與架構凍結 | 週 1-4 | 所有技術決策完成 |
| Phase 1 | Cuttlefish Bring-up | 週 5-10 | Cuttlefish 上 Debian VM 可開機 |
| Phase 2 | Framework API | 週 8-16 | LinuxManagerService AIDL + 狀態機 |
| Phase 3 | Bridge + Terminal | 週 14-24 | vsock auth + 原生 Terminal |
| Phase 4 | File + Network | 週 20-28 | virtiofs SAF + VPN 遵守 |
| Phase 5 | GUI + Launcher | 週 24-36 | Wayland per-window + Recents |
| Phase 6 | Audio + Portal | 週 28-38 | virtio-snd + XDG portal |
| Phase 7 | Security Hardening | 週 32-42 | SELinux enforcing + threat model |
| Phase 8 | OTA + Multi-user | 週 36-46 | A/B guest update + per-user CE |
| Phase 9 | CTS/VTS + Reference HW | 週 40-52 | CTS pass + 真機 bring-up |
| Phase 10 | Beta + RC | 週 48-60 | 外部 beta + release candidate |
| Phase 11 | 1.0 Release | 週 56-72 | 正式 1.0 + 長期維護 |

所有時間估算均為粗略假設，基於 10 人工程團隊，實際視資源調整。

---

## 29.2 Phase 0：研究與架構凍結

**目標**：確保所有技術決策基於真實 AOSP 原始碼，架構方案凍結。

**工作項目**：
- [ ] 驗證 AVF non-protected VM 在 Cuttlefish 上可建立自訂 rootfs
- [ ] 確認 AF_VSOCK 在 Android 15/16 上的 API 狀態
- [ ] 評估 virtio-gpu gfxstream vs virglrenderer 可用度
- [ ] 確認 LUKS2 在 ARM64 kernel 的支援狀態
- [ ] 審核 b/372666638 狀態（gRPC over vsock）
- [ ] 凍結 Bridge RPC protocol v1.0 設計
- [ ] 凍結 AIDL 介面設計

**完成標準**：Architecture Decision Records (ADR) 全部完成，無未解決的 UNKNOWN 項目。

**Fail-closed gate**：若關鍵技術問題（如 vsock 不可用）無解，整個專案停止。

---

## 29.3 Phase 1：Cuttlefish Bring-up

**目標**：在 Cuttlefish 模擬器上成功啟動 Debian 12 ARM64 Guest VM。

**工作項目**：
- [ ] 建立 Debian 12 ARM64 rootfs（debootstrap）
- [ ] 建立 EROFS base image（mkfs.erofs）
- [ ] 配置 crosvm 使用自訂 non-protected VM
- [ ] 確認 Guest kernel 以 KVM guest 模式啟動
- [ ] 確認 systemd PID 1 正常啟動
- [ ] 確認 AF_VSOCK 連接（基本 echo test）
- [ ] 確認 virtiofs 掛載正常
- [ ] 確認 apt 可安裝套件

**依賴**：Phase 0 架構凍結

**輸出物**：
- 可重現的 Cuttlefish + Debian VM 環境
- Guest image builder（Dockerfile + scripts）
- 基本 vsock test tool

**測試**：Boot test + vsock echo test + apt install test

**完成標準**：在 Cuttlefish 上 `apt install git` 成功。

**不應在此階段做**：Security hardening、CTS、GUI 功能。

---

## 29.4 Phase 2：Framework API

**目標**：LinuxManagerService AIDL 完整實作，VM lifecycle 狀態機可用。

**工作項目**：
- [ ] 新增 `android.system.linux` package（AIDL + Parcelable）
- [ ] LinuxManagerService 在 SystemServer 注冊
- [ ] VM lifecycle 狀態機（所有狀態轉換）
- [ ] 基本 linux_bridge daemon（skeleton，無 auth）
- [ ] `dumpsys linux` 基本實作
- [ ] 權限宣告（AndroidManifest.xml）
- [ ] `adb shell cmd linux start/stop/status`

**依賴**：Phase 1（VM 可啟動）

**輸出物**：
- `ILinuxManager.aidl` + stub 實作
- `LinuxManagerService.java`
- `linux_bridge` C++ daemon（skeleton）

**修改模組**：`frameworks/base/`, `SystemServer.java`, `SystemServiceRegistry.java`

**測試**：Framework AIDL 單元測試、狀態機 instrumentation test

**完成標準**：`adb shell cmd linux start` 啟動 VM，狀態轉換正確。

---

## 29.5 Phase 3：Bridge Authentication + 原生 Terminal

**目標**：
1. vsock HMAC-SHA256 認證完整實作
2. 原生 Surface Canvas Terminal UI 可用（替換 WebView/ttyd）

**工作項目（Bridge）**：
- [ ] HMAC-SHA256 challenge-response handshake
- [ ] Session token 管理
- [ ] Binary framing protocol v1.0
- [ ] RPC routing（Control + PTY ports）
- [ ] Replay protection（nonce + timestamp）
- [ ] Guest android-bridge-agent（Rust）
- [ ] Guest pty-agent
- [ ] linux_bridge seccomp policy

**工作項目（Terminal）**：
- [ ] `LinuxTerminal` App skeleton
- [ ] `TerminalView`（Surface Canvas，非 WebView）
- [ ] libvterm JNI 整合
- [ ] `TerminalInputConnection`（基本 ASCII）
- [ ] vsock PTY client（Port 5001）
- [ ] Modifier key bar（Ctrl, Alt, Esc, 方向鍵）
- [ ] Shell Mode 觸控手勢

**依賴**：Phase 1 + Phase 2

**完成標準**：
- 點擊 Terminal App → VM 啟動 → bash prompt 顯示
- 可輸入文字，Ctrl+C 正常，SSH 可用

---

## 29.6 Phase 4：IME + 進階 Terminal 功能

**目標**：完整 CJK IME、TUI Mouse Mode、三種觸控模式。

**工作項目**：
- [ ] `TerminalInputConnection` CJK 組字（注音/倉頡/拼音）
- [ ] 組字懸浮視窗預覽
- [ ] TUI Mouse Mode（SGR 1006 格式）
- [ ] Touchpad Mode
- [ ] 多 session、分頁 UI
- [ ] Session 持久化（Activity recreation 後不斷線）
- [ ] VM suspend/resume 後自動重連
- [ ] 安全輸入 mode（password prompt）
- [ ] Bracketed paste + 大量貼上警告

**完成標準**：
- 注音輸入「ㄓㄨㄥ」→「中」正確
- Vim mouse mode：點擊移動游標正常
- 旋轉裝置：session 不中斷

---

## 29.7 Phase 5：File Share + Network

**目標**：virtiofs 共享、DocumentsProvider、VPN 遵守。

**工作項目**：
- [ ] virtiofs 設定（/data/media/0/LinuxShared）
- [ ] `LinuxStorageProvider`（DocumentsProvider）
- [ ] Android → Linux 檔案共享 UI
- [ ] Linux → Android 分享（SAF Intent）
- [ ] Guest NAT 網路設定（crosvm TAP）
- [ ] NetworkPolicyManager 整合（metered, VPN）
- [ ] Port Publishing UI + iptables NAT
- [ ] DNS 透過 Host resolver

**完成標準**：
- Android Files App 可瀏覽 Linux home
- Linux 有網路可用
- Android VPN 啟用時，Linux 流量也走 VPN

---

## 29.8 Phase 6：GUI + Launcher

**目標**：Wayland per-window forwarding，Linux App 出現在 Launcher。

**工作項目**：
- [ ] Guest wayland-agent（Sommelier-like）
- [ ] `LinuxWindowBridgeService`
- [ ] `LinuxAppProxyActivity`
- [ ] virtio-gpu virglrenderer 啟用（非 EXPERIMENTAL flag）
- [ ] per-window SurfaceControl 映射
- [ ] Recents 整合（Task thumbnail）
- [ ] Guest .desktop 檔案解析 + icon 同步
- [ ] `LinuxAppRegistryService`
- [ ] Launcher3 Linux shortcut 整合
- [ ] IME 路由到 Linux App

**完成標準**：
- VS Code 可在 Android Launcher 開啟
- VS Code 出現在 Recents
- 可分割畫面（VS Code + Terminal）

---

## 29.9 Phase 7：Audio + Portals

**目標**：virtio-snd 音訊，完整 XDG Portal。

**工作項目**：
- [ ] virtio-snd Host/Guest 設定
- [ ] AudioFocus 管理
- [ ] `LinuxPortalService`（camera, mic, location）
- [ ] xdg-portal-agent（Guest）
- [ ] AppOps 整合（OP_LINUX_CAMERA 等）
- [ ] 使用者 Portal 授權對話框
- [ ] 隱私指示器（相機/麥克風活動）
- [ ] Notification portal

**完成標準**：
- Linux App 可播放音訊（Doze 保護）
- Linux App 可請求相機，Android 顯示授權對話框

---

## 29.10 Phase 8：Security Hardening

**目標**：SELinux enforcing，威脅模型驗證，安全審計。

**工作項目**：
- [ ] 完整 SELinux domains（linux_manager, linux_bridge, linux_portal, linux_terminal, linux_window_bridge, linux_file_bridge, linux_update_service）
- [ ] 所有 NEVERALLOW 規則
- [ ] crosvm seccomp 審計
- [ ] linux_bridge seccomp
- [ ] Guest image AVB 2.0 RSA-4096 簽章
- [ ] LUKS2 CE key binding 完整實作
- [ ] 安全審計（內部 code review）
- [ ] Fuzzing（bridge parser, terminal ANSI）
- [ ] 滲透測試（guest escape attempt）

**完成標準**：
- `atest CtsSelinuxHostTestCases` 通過
- Guest escape 測試無成功
- Bridge spoofing 測試無成功

---

## 29.11 Phase 9：OTA + Multi-user

**工作項目**：
- [ ] Guest A/B image update 完整流程
- [ ] update-agent（Guest）
- [ ] `LinuxImageManager`（Host）
- [ ] AVB rollback index 管理
- [ ] Factory reset 行為
- [ ] Backup/restore
- [ ] per-user Linux CE 隔離
- [ ] DevicePolicyManager restrictions
- [ ] Work profile 政策

**完成標準**：
- Guest OTA：下載 → 驗證 → 安裝 → 重啟 → 成功
- OTA 失敗：自動 rollback 到前一版本
- User A 的 Linux home 對 User B 不可見

---

## 29.12 Phase 10：CTS/VTS + Reference Hardware

**工作項目**：
- [ ] 運行完整 CTS（確認無回歸）
- [ ] 運行 VTS
- [ ] 運行 `CtsSELinuxHostTestCases`
- [ ] ARM64 reference device bring-up
- [ ] 真機效能測量（SLO 達成確認）
- [ ] 熱保護測試
- [ ] 低記憶體裝置測試（4GB RAM）
- [ ] 外接螢幕測試（Desktop Mode）

**完成標準**：
- CTS 無不可接受回歸
- 所有 1.0 SLO 達成（見第十章）

---

## 29.13 Phase 11：Beta → 1.0

**工作項目**：
- [ ] Closed Beta（100 位開發者測試）
- [ ] Bug fix sprint
- [ ] Open Beta
- [ ] Release Candidate
- [ ] 安全最終審計
- [ ] SBOM 發布
- [ ] Release notes
- [ ] 1.0 Tag + 正式 release

**Post-1.0 維護**：
- 每月安全 patch（Debian CVE + Android CVE）
- 每季功能更新
- 每年重大版本更新（跟隨 Android 主版本）
