# Investigation & Handoff Report — Explorer 2 (Focus Area 2: Task Lifecycle & Window Management)

## 1. Observation

### 1.1 Codebase Audit Findings
- **Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`
- **Service Directory**: `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/`
  - Existing files observed via `list_dir`: `LinuxBridgeService.java`, `LinuxCeKeyManager.java`, `LinuxManagerInternal.java`, `LinuxManagerService.java`.
  - **Absence**: `LinuxWindowBridgeService.java` does NOT exist yet.
- **Application Directory**: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`
  - Existing files observed via `find_by_name`: `TerminalActivity.java`, `TerminalView.java`, `ime/*`, `net/*`, `parser/*`, `renderer/*`, `touch/*`.
  - **Absence**: `LinuxAppProxyActivity.java` and window resize handlers do NOT exist yet.
- **Test Infrastructure**:
  - `tests/e2e/tier1_feature_coverage/test_m4_tier1.py` lines 143-281 contain functional test assertions for F-R4-003 (`T1-96` to `T1-100`) and F-R4-004 (`T1-101` to `T1-105`).
  - `tests/e2e/tier2_boundary_corner/test_m4_tier2.py` lines 184-380 contain boundary tests for F-R4-003 (`T2-96` to `T2-100`) and F-R4-004 (`T2-101` to `T2-105`).
  - `tests/e2e/runner.py` executed successfully via `run_tests.sh` with 430/430 tests passing (100% pass rate in mock harness).

### 1.2 Required Specifications (from Blueprint & spec.md)
- **F-R4-003 (LinuxAppProxyActivity Task ID)**:
  - Each forwarded Linux GUI window must launch a dedicated `LinuxAppProxyActivity` with `Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK`.
  - Android Recents overview screen must display discrete app cards with title and icon bitmap derived from Wayland/`.desktop` metadata.
  - Swiping away an app card in Recents must trigger an `xdg_toplevel.close` signal over Vsock Port 5002 to send `SIGTERM` to the guest app PID.
  - Launching an app already running must bring the existing Task ID to front (`FLAG_ACTIVITY_REORDER_TO_FRONT`).
  - Concurrent task limit of 20 active tasks must be enforced; exceeding returns an error.
  - VM state transition to `STATE_OFF` / shutdown must flush active task registries cleanly.
- **F-R4-004 (Freeform Multi-Window Resize)**:
  - Freeform windowing mode must support resizable window drag handles.
  - Resize events must trigger `xdg_toplevel.configure` events sent over Vsock Port 5002 to Guest Sommelier.
  - Live window resizing must implement dynamic frame pacing and debouncing to prevent buffer queue flooding.
  - Window dimensions must be clamped between minimum (320x240 px) and maximum (screen bounds) constraints, taking display density (DPI) into account (`px = dp * scale`).
  - Fixed-ratio apps must preserve aspect ratio during resize.

---

## 2. Logic Chain

1. **Observation**: The system architecture plan and `PROJECT.md` assign F-R4-003 and F-R4-004 to Milestone M4, requiring discrete Task IDs for Linux GUI windows and resizable freeform windowing support.
2. **Observation**: `grep_search` and `find_by_name` confirm `LinuxWindowBridgeService.java` and `LinuxAppProxyActivity.java` are missing from `frameworks/base/services/core/java/com/android/server/linux/` and `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/`.
3. **Logic Step**: To satisfy F-R4-003, `LinuxWindowBridgeService.java` must be created in SystemServer to maintain a Vsock 5002 surface registry, allocate discrete Task IDs, and spawn `LinuxAppProxyActivity` instances.
4. **Logic Step**: `LinuxAppProxyActivity.java` must be created in `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/` to host the forwarded `SurfaceControl`/`SurfaceView` and set up `ActivityManager.TaskDescription` with title and icon bitmap.
5. **Logic Step**: To satisfy F-R4-004, `LinuxAppProxyActivity` must override `onConfigurationChanged` and `onMultiWindowModeChanged`, converting window bounds from dp to px, clamping dimensions (320x240 min, max screen bounds), preserving aspect ratio, and delegating to a `WindowResizePacer` debouncer handler.
6. **Logic Step**: `WindowResizePacer` throttles high-frequency drag resize events to match display frame pacing (~16ms rate limit), sending `CMD_WAYLAND_CONFIGURE_SURFACE` over Vsock 5002 while retaining the last valid frame in `SurfaceControl` until guest redraw completes.
7. **Conclusion**: Implementation requires 3 new production Java files (`LinuxWindowBridgeService.java`, `LinuxAppProxyActivity.java`, `WindowResizePacer.java`), updates to `AndroidManifest.xml`, 2 unit test files (`LinuxWindowBridgeServiceTest.java`, `LinuxAppProxyActivityTest.java`), and integration into `scripts/run_m4_verification.sh`.

---

## 3. Caveats

- **Mock Execution vs Real HW**: Current E2E tests run against a mock environment (`tests/e2e/framework/mock_env.py`). Hardware `SurfaceControl` zero-copy dma-buf buffer import requires native Android `HardwareBuffer` APIs (`AHardwareBuffer_importAndAcquire`) on device targets.
- **Scope Boundary**: Focus Area 1 (Wayland proxy & virtio-gpu dma-buf) is owned by Explorer 1; Focus Area 3 (`.desktop` Inotify & Launcher3 synthetic shortcuts) is owned by Explorer 3. Explorer 2 focuses strictly on Task ID lifecycle, Recents overview mapping, and Freeform resize frame pacing handlers.

---

## 4. Conclusion & Proposed Architecture

### 4.1 Target File Map

| Component | Target File Path | Description |
|-----------|------------------|-------------|
| **Window Bridge Service** | `frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java` | SystemServer service managing Wayland Vsock 5002 surface registry, Task IDs, and Recents overview mapping. |
| **App Proxy Activity** | `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java` | Discrete Android Activity hosting forwarded Linux GUI window surface. |
| **Resize Frame Pacer** | `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/window/WindowResizePacer.java` | Debouncer and frame pacing handler for live freeform resize drag operations. |
| **App Manifest** | `packages/apps/LinuxTerminal/AndroidManifest.xml` | Declares `LinuxAppProxyActivity` with multi-window & freeform activity flags. |
| **Unit Test: Window Bridge** | `tests/unit/LinuxWindowBridgeServiceTest.java` | Unit test suite for Task ID allocation, task reuse, max task limit, and VM shutdown flush. |
| **Unit Test: Proxy Activity** | `tests/unit/LinuxAppProxyActivityTest.java` | Unit test suite for freeform resize, bounds clamping, DPI scaling, and aspect ratio preservation. |

---

## 5. Verification Method

### 5.1 Compilation Verification Command
```bash
find frameworks/base/core/java frameworks/base/services/core/java packages/apps/LinuxTerminal/src tests/unit -name "*.java" > build_out/sources.txt
javac -d build_out/classes @build_out/sources.txt
```

### 5.2 Unit Test Verification Command
```bash
java -cp build_out/classes tests.unit.LinuxWindowBridgeServiceTest
java -cp build_out/classes tests.unit.LinuxAppProxyActivityTest
```

### 5.3 E2E Test Verification Command
```bash
python3 tests/e2e/runner.py --tier 1 --feature F-R4-003
python3 tests/e2e/runner.py --tier 1 --feature F-R4-004
python3 tests/e2e/runner.py --tier 2 --feature F-R4-003
python3 tests/e2e/runner.py --tier 2 --feature F-R4-004
/Users/iml1s/Documents/mine/aosp-linux/tests/e2e/run_tests.sh
```
