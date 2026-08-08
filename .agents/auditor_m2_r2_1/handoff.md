# Handoff Report — Forensic Integrity Audit (Milestone M2 Iteration 2)

**Auditor ID**: Forensic Auditor 1 (`auditor_m2_r2_1`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_r2_1`  
**Target Work Product**: `guest/bridge-agent` (`src/main.rs`, `src/auth.rs`, `src/vsock.rs`, `src/pty.rs`, `src/wayland.rs`, `src/portal.rs`)  
**Integrity Mode**: `development` (from `ORIGINAL_REQUEST.md`)  
**Date**: 2026-08-08  

---

## Forensic Audit Report

**Work Product**: `guest/bridge-agent`  
**Profile**: General Project  
**Verdict**: **CLEAN**  

### Phase Results
- **Canonical Path Delivery**: PASS — All active code resides strictly within `guest/bridge-agent/src/`.
- **Hardcoded Secret / Zero-Token Fallback Check**: PASS — No hardcoded secrets, no all-zero token fallbacks. Token authentication strictly rejects empty tokens, empty secrets, all-zero tokens, and length/byte mismatches. Auth failure triggers immediate `std::process::exit(1)`.
- **Facade / Dummy Stub Detection**: PASS — Genuine PTY (with `libc::dup` IO safety), Wayland un-locked proxying via `try_clone()`, bounded Portal RPC (64KB payload limit), and proper `AF_VSOCK` listener drops.
- **Secondary / Temporary Directory Cleanup**: PASS — `guest/bridge-agent-m2` and `guest/bridge-agent-link` are confirmed removed.
- **Empirical Build & Test Verification**: PASS — `cargo check` succeeds with 0 warnings/errors and `cargo test` passes all 21 unit tests genuinely.

---

## 1. Observation (獨立驗證數據與觀察紀錄)

1. **規範交付路徑驗證 (Canonical Path Verification)**:
   - 原始碼檔案位於 `guest/bridge-agent/src/`：`main.rs`, `auth.rs`, `vsock.rs`, `pty.rs`, `wayland.rs`, `portal.rs`。
   - 執行目錄清理檢查：
     `test ! -d guest/bridge-agent-m2 && test ! -L guest/bridge-agent-link && echo "CLEANUP_CHECK: PASS"`
     回傳結果為 `CLEANUP_CHECK: PASS`。

2. **資安與 Token 驗證機制 (Security & Token Verification)**:
   - 經搜尋 `guest/bridge-agent/src/` 原始碼，無任何預設硬編碼金鑰（如 `shared_secret_key_32bytes_long!!` 已徹底清理）。
   - `auth.rs` 中 `extract_auth_secret()` 按序從環境變數 `LINUX_AUTH_SECRET`、檔案 `/etc/linux_auth_secret` 與 `/proc/cmdline` 提取動態金鑰；若無任何來源則返回錯誤，不提供寫死的 fallback。
   - `verify_token()` 明確防範全零 Token、空 Token、空 Secret，採用常數時間位元比對：
     ```rust
     if token.iter().all(|&b| b == 0) { return false; }
     ```
   - `main.rs` 在握手驗證失敗或 Listener 綁定失敗時無條件呼叫 `std::process::exit(1)` 終止進程。

3. **核心邏輯真實性驗證 (Genuine Logic Verification)**:
   - `pty.rs` 使用 `libc::dup` 產生 3 個獨立的 stdio FD (`stdin_fd`, `stdout_fd`, `stderr_fd`) 傳遞給 `Stdio::from_raw_fd`，解決 Rust IO Safety double-close SIGABRT。背景讀取執行緒獨立處理 `master_read_fd`。 Payload 設有 `MAX_PAYLOAD_SIZE = 65536` 防範 OOM。
   - `wayland.rs` 實現 `proxy_split` 與 `VsockStream::try_clone()`，將讀寫頻道分離，達成全雙工無鎖轉發，杜絕 Mutex 跨越 blocking read 的死鎖問題。
   - `vsock.rs` 為 `VsockListener` 與 `VsockStream` 實作 `Drop` 特性以釋放 `libc::close(fd)`，防範 FD 洩漏。
   - `portal.rs` 實作真正的 `camera`, `audio`, `location`, `file.read`, `file.write`, `file.list` JSON RPC 處理器，並施加 64KB 請求長度上限。

4. **單元測試實測紀錄 (Empirical Test Execution)**:
   - 執行 `cargo check --manifest-path guest/bridge-agent/Cargo.toml`：0 warnings, 0 errors。
   - 執行 `cargo test --manifest-path guest/bridge-agent/Cargo.toml`：
     ```text
     running 21 tests
     test auth::tests::test_parse_secret_from_cmdline ... ok
     test auth::tests::test_perform_handshake_failure ... ok
     test auth::tests::test_perform_handshake_success ... ok
     test auth::tests::test_verify_token_all_zero_rejected ... ok
     test auth::tests::test_verify_token_empty_rejected ... ok
     test auth::tests::test_verify_token_valid ... ok
     test auth::tests::test_verify_token_mismatch_rejected ... ok
     test portal::tests::test_dispatch_audio_status ... ok
     test portal::tests::test_dispatch_camera_status ... ok
     test portal::tests::test_dispatch_location_get ... ok
     test portal::tests::test_dispatch_file_write_and_read ... ok
     test portal::tests::test_handle_portal_session_payload_size_limit ... ok
     test portal::tests::test_handle_portal_session_stream ... ok
     test pty::tests::test_pty_header_encode_parse ... ok
     test pty::tests::test_pty_payload_len_limit ... ok
     test wayland::tests::test_get_wayland_socket_path_default ... ok
     test vsock::tests::test_vsock_listener_bind_free_port ... ok
     test pty::tests::test_pty_master_open_and_slave_name ... ok
     test wayland::tests::test_proxy_bi_directional ... ok
     test pty::tests::test_pty_resize ... ok
     test wayland::tests::test_proxy_split_unix_stream_full_duplex ... ok

     test result: ok. 21 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
     ```

---

## 2. Logic Chain (邏輯推理鏈)

1. **路徑與清理規範 (Canonical Path & Directory Cleanup)**:
   - `PROJECT.md` 與 `SCOPE.md` 要求以 `guest/bridge-agent` 為唯一交付路徑。經檢測，臨時目錄 `guest/bridge-agent-m2` 及軟連結 `guest/bridge-agent-link` 已不復存在，代碼完全整合於 `guest/bridge-agent/src/`，滿足專案結構合規性。
2. **安全性與無模擬驗證 (Security & Genuine Execution)**:
   - 認證模組無寫死金鑰，無 zero-token 通過邏輯。動態提取與全零拒絕機制已獲單元測試覆蓋。
   - main 迴圈遇到認證失敗時執行 `std::process::exit(1)`，避免未授權 Session 繼續執行。
3. **系統穩定度與資源管理 (System Stability & Resource Safety)**:
   - `pty.rs` 之 `libc::dup` 機制確保每一個 RawFd 於 Rust Stdio 封裝中擁有獨立所有權，徹底修復 SIGABRT (double free/close) 風險。
   - `VsockListener::drop` 確保背景被觸發或監聽器被銷毀時 FD 正常關閉。
   - `proxy_split` 將全雙工讀寫拆分成獨立線程與 clone 流，解決 Blocking Read 導致的死鎖問題。
4. **綜合評定 (Overall Verdict Deduction)**:
   - 依據 `ORIGINAL_REQUEST.md` (Integrity mode: development) 規範與各階段 Forensic 檢查，無 Facade、無假測試、無硬編碼金鑰、無殘留暫存目錄，單元測試真實全數通過。評定結果為 **CLEAN**。

---

## 3. Caveats (注意事項)

1. **環境相容性說明**:
   - 單元測試在無 `AF_VSOCK` 驅動之 host 環境（如 macOS）上自動降級（fallback）至 127.0.0.1 TCP Socket 進行通訊測試，此為符合規範之本機測試機制。
   - 在 Debian Guest ARM64 實際運行環境下，將自動啟用 Linux 原生 `AF_VSOCK` syscall。

---

## 4. Conclusion (結論)

標的產物 `guest/bridge-agent` 經嚴格與獨立的法醫審計（Forensic Audit），完全滿足 Milestone M2 Iteration 2 (R2) 之各項規範與品質要求。
正式審計結論為：**CLEAN**。

---

## 5. Verification Method (獨立復現驗證指令)

為獨立驗證本 Hand-off 報告之結論，可於 workspace 根目錄執行以下命令：

```bash
export PATH="$HOME/.cargo/bin:$PATH"

# 1. 檢查目錄清理狀態
test ! -d guest/bridge-agent-m2 && test ! -L guest/bridge-agent-link && echo "CLEANUP: PASS"

# 2. 搜尋無硬編碼金鑰殘留
grep -rn "shared_secret_key_32bytes_long!!" guest/bridge-agent/src/ || echo "NO_HARDCODED_SECRET: PASS"

# 3. 執行 Cargo check 確保語法與編譯乾淨
cargo check --manifest-path guest/bridge-agent/Cargo.toml

# 4. 執行 Cargo test 驗證 21 項單元測試全數通過
cargo test --manifest-path guest/bridge-agent/Cargo.toml
```

---
**Report compiled by auditor_m2_r2_1**
