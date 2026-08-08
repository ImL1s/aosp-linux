# 第十七章：音訊設計

## 17.1 音訊架構

```
Guest Linux App
    ↓ PipeWire/PulseAudio (client)
Guest PipeWire daemon
    ↓ virtio-snd driver (ALSA backend)
crosvm virtio-snd device
    ↓ Host audio bridge
Host AudioService
    ↓ AudioFocus + routing
Speaker / Bluetooth / USB Audio
```

## 17.2 virtio-snd 設定

```
crosvm 音訊設定：
--sound pcm_s16le:48000:2   （16-bit PCM, 48kHz, Stereo）
--sound pcm_s16le:16000:1   （語音：16kHz, Mono）
--capture                   （啟用麥克風）
```

Guest 側設定：
- 安裝 `linux-modules-extra-$(uname -r)` 包含 `snd-virtio.ko`
- PipeWire 使用 ALSA backend（`/dev/snd/pcmC0D0p`）
- PulseAudio 相容層（pulseaudio-socket for legacy apps）

## 17.3 AudioFocus 政策

```java
// Host 側 AudioFocus 管理
// 當 Linux 播放音訊時，請求 AudioFocus

class LinuxAudioBridge {
    AudioFocusRequest audioFocusRequest;
    
    void onLinuxAudioStart() {
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(this)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build())
            .build();
        
        audioManager.requestAudioFocus(audioFocusRequest);
    }
    
    @Override
    void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AUDIOFOCUS_LOSS:
                // 暫停 Guest 音訊（透過 Bridge RPC）
                bridge.pauseGuestAudio();
                break;
            case AUDIOFOCUS_LOSS_TRANSIENT:
                // 暫時靜音（電話來電）
                bridge.muteGuestAudio(true);
                break;
            case AUDIOFOCUS_GAIN:
                bridge.muteGuestAudio(false);
                break;
        }
    }
}
```

## 17.4 麥克風權限

- 麥克風存取：必須透過 XDG Portal（見第十九章）
- 即使 Guest root 也不能直接存取 `/dev/snd/pcmC0D0c`（virtio-snd capture）
- 必須 Android runtime permission + XDG portal 授權
- 啟動麥克風錄音時顯示 Android 隱私指示器（綠色圓點）

---

# 第十八章：輸入設計

## 18.1 鍵盤輸入架構

```
實體鍵盤（USB/Bluetooth）→ Android InputDispatcher
    ↓ 若焦點在 Terminal/Linux App
LinuxWindowBridgeService
    ↓ 轉換為 Linux input events
virtio-input / vsock keyboard events → Guest
    ↓
Guest evdev → Wayland compositor → Linux App
```

## 18.2 觸碰輸入架構

```
Android 觸碰事件 → MotionEvent
    ↓
TerminalView / LinuxAppProxyActivity
    ↓
TouchModeController（模式判斷）
    ↓ Shell Mode:  scroll / long-press select / IME trigger
    ↓ TUI Mode:    轉換為 SGR mouse events → PTY
    ↓ Touchpad:    轉換為 relative mouse moves → virtio-input
    ↓ GUI Mode:    absolute coordinates → Wayland pointer events
Guest compositor
```

## 18.3 硬體鍵盤佈局

```
支援的佈局：
- QWERTY（各語言變體）
- AZERTY（法語）
- QWERTZ（德語）
- Dvorak, Colemak

CJK 輸入法（實體鍵盤）：
- 使用 Android IME 組字（同觸碰，在 Terminal 懸浮視窗顯示）
- 組字確定後轉為 UTF-8 發送到 PTY

Modifier key 組合：
- Ctrl+A-Z：正確映射到 control characters
- Alt+char：ESC 前綴（\e + char）
- Shift+修飾鍵：正確傳遞

鍵盤快捷鍵衝突解決：
- Android 系統快捷鍵（e.g., Ctrl+Space for IME）優先 Android 處理
- 在 Terminal 專注模式下，大多數快捷鍵轉發到 Guest
- 例外：Ctrl+H（Home）、Ctrl+M（Menu）保留給 Android
```

## 18.4 遊戲手把與旋轉輸入

