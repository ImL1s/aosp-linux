# Handoff Report — Milestone 3 Challenger 1 (challenger_m3_1)

**VERDICT: APPROVE**

---

## 1. Observation (觀察事實)

1. **Rust ARM64 交叉編譯檢查 (`cargo check --target aarch64-unknown-linux-gnu`)**:
   - `guest/bridge-agent`: 執行 `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`。
     - 輸出：`Finished dev profile [unoptimized + debuginfo] target(s) in 0.07s`。
     - 結果：**0 warnings, 0 errors**。
   - `guest/portal-agent`: 執行 `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`。
     - 輸出：`Finished dev profile [unoptimized + debuginfo] target(s) in 0.02s`。
     - 結果：**0 warnings, 0 errors**。

2. **Rust 單元與實證測試 (`cargo test`)**:
   - `guest/bridge-agent`: 執行 `$HOME/.cargo/bin/cargo test`。
     - 測試總數：35 個測試（包含 HMAC 測試、Socket 超時測試、Pty / Wayland 壓力測試、重放與非法金鑰防禦測試）。
     - 結果：`test result: ok. 35 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 10.00s`。
   - `guest/portal-agent`: 執行 `$HOME/.cargo/bin/cargo test`。
     - 結果：0 passed; 0 failed。測試編譯時產生 3 個未使用的匯入/變體警告（`unused import: CString`, `unused import: OsStrExt`, `variant Deleted is never constructed` in `inotify_watcher.rs`），但功能無誤。

3. **原生 C++ 測試與端對端 (E2E) 測試 verification**:
   - C++ 原生單元測試：執行 `./build_out/bin/linux_bridge_test`。
     - 結果：`PASS (50/50 succeeded)`，`NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`。
   - Python E2E Tier 1 & Tier 2：執行 `python3 tests/e2e/runner.py --tier 1 --feature F-R3-001..007` 與 `--tier 2 --feature F-R3-001..007`。
     - 結果：**100.0% PASS RATE** across all Tier 1 and Tier 2 tests for Milestone 3 (F-R3-001 到 F-R3-007)。

4. **安全與協議機制檢查 (Replay protection, Invalid secret, Timeout)**:
   - **重放防護 (Replay Protection)**：
     - 在 `system/linux_bridge/hmac_auth.cpp` 中，`HmacAuth::isTokenUsed` 與 `HmacAuth::markTokenUsed` 透過線程安全的 `sUsedTokens` 哈希表維護已使用的 Token。
     - 在 `verifyHandshake` 中，若發現 `isTokenUsed(payloadToken)` 為 true，立即輸出 `[HmacAuth] Replayed token rejected during handshake` 並拒絕握手。
   - **非法金鑰處理 (Invalid Secret Handling)**：
     - `verify_token` (`guest/bridge-agent/src/auth.rs`) 與 `constantTimeCompare` (`system/linux_bridge/hmac_auth.cpp`) 採用常數時間位元比較 (Constant-time comparison)，防止時脈側信道攻擊 (Timing attacks)。
     - 零金鑰 (`all-zero token`)、空金鑰、或 HMAC 簽名不匹配時，回應 `STATUS_UNAUTHORIZED` (0x00000401)，紀錄 Security Alert 並中止連線。
   - **超時機制 (Timeout Behavior)**：
     - Host 端 `HmacAuth::HANDSHAKE_TIMEOUT_SEC` 設定為 5.0 秒，超時即拒絕握手。
     - Guest 端 `perform_handshake` 透過 `stream.set_read_timeout(Some(Duration::from_secs(5)))` 設定 5 秒 Socket 讀取超時。
     - 在實證測試 `test_silent_socket_handshake_timeout_empirical` 中驗證：無論是傳送部分封包（5 bytes）或完全沉默連線（0 bytes），均在 5 秒超時窗口（4.8s~6.5s）準確中斷並傳回 false。

---

## 2. Logic Chain (推理邏輯鏈)

1. **單一金鑰 (Single-Secret) 協議閉環**:
   - Host Java (`LinuxManagerService`) 隨機產生 32-byte Token + 32-byte Secret (64-byte payload)，透過 `launch_vm.sh` 以 `android_bridge.token=<64_hex_chars>` 傳遞給 Kernel Cmdline。
   - Guest Agent (`auth.rs`) 從 `/proc/cmdline` 讀取並精確解碼為 32-byte 二進位金鑰。
   - Guest 啟動時作為 Initiator 連線至 Host CID 2 Port 5000，發送 64-byte `AuthHandshakePayload` (Token + HMAC-SHA256 signature)。
   - Host C++ (`vsock_server.cpp`) 驗證 HMAC 簽名成功後，發送 `CMD_HANDSHAKE_COMPLETE` 促使 VM 狀態轉為 `RUNNING`。

2. **強固性與防禦深度**:
   - 經實測，所有常數時間比較、防重放雜湊表與 5 秒讀取超時運作符合預期。
   - ARM64 交叉編譯無任何警告與錯誤，Rust 單元測試與 Python E2E 測試達到 100% 通過率。

---

## 3. Caveats (注意事項)

- **輕微編譯警告**：`guest/portal-agent` 在執行 `cargo test` 時對 `inotify_watcher.rs` 產生 3 個 `unused import` / `dead_code` 警告，但在指定 `--target aarch64-unknown-linux-gnu` 的 `cargo check` 中為 0 警告 0 錯誤。不影響安全與功能。

---

## 4. Conclusion (審查結論)

Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator) 的實作符合所有規格與安全性要求。

**審查判定：APPROVE**

---

## 5. Verification Method (獨立驗證方法)

1. **Rust ARM64 交叉編譯檢查**:
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   (cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   ```
   *預期結果*: Exit code 0, 0 warnings, 0 errors.

2. **Rust 單元與實證測試**:
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo test)
   (cd guest/portal-agent && $HOME/.cargo/bin/cargo test)
   ```
   *預期結果*: 35/35 passed, Exit code 0.

3. **C++ 原生單元測試**:
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```
   *預期結果*: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`.

4. **Python E2E 測試**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --feature F-R3-001..007
   python3 tests/e2e/runner.py --tier 2 --feature F-R3-001..007
   ```
   *預期結果*: 100.0% PASS RATE.
