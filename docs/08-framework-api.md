# 第八章：Framework API 設計

## 8.1 架構決策：system_server 還是獨立 daemon？

**決策**：混合架構 — system_server API + 隔離 native daemon

理由：
- `LinuxManagerService` 做狀態機、Binder API、policy 決策，放進 system_server（uid=1000）
- `linux_bridge` 做 vsock packet parsing 和協議轉發，作為獨立 native daemon（uid=linux_bridge）
- 隔離 `linux_bridge` 可大幅降低 system_server crash blast radius：橋接協議解析 bug 不會讓整個 system_server 崩潰
- vsock 的 SecComp policy 只需要在 `linux_bridge` 上實施，不影響 system_server

```
system_server (uid=1000)
└── LinuxManagerService [state machine, Binder API, policy]
    └── binds to linux_bridge_daemon via Unix socket
        └── linux_bridge daemon (uid=linux_bridge) [vsock framing, auth]
            └── AF_VSOCK ──► Guest android-bridge-agent
```

## 8.2 AIDL 介面定義

### ILinuxManager.aidl（NEW）

```java
// 路徑：frameworks/base/core/java/android/system/linux/ILinuxManager.aidl
// 需要 signature-level 或 USE_LINUX_TERMINAL 權限

package android.system.linux;

import android.system.linux.ILinuxStatusCallback;
import android.system.linux.ILinuxTerminalCallback;
import android.system.linux.LinuxAppInfo;
import android.system.linux.LinuxHealthStatus;
import android.system.linux.LinuxResourceUsage;
import android.system.linux.LinuxVmState;

interface ILinuxManager {
    // === VM Lifecycle ===
    void start();
    void stop();
    void shutdown();
    void suspend();
    void resume();
    void restart();
    
    // === 狀態查詢 ===
    LinuxVmState getState();
    int getBootProgress();           // 0-100
    LinuxHealthStatus getHealthStatus();
    LinuxResourceUsage getResourceUsage();
    
    // === Terminal Session ===
    String createTerminalSession(@nullable String workingDir);  // returns sessionId
    boolean attachTerminalSession(String sessionId, ILinuxTerminalCallback callback);
    void closeTerminalSession(String sessionId);
    String[] listTerminalSessions();
    
    // === App Management ===
    boolean launchLinuxApp(String desktopFileId, @nullable String[] args);
    void stopLinuxApp(String pid);
    LinuxAppInfo[] listInstalledLinuxApps();
    
    // === Image Management ===
    void installGuestImage(String imagePath);
    void updateGuestImage();
    void rollbackGuestImage();
    void resizeDisk(long newSizeBytes);
    
    // === Resource Control ===
    void setMemoryLimit(long limitBytes);
    void setCpuLimit(int cpuPercent);
    
    // === Port Publishing ===
    boolean publishPort(int guestPort, int hostPort, String label);
    void revokePublishedPort(int hostPort);
    int[] getPublishedPorts();
    
    // === Snapshot ===
    String createSnapshot(String label);   // returns snapshotId
    void restoreSnapshot(String snapshotId);
    void deleteSnapshot(String snapshotId);
    String[] listSnapshots();
    
    // === Backup / Restore ===
    void exportBackup(ParcelFileDescriptor fd, ILinuxStatusCallback progress);
    void importBackup(ParcelFileDescriptor fd, ILinuxStatusCallback progress);
    
    // === Reset ===
    void resetLinuxEnvironment(boolean keepHome);
    
    // === Callback Registration ===
    void registerStatusCallback(ILinuxStatusCallback callback);
    void unregisterStatusCallback(ILinuxStatusCallback callback);
    
    // === Debug ===
    String dumpState();
    void enableDebugLogging(boolean enable);
}
```

### ILinuxStatusCallback.aidl（NEW）

