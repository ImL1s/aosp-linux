# Handoff Report — Reviewer 1 (Milestone M2 Iteration 2)

**Reviewer ID**: `reviewer_m2_r2_1`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_r2_1`  
**Target Reviewed Path**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent`  
**Date**: 2026-08-08  
**Verdict**: **APPROVE**

---

## 1. Observation (觀察與數據)

經對規範路徑 `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent` 進行獨立程式碼審查與測試驗證，記錄以下客觀數據：

### 1.1 Canonical Path 交付與目錄清理
- **規範路徑交付**: `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/` 包含 6 個核心 Rust 原始檔：`main.rs`, `auth.rs`, `vsock.rs`, `pty.rs`, `wayland.rs`, `portal.rs`。
- **次要與臨時目錄清理狀況**:
  - `guest/bridge-agent-m2`: 不存在。
  - `guest/bridge-agent-link`: 不存在。
  - `guest/bridge-agent/src/ota_rollback.rs`: 不存在。
  - `guest/bridge-agent/Cargo.toml.new`: 不存在。

### 1.2 PTY IO Safety (`libc::dup` 驗證)
- **檔案**: `guest/bridge-agent/src/pty.rs`
- **關鍵程式碼區段 (第 113-124 行)**:
  ```rust
  let stdin_fd = unsafe { libc::dup(slave_fd) };
  let stdout_fd = unsafe { libc::dup(slave_fd) };
  let stderr_fd = unsafe { libc::dup(slave_fd) };

  if stdin_fd < 0 || stdout_fd < 0 || stderr_fd < 0 {
      if stdin_fd >= 0 { unsafe { libc::close(stdin_fd); } }
      if stdout_fd >= 0 { unsafe { libc::close(stdout_fd); } }
      if stderr_fd >= 0 { unsafe { libc::close(stderr_fd); } }
      return Err(io::Error::last_os_error());
  }
  ```
- **第 127-129 行**:
  ```rust
  cmd.stdin(unsafe { Stdio::from_raw_fd(stdin_fd) })
     .stdout(unsafe { Stdio::from_raw_fd(stdout_fd) })
     .stderr(unsafe { Stdio::from_raw_fd(stderr_fd) });
  ```
- **第 148 行**:
  ```rust
  let master_read_fd = unsafe { libc::dup(master_write_fd) };
  ```
- **評價**: 為 `stdin`, `stdout`, `stderr` 分別使用 `libc::dup` 取得獨立 Owned File Descriptor，徹底消除 Rust IO Safety (RawFd 重複釋放) 的 crash / panic 隱患。

### 1.3 Wayland 全雙工代理與 `try_clone` 驗證
- **檔案**: `guest/bridge-agent/src/vsock.rs` (第 31-44 行) 與 `guest/bridge-agent/src/wayland.rs` (第 39-45 行, 第 48-93 行)
- **`VsockStream::try_clone` (vsock.rs:31-43)**:
  ```rust
  pub fn try_clone(&self) -> io::Result<Self> {
      match self {
          VsockStream::Vsock(fd) => {
              let dup_fd = unsafe { libc::dup(*fd) };
              if dup_fd < 0 { Err(io::Error::last_os_error()) }
              else { Ok(VsockStream::Vsock(dup_fd)) }
          }
          VsockStream::Tcp(s) => Ok(VsockStream::Tcp(s.try_clone()?)),
      }
  }
  ```
- **`proxy_split` 轉發 (wayland.rs:48-93)**: 使用獨立執行緒 `t1` 與 `t2` 分別處理 `r1 -> w1` 與 `r2 -> w2` 的全雙工轉發，無任何 `Mutex` 跨越 `read` 系統呼叫，徹底排除死鎖。
- **單元測試**: `wayland::tests::test_proxy_split_unix_stream_full_duplex` 實測通過。

### 1.4 Payload 長度上限限制 (`MAX_PAYLOAD_SIZE = 65536`)
- **PTY (pty.rs:12 & 207-213)**:
  ```rust
  pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB limit
  ...
  if header.payload_len as usize > MAX_PAYLOAD_SIZE {
      eprintln!("[PTY] Payload length {} exceeds MAX_PAYLOAD_SIZE ({})", header.payload_len, MAX_PAYLOAD_SIZE);
      break;
  }
  ```
- **Portal (portal.rs:5 & 119-128)**:
  ```rust
  pub const MAX_PAYLOAD_SIZE: usize = 65536; // 64 KB limit
  ...
  if bytes_read > MAX_PAYLOAD_SIZE {
      eprintln!("[Portal] Request line length {} exceeds MAX_PAYLOAD_SIZE ({})", bytes_read, MAX_PAYLOAD_SIZE);
      ...
      break;
  }
  ```

### 1.5 Socket FD Drop 實現
- **檔案**: `guest/bridge-agent/src/vsock.rs` (第 85-93 行, 第 177-185 行)
- **`VsockListener` Drop**:
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
- **`VsockStream` Drop**:
  ```rust
  impl Drop for VsockStream {
      fn drop(&mut self) {
          if let VsockStream::Vsock(fd) = self {
              if *fd >= 0 {
                  unsafe { libc::close(*fd); }
              }
          }
      }
  }
  ```

