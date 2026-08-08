## 2026-08-08T14:01:39Z

Objective: Investigate RPC dispatchers in guest/bridge-agent/src/pty.rs, src/wayland.rs, and src/portal.rs.
Analyze how incoming connections on Ports 5000, 5001, 5002 are dispatched to real implementations:
- Port 5001: PTY allocation (forkpty/openpty), master/slave fd handling, session framing.
- Port 5002: Wayland socket proxying (/run/user/1000/wayland-0 unix domain socket forwarding).
- Port 5000: Portal RPC handling (Camera, Audio, Location, File access requests).
Formulate exact technical specifications for each module.
Output report to /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_r1_3/handoff.md and report back.
