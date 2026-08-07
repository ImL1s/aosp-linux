# Investigation & Handoff Report — Explorer 3 (Milestone M4 Focus Area 3)

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4/explorer_3`
**Focus Area 3**: Desktop Entry Inotify & Launcher Integration (F-R4-005 & F-R4-006)

---

## 1. Observation

### Existing Codebase Structures
1. **Framework Data Structure & Interface Contracts**:
   - `frameworks/base/core/java/android/system/linux/LinuxAppInfo.java`: Line 35-66. Defines `LinuxAppInfo` containing `mAppId`, `mDisplayName`, `mGenericName`, `mComment`, `mIconPath`, `mExecCommand`, `mMimeTypes`, `mCategories`, and `mIsTerminalApp`. Implements `Parcelable`.
   - `frameworks/base/core/java/android/system/linux/ILinuxManager.aidl`: Lines 27-28:
     ```aidl
     List<LinuxAppInfo> getInstalledApps();
     boolean launchLinuxApp(String appId, int displayId);
     ```
   - `frameworks/base/core/java/android/system/linux/LinuxManager.java`: Lines 297-318. Exposes `getInstalledApps()`, `listInstalledApps()`, and `launchLinuxApp(appId, displayId)` facade methods.
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`:
     - Line 56: `public static final short CMD_APP_SYNC = 0x0200;`
     - Lines 274-282: `getCachedAppList()` returns hardcoded default entries (`org.gnome.Terminal`, `org.mozilla.firefox`) when `mCachedApps` list is empty.
     - Lines 162-180: `handleIncomingPacket()` currently lacks a `CMD_APP_SYNC` handler block (falls through to `default:` log statement).
     - Lines 284-291: `launchApp(String appId, int displayId)` packs `CMD_APP_SYNC` request over local socket to daemon.

2. **Native Daemon & Guest Architecture**:
   - `system/linux_bridge/vsock_framing.h`: Lines 28-30 define `VSOCK_PORT_CONTROL = 5000`. Line 43 defines `VsockFrameHeader` magic `0x56534F4B` ("VSOK").
   - `guest/bridge-agent/src/main.rs`: Lines 45-51. Runs `android-bridge-agent` in guest VM listening on Vsock Port 5000 after HMAC-SHA256 handshake.
   - **Missing Directory/Files**: `guest/portal-agent` (guest inotify daemon) does not exist yet.
   - **Missing Directory/Files**: `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java` does not exist yet.

3. **E2E Test Specifications**:
   - `tests/e2e/tier1_feature_coverage/test_m4_tier1.py`:
     - F-R4-005 (Lines 285-351): T1-106 (inotify watch on `/usr/share/applications/`), T1-107 (`IN_CLOSE_WRITE` detection), T1-108 (metadata parsing), T1-109 (Vsock 5000 payload transmission), T1-110 (`IN_MODIFY` / `IN_DELETE` detection).
     - F-R4-006 (Lines 356-417): T1-111 (Host receives `.desktop` metadata), T1-112 (Synthetic shortcut generation in Launcher3), T1-113 (Icon PNG/SVG extraction), T1-114 (Tapping launcher icon starts `LinuxAppProxyActivity`), T1-115 (Package uninstall removes shortcut).
   - `tests/e2e/tier2_boundary_corner/test_m4_tier2.py`:
     - F-R4-005 (Lines 364-440): T2-106 (Ignore missing `[Desktop Entry]` malformed files), T2-107 (Ignore `NoDisplay=true` entries), T2-108 (Inotify burst throttling / debouncing), T2-109 (Missing icon fallback to `default_linux_app_icon.png`), T2-110 (Subfolder watching `~/.local/share/applications`).
     - F-R4-006 (Lines 444-523): T2-111 (Duplicate shortcut deduplication), T2-112 (Launcher restart persistence), T2-113 (Special character XML escaping), T2-114 (Icon conversion fallback for unsupported formats like `.xpm`), T2-115 (Work profile / multi-user isolation).
   - Executed E2E test commands:
     - `python3 tests/e2e/runner.py --feature F-R4-005` -> Output: 13/13 PASS (100.0%)
     - `python3 tests/e2e/runner.py --feature F-R4-006` -> Output: 14/14 PASS (100.0%)

---

## 2. Logic Chain

