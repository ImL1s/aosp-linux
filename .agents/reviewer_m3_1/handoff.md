# Review Report — Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator)

**Reviewer**: reviewer_m3_1  
**Verdict**: **APPROVE**  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m3_1`  

---

## 1. Observation

### 1.1 Source Code Inspection Observations

1. **Host Java System Server Services**:
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`:
     - Lines 275–287 (`generateHmacAuthToken()`): Uses `SecureRandom` to generate a 32-byte binary token and a 32-byte binary secret. Combines both into a 64-byte payload array (`payload[0..31]` = token, `payload[32..63]` = secret) and sets `mActiveAuthToken` and `mActiveAuthSecret`.
     - Lines 429–432 (`startVm()`): Obtains the 64-byte payload from `generateHmacAuthToken()` and forwards it via `mBridgeService.notifyVmStarting(authToken)`.
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`:
     - Lines 290–292 (`notifyVmStarting(byte[] authPayload)`): Sends `CMD_VM_START` (0x0001) containing the 64-byte `authPayload` over Unix Domain Socket (`/dev/socket/linux_bridge`).

2. **Host C++ Bridge Daemon**:
   - `system/linux_bridge/socket_server.cpp`:
     - Lines 239–256: Inspects the incoming 64-byte payload on `CMD_VM_START`. Extracts `token` from `payload[0..31]` and `secret` from `payload[32..63]`. Calls `mVsockServer->setAuthToken(token, secret)` and hex-encodes the 32-byte secret into a 64-character hex string `secretHex`.
     - Line 273: Invokes `execlp("bash", "bash", scriptPath, configPath, secretHex.c_str(), nullptr)` passing `secretHex` as argument 2.
   - `system/linux_bridge/vsock_server.cpp` & `hmac_auth.cpp`:
     - `vsock_server.cpp` (lines 144–153): Accepts AF_VSOCK connections on control port 5000, reads 64-byte `AuthHandshakePayload`, and calls `processHandshake(cid, payload)`.
     - `hmac_auth.cpp` (lines 236–270): Verifies `payloadToken`, checks replay status using `isTokenUsed()`, computes expected RFC 2104 HMAC-SHA256 signature using `computeHmacSha256(secret, payloadToken)`, and performs constant-time byte comparison (`constantTimeCompare`). On success, triggers `SocketServer::onVsockHandshakeSuccess(cid)` which transmits `CMD_HANDSHAKE_COMPLETE` (0x0003) back to Java framework.

3. **VM Launch Script**:
   - `guest/scripts/launch_vm.sh`:
     - Lines 6 & 81: Captures `$2` as `AUTH_TOKEN` (64-char hex string) and constructs kernel command line `CMDLINE="... android_bridge.token=${AUTH_TOKEN} ..."`.
     - Lines 87–120: Passes `CMDLINE` directly to `crosvm` (`--params`) or `qemu` (`-append`).

4. **Guest Rust Agent**:
   - `guest/bridge-agent/src/auth.rs`:
     - Lines 28–32 & 55–74 (`parse_secret_from_cmdline`): Scans `/proc/cmdline` for `android_bridge.token=`, `linux_auth_secret=`, or `auth_secret=`.
     - Lines 37–51 (`decode_hex_or_raw`): Decodes 64 ASCII hex characters into the exact 32-byte binary secret.
     - Lines 187–213 (`HmacSha256::compute_hmac_response`): Implements pure Rust RFC 2104 HMAC-SHA256 computation over the secret key.
   - `guest/bridge-agent/src/main.rs`:
     - Lines 29–54: Implements active startup initiator behavior. Upon agent boot, extracts secret and connects to Host `CID_HOST=2` Port 5000 via `VsockStream::connect(VMADDR_CID_HOST, PORT_PORTAL)`. Computes 32-byte HMAC-SHA256 signature over token payload, constructs 64-byte payload, and sends it over AF_VSOCK socket.
     - Lines 59–84: Initializes multi-threaded listener loop binding ports 5000 (Portal), 5001 (PTY), and 5002 (Wayland).
   - `guest/bridge-agent/src/vsock.rs`:
     - Lines 35–71 (`VsockStream::connect`): Provides cross-platform AF_VSOCK socket connect implementation.

---

### 1.2 Verification Command Results

1. **Guest Agent Cargo Check (`aarch64-unknown-linux-gnu`)**:
   - Command: `cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`
   - Result: `Finished dev profile [unoptimized + debuginfo] target(s) in 0.01s` (Exit code: 0, **0 warnings, 0 errors**).
   - Command: `cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu`
   - Result: `Finished dev profile [unoptimized + debuginfo] target(s) in 0.01s` (Exit code: 0, **0 warnings, 0 errors**).

2. **Java Framework & Services Compilation**:
   - Command: `javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt`
   - Result: Exit code 0, **0 compilation errors**.

3. **Java Unit Test Suite**:
   - Command: `java -cp /tmp/m3_classes:... tests.unit.TerminalAppUnitTest`
   - Result: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` (**8/8 unit tests passed**).

