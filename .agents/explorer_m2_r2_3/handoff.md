# Handoff Report — Milestone M2 Explorer 3 (Defect Investigation & Fix Design)

## 1. Observation

本報告針對 `guest/bridge-agent-m2` 模組中的 3 大核心缺陷（及連帶之 1 項致命 PTY 崩潰問題）進行深入源碼分析與修復設計說明。

### 系統與檔案位置
- **目標原始碼檔案**:
  - `guest/bridge-agent-m2/src/wayland.rs`
  - `guest/bridge-agent-m2/src/pty.rs`
  - `guest/bridge-agent-m2/src/vsock.rs`
  - `guest/bridge-agent-m2/src/main.rs`
- **現有測試狀態**:
  - `cargo check --manifest-path guest/bridge-agent-m2/Cargo.toml`
  - `cargo test --manifest-path guest/bridge-agent-m2/Cargo.toml`
  - 測試結果：18 個單元測試全數通過，但單元測試未包含實體雙向全雙工併發讀寫與 Shell 關閉測試。

---

### 缺陷觀察 1：Wayland 全雙工死鎖（Full-Duplex Deadlock）
- **檔案與行號**: `guest/bridge-agent-m2/src/wayland.rs` (第 50–99 行)
- **原始碼片段引用**:
  ```rust
  50: let s1 = Arc::new(Mutex::new(stream1));
  51: let s2 = Arc::new(Mutex::new(stream2));
  52: 
  53: let (s1_read, s2_write) = (Arc::clone(&s1), Arc::clone(&s2));
  54: let t1 = thread::spawn(move || {
  55:     let mut buf = [0u8; 8192];
  56:     loop {
  57:         let n = match s1_read.lock() {
  58:             Ok(mut r) => match r.read(&mut buf) {
  59:                 Ok(0) => break,
  60:                 Ok(n) => n,
  61:                 Err(_) => break,
  62:             },
  63:             Err(_) => break,
  64:         };
  ...
  ```
- **現場現象**: `s1_read.lock()` 產生的 `MutexGuard` 跨越了阻塞式的 `r.read(&mut buf)` 系統呼叫。在此期間，執行緒 `t1` 持有 `s1` 的 Mutex 鎖；反向執行緒 `t2`欲寫入 Wayland 顯示畫面封包至 `stream1` 時會調用 `s1_write.lock()`，因而無期限阻塞，造成 Wayland 畫面傳輸徹底死鎖。

---

### 缺陷觀察 2：無上限記憶體配置（Unbounded Memory Allocation OOM DoS）
- **檔案與行號**: `guest/bridge-agent-m2/src/pty.rs` (第 176–177 行)
- **原始碼片段引用**:
  ```rust
  176: let header = PtyHeader::parse(&header_buf);
  177: let mut payload = vec![0u8; header.payload_len as usize];
  ```
- **現場現象**: `header.payload_len` 直接取自網路接收之 4 位元組 Big-Endian 整數，完全未進行長度範圍檢查。當網路端傳入 `payload_len = 0xFFFFFFFF` (4 GB) 時，`vec![0u8; ...]` 會立即嘗試配置 4 GB 記憶體，引發 Linux OOM Killer 或 Rust panic 導致進程崩潰。

---

### 缺陷觀察 3：Socket FD 記憶體與檔案描述符洩漏（Socket FD Leak）
- **檔案與行號**: `guest/bridge-agent-m2/src/vsock.rs` (第 76–154 行)
- **原始碼片段引用**:
  ```rust
  76: pub enum VsockListener {
  77:     Vsock(libc::c_int, u32),
  78:     Tcp(TcpListener, u32),
  79: }
  ```
- **現場現象**: `VsockStream` 實作了 `Drop` 介面（調用 `libc::close(*fd)`），然而 `VsockListener` 卻**完全沒有**實作 `Drop`。在 `VsockListener::bind` 中透過 `libc::socket(AF_VSOCK, ...)` 建立的底層 raw socket fd 在 `VsockListener` 實例被釋放或開關時不會關閉，導致作業系統 File Descriptor 永久洩漏。

