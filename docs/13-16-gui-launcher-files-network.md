# 第十三章：Linux GUI 與 WindowManager 整合

## 13.1 GUI 轉發架構

**不可接受的最終方案**：全螢幕 VNC

**目標**：每個 Linux GUI App 顯示為獨立 Android Task，支援 Recents、分割畫面、自由視窗。

```
Linux App (Wayland client)
    ↓ Wayland protocol (socket in /run/wayland-0)
Guest wayland-agent (Sommelier-like compositor)
    ↓ surface created notification (vsock Port 5002)
Host LinuxWindowBridgeService
    ↓ creates Task + SurfaceControl
LinuxAppProxyActivity (Android Activity)
    ↓ renders to SurfaceControl
Android WindowManager / SurfaceFlinger
    ↓ displayed to user

Buffer 傳輸路徑（virtio-gpu）：
Linux App → GBM/DRI → virtio-gpu (Mesa) → Host GPU memory
Host LinuxWindowBridgeService → dma-buf → SurfaceControl
→ SurfaceFlinger compositing → display
```

## 13.2 技術選型

| 元件 | 選擇 | 理由 |
|------|------|------|
| Guest compositor | Sommelier-style (custom) | Sommelier 是 ChromeOS Crostini 驗證架構，適合 VM 場景 |
| GPU 渲染 | virglrenderer（OpenGL）→ gfxstream（Vulkan）| virglrenderer 更成熟；gfxstream 效能更好但仍 EXPERIMENTAL |
| XWayland | 啟用（Guest 內部）| 支援不支援 Wayland 的 X11 應用 |
| Window mapping | per-surface → per Android Task | 每個 window 獨立 Task，支援 Recents |
| Buffer sharing | dma-buf import（Android → Guest via virtio-gpu）| 零拷貝，最低延遲 |
| Frame pacing | Host-side VSync 同步 | Guest 不知道 Host VSync，由 Host 控制 |

## 13.3 App Mode 實作

```java
// LinuxAppProxyActivity
// 每個 Linux GUI App window 建立一個此 Activity

class LinuxAppProxyActivity extends Activity {
    private SurfaceHolder surfaceHolder;
    private long waylandSurfaceId;  // 對應 Guest wayland surface
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        waylandSurfaceId = getIntent().getLongExtra("wayland_surface_id", -1);
        
        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // 通知 LinuxWindowBridgeService：Surface 就緒
                // 開始接收 virtio-gpu buffer
                linuxWindowBridge.attachSurface(waylandSurfaceId, holder.getSurface());
            }
        });
        setContentView(surfaceView);
        
        // 設定視窗標題（從 Guest wayland app_id 取得）
        setTitle(linuxWindowBridge.getWindowTitle(waylandSurfaceId));
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 轉發鍵盤事件到 Guest
        linuxWindowBridge.forwardKeyEvent(waylandSurfaceId, event);
        return true;
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // 轉換觸碰座標，轉發到 Guest
        linuxWindowBridge.forwardTouchEvent(waylandSurfaceId, event);
        return true;
    }
}
```

## 13.4 Desktop Mode

```
Desktop Mode 啟用條件：
- 外接螢幕 + 鍵盤 + 滑鼠
- 平板大螢幕 + 實體鍵盤
- 使用者手動開啟

Desktop Mode 行為：
- 外接螢幕顯示完整 Linux Wayland Desktop（Weston 或 Mutter）
- Android 螢幕作為輸入觸控板
- 支援多視窗
- 全螢幕 Linux Desktop（獨立 display）

實作：
- virtio-gpu 第二個 virtual display
- LinuxWindowBridgeService 掛載第二個 Display
- Guest wayland-agent 輸出到第二個 virtual display
- Android 觸碰 → 轉換為 touchpad 事件 → 轉發到 Guest
```

## 13.5 多視窗支援

```
Android Task 映射規則：

1. 每個 Wayland toplevel surface → 一個 LinuxAppProxyActivity instance
2. Activity 帶有 taskAffinity（確保 Linux App 不合併到錯誤 task stack）
3. app_id 作為 activity 識別（e.g., "code" → VS Code）
4. 關閉 Linux App → 對應 Activity.finish() + Task 移除
5. Guest crash → 所有對應 Activity 強制關閉

Recents 整合：
- LinuxAppProxyActivity 有自己的 Task
- 顯示 Linux App icon（從 .desktop file 取得）
- 顯示 Linux App 最後一幀截圖（Recents thumbnail）
- 點擊 Recents 中的 Linux App → resume
```

