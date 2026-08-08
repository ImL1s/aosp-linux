# 第二十三章：多使用者與企業政策

## 23.1 多使用者 Linux 資料隔離

```
per-Android-user 隔離：

User 0 (主使用者):
  /data/user/0/com.android.linux/files/
  ├── base_a.img        (共用或各自，見下文)
  ├── overlay_rw.img    (各自獨立)
  └── user_home.img     (各自獨立，CE key 不同)

User 10 (次要使用者):
  /data/user/10/com.android.linux/files/
  ├── overlay_rw.img    (獨立)
  └── user_home.img     (獨立，完全隔離)

User 11 (Guest user):
  Linux 功能預設禁用（可設定）

CE Key 隔離：
- User 0 的 CE master key ≠ User 10 的 CE master key
- HKDF 衍生：IKM = ce_key + user_id → 完全不同的 LUKS key
- 使用者 A 解鎖不能解密使用者 B 的 Linux home
```

## 23.2 共用 base image vs 各自

```
選項 A：共用 base.img
優點：節省空間（base 通常 ~500MB）
缺點：一個使用者的 base update 影響所有使用者

選項 B：各自 base.img
優點：完全隔離，各自可有不同版本
缺點：空間消耗 x N

決策：共用 base_a.img（read-only，使用 hardlink 或 shared mount）
各自有 overlay_rw.img 和 user_home.img

共用 base 的安全性：EROFS 是 read-only，無法修改，共用沒有安全風險
```

## 23.3 Work Profile

```
Work Profile 下的 Linux 政策：
- 企業 IT 可以透過 DevicePolicyManager 控制
- 可以禁用 Linux（DPM restriction: DISALLOW_LINUX）
- 可以限制 Portal 存取（禁止 camera, mic from Linux）
- 可以強制 VPN 旁路檢查

DevicePolicyManager 新增限制（NEW）：
UserManager.DISALLOW_USE_LINUX_TERMINAL
UserManager.DISALLOW_LINUX_PORTAL_CAMERA
UserManager.DISALLOW_LINUX_PORTAL_MICROPHONE
UserManager.DISALLOW_LINUX_BACKGROUND_SERVICES
```

## 23.4 Device Owner 政策

```
Device Owner（Enterprise MDM）可以：
- 完全禁用 Linux
- 限制 Linux 磁碟大小
- 強制 Linux 網路走 VPN
- 禁止 Port Publishing
- 禁止 USB 相關 Portal
- 讀取 Linux health status（監控）

設定 API：
DevicePolicyManager.setLinuxPolicy(ComponentName admin, LinuxPolicy policy)
```

---

# 第二十四章：建置系統

## 24.1 Repo 結構

```
monorepo 策略（vendor overlay）：

AOSP base (android.googlesource.com)
└── vendor/aosp-linux/                   [本專案 repo]
    ├── Android.bp                       [頂層 build graph]
    ├── frameworks/
    │   └── base/                        [framework 修改 patch]
    ├── packages/
    │   ├── apps/LinuxTerminal/
    │   └── apps/LinuxSettings/
    ├── system/
    │   ├── linux_bridge/
    │   └── sepolicy/
    ├── guest/
    │   ├── bridge-agent/                [Rust]
    │   ├── pty-agent/
    │   ├── wayland-agent/
    │   ├── xdg-portal-agent/
    │   └── update-agent/
    ├── guest-image/
    │   ├── builder/                     [Debian image builder]
    │   ├── Dockerfile
    │   └── scripts/
    ├── proto/
    │   └── bridge_rpc.proto             [Bridge RPC 協議定義]
    ├── tests/
    │   ├── host/                        [Android tests]
    │   ├── guest/                       [Guest tests]
    │   └── cts/                         [CTS 相關]
    ├── tools/
    │   └── release/                     [Release 腳本]
    └── docs/                            [本文件]
```

## 24.2 lunch target

```bash
# 新增 lunch target
lunch aosp_arm64-linux-userdebug   # 開發用
lunch aosp_arm64-linux-user        # Release 用

# 對應 device/aosp_linux/
device/aosp_linux/
├── AndroidProducts.mk
├── aosp_arm64_linux.mk
└── BoardConfig.mk
```

## 24.3 Guest Image Builder

