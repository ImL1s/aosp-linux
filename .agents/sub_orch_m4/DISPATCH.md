## 2026-08-06T11:33:15Z

<USER_REQUEST>
You are sub_orch_m4, the Sub-Orchestrator for Milestone M4 (Seamless Wayland GUI Window Forwarding & Recents Overview Mapping) in the AOSP Dual-OS Project.

Your parent orchestrator conversation ID: f49c13dc-3d4b-4e80-a68f-c1ded5ab6c3a

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4
Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4/SCOPE.md

Context & Reference Files:
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (MANDATORY: read this first!)
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Full Technical Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md

Scope of Milestone M4 (6 features):
1. F-R4-001: Wayland Window Forwarding - Guest Sommelier Wayland proxy buffer forwarding over Vsock Port 5002.
2. F-R4-002: virtio-gpu dma-buf Sharing - Zero-copy dma-buf memory buffer binding to Host SurfaceControl.
3. F-R4-003: LinuxAppProxyActivity Task ID - Discrete Android Task ID allocation & Recents overview mapping (`LinuxWindowBridgeService.java`).
4. F-R4-004: Freeform Multi-Window Resize - Freeform windowing mode support & dynamic frame pacing resize handler.
5. F-R4-005: .desktop Inotify Monitor Daemon - Guest portal-agent inotify watcher for `/usr/share/applications/`.
6. F-R4-006: Launcher3 Synthetic Shortcuts - Vsock 5000 metadata sync & Launcher3 synthetic shortcut generator (`LinuxAppTracker.java`).

Execution Rules:
1. Initialize your BRIEFING.md, progress.md, and SCOPE.md under `.agents/sub_orch_m4/`.
2. Follow the Project Pattern iteration loop:
   a. Dispatch 3 Explorers (teamwork_preview_explorer) to analyze M4 codebase & design strategy.
   b. Dispatch Worker (teamwork_preview_worker) with Explorer findings and MANDATORY INTEGRITY WARNING:
      "DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected."
   c. Dispatch 2 Reviewers (teamwork_preview_reviewer) to verify implementation, unit tests, and E2E compatibility.
   d. Dispatch 2 Challengers (teamwork_preview_challenger) for empirical testing and stress verification.
   e. Dispatch Forensic Auditor (teamwork_preview_auditor) for integrity verification.
   f. Record all verdicts in GATE_STATUS.md. GATE PASS requires: all Reviewers APPROVE, all Challengers APPROVE, Forensic Auditor CLEAN. If audit fails (INTEGRITY VIOLATION), it is a BINARY VETO — loop back immediately with full audit evidence report.
3. Once Milestone M4 passes the gate review, write handoff.md under `.agents/sub_orch_m4/` and send a message back to parent orchestrator (`f49c13dc-3d4b-4e80-a68f-c1ded5ab6c3a`).
</USER_REQUEST>
