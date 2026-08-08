## 2026-08-08T12:53:01Z

You are teamwork_preview_worker_r2_p2.
Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_p2

Task: Implement Round 2 Remediation Work Package 2 — Guest Portal Rust Real Event Consumption & Purge Mock Responses in `guest/bridge-agent/src/portal.rs`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Context Files:
- Original Request: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
- Explorer 2 Report (Guest Portal): /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_r2_2/handoff.md

Detailed Remediation Instructions:
1. **`guest/bridge-agent/src/portal.rs` Data Models & State Store**:
   - Define `LocationEvent`, `CameraFrameEvent`, `AudioPcmEvent` and Serde enum `HostPortalEvent` (with aliases `Latitude`, `Longitude`, `Accuracy`).
   - Define thread-safe `PortalState` container (`last_location`, `last_camera`, `last_audio`) and global instance `GLOBAL_PORTAL_STATE` using `Arc<RwLock<PortalState>>`.

2. **Ingest Host Events in `handle_portal_session`**:
   - In `handle_portal_session`: Add dual parsing pipeline for messages on Port 5000.
   - First, try parsing `HostPortalEvent` or legacy `LocationEvent`/`CameraFrameEvent`/`AudioPcmEvent`. If valid Host event, update `GLOBAL_PORTAL_STATE` (`last_location`, `last_camera`, `last_audio`) and continue stream processing without error response.
   - Second, if message is a Guest `PortalRequest`, parse and dispatch to `dispatch_portal_request(req)`.

3. **Purge Hardcoded Mock Responses in `dispatch_portal_request`**:
   - For `"location.get"` / `"location.request"`: Read `GLOBAL_PORTAL_STATE.last_location`. Return `PortalResponse::ok` with actual event coordinates/accuracy if present; return `PortalResponse::err(..., "Location unavailable...")` if uninitialized. REMOVE hardcoded `0.0, 0.0` and `"mock"`.
   - For `"camera.status"` / `"camera.request"`: Read `GLOBAL_PORTAL_STATE.last_camera`. Return `PortalResponse::ok` with actual camera event status/device/dimensions if present; return `PortalResponse::err(...)` if uninitialized. REMOVE fixed `"status": "available"`.
   - For `"audio.status"` / `"audio.request"`: Read `GLOBAL_PORTAL_STATE.last_audio`. Return `PortalResponse::ok` with actual audio event status/backend if present; return `PortalResponse::err(...)` if uninitialized. REMOVE fixed `"status": "available"`.

4. **Update Rust Unit Tests**:
   - Update unit tests in `portal.rs` to verify that uninitialized requests return `success: false` / error response, and that ingesting Host events dynamically updates response JSON to match injected values.

5. **Verification**:
   - Run `cargo check` and `cargo test` in `guest/bridge-agent/` -> All unit tests pass cleanly.
   - Check `portal.rs` for `0.0` or `"mock"` -> 0 matches.
   Write detailed handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_r2_p2/handoff.md`.
