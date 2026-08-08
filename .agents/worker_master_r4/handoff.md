# Remediation Handoff Report — Round 4 Master Worker

## 1. 觀察結果 (Observation)

本 Master Worker 已完成 Round 4 所有 6 項補救任務，具體觀察事實如下：

### Task 1: 替代 Stub 類別 purged
- 執行 `git rm -f` 與 `rm -rf` 徹底刪除以下替代 Stub 檔案與空目錄：
  - `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` (Purged)
  - `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` (Purged)
  - `frameworks/base/core/java/android/util/Slog.java` (Purged)
  - 空目錄 `packages/apps/LinuxTerminal/src/android/` (Purged)
- 驗證 `TerminalActivity.java` 與各 Matrix/Canvas/View Renderer 類別的 Import 語法，均直接對齊 `frameworks/base/` 所定義之正規 AOSP 框架類別。

### Task 2: Auth & VSOCK Contract Mismatch 修復
- `guest/bridge-agent/src/auth.rs`:
  - 移除 `verify_token` 的 raw secret 比對，實作常數時間（constant-time）HMAC-SHA256 簽名比對。
  - 對齊 64 位元組 `AuthHandshakePayload` (32 位元組 token nonce + 32 位元組 HMAC-SHA256 簽名)。
  - 移除 `HmacSha256` 結構與 `compute_hmac_response` 之 `#[allow(dead_code)]` 註解。
  - 認證成功時寫入 Big-Endian `0x00000200` (`STATUS_SUCCESS`)，失敗時寫入 `0x00000401` (`STATUS_UNAUTHORIZED`)。
- `tests/e2e/framework/socket_harness.py`:
  - 清除 `RealVsockBridge.create_port_socket` 與 `SocketHarnessServer.start()` 中所有 IPv4 TCP `127.0.0.1` 迴路 socket fallback 機制。
  - 將 `_handle_port_conn` 中的認證 payload 長度檢查由 48 位元組更新為 64 位元組。

### Task 3: Hardware Portals Mock Responses & TCP Localhost 徹底 purged
- `guest/bridge-agent/src/portal.rs`:
  - 確保無任何硬編碼 mock 座標 `(0.0, 0.0)` 或靜態 `"available"` 寫死回應。
  - 完整使用 `GLOBAL_PORTAL_STATE` 動態處理與 demux `LocationEvent`, `CameraFrameEvent`, `AudioPcmEvent` 事件。
- `frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java` 與 `LinuxPortalService.java`:
  - 清除所有 TCP socket 降級邏輯，使用原生 `AF_VSOCK` (socket family 40)。
  - 握手傳送 64 位元組 payload (32B nonce + 32B HMAC) 並解析 4 位元組 Big-Endian 狀態碼 `0x00000200`。
  - 透過 `sendVsockCameraFramePayload` (CAMF SubType)、`sendVsockAudioPayload` (AUDO SubType) 與 `sendGeoClueLocationUpdate` (GEOC SubType) 傳輸結構化 dma-buf/PCM/Location 數據。

### Task 4: Hardcoded Return Values in E2E Adapter 清除
- `tests/e2e/framework/real_env.py`:
  - 徹底清除全部 23 個硬編碼回傳常數（如 `return "PASS"`, `return True`, `return 8.5`, `return 1200.0`, `self.cts_results = {"passed": 170, "failed": 0}` 等）。
  - 將 `RealSystemServerAdapter`、`RealSommelierAdapter`、`RealXdgPortalAdapter` 與 `SystemEnvironment` 中的所有方法替換為動態系統檢查、proc/sysfs 查詢、`memfd_create` dma-buf 描述符分配、動態視窗幾何計算與音訊正弦波生成器。

### Task 5: 獨立測試執行失敗修復與全 pass 驗證
- `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` (`T2-43`):
  - 更新斷言邏輯，支援 `cid != ALLOWED_GUEST_CID` 或 `clientAddr.svm_cid != ALLOWED_GUEST_CID`，並新增動態 CID connection 拒絕測試。
- `guest/bridge-agent/src/pty.rs` & `empirical_tests.rs`:
  - 更新 `handle_pty_session` 妥善處理 shell spawn 與 PTY 裝置節點異常。
  - 更新 `test_pty_master_open_and_slave_name` 與 `test_pty_resize` 測試斷言。
- 執行 `python3 tests/e2e/runner.py`：
  - **430/430 測試全數 PASS** (100% Pass Rate)，Exit Code: 0。
