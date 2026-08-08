# Remediation Review R3 Handoff Report — reviewer_r3_2

## 1. Observation (觀察事實)

對 `tests/e2e/framework/real_env.py` 與 `guest/bridge-agent/src/pty.rs` 進行行號級別的代碼審查與實體測試驗證，觀察到以下具體事實：

### 1.1 `tests/e2e/framework/real_env.py`
- **預設屬性初始化為 `None`**：
  - Line 38–41 (`RealSystemServerAdapter.__init__`)：
    ```python
    self.cts_verifier_status: Optional[str] = None
    self.idle_power_drop_override: Optional[float] = None
    self.gsi_boot_override: Optional[bool] = None
    self.gsi_boot_compatible: Optional[bool] = None
    ```
  - Line 473–478 (`SystemEnvironment.__init__`)：
    ```python
    self._cts_results: Optional[Dict[str, int]] = None
    self.virtiofs_speed_override: Optional[float] = None
    self.virtiofs_read_speed_override: Optional[float] = None
    self.sepolicy_boards_override: Optional[int] = None
    self.sepolicy_board_count: Optional[int] = None
    self.erofs_throughput_override: Optional[float] = None
    ```
- **無 Override 時拋出 `EnvironmentError`**：
  - `verify_cts_verifier_compatibility()` (Line 137–158)：若無 `cts_verifier_status` 覆寫，且 Host 缺乏 CTS 結果檔或 `pm` / `cts-tradefed` 工具，明確拋出 `EnvironmentError("CTS Verifier package and CTS report files unavailable")`。
  - `measure_cts_idle_power_drop()` (Line 160–187)：若無 `idle_power_drop_override` 覆寫，且 Host 缺乏 `/sys/class/power_supply/battery` 或 `dumpsys battery`，明確拋出 `EnvironmentError("Power supply sysfs nodes and dumpsys battery unavailable")`。
  - `verify_gsi_boot_compatibility()` (Line 189–210)：若無 `gsi_boot_compatible` 覆寫，且 Host 缺乏 `getprop ro.gsi.version` 或 `/proc/cmdline` 之中 GSI 標記，明確拋出 `EnvironmentError("GSI boot compatibility property ro.gsi.version and kernel parameters unavailable")`。
  - `measure_erofs_read_throughput()` (Line 709–736)：若無 `erofs_throughput_override` 覆寫，且 `/proc/mounts` 中無 `erofs` 掛載點，明確拋出 `EnvironmentError("EROFS read throughput measurement failed: no active erofs mount in /proc/mounts")`。
  - `measure_virtiofs_read_speed()` (Line 616–664)：若無 `virtiofs_read_speed_override` 覆寫，且 `/proc/mounts` 中無 `virtiofs` 掛載點，明確拋出 `EnvironmentError("virtiofs read speed measurement failed: no active virtiofs mount found")`。
- **`SystemEnvironment.reset()` 狀態重置與隔離**：
  - Line 530–589 (`SystemEnvironment.reset`) 清晰地將所有 10 項 Override 與快取欄位重置為 `None`：
    `self.system_server.cts_verifier_status = None`
    `self.system_server.idle_power_drop_override = None`
    `self.system_server.gsi_boot_override = None`
    `self.system_server.gsi_boot_compatible = None`
    `self._cts_results = None`
    `self.virtiofs_speed_override = None`
    `self.virtiofs_read_speed_override = None`
    `self.sepolicy_boards_override = None`
    `self.sepolicy_board_count = None`
    `self.erofs_throughput_override = None`
    `self.sommelier.zero_copy_latency_override = None`
    `self.portal.audio_delay_override = None`

### 1.2 `guest/bridge-agent/src/pty.rs`
- **`PtyMaster::open` ENXIO 處理**：
  - Line 48–66 (`PtyMaster::open`)：
    ```rust
    let master_fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
    if master_fd < 0 {
        let err = io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::ENXIO) {
            eprintln!("[PtyMaster::open] posix_openpt returned ENXIO (OS error 6): missing PTY allocation device on host platform");
        }
        return Err(err);
    }
    ```
    當 Host 平台缺少 `/dev/ptmx` 時，`posix_openpt` 回傳 -1 且 errno 為 `ENXIO` (OS error 6)，`PtyMaster::open` 記錄清晰日誌並將原始 `Err(err)` 向上傳遞，並未遮蔽真實 OS 錯誤或回傳假 FD。
- **單元測試優雅處理 ENXIO**：
  - Line 310–330 (`test_pty_master_open_and_slave_name`) 與 Line 332–351 (`test_pty_resize`)：
    測試函數對 `PtyMaster::open()` 回傳之 `Err(e)` 進行匹配，若 `e.raw_os_error() == Some(libc::ENXIO)` 則印出提示訊息並優雅跳過測試，不致引發 panic，同時維護測試環境相容性。

### 1.3 測試執行結果 (Test Execution Results)
1. **Python End-to-End Suite (`python3 tests/e2e/runner.py`)**：
   - 執行結果：`TOTAL TESTS: 430 | PASSED: 430 | FAILED: 0 | ERRORS: 0 | PASS RATE: 100.0%` (耗時 8.78 秒)。
2. **Rust Bridge-Agent Suite (`$HOME/.cargo/bin/cargo test`)**：
   - 執行結果：`33 passed; 0 failed; 0 ignored` (包含 `test_pty_master_open_and_slave_name`, `test_pty_resize`, `test_pty_disconnect_no_sigabrt_stress` 等 33 項測試全部通過)。
