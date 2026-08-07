# Scope: E2E Testing Track (`SCOPE.md`)

## Architecture & Test Approach
- **Approach**: Opaque-box, requirement-driven end-to-end testing suite for AOSP Dual-OS System.
- **Location**: `tests/e2e/` (Test runner, harness, test modules).
- **Publication**: `TEST_INFRA.md` (infra architecture) and `TEST_READY.md` (readiness marker at workspace root).

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1-TEST | Test Infrastructure & Runner | Design harness, runner script (`tests/e2e/run_tests.sh` / Python runner), report generator, `TEST_INFRA.md` | none | DONE |
| M2-TEST | Tier 1 Feature Coverage | Implement 185 happy-path test cases (5 per feature for F-R1-001 through F-R5-014) | M1-TEST | DONE |
| M3-TEST | Tier 2 Boundary & Edge Cases | Implement 185 boundary, invalid input, and error condition test cases (5 per feature) | M1-TEST, M2-TEST | DONE |
| M4-TEST | Tier 3 Pairwise Combinations | Implement 40 cross-feature interaction test cases | M1-TEST, M2-TEST | DONE |
| M5-TEST | Tier 4 Application Scenarios & Publication | Implement 20 real-world application scenarios, verify suite execution, publish `TEST_READY.md` | M1..M4 | DONE |

## Feature Coverage Matrix Target

| Tier | Required Count | Target Scope |
|------|---------------:|--------------|
| Tier 1: Feature Coverage | >= 185 | 5+ happy-path tests per feature (37 features) |
| Tier 2: Boundary & Corner | >= 185 | 5+ boundary/edge/error tests per feature (37 features) |
| Tier 3: Cross-Feature | >= 37 | Pairwise interaction scenarios |
| Tier 4: Real-World Application | >= 18 | Multi-feature E2E workflow applications |
| **Total Test Suite** | **>= 425** | **Full Opaque-box Test Suite** |
