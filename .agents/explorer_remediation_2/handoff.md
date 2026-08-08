# Phase B Audit Findings Remediation Report — Cheating, Simulated Executions, Dead HMAC Code & Facades

## 1. Observation (對象觀察事實)

Direct code evidence was inspected across the project codebase to locate cheating mechanisms, simulated execution fallbacks, dead HMAC authentication code, fake mock test frameworks, and facade service implementations.

### Item 1: `guest/scripts/launch_vm.sh`
- **Line 76**:
  ```bash
  if [ ! -c /dev/kvm ] && [ "${TEST_MODE:-0}" != "1" ]; then
      echo "ERROR: KVMException: /dev/kvm not found or insufficient permission" >&2
      exit 1
  fi
  ```
  - *Observation*: Checks if `/dev/kvm` exists, but if `TEST_MODE=1` is passed, it bypasses the `/dev/kvm` hardware requirement completely instead of returning `BLOCKED` or exit code 1.
- **Lines 100-107**:
  ```bash
  else
      echo "[Launch Script] crosvm binary not in PATH (Simulated execution mode)"
      if [ "${TEST_MODE:-0}" = "1" ]; then
          exec sleep 3600
      fi
  fi

  echo "[Launch Script] VM launch script completed successfully."
  ```
  - *Observation*: If `crosvm` is missing from `PATH` and `TEST_MODE=1` is set, the script executes `sleep 3600` to fake a running VM process. If `TEST_MODE` is unset or 0, it prints `[Launch Script] crosvm binary not in PATH (Simulated execution mode)` and exits with status `0` (Line 107), deceptively reporting success despite launching no VM.

### Item 2: `guest/bridge-agent/src/auth.rs`
- **Lines 161-192**:
  ```rust
  /// HMAC-SHA256 signature calculator for authentication challenge responses.
  #[allow(dead_code)]
  pub struct HmacSha256;

  impl HmacSha256 {
      #[allow(dead_code)]
      pub fn compute_hmac_response(secret: &[u8], challenge: &[u8]) -> Vec<u8> { ... }
  }
  ```
  - *Observation*: `HmacSha256` struct and `compute_hmac_response` function are marked with `#[allow(dead_code)]` and are never called in any authentication workflow.
- **Lines 224-253**:
  ```rust
  pub fn perform_handshake<S: Read + Write + SetReadTimeout>(stream: &mut S, secret: &[u8]) -> bool {
      ...
      let mut token_buf = vec![0u8; secret.len()];
      if stream.read_exact(&mut token_buf).is_err() { ... return false; }
      if !verify_token(&token_buf, secret) {
          let _ = stream.write_all(b"AUTH_FAILED\n");
          ...
          return false;
      }
      if stream.write_all(b"AUTH_OK\n").is_err() || stream.flush().is_err() { ... return false; }
      true
  }
  ```
  - *Observation*: `perform_handshake` reads raw secret bytes directly from the stream into `token_buf` and performs raw byte equality comparison (`token_buf == secret` in `verify_token`), returning `AUTH_OK\n` or `AUTH_FAILED\n`. Plaintext secret bytes are passed over socket transport while HMAC-SHA256 is entirely bypassed.

### Item 3: `tests/e2e/framework/socket_harness.py`
- **Lines 107-120**:
  ```python
  def create_port_socket(self, port: int) -> socket.socket:
      """
      Creates AF_VSOCK socket if available, otherwise falls back to TCP loopback (127.0.0.1).
      """
      if hasattr(socket, "AF_VSOCK"):
          try:
              sock = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
              return sock
          except OSError:
              pass
      # Fallback to TCP loopback socket
      sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
      sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
      return sock
  ```
  - *Observation*: If `socket.AF_VSOCK` is missing or raises `OSError` (e.g. non-AVF host environment), `create_port_socket` silently catches the exception and falls back to an IPv4 TCP socket on `127.0.0.1`.
- **Lines 51, 72-79, 196-220**: Binds TCP loopback sockets on ports 5000, 5001, 5002, 15000, 15001, 15002 pretending to be AF_VSOCK connections.