```dockerfile
# guest-image/Dockerfile
FROM debian:12-slim AS builder

RUN apt-get update && apt-get install -y \
    debootstrap qemu-user-static binfmt-support \
    squashfs-tools erofs-utils cryptsetup \
    android-avb-tools

# 建立 Debian 12 ARM64 rootfs
RUN debootstrap --arch=arm64 --foreign bookworm /rootfs \
    http://deb.debian.org/debian

# 安裝必要套件
COPY scripts/install_packages.sh /
RUN chroot /rootfs /install_packages.sh

# 安裝 Guest Agents
COPY agents/ /rootfs/usr/bin/

# 建立 EROFS base image
RUN mkfs.erofs -L base -z lz4hc base.img /rootfs/

# AVB 簽章
RUN avbtool add_hashtree_footer \
    --image base.img \
    --partition_name linux_base \
    --algorithm SHA256_RSA4096 \
    --key release.pem
```

## 24.4 Soong 模組定義

```python
# system/linux_bridge/Android.bp
cc_binary {
    name: "linux_bridge",
    srcs: [
        "main.cpp",
        "vsock_server.cpp",
        "rpc_router.cpp",
        "auth.cpp",
        "portal_forwarder.cpp",
    ],
    shared_libs: [
        "liblog",
        "libcutils",
        "libbase",
        "libbinder",
    ],
    static_libs: [
        "libcrypto",
        "libssl",
    ],
    cflags: [
        "-Wall",
        "-Wextra",
        "-fstack-protector-strong",
        "-D_FORTIFY_SOURCE=2",
    ],
    init_rc: ["linux_bridge.rc"],
    required: ["linux_bridge_sepolicy"],
}

# packages/apps/LinuxTerminal/Android.bp
android_app {
    name: "LinuxTerminal",
    srcs: ["src/**/*.java"],
    jni_libs: ["libvterm_jni"],
    privileged: true,
    certificate: "platform",
    required_certificate: "platform",
}

rust_binary_host {
    name: "android-bridge-agent",
    crate_name: "android_bridge_agent",
    srcs: ["guest/bridge-agent/src/main.rs"],
    rustlibs: [
        "libhmac",
        "libzeroize",
        "libtokio",
    ],
}
```

## 24.5 簽章與 SBOM

```
Release 簽章：
- Host AOSP：使用 AOSP release key（vendor 管理）
- Guest base.img：使用獨立 AVB key（RSA-4096）
- Guest agents（Debian 套件）：使用 Debian GPG key（自有 repo）

SBOM（Software Bill of Materials）：
- Host：Android SBOM（build 自動產生）
- Guest base：Debian SBOM（dpkg list output）
- Guest agents：Rust Cargo.lock SBOM
- 每個 release 必須附 SBOM

CVE 掃描：
- Host：AOSP security bulletin 追蹤
- Guest Debian：Debian Security Tracker（dsect.debian.org）
- Guest agents：cargo audit
```

---

# 第二十五章：測試策略

## 25.1 測試金字塔

```
層級 4（E2E）：Cuttlefish + 真機
├── 完整功能測試
├── CTS/VTS subset
└── 使用者體驗測試

層級 3（整合）：Cuttlefish + 模擬器
├── VM lifecycle
├── Bridge 協議
├── Portal 授權
└── OTA 流程

層級 2（模組）：Host unit + Guest unit
├── Framework API
├── SELinux policy
├── Terminal parser
└── Bridge protocol

層級 1（單元）：AOSP atest / Rust test
├── AIDL 界面
├── 狀態機
├── Crypto（HMAC, HKDF）
└── File parser
```

## 25.2 SELinux 測試

```bash
# 使用 atest 運行 SELinux 相關測試
atest CtsSelinuxHostTestCases
atest CtsSELinuxTargetTestCases

# 自訂 neverallow 測試
atest LinuxManagerSelinuxTest

# 手動驗證（userdebug）
adb shell dmesg | grep "avc: denied" | grep linux
```

## 25.3 Bridge 協議模糊測試

```rust
// guest/bridge-agent/fuzz/
// 使用 cargo-fuzz

#[fuzz_target]
fn fuzz_rpc_parser(data: &[u8]) {
    let _ = RpcHeader::parse(data);  // 不應 panic
    let _ = parse_rpc_payload(data); // 不應 panic
}

// 覆蓋場景：
// - 截斷的 header
// - 巨大的 payload_len
// - 錯誤的 magic bytes
// - 錯誤的 checksum
// - 超長 string fields
```

