# Handoff Report — Remediation Design for Defect 1 & Defect 2

**Agent**: `teamwork_preview_explorer_r3_1`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_r3_1`  
**Target Milestone**: Round 3 Victory Audit Remediation — Defect 1 & Defect 2  

---

## 1. Observation

### Focus Area 1: Stand-in Stub Classes (Defect 1 / Req 1 / Rule 3)

Direct inspection of the repository codebase revealed three stand-in stub files shadowing canonical framework and SDK classes:

1. **Stub Class 1**: `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` (Lines 1–14):
   ```java
   package android.system.linux;

   public class LinuxManager {
       public static final int STATE_STOPPED = 0;
       public static final int STATE_RUNNING = 1;

       public int getState() {
           return STATE_STOPPED;
       }

       public void startVm() {}
       public void stopVm() {}
   }
   ```
   - **Canonical Replacement**: Exists at `frameworks/base/core/java/android/system/linux/LinuxManager.java` (343 lines), defining full `ILinuxManager` AIDL IPC calls, callback registrations, and VM state management.
   - **App Usage**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java` (Line 5: `import android.system.linux.LinuxManager;`).
   - **Build Configuration**: `packages/apps/LinuxTerminal/Android.bp` (Line 47: `platform_apis: true`). When the local stub file is purged, the build system resolves `android.system.linux.LinuxManager` to the canonical framework class defined in `Android.bp` (`java_sdk_library` `"android.system.linux"`).

2. **Stub Class 2**: `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` (Lines 1–44):
   ```java
   package android.graphics;

   public class Rect {
       public int left;
       public int top;
       public int right;
       public int bottom;
       ...
   }
   ```
   - **Canonical Replacement**: Standard Android SDK framework class `android.graphics.Rect`.
   - **App Usage**: `TerminalScreenMatrix.java` (Line 3), `NativeSurfaceCanvasRenderer.java` (Line 6), `TerminalSurfaceView.java` (Line 4).
   - **Build Configuration**: Shadowed canonical SDK `android.graphics.Rect`. Purging this local stub file forces all renderer classes to link directly against genuine Android framework `android.graphics.Rect`.

3. **Stub Class 3**: `frameworks/base/core/java/android/util/Slog.java` (Lines 17–29):
   ```java
   package android.util;

   public final class Slog {
       private Slog() {}
       public static int v(String tag, String msg) { return Log.v(tag, msg); }
       ...
   }
   ```
   - **Canonical Replacement**: Canonical AOSP system server logger `android.util.Slog`.
   - **Framework Usage**: `LinuxManagerService.java` (Line 14), `LinuxPortalService.java` (Line 37), `LinuxStorageProvider.java`, `LinuxWindowBridgeService.java`, `LinuxBridgeService.java`, `LinuxPermissionActivity.java`.
   - **Patch File Alignment**: `patches/aosp_frameworks_base.patch` adds canonical integration hooks to `Context.java`, `SystemServiceRegistry.java`, `SystemServer.java`, and `AndroidManifest.xml`. Purging `frameworks/base/core/java/android/util/Slog.java` aligns framework logging with standard AOSP system logging.

---

### Focus Area 2: Auth & VSOCK Contract Mismatch (Defect 2 / Req 3)

Direct inspection of `guest/bridge-agent` Rust agent, host C++ bridge, and Python test harness revealed contract mismatches:

1. **Guest Agent Auth (`guest/bridge-agent/src/auth.rs`)**:
   - Lines 57–77: `verify_token` performs simple raw token byte comparison.
   - Line 80 & Line 161: `sha256` and `HmacSha256` struct/methods are annotated with `#[allow(dead_code)]` and never executed in production authentication flow.
   - Lines 224–253 (`perform_handshake`): Reads `secret.len()` raw bytes from stream, calls `verify_token(&token_buf, secret)`, and returns `"AUTH_OK\n"` or `"AUTH_FAILED\n"`.

2. **Host C++ Auth (`system/linux_bridge/hmac_auth.cpp` & `vsock_framing.h`)**:
   - `vsock_framing.h` (Lines 50–53): Defines `AuthHandshakePayload` containing 32-byte `token` (random challenge nonce) + 32-byte `signature` (`HMAC-SHA256(Secret, Token)`), totaling 64 bytes.
   - `hmac_auth.cpp` (Lines 236–270): `verifyHandshake` calculates `expectedSig = computeHmacSha256(secret, payloadToken)` and performs constant-time comparison against `payload.signature`.

3. **Guest PTY Teardown Deadlock (`guest/bridge-agent/src/pty.rs`)**:
   - Lines 277–281: `handle_pty_session` calls `child.kill()`, `child.wait()`, then attempts `reader_handle.join()` *before* `drop(pty)`. Because `reader_handle` is blocked on `libc::read(master_read_fd, ...)` which only unblocks when `master_write_fd` is closed, calling `reader_handle.join()` before dropping `pty` causes a deadlock during teardown, leading to `cargo test` failures (`exit code 101`).

