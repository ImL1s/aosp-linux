# 第十一章：Android / Linux Bridge

## 11.1 Bridge 架構總覽

```
Android Host                              Linux Guest VM
─────────────────────────────────────────────────────────────────
LinuxManagerService (system_server)       android-bridge-agent
    ↕ Binder                              systemd service
linux_bridge daemon (uid=linux_bridge)    
    ↕ AF_VSOCK Port 5000 (Control RPC)  ↔ android-bridge-agent
    ↕ AF_VSOCK Port 5001 (PTY stream)   ↔ pty-agent
    ↕ AF_VSOCK Port 5002 (Wayland)      ↔ wayland-agent
    ↕ AF_VSOCK Port 5003 (Portal)       ↔ xdg-portal-agent
    ↕ AF_VSOCK Port 5004 (Update)       ↔ update-agent
    ↕ virtio-fs (File Share)            ↔ /mnt/shared
    ↕ virtio-snd (Audio)                ↔ PulseAudio/PipeWire
    ↕ virtio-gpu (Display)              ↔ wayland-agent
```

**注意**：gRPC for Java 不支援 vsock（b/372666638），因此 Control RPC 層應使用：
- **選項 A**：自製 binary framing protocol over raw vsock（Rust 實作）
- **選項 B**：gRPC over vsock 使用 C++ gRPC 或 tonic (Rust)
- **決策**：選項 A（自製 binary framing），避免 gRPC dependency 問題，完全掌控協議

## 11.2 握手協議

```
4-Step HMAC-SHA256 Challenge-Response Handshake
─────────────────────────────────────────────────

Step 1: Host → Guest（Connection 建立後）
    MESSAGE: HELLO
    {
        version: u16,          // 協議版本
        timestamp: u64,        // Unix time ms
        nonce: [u8; 32],       // 密碼學隨機數
    }

Step 2: Guest → Host
    MESSAGE: CHALLENGE_RESPONSE
    {
        version: u16,
        hmac: [u8; 32],        // HMAC-SHA256(shared_key, nonce || timestamp)
        timestamp: u64,        // Guest 時間（防 replay）
        capabilities: u64,     // bitmask: TERMINAL|WAYLAND|AUDIO|PORTAL|UPDATE
    }

Step 3: Host 驗證
    - 計算預期 HMAC
    - 比較時間差（|host_ts - guest_ts| ≤ 30s）
    - constant_time_eq 比較 HMAC（防 timing attack）
    - 生成 session_token = HKDF-SHA256(shared_key, nonce, "session")

Step 4: Host → Guest
    MESSAGE: SESSION_ESTABLISHED
    {
        session_token: [u8; 32],  // 此後每個 RPC 必須帶 token
        session_id: u64,
    }

Shared key 來源：
    - VM 啟動時由 LinuxManagerService 生成 32 bytes 隨機 key
    - 透過 IVmPayloadService.getVmInstanceSecret() 注入 Guest
    - Guest android-bridge-agent 啟動時從 /run/android_secret 讀取
    - 注入後立即 zeroize 原始 key（使用 Rust zeroize crate）
```

## 11.3 Control RPC 協議格式

```rust
// RPC 封包格式（binary framing over vsock）
struct RpcHeader {
    magic: [u8; 4],        // b"ABRF" (Android Bridge RPC Frame)
    version: u16,          // 目前 = 1
    request_id: u64,       // 唯一 request ID（monotonic）
    message_type: u16,     // MessageType enum
    payload_len: u32,      // payload 長度（max 4MB）
    session_token: [u8; 32], // 每個 request 必須包含
    checksum: u32,         // CRC32 of header
}

struct RpcPayload {
    // protobuf-encoded payload（根據 message_type）
}

enum MessageType {
    // Control
    PING = 1,
    PONG = 2,
    STATUS_REQUEST = 10,
    STATUS_RESPONSE = 11,
    
    // VM Control
    SHUTDOWN_REQUEST = 20,
    SUSPEND_PREPARE = 21,
    RESUME_NOTIFY = 22,
    
    // Terminal
    PTY_CREATE = 30,
    PTY_CLOSE = 31,
    PTY_RESIZE = 32,
    PTY_LIST = 33,
    
    // Apps
    APP_LAUNCH = 40,
    APP_STOP = 41,
    APP_LIST_REQUEST = 42,
    APP_LIST_RESPONSE = 43,
    APP_INSTALLED_NOTIFY = 44,
    APP_UNINSTALLED_NOTIFY = 45,
    
    // Portal
    CAMERA_REQUEST = 60,
    MIC_REQUEST = 61,
    LOCATION_REQUEST = 62,
    PORTAL_FRAME_DATA = 63,
    PORTAL_STOP = 64,
    
    // Update
    UPDATE_AVAILABLE = 80,
    UPDATE_STAGE = 81,
    UPDATE_APPLY = 82,
    UPDATE_ROLLBACK = 83,
    UPDATE_PROGRESS = 84,
    
    // Errors
    ERROR = 255,
}
```

