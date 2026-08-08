# Handoff Report — Explorer 3 (explorer_m6_testcases)

## 1. Observation

A comprehensive inspection was conducted across all test files and framework components under `tests/e2e/` (using the repository structure at `/tmp/aosp-linux-work/aosp-linux/tests/e2e/` due to workspace permission attributes on macOS `~/Documents`).

### Test Suite Structure Overview
- Total test files audited: 12 test modules across 4 tiers + 3 framework files.
- **Tier 1 (Feature Coverage)**: 5 files (`test_m1_tier1.py` through `test_m5_tier1.py`)
- **Tier 2 (Boundary & Corner Cases)**: 5 files (`test_m1_tier2.py` through `test_m5_tier2.py`)
- **Tier 3 (Cross-Feature Pairwise)**: 1 file (`test_pairwise_matrix.py` containing 40 pairwise classes)
- **Tier 4 (Real-World Scenarios)**: 1 file (`test_scenarios.py` containing 20 scenario classes)
- **Framework**: `mock_env.py`, `assertions.py`, `base_test.py`, `runner.py`

### Catalog of Identified Tautological Assertions & Fake Checks

1. **In-Memory Mock State Tautologies (`self.mock_env`)**:
   - `tests/e2e/tier1_feature_coverage/test_m1_tier1.py`:
     - Line 42: `self.mock_env.start_vm()` sets `self.mock_env.system_server.vm_state = "RUNNING"`.
     - Line 43: `CustomAssertions.assert_equal(self.mock_env.system_server.vm_state, "RUNNING")` — Tautology: Asserts that an in-memory Python property equals the value explicitly set on the preceding line without issuing any socket command or IPC call.
   - `tests/e2e/tier1_feature_coverage/test_m2_tier1.py`:
     - Line 58: `self.mock_env.auth_valid = False` followed by `CustomAssertions.assert_false(self.mock_env.auth_valid)` — Tautology: Asserts Python variable mutated in memory.
   - `tests/e2e/tier1_feature_coverage/test_m3_tier1.py`:
     - Line 35: `session_id = self.mock_env.create_terminal_session()` returns hardcoded string `"0123456789abcdef"`.
     - Line 36: `CustomAssertions.assert_equal(session_id, "0123456789abcdef")` — Tautology: Compares hardcoded return value string with exact literal.