### Item 4: `tests/e2e/framework/real_env.py` & Test Suite
- **Hardcoded Return Constants in `real_env.py`**:
  - **Lines 88-89**: `verify_vts_kernel_compliance()` returns hardcoded `True`.
  - **Lines 91-92**: `verify_cts_verifier_compatibility()` returns hardcoded `"PASS"`.
  - **Lines 94-95**: `measure_cts_idle_power_drop()` returns hardcoded `1.4`.
  - **Lines 149-150**: `export_dma_buf()` returns hardcoded `42`.
  - **Lines 152-153**: `import_dma_buf()` returns hardcoded dict `{"id": 2001, "source_fd": source_fd, "width": 1920, "height": 1080, "imported": True}`.
  - **Lines 158-159**: `measure_zero_copy_latency()` returns hardcoded `8.5`.
  - **Lines 193-197**: `request_location_access()` returns hardcoded dict `{"latitude": 25.0330, "longitude": 121.5654, "accuracy": 5.0}`.
  - **Lines 205-206**: `get_pcm_audio_stream_chunk()` returns hardcoded bytes `b"\x00\x7f" * 512`.
  - **Lines 248, 303**: `cts_results` returns static dict `{"passed": 170, "failed": 0}`.
  - **Lines 331, 371**: `measure_virtiofs_read_speed()` returns `1200.0`, `measure_erofs_read_throughput()` returns `245.0`.
- **Self-Certifying Tests in `tests/e2e/`**:
  - Example in `tests/e2e/tier1_feature_coverage/test_m1_tier1.py` (lines 41-43):
    ```python
    self.mock_env.system_server.registered_services[service_name] = "LinuxManager"
    CustomAssertions.assert_in(service_name, self.mock_env.system_server.registered_services)
    CustomAssertions.assert_equal(self.mock_env.system_server.registered_services[service_name], "LinuxManager")
    ```
  - Tests directly write expected strings into python in-memory dictionaries (`mock_env.system_server.registered_services` or `installed_desktop_apps`), and immediately assert the existence of the value just set (self-certifying memory mocks).

### Item 5: Facade Implementations in `LinuxManagerService.java`
- **File**: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`
- **Lines 555-566 (`getInstalledApps()`)**:
  ```java
  @Override
  public List<LinuxAppInfo> getInstalledApps() {
      if (mContext != null) {
          mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to get installed apps");
      }
      if (mBridgeService != null) {
          return mBridgeService.getCachedAppList();
      }
      List<LinuxAppInfo> apps = new ArrayList<>();
      apps.add(new LinuxAppInfo("org.gnome.Terminal", "Terminal", "gnome-terminal", "/usr/share/icons/terminal.png", "text/plain"));
      apps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox Web Browser", "firefox", "/usr/share/icons/firefox.png", "text/html"));
      return apps;
  }
  ```
  - *Observation*: If `mBridgeService` is null, returns a static hardcoded list (`org.gnome.Terminal`, `org.mozilla.firefox`) instead of returning an empty list or querying the guest agent.
- **Lines 569-578 (`launchLinuxApp()`)**:
  ```java
  @Override
  public boolean launchLinuxApp(String appId, int displayId) {
      if (mContext != null) {
          mContext.enforceCallingOrSelfPermission(PERMISSION_USE_LINUX_TERMINAL, "Permission denied to launch Linux app");
      }
      Slog.i(TAG, "Launching Linux App: " + appId + " on display: " + displayId);
      if (mBridgeService != null && mBridgeService.isConnected()) {
          return mBridgeService.launchApp(appId, displayId);
      }
      return true;
  }
  ```
  - *Observation*: If `mBridgeService` is null or disconnected, returns `true` directly, faking app launch success when no connection exists.
- **Lines 580-587 (`installGuestImage()`)**:
  ```java
  @Override
  public boolean installGuestImage(ParcelFileDescriptor imageFd, long size) {
      if (mContext != null) {
          mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE_LINUX, "Permission denied to install guest image");
      }
      Slog.i(TAG, "Installing guest image size: " + size + " bytes");
      return true;
  }
  ```
  - *Observation*: Completely ignores `imageFd` and `size`, performs no I/O operations, and returns `true` unconditionally.

---

## 2. Logic Chain (推論邏輯鏈)

1. **Rule Violation Analysis**:
   - `ORIGINAL_REQUEST.md` Rule 5: *"No TEST_MODE, simulated-success path, localhost TCP fallback, swallowed transport exception, or prebuilt artifact may count toward production verification."*
   - Rule 7: *"Missing KVM, AVF, crosvm, AOSP tree, device, camera, mic, or location hardware means BLOCKED. It does not mean PASS."*
   - Requirement R3 & Phase 3: Require real HMAC-SHA256 challenge/response verification during vsock handshake, eliminating raw token equality.

2. **Impact of Current Implementations**:
   - **`launch_vm.sh`**: Bypassing KVM checks and executing `sleep 3600` under `TEST_MODE=1` causes CI test environments without ARM64 KVM/crosvm to falsely report that a Debian Guest VM launched successfully.
   - **`auth.rs`**: Passing raw auth secrets over vsock streams while marking `HmacSha256` as `#[allow(dead_code)]` fails the Phase 3 protocol specification requirement and leaks authentication secrets.
   - **`socket_harness.py`**: Falling back to TCP `127.0.0.1` sockets masks missing `AF_VSOCK` kernel support, violating Rule 5.
   - **`real_env.py` & `tests/e2e/`**: Returning hardcoded strings (`"PASS"`, `1.4`, `42`, GPS coordinates, static `cts_results`) and self-asserting python memory variables allows test suites to pass 100% without executing any real hardware, kernel, or daemon code.
   - **`LinuxManagerService.java`**: Facade methods returning fixed app lists or `true` when disconnected allow host API calls to pretend success when the guest subsystem is completely dead or uninstalled.

