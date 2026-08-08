# 第三十二章：1.0 驗收矩陣

## 32.1 驗收標準完整清單

每條驗收標準均標注：
- 測試方法
- 自動化 or 人工
- 責任團隊

| # | 驗收標準 | 測試方法 | 自動化 | 責任 |
|---|---------|---------|--------|------|
| AC-01 | AOSP 能正常開機 | Boot test | ✅ | Virtualization |
| AC-02 | Android App 正常執行（APK 安裝、ART 運行）| CTS subset | ✅ | QA |
| AC-03 | Android baseline CTS/VTS 無不可接受回歸 | Full CTS/VTS | ✅ | QA |
| AC-04 | Linux Guest 使用獨立 kernel（`uname -r` 在 Guest 執行）| adb shell cmd linux exec "uname -r" | ✅ | Virtualization |
| AC-05 | Guest 具有真正 UID 0 root（`id -u` = 0 in guest）| Bridge RPC exec | ✅ | Guest |
| AC-06 | systemd 正常（`systemctl status` 無 failed units）| Bridge RPC | ✅ | Guest |
| AC-07 | apt 正常（`apt install git` 成功）| Bridge RPC | ✅ | Guest |
| AC-08 | SSH 正常（Guest SSH server 可連線）| TCP port forward test | ✅ | Guest |
| AC-09 | Git、Python、Node、Clang、Rust 可用 | 各自 --version 指令 | ✅ | Guest |
| AC-10 | Linux 資料重開機後保留（寫檔 → 重啟 → 讀回）| Bridge RPC | ✅ | Guest |
| AC-11 | 點擊 Android Terminal → Linux shell prompt 顯示（≤15s）| UI test + timing | ✅ | Terminal |
| AC-12 | 可完全透過觸控與 Android IME 操作 Terminal | UI 人工測試 | 人工 | Terminal |
| AC-13 | 中文注音輸入正常（ㄓㄨㄥ→中）| IME UI test | ✅ | Terminal |
| AC-14 | Ctrl/Alt/Esc/Tab/方向鍵正常（對應 ANSI sequence）| Terminal input test | ✅ | Terminal |
| AC-15 | Vim、tmux、htop TUI mouse 可操作 | UI test（mouse mode）| ✅ | Terminal |
| AC-16 | Terminal session 在旋轉與 Activity recreation 後不消失 | 旋轉 → 確認 session | ✅ | Terminal |
| AC-17 | VM suspend/resume 後可重新接回 PTY（≤5s）| 自動化 suspend/resume | ✅ | Bridge |
| AC-18 | Linux root 無法讀取其他 Android App 私有資料 | 安全滲透測試 | 人工 | Security |
| AC-19 | Linux root 無法直接取得 Android Binder 全權 | SELinux neverallow test | ✅ | Security |
| AC-20 | Linux 相機/麥克風/定位必須經 Android 權限對話框 | Portal 授權測試 | ✅ | Portal |
| AC-21 | Linux GUI App 可由 Android Launcher 開啟 | Launcher UI test | ✅ | UX |
| AC-22 | 至少一個 Linux GUI App 可顯示在 Android Task（VS Code 或 GIMP）| Wayland test | 人工 | Graphics |
| AC-23 | 支援完整 Linux Desktop 模式（外接螢幕）| 人工測試 | 人工 | Graphics |
| AC-24 | Android 與 Linux 可受控共享檔案（virtiofs）| 檔案讀寫測試 | ✅ | Bridge |
| AC-25 | 剪貼簿可設定與撤銷（Portal 測試）| Portal UI test | ✅ | Portal |
| AC-26 | Linux Notification 可映射成 Android Notification | Notification test | ✅ | Portal |
| AC-27 | Linux Guest 網路遵守 Android VPN 政策 | VPN bypass test | ✅ | Network |
| AC-28 | Linux VM 可 suspend（≤10s）| 自動化測試 | ✅ | Virtualization |
| AC-29 | 背景 server 有清楚通知與資源政策 | UI 確認 | 人工 | UX |
| AC-30 | Host OTA 不刪除 Linux /home | OTA 測試 | ✅ | OTA |
| AC-31 | Guest 更新失敗可 rollback | OTA failure test | ✅ | OTA |
| AC-32 | image 簽章錯誤 fail-closed | 修改 image hash → 確認拒絕 | ✅ | Security |
| AC-33 | Guest bridge 驗證失敗 fail-closed（無效 HMAC）| 模擬 bad HMAC | ✅ | Security |
| AC-34 | SELinux 正式版不可 permissive | `getenforce` = Enforcing | ✅ | Security |
| AC-35 | Factory reset、backup、restore 行為明確可測試 | 完整 reset 測試 | ✅ | QA |
| AC-36 | 多 Android 使用者 Linux 資料完全隔離 | User switch + cross-read test | ✅ | Security |
| AC-37 | 具備可用 dumpsys、diagnostics 與 recovery 路徑 | `dumpsys linux` 輸出確認 | ✅ | Framework |

---

# 第三十三章：Architecture Decision Records

## ADR-001：AVF + crosvm + KVM + non-protected VM