4. **Socket Harness TCP Fallback (`tests/e2e/framework/socket_harness.py`)**:
   - Lines 87–93: `RealVsockBridge.send` opens IPv4 TCP `127.0.0.1:port` connection.
   - Lines 133–147: `RealVsockBridge.create_port_socket` falls back to `socket.AF_INET` loopback sockets if `AF_VSOCK` fails.
   - Lines 223–247: `SocketHarnessServer.start()` binds `socket.AF_INET` listeners on `127.0.0.1` for ports 5000, 5001, 5002, 15000, 15001, 15002.

---

## 2. Logic Chain

1. **Stand-in Stub Class Purge (Defect 1)**:
   - *Observation A.1–A.3* shows three stub files (`LinuxManager.java` in app, `Rect.java` in app, `Slog.java` in framework) that duplicate/shadow platform classes.
   - Removing `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java` and `packages/apps/LinuxTerminal/src/android/graphics/Rect.java` forces Java source files in `LinuxTerminal` (`TerminalActivity.java`, `TerminalScreenMatrix.java`, etc.) to import genuine framework/SDK classes.
   - Removing `frameworks/base/core/java/android/util/Slog.java` ensures framework services (`LinuxManagerService.java`, etc.) compile against canonical AOSP system logging.

2. **RFC 2104 HMAC-SHA256 Auth Contract Wiring (Defect 2)**:
   - *Observation B.1–B.2* shows Host C++ expects a 64-byte `AuthHandshakePayload` (32B challenge token + 32B HMAC-SHA256 signature), while Guest Rust `auth.rs` expected raw token bytes and left `HmacSha256` as dead code.
   - Updating `perform_handshake` in `auth.rs` to read 64 bytes (`[token (32B) | signature (32B)]`), reject all-zero tokens, compute `expected_sig = HmacSha256::compute_hmac_response(secret, &token)`, and compare using constant-time check enforces full RFC 2104 contract parity between Host C++ and Guest Rust.
   - Removing `#[allow(dead_code)]` from `HmacSha256` and `sha256` ensures active execution of crypto verification in production code.

3. **Guest PTY Teardown Fix**:
   - *Observation B.3* shows `handle_pty_session` in `pty.rs` deadlocking because `reader_handle.join()` is called while `master_write_fd` is still open.
   - Reordering teardown steps in `pty.rs` to close master FDs before joining `reader_handle` resolves the deadlock, allowing `cargo test` to execute cleanly with zero failures.

4. **Socket Harness Fallback Elimination**:
   - *Observation B.4* shows `socket_harness.py` creating TCP `127.0.0.1` sockets for ports 5000, 5001, 5002, 15000, 15001, 15002.
   - Stripping `socket.AF_INET` loopback bindings from `create_port_socket`, `SocketHarnessServer.start()`, and `RealVsockBridge.send()`, and throwing an explicit exception when `AF_VSOCK` is missing, enforces strict compliance with Requirement 3 and Non-Negotiable Rule 5.

---

## 3. Caveats

No caveats. All findings were established through direct source code inspection of `packages/apps/LinuxTerminal`, `frameworks/base`, `guest/bridge-agent`, `system/linux_bridge`, and `tests/e2e/framework`.

---

## 4. Conclusion & Step-by-Step Remediation Design

### Remediation Plan for Defect 1: Stand-in Stub Classes Purge

#### Step 1.1: Purge Stub Files
Delete the following 3 files completely from the repository:
1. `packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java`
2. `packages/apps/LinuxTerminal/src/android/graphics/Rect.java`
3. `frameworks/base/core/java/android/util/Slog.java`

#### Step 1.2: Validate Imports in Affected Components
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalActivity.java`:
  - Line 5: `import android.system.linux.LinuxManager;` (Resolves to `frameworks/base/core/java/android/system/linux/LinuxManager.java`).
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalScreenMatrix.java`:
  - Line 3: `import android.graphics.Rect;` (Resolves to `android.graphics.Rect` SDK).
- `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/NativeSurfaceCanvasRenderer.java`:
  - Line 6: `import android.graphics.Rect;` (Resolves to `android.graphics.Rect` SDK).
- `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`:
  - Line 14: `import android.util.Slog;` (Resolves to AOSP framework `android.util.Slog`).

---

### Remediation Plan for Defect 2: Auth & VSOCK Contract Mismatch

#### Step 2.1: Implement RFC 2104 HMAC-SHA256 Verification in `guest/bridge-agent/src/auth.rs`

1. **Remove `verify_token` and `#[allow(dead_code)]`**:
   Replace raw token comparison in `auth.rs` with `constant_time_compare`:
   ```rust
   /// Constant-time byte array comparison to prevent timing attacks.
   pub fn constant_time_compare(a: &[u8], b: &[u8]) -> bool {
       if a.len() != b.len() || a.is_empty() {
           return false;
       }
       let mut diff = 0u8;
       for (&x, &y) in a.iter().zip(b.iter()) {
           diff |= x ^ y;
       }
       diff == 0
   }
   ```

