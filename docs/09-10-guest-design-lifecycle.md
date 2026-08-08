# 第九章：Linux Guest 設計

## 9.1 基底發行版選擇

### 評估矩陣

| 發行版 | glibc | systemd | ARM64 套件 | LTS 安全更新 | 商業條件 | Wayland | image 大小 | reproducible build | 選擇 |
|--------|-------|---------|-----------|------------|---------|---------|-----------|-------------------|------|
| Debian 12 (Bookworm) | ✅ | ✅ | 完整 | ~5年 | DFSG（可商業使用）| ✅ | ~500MB base | 積極進行中 | **✅ 選擇** |
| Ubuntu 24.04 LTS | ✅ | ✅ | 完整 | 5年 | 可商業 | ✅ | ~800MB base | 部份 | 備選 |
| Alpine 3.x | musl（非glibc）| ✅ | 部份 | 短（rolling）| MIT | 有限 | ~150MB | ✅ | 不選（musl 相容性）|
| Arch Linux | ✅ | ✅ | 部份 | Rolling | GPL | ✅ | 可 | ❌ | 不選（不穩定）|
| Fedora | ✅ | ✅ | ✅ | ~13個月 | 可商業 | ✅ | ~900MB | 部份 | 不選（生命週期短）|
| postmarketOS | ✅ | OpenRC | 有限 | 長期 | GPL | ✅ | 小 | 部份 | 不選（非主流）|

**決策：Debian 12 (Bookworm) ARM64**

理由：
- glibc 相容性最佳，幾乎所有 Linux 軟體可直接安裝
- systemd 完整支援
- ARM64 (`arm64`) 套件庫完整，包含 gcc, clang, rust, nodejs, python
- Debian Free Software Guidelines 允許商業發佈
- 安全更新到 2028 年
- 官方提供 debootstrap，方便建立 base image

## 9.2 Guest 儲存結構

```
VM Storage Layout (per user)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/data/user/0/com.android.linux/files/
├── base_a.img          [EROFS, read-only, dm-verity signed, ~500MB]
├── base_b.img          [EROFS, A/B slot B, for staged updates]
├── overlay_rw.img      [ext4, writable overlay, ~2GB initial]
├── user_home.img       [ext4 in LUKS2, CE-key encrypted, ~8GB]
├── vm_metadata.bin     [state: active_slot, boot_count, etc]
├── update_staging/     [temporary download area]
├── snapshots/
│   └── snap-{timestamp}.img
└── crash_dumps/
    └── kdump-{timestamp}.gz

Mount 結構 (inside VM)：
/                 → EROFS base_a.img (read-only)
/                 → overlayfs (overlay_rw.img 作為 upper layer)
/home/user        → user_home.img (LUKS2 decrypted, ext4, rw)
/mnt/shared       → virtiofs host:/data/media/0/LinuxShared
/tmp              → tmpfs
/proc, /sys, /dev → 標準 kernel 虛擬 FS
```

## 9.3 儲存格式選擇理由

| 層 | 格式 | 理由 |
|----|------|------|
| base image | EROFS | 唯讀、可 dm-verity 驗證、壓縮比高、隨機讀取快 |
| writable overlay | ext4 | 成熟、crosvm 支援、journaling 防崩潰、resize2fs |
| user home | ext4 in LUKS2 | LUKS2 提供 CE key 綁定，ext4 提供完整 POSIX |
| snapshot | ext4 + btrfs subvol 或 qcow2 | 按照 crosvm 支援程度選擇 |
| swap | 可選 zram 或 swap file | 建議先用 zram，節省 I/O |

## 9.4 LUKS2 + Android CE Key Binding

```
Android 解鎖流程（PIN 驗證後）：
1. Android Keystore 釋放 CE Master Key
2. LinuxManagerService 使用 HKDF-SHA256 衍生 LUKS2 passphrase：
   luks_key = HKDF-SHA256(
       IKM  = android_ce_master_key,
       salt = vm_instance_id + user_id,
       info = "aosp_linux_luks2_v1"
   )
3. linux_bridge daemon 透過 vsock 將 key 傳給 Guest（TLS-encrypted）
4. Guest android-bridge-agent 調用 cryptsetup luksOpen
5. 掛載 /home/user
6. zeroize luks_key（Rust: zeroize crate）

鎖屏時：
1. PowerManager 觸發 screen lock
2. LinuxManagerService 通知 Guest 同步
3. Guest android-bridge-agent 執行 sync + unmount /home/user
4. cryptsetup luksClose
5. Host CE Master Key 被 zeroize
6. Guest home 完全鎖定
```

