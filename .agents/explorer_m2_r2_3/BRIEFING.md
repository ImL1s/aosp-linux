# BRIEFING — 2026-08-08T06:20:50Z

## Mission
Investigate 3 defects in aosp-linux (Wayland Full-Duplex Deadlock, Unbounded Memory Allocation in pty.rs, Socket FD Leak in VsockListener) and design detailed fixes for them.

## 🔒 My Identity
- Archetype: Explorer
- Roles: Read-only investigation, defect analysis, fix design synthesis
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3
- Original parent: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Milestone: M2 (Iteration 2)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement fixes directly in source code files.
- Produce detailed report in `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/handoff.md`.
- Use Traditional Chinese (繁體中文) for text communication and report.

## Current Parent
- Conversation ID: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Updated: 2026-08-08T06:20:50Z

## Investigation State
- **Explored paths**: `guest/bridge-agent-m2/src/wayland.rs`, `guest/bridge-agent-m2/src/pty.rs`, `guest/bridge-agent-m2/src/vsock.rs`, `guest/bridge-agent-m2/src/main.rs`
- **Key findings**:
  1. Wayland Deadlock: `proxy_bi_directional` holds `Arc<Mutex<S>>` during blocking `.read()`, starving `write_all`.
  2. Unbounded Memory Allocation: `pty.rs` parses `payload_len` without upper bound, allocating `vec![0u8; len]` directly.
  3. Socket FD Leak: `VsockListener::Vsock` lacks `Drop` implementation to close raw libc socket fd.
  4. PTY Crash: `spawn_shell` passes same `slave_fd` to `Stdio::from_raw_fd` 3 times, causing triple-close IO safety fatal abort.
- **Unexplored areas**: None, all target defects thoroughly analyzed and fix designs produced.

## Key Decisions Made
- Formulated `try_clone()` stream splitting for Wayland proxy (`proxy_split`) removing all mutex locking during reads.
- Specified `MAX_PAYLOAD_SIZE = 65536` check in `pty.rs`.
- Specified `impl Drop for VsockListener` calling `libc::close(*fd)`.
- Specified safe `slave_file.try_clone()?` for PTY `spawn_shell`.

## Artifact Index
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/DISPATCH.md` — Dispatch record
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/BRIEFING.md` — Agent briefing state
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/progress.md` — Progress log
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_3/handoff.md` — Handoff report with 5 components and code-level fix designs
