# Handoff Report: Specification Mining for R3 & R4
**From**: `spec_miner_1`
**To**: Orchestrator & Milestone Implementation Teams (M3 & M4)
**Date**: 2026-08-06T13:58:15+08:00

---

## 1. Observation
- **Specification Inputs Inspected**:
  - `ORIGINAL_REQUEST.md`: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md` (Lines 1-22)
  - `aosp_linux_system_architecture_plan.md`: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md` (Sections 10, 11, 12, 16, 28, 29)
  - `DISPATCH.md`: `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/DISPATCH.md`
- **Key Artifact Created**:
  - `spec.md`: `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/spec.md` (Contains 17 discovered features across R3 & R4, 8 edge cases, IPC protocols, state machines, and acceptance criteria).

---

## 2. Logic Chain
1. **R3 Analysis**:
   - WebView + xterm.js has high memory consumption (>150MB) and noticeable input latency. Thus, R3 specifies a Native Surface Canvas Renderer integrated with `libvterm` / `vte`.
   - Android CJK IMEs (Zhuyin/Cangjie/Pinyin) require multi-stage composition. Standard terminal inputs fail on partial key events. Thus, custom `TerminalInputConnection` extends `BaseInputConnection` to manage inline composing highlight buffers, committing UTF-8 strings to Vsock Port 5001 only upon IME selection/completion.
   - Interactive TUI apps (Vim, tmux) emit `DECSET 1000/1002/1006`. The Touch Modes state machine detects these sequences to toggle between Shell Mode, TUI Mouse Mode (emitting SGR mouse protocol packets), and Touchpad Mode.

2. **R4 Analysis**:
   - Linux GUI apps run under guest `Sommelier` Wayland proxy. Buffers are shared via `virtio-gpu` dma-buf zero-copy memory.
   - To make Linux GUI windows native first-class citizens in Android UI, `LinuxWindowBridgeService` on Vsock Port 5002 communicates with `LinuxAppProxyActivity`, binding `virtio-gpu` buffers directly to `SurfaceControl` with discrete Android Task IDs.
   - App discovery and synchronization requires tracking `.desktop` files in `/usr/share/applications/` via `portal-agent` inotify daemon. Parsed metadata and PNG icons are sent via Vsock Port 5000 to `LinuxAppRegistryService` to generate synthetic shortcuts in Launcher3.

---

## 3. Caveats
- Physical SoC GPU virtualizer quirks (e.g. Qualcomm Adreno vs MediaTek Mali `gfxstream` / `virgl` driver differences) require hardware-in-the-loop verification during Phase 2 bring-up.
- Software rendering fallback (llvmpipe) should be implemented if hardware dma-buf allocation fails on low-end chipsets.

---

## 4. Conclusion
The specifications for **R3 (Native Touch Terminal App & Custom InputConnection IME)** and **R4 (Seamless Wayland GUI Window Forwarding & Recents/Launcher3 Integration)** are fully extracted, structured, and documented in `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/spec.md`.

The implementation requirements, state machine transitions, IPC vsock contracts, 17 feature definitions, 8 edge cases, and verification protocols are complete and ready for execution by M3 and M4 engineering teams.

---

## 5. Verification Method
1. Inspect generated specification file:
   `view_file /Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/spec.md`
2. Verify inclusion of mandatory tables (`Features Discovered` & `Edge Cases`).
3. Verify R3 requirements:
   - Check Native Surface Canvas & `libvterm` parser specs.
   - Check `TerminalInputConnection` CJK IME composing sequence.
   - Check 3 Touch Modes state machine transitions.
4. Verify R4 requirements:
   - Check `Sommelier` -> `virtio-gpu` dma-buf -> `LinuxAppProxyActivity` `SurfaceControl` bridge specs.
   - Check discrete Task ID & Recents overview integration.
   - Check `.desktop` file inotify daemon & Launcher3 synthetic shortcut sync specs.
