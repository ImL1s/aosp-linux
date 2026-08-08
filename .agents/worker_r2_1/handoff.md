# Handoff Report: Host Portal AF_VSOCK & Image Payload Developer (`worker_r2_1`)

**Agent ID**: `worker_r2_1`  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_1`  
**Date**: 2026-08-08  
**Target File**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`

---

## 1. Observation

1. **Purged Localhost TCP Sockets**:
   Verified using `grep -rn "new Socket(" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`.
   - Result: 0 matches found (all localhost TCP sockets purged).

2. **Implemented AF_VSOCK `VsockPortalClient`**:
   - `VsockPortalClient` (`frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java`) creates socket via `android.system.Os.socket(40 /* AF_VSOCK */, OsConstants.SOCK_STREAM, 0)`.
   - Connects to `new VmSocketAddress(5000, guestCid)`.
   - Encapsulates payload inside 13-byte Big-Endian VSOK framing header:
     - `VSOK_MAGIC`: `0x56534F4B` ("VSOK", 4 bytes)
     - `frameType`: `0x01` (1 byte)
     - `payloadLen`: integer (4 bytes, Big-Endian)
     - `sequenceId`: integer (4 bytes, Big-Endian)

3. **Camera Payload Conversion (`YUV_420_888` -> `NV21`) & CAMF Header**:
   - Implemented `convertYuv420ToNv21(android.media.Image image)` in `LinuxPortalService.java`.
   - Constructs binary camera frame header (`subType = 0x43414D46` "CAMF", width, height, format=ImageFormat.NV21, timestampNs, payloadSizeBytes) followed by NV21 pixel bytes.
   - Dispatches camera payloads via `VsockPortalClient.sendPortalFrame((byte) 0x01, buf.array())`.

4. **Refactored Audio (AUDO) & Location (GEOC) Payloads**:
   - Audio: `sendVsockAudioPayload(byte[] pcmData)` constructs binary header (`subType = 0x4155444F` "AUDO", `payloadSizeBytes`) followed by PCM bytes.
   - Location: `sendGeoClueLocationUpdate(double lat, double lon, float accuracy)` constructs binary header (`subType = 0x47454F43` "GEOC", `payloadSizeBytes`) followed by GeoJSON bytes.

---

## 2. Logic Chain

1. **Purging Localhost TCP Fallbacks**:
   - Previously, lines 713, 724, and 747 created standard IPv4 TCP sockets connected to `"localhost"` port 5000. This bypassed VM boundary isolation and violated Rule 5 ("No localhost TCP fallback").
   - By removing all `new Socket("localhost", ...)` calls and replacing them with `VsockPortalClient`, all portal traffic is routed through POSIX `AF_VSOCK` (`family = 40`) to `guestCid` (port 5000).

2. **13-Byte Big-Endian VSOK Framing Header**:
   - Standardized `VsockPortalClient.sendPortalFrame(frameType, payload)` packs `0x56534F4B`, `frameType`, `payloadLen`, and `sequenceId` into a 13-byte network byte order buffer before sending the payload.

3. **Real Camera Payload Processing**:
   - In `openHardwareCamera`, `ImageReader.acquireNextImage()` extracts planar YUV buffers from `android.media.Image`.
   - `convertYuv420ToNv21` converts Y, U, and V plane buffers into a contiguous NV21 byte array.
   - `sendVsockCameraFramePayload` formats the 28-byte `CAMF` binary header + NV21 payload, ensuring real pixel data is transmitted across the portal.

4. **Audio & Location SubType Binary Framing**:
   - `sendVsockAudioPayload` wraps PCM data with the 8-byte `AUDO` binary header (`0x4155444F` + `payloadSizeBytes`).
   - `sendGeoClueLocationUpdate` wraps GeoJSON bytes with the 8-byte `GEOC` binary header (`0x47454F43` + `payloadSizeBytes`).

---

## 3. Caveats

- **Mock Execution Environment**: Standalone unit testing on host JVM requires `android.util.Slog` stub (`frameworks/base/core/java/android/util/Slog.java`) for logging output without relying on Android framework JAR stubs throwing `RuntimeException: Stub!`.

---

## 4. Conclusion

All requirements for `LinuxPortalService.java` refactoring are complete:
- Zero occurrences of `new Socket("localhost", ...)` remain in `LinuxPortalService.java`.
- POSIX `AF_VSOCK` (family 40) socket connection via `VmSocketAddress(5000, guestCid)` and 13-byte VSOK framing header are fully integrated.
- `YUV_420_888` -> `NV21` conversion and binary `CAMF` header framing implemented for camera portal.
- Binary `AUDO` and `GEOC` framing implemented for audio and location portals.
- All unit tests (`LinuxPortalServiceTest`, `ChallengerM5Iter2EmpiricalTest`) and Python E2E portal tests (F-R5-001, F-R5-002, F-R5-003) compile and pass 100%.

---

## 5. Verification Method

To independently verify these changes, run the following commands from workspace root (`/Users/iml1s/Documents/mine/aosp-linux`):

1. **Verify No Localhost TCP Sockets Remain**:
   ```bash
   grep -rn "new Socket(" frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java
   ```
   *Expected Output*: 0 lines returned (empty).

2. **Compile Java Services & Run Unit Tests**:
   ```bash
   mkdir -p build_out/classes && javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java -d build_out/classes frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java tests/unit/LinuxPortalServiceTest.java tests/unit/ChallengerM5Iter2EmpiricalTest.java && java -cp build_out/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.LinuxPortalServiceTest && java -cp build_out/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.ChallengerM5Iter2EmpiricalTest
   ```
   *Expected Output*:
   - `PASS: LinuxPortalServiceTest executed successfully.`
   - `EMPIRICAL VERIFICATION SUMMARY: 10 PASSED, 0 FAILED out of 10 TESTS.`

3. **Run Python E2E Portal Tests**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --feature F-R5-001 && python3 tests/e2e/runner.py --tier 1 --feature F-R5-002 && python3 tests/e2e/runner.py --tier 1 --feature F-R5-003
   ```
   *Expected Output*: `100.0% PASS RATE` for all features.