## 25.4 Terminal 測試矩陣

```
自動化 UI 測試（使用 Espresso + UIAutomator）：

| 測試場景 | 測試方法 | 預期結果 |
|---------|---------|---------|
| 注音「ㄓㄨㄥ」→「中」| inject IME events | PTY 收到 U+4E2D |
| Ctrl+C | 點擊快捷列 Ctrl+C | PTY 收到 0x03 |
| Vim mouse mode | 模擬觸碰 Vim 畫面 | 收到 \e[<0;X;YM |
| 旋轉裝置 | setRequestedOrientation | session 不中斷 |
| suspend/resume | PowerManager.goToSleep/wakeUp | Terminal 自動重連 |
| 大量貼上 | setClipboard(1000 chars) | 顯示確認對話框 |
| CJK 寬字元 | 輸入「日」| 渲染為 2-cell |
```

## 25.5 安全測試

```
安全測試清單：

Guest escape attempt:
adb shell su -c "nsenter -t $(pgrep crosvm) -- cat /proc/self/maps | grep data"
→ 預期：Permission denied（SELinux）或 crash

Android /data 存取嘗試（從 Guest）：
# 在 Guest bash 中
ls /proc/$(pgrep crosvm)/fd
→ 預期：看不到 Android data file descriptors

SELinux NEVERALLOW 驗證：
atest LinuxNeverallowTest
→ 預期：所有 neverallow 通過

vsock spoofing：
# 模擬假 Guest 連線（不帶正確 HMAC）
./test_tools/fake_vsock_client --cid 42 --port 5000
→ 預期：Connection refused（HMAC 失敗）

Rollback attack：
./test_tools/stage_older_image.sh
→ 預期：AVB rollback index 拒絕安裝

File traversal（virtiofs）：
# 從 Guest 嘗試
ls /mnt/shared/../../../data/data/
→ 預期：Permission denied
```

---

# 第二十六章：CI/CD 與 Release Engineering

## 26.1 CI Pipeline 設計

```
Presubmit（每次 PR）：
├── 快速編譯檢查（soong check）
├── 單元測試（atest unit tests）
├── SELinux policy lint
├── 安全掃描（lint, cargo audit）
└── Fail-closed gate：任何失敗阻擋 merge

Postsubmit（merge 後）：
├── 完整 AOSP 編譯
├── Cuttlefish boot test
├── Bridge protocol conformance test
├── Guest image build
├── AVB 簽章驗證
└── Fail-closed gate

Nightly：
├── 完整 CTS/VTS subset 測試（Cuttlefish）
├── Guest image 完整測試
├── Fuzzing（bridge protocol, terminal parser）
├── Memory leak 檢測
└── Performance regression 測試

Weekly Security Build：
├── CVE 掃描（Debian Security Tracker）
├── cargo audit（Guest Rust dependencies）
├── SBOM 更新
└── 自動建立 security patch release（如有需要）

Release Candidate：
├── 全套 CTS/VTS
├── 真機測試（ARM64 reference device）
├── OTA 升級測試（A→B→A rollback）
├── 安全審計
└── 人工核准 gate

Stable Release：
├── Release notes 確認
├── AVB 簽章（production key）
├── SBOM 發布
└── Canary → Beta → Stable 逐步推送
```

## 26.2 Guest Image Pipeline

```
Guest Image 建置流程（完全自動化）：

1. Trigger：framework 版本 tag 或 guest agent 更新
2. Build guest agents（Rust cross-compile for aarch64）
3. Debian debootstrap（reproducible build）
4. Install packages（locked versions from Debian snapshot）
5. Install agents
6. Build EROFS base.img
7. AVB sign（production key 或 dev key）
8. 驗證 AVB signature（fail-closed：簽章錯誤→整個 pipeline 失敗）
9. Upload to release server
10. 更新 release manifest（version, hash, URL）

不可接受：
- 沿用舊 Guest image 並繼續宣稱 release 成功
- 未驗證簽章的 image 進入 release channel
```

---

