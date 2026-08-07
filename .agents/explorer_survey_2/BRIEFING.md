# BRIEFING — 2026-08-06T13:25:35Z

## Mission
Investigate Requirement R2: Soong Android.bp module compilation checks, Rust bridge-agent static build, and AVB 2.0 signed guest image packaging in aosp-linux project.

## 🔒 My Identity
- Archetype: teamwork_preview_explorer
- Roles: Codebase Explorer - Build & Packaging
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_survey_2
- Original parent: 7249e5f0-af46-4f65-970f-c4ca44e9345e
- Milestone: Requirement R2 Investigation

## 🔒 Key Constraints
- Read-only investigation — do NOT edit source code files or run builds yet
- Written language in communications: Traditional Chinese (繁體中文)
- All findings must be backed by concrete file paths, line numbers, and commands

## Current Parent
- Conversation ID: 7249e5f0-af46-4f65-970f-c4ca44e9345e
- Updated: 2026-08-06T13:29:35Z

## Investigation State
- **Explored paths**:
  - `Android.bp` (Root framework java_library modules `services.linux` / `service-linux`)
  - `packages/apps/LinuxTerminal/Android.bp` & `jni/Android.bp` (`LinuxTerminal` android_app, `libvterm_jni`)
  - `system/sepolicy/private/linux_manager.te` (SELinux domain policy)
  - `guest/bridge-agent/Cargo.toml` & `src/` (`android-bridge-agent` Rust daemon & auth module)
  - `guest/scripts/init_storage_layout.sh` (4-layer storage image layout)
  - `system/vold/AvbVerifier.h` / `AvbVerifier.cpp` & `system/etc/security/avb/guest_root_key.pub` (AVB 2.0 verification)
  - `scripts/run_m1_verification.sh`, `run_m2_verification.sh`, `run_m5_verification.sh` (Verification scripts)
- **Key findings**:
  - `LinuxManagerService` is in `Android.bp` under `services.linux`.
  - `linux_manager.te` is in `system/sepolicy/private/linux_manager.te`.
  - `LinuxTerminal.apk` is in `packages/apps/LinuxTerminal/Android.bp`.
  - `android-bridge-agent` source & Cargo build config are at `guest/bridge-agent/`.
  - AVB 2.0 guest image packaging uses `init_storage_layout.sh` for 4-layer disk setup, and `AvbVerifier.cpp` with `guest_root_key.pub` for RSA-4096 SHA-256 signature verification.
- **Unexplored areas**: None for R2 scope.

## Key Decisions Made
- Completed read-only investigation for Requirement R2.
- Compiled comprehensive analysis report (`analysis.md`) and Handoff report (`handoff.md`).

## Artifact Index
- DISPATCH.md — Initial task dispatch details
- BRIEFING.md — Context and working memory
- progress.md — Liveness heartbeat and step tracking
- analysis.md — Detailed analysis report for Requirement R2
- handoff.md — 5-component handoff report for Requirement R2