## 9.5 Guest Kernel 設定

```
Linux 6.6+ ARM64 kernel configuration 關鍵項目：

必須開啟：
CONFIG_KVM_GUEST=y              # KVM para-virtualization
CONFIG_VIRTIO=y
CONFIG_VIRTIO_BLK=y             # 磁碟
CONFIG_VIRTIO_NET=y             # 網路
CONFIG_VIRTIO_CONSOLE=y         # 序列主控台
CONFIG_VIRTIO_VSOCK=y           # AF_VSOCK ← 關鍵
CONFIG_VIRTIO_FS=y              # virtiofs
CONFIG_VIRTIO_SND=y             # 音訊
CONFIG_VIRTIO_GPU=y             # GPU（EXPERIMENTAL）
CONFIG_VIRTIO_INPUT=y           # 輸入設備
CONFIG_DM_VERITY=y              # dm-verity for base image
CONFIG_DM_CRYPT=y               # LUKS2 支援
CONFIG_OVERLAY_FS=y             # overlayfs
CONFIG_CGROUPS=y                # cgroup v2
CONFIG_EXT4_FS=y
CONFIG_EROFS_FS=y               # EROFS base image

建議開啟：
CONFIG_ZRAM=y                   # zram swap
CONFIG_PSI=y                    # Pressure Stall Information
CONFIG_TRANSPARENT_HUGEPAGE=y  # 記憶體效能
CONFIG_VSOCK_DIAG=y             # vsock 診斷
CONFIG_INOTIFY_USER=y           # .desktop 監控

建議關閉（減少攻擊面）：
# CONFIG_MODULES is NOT set  (或僅允許簽署模組)
# CONFIG_PROC_SYSCTL 只允許必要項目
# CONFIG_BINFMT_MISC is NOT set (非必要)
```

## 9.6 Guest Agent 系統設計

### android-bridge-agent（Rust）

```
路徑：guest/bridge-agent/src/main.rs

職責：
- vsock Port 5000 監聽
- HMAC-SHA256 challenge-response 握手
- 接收並路由 Host RPC 命令
- 回報系統狀態
- 轉發 Portal 請求給 xdg-portal-agent
- 處理 Guest shutdown 命令

啟動：systemd service，After=network.target
Socket activation：由 systemd socket unit 管理 vsock 監聽
```

### pty-agent

```
路徑：guest/pty-agent/

職責：
- vsock Port 5001 監聽
- 管理 PTY session（openpty, fork, exec bash/zsh）
- 多 session 支援
- Session 持久化（關閉 Terminal 後 session 不消失）
- 傳輸二進位資料（PTY 輸出/輸入）
- 處理 resize (SIGWINCH)
- 支援 tmux attach（透過 SSH 或直接 PTY）
```

### wayland-agent / Sommelier-like bridge

```
路徑：guest/wayland-agent/

職責：
- 接收 Guest Wayland clients 的 surface 建立請求
- 透過 virtio-gpu dma-buf 映射到 Host
- 每個頂層 window → 一個 Host SurfaceControl / LinuxAppProxyActivity
- 傳遞輸入事件（keyboard, mouse, touch）到正確的 Guest window
- 處理 window resize、focus 等
```

### xdg-portal-agent

```
路徑：guest/xdg-portal-agent/

職責：
- 實作 XDG Desktop Portal D-Bus 介面
- 攔截 Linux App 的 org.freedesktop.portal.Camera/Microphone/Location/FileChooser 請求
- 透過 vsock 轉發到 Host LinuxPortalService
- 將 Host 的回應（permission granted + data stream）返回給 Linux App
```

### update-agent

```
路徑：guest/update-agent/

職責：
- 接收 Host 的 OTA 更新指令
- 下載新 base image 到 base_b.img（透過 virtiofs 或直接 URL）
- 驗證 AVB 簽章
- 標記 pending update（寫入 vm_metadata）
- 支援 apt 套件更新（在 overlay 層）
- 報告更新進度到 Host
```

## 9.7 Guest systemd 服務單元設計

```ini
# /etc/systemd/system/android-bridge.service
[Unit]
Description=Android Bridge Agent
After=network.target
Requires=android-bridge.socket

[Socket]
# vsock socket activation
ListenStream=vsock:5000
Accept=no

[Service]
Type=simple
ExecStart=/usr/bin/android-bridge-agent
Restart=always
RestartSec=1
# Security hardening
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=no    # 需要訪問 /home
AmbientCapabilities=
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
```