# 第二十七章：可觀測性與故障恢復

## 27.1 dumpsys linux 設計

```bash
# dumpsys 輸出格式
$ adb shell dumpsys linux

=== Linux Manager Service Dump ===
State: RUNNING
Uptime: 2h 34m 12s
Guest CID: 42
Active User: 0
Bridge: CONNECTED (latency: avg=3ms, max=15ms)
Protocol version: host=1.1, guest=1.1

Resources:
  RAM: 1.2GB / 2.0GB (60%)
  CPU: 15.3% (cgroup cpu.stat)
  Disk (overlay): 8.4GB / 20GB (42%)
  Disk (home): 3.1GB / 8GB (38%)
  Network: rx=2.3MB/s tx=0.1MB/s

Sessions:
  PTY: 2 active [session-001 bash, session-002 vim]
  Wayland: 2 surfaces [VS Code, GIMP]

Portals (last 1h):
  Camera: 0 requests
  Microphone: 2 requests (granted, revoked)
  Location: 1 request (denied by user)

Update Status:
  base.img: slot=a, version=1.2.3, avb_index=5
  bridge: guest=1.1, host=1.1 (compatible)
  apt: 3 packages pending

Recent Events (last 10):
  14:32:01 VM_STARTED success
  14:32:02 BRIDGE_AUTHENTICATED
  14:32:03 PTY_SESSION_CREATED session-001
  14:35:20 PORTAL_MIC_REQUESTED by pid 1234
  14:35:21 PORTAL_MIC_GRANTED user=0 pid=1234
  14:36:00 PORTAL_MIC_REVOKED timeout
```

## 27.2 故障分類與恢復路徑

| 故障類型 | 症狀 | 自動恢復 | 人工恢復 |
|---------|------|---------|---------|
| Guest image 損壞 | dm-verity 失敗 | 回滾到 slot B | Reset Linux |
| Bridge 認證失敗 | HMAC mismatch | 重新啟動 VM | 檢查 secret |
| PTY 連接中斷 | Terminal 顯示「Reconnecting」| 自動重連（5 次）| Restart session |
| Wayland compositor crash | Linux GUI App 閃退 | 重啟 compositor | 無需介入 |
| 音訊失敗 | 無聲音 | 重啟 virtio-snd | Settings 重置 |
| 網路失敗 | Guest 無法聯網 | 重啟 netd NAT | 重啟 VM |
| 儲存空間不足 | apt install 失敗 | 通知使用者 | 清理空間 |
| Portal 服務失敗 | Camera 無法開啟 | 重啟 portal daemon | 檢查 AppOps |
| VM 啟動失敗（3次）| 無法開機 | 通知使用者 | Rollback / Reset |
| Host OTA 後不相容 | Bridge version mismatch | 觸發 Guest update | 等待 Guest update |

## 27.3 使用者可見的故障 UI

```
各種故障的通知設計：

VM 啟動失敗：
  標題：「Linux 啟動失敗」
  內容：「請嘗試重新啟動 Linux 環境」
  操作：[重試] [回報問題] [重置 Linux]

Terminal 連接中斷：
  內聯在 Terminal View 顯示：「連接中斷，正在重試...」
  自動重試，成功後透明恢復

空間不足：
  系統通知：「Linux：儲存空間不足（18/20 GB）」
  操作：[管理空間] [擴充儲存]

需要更新（版本不相容）：
  系統通知：「Linux 環境需要更新才能繼續使用」
  操作：[立即更新] [稍後]
```

---

# 第二十八章：產品 UX

## 28.1 Settings 頁面結構