---

# 第十四章：Launcher 與 Linux App 整合

## 14.1 設計決策：不製造假 APK

**放棄**：動態 synthetic package（每個 Linux App 假裝成 APK）

理由：
- 破壞 PackageManager invariant（APP 不存在於 /data/app/）
- APK 生命週期管理複雜（install/uninstall 事件不真實）
- CTS PackageManager 測試可能失敗

**選擇**：LinuxAppRegistryService + Launcher Shortcut + AppProxy Activity

```
LinuxAppRegistryService 職責：
1. 監聽 Guest .desktop file 變更（透過 vsock notify）
2. 解析 .desktop file metadata（名稱、圖示、Exec、MIME）
3. 為每個 Linux App 建立 ShortcutInfo（android.content.pm.ShortcutManager）
4. 同步 icon 到 Host（透過 virtiofs）
5. 搜尋整合（Launcher3 搜尋可搜到 Linux App）

Launcher 看到的：
- 一般 Android App（APK）→ 真實 App
- Linux Terminal → priv-app shortcut
- VS Code → dynamic shortcut (pinned, not APK)
- GIMP → dynamic shortcut
- LibreOffice → dynamic shortcut
```

## 14.2 .desktop 解析安全性

```java
// DesktopFileParser - 安全解析 .desktop 文件
class DesktopFileParser {
    // 允許的 Exec 命令格式（sanitized）
    // 禁止：相對路徑、shell 運算符、環境變數注入
    
    static LinuxAppInfo parse(String content) {
        LinuxAppInfo info = new LinuxAppInfo();
        
        for (String line : content.split("\n")) {
            if (line.startsWith("Name=")) {
                // 限制長度，過濾 HTML/特殊字符
                info.displayName = sanitizeName(line.substring(5), 100);
            }
            if (line.startsWith("Exec=")) {
                // 僅允許 /usr/bin/ 或 /usr/local/bin/ 絕對路徑
                // 禁止 ;, &&, ||, `backticks`, $()
                info.execCommand = sanitizeExec(line.substring(5));
            }
            if (line.startsWith("Icon=")) {
                // 只接受 icon 名稱，不接受路徑
                info.iconName = sanitizeIconName(line.substring(5));
            }
            // ...
        }
        
        return info;
    }
}
```

## 14.3 Launcher3 修改

```java
// Launcher3 需要修改以支援 Linux App
// 路徑：packages/apps/Launcher3/src/com/android/launcher3/linux/

class LinuxAppTracker {
    // 監聽 LinuxAppRegistryService 的 app 變更
    // 向 Launcher 注入 Linux App shortcuts
    
    void onLinuxAppInstalled(LinuxAppInfo app) {
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, app.desktopFileId)
            .setShortLabel(app.displayName)
            .setIcon(loadLinuxAppIcon(app.iconName))
            .setIntent(new Intent(ACTION_LAUNCH_LINUX_APP).putExtra("app_id", app.desktopFileId))
            .build();
        
        shortcutManager.pushDynamicShortcuts(List.of(shortcut));
    }
    
    void onLinuxAppUninstalled(String desktopFileId) {
        shortcutManager.removeDynamicShortcuts(List.of(desktopFileId));
    }
}
```

---

# 第十五章：檔案系統與共享檔案

## 15.1 設計原則

1. Android `/data` 絕對不可直接完整掛載進 Guest
2. 共享通過 SAF（Storage Access Framework）或 virtiofs 受控目錄
3. Guest root 不能直接讀取 Android App 私有資料

## 15.2 virtiofs 共享設定

```
Host 側（virtiofs source）：
    /data/media/0/LinuxShared/     （外部儲存，使用者可見）
    只掛載此目錄，NOT /data/data/

Guest 側（virtiofs mount）：
    /mnt/shared → virtiofs tag "android_shared"
    
crosvm 設定：
    --shared-dir /data/media/0/LinuxShared:android_shared:type=fs
```