2. **Wire `HmacSha256` into `perform_handshake`**:
   Update `perform_handshake` in `guest/bridge-agent/src/auth.rs` (Lines 224–253):
   ```rust
   pub fn perform_handshake<S: Read + Write + SetReadTimeout>(stream: &mut S, secret: &[u8]) -> bool {
       if secret.is_empty() {
           return false;
       }

       // Set 5-second socket read timeout
       let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));

       // Read AuthHandshakePayload (64 bytes: 32B token + 32B signature)
       let mut payload = [0u8; 64];
       if stream.read_exact(&mut payload).is_err() {
           let _ = stream.set_read_timeout(None);
           return false;
       }

       let (token, signature) = payload.split_at(32);

       // Reject all-zero challenge token
       if token.iter().all(|&b| b == 0) {
           let _ = stream.write_all(b"AUTH_FAILED\n");
           let _ = stream.flush();
           let _ = stream.set_read_timeout(None);
           return false;
       }

       // Compute expected RFC 2104 HMAC-SHA256 signature over challenge token
       let expected_sig = HmacSha256::compute_hmac_response(secret, token);

       // Constant-time signature verification
       if !constant_time_compare(signature, &expected_sig) {
           let _ = stream.write_all(b"AUTH_FAILED\n");
           let _ = stream.flush();
           let _ = stream.set_read_timeout(None);
           return false;
       }

       if stream.write_all(b"AUTH_OK\n").is_err() || stream.flush().is_err() {
           let _ = stream.set_read_timeout(None);
           return false;
       }

       // Reset read timeout after successful authentication
       let _ = stream.set_read_timeout(None);
       true
   }
   ```

3. **Add Golden Vector Unit Test to `auth.rs`**:
   ```rust
   #[test]
   fn test_rfc2104_hmac_sha256_golden_vector() {
       // RFC 2104 Test Vector for HMAC-SHA256
       let secret = b"key";
       let challenge = b"The quick brown fox jumps over the lazy dog";
       let hmac = HmacSha256::compute_hmac_response(secret, challenge);
       assert_eq!(hmac.len(), 32);
       // Verify against expected hex
       let expected_hex = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
       let computed_hex = hmac.iter().map(|b| format!("{:02x}", b)).collect::<String>();
       assert_eq!(computed_hex, expected_hex);
   }
   ```

#### Step 2.2: Fix Teardown Deadlock in `guest/bridge-agent/src/pty.rs`
In `pty.rs` (Lines 277–281), ensure `master_read_fd` and `master_write_fd` (pty) are closed before joining `reader_handle`:
```rust
let _ = child.kill();
let _ = child.wait();
drop(pty); // Close master PTY FD to unblock libc::read in reader thread
let _ = reader_handle.join();
```

#### Step 2.3: Remove IPv4 TCP `127.0.0.1` Fallbacks in `tests/e2e/framework/socket_harness.py`

1. **`RealVsockBridge.create_port_socket` (Lines 133–147)**:
   ```python
   def create_port_socket(self, port: int) -> socket.socket:
       """
       Creates AF_VSOCK socket. Strict compliance mode: prohibits TCP 127.0.0.1 fallback.
       """
       if hasattr(socket, "AF_VSOCK"):
           try:
               sock = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
               _apply_socket_options(sock)
               return sock
           except OSError as e:
               raise OSError(f"AF_VSOCK creation failed on port {port}: {e}. TCP fallback prohibited.")
       raise NotImplementedError(f"AF_VSOCK is not supported on this platform. TCP fallback prohibited.")
   ```

2. **`SocketHarnessServer.start` (Lines 223–247)**:
   Remove `socket.AF_INET` loopback bindings for ports 5000, 5001, 5002, 15000, 15001, 15002. Rely solely on Unix domain sockets (`/dev/socket/linux_bridge`) and authentic `AF_VSOCK` sockets.

3. **`RealVsockBridge.send` (Lines 85–96)**:
   Remove IPv4 TCP connection block (`socket.AF_INET`, `127.0.0.1`).

---

## 5. Verification Method

To independently verify the implementation of Defect 1 and Defect 2 remediation fixes:

1. **Verify Stub Classes Purged**:
   ```bash
   # Confirm files are deleted
   ls packages/apps/LinuxTerminal/src/android/system/linux/LinuxManager.java 2>&1
   ls packages/apps/LinuxTerminal/src/android/graphics/Rect.java 2>&1
   ls frameworks/base/core/java/android/util/Slog.java 2>&1
   # Output must report: No such file or directory
   ```

2. **Verify Guest Agent Cargo Build and Tests Pass**:
   ```bash
   cd guest/bridge-agent && $HOME/.cargo/bin/cargo test
   # Expected result: 0 test failures (Exit code 0)
   ```

3. **Verify E2E Unit Tests Pass**:
   ```bash
   python3 -m unittest discover -s tests/unit
   # Expected result: All unit tests pass cleanly without TCP fallbacks
   ```
