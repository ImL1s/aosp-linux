# Handoff Report — Milestone M2 (Iteration 2) Explorer Investigation

**Task**: Safe Overwrite of Canonical Target Path `guest/bridge-agent` & Removal of Secondary Folders  
**Agent**: Explorer 1 (`explorer_m2_r2_1`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_1`  
**Date**: 2026-08-08  

---

## 1. Observation (觀察與調研證據)

經對 `/Users/iml1s/Documents/mine/aosp-linux/` 進行實地調查，確認以下事實與證據鏈：

### 1.1 正式規範路徑 `guest/bridge-agent` 現況
1. **硬編碼金鑰 (Hardcoded Secret)**：
   - 檔案：`guest/bridge-agent/src/main.rs` (第 72 行)
   - 原始程式碼：`let shared_secret = b"shared_secret_key_32bytes_long!!";`
   - 缺陷：缺少動態金鑰提取與連線驗證失敗時的進程終止 (`std::process::exit(1)`) 邏輯，且僅有單一模擬迴圈 (Line 29: `loop { std::thread::sleep(...) }`)，未綁定並監聽 Ports 5000, 5001, 5002。
2. **零 Token 降級漏洞 (Zero-Token Fallback)**：
   - 檔案：`guest/bridge-agent/src/auth.rs` (第 24 行)
   - 原始程式碼：`Ok(vec![0u8; 32])`（在 cmdline 解析失敗時預設回傳 32 位元組全零金鑰）。
3. **假樁檔案 (Dummy Stub)**：
   - 檔案：`guest/bridge-agent/src/pty.rs`
   - 檔案大小：15 bytes（內容僅為 `// pty.rs test\n`），完全缺乏 POSIX PTY 開啟、`posix_openpt`、`grantpt`、`unlockpt`、`ptsname`、`TIOCSWINSZ` 調整大小及 shell 啟動邏輯。
4. **單元測試通過數為 0**：
   - 執行指令：`cargo test --manifest-path guest/bridge-agent/Cargo.toml`
   - 測試結果：`running 0 tests; test result: ok. 0 passed; 0 failed`
5. **殘留舊/暫存檔案**：
   - `guest/bridge-agent/Cargo.toml.new`（166 bytes，上次作業殘留檔案）。
   - `guest/bridge-agent/src/ota_rollback.rs`（1039 bytes，未引用且產生編譯警告的死碼）。
6. **寫入權限驗證**：
   - 經實測在 `guest/bridge-agent/src/` 中建立與寫入測試檔案均順利成功。Worker 1 於 Iteration 1 所稱「macOS TCC `com.apple.provenance` 鎖定目錄無法寫入」之主張**已被證實不成立**。

### 1.2 次要目錄 `guest/bridge-agent-m2` 現況
1. **完整 M2 邏輯實現**：
   - 包含 `main.rs` (4779 bytes), `auth.rs` (5018 bytes), `pty.rs` (7520 bytes), `wayland.rs` (3446 bytes), `portal.rs` (6986 bytes), `vsock.rs` (4881 bytes)。
   - `main.rs`: 實現多線程 Server Dispatch Loop，主動監聽 Port 5000 (Portal), 5001 (PTY), 5002 (Wayland)；金鑰提取失敗或監聽綁定失敗時執行 `std::process::exit(1)`。
   - `auth.rs`: 動態由 `LINUX_AUTH_SECRET` 環境變數、`/etc/linux_auth_secret` 檔案或 `/proc/cmdline` (`linux_auth_secret=`) 提取金鑰，拒絕全零 Token 與空 Token，移除任何硬編碼金鑰與 Fallback。
   - `pty.rs`: 實現完整的 `PtyMaster` (open/slave_name/resize/drop)、`PtyHeader` (21-byte 封包編解碼)、shell 啟動及多線程 PTY 讀寫迴圈。
   - `wayland.rs`: 實現 Unix Domain Socket 與 `WAYLAND_DISPLAY` / `XDG_RUNTIME_DIR` 之雙向 Proxy 轉發。
   - `portal.rs`: 實現 JSON-RPC 請求分發 (Camera, Audio, Location, File read/write/list)。
   - `vsock.rs`: 實現 Linux `AF_VSOCK` 監聽/連線，並具備單元測試與非 Linux 環境之 TCP Fallback 模擬。
2. **單元測試結果**：
   - 執行指令：`cargo test --manifest-path guest/bridge-agent-m2/Cargo.toml`
   - 測試結果：`running 18 tests; test result: ok. 18 passed; 0 failed`
3. **Cargo.toml 設定**：
   - 包含 `[dependencies]` `libc`, `serde`, `serde_json` 及 `[dev-dependencies]` `tempfile = "3.8"`。

### 1.3 軟連結 `guest/bridge-agent-link`
- 軟連結指向：`bridge-agent-link -> /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2`
- 此軟連結為非標準路徑產物，需於安全替換後進行清理。

---

## 2. Logic Chain (邏輯推理鏈)

1. **依據專案規範 (PROJECT.md & SCOPE.md)**：
   - Milestone M2 (R2) 的 canonical 交付路徑明確規定為 `guest/bridge-agent`。
   - `Cargo.toml` 必須位於 `guest/bridge-agent/Cargo.toml`，原始碼必須位於 `guest/bridge-agent/src/`。
2. **評估次要目錄程式碼品質**：
   - `guest/bridge-agent-m2/src/` 的程式碼經實測 18 項單元測試 100% 通過，完全滿足 M2 的多線程服務監聽 (Ports 5000/5001/5002)、動態金鑰驗證、POSIX PTY、Wayland 代理與 Portal RPC 需求，且不包含任何硬編碼密碼或全零降級邏輯。
3. **驗證目錄可寫性**：
   - `guest/bridge-agent/src/` 為完全可寫目錄，無任何 macOS 權限限制。
4. **安全覆蓋與清理路徑**：
   - 將 `guest/bridge-agent-m2/src/` 下的 6 個源碼檔案直接覆蓋至 `guest/bridge-agent/src/`。
   - 更新 `guest/bridge-agent/Cargo.toml` 與 `guest/bridge-agent/Cargo.lock`（確保包含 `tempfile` 測試依賴項與 `serde` 等套件）。
   - 刪除舊的死碼 `guest/bridge-agent/src/ota_rollback.rs` 及殘留檔 `guest/bridge-agent/Cargo.toml.new`。
   - 刪除非標準目錄 `guest/bridge-agent-m2` 及軟連結 `guest/bridge-agent-link`。
5. **最終目標狀態**：
   - 在 `guest/bridge-agent/` 目錄直接執行 `cargo test` 即可通過全部 18 項單元測試，且符合 `PROJECT.md` 規範。

---

## 3. Caveats (注意事項與假設)

1. **死碼與未引用檔案**：
   - 舊目錄中存在 `src/ota_rollback.rs`，經查 `guest/bridge-agent-m2/src/main.rs` 未包含 `mod ota_rollback;`，且該檔會引發編譯警告。在覆蓋時應直接將其清理，避免干擾專案編譯。
2. **Cargo Package Name 命名一致性**：
   - 舊 `Cargo.toml` 中 `name = "android-bridge-agent"`，而 `guest/bridge-agent-m2/Cargo.toml` 中 `name = "bridge-agent"`。應採用 `name = "bridge-agent"`，與目錄名稱及 `PROJECT.md` 規範保持一致。
3. **第三方依賴項完整性**：
   - 確保 `Cargo.lock` 一併從 `guest/bridge-agent-m2` 複製覆蓋至 `guest/bridge-agent`，避免 `cargo test` 在本機重新解析依賴時發生版本不一致或網路請求延遲。

---

## 4. Conclusion (結論與具體修復方案)

請 Implementer 依據以下具體步驟執行程式碼替換與目錄清理：

### 修復步驟 (Step-by-Step Remediation Plan)

1. **覆蓋源碼至 Canonical 交付路徑**：
   - 將 `guest/bridge-agent-m2/src/` 下的所有檔案複製並覆蓋至 `guest/bridge-agent/src/`：
     - `main.rs`
     - `auth.rs`
     - `vsock.rs`
     - `pty.rs`
     - `wayland.rs`
     - `portal.rs`
2. **清理多餘/舊有源碼與暫存檔**：
   - 移除 `guest/bridge-agent/src/ota_rollback.rs`
   - 移除 `guest/bridge-agent/Cargo.toml.new`
3. **更新 Cargo 配置與 Lockfile**：
   - 將 `guest/bridge-agent-m2/Cargo.toml` 覆蓋至 `guest/bridge-agent/Cargo.toml`
   - 將 `guest/bridge-agent-m2/Cargo.lock` 覆蓋至 `guest/bridge-agent/Cargo.lock`
4. **移除次要目錄與軟連結**：
   - 刪除軟連結 `guest/bridge-agent-link`
   - 刪除目錄 `guest/bridge-agent-m2`
5. **執行 Target Path 驗證**：
   - 於 `guest/bridge-agent/` 執行 `cargo check` 與 `cargo test`。

---

## 5. Verification Method (獨立驗證方法)

執行以下 Shell 命令獨立驗證修復結果：

```bash
export PATH="$HOME/.cargo/bin:$PATH"

# 1. 驗證 Canonical 目錄 Cargo 編譯
cargo check --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml

# 2. 驗證 Canonical 目錄 18 項單元測試全部通過
cargo test --manifest-path /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml

# 3. 驗證禁用的違規模式已被徹底清除 (回傳應為空)
grep -rn "shared_secret_key_32bytes_long!!" /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/
grep -rn "vec!\[0u8; 32\]" /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/

# 4. 驗證 pty.rs 內容非 Dummy Stub (行數應大於 200 行)
wc -l /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/pty.rs

# 5. 驗證非標準目錄與軟連結已不存在
test ! -d /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2 && echo "bridge-agent-m2 removed: PASS"
test ! -L /Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-link && echo "bridge-agent-link removed: PASS"
```

---
**Report generated by explorer_m2_r2_1**