## 15.3 DocumentsProvider（LinuxStorageProvider）

```java
// 讓 Android Files App 可以瀏覽 Linux home
class LinuxStorageProvider extends DocumentsProvider {
    // 透過 LinuxBridgeService，呼叫 Guest 的 virtiofs 路徑
    
    @Override
    public Cursor queryRoots(String[] projection) {
        // 顯示 "Linux Files" 根目錄
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        if (linuxManager.getState() == RUNNING) {
            result.newRow()
                .add(Root.COLUMN_ROOT_ID, "linux_home")
                .add(Root.COLUMN_TITLE, "Linux Files")
                .add(Root.COLUMN_ICON, R.drawable.ic_linux)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY);
        }
        return result;
    }
    
    @Override
    public Cursor queryChildDocuments(String parentDocumentId, ...) {
        // 透過 Bridge RPC 獲取 Guest 目錄列表
        return linuxBridge.listDirectory(parentDocumentId);
    }
}
```

## 15.4 使用者看到的統一介面

```
Files App
├── Internal Storage
│   ├── Downloads
│   ├── Documents
│   ├── Pictures
│   └── Android (/.../Android/)
└── Linux Files                    ← LinuxStorageProvider
    ├── home/user/
    │   ├── Documents/
    │   ├── Downloads/
    │   ├── Projects/
    │   └── .config/
    └── [Shared with Android]      ← /mnt/shared
```

---

# 第十六章：網路架構

## 16.1 Guest 網路設計

```
Guest 使用 NAT 模式（crosvm virtio-net + TAP + Android netd）

流量路徑：
Linux App → Guest eth0 → crosvm TAP → Android netd NAT → 實體網路
                                              ↑
                              遵守 Android VPN / Private DNS / 企業政策
```

## 16.2 VPN 流量遵守規則

這是安全的關鍵要求：**Linux Guest 不能成為繞過 Android VPN 的通道**

```
實作方式：
1. crosvm TAP interface 歸入特定 UID（crosvm UID）
2. Android NetworkPolicyManager 對 crosvm UID 套用相同 VPN policy
3. Always-on VPN：crosvm UID 的流量也必須走 VPN tunnel
4. lockdown VPN mode：VPN 斷開時，crosvm 流量也 block

DNS：
- Guest DNS 指向 Host resolver（10.0.2.3 或 virtio-net host gateway）
- Host resolver 遵守 Android Private DNS 設定
- Private DNS（DoT/DoH）：Guest 透過 Host resolver 自動獲得

Metered 網路：
- NetworkPolicyManager.isNetworkMetered() → true 時
- 通知 Guest via Bridge RPC
- Guest 可設定 apt 不在計費網路更新
- 使用者可以在 Settings 覆寫

Captive Portal：
- Guest 透過 Host NAT，captive portal 由 Android 處理
- 不另外在 Guest 實作 captive portal 偵測
```

## 16.3 Port Publishing（對外暴露 Guest 服務）

```
使用場景：在 Linux Guest 執行 Web Server，從同 Wi-Fi 其他設備訪問

API：
linuxManager.publishPort(guestPort=8080, hostPort=8080, label="Dev Server")

實作：
- Android iptables NAT 規則：DNAT 到 Guest IP:Port
- 顯示通知："Linux: Port 8080 published to network"
- Settings 中列出所有 published ports
- 外部連接僅允許來自 LAN（預設，可設定）

安全考量：
- 不允許 published port 0-1024（系統 port）
- 每個 port publication 需要 MANAGE_LINUX_ENVIRONMENT 權限
- 預設不 publish 任何 port
```

## 16.4 Doze 與背景資料政策

```
Doze 模式下：
- VM suspend（預設）：完全暫停，無網路
- 背景服務模式（若使用者設定）：
  - 允許繼續運行（需 FOREGROUND_SERVICE_SPECIAL_USE）
  - 但網路受 Doze 限制
  - 顯示永久通知（"Linux: Background services running"）

App Standby：
- crosvm UID 屬於 ACTIVE 或 WORKING_SET bucket
- 不受 App Standby 嚴格限制（因為有前台通知）
```
