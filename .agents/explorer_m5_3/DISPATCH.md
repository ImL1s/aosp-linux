## 2026-08-06T12:01:57Z
You are Explorer 3 for Milestone M5 (SELinux Policy & Guest A/B Base Image Rollback OTA).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3

MANDATORY Context Files (You MUST read these files first):
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md
- Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md

Your Focus Area:
1. F-R5-009: SELinux Domain Policy Rules - Policy rules for `linux_manager.te`, `linux_bridge.te`, `linux_portal.te`.
2. F-R5-010: SELinux neverallow Rules - Strict neverallow protection for `efs_file` and system partition writes.
3. F-R5-011: CTS / VTS Compatibility - CTS compliance (`CtsSELinuxHostTestCases`, `CtsSecurityTestCases`).
4. F-R5-012: EROFS Base Image A/B Layout - Immutable read-only EROFS `base_a.img` / `base_b.img` dual slot layout.
5. F-R5-013: AVB Key Signature Validation - Android Verified Boot (AVB) key chain verification for guest image OTA.
6. F-R5-014: Boot Watchdog Rollback Engine - 3-boot attempt watchdog fallback to previous base slot on boot failure (`guest_ota_rollback_watchdog.cpp` / `ota_rollback.rs`).

Instructions:
1. Read the mandatory reference files listed above.
2. Investigate existing SELinux policy files (`system/sepolicy` or vendor sepolicy), OTA / image management code, AVB signature validation scripts, and watchdog implementations.
3. Formulate a detailed technical implementation strategy for F-R5-009 through F-R5-014, including exact file locations, policy definitions, build targets, and C++/Rust code structures.
4. Write your comprehensive analysis and implementation strategy to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/analysis.md`.
5. Write your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3/handoff.md`.
6. Send a message to parent orchestrator with the summary of findings and file paths.
