# Empirical Challenge Report — Milestone M2 (Rust Bridge Agent)

## Challenge Summary

**Overall risk assessment**: HIGH

Empirical testing of `guest/bridge-agent` revealed several functional gaps and failure modes in the Rust daemon:
1. `cargo test` runs without compilation errors, but contains **0 unit tests** (zero assertions or test functions across `main.rs`, `auth.rs`, `vsock.rs`, and `ota_rollback.rs`).
2. The `android-bridge-agent` executable lacks CLI flag parsing (`--help`, `--version`, `--dry-run`). When invoked with `--help` or `--version`, it ignores arguments, attempts to connect to non-existent AF_VSOCK sockets, and hangs indefinitely in an infinite `loop { sleep(5) }`.
3. `ota_rollback.rs` contains an uncalled function (`send_boot_heartbeat`), producing dead code compiler warnings.
4. `main.rs` hardcodes the HMAC shared secret (`shared_secret_key_32bytes_long!!`) rather than reading it from security configuration or key storage.

---

## Challenges

### 1. [High Risk] Lack of CLI Argument Parsing (`--help` / `--version` / `--dry-run` Hangs)

- **Assumption challenged**: Executable daemon supports basic CLI inspection (`--help`, `--version`, or `--dry-run`) and terminates gracefully.
- **Attack scenario**: Calling `./target/debug/android-bridge-agent --help` or `--version` in scripts, systemd readiness checks, or CLI environments.
- **Blast radius**: Process hangs indefinitely in background (`loop { std::thread::sleep(Duration::from_secs(5)); }`), blocking terminal scripts or health checks.
- **Mitigation**: Implement `std::env::args()` handling in `main.rs` to handle `-h/--help`, `-v/--version`, and `--dry-run` before initializing VSOCK socket connections.

### 2. [Medium Risk] Zero Cargo Unit Tests in Rust Bridge Agent

- **Assumption challenged**: `cargo test` in `guest/bridge-agent/` verifies Rust HMAC authentication and VSOCK frame packing logic.
- **Attack scenario**: Refactoring or updating dependencies in `Cargo.toml` without Rust unit tests to catch regressions.
- **Blast radius**: `cargo test` returns `0 passed; 0 failed; 0 ignored`, providing false confidence in test coverage.
- **Mitigation**: Add `#[cfg(test)]` modules in `auth.rs`, `vsock.rs`, and `ota_rollback.rs` to unit test HMAC computation, payload packing, token extraction, zeroization, and frame header generation.

### 3. [Medium Risk] Unused Code Warning & Disconnected OTA Heartbeat Watchdog

- **Assumption challenged**: `ota_rollback.rs::send_boot_heartbeat` is integrated into the daemon's boot routine.
- **Attack scenario**: System boots, but `send_boot_heartbeat()` is never called by `main()`.
- **Blast radius**: Compiler outputs `warning: function send_boot_heartbeat is never used`. Boot completion heartbeat signal is never dispatched to Host Watchdog.
- **Mitigation**: Invoke `ota_rollback::send_boot_heartbeat()` during initialization or after successful VSOCK handshake in `main.rs`.

### 4. [Low Risk] Hardcoded Shared Secret Key in Binary

- **Assumption challenged**: HMAC secret key is dynamically provisioned per VM session.
- **Attack scenario**: Binary extraction or static analysis reveals static HMAC secret `b"shared_secret_key_32bytes_long!!"`.
- **Blast radius**: Hardcoded credential reuse across multiple VM instances.
- **Mitigation**: Pass secret key via environment variable, command-line parameter, or secure storage file.

---

## Stress Test Execution Results

| Test Scenario | Command / Target | Expected Behavior | Actual Behavior | Result |
|---------------|------------------|-------------------|-----------------|--------|
| **Rust Unit Tests** | `cargo test` | Execute unit test suite and verify assertions | Executed clean, but `0 passed; 0 failed` (0 tests written) | **WARN** |
| **CLI `--help`** | `./android-bridge-agent --help` | Print usage and exit `0` | Ignored argument, failed vsock, entered infinite `loop` | **FAIL** |
| **CLI `--version`** | `./android-bridge-agent --version` | Print version and exit `0` | Ignored argument, failed vsock, entered infinite `loop` | **FAIL** |
| **Cargo Build** | `cargo build` | Compile without warnings | Compiled cleanly with 2 warnings (`unused import`, `dead_code`) | **WARN** |
| **Token Zeroization** | `auth::zeroize_token()` | Zero out byte array | `zeroize` crate correctly wipes buffer | **PASS** |
| **HMAC Construction** | `auth::construct_handshake_payload()` | Produce 64-byte payload (32B token + 32B HMAC) | Correctly validates 32B length and produces 64B payload | **PASS** |

---

## Unchallenged Areas

- **Kernel AF_VSOCK Transmission inside Guest VM**: Unable to perform live hypervisor AF_VSOCK packet exchange due to testing environment lacking nested KVM hardware virtualization on macOS host. Verified native C++ vsock server logic separately.
