## 2026-08-06T06:34:49Z
<USER_REQUEST>
You are Worker 1 (teamwork_preview_worker) for Milestone M2 (AVF / crosvm / KVM Non-Protected Debian ARM64 Setup & CE Storage Encryption).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2
Workspace Root: /Users/iml1s/Documents/mine/aosp-linux

MANDATORY DOCUMENTS TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_2/handoff.md
5. /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_3/handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

YOUR ASSIGNMENT:
Implement and verify all Milestone M2 components based on the Explorer handoff reports:
- F-R2-001: Non-Protected Debian VM setup (`crosvm` runner config, guest config `vm_config.json`, launch scripts, kernel/initrd/cmdline).
- F-R2-002: 4-Layer Storage Image Layout (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`, dm-snapshot / overlayfs mounting strategy).
- F-R2-003: LUKS2 CE Storage Encryption (`user_home.img` LUKS2 formatting & key management bound to Android CE key, host-side cryptsetup mapper `/dev/mapper/user_home_decrypted`, lock screen revocation).
- F-R2-004: Vsock 3-Port Allocation (Port 5000 Control RPC, Port 5001 PTY Stream, Port 5002 Wayland Display).
- F-R2-005: HMAC-SHA256 Auth Handshake (256-bit single-use token generation, injection via `--params android_bridge.token=<hex>`, 4-step challenge-response HMAC-SHA256 verification with constant-time comparison).

34: ## 2026-08-06T13:41:49Z
35: <USER_REQUEST>
36: Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2.
37: Your identity is teamwork_preview_worker (M2 Build & Packaging Worker).
38: Original request file: /Users/iml1s/Documents/mine/aosp-linux/.agents/ORIGINAL_REQUEST.md
39: Scope document: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
40: 
41: MANDATORY INTEGRITY WARNING:
42: DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
43: 
44: Objective for Milestone M2 (R2):
45: Execute Soong Android.bp module compilation checks, Rust bridge-agent static build, AVB 2.0 signed guest image packaging, and M2 verification scripts.
46: 
47: Instructions:
48: 1. Soong Module Compilation & Java framework build:
49:    Execute compilation of LinuxManagerService.class, linux_manager.te, and LinuxTerminal.apk.
50:    Verify output classes in build_out/classes/ and artifacts.
51: 
52: 2. Rust bridge-agent static build:
53:    Execute cargo build --release (or cargo test) in guest/bridge-agent/.
54:    Verify android-bridge-agent executable is produced in guest/bridge-agent/target/release/android-bridge-agent or target/release/android-bridge-agent.
55: 
56: 3. AVB 2.0 signed guest image packaging:
57:    Execute bash guest/scripts/init_storage_layout.sh build_out/guest_images to initialize storage layout (base_rootfs.img, custom_overlay.img, user_home.img, vm_state.snapshot, vm_config.json) and generate AVB 2.0 signed vbmeta.img.
58: 
59: 4. M2 Verification Suite Execution:
60:    Execute bash scripts/run_m2_verification.sh and verify all checks pass.
61:    Execute python3 tests/e2e/runner.py --verbose --report tests/e2e_report.json to confirm full E2E pass rate (100.0%).
62: 
63: Write execution details to changes.md and complete handoff.md in your working directory. Send a message when complete.
64: </USER_REQUEST>

VERIFICATION REQUIREMENTS:
1. Run all builds and tests relevant to Milestone M2, including `python3 tests/e2e/runner.py`.
2. Ensure 100% pass rate across unit tests and E2E test suites (Tier 1, Tier 2, Tier 3, Tier 4).
3. Document exact build/test commands executed and full pass results in your report.

Write your report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2/handoff.md` and send a message when complete.
</USER_REQUEST>