---

### 缺陷觀察 4（補充關鍵缺陷）：PTY 雙重/三重 FD 關閉導致 Rust 運行時致命崩潰
- **檔案與行號**: `guest/bridge-agent-m2/src/pty.rs` (第 111–114 行)
- **原始碼片段引用**:
  ```rust
  111: let mut cmd = Command::new(shell);
  112: cmd.stdin(unsafe { Stdio::from_raw_fd(slave_fd) })
  113:    .stdout(unsafe { Stdio::from_raw_fd(slave_fd) })
  114:    .stderr(unsafe { Stdio::from_raw_fd(slave_fd) });
  ```
- **現場現象**: 將同一個整數 `slave_fd` 傳遞給 `Stdio::from_raw_fd` 3 次，會在 Rust 1.63+ 運行時封裝出 3 個持有同一個整數 FD 的 `OwnedFd`。當 Command 及 Child 結束釋放時，該 FD 會被重複閉關（Close）三次，觸發 Rust 標準庫之 IO Safety 斷言，印出：
  `fatal runtime error: IO Safety violation: owned file descriptor already closed, aborting`
  並立即強行終止整個 `bridge-agent` 進程！

---

## 2. Logic Chain

1. **Wayland 死鎖推導**:
   - `proxy_bi_directional` 建立 `s1 = Arc::new(Mutex::new(stream1))` 與 `s2 = Arc::new(Mutex::new(stream2))`。
   - `t1` 進入 loop 後調用 `s1_read.lock()` 獲得 `MutexGuard`。`match` 匹配分支在 `MutexGuard` 尚未 drop 前便調用了 `r.read(&mut buf)`。
   - `r.read()` 為阻塞式 Socket 讀取。若 Client 端暫無資料寫入，`t1` 將持鎖停留在 `.read()` 呼叫中。
   - 同一時間，Wayland Compositor（`stream2`）產生新的 Display Event，`t2` 讀取後欲寫回 `stream1`，呼叫 `s1_write.lock()`。
   - 由於 `s1` 的鎖被 `t1` 持有且處於阻塞讀取狀態，`t2` 永遠無法取得鎖，Wayland 事件無法送達 Client，雙向全雙工代理發生死鎖。

2. **OOM 攻擊推導**:
   - 封包解析邏輯直接信任網路 Header 中的 32-bit `payload_len` 欄位。
   - 惡意 Client 或異常網路封包可送出大於實體記憶體之長度標示（例如 4GB）。
   - 系統執行 `vec![0u8; 4294967295]` 無條件記憶體分配，在記憶體受限環境下必定觸發 panic 或系統 OOM Killer，導致 bridge-agent 服務中斷。

3. **VsockListener 資源洩漏推導**:
   - 在 Linux 平台上，`VsockListener::bind` 使用原始 libc 系統呼叫 `libc::socket(AF_VSOCK, SOCK_STREAM, 0)` 建立 Listening FD。
   - 當 `VsockListener` 離開作用域（例如測試結束或服務重啟），因未實作 `Drop` 特性，作業系統核心中的 Socket FD 仍保持開啟狀態。
   - 隨著監聽器重啟或測試重複執行，FD 計數持續上升直至達到上限 `EMFILE`（Too many open files）。

4. **PTY 崩潰推導**:
   - `Stdio::from_raw_fd(slave_fd)` 擁有該 FD 的所有權（Ownership）。
   - 重複 3 次將同一個 FD 整數傳入，會建立 3 個獨立的 `OwnedFd` 結構。
   - 當這三個結構依序被 Drop 時，第 2 與第 3 次呼叫 `libc::close(slave_fd)` 會失敗（關閉已關閉之 FD），Rust stdlib IO Safety 機制檢測到無效關閉，主動調用 `std::process::abort()` 殺死進程。