## 9.8 User Profile 設定

```bash
# Guest 預設使用者
Username: user
UID: 1000
Groups: user, sudo, audio, video, plugdev

# sudo 政策
# /etc/sudoers.d/user
user ALL=(ALL) NOPASSWD: /usr/bin/apt-get update, /usr/bin/apt-get install, 
                          /usr/bin/apt-get upgrade, /usr/bin/systemctl *

# 注意：完整 root sudo 需要 user 明確確認
# 預設不授予無限制 sudo

# Guest root 存在，但 user 不預設是 root
# Developer mode 下可開啟 sudo ALL
```

## 9.9 網路與時間同步

```
Guest 網路配置：
- interface: eth0 (virtio-net)
- IP: 10.0.2.15/24 (crosvm default NAT)
- Gateway: 10.0.2.2 (Host NAT gateway)
- DNS: 透過 Host Android 解析（遵守 Private DNS 設定）

時間同步：
- Guest 使用 virtio-ptp 或 Host RTC 同步
- 每次 VM 啟動時，android-bridge-agent 接收 Host 時間戳
- systemd-timesyncd 設定從 Host 同步

Hostname：
- 固定為 "linux" 或 "android-linux-{device_id}"

Machine-ID：
- 首次啟動生成，綁定到 user_home.img
- 重置 Linux 環境時重新生成
```

---

# 第十章：VM Lifecycle

## 10.1 完整 VM 啟動流程

```
Phase 1: 前置條件檢查（系統服務層）
────────────────────────────────────
1. LinuxManagerService.ensureRunning() 調用
2. 檢查 Android 使用者是否已解鎖（CE storage 可用）
3. 確認 base image 存在且簽章有效
4. 確認 overlay_rw.img 健康（未損壞）
5. 衍生 LUKS2 key（需要 CE master key）
6. 分配記憶體（根據 LinuxUserPolicy）

Phase 2: VM 建立（AVF 層）
──────────────────────────
7. LinuxManagerService 呼叫 VirtualizationService.createVm(config)
8. config 包含：
   - cpu_count, memory_size
   - disk: base_a.img (or base_b.img per active slot)
   - disk: overlay_rw.img (rw)
   - disk: user_home.img.luks (rw)
   - net: tap (NAT)
   - vsock: enable
   - virtio-fs: /data/media/0/LinuxShared
   - audio: enable (if supported)
   - gpu: virglrenderer/gfxstream (if EXPERIMENTAL and enabled)
9. virtmgr 啟動 crosvm 進程（SELinux: crosvm.te）

Phase 3: Guest Boot
──────────────────
10. crosvm 載入 kernel image
11. Linux kernel 初始化，KVM para-virt
12. initramfs 執行：
    a. 驗證 base image dm-verity
    b. mount base_a.img as EROFS
    c. mount overlay_rw.img as overlayfs upper
    d. pivot_root 到 overlayfs
13. systemd (PID 1) 啟動
14. 掛載 /home/user (LUKS2 passphrase 透過 vsock 取得)
15. android-bridge-agent 啟動，進行 HMAC handshake

Phase 4: 握手與就緒
───────────────────
16. Host 發送 challenge（32 bytes 隨機數）
17. Guest 計算 HMAC-SHA256(shared_key, challenge || timestamp)
18. Host 驗證，生成 session token
19. android-bridge-agent 調用 IVmPayloadService.notifyPayloadReady()
20. Host onPayloadReady() 回調
21. LinuxManagerService 狀態 → RUNNING
22. onStateChanged(RUNNING) 廣播

Phase 5: 服務建立
─────────────────
23. pty-agent 啟動（vsock Port 5001）
24. 如果 GUI 支援：wayland-agent 啟動（vsock Port 5002）
25. xdg-portal-agent 啟動
26. update-agent 啟動
```

## 10.2 Suspend 流程

