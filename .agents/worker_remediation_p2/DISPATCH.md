## 2026-08-08T12:07:52Z
Task: Phase B Remediation — VM Launch Script, HMAC Auth & LinuxManagerService Facade Cleanup

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Victory Audit Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/victory_auditor/handoff.md
- Explorer 2 Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_remediation_2/handoff.md

Detailed Remediation Instructions:
1. `guest/scripts/launch_vm.sh`:
   - Remove ALL `TEST_MODE` logic, checks, and `exec sleep 3600`.
   - Fail-fast with non-zero exit code if `/dev/kvm` node does not exist:
     `if [ ! -c /dev/kvm ]; then echo "ERROR: KVMException: /dev/kvm hardware device node not available" >&2; exit 1; fi`
   - Fail-fast with non-zero exit code if `crosvm` binary is missing from PATH:
     `if ! command -v crosvm >/dev/null 2>&1; then echo "ERROR: CrosvmNotFound: crosvm binary not found in PATH" >&2; exit 4; fi`
   - Ensure the script directly launches crosvm without simulated success fallbacks.

2. `guest/bridge-agent/src/auth.rs`:
   - Remove `#[allow(dead_code)]` from `sha256`, `HmacSha256`, and `compute_hmac_response`.
   - In `perform_handshake`, implement real HMAC-SHA256 challenge/response verification:
     - Read 16-byte nonce / challenge, compute expected signature using `HmacSha256::compute_hmac_response(secret, &challenge)`, verify received signature matches expected signature in constant time using `verify_token(&signature, &expected)`.
     - Remove raw token equality comparison (`token_buf == secret`).
     - On handshake failure, fail client handshake without process crash/exit.

3. `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`:
   - `getInstalledApps()`: Remove hardcoded fallback list (`org.gnome.Terminal`, `org.mozilla.firefox`). If VM is not running or `mBridgeService` is disconnected/null, return `Collections.emptyList()`.
   - `launchLinuxApp()`: Check `mBridgeService != null && mBridgeService.isConnected()`. If not connected, return `false` (do NOT return `true`).
   - `installGuestImage()`: Implement real file streaming/writing from `ParcelFileDescriptor imageFd` to disk (e.g. `/data/misc/linux/base_rootfs.img.tmp`), verifying byte count against `size`, and atomically renaming to target image file. Return `false` on any I/O error or size mismatch.

4. Verify & Report:
   Run unit tests or check build:
   - Check `launch_vm.sh` for `TEST_MODE` -> none.
   - Check `auth.rs` for `#[allow(dead_code)]` -> none.
   - Check `LinuxManagerService.java` for hardcoded strings -> none.
   Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_remediation_p2/handoff.md`.