2. **Source Code Text Matching Tautologies (`assert_in("...", content)`)**:
   - `tests/e2e/tier2_boundary_corner/test_m1_tier2.py`:
     - Line 28: `with open("frameworks/.../LinuxManagerService.java") as f: content = f.read()`
     - Line 30: `CustomAssertions.assert_in("BOOT_TIMEOUT_MS = 15000L", content)` — Tautology: Checks if text string exists in source code file, NOT if timeout handling functions at runtime.
   - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`:
     - Line 18: `CustomAssertions.assert_in("/dev/kvm", content)` — Tautology: Text search in script content rather than testing KVM device node opening.
     - Line 19: `CustomAssertions.assert_in("REQ_RAM_MB=4096", content)`
     - Line 25: `CustomAssertions.assert_in('rm -rf "/mnt/overlay/upper/', content)`

3. **Synthetic Performance / Math Tautologies (`assert speed > 500` without I/O)**:
   - `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`:
     - Line 112: Calculates `read_speed_mbps = (100 * 1024 * 1024 / 0.1) / (1024 * 1024)` -> `1000.0` from hardcoded variables without opening or reading any file.
     - Line 115: `CustomAssertions.assert_true(read_speed_mbps > 500.0)` — Tautology: Evaluates `1000.0 > 500.0` (`True`).
   - `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`:
     - Line 84: `CustomAssertions.assert_equal(len(audio_buffer), MIN_BUFFER_SIZE)` where `audio_buffer` is initialized to `[0] * MIN_BUFFER_SIZE`.

4. **Tautological Exit Code Checks**:
   - `tests/e2e/tier2_boundary_corner/test_m3_tier2.py`:
     - Lines 15-52: 38 instances of `CustomAssertions.assert_equal(res.exit_code, 0)` checking dummy command output without inspecting process side-effects or state.

5. **Static Dict SELinux Policy Checks**:
   - `tests/e2e/tier3_cross_feature/test_pairwise_matrix.py`:
     - Line 140: `CustomAssertions.assert_in("gimp.desktop", self.mock_env.installed_desktop_apps)` checking Python dict populated during setup.

---

## 2. Logic Chain

1. **Problem Identification**: The current E2E test suite does not perform real integration testing. The tests instantiate `MockEnv` from `tests/e2e/framework/mock_env.py`, mutate in-memory state variables, or perform static text searches in Java/C++/Shell source code files.
2. **Root Cause**: The test runner (`runner.py`) and individual test modules were built as facades that simulate passes without invoking real system calls, sockets (`/dev/socket/linux_bridge`, AF_VSOCK), or binaries (`crosvm`, `checkpolicy`, `avbtool`).
3. **Impact**: Under Milestone M6 (R6), these tautological checks allow broken or unimplemented features to pass CI undetected.
4. **Remediation Strategy**: Replace all mock-based and text-matching assertions with real functional assertions:
   - Unix Domain Socket (`LocalSocket`) frame sends & receives (`/dev/socket/linux_bridge`).
   - AF_VSOCK socket frame sends & receives (Ports 5000, 5001, 5002).
   - Real binary execution & exit code checks (`checkpolicy` for SELinux policies, `avbtool` for AVB signatures).
   - Real File I/O benchmarking (`os.write`, `os.read`, `time.perf_counter()`).
   - Real IPC AIDL invocation checks.

---

## 3. Detailed File-by-File Replacement Strategy

### Tier 1: Feature Coverage (`tests/e2e/tier1_feature_coverage/`)

#### 1. `test_m1_tier1.py` (M1: Real AVF VM Launch)
- **Current Tautologies**: `self.mock_env.start_vm()` sets `vm_state = "RUNNING"`; `assert self.mock_env.system_server.vm_state == "RUNNING"`.
- **Replacement Strategy**:
  ```python
  # REAL REPLACEMENT: Connect to unix domain socket and issue CMD_VM_START (0x0001)
  import socket, struct

  sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
  sock.connect("/dev/socket/linux_bridge")
  # Send 8-byte header: magic 0x414f, cmd 0x0001 (VM_START), len 4, payload RAM=4096MB
  pkt = struct.pack(">HHII", 0x414F, 0x0001, 4, 4096)
  sock.sendall(pkt)

  resp = sock.recv(16)
  magic, resp_cmd, status, cid = struct.unpack(">HHII", resp)
  assert magic == 0x414F, "Invalid response magic"
  assert resp_cmd == 0x0003, "Expected CMD_HANDSHAKE_COMPLETE (0x0003)"
  assert status == 0, f"VM start failed with status {status}"
  assert cid > 2, f"Invalid guest CID returned: {cid}"
  ```

#### 2. `test_m2_tier1.py` (M2: Production Guest Agent Loop)
- **Current Tautologies**: `self.mock_env.vsock.send_packet()` appends packet object to Python list; `assert len(ctrl_pkts) == 1`.
- **Replacement Strategy**:
  ```python
  # REAL REPLACEMENT: Real AF_VSOCK socket connection and HMAC Auth Handshake
  import socket, struct, hmac, hashlib

  # Fallback to localhost TCP test port if AF_VSOCK not available in container
  sock = socket.socket(getattr(socket, 'AF_VSOCK', socket.AF_INET), socket.SOCK_STREAM)
  sock.connect((guest_cid, 5000))

  secret = b"aosp_linux_hmac_secret_key_32b!"
  nonce = os.urandom(16)
  token = hmac.new(secret, nonce, hashlib.sha256).digest()

  sock.sendall(nonce + token)
  res = sock.recv(4)
  status_code = struct.unpack(">I", res)[0]
  assert status_code == 0x200, f"Authentication failed, status={status_code}"
  ```

#### 3. `test_m3_tier1.py` (M3: Real Vsock Socket Connect & Session ID)
- **Current Tautologies**: `mock_env.create_terminal_session()` returns `"0123456789abcdef"`; `assert session_id == "0123456789abcdef"`.
- **Replacement Strategy**:
  ```python
  # REAL REPLACEMENT: Invoke LinuxManagerService AIDL for dynamic session ID and test PTY Vsock framing
  session_id_bytes = binder_client.createTerminalSession(80, 24)
  assert len(session_id_bytes) == 16, "Session ID must be 16 raw bytes"
  assert session_id_bytes != b"0123456789abcdef", "Session ID must be dynamically generated"

  # Test PTY VsockFraming over socket port 5001
  pty_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
  pty_sock.connect(("127.0.0.1", 5001))
  # Frame header: 16-byte session ID + 2-byte cmd (DATA=0x01) + 2-byte len
  payload = "echo hello\n".encode("utf-8")
  frame = session_id_bytes + struct.pack(">HH", 0x01, len(payload)) + payload
  pty_sock.sendall(frame)

  resp_frame = pty_sock.recv(1024)
  resp_sid = resp_frame[:16]
  assert resp_sid == session_id_bytes, "Response session ID mismatch"
  ```

#### 4. `test_m4_tier1.py` (M4: Real Wayland dma-buf & SurfaceControl Binding)
- **Current Tautologies**: `sommelier.commit_frame()` increments dictionary counter; `assert committed_frames == 1`.
- **Replacement Strategy**:
  ```python
  # REAL REPLACEMENT: Create real dma-buf / pipe fd and pass via SCM_RIGHTS Unix socket
  import socket, array

  r_fd, w_fd = os.pipe()
  sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
  sock.connect("/dev/socket/linux_bridge")

  # Send SCM_RIGHTS file descriptor
  cmsg = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [r_fd]).tobytes())]
  hdr = struct.pack(">II", surface_id, 0x01) # COMMIT_FRAME opcode
  sock.sendmsg([hdr], cmsg)

  res = sock.recv(8)
  status = struct.unpack(">I", res[:4])[0]
  assert status == 0, "SurfaceControl transaction commit failed"
  os.close(r_fd); os.close(w_fd)
  ```

#### 5. `test_m5_tier1.py` (M5: Real System Hardware Portals & Storage)
- **Current Tautologies**: Math tautology `read_speed_mbps = (100 * 1024 * 1024 / 0.1) / (1024 * 1024)`; `assert read_speed_mbps > 500`.
- **Replacement Strategy**:
  ```python
  # REAL REPLACEMENT: Execute real file I/O write & read benchmarking on virtiofs mount
  import time, os

  test_path = "/mnt/shared/test_perf_10mb.dat"
  data = os.urandom(10 * 1024 * 1024) # 10MB data block

  t0 = time.perf_counter()
  with open(test_path, "wb", buffering=0) as f:
      f.write(data)
  t1 = time.perf_counter()

  with open(test_path, "rb", buffering=0) as f:
      read_back = f.read()
  t2 = time.perf_counter()

  assert len(read_back) == len(data), "File read integrity failure"
  read_speed = (len(data) / (t2 - t1)) / (1024 * 1024)
  assert read_speed > 5.0, f"Virtiofs throughput too slow: {read_speed:.2f} MB/s"
  os.remove(test_path)
  ```

---

### Tier 2: Boundary & Corner Cases (`tests/e2e/tier2_boundary_corner/`)

#### 1. `test_m1_tier2.py` (M1 Boundary & Corner)
- **Current Tautologies**: Source code text assertions `assert_in("BOOT_TIMEOUT_MS = 15000L", content)`.
- **Replacement Strategy**: Send invalid opcode `0xFFFF` over `/dev/socket/linux_bridge`, assert daemon responds with error header `status = 0x8001 (ERR_INVALID_CMD)`. Perform rapid start/stop loop and verify no process leak.

#### 2. `test_m2_tier2.py` (M2 Boundary & Corner)
- **Current Tautologies**: Source text assertions `assert_in("/dev/kvm", content)`, `assert_in("REQ_RAM_MB=4096", content)`.
- **Replacement Strategy**: Execute `launch_vm.sh --ram -1`, capture exit code, assert `exit_code != 0` and `stderr` contains invalid parameter message.

#### 3. `test_m3_tier2.py` (M3 Boundary & Corner)
- **Current Tautologies**: 38 repeated instances of `assert_equal(res.exit_code, 0)`.
- **Replacement Strategy**: Send 65KB payload over vsock port 5001 (exceeding 16KB framing limit), assert socket receives error frame `0x8002 (ERR_PAYLOAD_TOO_LARGE)`. Open 50 simultaneous vsock connections, assert all 50 receive distinct session IDs.

#### 4. `test_m4_tier2.py` (M4 Boundary & Corner)
- **Current Tautologies**: `assert_raises(ValueError, import_dma_buf, -1)` on Python mock.
- **Replacement Strategy**: Pass closed/invalid fd `-1` over socket, assert server returns error code `EBADF / 0x8004` without crashing daemon. Trigger 100 rapid surface resize requests (`100x100` to `3840x2160`), measure process memory RSS via `ps`, assert RSS growth < 10MB.

#### 5. `test_m5_tier2.py` (M5 Boundary & Corner)
- **Current Tautologies**: Dict assertions `assert_equal(fallback_mode, (1920, 1080, 30))`, static text checks.
- **Replacement Strategy**:
  - **SELinux Neverallow Compiler Check**: Run `checkpolicy -M -b /system/etc/selinux/target_sepolicy` or `secilc` on sepolicy files. Verify exit code is `0` and no neverallow violations are found.
  - **AppOps Denial Check**: Set AppOps mode `OP_CAMERA` to `MODE_IGNORED` via AIDL runner, send camera portal stream request over port 5000, verify server returns `0x403 (ERR_PERMISSION_DENIED)`.

---

### Tier 3: Cross-Feature Pairwise Matrix (`tests/e2e/tier3_cross_feature/test_pairwise_matrix.py`)

- **Current Tautologies**: 40 pairwise test classes (`TestT3Pair01` to `TestT3Pair40`) relying on `self.mock_env` state mutations.
- **Replacement Strategy**: Re-write all 40 pairwise tests to execute real multi-service socket/IPC sequences:
  - **Pair 01 (Shutdown + LUKS Unmount)**: Issue `CMD_VM_STOP` on unix socket, verify `/dev/mapper/linux_user_data` is unmounted via `/proc/mounts`.
  - **Pair 09 (SELinux Domain + Neverallow)**: Execute `checkpolicy` verifying `system_server` -> `linux_bridge_t` transition allowed, `untrusted_app` -> `linux_bridge_t` denied.
  - **Pair 28 (EROFS + AVB Validation)**: Generate sample EROFS image with `mkfs.erofs`, sign with `avbtool add_hash_footer`, run `avbtool verify_image`, verify signature validity.

---

### Tier 4: Real-World Scenarios (`tests/e2e/tier4_real_world/test_scenarios.py`)

- **Current Tautologies**: 20 scenario test classes (`TestScenario01` to `TestScenario20`) mutating `self.mock_env` lists and dicts.
- **Replacement Strategy**: Convert all 20 scenario classes to execute real end-to-end integration workflows:
  - **Scenario 01 (Cold Boot Debian VM)**: Start daemon, send `CMD_VM_START` on `/dev/socket/linux_bridge`, await port 5000 vsock socket connection, complete HMAC handshake, verify status `RUNNING`.
  - **Scenario 02 (CJK IME Input Pipeline)**: Connect vsock port 5001, send IME注音 input frames (`"你好"`), read PTY stream output, verify UTF-8 encoded text buffer.
  - **Scenario 07 (Camera Streaming Portal)**: Request camera stream over portal port 5000, verify frame header format `MJPEG` / `YUV420`, read frame payload, assert payload size > 0.
  - **Scenario 14 (SELinux Domain Denial)**: Attempt access to `/dev/block/by-name/userdata` from `linux_bridge_t` domain, verify access blocked with `EACCES` / `EPERM`.

---

## 4. Caveats

1. **Test Environment Requirements**:
   - Real socket assertions (`AF_UNIX` `/dev/socket/linux_bridge` and `AF_VSOCK` ports 5000-5002) require the `linux_bridge` native daemon or a live test stub server running during E2E test execution.
   - SELinux policy checks (`checkpolicy`, `secilc`) require `checkpolicy` binary installed on the build host or test runner container.
   - AVB checks require `avbtool` and `mkfs.erofs` installed in the test runner environment.
2. **Platform Fallbacks**:
   - If `AF_VSOCK` is not supported by the host Linux kernel (e.g. standard macOS host without virtio-vsock driver), the test runner must support a TCP loopback fallback mode (`127.0.0.1:5000..5002`) while preserving the exact binary packet framing.

---

## 5. Conclusion

The E2E test suite across Tiers 1-4 currently relies heavily on tautological string matching, source code text searching, and in-memory mock dictionary assertions. By replacing `mock_env.py` and mock-based assertions with real Unix Domain Socket (`LocalSocket`) frames, AF_VSOCK socket framing, real SELinux policy binary compilation (`checkpolicy`), real file I/O benchmarking, and real AIDL IPC calls, the test suite will become a clean, honest, and rigorous verification system for Milestone M6 (R6).

---

## 6. Verification Method

To verify the test suite after implementing the replacement strategy:

1. **Run E2E Test Runner**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --tier 2 --tier 3 --tier 4
   ```
2. **Verify SELinux Policy Check Command**:
   ```bash
   checkpolicy -M -b /system/etc/selinux/target_sepolicy
   ```
3. **Verify AVB Signature Check Command**:
   ```bash
   avbtool verify_image --image /path/to/test_erofs.img
   ```
4. **Verify Live Socket Response**:
   ```bash
   python3 -c "import socket; s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); s.connect('/dev/socket/linux_bridge'); print('Connected successfully')"
   ```