4. **Host C++ Native Test Suite**:
   - Command: `./build_out/bin/linux_bridge_test`
   - Result: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` (**50/50 unit tests passed**).

5. **Python E2E Tier 1 & Tier 2 Test Suites**:
   - Commands: `python3 tests/e2e/runner.py --tier 1 --feature F-R3-001..007` & `--tier 2 --feature F-R3-001..007`
   - Result: **100.0% PASS RATE** across all 70 test cases (35 Tier 1 + 35 Tier 2).

---

## 2. Logic Chain

1. **End-to-End Single-Secret Agreement**:
   - Host Java `LinuxManagerService` generates a 32-byte token and 32-byte secret via cryptographically secure RNG (`SecureRandom`).
   - Host C++ daemon receives both in a single 64-byte IPC payload, stores the binary secret in `mVsockServer`, and hex-encodes it into a 64-character hex string.
   - `launch_vm.sh` propagates `android_bridge.token=<64_hex_chars>` via kernel command line.
   - Guest Agent `auth.rs` parses `/proc/cmdline` and decodes the 64 hex characters into the exact 32-byte binary secret matching the host.

2. **Startup Initiator Flow & VM State Transition**:
   - On boot, Guest Agent `main.rs` extracts the secret and actively connects to Host `CID_HOST=2` on control port 5000 over `AF_VSOCK`.
   - Guest transmits the 64-byte `AuthHandshakePayload` (32-byte token + 32-byte HMAC-SHA256 signature).
   - Host C++ `vsock_server.cpp` validates the payload using `HmacAuth::verifyHandshake()`, ensuring constant-time signature comparison and replay protection.
   - Upon verification success, Host C++ sends `CMD_HANDSHAKE_COMPLETE` to Java framework, triggering transition of VM state to `STATE_RUNNING`.

3. **Build Cleanliness & Security**:
   - Cross-compilation target `aarch64-unknown-linux-gnu` compiles cleanly with zero warnings or errors.
   - Constant-time comparison logic is used on both Rust and C++ sides to eliminate timing side-channels.
   - Replay protection table (`sUsedTokens`) prevents re-use of authentication tokens.

---

## 3. Caveats

- **No caveats**: All Milestone 3 acceptance criteria have been verified with complete source code inspection and empirical command execution. No integrity violations, dummy implementations, or fake test passes were detected.

---

## 4. Conclusion

Milestone 3 (R3 Single-Secret HMAC Agreement & Handshake Initiator) is fully verified and compliant with all project requirements.

**Verdict**: **APPROVE**

---

## 5. Verification Method

To independently verify this review:

1. **Guest Agent ARM64 Compilation**:
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   (cd guest/portal-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   ```
   *Expected output*: Exit code 0, 0 warnings, 0 errors.

2. **Java Compilation & Unit Tests**:
   ```bash
   mkdir -p build_out/classes
   find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src -name "*.java" > build_out/sources.txt
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d build_out/classes @build_out/sources.txt
   javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
   *Expected output*: `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY`.

3. **Host C++ Daemon Compilation & Unit Tests**:
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```
   *Expected output*: `NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY`.

4. **E2E Tier 1 & Tier 2 Test Runner**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --feature F-R3-001
   python3 tests/e2e/runner.py --tier 2 --feature F-R3-001
   ```
   *Expected output*: 100% PASS RATE.
