# Handoff Report: Explorer 1 — Milestone M5 (Hardware Portals & Audio Subsystem)

## 1. Observation
1. **Existing Codebase Inspection**:
   - `frameworks/base/services/core/java/com/android/server/SystemServer.java`: Line 38 instantiates `LinuxManagerService` in `startOtherServices()`. Currently lacks `LinuxPortalService` and `LinuxAudioPolicyHandler` registration.
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`: Line 33 implements SystemService for VM lifecycle and owns `LinuxBridgeService`.
   - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`: Line 47 connects to `/dev/socket/linux_bridge` and uses binary framing header `MAGIC = 0x4C4E5842`. Contains command codes `CMD_VM_START` (0x0001), `CMD_PTY_*` (0x0100..0x0103), `CMD_APP_SYNC` (0x0200).
   - `frameworks/base/core/java/android/system/linux/LinuxManager.java`: Defines SystemApi constants, state callbacks, and AIDL wrapper.
2. **Missing Component Inspection**:
   - `LinuxPortalService.java`: Does not exist in `frameworks/base/services/core/java/com/android/server/linux/`. Needs creation to handle Camera2, AudioRecord, LocationManager, and AppOps enforcement.
   - `LinuxAudioPolicyHandler.java`: Does not exist in `frameworks/base/services/core/java/com/android/server/linux/`. Needs creation to handle `AudioManager.OnAudioFocusChangeListener` ducking and pausing.
   - `LinuxPermissionActivity.java`: Does not exist in `frameworks/base/services/core/java/com/android/server/linux/`. Needs creation to present Host runtime permission prompts to users.
3. **E2E Test Specifications**:
   - `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`: Defines tests T1-116 through T1-145 for features F-R5-001 through F-R5-006.
   - `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`: Defines boundary/corner case tests T2-116 through T2-145 covering camera denied, mic privacy mute, coarse location rounding, 30s permission prompt timeout, virtio-snd underflow zero-fill, and AudioFocus call ducking (0.2).
   - `tests/e2e/framework/mock_env.py`: Defines `MockXdgPortal`, `MockSystemServer`, and AppOps mock permissions (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`).

---

## 2. Logic Chain
1. **Observation 1 & 2** -> The AOSP SystemServer layer currently has `LinuxManagerService`, `LinuxBridgeService`, and `LinuxWindowBridgeService`, but is missing hardware portal and audio policy components (`LinuxPortalService.java`, `LinuxAudioPolicyHandler.java`, `LinuxPermissionActivity.java`).
2. **Observation 3** -> The E2E test harness (`test_m5_tier1.py` & `test_m5_tier2.py`) specifies the functional, boundary, and corner-case requirements for features F-R5-001 through F-R5-006.
3. **Reasoning from 1 & 2** -> To implement F-R5-001 through F-R5-006, the implementation team must create `LinuxPortalService.java`, `LinuxAudioPolicyHandler.java`, and `LinuxPermissionActivity.java`, extend `LinuxBridgeService.java` with portal IPC commands (`CMD_PORTAL_*`), and register these services in `SystemServer.java`.
4. **Conclusion** -> The complete technical strategy detailed in `analysis.md` provides exact file paths, class signatures, IPC protocols, AppOps permission checks, and edge-case handling algorithms necessary to fulfill all Tier 1 and Tier 2 test assertions.

---

## 3. Caveats
- **Hardware Emulation in Tests**: Test execution relies on `MockEnvironment` and synthetic Vsock framing in Python test harnesses (`tests/e2e/runner.py`). Physical device kernel testing requires `v4l2loopback` and ALSA loopback drivers loaded in the Debian guest kernel.
- **Scope Limit**: Virtiofs file sharing (F-R5-007, F-R5-008) is investigated by Explorer 2. SELinux policy & OTA rollback (F-R5-009 through F-R5-014) are investigated by Explorer 3.

---

## 4. Conclusion
The technical investigation for Features **F-R5-001 through F-R5-006** is complete. The detailed implementation blueprint, file locations, class definitions, Vsock command codes, AppOps permission prompt flow, virtio-snd audio streaming, and AudioFocus policy handler logic are documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/analysis.md`.

---

## 5. Verification Method
1. **File Inspection**:
   - Verify `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/analysis.md` exists and contains detailed technical specifications for F-R5-001 through F-R5-006.
   - Verify `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1/handoff.md` exists and adheres to the 5-component handoff protocol.
2. **E2E Test Execution Command**:
   ```bash
   python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 1 --feature F-R5-001
   python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py --tier 2 --feature F-R5-001
   ```
3. **Invalidation Conditions**:
   - If `analysis.md` does not specify exact file paths or class definitions.
   - If AppOps permission checks (`OP_CAMERA`, `OP_RECORD_AUDIO`, `OP_FINE_LOCATION`) are omitted.