目前不在第一優先範圍，但預留 virtio-input 介面：
- gamepad events 可透過 virtio-input 轉發
- rotary input（車機）暫不支援
- stylus：在 Touchpad Mode 下作為高精度滑鼠

---

# 第十九章：Hardware Portals

## 19.1 Portal 架構

XDG Desktop Portal 的設計目標是讓 Flatpak sandboxed app 能安全請求硬體權限。本專案將此模式應用到 Linux VM：

```
Linux App
    ↓ D-Bus org.freedesktop.portal.*
xdg-portal-agent（Guest）
    ↓ vsock Bridge RPC (PORTAL_REQUEST)
LinuxPortalService（Host, system_server）
    ↓ checkPermission + showDialog
PermissionController
    ↓ 使用者授權
Android Runtime + Hardware HAL
    ↓ 串流資料回傳（via vsock or virtio device）
xdg-portal-agent
    ↓ D-Bus 回應
Linux App
```

## 19.2 Portal 權限矩陣

### 第一層（低敏感度，可設定自動授權）

| Portal | Linux 呼叫 | Android API | 授權類型 | 背景限制 |
|--------|-----------|------------|---------|---------|
| clipboard.read | xdg-portal Clipboard | ClipboardManager | 一次性確認 | 前台 App 才能讀 |
| clipboard.write | 同上 | ClipboardManager | 自動（有通知）| 無限制 |
| files.open | xdg-portal FileChooser | SAF Intent | 每次 | N/A |
| notification.post | xdg-portal Notification | NotificationManager | 自動（已授權 notification）| 有限制 |
| battery.read | xdg-portal PowerMonitor | BatteryManager | 自動 | 無 |
| theme.read | xdg-portal Settings | UiModeManager | 自動 | 無 |
| locale.read | xdg-portal Settings | Locale | 自動 | 無 |

### 第二層（高敏感度，必須每次授權）

| Portal | Linux 呼叫 | Android API | Android Permission | 隱私指示器 | 背景限制 |
|--------|-----------|------------|-------------------|-----------|---------|
| camera.capture | XDG Camera | Camera2 | CAMERA | 綠點 | 需前台服務 |
| microphone.capture | XDG Microphone | AudioRecord | RECORD_AUDIO | 綠點 | 需前台服務 |
| location.fine | XDG Location | LocationManager | ACCESS_FINE_LOCATION | 箭頭 | 需精確位置權限 |
| location.coarse | XDG Location | LocationManager | ACCESS_COARSE_LOCATION | 箭頭 | 有限 |
| contacts.query | XDG Contacts（自製）| ContentResolver | READ_CONTACTS | 無 | 無 |
| biometric.auth | XDG Biometric（自製）| BiometricPrompt | USE_BIOMETRIC | 無 | 不允許背景 |

### Linux root 不自動取得 Portal 權限

即使 Guest root（UID 0）發出 Portal 請求，仍必須：
1. 通過 Bridge 認證（session token）
2. 通過 AppOps 檢查（OP_LINUX_CAMERA 等）
3. 通過使用者確認對話框

## 19.3 Biometric 特殊處理

```
Guest 請求 biometric 認證：
1. xdg-portal-agent 發送 BIOMETRIC_AUTH_REQUEST vsock RPC
2. LinuxPortalService 呼叫 BiometricPrompt
3. 使用者在 Android 原生指紋/臉部對話框驗證
4. BiometricPrompt.AuthenticationCallback
   - onAuthenticationSucceeded → 回傳 SUCCESS（bool）
   - onAuthenticationFailed → 回傳 FAILURE
5. 不傳送任何生物特徵資料到 Guest
6. Guest 只收到 SUCCESS/FAILURE

重要：Guest 永遠不接觸 biometric template 或 raw sensor data。
```

## 19.4 相機 Portal 實作

```java
// LinuxPortalService 相機 Portal 處理
void handleCameraRequest(BridgeRpc.CameraRequest request, long sessionToken) {
    // 1. 驗證 session token
    if (!authManager.validateToken(sessionToken)) {
        bridge.sendError(request.requestId, ERROR_AUTH_FAILED);
        return;
    }
    
    // 2. 檢查 AppOps
    if (appOpsManager.noteOp(OP_LINUX_CAMERA, Process.myUid(), packageName) 
            != AppOpsManager.MODE_ALLOWED) {
        bridge.sendError(request.requestId, ERROR_PERMISSION_DENIED);
        return;
    }
    
    // 3. 顯示使用者對話框
    showCameraPermissionDialog(new PermissionCallback() {
        @Override
        void onGranted() {
            // 4. 開啟相機，串流 JPEG/YUV 到 Guest via vsock
            openCameraAndStream(request.requestId, request.cameraId);
        }
        
        @Override
        void onDenied() {
            bridge.sendError(request.requestId, ERROR_USER_DENIED);
        }
    });
}
```