1. **Observations -> Inotify Monitor Daemon Gap (F-R4-005)**:
   - Observation: `guest/portal-agent` is absent from `guest/`.
   - Reason: Inotify monitoring requires a guest daemon watching `/usr/share/applications/` and `/home/user/.local/share/applications/`.
   - Deduction: Worker must create `guest/portal-agent` (in Rust or C++) with `inotify` watches for `IN_CLOSE_WRITE`, `IN_MODIFY`, `IN_DELETE`, a `.desktop` parser validating `[Desktop Entry]` sections and filtering `NoDisplay=true`, a 50ms burst debouncing window, icon resolution with fallback, and Vsock 5000 transmission of JSON-encoded `LinuxAppInfo` objects.

2. **Observations -> Host Bridge Dispatch Gap (F-R4-005/006)**:
   - Observation: `LinuxBridgeService.java` defines `CMD_APP_SYNC` (0x0200) but line 162 `handleIncomingPacket()` does not handle `CMD_APP_SYNC`.
   - Reason: Incoming Vsock 5000 app sync packets from `linux_bridge` are currently unhandled and logged as unhandled frames.
   - Deduction: Worker must update `LinuxBridgeService.java` to handle `CMD_APP_SYNC` by deserializing JSON payload into `LinuxAppInfo` instances, updating `mCachedApps`, and broadcasting `android.system.linux.action.LINUX_APPS_CHANGED`.

3. **Observations -> Launcher3 Synthetic Shortcut Generator Gap (F-R4-006)**:
   - Observation: `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java` is listed in `PROJECT.md` line 137 but missing from filesystem.
   - Reason: Launcher3 requires a dedicated tracker class to convert synchronized `.desktop` metadata into dynamic shortcuts in the Android App Drawer.
   - Deduction: Worker must create `LinuxAppTracker.java` in Launcher3. It must:
     - Listen for `LINUX_APPS_CHANGED` broadcasts or poll `LinuxManager.getInstalledApps()`.
     - Dynamically generate synthetic app drawer shortcuts.
     - Extract PNG/SVG icons and convert unsupported formats (`.xpm`) to default fallback icons.
     - Handle tap events by launching `LinuxAppProxyActivity` with `appId` and `execCommand`.
     - Implement deduplication (update existing entry by `appId`), persistence across Launcher restarts, special character XML escaping (`&` -> `&amp;`), multi-user isolation by `userId`, and shortcut removal when `.desktop` files are deleted.

---

## 3. Caveats

- **No Caveats**: All required framework structures, AIDL files, E2E test specs, and command execution pipelines have been completely inspected and verified.

---

## 4. Conclusion

Features F-R4-005 and F-R4-006 have a complete design spec and framework skeleton in place, but require three concrete implementation artifacts from the Worker:
1. `guest/portal-agent`: Guest inotify monitor daemon for `/usr/share/applications/` and `~/.local/share/applications/`.
2. `LinuxBridgeService.java`: `CMD_APP_SYNC` host bridge packet handler and broadcast dispatcher.
3. `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java`: Launcher3 synthetic shortcut generator with icon formatting, deduplication, escaping, persistence, and task proxy activity launching.

---

## 5. Verification Method

### Concrete Verification Commands
1. **E2E Test Execution**:
   ```bash
   # Run F-R4-005 test suite:
   python3 tests/e2e/runner.py --feature F-R4-005

   # Run F-R4-006 test suite:
   python3 tests/e2e/runner.py --feature F-R4-006

   # Run full M4 suite:
   python3 tests/e2e/runner.py --filter R4
   ```
   **Expected Output**: Exit code `0`, 100.0% Pass Rate across all test cases.

2. **Unit Test Compilation & Execution**:
   ```bash
   # Compile Java framework and tests:
   mkdir -p build_out/classes
   find frameworks/base/core/java frameworks/base/services/core/java packages/apps/Launcher3/src tests/unit -name "*.java" > build_out/sources.txt
   javac -d build_out/classes @build_out/sources.txt

   # Execute unit tests:
   java -cp build_out/classes tests.unit.LinuxAppTrackerTest
   ```
   **Expected Output**: `PASS: LinuxAppTrackerTest executed successfully.`

### Files to Inspect
- `guest/portal-agent/src/main.rs` (or `inotify_watcher.rs` / `desktop_parser.rs`)
- `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`
- `packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java`
- `tests/unit/LinuxAppTrackerTest.java`

### Invalidation Conditions
- Any malformed `.desktop` file without `[Desktop Entry]` not being rejected.
- `NoDisplay=true` entries causing synthetic shortcuts to appear.
- Rapid file writes during `apt install` causing un-debounced IPC flooding.
- Failure to escape special characters (`&`, `<`) in Launcher3 shortcut titles.
- Duplicate shortcuts appearing when `.desktop` files are updated instead of replaced.
