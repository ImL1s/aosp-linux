# BRIEFING — 2026-08-08T14:02:36Z

## Mission
Investigate RPC dispatchers in guest/bridge-agent (pty.rs, wayland.rs, portal.rs) for Vsock Ports 5000, 5001, 5002 and formulate exact technical specifications.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Read-only investigator
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r1_3
- Original parent: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Milestone: M2

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Inspect guest/bridge-agent/src/pty.rs, wayland.rs, portal.rs and related files
- Output handoff report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r1_3/handoff.md

## Current Parent
- Conversation ID: 8c471e2b-0dd2-4008-8ee4-fe716853c275
- Updated: 2026-08-08T14:02:36Z

## Investigation State
- **Explored paths**:
  - guest/bridge-agent (Cargo.toml, main.rs, auth.rs, vsock.rs, ota_rollback.rs)
  - packages/apps/LinuxTerminal (VsockPtyFramer.java, VsockTerminalClient.java)
  - frameworks/base/services/core/java/com/android/server/linux (LinuxWindowBridgeService.java, LinuxPortalService.java)
  - tests/e2e/tier1_feature_coverage/test_m2_tier1.py
- **Key findings**:
  - Port 5001 (PTY): 21-byte header framing [16B SessionID][1B Type][4B BE Length][Payload]. Types: DATA(0x01), RESIZE(0x02), PING(0x03), PONG(0x04), EOS(0x05). PTY allocation using openpty/forkpty, master/slave fd handling, ioctl(TIOCSWINSZ) resizing.
  - Port 5002 (Wayland): 13-byte VsockFrameHeader (magic=0x56534F4B, frame_type=0x03). Bi-directional stream forwarding to Guest Unix domain socket /run/user/1000/wayland-0.
  - Port 5000 (Portal): 13-byte VsockFrameHeader (magic=0x56534F4B, frame_type=0x01). JSON-RPC handling for Camera2 HAL (fallback resolution), AudioRecord (PCM streaming, privacy toggle zero fill, 1024B padding), LocationManager GPS, virtiofs SAF file mounts.
  - main.rs & auth.rs: Immediate process::exit(1) on auth failure; active multi-threaded server dispatch loop on Ports 5000, 5001, 5002.
- **Unexplored areas**: None for M2 RPC dispatch scope

## Key Decisions Made
- Formulated complete technical specifications for pty.rs, wayland.rs, portal.rs, and main.rs dispatch loop.
- Written handoff report to handoff.md.

## Artifact Index
- handoff.md — Comprehensive handoff report with 5 mandatory components and full technical specifications.