```
觸發條件：
- PowerManager.goToSleep()（螢幕關閉）
- 使用者主動 suspend（Settings）
- Doze 模式

Suspend 步驟：
1. LinuxManagerService 接收 PowerManager wake lock 釋放信號
2. 發送 RPC: PREPARE_FREEZE
3. Guest android-bridge-agent：
   a. 通知所有服務準備暫停
   b. PTY sessions 標記為 suspended
   c. sync && echo 3 > /proc/sys/vm/drop_caches（可選）
4. Host 呼叫 virtualMachine.suspend()
5. crosvm 保存 VM 狀態到 vm_state.snapshot（可選，增加 resume 速度）
6. 狀態 → SUSPENDED

Resume 步驟：
1. LinuxManagerService 接收 PowerManager screen on
2. 呼叫 virtualMachine.resume()
3. crosvm 從快照或暫停狀態恢復
4. Guest 繼續執行
5. android-bridge-agent 重新建立 vsock connection
6. 重新驗證 session（replay protection：timestamp 檢查）
7. Terminal sessions 重新連接
8. 狀態 → RUNNING
```

## 10.3 Crash Recovery

```
Guest Crash：
1. crosvm 退出（exit code 33: CRASH）
2. virtmgr 偵測到 onDied(CRASH)
3. LinuxManagerService 狀態 → FAILED
4. 等待 3 秒
5. 嘗試自動重啟（最多 3 次）
6. 若 3 次仍失敗 → DEGRADED 狀態
7. 通知使用者（Notification）
8. Offer recovery options: restart / rollback / reset

Bridge Crash：
1. linux_bridge daemon 退出
2. 系統自動重啟 daemon（systemd-like restart）
3. 重新握手

Host Reboot：
1. system_server shutdown 觸發 LinuxManagerService.onSystemShutdown()
2. 發送 SHUTDOWN RPC 到 Guest
3. 等待 graceful shutdown（3s timeout）
4. 強制停止 VM
5. 重開機後狀態從 STOPPED 開始

Android Kernel Panic：
1. VM 直接消失（外部停止）
2. Guest 未被通知（直接 killed）
3. 下次啟動時 vm_metadata 顯示 unclean shutdown
4. 觸發 fsck 或一致性檢查
```

## 10.4 資源限制策略

```
預設資源限制（可由 LinuxUserPolicy 調整）：

CPU：
- 最高 50% CPU（cgroup cpu.max）
- 前景 Linux App：優先級提升
- 後台服務：Nice +10

記憶體：
- 預設 2 GB（可設定 1-4 GB）
- 記憶體氣球（virtio-balloon）動態調整
- Host 記憶體壓力時自動縮減
- PSI 監控：10% stall → 觸發 balloon

磁碟：
- 系統 disk IO：最低優先（ionice class 3）
- /home write：normal（ionice class 2）

網路：
- 遵守 Android NetworkPolicyManager
- Metered 網路下限速（可設定）
- Doze 時斷網（除非有 wake lock）

電池優化：
- 螢幕關閉 30 秒後：CPU 降頻
- 螢幕關閉 5 分鐘後：suspend VM（可設定）
- 充電時：不限制（可設定）
```

## 10.5 SLO 目標

| 指標 | 目標值 | 可接受上限 | 假設硬體 | 測量方法 |
|------|--------|-----------|---------|---------|
| Cold boot → Terminal ready | ≤ 8 秒 | ≤ 15 秒 | Cortex-A76, 8GB RAM | `adb logcat` timestamp |
| Warm resume → Terminal reconnect | ≤ 2 秒 | ≤ 5 秒 | 同上 | 計時 suspend→reconnect |
| Linux App 啟動 | ≤ 3 秒 | ≤ 8 秒 | 同上 + virglrenderer | 應用啟動到 first frame |
| Touch input → PTY latency | ≤ 50ms | ≤ 100ms | 同上 | 硬體計時器 |
| GUI frame latency | ≤ 16ms (60fps) | ≤ 33ms | 同上 + virtio-gpu | SurfaceFlinger 統計 |
| Idle RAM overhead | ≤ 400MB | ≤ 600MB | 8GB 裝置 | `dumpsys meminfo` |
| Active RAM (Terminal only) | ≤ 600MB | ≤ 900MB | 同上 | 同上 |
| Idle battery drain | ≤ 1% /hour | ≤ 2% /hour | 4000mAh | `batterystats` |
| VM crash recovery | ≤ 30 秒 | ≤ 60 秒 | 同上 | 崩潰到恢復 |
| Guest OTA update | ≤ 5 分鐘 | ≤ 15 分鐘 | Wi-Fi | 下載+安裝計時 |

所有數值為目標值，基於 Cortex-A76 類 SoC + 8GB RAM 的假設，實際依硬體大幅調整。