```
Settings > Linux
├── 狀態
│   ├── Linux 執行中 ●（綠）/ 已停止 ○（灰）
│   ├── CPU：15%  RAM：1.2 GB  儲存：8.4/20 GB
│   ├── 執行時間：2小時34分
│   └── [啟動] [停止] [重新啟動]
├── 資源設定
│   ├── RAM 配置：2 GB（滑桿 1-4 GB）
│   ├── CPU 限制：50%
│   ├── 儲存空間：20 GB（可擴充）
│   └── Home 加密：已啟用（CE 綁定）
├── 網路
│   ├── 遵守 Android VPN：✅ 已啟用（不可關閉）
│   ├── 計費網路限制：已啟用
│   └── 已發布的 Port：8080（Dev Server）[移除]
├── 共享資料夾
│   ├── Android → Linux：已啟用（/mnt/shared）
│   └── Linux Files：可在 Files App 瀏覽
├── 硬體 Portal 權限
│   ├── 相機：詢問（每次）
│   ├── 麥克風：詢問（每次）
│   ├── 位置：已拒絕
│   ├── 剪貼簿：詢問
│   └── USB：已禁用
├── 背景服務
│   ├── 螢幕關閉後繼續執行：關閉（預設）
│   └── Doze 模式暫停：開啟（預設）
├── 已安裝的 Linux App
│   ├── VS Code 1.87
│   ├── GIMP 2.10
│   └── LibreOffice 24.2
├── 快照管理
│   ├── snap-2024-03-15 (2.1 GB)
│   └── [建立快照] [還原] [刪除]
├── 備份與還原
│   ├── 上次備份：2024-03-14
│   └── [備份] [還原]
├── 更新
│   ├── 目前版本：1.2.3
│   └── [檢查更新]
├── 診斷
│   ├── [查看日誌] [匯出錯誤報告]
│   └── 問題回報
└── 重置
    ├── [重置 Linux 環境（保留資料）]
    └── [完全重置 Linux]
```

## 28.2 First-run 流程

```
首次啟動流程（使用者不應接觸底層技術名詞）：

Step 1：歡迎畫面
  「歡迎使用 Linux 環境」
  「本裝置支援在 Android 上執行完整的 Linux，
   您可以使用命令列工具、開發環境和 Linux 應用程式。」
  [開始設定]

Step 2：儲存空間
  「選擇 Linux 儲存空間大小」
  滑桿：5 GB ~ 最大可用空間
  建議：20 GB（根據裝置可用空間顯示）
  [繼續]

Step 3：使用者設定
  「設定 Linux 使用者」
  使用者名稱：[user]（可修改）
  密碼：（選填，設定後 sudo 需要密碼）
  [繼續]

Step 4：套件選項
  「選擇初始套件」
  ☑ 基本工具（Git, curl, wget）
  ☑ 開發工具（GCC, Clang, Python, Node.js, Rust）
  ○ 桌面環境（GNOME/Xfce）（+2 GB）
  ○ 辦公套件（LibreOffice）（+1.5 GB）
  ○ 僅基本系統
  [繼續]

Step 5：安裝進度
  「正在安裝 Linux 環境...」
  進度條：下載（45%）/ 安裝（80%）/ 完成（100%）
  預計時間：約 3-10 分鐘（Wi-Fi）
  [不要關閉應用程式]

Step 6：完成
  「Linux 環境已準備就緒！」
  「點擊下方的 Linux Terminal 開始使用。」
  [完成]
```

## 28.3 Launcher 使用者看到的

```
Home Screen（Launcher3）：

Android Apps 區域：
  [電話] [訊息] [地圖] [Camera] ...

Linux 區域（或 Linux 資料夾）：
  [Linux Terminal]    → 開啟原生 Terminal
  [Linux 檔案]        → 開啟 Linux Files DocumentsProvider
  [Linux 設定]        → 開啟 Linux Settings
  [VS Code]           → 啟動 VS Code（Linux GUI App）
  [GIMP]              → 啟動 GIMP（Linux GUI App）
  [LibreOffice Writer]→ 啟動 LibreOffice（Linux GUI App）

App Drawer 搜尋：
  搜尋「vs code」→ 顯示 VS Code（Linux App）
  搜尋「git」→ 顯示 Linux Terminal（帶 git 搜尋 hint）
```

## 28.4 常見通知設計

```
Linux 執行中（前台通知，持續顯示）：
  圖示：Linux 企鵝小圖示
  標題：Linux 執行中
  內容：RAM: 1.2GB  CPU: 15%
  操作：[停止] [開啟 Terminal]

麥克風使用中（隱私指示器）：
  Android 系統級綠色圓點（同原生 App）

更新可用：
  標題：Linux 更新可用
  內容：基礎系統 v1.3.0（42 MB）
  操作：[稍後] [立即更新]

低儲存空間：
  標題：Linux 儲存空間不足
  內容：剩餘空間：2 GB
  操作：[管理空間]
```