### 1.6 Cargo 編譯與測試驗證結果
- **`cargo check`**: 執行 `export PATH="$HOME/.cargo/bin:$PATH"; cargo check`，Exit Code: 0，無任何警告或錯誤。
- **`cargo test`**: 執行 `export PATH="$HOME/.cargo/bin:$PATH"; cargo test`，Exit Code: 0，21 個單元測試 100% 通過：
  ```
  running 21 tests
  test auth::tests::test_parse_secret_from_cmdline ... ok
  test auth::tests::test_perform_handshake_failure ... ok
  test auth::tests::test_perform_handshake_success ... ok
  test auth::tests::test_verify_token_all_zero_rejected ... ok
  test auth::tests::test_verify_token_empty_rejected ... ok
  test auth::tests::test_verify_token_mismatch_rejected ... ok
  test auth::tests::test_verify_token_valid ... ok
  test portal::tests::test_dispatch_audio_status ... ok
  test portal::tests::test_dispatch_camera_status ... ok
  test portal::tests::test_dispatch_file_read_and_read ... ok
  test portal::tests::test_dispatch_location_get ... ok
  test portal::tests::test_handle_portal_session_payload_size_limit ... ok
  test portal::tests::test_handle_portal_session_stream ... ok
  test pty::tests::test_pty_header_encode_parse ... ok
  test pty::tests::test_pty_master_open_and_slave_name ... ok
  test pty::tests::test_pty_payload_len_limit ... ok
  test pty::tests::test_pty_resize ... ok
  test vsock::tests::test_vsock_listener_bind_free_port ... ok
  test wayland::tests::test_get_wayland_socket_path_default ... ok
  test wayland::tests::test_proxy_bi_directional ... ok
  test wayland::tests::test_proxy_split_unix_stream_full_duplex ... ok

  test result: ok. 21 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
  ```

### 1.7 Integrity Violation 檢查
- **有無寫死測試結果或寫死密鑰**: 經檢查 `auth.rs` 中無任何寫死密鑰，驗證失敗時主動調用 `std::process::exit(1)`。
- **有無虛假的 Facade 實現**: PTY 使用真實 `libc::posix_openpt` 驅動，Wayland 使用 `UnixStream` 代理，Portal 實作了真實的 `fs::read_to_string` / `fs::write` / `fs::read_dir`。
- 結論：**無任何誠信違規 (Integrity Violation)**。

---

## 2. Logic Chain (邏輯推理鏈)

1. **交付路徑完整性與規範性**:
   - `guest/bridge-agent/src/` 包含全套 `main.rs`, `auth.rs`, `vsock.rs`, `pty.rs`, `wayland.rs`, `portal.rs`。次要與過時檔案均已清理乾淨。符合標準交付要求。
2. **IO Safety 安全推導**:
   - `Stdio::from_raw_fd` 擁有 RawFd 之控制權並會在 Drop 時釋放該 FD。使用 3 次 `libc::dup(slave_fd)` 為 `stdin`, `stdout`, `stderr` 分別建立獨立 FD，可防止 RawFd 被重複 Close 引發 SIGABRT / Double Drop Panic。
3. **Wayland 死鎖排除推導**:
   - 使用 `VsockStream::try_clone()` (內部執行 `libc::dup`) 與 `UnixStream::try_clone()` 將 Stream 拆分為獨立讀寫控制代碼，雙向轉發分別處於兩個無鎖 Thread (`proxy_split`) 中，完全消除 `Mutex` 對 Blocking Read 的持有，解決死鎖問題。
4. **記憶體與 FD 資源回收推導**:
   - 限制 `MAX_PAYLOAD_SIZE = 65536` 防止超大封包引發 OOM。`VsockListener` 與 `VsockStream` 實作 `Drop` 特性主動閉合 Socket FD，防止 FD 洩漏。
5. **獨立編譯與測試驗證**:
   - 實測 `cargo check` 與 `cargo test` 均為 0 錯誤且 21 項測試 100% 通過。

---

## 3. Caveats (注意事項)

- **非 Linux 環境下 vsock bind/accept 測試**: `vsock.rs` 在非 Linux 平台（如 macOS 審查環境）會彈性降級使用 `127.0.0.1` TCP 進行單元測試，在真實 Linux (KVM/crosvm) 環境則使用真正的 `AF_VSOCK` 核心 Socket (Ports 5000, 5001, 5002)。這屬於正常的跨平台相容設計。

---

## 4. Conclusion (結論)

審查結果為 **APPROVE**。

`guest/bridge-agent` 的程式碼實作品質優良，完成度 100%，全部 6 項驗證指標與誠信檢驗完全合規，推薦通過 Milestone M2 (Iteration 2) 之獨立審查。

---

## 5. Verification Method (獨立驗證方法)

執行以下指令驗證：

```bash
export PATH="$HOME/.cargo/bin:$PATH"
cd /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent

# 1. 驗證 cargo check
cargo check

# 2. 驗證 cargo test
cargo test

# 3. 驗證次要目錄已被清理
test ! -d /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2 && echo "PASS"
test ! -L /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-link && echo "PASS"
```

---
**Report submitted by `reviewer_m2_r2_1`**
