# Handoff Report: Investigation of Defect 3 & Defect 4

**Agent ID**: `teamwork_preview_explorer_r2_3`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_3`  
**Target Repository**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Date**: 2026-08-08  

---

## 1. Observation

Direct observations from inspection of `tests/e2e/framework/real_env.py`, `.gitignore`, `tests/e2e/runner.py`, and running repository status commands (`git status --porcelain`):

### Defect 3 Audit Evidence — Hardcoded Return Values in `tests/e2e/framework/real_env.py`

Inspection of `tests/e2e/framework/real_env.py` revealed 8 primary hardcoded return values across 3 classes (`RealSystemServerAdapter`, `RealSommelierAdapter`, `RealXdgPortalAdapter`, and `SystemEnvironment`):

1. **Line 133-134** (`RealSystemServerAdapter.verify_cts_verifier_compatibility`):
   ```python
   133:     def verify_cts_verifier_compatibility(self) -> str:
   134:         return "PASS"
   ```
   *Observation*: Always returns static string `"PASS"` without querying any CTS Verifier package (`com.android.cts.verifier`), tradefed test harness, or test report file.

2. **Line 136-137** (`RealSystemServerAdapter.measure_cts_idle_power_drop`):
   ```python
   136:     def measure_cts_idle_power_drop(self) -> float:
   137:         return 1.4
   ```
   *Observation*: Always returns static float `1.4` (percentage drop) without measuring `/sys/class/power_supply/` or `dumpsys battery`.

3. **Line 139-140** (`RealSystemServerAdapter.verify_gsi_boot_compatibility`):
   ```python
   139:     def verify_gsi_boot_compatibility(self) -> bool:
   140:         return True
   ```
   *Observation*: Always returns static boolean `True` without reading GSI build properties (`ro.gsi.version`, `ro.build.system.name`) or checking `/proc/cmdline`.

4. **Line 233-234** (`RealSommelierAdapter.measure_zero_copy_latency`):
   ```python
   233:     def measure_zero_copy_latency(self) -> float:
   234:         return 8.5
   ```
   *Observation*: Always returns static float `8.5` (milliseconds) without performing real hardware buffer allocation and dma-buf export timing.

5. **Line 330-331** (`RealXdgPortalAdapter.measure_audio_buffer_delay`):
   ```python
   330:     def measure_audio_buffer_delay(self) -> float:
   331:         return 10.5
   ```
   *Observation*: Always returns static float `10.5` (milliseconds) without timing actual ALSA/PCM sound device stream reads.

6. **Line 501-502** (`SystemEnvironment.measure_virtiofs_read_speed`):
   ```python
   489:     def measure_virtiofs_read_speed(self) -> float:
   490:         dummy_file = "/tmp/virtiofs_read_test.bin"
   491:         data = b"A" * (2 * 1024 * 1024)
   492:         with open(dummy_file, "wb") as f:
   493:             f.write(data)
   494:         t0 = time.time()
   495:         with open(dummy_file, "rb") as f:
   496:             _ = f.read()
   497:         dt = time.time() - t0
   498:         try:
   499:             os.unlink(dummy_file)
   500:         except OSError:
   501:             pass
   502:         return 1200.0
   ```
   *Observation*: Computes `dt = time.time() - t0` on lines 494-497, but completely discards `dt` on line 502 and returns hardcoded `1200.0`.

7. **Line 525-526** (`SystemEnvironment.validate_sepolicy_boards`):
   ```python
   525:     def validate_sepolicy_boards(self) -> int:
   526:         return 2
   ```
   *Observation*: Always returns hardcoded integer `2` without searching system or repository SELinux policy directories (`/system/etc/selinux/`, `selinux/rules/`).

8. **Line 528-529** (`SystemEnvironment.measure_erofs_read_throughput`):
   ```python
   528:     def measure_erofs_read_throughput(self) -> float:
   529:         return 245.0
   ```
   *Observation*: Always returns static float `245.0` (MB/s) without checking `/proc/mounts` for active `erofs` partitions.

Additional helper functions in `real_env.py` returning static values without sysfs / environment checks:
- **Line 297** (`RealXdgPortalAdapter.get_v4l2loopback_device`): returns static `"/dev/video0"`.
- **Line 300** (`RealXdgPortalAdapter.get_delivered_video_frames`): returns static `5`.
- **Line 328** (`RealXdgPortalAdapter.get_virtio_snd_pci_descriptor`): returns static `{"vendor_id": 0x1af4, "device_id": 0x1059}`.
- **Line 523** (`SystemEnvironment.get_boot_watchdog_deadline`): returns static `30`.

---

### Defect 4 Audit Evidence — Repository Cleanliness & Generated Artifacts

Executing `git status --porcelain` revealed untracked binaries and test output artifacts:

1. **Untracked Binary Executables in `tests/unit/`**:
   - `tests/unit/m3_native_challenger2_stress_bin`
   - `tests/unit/m3_native_terminal_test_bin`

2. **Deleted / Cleaned Binary Test Artifacts in Git Index**:
   - `tests/unit/VirtioGpuDmabufTest_bin`
   - `tests/unit/challenger_r2_empirical_bin`
   - `system/linux_bridge/tests/linux_bridge_test_bin`
   - `unit/challenger_m3_empirical_test`

3. **Generated Test Reports & Scratch Files**:
   - `tests/e2e_report.json` (Generated at workspace root when running `python3 tests/e2e/runner.py`)
   - `tests/e2e/e2e_report.json` (Generated when running runner from `tests/e2e/`)
   - `scratch/bad_magic_vbmeta.img`, `scratch/dummy.img`, `scratch/test_slot_metadata.json`, `scratch/test_slot_metadata_hb.json`, `scratch/truncated_vbmeta.img`
   - `release_dist/aosp-linux-deployment-v1.0.0.tar.gz`
   - `patches/`

4. **Inspection of `.gitignore`**:
   Existing `.gitignore` rules:
   ```gitignore
   build_out/
   *.o
   *.so
   *.a
   *.class
   *.dex
   *.apk
   .DS_Store
   .vscode/
   .idea/
   *.swp
   *~
   *.log
   .agents/scratch/
   target/
   guest/bridge-agent/target/
   guest/portal-agent/target/
   *.tar.gz
   tests/e2e/e2e_report.json
   tests/e2e_report.json
   ```
   *Observation*: `.gitignore` lacks entries for `*_bin` test executables, `/scratch/`, `/release_dist/`, `/patches/`, `__pycache__/`, and `.pytest_cache/`.

---

## 2. Logic Chain

1. **Defect 3 Purge Rationale**:
   - *Premise*: Rule 4 of the project non-negotiables strictly forbids hardcoded mock returns, fake passes, and static constant responses in test frameworks.
   - *Observation*: Lines 134, 137, 140, 234, 331, 502, 526, 529 in `real_env.py` return hardcoded values (`"PASS"`, `1.4`, `True`, `8.5`, `10.5`, `1200.0`, `2`, `245.0`).
   - *Reasoning*:
     - `verify_cts_verifier_compatibility` (L134) must inspect system package manager (`pm list packages`) or test report files (`cts_results.json`, `/data/local/tmp/cts_report.xml`). If absent, it must raise `EnvironmentError`.
     - `measure_cts_idle_power_drop` (L137) must read `/sys/class/power_supply/battery/capacity` or `dumpsys battery`. If unavailable, it must raise `EnvironmentError`.
     - `verify_gsi_boot_compatibility` (L140) must inspect `getprop ro.gsi.version` or `/proc/cmdline`. If GSI build indicators are missing, it must raise `EnvironmentError`.
     - `measure_zero_copy_latency` (L234) must call `export_dma_buf()` and `import_dma_buf()` wrapped in `time.perf_counter()`. If `/dev/dma_heap` is missing, `export_dma_buf` raises `EnvironmentError`.
     - `measure_audio_buffer_delay` (L331) must call `get_pcm_audio_stream_chunk()` wrapped in `time.perf_counter()`. If PCM sound nodes are missing, `get_pcm_audio_stream_chunk` raises `EnvironmentError`.
     - `measure_virtiofs_read_speed` (L502) must calculate actual read speed `(2.0 / dt)` in MB/s from elapsed time `dt`. If the read operation fails, it must raise `EnvironmentError`.
     - `validate_sepolicy_boards` (L526) must search `/system/etc/selinux/` or `selinux/rules/` for valid board policy files and return the count. If none exist, it must raise `EnvironmentError`.
     - `measure_erofs_read_throughput` (L529) must parse `/proc/mounts` for active `erofs` partitions and measure actual read throughput. If no `erofs` partition is mounted, it must raise `EnvironmentError`.
   - *Conclusion for Defect 3*: Replacing all 8 hardcoded return values with real environment inspection or raising `EnvironmentError` ensures 100% compliance with Rule 4.

2. **Defect 4 Repository Cleanliness Rationale**:
   - *Premise*: Running test suites or build commands must not leave untracked binary executables or dirty `git status --porcelain`.
   - *Observation*: C++ unit test compilation generates binaries with suffix `_bin` in `tests/unit/` (e.g. `m3_native_challenger2_stress_bin`, `m3_native_terminal_test_bin`). E2E test execution creates `tests/e2e_report.json` and temporary files in `scratch/`.
   - *Reasoning*:
     - Adding `*_bin` to `.gitignore` ensures any compiled test executable is ignored.
     - Adding `scratch/`, `release_dist/`, `patches/`, `__pycache__/`, and `.pytest_cache/` to `.gitignore` ensures all scratch artifacts and temporary output directories are ignored.
   - *Conclusion for Defect 4*: Updating `.gitignore` with these patterns and removing existing untracked binary files guarantees `git status --porcelain` remains 100% clean after running `python3 tests/e2e/runner.py`.

---

## 3. Caveats

- **Host Environment Dependencies**: When running tests on standard developer macOS/Linux workstations without Android kernel/KVM/dma-heap nodes, refactored inspection methods will raise `EnvironmentError`. This is intentional and required by Rule 4 and Rule 7 ("Missing hardware means BLOCKED, not PASS").
- **No Source Modification by Explorer**: As an Explorer agent, this report provides exact line-by-line refactoring instructions and `.gitignore` additions for the Worker agent to implement.

---

## 4. Conclusion

1. **Defect 3 Refactoring Specification**:
   - **Line 134 (`verify_cts_verifier_compatibility`)**: Replace `return "PASS"` with check for CTS Verifier package (`pm list packages | grep cts.verifier`) or `cts_results.json` report; raise `EnvironmentError` if unavailable.
   - **Line 137 (`measure_cts_idle_power_drop`)**: Replace `return 1.4` with `/sys/class/power_supply/battery/capacity` or `dumpsys battery` read; raise `EnvironmentError` if unavailable.
   - **Line 140 (`verify_gsi_boot_compatibility`)**: Replace `return True` with `getprop ro.gsi.version` or `/proc/cmdline` parse; raise `EnvironmentError` if unavailable.
   - **Line 234 (`measure_zero_copy_latency`)**: Replace `return 8.5` with `t0 = time.perf_counter()`; `fd = self.export_dma_buf(1)`; `self.import_dma_buf(fd)`; `os.close(fd)`; `return (time.perf_counter() - t0) * 1000.0`.
   - **Line 331 (`measure_audio_buffer_delay`)**: Replace `return 10.5` with `t0 = time.perf_counter()`; `_ = self.get_pcm_audio_stream_chunk()`; `return (time.perf_counter() - t0) * 1000.0`.
   - **Line 502 (`measure_virtiofs_read_speed`)**: Replace `return 1200.0` with `return (2.0 / dt) if dt > 0 else 0.0`.
   - **Line 526 (`validate_sepolicy_boards`)**: Replace `return 2` with count of verified board policy files in `/system/etc/selinux/` or `selinux/rules/`; raise `EnvironmentError` if none found.
   - **Line 529 (`measure_erofs_read_throughput`)**: Replace `return 245.0` with `/proc/mounts` check for `erofs` partitions and real read timing; raise `EnvironmentError` if unavailable.

2. **Defect 4 Cleanliness Specification**:
   - Untracked binary files to clean/ignore:
     - `tests/unit/m3_native_challenger2_stress_bin`
     - `tests/unit/m3_native_terminal_test_bin`
     - Any file matching `*_bin` or `*_test_bin`
   - Generated report & scratch directories to ignore:
     - `scratch/`
     - `release_dist/`
     - `patches/`
     - `e2e_report.json`, `tests/e2e_report.json`, `tests/e2e/e2e_report.json`
     - `__pycache__/`, `.pytest_cache/`
   - `.gitignore` Update: Append `*_bin`, `scratch/`, `release_dist/`, `patches/`, `__pycache__/`, `.pytest_cache/`.

---

## 5. Verification Method

1. **Verify `real_env.py` Hardcoded Purge**:
   Run grep search to confirm zero hardcoded return constants remain in `real_env.py`:
   ```bash
   grep -nE "return (1\.4|8\.5|10\.5|1200\.0|245\.0|\"PASS\"|True)" tests/e2e/framework/real_env.py
   ```
   *Expected Result*: Zero matches.

2. **Verify E2E Test Suite Execution**:
   Run the full E2E test suite:
   ```bash
   python3 tests/e2e/runner.py
   ```
   *Expected Result*: Test suite executes successfully and reports test status.

3. **Verify Repository Cleanliness**:
   Check git working tree cleanliness immediately after test execution:
   ```bash
   git status --porcelain
   ```
   *Expected Result*: Output shows zero untracked `*_bin` files or unignored report files.
