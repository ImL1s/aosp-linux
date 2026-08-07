## 2026-08-06T12:19:14Z

You are Worker 2 for Milestone M5 Iteration 2 (Remediation Implementation & Test Suite Rewrite).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_2

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY Context & Reference Files (You MUST read these files first):
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md
- Gate Status: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/GATE_STATUS.md
- Forensic Auditor Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m5_1/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m5_1/handoff.md
- Explorer 1 (Iteration 2) Strategy: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1_r2/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_1_r2/handoff.md
- Explorer 2 (Iteration 2) Strategy: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2_r2/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_2_r2/handoff.md
- Explorer 3 (Iteration 2) Strategy: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3_r2/analysis.md and /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3_r2/handoff.md

Your Mission:
Execute the full remediation plan across code and test suites to eliminate all integrity violations and defects:

1. `LinuxPortalService.java` & `LinuxPermissionActivity.java`:
   - Replace negative check with affirmative `MODE_ALLOWED` enforcement.
   - Wire `MODE_PROMPT` to trigger `LinuxPermissionActivity.launchPrompt()` for real permission dialogs.
   - Fix `LinuxPermissionActivity` concurrency bug using static monitor `sLock` for `sPendingPromptsQueue` and `sIsDialogVisible`.
   - Wire integration to `CameraManager` (Camera2 HAL), `AudioRecord`, and `LocationManager`.

2. `LinuxAudioPolicyHandler.java`:
   - Implement stacked AudioFocus tracking (`mPreTransientFocusState`) so call ducking (`0.2f`) is restored when transient alarms end during an ongoing phone call.
   - Replace unsynchronized list with `ConcurrentLinkedQueue` for real PCM streaming to `AudioTrack`/`AudioService`.

3. `LinuxStorageProvider.java` (`DocumentsProvider`):
   - Fix path traversal security vulnerability using `File.getCanonicalPath()` and `canonicalTarget.startsWith(canonicalBase)` boundary validation. Block `/etc/shadow`, `/home/user/../../etc/shadow`.
   - Implement real `ParcelFileDescriptor.open(targetFile, pfdMode)` handling SAF modes (`"r"`, `"rw"`, `"wt"`, etc.).
   - Replace hardcoded mock data (`"doc.txt"`, 1024L) with dynamic `file.listFiles()` directory listing and real metadata.

4. `guest_ota_rollback_watchdog.cpp`:
   - Implement real JSON metadata serialization in `saveMetadata()` and file loading in `loadMetadata()`.
   - Fix `guest_ota_rollback_watchdog_test.cpp` to call `startWatchdog()` and exercise real countdown and slot rollback.

5. `AvbVerifier.cpp`:
   - Implement `calculateImageDigest()` (SHA-256) and OpenSSL `PEM_read_PUBKEY`/`EVP_DigestVerify` for real RSA-4096 signature verification against public key files instead of `(void)imagePath;` stub.
   - Update `scripts/run_m5_verification.sh` build flags for OpenSSL (`pkg-config --cflags --libs openssl`).

6. Tier-1 E2E Test Suite Rewrite (`test_m5_tier1.py`):
   - Completely remove `_create_t1_m5_class` helper generator and all hardcoded `assert_true(True)` dummy assertions.
   - Implement 70 genuine `BaseTestCase` classes (T1-116 through T1-185) testing real IPC, virtiofs, SAF, SELinux, AVB, and watchdog behaviors.

Instructions:
- Write all code changes to the main codebase.
- Execute build and run test suites (`scripts/run_m5_verification.sh` and `python3 tests/e2e/runner.py`) to verify that all code compiles cleanly and tests pass genuinely.
- Write your complete handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_2/handoff.md`.
- Send a message to the orchestrator upon completion.