- **狀態**：已決定
- **決定**：使用 AVF 的 non-protected VM 執行 Debian 12
- **理由**：AVF 是 AOSP 官方框架，已有完整 AIDL 基礎設施。Non-protected VM 允許自訂 rootfs，pvmfw 限制太多
- **結果**：Guest root 隔離由 KVM page table 保證，不依賴 pvmfw attestation chain
- **替代考量**：直接 crosvm（工程成本高、維護困難）；protected VM（rootfs 自訂受限）

## ADR-002：Terminal UI - 原生 Canvas，非 WebView

- **狀態**：已決定
- **決定**：新建原生 Surface Canvas Terminal App，不沿用 WebView/ttyd
- **理由**：WebView/ttyd（TerminalView.kt 已確認是 WebView subclass）無法完整控制 IME、觸控延遲高、CJK 輸入受限
- **結果**：需要投入 libvterm JNI 整合和 TerminalInputConnection 設計工作
- **遷移策略**：Phase 1 保留 WebView/ttyd（修復 auth），Phase 3 完整替換

## ADR-003：Bridge Protocol - 自製 binary framing，非 gRPC

- **狀態**：已決定
- **決定**：自製 binary framing protocol over raw AF_VSOCK
- **理由**：gRPC for Java 不支援 vsock（b/372666638，已在原始碼確認）；自製協議完全掌控，更小、更快
- **結果**：需維護 proto 定義 + 自製 framing
- **替代**：gRPC over vsock 可用 C++/Rust gRPC（tonic），但增加依賴複雜度

## ADR-004：SELinux - 新增最小權限 domain

- **狀態**：已決定
- **決定**：為每個新增服務建立獨立最小權限 SELinux domain
- **理由**：permissive 不可接受；broad allow 消除安全意義；最小權限符合 Android security principles
- **結果**：需要較多初期 SELinux policy 工作（avc denial 分析 + policy 撰寫）

## ADR-005：Guest Storage - EROFS + ext4 overlay + LUKS2

- **狀態**：已決定
- **決定**：EROFS（read-only base）+ ext4 overlayfs（writable）+ LUKS2（home 加密）
- **理由**：EROFS 支援 dm-verity、壓縮好；ext4 overlayfs 成熟；LUKS2 提供 CE key binding
- **結果**：比 qcow2 更複雜的多層結構，但安全性和可維護性更好

## ADR-006：Linux App 整合 - Shortcut + AppProxy，非 synthetic APK

- **狀態**：已決定
- **決定**：使用 ShortcutManager + LinuxAppProxyActivity，不製造 synthetic APK
- **理由**：Synthetic APK 破壞 PackageManager invariant，可能影響 CTS
- **結果**：每個 Linux App 為 dynamic shortcut + Activity instance，不是真實 APK

## ADR-007：Guest 發行版 - Debian 12 ARM64

- **狀態**：已決定
- **決定**：Debian 12 Bookworm ARM64
- **理由**：glibc 相容性最佳、LTS 安全更新到 2028、DFSG 允許商業發佈、APT 成熟
- **替代**：Ubuntu（更大 image，Canonical 條款）；Alpine（musl，相容性差）

---

# 第三十四章：尚待實機驗證事項

以下項目目前在 AOSP 原始碼層面確認 EXISTING 或 EXPERIMENTAL，
但必須在實際 ARM64 裝置（或 Cuttlefish）上驗證，不能假設已可用：

## 34.1 必須在 Cuttlefish 驗證（Phase 1）

| 項目 | 預期狀態 | 驗證方法 |
|------|---------|---------|
| VirtualMachineCustomImageConfig 支援 non-protected custom rootfs | EXISTING | 嘗試建立自訂 rootfs VM |
| AF_VSOCK 在 Android 15+ 可從 App 使用 | EXISTING | 建立 vsock connection |
| virtio-fs（virtiofs）在 Cuttlefish 可掛載 host directory | EXISTING | 掛載並讀寫文件 |
| crosvm 支援 EROFS disk image | EXISTING | 以 EROFS 啟動 VM |
| LUKS2 在 ARM64 kernel 可用（dm-crypt）| 需驗證 | cryptsetup luksFormat 成功 |
| overlayfs 在 Guest kernel 可用 | 需驗證 | mount -t overlay 成功 |
| virtio-snd 基本播放/錄音 | EXPERIMENTAL | 音訊播放測試 |

## 34.2 必須在真實 ARM64 裝置驗證（Phase 10）

| 項目 | 風險 | 備注 |
|------|------|------|
| virtio-gpu virglrenderer 在非 Cuttlefish 真機效能 | HIGH | 許多裝置 GPU 驅動不同 |
| KVM `/dev/kvm` 可用性 | HIGH | 商業裝置可能未開放 |
| Suspend/Resume 在不同 SoC 的穩定性 | MEDIUM | SoC-specific |
| crosvm TAP + Android netd NAT 在所有裝置 | MEDIUM | 需確認 iptables 支援 |
| EROFS dm-verity 在裝置 vendor kernel | MEDIUM | vendor kernel 設定差異 |
| virtio-input 觸碰事件在 non-Cuttlefish | MEDIUM | 需實機測試 |
| AudioFocus 在有電話功能的裝置 | LOW | 電話來電打斷測試 |

