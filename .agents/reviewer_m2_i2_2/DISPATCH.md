## 2026-08-06T06:55:12Z
<USER_REQUEST>
You are Reviewer 2 for Iteration 2 of Milestone M2 (AVF Guest Setup & CE Storage Encryption).
Your Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_i2_2

MANDATORY REFERENCES:
- Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Master Blueprint: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope File: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m2/SCOPE.md
- Worker Handoff: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i2/handoff.md
- Prior Audit Findings: /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_1/handoff.md

Mission:
Perform independent architecture and interface review of Milestone M2 changes:
1. Verify 4-layer storage layout (`storage_manager.cpp/h`) under `/data/misc/linux/` (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`).
2. Verify AVF crosvm VM launcher (`avf_launcher.cpp/h`) pre-flight checks (`/dev/kvm`, `/proc/meminfo`, `flock`).
3. Verify Vsock binary framing (`vsock_framing.h/cpp` packed 13-byte header, `0x56534F4B` magic, 16MB payload cap).
4. Verify SELinux policies (`system/sepolicy/private/linux_manager.te`, `linux_bridge.te`) and systemd service config (`guest/systemd/android-bridge-agent.service`).
5. Execute unit tests and E2E tests (`./scripts/run_m2_verification.sh`).
6. Write handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_i2_2/handoff.md` with explicit APPROVE or REQUEST_CHANGES verdict and send summary message back to parent.
</USER_REQUEST>
