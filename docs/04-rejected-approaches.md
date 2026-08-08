# 第四章：不可行或不建議方案分析

## 4.1 完整方案比較矩陣

| 方案 | 真正 Linux | 獨立 kernel | Android 保留 | Guest root 隔離 | 安全邊界 | GPU | Wayland | 音訊 | 觸控輸入 | 相機 Portal | 檔案共享 | Suspend | 記憶體成本 | 背景服務 | OTA | 可維護性 | CTS 影響 | 裝置支援 | 工程成本 | 長期風險 |
|------|-----------|------------|-------------|----------------|---------|-----|---------|------|---------|------------|---------|---------|-----------|---------|-----|---------|---------|---------|---------|---------|
| **AVF+crosvm+KVM** ✅ | ✅ | ✅ | ✅ | ✅ | 強 | EXPERIMENTAL→可開發 | ✅ | virtio-snd | virtio-input | Portal 架構 | virtiofs | ✅ | VM overhead ~300MB | ✅ | A/B Guest | 高 | AArch64 KVM | 高 | 低（官方支持）|
| Non-AVF crosvm/KVM | ✅ | ✅ | ✅ | ✅ | 強 | 同上 | ✅ | virtio-snd | virtio-input | 自製 | virtiofs | ✅ | 同上 | ✅ | 自製 | 低 | AArch64 | 極高 | 高（維護成本）|
| Linux container/namespace | 部份 | ❌ | ✅ | ❌（逃脫風險）| 弱 | 共享 | 需 XWayland | 共享 | 共享 | 可 | 直接掛載 | 無 VM | 低 | ✅ | 困難 | 低 | 需 root | 中 | 高（安全）|
| chroot | ❌ | ❌ | ✅ | ❌（同 kernel）| 極弱 | 共享 | 需 XWayland | 共享 | 共享 | 無 | 直接掛載 | 無 | 極低 | ✅ | 無 | 低 | 廣泛 | 低 | 極高（安全）|
| PRoot | ❌ | ❌ | ✅ | ❌ | 無 | 無 | ❌ | 無 | 無 | 無 | ptrace syscall | 無 | 低 | 有限 | 無 | 低 | 廣泛 | 低 | 不可接受 |
| Termux | ❌ | ❌ | ✅ | ❌ | 無 | 無 | 有限 | 無 | 無 | 無 | SAF | 無 | 極低 | 有限 | 無 | 低 | 廣泛 | 低 | 不可接受 |
| 雙開機 | ✅ | ✅ | 分開 | ✅ | 強 | 原生 | ✅ | ✅ | ✅ | 原生 | 分開 | 困難 | 無 | ✅ | 2套 | 低 | 廣泛 | 低 | 不符需求 |
| 混合 rootfs | ❌（假 Linux）| ❌ | 破壞 | ❌ | 無 | 共享 | 困難 | 破壞 | 破壞 | 無 | 直接 | 無 | 無 | 困難 | 無 | 極低 | N/A | 極高 | 不可接受 |
| Droidian/Halium | ✅（反向）| 使用 Android vendor | ❌ | ✅ | 中 | 原生 | ✅ | ✅ | ✅ | 部份 | 部份 | 有限 | 低 | ✅ | 困難 | 低 | 特定裝置 | 高 | 高 |
| Waydroid 反向 | ❌（Android in Linux）| ❌ | ❌（非主系統）| ✅ | 強 | lxc | XDG | 模擬 | 模擬 | bridge | 共享 | 困難 | 高 | 有限 | N/A | 低 | 需 Linux Host | 高 | 高 |
| ChromeOS Crostini | ✅ | ✅ | 只有 ChromeOS | ✅ | 強 | virtio-gpu | ✅ | virtio-snd | Sommelier | XDG Portal | virtiofs | ✅ | 同 crosvm | ✅ | 成熟 | 高 | ARM64 Chromebook | 中（移植難）| 中 |
| WSL2 | ✅ | ✅ | 只有 Windows | ✅ | 強 | virtio-gpu | ✅ | virtio-snd | 轉發 | 部份 | virtio-fs | ✅ | 同 Hyper-V | ✅ | 成熟 | 高 | x86/ARM64 Windows | N/A（不適用）| N/A |
| postmarketOS | ✅ | ✅ | ❌（非 Android）| ✅ | 強 | 原生 | ✅ | ✅ | ✅ | 原生 | 原生 | ✅ | 低 | ✅ | APK | 低 | 特定裝置 | 高 | 高 |
| Microdroid | 有限（APK payload）| ✅ | ✅ | ✅ | 極強（pVM）| 無 | 無 | 無 | 無 | 無 | authfs | ✅ | 中 | 有限 | 有 | 高 | AArch64 | 低 | 低（但功能不足）|
| protected VM | ✅（技術可行）| ✅ | ✅ | ✅（pvmfw）| 最強 | 受限 | 受限 | 受限 | 受限 | 困難 | authfs | ✅ | 中 | 困難 | pvmfw 簽章 | 低（限制太多）| AArch64 | 高（限制多）| 中 |