3. **Conclusion Alignment**:
   - To achieve genuine production readiness, every simulated fallback, dead code annotation, TCP fallback, hardcoded return value, self-certifying mock assertion, and service facade must be replaced with real kernel/system/daemon operations or fail-fast error handling.

---

## 3. Caveats (注意事項與未檢驗範圍)

- **Read-Only Scope**: This report is an investigation and remediation plan. No source code modifications were committed during this exploration phase.
- **Hardware Limitations**: Real execution of KVM/crosvm and AF_VSOCK requires a physical ARM64 device or QEMU ARM64 instance with nested KVM enabled. On host environments lacking KVM/VSOCK kernel modules, proper fail-fast behavior will correctly yield `BLOCKED` / exit code failures rather than fake `PASS`.

---

## 4. Conclusion (最終結論與各檔案詳細修復策略)

All 5 audited items contain unacceptable cheating mechanisms, facade returns, or dead code. The comprehensive fix strategy for each file is defined below.

### Remediation Strategy for File 1: `guest/scripts/launch_vm.sh`
1. **Remove `TEST_MODE` References**: Delete all occurrences of `TEST_MODE` and `sleep 3600`.
2. **Fail-Fast Device Checks**:
   ```bash
   if [ ! -c /dev/kvm ]; then
       echo "ERROR: KVMException: /dev/kvm hardware device node not available" >&2
       exit 1
   fi
   ```
3. **Fail-Fast Binary Checks**:
   ```bash
   if ! command -v crosvm >/dev/null 2>&1; then
       echo "ERROR: CrosvmNotFound: crosvm binary not found in PATH" >&2
       exit 4
   fi
   ```
4. **Execution & Child Error Propagation**: Always use `exec crosvm run ...`. Ensure any launch error returns non-zero directly to `linux_bridge` so `waitpid` captures the error and dispatches `CMD_VM_START_FAILED`.

---

### Remediation Strategy for File 2: `guest/bridge-agent/src/auth.rs`
1. **Remove Dead Code Annotations**: Remove `#[allow(dead_code)]` from `sha256`, `HmacSha256`, and `compute_hmac_response`.
2. **Implement Real HMAC-SHA256 Challenge/Response Handshake**:
   - Server (`bridge-agent`) reads 16-byte challenge `nonce` generated by Host, or Server generates 16-byte `nonce` and sends to Host.
   - Client sends 32-byte HMAC signature: `signature = HMAC-SHA256(secret, nonce)`.
   - Server computes `expected = HmacSha256::compute_hmac_response(secret, &nonce)`.
   - Server performs constant-time byte verification `verify_token(&signature, &expected)`.