---

# 第二十章：電源與效能

## 20.1 電源狀態管理

```
螢幕開啟（Interactive）：
- VM 完全運行
- 所有服務可用
- 正常 CPU/記憶體配額

螢幕關閉（30 秒後）：
- 通知 Guest 準備降速
- CPU 降頻（cpuset: background）
- 背景服務繼續（如果使用者設定）
- 前台通知："Linux running in background"

螢幕關閉（5 分鐘後，預設）：
- Suspend VM
- 釋放 Guest 實體記憶體（balloon 縮小）
- 狀態 → SUSPENDED

充電中（可設定）：
- 不 suspend
- 允許 apt upgrade
- 允許備份

Battery Saver 模式：
- 限制 VM CPU 到 25%
- 禁止 GUI 轉發（Terminal 仍可用）
- 加速 suspend（30 秒後 suspend）
```

## 20.2 記憶體管理

```
virtio-balloon 策略（MemBalloonController 擴展）：

Host PSI memory pressure:
  level=LOW    → balloon +10% Guest memory
  level=MEDIUM → balloon +30% Guest memory
  level=HIGH   → balloon +50% + warn user
  level=CRITICAL → emergency: force suspend VM

Guest memory breakdown（2GB 配置）：
  - Linux kernel: ~100MB
  - systemd services: ~200MB
  - Bridge agents: ~100MB
  - User apps (Terminal, bash): ~200MB
  - Cached/free: ~1.4GB

zram 策略：
  - Guest 內部啟用 zram（/dev/zram0, 壓縮比約 2:1）
  - zram size = 50% of configured memory
  - swap 到 zram 而非實體 swap（減少 I/O）
```

## 20.3 CPU 配額

```
cgroup v2 設定（透過 virtmgr）：
  前景 Linux App（Terminal 可見）：
    cpu.max = "50000 100000"  (50% = 2 cores on 4-core)
  背景（螢幕關閉，有背景服務）：
    cpu.max = "10000 100000"  (10%)
  Suspended：
    cpu.max = "0 100000"      (0% = VM 暫停)

cpuset：
  - 前景：使用中高效能核心（big cores）
  - 背景：使用效能核心（little cores）
  - 螢幕關閉：僅使用最低效能核心（若有）

熱保護：
  ThermalManager throttle callback → 降低 CPU 配額
  嚴重過熱 → 強制 suspend VM
```

## 20.4 Doze 整合

```
Doze 進入：
1. PowerManager.BATTERY_CHANGED → 未充電 + 靜止 + 螢幕關閉
2. 進入 light idle → 通知 Guest 準備 idle
3. 進入 deep idle → VM suspend（強制）
4. 網路切斷（除非有 high-priority push）

Doze 退出：
1. 螢幕開啟 或 充電插入
2. VM resume
3. Guest 接收 RESUME_FROM_DOZE 通知
4. 恢復所有服務

背景服務例外（使用者設定）：
- FOREGROUND_SERVICE_SPECIAL_USE category: "linux_background"
- 允許一個持續前台服務（通知欄顯示 Linux 圖示）
- 但 Doze 期間仍限制網路（同 WorkManager）
```

## 20.5 效能分析工具

```
Host 側：
- `adb shell dumpsys linux` → VM 狀態、資源使用
- `adb shell dumpsys batterystats` → Linux 電池消耗
- `adb shell cat /proc/cgroups | grep linux` → cgroup 狀態
- `adb shell simpleperf stat -a --duration 10` → CPU profile

Guest 側（透過 Terminal）：
- htop / btop（CPU/記憶體）
- vmstat（記憶體 balloon 變化）
- iotop（磁碟 IO）
- sar（系統統計）
- journalctl -f（系統日誌）
```
