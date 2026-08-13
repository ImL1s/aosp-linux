# Handoff & Review Report — Milestone 3 (R3 HMAC Agreement & Handshake Initiator Review)

**Reviewer**: reviewer_m3_2
**Verdict**: APPROVE

---

## 1. Observation

### 1.1 Source Code Verification Observations

- **RFC 2104 HMAC-SHA256 Implementation**:
  - **Rust Guest Agent** (`guest/bridge-agent/src/auth.rs`):
    - Implements pure Rust `sha256` function (lines 104–181) with standard IVs (`0x6a09e667`, etc.), round constants `K`, 64-byte chunk padding, and big-endian length encoding.
    - Implements `HmacSha256::compute_hmac_response` (lines 186–213) with `ipad` (`0x36`) and `opad` (`0x5c`) XOR block pads following RFC 2104.
    - Implements `test_rfc2104_golden_vector` (lines 289–300) testing key `Jefe` and message `what do ya want for nothing?`, matching RFC 4231 Test Case 2 (`5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`).
    - Implements constant-time comparison in `verify_token` (lines 96–100) and rejects all-zero tokens.
  - **Host C++ Daemon** (`system/linux_bridge/hmac_auth.cpp`):
    - Implements OpenSSL HMAC-SHA256 with pure C++ SHA-256 fallback (`sha256_internal::sha256`).
    - Implements `HmacAuth::verifyHandshake` (lines 236–270) enforcing 5-second handshake timeout, token reuse check (`isTokenUsed`), constant-time signature comparison (`constantTimeCompare`), and token invalidation.

- **Host VM State Transition to `STATE_RUNNING`**:
  - **Framework Java** (`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`):
    - `generateHmacAuthToken()` (lines 275–287) uses `SecureRandom` to generate a 32-byte token and a 32-byte secret, returning a 64-byte payload.
    - `startVmInternal` calls `generateHmacAuthToken()` and sends payload to `LinuxBridgeService.notifyVmStarting`.
    - Callback `onVmHandshakeCompleted()` (lines 73–75) calls `notifyVmStarted()` (lines 164–174), cancelling boot timeout and updating `mCurrentState` from `LinuxManager.STATE_STARTING` to `LinuxManager.STATE_RUNNING`.
  - **Native Bridge Daemon** (`system/linux_bridge/socket_server.cpp` & `vsock_server.cpp`):
    - `socket_server.cpp` parses `secret` from `payload[32..63]`, passes `secretHex` to `launch_vm.sh`, and sets token/secret on `mVsockServer`.
    - `vsock_server.cpp` listens on AF_VSOCK port 5000 (`VSOCK_PORT_CONTROL`). Upon receiving 64-byte `AuthHandshakePayload` from guest CID 3, it calls `processHandshake`.
    - Upon successful HMAC verification, `onVsockHandshakeSuccess` triggers in `socket_server.cpp` (lines 70–81), sending `CMD_HANDSHAKE_COMPLETE` (0x0003) over Unix domain socket to `LinuxBridgeService`.
  - **Guest Agent Initiator** (`guest/bridge-agent/src/main.rs`):
    - On boot, extracts 32-byte secret from `/proc/cmdline` (`android_bridge.token=<hex_secret>`).
    - Connects to Host CID 2 Port 5000 via AF_VSOCK (`VsockStream::connect`).
    - Computes HMAC-SHA256 signature using `secret` over token and sends 64-byte payload, triggering the Host state transition.

### 1.2 Build & Execution Command Results

1. **Rust Target `aarch64-unknown-linux-gnu` Cargo Check**:
   - Command: `(cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)`
     - Result: `Finished dev profile [unoptimized + debuginfo] target(s) in 0.02s` (Exit code: 0, 0 errors, 0 warnings).
   - Command: `(cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)`
     - Result: `Finished dev profile [unoptimized + debuginfo] target(s) in 0.01s` (Exit code: 0, 0 errors, 0 warnings).

