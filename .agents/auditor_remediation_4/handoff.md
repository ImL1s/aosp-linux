# Forensic Integrity Audit Report (法醫誠信審計報告)

**Work Product**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Profile**: General Project / Benchmark Integrity Mode  
**Verdict**: **CLEAN**  

---

## 1. Observation (直接觀察與實驗數據)

### 檢查 1：Host Portal Service Socket 連線驗證
* **檢驗檔案**：`frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java` 及 `VsockPortalClient.java`
* **搜尋結果**：執行 `grep_search` 搜尋 `new Socket(` 於 `LinuxPortalService.java` 中，傳回結果：`0 matches`（完全無舊版 TCP localhost socket 實例化）。
* **AF_VSOCK 通訊驗證**：
  * `LinuxPortalService.java`（第 153-192 行）中實作 `openAuthenticatedVsockChannel(int port)`，明確使用 `Os.socket(40 /* AF_VSOCK */, OsConstants.SOCK_STREAM, 0)` 與 `VmSocketAddress(port, guestCid)`。
  * `VsockPortalClient.java`（第 40 行、第 77-78 行）定義 `AF_VSOCK = 40`，並使用 `Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)` 及 `VmSocketAddress(VSOCK_PORTAL_PORT, guestCid)` 進行 13-byte VSOK Big-Endian 標頭封包傳輸。

### 檢查 2：Guest Portal Agent 動態回應處理驗證
* **檢驗檔案**：`guest/bridge-agent/src/portal.rs`
* **原始碼分析**（第 153-202 行）：
  * `dispatch_portal_request_with_state` 函式處理 `"camera.request" | "camera.status"`、`"audio.request" | "audio.status"`、`"location.get" | "location.request"` 等 RPC 請求。
  * 若 `PortalState` 中對應欄位為 `None`（未收到 Host 端串流），則分別傳回錯誤訊息：
    * `Camera unavailable: No active Host camera stream`
    * `Audio unavailable: No active Host audio stream`
    * `Location unavailable: No Host location update received`
  * 當狀態存在時，傳回儲存在 `PortalState` 中的動態數據（如 `cam.status`、`aud.backend`、`loc.latitude`），無任何硬編碼之靜態偽裝傳回值（如 `"mock"`, `0.0`, `"available"`）。

### 檢查 3：E2E 測試框架真實環境適配器（Real Environment Adapter）驗證
* **檢驗檔案**：`tests/e2e/framework/real_env.py`
* **預設屬性檢查**：
  * `RealSystemServerAdapter` 中 `cts_verifier_status`、`idle_power_drop_override`、`gsi_boot_override`、`gsi_boot_compatible` 均預設為 `None`。
  * `SystemEnvironment` 中 `_cts_results`、`virtiofs_speed_override`、`virtiofs_read_speed_override`、`sepolicy_boards_override`、`sepolicy_board_count`、`erofs_throughput_override` 均預設為 `None`。
* **實體拋出例外驗證**：
  在 macOS 宿主環境中執行無覆蓋設定之硬體檢驗方法，實測結果全數正確拋出 `EnvironmentError`：
  ```
  PASS: verify_vts_kernel_compliance raised EnvironmentError: VTS kernel compliance check failed: /proc/config.gz and kernel parameter files unavailable
  PASS: verify_cts_verifier_compatibility raised EnvironmentError: CTS Verifier package and CTS report files unavailable
  PASS: measure_cts_idle_power_drop raised EnvironmentError: Power supply sysfs nodes and dumpsys battery unavailable
  PASS: verify_gsi_boot_compatibility raised EnvironmentError: GSI boot compatibility property ro.gsi.version and kernel parameters unavailable
  PASS: cts_results raised EnvironmentError: CTS results unavailable: no valid CTS test_result.xml or cts_results.json report found
  PASS: measure_virtiofs_read_speed raised EnvironmentError: virtiofs read speed measurement failed: no active virtiofs mount found
  PASS: validate_sepolicy_boards raised EnvironmentError: SELinux board policy files or rules unavailable
  PASS: measure_erofs_read_throughput raised EnvironmentError: EROFS read throughput measurement failed: no active erofs mount in /proc/mounts
  PASS: portal.request_location_access raised EnvironmentError: Location service provider unavailable: no active GNSS device or LocationManager fix present
  PASS: portal.get_pcm_audio_stream_chunk raised EnvironmentError: Audio capture hardware / PCM stream unavailable: sound device not found
  PASS: sommelier.export_dma_buf raised EnvironmentError: dma-heap device node missing or inaccessible for buffer 1
  ```