## 34.3 gRPC over vsock 狀態追蹤

bug：b/372666638（gRPC for Java doesn't support vsock）

當前決策：使用自製 binary framing（ADR-003）

需定期追蹤此 bug 狀態：
- 若 AOSP 修復此 bug，評估遷移到 gRPC over vsock
- 自製協議仍需維護，但遷移後可利用 gRPC 生態系（health check, interceptor 等）

## 34.4 virtio-gpu EXPERIMENTAL 狀態

當前代碼（VmLauncherService.kt 已確認）：

```kotlin
// 需要在 sdcard 放置觸發文件才啟用 virglrenderer
if (Files.exists(ImageArchive.getSdcardPathForTesting().resolve("virglrenderer"))) {
    // ...
}
```

這表示 GPU 支援在 AOSP main branch 仍為 testing-only flag。

待驗證：何時 `Flags.terminalGuiSupport()` 會從 testing flag 升為正式 API。

---

# 第三十五章：官方來源與實際原始碼引用

## 35.1 AOSP 原始碼直接引用

以下均已於 2026-08-06 直接從 android.googlesource.com 取得：

### packages/modules/Virtualization（tree hash: d1dfcf5819575b6ce7388c062988c9c4f00305a8）

| 檔案 | 關鍵資訊 |
|------|---------|
| `android/TerminalApp/java/.../TerminalView.kt` (blob: 6917827a) | `class TerminalView extends WebView` — 確認非原生渲染 |
| `android/TerminalApp/java/.../VmLauncherService.kt` (blob: f1ad561c) | `b/372666638` TODO：gRPC 不支援 vsock；IP-based auth；Flags.terminalGuiSupport() |
| `android/virtualizationservice/aidl/...` | IVirtualizationService 15 個方法，6 個 feature flags |
| `android/virtmgr/src/` | Rust，VmState enum，16 種 death reason，boot timeout 30s/300s |
| `guest/pvmfw/` | AArch64 only，load@0x7fc0_0000，config v1.3 |
| `guest/microdroid_manager/aidl/.../IVmPayloadService.aidl` | 8 個方法，notifyPayloadReady, getVmInstanceSecret |

### system/sepolicy/private（確認存在的 virtualization 相關 domain）

- `crosvm.te` ← EXISTING
- `early_virtmgr.te` ← EXISTING
- `linux_vm_setup.te` ← EXISTING（注意：此 domain 已存在）

## 35.2 官方文件來源

| 文件 | URL | 用途 |
|------|-----|------|
| Android Virtualization Framework | https://source.android.com/docs/core/virtualization | AVF 官方架構說明 |
| crosvm documentation | https://crosvm.dev/ | crosvm 設定與 API |
| virtio spec | https://docs.oasis-open.org/virtio/virtio/v1.2/ | virtio 設備規格 |
| AF_VSOCK man page | https://man7.org/linux/man-pages/man7/vsock.7.html | vsock 協議規格 |
| Debian 12 Release Notes | https://www.debian.org/releases/bookworm/ | Debian ARM64 套件支援 |
| XDG Desktop Portal | https://flatpak.github.io/xdg-desktop-portal/ | Portal D-Bus 規格 |
| Android CDD | https://source.android.com/docs/compatibility/cdd | 相容性定義 |
| SELinux Android Policy | https://source.android.com/docs/security/features/selinux | Android SELinux 設計 |
| LUKS2 spec | https://gitlab.com/cryptsetup/LUKS2-docs | LUKS2 加密格式 |
| AVB (Android Verified Boot) | https://android.googlesource.com/platform/external/avb/ | AVB 2.0 簽章 |
| KVM documentation | https://www.kernel.org/doc/html/latest/virt/kvm/ | KVM hypervisor |
| EROFS documentation | https://erofs.docs.kernel.org/ | EROFS 檔案系統 |

## 35.3 重要 AOSP Bug 追蹤

| Bug | 說明 | 影響 |
|-----|------|------|
| b/372666638 | gRPC Java 不支援 vsock | Bridge 協議選擇（ADR-003）|
| b/373533555 | debian_service_port 發現應改用 mDNS | 短期 workaround，長期用 vsock |
| b/376827536 | Terminal a11y：double_tap_to_edit_text hint | Terminal 可及性 |

## 35.4 本文件說明

- **研究日期**：2026-08-06
- **AOSP branch**：refs/heads/main
- **驗證方法**：直接 fetch android.googlesource.com Gitiles，閱讀原始碼
- **所有 EXISTING 標記**：均已從原始碼取得，非推測
- **所有 NEW 標記**：本專案需新增，目前 AOSP 中不存在
- **所有 EXPERIMENTAL 標記**：已確認使用 flag gate 或 testing-only 標記

本文件所有「建議新增的路徑」（如 `frameworks/base/core/java/android/system/linux/`）均明確標注為 **[NEW]**，不存在於當前 AOSP 原始碼。
