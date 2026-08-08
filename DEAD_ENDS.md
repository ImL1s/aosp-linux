# Dead Ends Log

| Iteration | Approach Tried | Why It Failed | Files Touched |
|-----------|---------------|---------------|---------------|
| 1 | `launch_vm.sh` retaining `exec sleep 3600` under `TEST_MODE=1` | When `crosvm` is absent, `launch_vm.sh` under `TEST_MODE=1` executes `exec sleep 3600`, causing `CommandRunner.run` in `test_m2_tier2.py` (T2-35) to time out after 30 seconds and leak an orphaned `sleep 3600` process into the host process table on every test runner execution. | `guest/scripts/launch_vm.sh`, `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` |
| 2 | Master Worker R4 retaining `exec sleep 3600` in `launch_vm.sh`, 113 files in `frameworks/base/`, unisolated `GLOBAL_PORTAL_STATE` in `portal.rs`, and untracked `challenger_r4_stress_harness.py` | Forensic Auditor INTEGRITY VIOLATION VETO: `launch_vm.sh` lines 76, 102, 103 contains prohibited `exec sleep 3600`; `frameworks/base/` contains 113 files (expected 20); `cargo test` flakiness on `GLOBAL_PORTAL_STATE`; untracked file `tests/unit/challenger_r4_stress_harness.py`. | `guest/scripts/launch_vm.sh`, `frameworks/base/`, `guest/bridge-agent/src/portal.rs`, `tests/unit/` |
