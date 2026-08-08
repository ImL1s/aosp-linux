# BRIEFING — 2026-08-08T06:21:00Z

## Mission
Investigate PTY defects (PTY IO Safety violation and FD use-after-close / lifecycle) in `src/pty.rs` and design verified fixes for M2 Iteration 2.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Explorer 2 (Milestone M2 Iteration 2)
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2
- Original parent: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Milestone: M2 Iteration 2

## 🔒 Key Constraints
- Read-only investigation — do NOT modify codebase source files directly (only write to working directory `.agents/explorer_m2_r2_2/`)
- Language constraint: 繁體中文 for communication
- Output report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/handoff.md`
- Send message back to parent agent via `send_message`

## Current Parent
- Conversation ID: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Updated: 2026-08-08T06:21:00Z

## Investigation State
- **Explored paths**:
  - `guest/bridge-agent-m2/src/pty.rs` & `guest/bridge-agent/src/pty.rs`
  - Challenger handoffs `challenger_m2_r1_1/handoff.md` and `challenger_m2_r1_2/handoff.md`
- **Key findings**:
  - **Defect 1**: `spawn_shell` in `src/pty.rs` calls `Stdio::from_raw_fd(slave_fd)` 3 times for stdin, stdout, stderr using the same `slave_fd`. Standard library I/O Safety (`OwnedFd`) detects multiple owned file descriptor drops closing the same FD and triggers `std::process::abort()` (SIGABRT -6). Fix requires `libc::dup(slave_fd)` to allocate 3 distinct file descriptors.
  - **Defect 2**: `PtyMaster` drop closes `master_fd` while `_reader_thread` is actively looping on `libc::read(master_fd, ...)`. This creates an FD use-after-close race condition where recycled FDs can be read by `_reader_thread`. Fix requires duping `master_write_fd` into `master_read_fd` for `_reader_thread`, managing proper drop/close order, killing/reaping child process (`child.kill()`, `child.wait()`), and joining `reader_handle`.
- **Unexplored areas**: None within the PTY defect scope.

## Key Decisions Made
- Completed root-cause investigation for Defects 1 & 2.
- Designed comprehensive, verified Rust patch for `src/pty.rs` featuring safe FD duplication (`libc::dup`), bounded memory allocation (`MAX_PAYLOAD_LEN`), explicit child process teardown, and clean thread join.
- Output handoff report to `.agents/explorer_m2_r2_2/handoff.md`.

## Artifact Index
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/DISPATCH.md` — Dispatch log
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/BRIEFING.md` — Agent briefing state
- `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r2_2/handoff.md` — Handoff report with 5-component structure and patch design