```java
// oneway: 所有回調不阻塞
oneway interface ILinuxStatusCallback {
    void onStateChanged(LinuxVmState newState, int reason);
    void onBootProgress(int progress, String stage);
    void onError(int errorCode, String message);
    void onAppInstalled(String desktopFileId);
    void onAppUninstalled(String desktopFileId);
    void onPortPublished(int guestPort, int hostPort);
    void onPortRevoked(int hostPort);
    void onUpdateAvailable(String version, long sizeBytes);
    void onUpdateProgress(int progress, String stage);
    void onResourceWarning(int type, long current, long limit);
}
```

### ILinuxTerminalCallback.aidl（NEW）

```java
oneway interface ILinuxTerminalCallback {
    void onData(String sessionId, byte[] data);
    void onResize(String sessionId, int cols, int rows);
    void onDisconnected(String sessionId, int reason);
    void onReconnected(String sessionId);
}
```

## 8.3 Parcelable 資料模型

```java
// LinuxVmState.java - 狀態枚舉
public enum LinuxVmState {
    NOT_INSTALLED,   // 未安裝 Guest image
    INSTALLING,      // 正在安裝
    STOPPED,         // 已停止
    STARTING,        // 正在啟動 VM
    BOOTING,         // VM 已啟動，等待 Guest 系統啟動
    RUNNING,         // 正常運行
    SUSPENDING,      // 正在掛起
    SUSPENDED,       // 已掛起
    RESUMING,        // 正在恢復
    STOPPING,        // 正在停止
    UPDATING,        // 正在更新 Guest image
    ROLLING_BACK,    // 正在回滾
    DEGRADED,        // 部份功能失敗（e.g., GUI 失敗但 Terminal 正常）
    FAILED           // 致命錯誤，需要重啟或 reset
}

// LinuxAppInfo.java
public class LinuxAppInfo implements Parcelable {
    public String desktopFileId;     // e.g., "vscode.desktop"
    public String displayName;       // localized name
    public String execCommand;       // parsed Exec= field (sanitized)
    public String iconName;          // icon identifier
    public String[] categories;
    public String mimeType;
    public boolean isRunning;
    public int pid;                  // -1 if not running
}

// LinuxHealthStatus.java
public class LinuxHealthStatus implements Parcelable {
    public LinuxVmState state;
    public boolean bridgeConnected;
    public boolean terminalReady;
    public boolean waylandReady;
    public boolean audioReady;
    public boolean networkReady;
    public long lastHeartbeatMs;
    public String[] activeAlerts;
}

// LinuxResourceUsage.java
public class LinuxResourceUsage implements Parcelable {
    public long memoryUsedBytes;
    public long memoryLimitBytes;
    public float cpuUsagePercent;
    public long diskUsedBytes;
    public long diskTotalBytes;
    public long networkRxBytes;
    public long networkTxBytes;
    public long uptimeMs;
}
```

## 8.4 VM 狀態機完整規格

```
狀態轉換表：

FROM               EVENT              TO                  TIMEOUT   ROLLBACK
NOT_INSTALLED  → install_start     → INSTALLING           30min     → NOT_INSTALLED
INSTALLING     → install_done      → STOPPED              -         -
INSTALLING     → install_fail      → NOT_INSTALLED        -         -
STOPPED        → start()           → STARTING             -         -
STARTING       → vm_boot_done      → BOOTING              30s/300s  → STOPPED
BOOTING        → guest_ready       → RUNNING              60s       → STOPPED
RUNNING        → stop()            → STOPPING             3s        → FAILED
RUNNING        → suspend()         → SUSPENDING           10s       → RUNNING
RUNNING        → crash             → FAILED               -         auto
SUSPENDING     → suspend_done      → SUSPENDED            -         -
SUSPENDING     → timeout           → RUNNING              -         -
SUSPENDED      → resume()          → RESUMING             -         -
RESUMING       → resume_done       → RUNNING              15s       → STOPPED
RUNNING        → update_start      → UPDATING             -         -
UPDATING       → update_done       → RUNNING              -         -
UPDATING       → update_fail       → RUNNING              -         rollback
FAILED         → reset/restart     → STARTING             -         -

Reboot 後狀態恢復：
- SUSPENDED → SUSPENDED（如果有 snapshot），否則 → STOPPED
- RUNNING → STOPPED（VM 不跨 reboot 保持）
- UPDATING → ROLLING_BACK（中斷的更新必須回滾）
```