3. **驗證腳本獨立實測**：
   - 經由 `python3 -c` 腳本測試預設初始化、`EnvironmentError` 拋出、Override 設定及 `env.reset()` 後還原，均全數通過。

---

## 2. Logic Chain (推理邏輯鏈)

1. **前提 1（預設值正確性）**：
   - 在先前版本中，`RealSystemServerAdapter` 的 `cts_verifier_status` 預設為 `"PASS"`、`idle_power_drop_override` 預設為 `1.4`、`gsi_boot_compatible` 預設為 `True`，這會導致測試在沒有真實硬體或報告檔案時誤判通過。
   - 修正後，上述欄位在 `__init__` 及 `reset()` 中皆被初始化為 `None`。因此在缺乏真實系統節點且未顯式指定 Override 的情況下，方法調用會精確觸發 `EnvironmentError`。
2. **前提 2（環境錯誤與退回機制）**：
   - 審查 `verify_cts_verifier_compatibility()`, `measure_cts_idle_power_drop()`, `verify_gsi_boot_compatibility()`, `measure_erofs_read_throughput()`, 以及 `measure_virtiofs_read_speed()`，發現這些方法皆優先檢測真實檔案系統 (`/proc/mounts`, `/sys/class/power_supply/battery`, `cts_results.json`) 或執行系統指令 (`dumpsys`, `getprop`)。只有在系統節點不存在且 Override 為 `None` 時才拋出 `EnvironmentError`，符合真實測試架構之防禦性要求。
3. **前提 3（狀態隔離與防止洩漏）**：
   - `SystemEnvironment.reset()` 完整的重置了包含 `system_server`、`sommelier`、`portal` 以及 `SystemEnvironment` 本體上的所有 Override 與快取欄位，確保測試案例之間的狀態完全隔離，不會發生測試間覆寫殘留洩漏。
4. **前提 4（PTY 設備缺失之錯誤處理）**：
   - `pty.rs` 中 `PtyMaster::open` 使用 `posix_openpt`，在不支援 PTY 設備的 Host 上會回傳 `ENXIO`。代碼適當地補補了日誌並回傳 `Err(err)`，絕不掩蓋錯誤。單元測試精確捕捉 `ENXIO` 錯誤碼並優雅退出，避免在 CI/沙盒環境無 `/dev/ptmx` 時造成非預期 panic。
5. **前提 5（誠信檢查 / Code Integrity Inspection）**：
   - 仔細比對源碼與測試後，未發現任何偽造 (facade) 實作、硬編碼測試結果、靜態回傳捷徑或自我認證 (self-certifying) 欺騙性程式碼。

---

## 3. Caveats (審查局限與假設)

1. **Linux 專屬 sysfs 節點**：本次審查在 macOS Host 環境下執行測試，因此 `verify_cts_verifier_compatibility` 等方法會精確觸發 `EnvironmentError`；在真實 Linux/Android 目標機上，這些方法會直接讀取真實 sysfs 節點與 `dumpsys` 輸出。
2. **Cargo 工具鏈路徑**：環境中 `cargo` 未在預設 PATH，需使用全路徑 `$HOME/.cargo/bin/cargo` 執行 Rust 測試。

---

## 4. Conclusion (結論與審查裁定)

**Verdict: APPROVE (核准通過)**

本次缺陷修復全面滿足審查要求：
1. `real_env.py` 中 5 項預設屬性嚴格初始化為 `None`，無 override 且缺乏 Host 節點時精確拋出 `EnvironmentError`，`reset()` 方法能完美重置所有欄位避免洩漏。
2. `pty.rs` 嚴謹處理 `ENXIO` (OS error 6)，不遮蔽真實系統錯誤，測試案例優雅處理無 PTY 設備之 Host 環境。
3. 程式碼無硬編碼假測試結果或 facade 欺騙行為，全部 430 項 E2E 測試與 33 項 Rust 單元測試皆 100% 通過。

---

## 5. Verification Method (獨立驗證方法)

可在專案根目錄執行以下指令獨立驗證：

### 5.1 驗證 `real_env.py` 屬性初始化、EnvironmentError 與 reset 行為
```bash
python3 -c "
from tests.e2e.framework.real_env import SystemEnvironment
env = SystemEnvironment()
adapter = env.system_server

# 1. 驗證預設值為 None
assert adapter.cts_verifier_status is None
assert adapter.idle_power_drop_override is None
assert adapter.gsi_boot_compatible is None
assert env.virtiofs_read_speed_override is None
assert env.erofs_throughput_override is None

# 2. 驗證無 Override 時拋出 EnvironmentError
for fn in [adapter.verify_cts_verifier_compatibility, adapter.measure_cts_idle_power_drop, adapter.verify_gsi_boot_compatibility, env.measure_erofs_read_throughput, env.measure_virtiofs_read_speed]:
    try:
        fn()
        assert False, f'{fn.__name__} did not raise EnvironmentError'
    except EnvironmentError:
        pass

# 3. 驗證 Override 與 reset 還原
adapter.cts_verifier_status = 'PASS'
env.reset()
assert adapter.cts_verifier_status is None
print('Python RealEnv verification PASS')
"
```

### 5.2 執行完整 E2E 測試套件
```bash
python3 tests/e2e/runner.py
```
*預期輸出*：`TOTAL TESTS: 430 | PASSED: 430 | FAILED: 0`

### 5.3 執行 Rust PTY 與 Bridge Agent 測試套件
```bash
cd guest/bridge-agent && $HOME/.cargo/bin/cargo test
```
*預期輸出*：`test result: ok. 33 passed; 0 failed`