---

## 3. Caveats

- **唯讀調查規範**: 本報告遵守 Explorer 角色規範，所有源碼分析與問題重現均以唯讀方式完成，未直接修改 `src/` 目錄下的產品代碼。
- **測試環境差異**: 單元測試（`cargo test`）預設使用非 Linux 下的 TCP Loopback 回退邏輯且未涵蓋併發死鎖與 Shell 關閉流程，因此單元測試顯示 18 passed 並不能代表併發安全性。修復設計必須經由強化的併發 stress test 驗證。

---

## 4. Conclusion & Precise Fix Designs

針對上述缺陷，Explorer 3 提出以下完整的代碼級修復設計方案（Fix Designs），供後續 Implementer 直接進行實作與重構。

### 修復設計 1：Wayland 無鎖串流拆分與全雙工代理 (`src/wayland.rs` & `src/vsock.rs`)

1. 在 `src/vsock.rs` 中為 `VsockStream` 實作 `try_clone()` 方法：
   ```rust
   impl VsockStream {
       pub fn try_clone(&self) -> io::Result<Self> {
           match self {
               VsockStream::Vsock(fd) => {
                   let new_fd = unsafe { libc::dup(*fd) };
                   if new_fd < 0 {
                       Err(io::Error::last_os_error())
                   } else {
                       Ok(VsockStream::Vsock(new_fd))
                   }
               }
               VsockStream::Tcp(s) => Ok(VsockStream::Tcp(s.try_clone()?)),
           }
       }
   }
   ```
2. 重構 `src/wayland.rs` 中的 `proxy_bi_directional` 與 `handle_wayland_proxy`：
   取消使用 `Arc<Mutex<Stream>>` 跨越阻塞讀取，改將 Stream 拆分為獨立的讀取端與寫入端，完全移除 Mutex 鎖鎖定：
   ```rust
   pub fn handle_wayland_proxy(vsock_stream: VsockStream) -> io::Result<()> {
       let wayland_path = get_wayland_socket_path();
       let unix_stream = UnixStream::connect(&wayland_path)?;

       let vsock_read = vsock_stream.try_clone()?;
       let vsock_write = vsock_stream;

       let unix_read = unix_stream.try_clone()?;
       let unix_write = unix_stream;

       proxy_split(vsock_read, unix_write, unix_read, vsock_write)
   }

   pub fn proxy_split<R1, W1, R2, W2>(
       mut r1: R1,
       mut w1: W1,
       mut r2: R2,
       mut w2: W2,
   ) -> io::Result<()>
   where
       R1: Read + Send + 'static,
       W1: Write + Send + 'static,
       R2: Read + Send + 'static,
       W2: Write + Send + 'static,
   {
       let t1 = thread::spawn(move || {
           let mut buf = [0u8; 8192];
           loop {
               match r1.read(&mut buf) {
                   Ok(0) => break,
                   Ok(n) => {
                       if w1.write_all(&buf[..n]).is_err() || w1.flush().is_err() {
                           break;
                       }
                   }
                   Err(_) => break,
               }
           }
       });

       let t2 = thread::spawn(move || {
           let mut buf = [0u8; 8192];
           loop {
               match r2.read(&mut buf) {
                   Ok(0) => break,
                   Ok(n) => {
                       if w2.write_all(&buf[..n]).is_err() || w2.flush().is_err() {
                           break;
                       }
                   }
                   Err(_) => break,
               }
           }
       });

       let _ = t1.join();
       let _ = t2.join();
       Ok(())
   }
   ```
   *好處*: `r1` 與 `w1` 完全解耦，任何阻塞式的 `.read()` 絕不會鎖定對向的 `.write()`，徹底解決死鎖問題。

---

### 修復設計 2：PTY 封包 Payload 長度上限與記憶體保護 (`src/pty.rs`)