3. **Non-fatal Bad Client Handling**: Handshake failure must close the client connection stream and return `false`, without calling `std::process::exit(1)` for the entire server daemon.

---

### Remediation Strategy for File 3: `tests/e2e/framework/socket_harness.py`
1. **Eliminate TCP Loopback Fallback**:
   ```python
   def create_port_socket(self, port: int) -> socket.socket:
       if not hasattr(socket, "AF_VSOCK"):
           raise VsockUnavailableError("AF_VSOCK is not supported on this platform")
       return socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
   ```
2. **Strict Environment Status**: When `AF_VSOCK` is unavailable, return `BLOCKED` status in the test runner per Rule 7 instead of routing test traffic over `127.0.0.1` TCP sockets.

---

### Remediation Strategy for File 4: `tests/e2e/framework/real_env.py` & `tests/e2e/`
1. **Remove All Hardcoded Return Constants**:
   - `verify_vts_kernel_compliance()`: Inspect `/proc/config.gz` or `uname -r` for actual kernel capabilities.
   - `export_dma_buf()` / `import_dma_buf()`: Perform actual `/dev/dma_heap` or `GraphicBuffer` FD allocations. Raise `EnvironmentError` if dma-heap is missing.
   - `request_location_access()`: Query real Android `LocationManager` or `LocationListener` via binder / portal. Return error/blocked if location provider is unavailable.
   - `get_pcm_audio_stream_chunk()`: Read actual raw PCM bytes from audio hardware.
2. **Eliminate Self-Certifying Tests**: Re-write tests in `tests/e2e/` to send real IPC socket messages over `/dev/socket/linux_bridge` or AF_VSOCK and assert response payloads returned by system daemons, rather than setting and checking local python dicts.

---

### Remediation Strategy for File 5: `LinuxManagerService.java`
1. **`getInstalledApps()` Fix**:
   - Remove hardcoded fallbacks (`org.gnome.Terminal`, `org.mozilla.firefox`).
   - If VM state is not `RUNNING` or `mBridgeService` is disconnected, return `Collections.emptyList()`.
2. **`launchLinuxApp()` Fix**:
   - Check `mCurrentState == LinuxManager.STATE_RUNNING` and `mBridgeService != null && mBridgeService.isConnected()`.
   - Return `false` if VM is stopped or bridge is disconnected; never fake success (`return true`).
3. **`installGuestImage()` Fix**:
   - Implement real file streaming from `ParcelFileDescriptor` to `/data/misc/linux/base_rootfs.img.tmp`.
   - Verify total bytes written against `size`.
   - Atomic rename temp file to `/data/misc/linux/base_rootfs.img` upon successful stream completion, returning `false` on any I/O failure or size mismatch.

---

## 5. Verification Method (獨立驗證方法)

1. **Verify No `TEST_MODE` in Launch Script**:
   ```bash
   grep -n "TEST_MODE" guest/scripts/launch_vm.sh
   ```
   *Expected Outcome*: Zero lines returned.

2. **Verify HMAC Call in Bridge Agent**:
   ```bash
   grep -n "compute_hmac_response" guest/bridge-agent/src/auth.rs
   ```
   *Expected Outcome*: Function is called inside `perform_handshake`, and no `#[allow(dead_code)]` attributes remain.

3. **Verify No TCP Fallback in Socket Harness**:
   ```bash
   grep -n "AF_INET" tests/e2e/framework/socket_harness.py
   ```
   *Expected Outcome*: Zero instances of `AF_INET` fallback inside `create_port_socket`.

4. **Verify No Facade Returns in `LinuxManagerService.java`**:
   ```bash
   grep -n -C 5 "org.gnome.Terminal" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
   ```
   *Expected Outcome*: Hardcoded app strings removed from `getInstalledApps()`.

5. **Execute E2E Runner**:
   ```bash
   python3 tests/e2e/runner.py
   ```
   *Expected Outcome*: Harness validates real socket calls, and missing hardware results in `BLOCKED` status instead of fake `PASS`.
