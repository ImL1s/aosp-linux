## 2026-08-08T15:54:23Z
You are dispatched as teamwork_preview_reviewer_gen2_4 for independent code review & quality gate verification.

Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_reviewer_gen2_4
Create your working directory `.agents/teamwork_preview_reviewer_gen2_4` if it doesn't exist.

Context and Key Files:
- ORIGINAL_REQUEST: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- PROJECT: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- WORKER 3 HANDOFF: /Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_worker_gen2_3/handoff.md

Review Tasks:
1. Conduct an independent code review of `guest/scripts/launch_vm.sh`, `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`, `tests/e2e/framework/real_env.py`, `tests/e2e/tier2_boundary_corner/test_m5_tier2.py`, and `guest/bridge-agent/`.
2. Ensure zero hardcoded cheat constants, zero facade implementations, zero process leaks, and zero swallowed exceptions.
3. Run verification commands:
   - `python3 tests/e2e/runner.py` (Must achieve 430/430 PASS, 100.0% Pass Rate, Exit Code 0, duration < 10s).
   - `$HOME/.cargo/bin/cargo test --manifest-path guest/bridge-agent/Cargo.toml` (Must achieve 34/34 PASS, Exit Code 0).
   - `git status --porcelain` (Must show 0 untracked binaries or reports).
4. Deliver your handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_reviewer_gen2_4/handoff.md`.
5. Specify your final verdict clearly on the first line of your Verdict section: `APPROVE` or `REQUEST_CHANGES`.
6. Send a message to the orchestrator with your report path when complete.