每個 transition 的 Binder callback：

```
STOPPED → STARTING:     onStateChanged(STARTING, REASON_USER_REQUEST)
STARTING → BOOTING:     onStateChanged(BOOTING, REASON_VM_STARTED) + onBootProgress(0, "vm_boot")
BOOTING → RUNNING:      onStateChanged(RUNNING, REASON_GUEST_READY) + onBootProgress(100, "ready")
RUNNING → SUSPENDING:   onStateChanged(SUSPENDING, REASON_POWER_SAVE)
...
```

## 8.5 權限模型

```xml
<!-- frameworks/base/core/res/AndroidManifest.xml 中新增 -->

<!-- 允許使用 Terminal（一般使用者）-->
<permission android:name="android.permission.USE_LINUX_TERMINAL"
    android:protectionLevel="normal" />

<!-- 允許管理 Linux 環境（系統或特權 App）-->
<permission android:name="android.permission.MANAGE_LINUX_ENVIRONMENT"
    android:protectionLevel="signature|privileged" />

<!-- 允許 Portal 存取（系統服務內部）-->
<permission android:name="android.permission.LINUX_PORTAL_ACCESS"
    android:protectionLevel="signature" />
```

## 8.6 AppOps 操作定義（NEW）

```java
// 新增 AppOps operations（在 AppOpsManager 中擴充）
OP_LINUX_CAMERA        // Linux App 存取相機
OP_LINUX_MICROPHONE    // Linux App 存取麥克風
OP_LINUX_LOCATION      // Linux App 存取位置
OP_LINUX_CLIPBOARD     // Linux App 存取剪貼簿
OP_LINUX_CONTACTS      // Linux App 存取聯絡人
OP_LINUX_STORAGE       // Linux App 存取共享儲存
```

## 8.7 dumpsys 與診斷

```
$ adb shell dumpsys linux

Linux Manager Service Dump
===========================
State: RUNNING
Uptime: 2h 34m 12s
Guest CID: 42
Bridge connection: OK (latency: 3ms)

VM Resources:
  Memory: 1.2 GB / 2 GB
  CPU: 15.3%
  Disk: 8.4 GB / 20 GB

Active Sessions:
  Terminal: 2 sessions (session-001, session-002)
  Wayland: VS Code (pid 1234), GIMP (pid 5678)

Portal Status:
  Camera: NOT REQUESTED
  Microphone: ACTIVE (pid 1234, since 10:23:45)
  Location: PENDING USER APPROVAL

Recent Events:
  10:23:40 VM started successfully
  10:23:41 Bridge authenticated (HMAC-SHA256)
  10:23:42 Terminal session-001 created
  10:23:45 Microphone portal requested by pid 1234
  10:23:46 User granted microphone (single use)

Errors (last 24h):
  None
```

## 8.8 stable API 策略

由於這是平台修改而非 SDK API，版本策略為：

1. 第一版 API 不公開給第三方 App（signature perm）
2. 當 API 穩定後，考慮透過 `@SystemApi` 開放給特權 App
3. AIDL 版本號從 1 開始，每次不相容修改增加版本
4. Bridge 協議版本（見第 11 章）獨立管理，Host/Guest 必須版本相容

## 8.9 per-user 政策

```java
// per-user Linux policy（存儲在 UserManager）
public class LinuxUserPolicy {
    public boolean linuxEnabled = true;        // 管理員可禁用
    public long maxDiskBytes = 20L * GB;
    public long maxMemoryBytes = 2L * GB;
    public int maxCpuPercent = 50;
    public boolean allowPortPublishing = true;
    public boolean allowCameraPortal = true;
    public boolean allowMicPortal = true;
    public boolean allowLocationPortal = true;
    public boolean allowClipboard = false;     // 預設禁用
    public boolean allowUSB = false;           // 預設禁用
}
```