## 11.4 安全保護措施

| 保護 | 實作方式 |
|------|---------|
| 身份驗證 | HMAC-SHA256 challenge-response（每次 VM 啟動重新驗證）|
| Session management | 每個請求帶 session_token，server 驗證 |
| Replay protection | nonce + timestamp（±30s 容忍）|
| Timing attack | constant_time_eq 比較所有 secret |
| Secret 清除 | zeroize crate 確保 key 不殘留記憶體 |
| Rate limiting | 每秒最多 1000 個 RPC requests |
| Payload limit | 最大 4MB per request |
| Backpressure | 佇列上限 64 pending requests |
| Audit log | 所有 Portal 請求記錄（不含資料內容）|

## 11.5 Protocol Versioning

```
版本協商：
1. HELLO 訊息帶 version 欄位（目前 = 1）
2. Guest 回應支援的 version 範圍（min_version, max_version）
3. 雙方取交集，使用最高共同版本
4. 若無交集 → CONNECTION_VERSION_MISMATCH 錯誤，拒絕連接

Compatibility Matrix（HOST version, GUEST version）：
Host 1.0 可連接 Guest 1.0
Host 1.1 可連接 Guest 1.0, 1.1
Host 2.0 不可連接 Guest 1.x（major version break）

→ 更新 Host AOSP 時，確保 Guest 版本仍相容
→ Guest image OTA 必須在 Host 升級前完成
```

---

# 第十二章：原生觸控 Terminal 完整設計

## 12.1 從現有 AOSP Terminal 遷移策略

**現狀**（已驗證）：
- `TerminalView.kt` 是 WebView subclass
- 使用 ttyd 作為 HTTP terminal server（xterm.js）
- 透過 NSD/mDNS 發現，TCP 連接（非 vsock）
- IP-based auth（已知安全問題）
- CJK IME 控制受限（WebView 內的 textarea）

**遷移計劃**：

```
Phase 1（Milestone 1）：修復現有問題
  - 替換 IP auth 為 vsock + HMAC（解決 b/372666638）
  - 保留 WebView/ttyd 架構
  - 修復 vsock 連接問題

Phase 2（Milestone 3）：原生 Terminal 取代
  - 新建 LinuxTerminal App（packages/apps/LinuxTerminal/）
  - 原生 Surface Canvas 渲染
  - libvterm 整合（JNI）
  - 自訂 InputConnection（CJK IME 完整支援）
  - vsock PTY protocol

Phase 3（Milestone 4+）：功能完整化
  - Wayland 整合（LinuxAppProxyActivity）
  - 多 session、分割視窗
  - 進階觸控模式
```

## 12.2 Terminal UI 架構

```
TerminalActivity (Android Activity)
├── TerminalTabBar (多分頁 UI)
│   └── TabAdapter
└── TerminalView (自訂 View, NOT WebView)
    ├── TerminalSurface (SurfaceHolder/Canvas rendering)
    ├── TerminalRenderer (繪製邏輯)
    │   ├── libvterm (JNI) - VT 解析引擎
    │   ├── GlyphRenderer - 字型渲染
    │   ├── CursorRenderer - 游標動畫
    │   └── SelectionRenderer - 文字選取
    ├── TerminalInputConnection (IME 整合)
    │   ├── CjkComposer - 注音/倉頡/拼音組字
    │   └── KeyEventTranslator - 鍵盤事件轉 ANSI
    ├── TouchModeController - 觸控模式狀態機
    └── VsockPtyClient - vsock 連接管理
```

## 12.3 Terminal Renderer 設計

```java
// TerminalRenderer - 核心渲染類
class TerminalRenderer {
    // libvterm JNI bridge
    private long vtermPtr;          // native VTerm* pointer
    private TerminalBuffer buffer;  // screen buffer (rows×cols)
    
    // 渲染組件
    private GlyphCache glyphCache;  // 字型字形緩存
    private ColorTable colors;      // 256色 + truecolor
    
    void onData(byte[] data) {
        // JNI: vterm_input_write(vtermPtr, data, data.length)
        // vtermPtr 解析 ANSI sequences
        // 更新 TerminalBuffer（dirty flags）
        nativeInputWrite(vtermPtr, data);
    }
    
    void draw(Canvas canvas) {
        // 只重繪 dirty cells（效能優化）
        for each dirty cell in buffer:
            drawGlyph(canvas, cell)
            clearDirty(cell)
        drawCursor(canvas)
    }
    
    // Unicode 策略
    // - East Asian Width (EAW) lookup table for wide chars
    // - Combining characters: compose with base cell
    // - Emoji: multi-codepoint detection, single wide cell
    // - Fallback font chaining for CJK/emoji
}
```