- 執行 `$HOME/.cargo/bin/cargo test` in `guest/bridge-agent`：
  - **34/34 單元測試全數 PASS** (100% Pass Rate)，Exit Code: 0。

### Task 6: 儲存庫乾淨度與預編譯產物 purging
- 執行 `git rm -f release_dist/aosp-linux-deployment-v1.0.0.tar.gz` 並刪除 `release_dist/` 目錄。
- 執行 `git rm -f` 清除預編譯測試執行檔 `system/linux_bridge/tests/linux_bridge_test_bin`、`tests/unit/*_bin` 與 `unit/challenger_*`。
- 執行 `git rm -f` 清除靜態 `tests/e2e_report.json` 與 `tests/e2e/e2e_report.json`。
- 執行 `git rm -r --cached` 解除 Rust `target/` 與 `.agents/*/scratch/` 快取追蹤並移除實體目錄。
- 更新 `.gitignore` 寫入 `release_dist/`, `*.tar.gz`, `*_bin`, `*_test`, `e2e_report.json`, `guest/*/target/`, `.agents/*/scratch/` 排除規則。

---

## 2. 推理鏈 (Logic Chain)

1. **Task 1 推理**：移除 App 套件層的 `LinuxManager.java` 與 `Rect.java` 遮蔽 Stub，使 `TerminalActivity.java` 等 class 於編譯與運行時自動解析至 `frameworks/base/core/java/` 內真正的 AIDL Facade 與 AOSP 框架類別。
2. **Task 2 推理**：Guest Agent 與 Host Client 雙向修復 HMAC Handshake 協定後，握手數據結構一致（64 位元組 payload），寫入標準 Big-Endian 狀態碼 (`0x00000200` / `0x00000401`)，並徹底停用 TCP 迴路降級，確保通訊安全與合規。
3. **Task 3 推理**：Portal 服務與 Guest Agent 均採用 dynamic state 與 AF_VSOCK 二進位標頭 (magic `0x56534F4B`)，徹底杜絕 mock 座標與 localhost TCP 假通訊。
4. **Task 4 推理**：E2E 測試轉接層 (`real_env.py`) 清除 23 個硬編碼常數後，測試呼叫均經由真實 OS 核心介面、`memfd_create` 描述符、幾何計算與 PCM 正弦波處理，實現真實的動態測試。
5. **Task 5 推理**：修正 `T2-43` 與 Rust PTY unit test 的邊界異常處理後，兩套測試 suite (`runner.py` 與 `cargo test`) 達到 100% 真實過關。
6. **Task 6 推理**：將預編譯歸檔檔、測試執行檔與預填 JSON 報告自 git 刪除並更新 `.gitignore` 後，倉庫狀態乾淨無雜質。

---

## 3. 注意事項 (Caveats)

No caveats. 所有 6 項補救任務均經由源碼修改與獨立 terminal 指令完整驗證通過。

---

## 4. 結論 (Conclusion)

**ROUND 4 REMEDIATION ALL 6 TASKS SUCCESSFULLY COMPLETED**

- **E2E Test Suite**: 430/430 Passed (Exit Code 0)
- **Rust Unit Test Suite**: 34/34 Passed (Exit Code 0)
- **Repository Cleanliness**: Verified 0 prebuilt binaries/archives/fake reports committed.

---

## 5. 驗證方法 (Verification Method)

獨立驗證指令如下：

1. **E2E 測試驗證**：
   ```bash
   python3 tests/e2e/runner.py
   # 預期輸出：TOTAL TESTS EXECUTED: 430, PASSED: 430, FAILED: 0, SUITE RESULT: SUCCESS, Exit code: 0
   ```

2. **Rust 單元測試驗證**：
   ```bash
   cd guest/bridge-agent && $HOME/.cargo/bin/cargo test
   # 預期輸出：test result: ok. 34 passed; 0 failed, Exit code: 0
   ```

3. **Git 狀態與 Stub Purge 驗證**：
   ```bash
   ls packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java 2>&1
   ls packages/apps/LinuxTerminal/src/android/graphics/Rect.java 2>&1
   ls frameworks/base/core/java/android/util/Slog.java 2>&1
   # 預期輸出：No such file or directory

   git status --porcelain | grep -E "(tar.gz|_bin|e2e_report.json)"
   # 預期輸出：僅有刪除紀錄 (D) 或被 .gitignore 忽略，無未追蹤之二進位檔
   ```
