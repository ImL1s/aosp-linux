# Handoff Report — Milestone M2 Iteration 2 (Production Guest Agent Loop - R2)

**Worker ID**: Worker 2 (`worker_m2_r2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_r2`  
**Target Delivery Path**: `guest/bridge-agent`  
**Date**: 2026-08-08  

---

## 1. Observation (觀察與調查數據)

經對 `guest/bridge-agent` 與專案修復任務進行實作與驗證，確認以下客觀事實：

### 1.1 Canonical Path 交付與目錄清理 (Task 1)
- **規範交付路徑**: `guest/bridge-agent/src/` (包含 `main.rs`, `auth.rs`, `vsock.rs`, `pty.rs`, `wayland.rs`, `portal.rs`) 與 `guest/bridge-agent/Cargo.toml`。
- **清理非標準產物**:
  - 已徹底移除次要目錄 `guest/bridge-agent-m2` 與軟連結 `guest/bridge-agent-link` (`test ! -d guest/bridge-agent-m2 && test ! -L guest/bridge-agent-link` 回傳 `CLEANUP VERIFIED: PASS`)。
  - 已清理死碼 `guest/bridge-agent/src/ota_rollback.rs` 及殘留檔 `guest/bridge-agent/Cargo.toml.new`。

### 1.2 PTY IO Safety Dup 關鍵修復 (Task 2)
- **問題源頭**: 原 `spawn_shell` 直接將 `slave_fd` 的原始整數無保護傳遞給 `Stdio::from_raw_fd(slave_fd)` 3 次，會在 Rust 1.63+ 運行時觸發 `fatal runtime error: IO Safety violation: owned file descriptor already closed, aborting` (SIGABRT -6)。
- **實作修復**: 於 `guest/bridge-agent/src/pty.rs` 中調用 `libc::dup(slave_fd)` 3 次產生 `stdin_fd`, `stdout_fd`, `stderr_fd` 三個獨立 owned 檔案描述符後傳入 `Stdio::from_raw_fd`。`slave_file` 離開作用域時僅關閉原 `slave_fd`， child stdio 分別獨立管理與關閉各自 FD。
- **讀取執行緒生命週期與 FD 回收防範**: 於 `handle_pty_session` 中透過 `libc::dup(master_write_fd)` 為背景 `reader_handle` 建立獨立的 `master_read_fd`。`PtyMaster` 釋放時關閉 `master_write_fd` 觸發 EOF/EIO，`reader_handle` 離開迴圈並主動關閉 `master_read_fd` 後結束，杜絕 FD 重複利用與 Race Condition。

### 1.3 Wayland 全雙工代理死鎖修復 (Task 3)
- **問題源頭**: 原 `proxy_bi_directional` 採用 `Arc<Mutex<Stream>>` 且持有鎖跨越阻塞式的 `.read()` 呼叫，導致對向寫入無法取得鎖而引發全雙工死鎖。
- **實作修復**: 在 `guest/bridge-agent/src/vsock.rs` 為 `VsockStream` 實作 `try_clone()`。在 `guest/bridge-agent/src/wayland.rs` 中使用 `vsock_stream.try_clone()?` 與 `unix_stream.try_clone()?` 拆分為獨立的 `r1, w1, r2, w2`，調用 `proxy_split` 進行雙向無鎖全雙工轉發。新增單元測試 `test_proxy_split_unix_stream_full_duplex` 驗證雙向雙工並發收發。

### 1.4 Unbounded Payload Size 記憶體安全限制 (Task 4)
- **實作修復**: 於 `pty.rs` 與 `portal.rs` 定義 `pub const MAX_PAYLOAD_SIZE: usize = 65536;` (64KB 上限)。在讀取或解析 payload 時進行檢查；若傳入長度超過限制，立即記錄錯誤並終止異常會話，防範 OOM 攻擊。

### 1.5 Socket FD 洩漏修復 (Task 5)
- **實作修復**: 在 `guest/bridge-agent/src/vsock.rs` 為 `VsockListener` 實作 `Drop` 特性：
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
  確保 `VsockListener` 離開作用域時底層 `AF_VSOCK` socket fd 被乾淨關閉，解決描述符洩漏問題。

### 1.6 認證強化與無條件 Abort (Task 6)
- **動態金鑰提取**: 在 `guest/bridge-agent/src/auth.rs` 中依序由 `LINUX_AUTH_SECRET` 環境變數、`/etc/linux_auth_secret` 檔案、`/proc/cmdline` (`linux_auth_secret=`) 提取金鑰。
- **嚴格驗證**: `verify_token` 無條件拒絕全零 Token、空 Token、空 Secret 或不匹配 Token；無任何硬編碼預設金鑰或 zero-token fallback。
- **失敗連線立即 Exit**: 在 `guest/bridge-agent/src/main.rs` 中，不論金鑰提取失敗、Listener 綁定失敗，或連線握手驗證失敗 (`!auth::perform_handshake(...)`)，均立即呼叫 `std::process::exit(1)` 終止進程。

---

## 2. Logic Chain (邏輯推理鏈)

1. **路徑規範性與 integrity 確保**:
   - `PROJECT.md` 與 `SCOPE.md` 規定交付標的為 `guest/bridge-agent/`。將完整的產線邏輯直接搬移與改進至 `guest/bridge-agent/src/`，並清理非標準臨時目錄，完全滿足專案規範。
2. **IO Safety 與系統穩定性推導**:
   - `Stdio::from_raw_fd` 取得 RawFd 所有權。若 3 次使用同一 raw FD，Rust Runtime 會在其 drop 時多次 close 該 FD。使用 3 次 `libc::dup` 為每個 `Stdio` 提供獨占描述符，從結構上消除 double close 的條件。
   - 主執行緒 `drop(pty)` 關閉 `master_write_fd`，由 kernel 傳遞 EOF/EIO 至 `master_read_fd`，背景 reader 執行緒退出讀取迴圈並自行 `libc::close(master_read_fd)`，確保無 FD 重複利用與執行緒殘留。
3. **Wayland 死鎖排除推導**:
   - 透過 `try_clone()` 將 Stream 的讀取與寫入控制代碼完全拆分，每個執行緒專職處理一個方向（R1 -> W1 或 R2 -> W2），完全不用 Mutex 鎖住 blocking read 系統呼叫，徹底斷開鎖競爭與死鎖條件。
4. **驗證與編譯品質推導**:
   - 在 `guest/bridge-agent` 目錄執行 `cargo check` 0 警告 0 錯誤。
   - 在 `guest/bridge-agent` 目錄執行 `cargo test` 通過全部 21 項單元測試。

---

## 3. Caveats (注意事項)

1. **Linux AF_VSOCK 執行環境**:
   - 單元測試在大眾作業系統 (macOS/x86 Linux without vsock kernel module) 上會自動啟用 `127.0.0.1` TCP loopback 回退邏輯進行測試；在真實 QEMU/crosvm Debian Guest ARM64 上將直接綁定真實 `AF_VSOCK` 核心 Socket (Ports 5000, 5001, 5002)。
2. **認證金鑰配置**:
   - 生產環境啟動 `bridge-agent` 前，系統需確保 `LINUX_AUTH_SECRET` 環境變數、`/etc/linux_auth_secret` 檔案，或 `/proc/cmdline` 之中至少有一處提供有效的非空金鑰。

---

## 4. Conclusion (結論)

Milestone M2 Iteration 2 (Production Guest Agent Loop - R2) 7 大修復任務已 100% 完成，所有程式碼均落實於標準交付路徑 `guest/bridge-agent`。
- 無硬編碼金鑰，無 zero-token fallback。
- PTY IO Safety dup 與 FD 存活期完全修復。
- Wayland 全雙工代理轉為無鎖 try_clone 轉發，死鎖問題徹底排除。
- 64KB MAX_PAYLOAD_SIZE 限制生效。
- VsockListener Drop 實現關閉。
- 認證與初始化失敗立即執行 `std::process::exit(1)`。
- `cargo check` 與 `cargo test` 於 `guest/bridge-agent` 通過 21 項測試（100% 通過率）。

---

## 5. Verification Method (獨立驗證指令與結果)

請執行以下 Shell 命令獨立驗證修復結果：

```bash
export PATH="$HOME/.cargo/bin:$PATH"

# 1. 驗證 Cargo 編譯無錯誤與無警告
cargo check --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml

# 2. 驗證 21 項單元測試 100% 通過
cargo test --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml

# 3. 驗證禁用的違規硬編碼金鑰與 Fallback 不存在
grep -rn "shared_secret_key_32bytes_long!!" /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/
# (回傳應為空)

# 4. 驗證非標準目錄已完全清除
test ! -d /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2 && echo "bridge-agent-m2 clean: PASS"
test ! -L /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-link && echo "bridge-agent-link clean: PASS"
```

### 實測結果紀錄:
- `cargo test` 輸出: `running 21 tests; test result: ok. 21 passed; 0 failed; 0 ignored; 0 measured`
- `test ! -d ...` 輸出: `CLEANUP VERIFIED: PASS`

---
**Report generated by worker_m2_r2**