## 12.4 IME 設計（TerminalInputConnection）

Terminal 的 IME 整合是整個 Terminal 最複雜的部分。問題：

1. Terminal 不是普通文字輸入框
2. 字符發送時序必須符合 terminal semantics（一個字符，一個 byte stream）
3. 組字期間（composition）字符不能發送到 PTY
4. 確定後才轉為 byte stream 發送

```java
class TerminalInputConnection extends BaseInputConnection {
    // IME 組字狀態
    private SpannableStringBuilder composingText = new SpannableStringBuilder();
    private boolean inComposition = false;
    
    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        // 更新組字預覽（顯示在 Terminal 中的懸浮視窗）
        composingText.clear();
        composingText.append(text);
        inComposition = (text.length() > 0);
        
        // 不發送到 PTY（還在組字中）
        // 更新 Terminal overlay（組字預覽）
        terminalView.showCompositionWindow(text);
        return true;
    }
    
    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        // 組字完成，轉為 UTF-8 byte stream 發送到 PTY
        inComposition = false;
        terminalView.hideCompositionWindow();
        
        byte[] utf8Bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        vsockPtyClient.send(utf8Bytes);
        return true;
    }
    
    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Modifier keys: Ctrl, Alt, Shift → ANSI sequences
            byte[] ansiSequence = keyEventTranslator.translate(event);
            if (ansiSequence != null) {
                vsockPtyClient.send(ansiSequence);
                return true;
            }
        }
        return super.sendKeyEvent(event);
    }
    
    // Secure input mode：禁止 IME 學習
    @Override
    public EditorInfo getEditorInfo() {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_NULL;  // 禁止 autocomplete
        if (secureInputMode) {
            info.inputType |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }
        return info;
    }
}
```

## 12.5 觸控模式狀態機

```
三種觸控模式：

Mode 1: Shell Mode (預設)
────────────────────────
狀態：SHELL_MODE
觸控行為：
- 單指滑動 → scrollback
- 長按（500ms）→ 進入文字選取模式
- 短按（點擊）→ 取得輸入焦點 / 叫出 IME
- 雙指捏合 → 字型縮放
- 方向鍵 → 游標移動（讓 shell 移動，不是 scrollback）
IME：完整支援（組字預覽）

Mode 2: TUI Mouse Mode（自動偵測或手動切換）
────────────────────────────────────────────
觸發：偵測到 DECSET 1000/1006（SGR mouse mode）
狀態：TUI_MOUSE_MODE
觸控行為：
- 觸碰 → 計算 terminal 座標 (col, row)
- 生成 SGR 格式 mouse event：\e[<0;{col};{row}M（按下）/ \e[<0;{col};{row}m（釋放）
- 滑動 → 滑鼠滾輪事件：\e[<65;{col};{row}M（向下）/ \e[<64;{col};{row}M（向上）
- 雙指 → 滾輪
- 點擊類型：left=0, middle=1, right=2
適用：Vim, Neovim, tmux, htop, btop, MC, lazygit, ranger
衝突：選取文字時暫時切換到 Shell Mode

Mode 3: Touchpad Mode（手動切換）
──────────────────────────────────
狀態：TOUCHPAD_MODE
觸控行為：
- 單指移動 → 相對滑鼠游標移動（精確度可調）
- 單擊 → 滑鼠左鍵
- 雙指點擊 → 滑鼠右鍵
- 雙指滑動 → 滾輪
- 長按拖曳 → 拖拽
用途：Linux GUI App 的精確滑鼠控制

切換方式：
- 快捷列按鈕手動切換
- TUI Mouse Mode：自動偵測（解析 DECSET 1000/1006）
- 長按快捷列 Mouse 按鈕 → 顯示模式選單
```

## 12.6 Modifier Key 快捷列

Terminal 上方固定顯示快捷列，包含：

```
[ Ctrl ][ Alt ][ Shift ][ Esc ][ Tab ][ ← ][ → ][ ↑ ][ ↓ ][ PgUp ][ PgDn ]

第二列（可展開）：
[ Home ][ End ][ Ins ][ Del ][ F1-F12 ][ ⌃C ][ ⌃D ][ ⌃Z ][ ⌃L ][ Paste ][ Clear ]
```

各按鍵對應 ANSI 序列：

