# Handoff Report — Milestone M2 (Rust Bridge Agent)

## Verdict: REQUEST_CHANGES

---

## 1. Observation

Direct empirical observations from executing tool commands on `guest/bridge-agent`:

1. **Cargo Test Output**:
   - Command: `export PATH="$HOME/.cargo/bin:$PATH"; cargo test` in `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent`
   - Output:
     ```text
     warning: unused import: `std::time::Duration`
      --> src/ota_rollback.rs:5:5
     warning: function `send_boot_heartbeat` is never used
      --> src/ota_rollback.rs:8:8
     Finished `test` profile [unoptimized + debuginfo] target(s) in 0.02s
      Running unittests src/main.rs (target/debug/deps/android_bridge_agent-ca7eb51403b5c2f8)
     running 0 tests
     test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
     ```
   - Inspection of `guest/bridge-agent/src/` (`main.rs`, `auth.rs`, `vsock.rs`, `ota_rollback.rs`) confirmed zero `#[test]` attribute functions.

2. **CLI Flag Execution**:
   - Command: `./target/debug/android-bridge-agent --help`
   - Output before cancellation:
     ```text
     [Guest Agent] Starting android-bridge-agent daemon...
     [Guest Agent] Auth token extracted (length: 32 bytes)
     [Guest Agent] Connecting to Host CID 2 on Vsock Port 5000...
     [Guest Agent] Vsock connection error: Failed to create AF_VSOCK socket: Operation not supported by device (os error 19)
     [Guest Agent] Vsock handshake failed: Failed to create AF_VSOCK socket: Operation not supported by device (os error 19)
     [Guest Agent] Token zeroized from memory.
     [Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...
     ```
   - Process hung indefinitely in `loop { std::thread::sleep(Duration::from_secs(5)); }` until killed via `manage_task`.
   - Inspection of `guest/bridge-agent/src/main.rs` lines 28–52 shows `main()` does not inspect `std::env::args()`.

3. **Compiler Warnings**:
   - `cargo build` produces 2 compiler warnings:
     - `unused import: std::time::Duration` in `src/ota_rollback.rs:5:5`
     - `function send_boot_heartbeat is never used` in `src/ota_rollback.rs:8:8`

---

## 2. Logic Chain

1. **Observation 1** demonstrates that `cargo test` executes cleanly but reports `0 passed; 0 failed` because no Rust unit tests have been implemented in `guest/bridge-agent/src/`. Without unit test coverage, regressions in token extraction, HMAC signature calculation, or framing header generation cannot be automatically caught by `cargo test`.
2. **Observation 2** shows that `android-bridge-agent` lacks CLI argument parsing (`std::env::args()`). When executed with `--help`, `--version`, or `--dry-run`, it falls through to daemon execution, logs an AF_VSOCK connection failure, zeroizes memory, and enters an infinite sleep loop. This prevents CLI verification and breaks standard executable contracts.
3. **Observation 3** shows that `ota_rollback.rs::send_boot_heartbeat` is dead code because it is never called in `main.rs`, meaning the OTA watchdog boot heartbeat signal is not sent upon daemon startup.
4. From (1), (2), and (3), the Rust bridge agent requires changes before it can be considered fully complete and robust for Milestone M2.

---

## 3. Caveats

- **Host Platform Constraints**: Testing was conducted on a macOS host without native Linux AF_VSOCK kernel support (libc socket creation returned `os error 19`). Actual VSOCK socket read/write operations require execution inside a Linux VM environment or mock transport layer.
- **Scope Limit**: Code modification was strictly out of scope per role instructions ("Review-only — do NOT modify implementation code"). Required fixes must be applied by the implementer.

---

## 4. Conclusion

Verdict: **REQUEST_CHANGES**

### Actionable Requested Changes:
1. **Implement CLI Argument Handling**: Add `--help`, `-h`, `--version`, `-v`, and `--dry-run` flag parsing in `guest/bridge-agent/src/main.rs` before initializing VSOCK socket connections.
2. **Add Rust Unit Tests**: Implement `#[cfg(test)]` modules in `auth.rs`, `vsock.rs`, and `ota_rollback.rs` to unit test HMAC computation, payload packing, token extraction, zeroization, and frame header formatting so `cargo test` runs meaningful assertions.
3. **Connect Boot Heartbeat Signal**: Call `ota_rollback::send_boot_heartbeat()` in `main.rs` upon daemon initialization or resolve unused code warnings.

---

## 5. Verification Method

To verify the requested changes once implemented:

1. **Run Rust Unit Tests**:
   ```bash
   cd guest/bridge-agent
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo test
   ```
   *Expected Output*: At least 5+ passed unit tests covering `auth`, `vsock`, and `ota_rollback` modules with `0 failed`.

2. **Test CLI Help & Version Flags**:
   ```bash
   cd guest/bridge-agent
   cargo build
   ./target/debug/android-bridge-agent --help
   ./target/debug/android-bridge-agent --version
   ```
   *Expected Output*: Prints help usage / version info and exits immediately with status code `0` (does not hang in infinite loop).

3. **Check Compiler Warnings**:
   ```bash
   cargo build
   ```
   *Expected Output*: `Finished dev profile` with `0 warnings`.
