# Scope: Milestone M2 — Production Guest Agent Loop (R2)

## Scope Boundary
- `guest/bridge-agent/src/main.rs`
- `guest/bridge-agent/src/auth.rs`
- `guest/bridge-agent/src/vsock.rs`
- `guest/bridge-agent/src/pty.rs`
- `guest/bridge-agent/src/wayland.rs`
- `guest/bridge-agent/src/portal.rs`

## Objective
Implement an active multi-threaded server dispatch loop in `guest/bridge-agent` listening on Vsock Ports 5000, 5001, 5002; remove hardcoded secrets and zero-token fallbacks; abort process on authentication failure; dispatch real PTY, Wayland, and Portal RPC requests.

## Verification Criteria
1. `cargo check` and `cargo test` pass in `guest/bridge-agent`.
2. Auth failure triggers immediate `std::process::exit(1)`.
3. Vsock ports 5000, 5001, 5002 are actively bound and served multi-threaded.
4. Reviewers APPROVE, Challenger confirms correctness, Forensic Auditor gives CLEAN verdict.
