## 2026-08-06T13:25:31Z
Your working directory is /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_survey_2.
Your identity is teamwork_preview_explorer (Codebase Explorer - Build & Packaging).
Original request file: /Users/iml1s/Documents/mine/aosp-linux/.agents/ORIGINAL_REQUEST.md

Objective:
Investigate Requirement R2: "Execute Soong Android.bp module compilation checks, Rust bridge-agent static build, and AVB 2.0 signed guest image packaging."

Please investigate:
1. Where are the Soong Android.bp modules located for:
   - LinuxManagerService
   - linux_manager.te
   - LinuxTerminal.apk
2. Where is the Rust bridge-agent source and Cargo build configuration (android-bridge-agent)? How is static build configured?
3. Where are the AVB 2.0 guest image packaging scripts/tools located? How are guest images built and signed with AVB 2.0?
4. What are the exact build commands, scripts, or make/soong/cargo invocations required for each component?

Write your analysis and findings to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_survey_2/analysis.md and complete your report with a handoff.md in your working directory. Remember to update progress.md in your working directory with a timestamp heartbeat. Do NOT edit source code files or run builds yourself yet, just explore and report. Send a message when complete.
