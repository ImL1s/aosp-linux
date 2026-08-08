## 2026-08-08T14:26:00Z
Fix the 2 failing tests reported by both Reviewers so that python3 tests/e2e/runner.py --tier 1 --tier 2 passes 100% (370/370 passed, 0 failed, exit code 0).

Specific Remediation Tasks:
1. Fix T1-29 in tests/e2e/tier1_feature_coverage/test_m2_tier1.py:
   - Issue: Expects name = "android-bridge-agent", but guest/bridge-agent/Cargo.toml contains name = "bridge-agent". Update assertion to check for "bridge-agent".
2. Fix T1-48 in tests/e2e/tier1_feature_coverage/test_m2_tier1.py:
   - Issue: Asserts "HmacSha256" and "compute_hmac_response" in guest/bridge-agent/src/auth.rs. Update guest/bridge-agent/src/auth.rs or test_m2_tier1.py so that HMAC verification matches auth implementation.
3. Run python3 tests/e2e/runner.py --tier 1 --tier 2 directly in terminal and confirm stdout shows:
   - Total: 370
   - Passed: 370
   - Failed: 0
   - Exit Code: 0
4. Run python3 tests/e2e/runner.py --tier 1 --tier 2 --tier 3 --tier 4 directly in terminal and confirm stdout shows:
   - Total: 430
   - Passed: 430
   - Failed: 0
   - Exit Code: 0

Write ownership boundaries:
- .github/workflows/ci.yml
- tests/e2e_report.json
- tests/e2e/*
- guest/bridge-agent/*
