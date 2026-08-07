# Scope: Milestone M4 — Seamless Wayland GUI Window Forwarding & Recents Overview Mapping

## Architecture Overview
Milestone M4 establishes seamless Wayland GUI window forwarding from the Guest Linux environment into AOSP, alongside dynamic task lifecycle management, multi-window freeform resize support, `.desktop` application entry detection, and Launcher3 synthetic shortcuts.

## Feature Inventory & Status
| # | Feature Code | Description | Target Module / Files | Status |
|---|--------------|-------------|----------------------|--------|
| 1 | F-R4-001 | Wayland Window Forwarding — Guest Sommelier Wayland proxy buffer forwarding over Vsock Port 5002 | `LinuxWindowBridgeService.java`, Vsock 5002 | DONE |
| 2 | F-R4-002 | virtio-gpu dma-buf Sharing — Zero-copy dma-buf memory buffer binding to Host SurfaceControl | `wayland_buffer_sharing.cpp`, SurfaceControl | DONE |
| 3 | F-R4-003 | LinuxAppProxyActivity Task ID — Discrete Android Task ID allocation & Recents overview mapping | `LinuxWindowBridgeService.java`, `LinuxAppProxyActivity.java` | DONE |
| 4 | F-R4-004 | Freeform Multi-Window Resize — Freeform windowing mode support & dynamic frame pacing resize handler | `LinuxAppProxyActivity.java`, `WindowResizePacer.java` | DONE |
| 5 | F-R4-005 | .desktop Inotify Monitor Daemon — Guest portal-agent inotify watcher for `/usr/share/applications/` | `guest/portal-agent/src/inotify_watcher.rs`, `LinuxBridgeService.java` | DONE |
| 6 | F-R4-006 | Launcher3 Synthetic Shortcuts — Vsock 5000 metadata sync & Launcher3 synthetic shortcut generator | `LinuxAppTracker.java`, Launcher3 integration | DONE |

## Interface Contracts
- **Guest (Linux/Sommelier) ↔ Host (Android/LinuxWindowBridgeService)**: Vsock 5002 for Wayland buffer commands/events using VSOK binary framing (`0x56534F4B`).
- **Guest (portal-agent) ↔ Host (LinuxAppTracker)**: Vsock 5000 for `.desktop` metadata sync (`CMD_APP_SYNC` = 0x0200) and Launcher3 synthetic shortcut generation.
- **Window Management**: `LinuxAppProxyActivity` dynamically maps discrete Task IDs (up to 20 concurrent) to Guest Wayland surfaces and binds virtio-gpu dma-bufs to Host `SurfaceControl`.

## Iteration Summary
- **Iteration 1**: Initial implementation by Worker 1. Gate evaluation: FAIL (Reviewers requested real inotify watcher, Vsock packet serialization, C++ GPU fence completion, Task ID limit re-launch fix, SurfaceView compilation fix).
- **Iteration 2**: Complete defect remediation by Worker 2. Gate evaluation: **PASS** (Reviewer 3, Reviewer 4, Challenger 3, Challenger 4 APPROVED; Auditor 2 CLEAN).
