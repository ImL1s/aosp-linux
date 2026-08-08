## 2026-08-08T13:11:15Z
Objective:
1. Read ORIGINAL_REQUEST.md and victory_auditor_r2/handoff.md.
2. In frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java:
   - PURGE ALL 3 `new Socket("localhost", ...)` calls (lines 712, 724, 747).
   - Implement `VsockPortalClient.java` using POSIX AF_VSOCK=40 (`VmSocketAddress(5000, guestCid)`) and 13-byte Big-Endian VSOK header (`0x56534F4B`).
   - Implement `convertYuv420ToNv21` and stream binary `CAMF` camera payload (`0x43414D46` header + NV21 bytes).
   - Implement binary `AUDO` (`0x4155444F`) and `GEOC` (`0x47454F43`) headers over `VsockPortalClient`.
3. In guest/bridge-agent/src/portal.rs:
   - Implement thread-safe `PortalState` container (`Arc<RwLock<PortalState>>`).
   - In `handle_portal_session`, implement Serde parsing for incoming Host events (tagged `HostPortalEvent` and field aliases `Latitude`, `Longitude`, `Accuracy`, `device`, `status`), updating `PortalState`.
   - In `dispatch_portal_request`, PURGE ALL hardcoded mock responses (`(0.0, 0.0)`, `"mock"`, static `"available"`). Return dynamic state if present, or return error (`PortalResponse::err`) if uninitialized.
   - In guest/bridge-agent/src/pty.rs: fix `test_pty_master_open_and_slave_name` to handle PTY open errors gracefully on host OS.
4. In tests/e2e/framework/real_env.py:
   - In `RealSystemServerAdapter.__init__`, set `self.cts_verifier_status = None`, `self.idle_power_drop_override = None`, `self.gsi_boot_compatible = None`.
   - In `SystemEnvironment.__init__`, set `self.virtiofs_read_speed_override = None`, `self.erofs_throughput_override = None`.
   - In `verify_cts_verifier_compatibility()`, return dynamic status string `f"CTS_VERIFIER_COMPATIBLE_{version}"` or `f"CTS_VERIFIER_PKG_{pkg}"` (NO literal `return "PASS"`).
   - Ensure all 6 hardware methods raise `EnvironmentError` when real target hardware/sysfs/mounts are missing on host OS.
   - Verify `grep -nE "return (1\.4|8\.5|10\.5|1200\.0|245\.0|\"PASS\"|True)" tests/e2e/framework/real_env.py` returns EXACTLY 0 matches.
5. Clean repository:
   - Delete `tests/unit/m3_native_challenger2_stress_bin` and `tests/unit/m3_native_terminal_test_bin`.
   - Update `.gitignore` to include `*_bin`, `scratch/`, `release_dist/`, `patches/`, `__pycache__/`, `.pytest_cache/`, `e2e_report.json`, `tests/e2e_report.json`.
6. Run `export PATH="$HOME/.cargo/bin:$PATH" && cargo test --manifest-path guest/bridge-agent/Cargo.toml` — ALL 33 tests MUST pass.
7. Write handoff.md in /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_master_fix/handoff.md detailing all edits and verification commands. Report completion via send_message.
