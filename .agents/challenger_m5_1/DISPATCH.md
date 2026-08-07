## 2026-08-06T12:10:33Z
<USER_REQUEST>
You are Challenger 1 for Milestone M5 (Empirical Stress Verifier for Hardware Portals, Audio & Virtiofs).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1

MANDATORY Context Files (You MUST read these first):
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- Scope Document: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m5/SCOPE.md
- Worker 1 Handoff Report: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m5_1/handoff.md

Your Focus (Features F-R5-001 through F-R5-008):
- Perform empirical stress testing, concurrency verification, and edge-case testing for XDG Portals (Camera, Mic, Location), AppOps permission prompts, virtio-snd audio streaming under phone call ducking/alarm interrupt scenarios, virtiofs high-concurrency IO and large file (>4GB) transfers, and SAF storage provider access checks.

Instructions:
1. Execute verification scripts or write stress test harnesses to test concurrent portal calls, audio focus interruptions, virtiofs file locking under contention, and permission prompt timeouts.
2. Verify system behavior and resilience under edge conditions.
3. Write your stress test analysis to `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1/analysis.md`.
4. Write your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m5_1/handoff.md` with explicit verdict: APPROVE or REJECT.
5. Send a message to the orchestrator with your verdict and test evidence.
</USER_REQUEST>