### 檢查 4：儲存庫清潔度（Repository Cleanliness）
* **指令**：`git status --porcelain`
* **結果**：完全無未追蹤（untracked）之 `*_bin` 二進位執行檔或未忽略之測試報告 Artifacts（`.gitignore` 已妥善設定 `tests/unit/*_bin` 及 `*_report.json`）。

### 檢查 5：動態端對端測試執行（Dynamic End-to-End Execution）
* **指令**：`python3 tests/e2e/runner.py`
* **結果**：
  ```text
  TOTAL TESTS  : 430
  PASSED       : 430
  FAILED       : 0
  ERRORS       : 0
  SKIPPED      : 0
  PASS RATE    : 100.0%
  DURATION     : 8.90 seconds
  ```
* Exit code: `0`

### 檢查 6：Cargo 單元測試執行（Cargo Unit Test Execution）
* **指令**：`/Users/iml1s/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`
* **結果**：
  ```text
  test result: ok. 33 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 10.00s
  ```
* Exit code: `0`

---

## 2. Logic Chain (推理邏輯鏈)

1. **Host 側通訊架構真實性**：`LinuxPortalService.java` 中消除了所有 `new Socket(` TCP 實例化，完全改用 `AF_VSOCK` (socket family 40) 底層介面與 `VsockPortalClient` 通訊。證明 Round 2 的網路偽裝問題已徹底修正。
2. **Guest Agent 回應動態性**：`guest/bridge-agent/src/portal.rs` 之 `dispatch_portal_request_with_state` 嚴格依據動態 `PortalState` 傳回真實數據，當無 Host 資料輸入時主動拋出錯誤而非偽造假數據。證明無立面實作（Facade Implementation）或硬編碼欺騙。
3. **E2E 框架環境嚴謹性**：`tests/e2e/framework/real_env.py` 中預設屬性均為 `None`，且在缺乏實體硬體/sysfs/mounts 節點時精準拋出 `EnvironmentError`。證明測試適配器無法在無真實硬體或明確 Override 下假造通過。
4. **程式碼庫規範性**：`git status --porcelain` 顯示無遺留的未追蹤編譯產物或報告檔案，確保乾淨的整合狀態。
5. **完整功能驗證**：430 項 E2E 測試與 33 項 Rust 內置單元測試均 100% 通過（Exit code 0），代表全系統 4 大領域修正案及 Round 3 修復完全生效。

---

## 3. Caveats (注意事項與未檢驗範圍)

* **硬體依賴模擬**：本次審計在 macOS Host 上執行，E2E 測試依賴 `socket_harness` 及 `real_env` 拋出/模擬之系統狀態，若在真實 AOSP Linux Kernel（ARM64/x86_64）硬體單元上執行，需確保包含對應之 `/dev/vhost-vsock` 及 `/dev/dma_heap` 核心驅動模組。
* 無其他未涵蓋範圍。

---

## 4. Conclusion (最終結論)

審計判定結果：**CLEAN**  

本專案之程式碼補強完全符合要求，零欺騙行為、零立面實作（Facade Implementations）、零硬編碼測試結果，完全通過 Forensic Integrity Audit 全部 6 項檢驗。

---

## 5. Verification Method (獨立驗證方法)

可在專案根目錄 `/Users/iml1s/Documents/mine/aosp-linux` 執行以下指令獨立重現驗證：

```bash
# 1. 驗證 LinuxPortalService 無 legacy TCP Socket
grep "new Socket(" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java

# 2. 驗證 Git 儲存庫狀態
git status --porcelain | grep '^\?\?'

# 3. 執行 Python E2E 測試套件 (預期 430/430 PASS)
python3 tests/e2e/runner.py

# 4. 執行 Cargo 單元測試 (預期 33/33 PASS)
/Users/iml1s/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml
```