---

## 4.2 個別方案排除理由

### 4.2.1 Linux container / namespace

**排除理由**：
- Guest 使用 Host kernel，沒有真正的隔離邊界
- `ptrace`, `seccomp bypass`, `namespace escape` 等 kernel 漏洞可直接影響 Host
- 無法保證 Guest root 不影響 Host system
- 不符合「獨立 Linux kernel」要求

### 4.2.2 chroot

**排除理由**：
- 共用 Host kernel，完全沒有隔離
- Guest root = Host UID 0（若 chroot 逃脫）
- `chroot(2)` 不是安全邊界，僅改變文件系統根目錄
- 直接違反本專案「Linux root 不可取得 Android root」要求

### 4.2.3 PRoot / Termux

**排除理由**：
- PRoot 使用 ptrace 模擬 root，效能差、相容性差
- Termux 無獨立 kernel，無 glibc，無 systemd，無 APT（有 pacman 但非標準）
- 兩者均無法運行需要 root 的 Linux 服務（如 SSH daemon）
- 違反「真正 GNU/Linux 發行版」要求

### 4.2.4 雙開機（Dual Boot）

**排除理由**：
- 使用者必須選擇開機到哪個系統，無法「同時」使用
- 違反「一個統一的使用者體驗」核心要求
- Android 和 Linux 無法同時運行，無法相互呼叫
- 不符合本專案核心命題

### 4.2.5 混合單一 rootfs（glibc + systemd in /system）

**排除理由**：
- Android init（PID 1）與 systemd（要求 PID 1）直接衝突
- glibc 與 Android bionic 二進制格式不相容
- APT 會破壞 AOSP system partition 完整性
- 違反 Verified Boot
- 無法通過 CTS/VTS
- 這是技術上根本不可行的方案

### 4.2.6 Droidian / Halium 架構

**排除理由**：
- 設計目標是「Android 作 HAL，Linux 作主系統」，與本專案相反
- 喪失完整 Android 功能（APK、Play Store、Android 生態系）
- 主要用於 Linux 桌面，不是 Android-first 體驗
- 裝置支援受限，需要特殊適配工作

### 4.2.7 Waydroid 反向架構（Android in Linux）

**排除理由**：
- Waydroid 是在 Linux Host 中運行 Android Container
- 本專案需要的是在 Android Host 中運行 Linux，方向相反
- Waydroid 需要 Linux 作為 Host，不適用於 Android 裝置

### 4.2.8 ChromeOS Crostini 直接移植

**排除理由**：
- Crostini 依賴 Chrome OS 特定基礎設施（Concierge、Seneschal、Garcon）
- 使用 Chrome 的 Wayland compositor（Exo）
- 整個 UI 整合基於 Chrome OS 視窗管理器（Ash），無法直接移植到 Android

**啟發意義**（可借鑒）：
- Sommelier 類型的 Wayland-to-Host-surface 轉發架構
- virtiofs 共享目錄設計
- VM suspend/resume 策略
- XDG portal bridge 設計思路

### 4.2.9 protected VM（pvmfw）作為主要方案

**排除理由**：
- pvmfw 強制驗證 Guest image 簽章，無法安裝自訂 Debian rootfs
- 僅支援 AArch64
- pVM 設計目標是安全計算（Confidential Computing），不是通用 Linux 環境
- `IVmPayloadService` 的設計是 APK payload，不是一般 Linux
- Guest 與 Host 的通訊限制更嚴格

**可借鑒**：pvmfw 的 DICE attestation 和 Secretkeeper 整合模式

### 4.2.10 permissive SELinux

**排除理由**：
- 直接違反 Android CTS 要求（`android.security.cts.SELinuxTest`）
- 消除所有 SELinux 安全邊界
- 正式版本絕對禁止

---

## 4.3 非建議設計模式

除了方案本身，以下設計模式也明確禁止：

| 禁止的設計 | 理由 |
|-----------|------|
| 使用 Guest IP 作為身份驗證唯一依據 | IP 可在同網路內偽造；已被 VmLauncherService.kt 的 TODO 承認不足 |
| 只用 VNC 作為最終 GUI | VNC 全畫面串流延遲高，不支援每個 App 獨立 Task |
| 讓 Linux root 直接掛載 Android /data | 可讀取所有 Android App 私有資料 |
| 讓 Linux 直接取得 /dev 存取 | 繞過 Android HAL 和 runtime permission |
| glibc + systemd 安裝進 /system | 技術不可行，見 4.2.5 |
| 把所有功能塞進一個 platform-signed APK | 增加攻擊面，blast radius 大 |
| broad `allow system_server *:* *` | SELinux 意義全消 |
| 讓 APT 修改 AOSP system partition | 破壞 Verified Boot |
| 明文長期儲存 secret | 違反基本安全要求 |
| Guest reboot 後不重新驗證身份 | Replay attack 風險 |
