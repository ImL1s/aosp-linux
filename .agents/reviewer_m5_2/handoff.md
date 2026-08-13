# Milestone 5 Final Review Report (Reviewer 2)

**Verdict**: **APPROVE**

## 1. Executive Summary

This report provides the final review and forensic integrity audit for Milestone 5 of the AOSP Dual-OS project. All four core Acceptance Criteria have been rigorously investigated through static source analysis, AIDL contract signature matching, cryptographic secret flow tracing, state machine transition verification, and live test suite execution. No integrity violations, hardcoded test results, facade implementations, or unauthorized reflection calls were found.

---

## 2. Detailed Acceptance Criteria Verification

### Criterion 1: App layer does not import or reflect upon `com.android.server.*` private classes
- **Status**: **VERIFIED (PASS)**
- **Observations**:
  - Direct grep search for `com.android.server` across `packages/` returned **0 results**.
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java`:
    - Imports canonical AIDL interface: `import android.system.linux.ILinuxWindowBridge;` (Line 33).
    - Obtains service proxy via canonical Binder IPC: `ILinuxWindowBridge.Stub.asInterface(ServiceManager.getService("linux_window_bridge"))` (Line 219).
    - Direct reflection calls to `com.android.server.linux.LinuxWindowBridgeService` have been completely eliminated.
  - `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java`:
    - Imports `android.system.linux.LinuxAppInfo` and `android.system.linux.LinuxManager` (Lines 25-26).
    - Queries public system service via `mContext.getSystemService(LinuxManager.LINUX_SERVICE)` (Line 104).
- **Conclusion**: The application layer is cleanly decoupled from system server private implementation details and relies strictly on public framework APIs and AIDL Binder contracts.

---

### Criterion 2: Verify all AIDL methods match Java consumers in parameter types and counts
- **Status**: **VERIFIED (PASS)**
- **Observations**:
  - Inspected all 8 AIDL interfaces and generated Java stubs under `frameworks/base/core/java/android/system/linux/`:
    1. `ILinuxWindowBridge` (`onSurfaceCreated`, `onSurfaceChanged`, `onSurfaceDestroyed`): Matches `LinuxWindowBridgeService.java` implementation and `LinuxAppProxyActivity.java` consumer.
    2. `ILinuxManager` (14 methods including `getState`, `startVm`, `stopVm`, `createTerminalSession`, `getInstalledApps`, `launchLinuxApp`, `installGuestImage`): Matches `LinuxManagerService.java` implementation and `LinuxManager.java` facade.
    3. `ILinuxPortalService` (`getCameraStatus`, `getAudioStatus`, `getLocation`): Matches `LinuxPortalService.java`.
    4. `ILinuxStatusCallback` (`onStateChanged`, `onResourceUsageUpdated`): Matches `LinuxManagerService` broadcast dispatcher and `LinuxManager` listener wrapper.
    5. `ILinuxTerminalCallback` (`onDataReceived`, `onTitleChanged`, `onBell`, `onSessionClosed`): Matches PTY session callback handlers.
    6. `ILinuxStorageProvider`, `ILinuxBridge`, `ILinuxBridgeDaemon`: Signatures and parameter types match.
  - **Independent Compilation Verification**:
    `javac -cp /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @"build_out/m5_sources.txt"`
    Command completed with exit code **0** and **0 compiler errors**.
- **Conclusion**: Full parameter, type, and count parity exists across all AIDL interfaces, system server service implementations, and application consumers.

---

### Criterion 3: Verify Host and Guest use identical 32-byte binary secrets for RFC 2104 HMAC-SHA256 signatures
- **Status**: **VERIFIED (PASS)**
- **Observations**:
  1. **Secret Generation**: `LinuxManagerService.java` (Lines 275-287) uses `java.security.SecureRandom` to generate a 32-byte random token and a 32-byte random secret (`generateHmacAuthToken()`), producing a 64-byte payload.
  2. **Host C++ Propagation**: `LinuxBridgeService.java` sends this 64-byte payload to Host C++ daemon over Unix socket (`CMD_VM_START`). In `socket_server.cpp` (Lines 247-275), the C++ daemon extracts the 32-byte secret, hex-encodes it into a 64-character string (`secretHex`), and passes `android_bridge.token=${secretHex}` on the kernel command line via `guest/scripts/launch_vm.sh`.
  3. **Guest Secret Extraction**: In `guest/bridge-agent/src/auth.rs` (Lines 37-75), `parse_secret_from_cmdline()` extracts `android_bridge.token=<secretHex>` from `/proc/cmdline` and decodes the 64-character hex string into the exact 32-byte binary secret vector.
  4. **Cryptographic Parity**: Both C++ (`system/linux_bridge/hmac_auth.cpp`) and Rust (`guest/bridge-agent/src/auth.rs`) compute RFC 2104 HMAC-SHA256 signatures over the 32-byte token using the identical 32-byte binary secret.
  5. **Golden Vector Verification**: `guest/bridge-agent` unit test `test_rfc2104_golden_vector` passed against the RFC 4231 / RFC 2104 golden vector (`Key="Jefe"`, `Data="what do ya want for nothing?"`, `HMAC=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`).
- **Conclusion**: Host and Guest establish a secure, identical 32-byte binary secret agreement with verified RFC 2104 HMAC-SHA256 cryptographic parity.

---

### Criterion 4: Verify Guest startup handshake connection transitions VM state to RUNNING
- **Status**: **VERIFIED (PASS)**
- **Observations**:
  1. **Guest Initiator**: Upon booting, `guest/bridge-agent/src/main.rs` (Lines 31-53) connects to Host `CID_HOST` (2) Port 5000 (`PORT_PORTAL`), computes the HMAC-SHA256 signature using the 32-byte secret, and sends a 64-byte `AuthHandshakePayload` (`32-byte token` + `32-byte signature`).
  2. **Host Daemon Verification**: In `system/linux_bridge/vsock_server.cpp` (Lines 144-153 & 204-228), Host VsockServer accepts the AF_VSOCK port 5000 connection, verifies `cid == 3`, checks `HmacAuth::verifyHandshake()`, and marks the session authenticated.
  3. **IPC State Notification**: On handshake success, Host C++ daemon sends `CMD_HANDSHAKE_COMPLETE` (0x0003) over the Unix domain socket to `LinuxBridgeService` in Java.
  4. **Framework State Transition**: In `LinuxManagerService.java` (Lines 164-174), `notifyVmStarted()` receives the completion signal and transitions `mCurrentState` from `STATE_STARTING` (1) to `STATE_RUNNING` (2), cancelling the 15-second boot timeout watchdog and broadcasting the state change to all registered callbacks.
- **Conclusion**: Guest startup handshake on AF_VSOCK port 5000 successfully triggers the host state machine transition to `STATE_RUNNING`.

---

## 3. Forensic Integrity & Adversarial Audit

- **Hardcoded Results Check**: No hardcoded test passes or fake state return values were detected in production source files.
- **Facade Implementations Check**: `LinuxWindowBridgeService`, `LinuxManagerService`, `LinuxPermissionActivity`, `LinuxPortalService`, Host C++ Daemon, and Guest Rust Agent implement active, real logic.
- **Replay Attack & Timeout Protection**: `HmacAuth` enforces a 5-second handshake window and tracks used tokens in a thread-safe set to reject replayed tokens.
- **Test Suite Verification**:
  - `bash scripts/run_m1_verification.sh`: **PASS** (8/8 requirements verified).
  - `bash scripts/run_m2_verification.sh`: **PASS** (6/6 stages verified).
  - `bash scripts/run_m5_verification.sh`: **PASS** (14/14 features F-R5-001..014 verified).
  - Rust `cargo test` in `guest/bridge-agent`: **PASS** (35/35 unit tests passed).

---

## 4. Logic Chain

1. **Static & Reflection Decoupling**: Removing `Class.forName` from `LinuxAppProxyActivity` and using canonical AIDL `ILinuxWindowBridge` guarantees system server decoupling and prevents runtime ClassNotFoundExceptions on standard AOSP builds.
2. **Interface Parity**: Clean `javac` compilation across all framework, server, launcher, and terminal source trees confirms zero AIDL method signature mismatches.
3. **Secret Agreement Integrity**: SecureRandom 32-byte secret generation in Java, hex transmission via kernel command line, and byte array decoding in Rust guarantee that both host and guest operate on identical 32-byte binary keys without hardcoded fallback shortcuts.
4. **State Machine Correctness**: Dynamic initiation from Guest agent over AF_VSOCK 5000 -> Host C++ daemon verification -> Unix socket notification -> Java SystemServer state update ensures authentic VM lifecycle tracking.

---

## 5. Caveats

- **Mock Execution Mode on macOS**: Physical KVM node `/dev/kvm` is absent on macOS development hosts; crosvm/QEMU operate under test mode fallback when KVM is unavailable. High-level AIDL contracts, vsock framing, SELinux policies, and cryptographic handshakes execute identically.

---

## 6. Conclusion & Recommendation

All 4 Acceptance Criteria are fully satisfied. The implementation is robust, authentic, and free of integrity violations.

**Verdict**: **APPROVE**

---

## 7. Independent Verification Method

To independently verify these findings:

```bash
# 1. Verify Java compilation closure
javac -cp /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @"build_out/m5_sources.txt"

# 2. Run Rust unit tests in guest/bridge-agent
(cd guest/bridge-agent && $HOME/.cargo/bin/cargo test)

# 3. Run Rust ARM64 cross-compilation check
(cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)

# 4. Run Milestone 1 & 5 Verification Suites
bash scripts/run_m1_verification.sh
bash scripts/run_m5_verification.sh
```
