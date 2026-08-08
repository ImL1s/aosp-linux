# Handoff Report — Code Review (Reviewer 1 / Round 3)

## 1. Observation

Direct forensic code review observations from `/Users/iml1s/Documents/mine/aosp-linux`:

1. **Host Portal AF_VSOCK & HMAC Authentication Handshake**:
   - In `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`:
     - Line 78: `private static final int VSOCK_PORTAL_PORT = 5000;`
     - Lines 157-192: `openAuthenticatedVsockChannel(int port)` executes:
       - Line 157: `FileDescriptor fd = Os.socket(40 /* AF_VSOCK */, OsConstants.SOCK_STREAM, 0);`
       - Line 167: `Os.connect(fd, address);` (where `address` is `VmSocketAddress(port, guestCid)`).
       - Lines 172-188: Generates 16-byte random challenge (`new SecureRandom().nextBytes(challenge)`), computes 32-byte HMAC-SHA256 (`Mac.getInstance("HmacSHA256")`), transmits challenge + signature to guest, reads response, and validates `AUTH_OK`.
     - Zero instances of `new Socket("localhost", ...)` remain in `LinuxPortalService.java` or `VsockPortalClient.java`.
   - In `frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java`:
     - Line 40: `private static final int AF_VSOCK = 40;`
     - Line 77: `mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);`
     - Line 78: `VmSocketAddress address = new VmSocketAddress(VSOCK_PORTAL_PORT, guestCid);`
     - Lines 84-102: Implements 16-byte random challenge + 32-byte HMAC-SHA256 signature authentication handshake before setting `mConnected = true`.

2. **Binary Camera Frame Payload Structure**:
   - In `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`:
     - Lines 861-879 (`sendVsockCameraFramePayload`):
       ```java
       ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 4 + 8 + 4 + nv21Bytes.length);
       buf.order(ByteOrder.BIG_ENDIAN);
       buf.putInt(0x43414D46);          // SubType "CAMF" (4 bytes)
       buf.putInt(width);               // width (4 bytes)
       buf.putInt(height);              // height (4 bytes)
       buf.putInt(ImageFormat.NV21);    // format 0x11 (4 bytes)
       buf.putLong(timestampNs);        // timestamp (8 bytes)
       buf.putInt(nv21Bytes.length);    // payloadSizeBytes (4 bytes)
       buf.put(nv21Bytes);              // NV21 pixel bytes
       getVsockPortalClient().sendPortalFrame((byte) 0x01, buf.array());
       ```
     - Replaced legacy text string `"CAM_FRAME:/dev/video0:..."` with genuine binary metadata header (`MAGIC = 0x43414D46`) + YUV pixel array payload.

3. **Guest Portal Host Event Consumption, Global State, and Subsystem Checks**:
   - In `guest/bridge-agent/src/portal.rs`:
     - Lines 104-108: `pub static GLOBAL_PORTAL_STATE: OnceLock<Arc<RwLock<PortalState>>> = OnceLock::new();`
     - Lines 279-318 (`handle_portal_session`): Intercepts Host portal events (`HostPortalEvent::Location`, `HostPortalEvent::Camera`, `HostPortalEvent::Audio`, and untagged location/camera/audio JSON updates) to dynamically update `GLOBAL_PORTAL_STATE`.
     - Lines 155-202 (`dispatch_portal_request_with_state`): Reads from `GLOBAL_PORTAL_STATE`. Returns `PortalResponse::ok` with cached event data if present, or `PortalResponse::err` ("Camera/Audio/Location unavailable") if absent.
     - Zero hardcoded mock coordinates (`0.0`, `"mock"`) or static mock JSON responses remain in `portal.rs`.

4. **Independent Test Execution**:
   - Cargo Unit Tests: Executed `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml`.
     Result: **33/33 PASSED (0 failed)**.
   - E2E Test Suite: Executed `python3 tests/e2e/runner.py`.
     Result: **426/430 PASSED (0 failures, 4 environment errors due to missing Android sysfs nodes on macOS host)**.
   - Anti-Cheating Inspection: No hardcoded test result overrides or facade shortcuts were found in source code.

---

## 2. Logic Chain

1. **Host AF_VSOCK & Authentication Handshake**:
   - *Observation*: `LinuxPortalService.java` line 157 uses `Os.socket(40, SOCK_STREAM, 0)` and `VmSocketAddress(5000, guestCid)`. Lines 172-188 perform 16-byte challenge + 32-byte HMAC-SHA256 signature verification expecting `AUTH_OK`.
   - *Logic*: Completely eliminates localhost TCP fallback and satisfies Phase 6 requirement for host-guest portal security over native AF_VSOCK (family 40).

2. **Camera Frame Binary Framing**:
   - *Observation*: `sendVsockCameraFramePayload` packs `0x43414D46` (`CAMF` magic) header with width, height, format (`ImageFormat.NV21`), timestamp, payload length, and raw NV21 bytes.
   - *Logic*: Satisfies Phase 6 requirement to replace string descriptors (`CAM_FRAME:...`) with binary frame header + YUV pixel array payload.

3. **Guest Event Consumption & Mock Coordinate Purge**:
   - *Observation*: `portal.rs` maintains `GLOBAL_PORTAL_STATE`, demuxes Host location/camera/audio events, and serves RPC requests from cached state. Purged all static coordinates (`latitude: 0.0`, `"mock"`).
   - *Logic*: Satisfies Phase 6 requirement for Guest portal event consumption and dynamic status reporting.

4. **Integrity & Code Quality**:
   - *Observation*: Code calls real Android framework APIs (`CameraManager`, `AudioRecord`, `LocationManager`, `AppOpsManager`). `cargo test` passes 33/33. No hardcoded test results or facade shortcuts present.
   - *Logic*: Work product passes all correctness, quality, and anti-cheating criteria.

---

## 3. Caveats

- On non-Android host environments (such as macOS desktop), hardware system calls to `CameraManager`, `AudioRecord`, or `/sys/class/power_supply` will fall back or raise environment errors as required by Rule 7, because native Linux/Android kernel nodes are absent. This is expected behavior for hardware-isolated real environment tests.

---

## 4. Conclusion

**Verdict: APPROVE**

The code changes in `LinuxPortalService.java`, `VsockPortalClient.java`, and `portal.rs` meet all technical specifications, replace TCP localhost sockets with native AF_VSOCK (family 40) + HMAC authentication, stream binary camera frames (`MAGIC = 0x43414D46`) + YUV pixel arrays, consume Host events into `GLOBAL_PORTAL_STATE` in Guest `portal.rs` with zero hardcoded mock coordinates, and contain no integrity violations.

---

## 5. Verification Method

To independently verify these findings:

1. **Verify Host AF_VSOCK & HMAC in Java**:
   ```bash
   grep -n "localhost" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
   grep -n "AF_VSOCK" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
   ```
   *Expected Result*: 0 matches for "localhost", multiple matches for AF_VSOCK (family 40) socket setup and HMAC verification.

2. **Verify Camera Binary Frame Header**:
   ```bash
   grep -n "0x43414D46" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
   ```
   *Expected Result*: Match in `sendVsockCameraFramePayload` packing binary `CAMF` header.

3. **Verify Guest Portal Rust Agent Unit Tests**:
   ```bash
   $HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml
   ```
   *Expected Result*: 33 passed; 0 failed.

4. **Verify Guest Portal Mock Coordinate Purge**:
   ```bash
   grep -nE "(mock|0\.0)" guest/bridge-agent/src/portal.rs
   ```
   *Expected Result*: 0 matches for mock coordinate strings or hardcoded 0.0 location coordinates.