1. 在 `src/pty.rs` 中定義最大 Payload 限制常數：
   ```rust
   pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB 上限
   ```
2. 在 `handle_pty_session` 解析 `header` 後增加長度安全檢查：
   ```rust
   let header = PtyHeader::parse(&header_buf);
   if header.payload_len as usize > MAX_PAYLOAD_SIZE {
       eprintln!(
           "[Bridge-Agent] PTY payload_len {} exceeds MAX_PAYLOAD_SIZE ({})",
           header.payload_len, MAX_PAYLOAD_SIZE
       );
       return Err(io::Error::new(
           io::ErrorKind::InvalidData,
           "PTY payload_len exceeds MAX_PAYLOAD_SIZE",
       ));
   }
   let mut payload = vec![0u8; header.payload_len as usize];
   ```

---

### 修復設計 3：`VsockListener` 的 `Drop` 特性實作 (`src/vsock.rs`)

在 `src/vsock.rs` 中為 `VsockListener` 補全 `Drop` 實作：
```rust
impl Drop for VsockListener {
    fn drop(&mut self) {
        if let VsockListener::Vsock(fd, _) = self {
            if *fd >= 0 {
                unsafe { libc::close(*fd); }
            }
        }
    }
}
```
同時可加入 `#[allow(dead_code)]` 或條件編譯註解，消除非 Linux 構建時的編譯器警告。

---

### 修復設計 4：安全複製 PTY Slave 檔案描述符 (`src/pty.rs`)

重構 `spawn_shell` 函式，利用 `File::try_clone()` 為 `stdin`、`stdout`、`stderr` 建立獨立且owned的檔案控制代碼，避免 `Stdio::from_raw_fd` 的重複 Close 崩潰：
```rust
pub fn spawn_shell(slave_path: &str) -> io::Result<std::process::Child> {
    let shell = if std::path::Path::new("/bin/bash").exists() {
        "/bin/bash"
    } else {
        "/bin/sh"
    };

    let slave_file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(slave_path)?;

    let f_out = slave_file.try_clone()?;
    let f_err = slave_file.try_clone()?;

    let mut cmd = Command::new(shell);
    cmd.stdin(Stdio::from(slave_file))
       .stdout(Stdio::from(f_out))
       .stderr(Stdio::from(f_err));

    cmd.spawn()
}
```

---

## 5. Verification Method

獨立驗證本修復設計的方法如下：

1. **單元與語法驗證**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo check --manifest-path guest/bridge-agent-m2/Cargo.toml
   cargo test --manifest-path guest/bridge-agent-m2/Cargo.toml
   ```

2. **Wayland 死鎖與併發測試**:
   使用 Python 建立長連線測試腳本：
   - 連線至 Port 5002。
   - 保持 Client 端不發送任何資料（使讀取端處於阻塞狀態）。
   - 由另一端寫入 Wayland 事件，確認 `proxy_split` 能夠無延遲且無阻塞地成功送出封包。

3. **PTY 巨量 Payload 限制測試**:
   - 構造 Header `[0..16] = session_id`, `16 = 0x01`, `17..21 = 0xFFFFFFFF`。
   - 傳送至 Port 5001。
   - 驗證日誌輸出 `PTY payload_len ... exceeds MAX_PAYLOAD_SIZE` 且連線安全中斷，記憶體配置未暴增。

4. **PTY Shell 關閉與崩潰測試**:
   - 連線至 Port 5001 完成握手後開啟 Shell 並立即關閉 Socket。
   - 驗證 bridge-agent 進程持續運行，未出現 `fatal runtime error: IO Safety violation` 崩潰。

5. **FD 洩漏驗證**:
   - 使用 `lsof -p <bridge_agent_pid>` 或 `/proc/<pid>/fd/` 檢查在無數次 `VsockListener::bind` 與 drop 後，未有殘留之 raw socket fd。
