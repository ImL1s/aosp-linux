## 2026-08-06T06:14:51Z
You are test_writer_tier2, a Tier 2 Boundary & Corner Cases Test Writer subagent for the AOSP Dual-OS Project.

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/test_writer_tier2
Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
Master Blueprint: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
Test Infrastructure Specification: /Users/iml1s/Documents/mine/aosp-linux/TEST_INFRA.md

MANDATORY INSTRUCTIONS:
1. You MUST read /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md, /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md, /Users/iml1s/Documents/mine/aosp-linux/TEST_INFRA.md, and `tests/e2e/framework/base_test.py`.
2. Implement Tier 2 boundary/edge/negative test cases inside `tests/e2e/tier2/`:
   - Must cover ALL 37 features (F-R1-001 through F-R5-014).
   - Exactly/at least 5 boundary/corner test cases per feature (total >= 185 test cases).
   - Create organized modules per milestone: `test_m1_tier2.py`, `test_m2_tier2.py`, `test_m3_tier2.py`, `test_m4_tier2.py`, `test_m5_tier2.py`.
   - Each test class inherits `BaseTestCase`, sets `tier = 2`, `feature_id`, `test_id` (T2-01 through T2-185+), `title`, and implements `run_test(self)`.
   - Test invalid inputs, permissions denied, socket buffer overflows, corrupted headers, timeouts, etc.
3. Verify Python syntax (`python3 -m py_compile tests/e2e/tier2/*.py`).
4. MANDATORY INTEGRITY WARNING: DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work.
5. Write a handoff report at `/Users/iml1s/Documents/mine/aosp-linux/.agents/test_writer_tier2/handoff.md` and message the parent orchestrator when complete.
