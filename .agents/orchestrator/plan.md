# Orchestration Plan — AOSP Dual-OS Verification & Deployment Run

## Phase 0: Survey & Architecture Assessment
- [ ] Dispatch 3 Explorers (`explorer_survey_1`, `explorer_survey_2`, `explorer_survey_3`) to investigate codebase, runner.py, Soong modules, Rust bridge-agent, AVB 2.0 guest image packaging, and deployment paths.
- [ ] Synthesize findings and create `PROJECT.md` with Feature Inventory, Milestones, and Interface Contracts.

## Phase 1: Dual-Track Execution Strategy
- [ ] Track 1: E2E Test Suite Execution & Empirical Verification (R1)
  - Run all 430+ automated E2E & empirical stress test suites via runner.py.
  - Generate full verification reports.
- [ ] Track 2: Compilation & Packaging (R2)
  - Execute Soong Android.bp module compilation checks (LinuxManagerService, linux_manager.te, LinuxTerminal.apk).
  - Execute Rust bridge-agent static build (android-bridge-agent).
  - Execute AVB 2.0 signed guest image packaging.
- [ ] Track 3: Deployment & Target Verification (R3)
  - Deploy generated AOSP artifacts to `build_out/deployment/` directory.
  - Perform simulated target verification.

## Phase 2: Audit & Synthesis
- [ ] Dispatch `teamwork_preview_auditor` for integrity verification across all deliverables.
- [ ] Aggregate reports and compile final summary for human reporting.