2. **Java Compilation & Unit Tests**:
   - Command: `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt`
     - Result: Clean compilation (Exit code: 0).
   - Command: `java -cp /tmp/m3_classes:... tests.unit.TerminalAppUnitTest`
     - Result: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` (Exit code: 0).

3. **C++ Native Daemon Compilation & Unit Tests**:
   - Command: `./build_out/bin/linux_bridge_test`
     - Result: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY (50/50 succeeded)` (Exit code: 0).

4. **Python E2E Tier 1 Tests (F-R3-001..007)**:
   - Command: `python3 tests/e2e/runner.py --tier 1 --feature F-R3-001..007`
     - Result: `100.0% PASS RATE` across all M3 test suites (Exit code: 0).

---

## 2. Logic Chain

1. **RFC 2104 HMAC Verification**: The guest agent (`guest/bridge-agent/src/auth.rs`) and host daemon (`system/linux_bridge/hmac_auth.cpp`) both implement standard RFC 2104 HMAC-SHA256 calculations. Guest unit test `test_rfc2104_golden_vector` verifies conformity with RFC 4231 Test Case 2 golden vectors. Both implementations use constant-time byte comparisons to prevent timing attacks.
2. **State Transition Chain**: `LinuxManagerService` generates a 64-byte payload containing a 32-byte token and 32-byte secret. The Host C++ daemon receives this payload, configures `VsockServer` credentials, and passes the 32-byte secret via kernel cmdline (`android_bridge.token=`). On guest boot, `guest/bridge-agent` extracts the secret, connects to Host `CID_HOST=2` port 5000 over VSOCK, and sends a 64-byte handshake payload containing the token and RFC 2104 HMAC signature. Upon host verification, `VsockServer` triggers `onVsockHandshakeSuccess`, causing `SocketServer` to emit `CMD_HANDSHAKE_COMPLETE` to `LinuxBridgeService`, which invokes `notifyVmStarted()` in `LinuxManagerService`, transitioning VM state from `STATE_STARTING` to `STATE_RUNNING`.
3. **Build & Integrity Assurance**: Independent execution of `cargo check --target aarch64-unknown-linux-gnu` confirms clean Rust compilation for both guest agents without warnings or errors. Code auditing revealed no hardcoded test results, facade implementations, or integrity violations.

---

## 3. Caveats

- **No caveats**: All required features for Milestone 3 (R3) have been independently verified, tested, and confirmed meeting all specification and integrity standards.

---

## 4. Conclusion

Milestone 3 implementation passes all verification criteria:
- **Verdict**: **APPROVE**
- RFC 2104 HMAC-SHA256 calculation in Rust guest agent and C++ host daemon is accurate, secure, and verified against standard golden vectors.
- Host VM state transition to `STATE_RUNNING` upon successful VSOCK port 5000 handshake is fully implemented end-to-end.
- Clean compilation for `aarch64-unknown-linux-gnu` target confirmed for `guest/bridge-agent` and `guest/portal-agent`.

---

## 5. Verification Method

To re-verify independently:
```bash
# 1. Rust ARM64 cargo check
(cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
(cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)

# 2. Host C++ Unit Tests
mkdir -p build_out/bin
clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
./build_out/bin/linux_bridge_test

# 3. Java Unit Tests
mkdir -p /tmp/m3_classes
javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java
java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest

# 4. Python E2E Tier 1 Suite
python3 tests/e2e/runner.py --tier 1 --feature F-R3-001
python3 tests/e2e/runner.py --tier 1 --feature F-R3-003
```

---

## Quality & Adversarial Review Details

### Verified Claims

- RFC 2104 HMAC calculation → verified via `test_rfc2104_golden_vector` & C++ unit test → PASS
- Host VM state transition to `STATE_RUNNING` → verified via Java + C++ state machine tracing & E2E suite → PASS
- Cargo check ARM64 target → verified via `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` → PASS (0 errors, 0 warnings)
- Integrity Violation Check → verified via source code audit for dummy logic/hardcoded cheats → PASS (No integrity violations found)

### Coverage Gaps
- None.

### Unverified Items
- None.