```java
static final Map<Integer, byte[]> MODIFIER_KEY_SEQUENCES = new HashMap<>();
static {
    MODIFIER_KEY_SEQUENCES.put(R.id.key_ctrl_c, new byte[]{0x03});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_ctrl_d, new byte[]{0x04});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_ctrl_z, new byte[]{0x1A});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_ctrl_l, new byte[]{0x0C});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_esc, new byte[]{0x1B});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_tab, new byte[]{0x09});
    MODIFIER_KEY_SEQUENCES.put(R.id.key_arrow_up, new byte[]{0x1B, 0x5B, 0x41});     // ESC[A
    MODIFIER_KEY_SEQUENCES.put(R.id.key_arrow_down, new byte[]{0x1B, 0x5B, 0x42});   // ESC[B
    MODIFIER_KEY_SEQUENCES.put(R.id.key_arrow_right, new byte[]{0x1B, 0x5B, 0x43});  // ESC[C
    MODIFIER_KEY_SEQUENCES.put(R.id.key_arrow_left, new byte[]{0x1B, 0x5B, 0x44});   // ESC[D
    MODIFIER_KEY_SEQUENCES.put(R.id.key_home, new byte[]{0x1B, 0x5B, 0x48});         // ESC[H
    MODIFIER_KEY_SEQUENCES.put(R.id.key_end, new byte[]{0x1B, 0x5B, 0x46});          // ESC[F
    MODIFIER_KEY_SEQUENCES.put(R.id.key_page_up, new byte[]{0x1B, 0x5B, 0x35, 0x7E}); // ESC[5~
    MODIFIER_KEY_SEQUENCES.put(R.id.key_page_down, new byte[]{0x1B, 0x5B, 0x36, 0x7E}); // ESC[6~
    MODIFIER_KEY_SEQUENCES.put(R.id.key_del, new byte[]{0x1B, 0x5B, 0x33, 0x7E});    // ESC[3~
    // F1-F12...
}
```

## 12.7 安全模型

```
Secure Input Mode（密碼輸入時啟用）：
- 偵測 stty -echo 或特定 PS1 pattern
- 設定 EditorInfo.inputType |= TYPE_TEXT_VARIATION_PASSWORD
- FLAG_SECURE（可選，由使用者設定）
- 禁止 autofill
- 禁止 clipboard preview
- 禁止 IME 記憶（TYPE_TEXT_FLAG_NO_SUGGESTIONS 始終設定）

Terminal Escape Injection 防護：
- OSC 52（clipboard）：需要使用者授權
- OSC 7（working directory）：僅更新 title，不執行任何 action
- URL/hyperlink（OSC 8）：僅顯示，點擊前確認
- TITLE 修改（OSC 2）：允許，但顯示來源標識
- 禁止 xterm `allow window title to set icon name`

Bracketed Paste：
- 預設啟用
- 大量貼上（>500 bytes）顯示確認對話框
- 顯示貼上內容前 3 行預覽

ANSI Parser Fuzzing：
- libvterm 本身有 fuzzing 測試（upstream）
- 本專案額外 fuzzing：畸形 escape sequence 不應 crash
```

## 12.8 性能目標

| 指標 | 目標 |
|------|------|
| 觸控輸入到 PTY | ≤ 50ms |
| PTY 輸出到螢幕繪製 | ≤ 16ms（60fps）|
| 字型渲染（cold） | ≤ 100ms |
| 字型渲染（cached） | ≤ 1ms per char |
| IME 顯示 | ≤ 100ms |
| 組字確定到 PTY | ≤ 20ms |
| Activity recreation（旋轉）| PTY session 不中斷 |
| VM suspend/resume | Terminal 自動重連 |

## 12.9 測試矩陣

| 測試項 | 測試方法 |
|--------|---------|
| 注音輸入 | UI 自動化：輸入「ㄓㄨㄥ」確認「中」|
| 倉頡輸入 | UI 自動化：各倉頡碼確認字符 |
| Vim 滑鼠 | Instrumentation：模擬點擊確認 `\e[<0;X;YM` |
| tmux 滑鼠 | Instrumentation：確認 tmux 接收 mouse event |
| htop | UI：模擬觸碰確認 htop 游標移動 |
| UTF-8 寬字符 | 輸入「日本語」確認 2-cell 顯示 |
| emoji | 輸入「😀」確認不崩潰 |
| 旋轉不斷線 | 旋轉裝置確認 PTY session 保持 |
| suspend/resume | VM suspend 後確認 Terminal 自動重連 |
| 大量貼上警告 | 貼上 >500 bytes 確認對話框顯示 |
| secure input | 執行 `sudo` 確認 secure mode 啟用 |
