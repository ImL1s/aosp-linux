# Handoff Report: Phase B Remediation (VM Launch Script, HMAC Auth & LinuxManagerService Facade Cleanup)

## 1. Observation

### File 1: `guest/scripts/launch_vm.sh`
- Removed all `TEST_MODE` checks, logic, and `exec sleep 3600`.
- Added fail-fast check for `/dev/kvm`:
  ```bash
  if [ ! -c /dev/kvm ]; then
      echo "ERROR: KVMException: /dev/kvm hardware device node not available" >&2
      exit 1
  fi
  ```
- Added fail-fast check for `crosvm`:
  ```bash
  if ! command -v crosvm >/dev/null 2>&1; then
      echo "ERROR: CrosvmNotFound: crosvm binary not found in PATH" >&2
      exit 4
  fi
  ```
- Replaced simulated fallback execution with direct invocation: `exec crosvm run ...`.

### File 2: `guest/bridge-agent/src/auth.rs` & `main.rs`
- Removed `#[allow(dead_code)]` from `sha256`, `HmacSha256`, and `compute_hmac_response`.
- Implemented authentic HMAC-SHA256 challenge/response verification in `perform_handshake`:
  - Reads 16-byte nonce / challenge from stream into `challenge`.
  - Computes expected signature: `HmacSha256::compute_hmac_response(secret, &challenge)`.
  - Reads 32-byte signature from client stream into `signature`.
  - Verifies received signature matches expected signature in constant time using `verify_token(&signature, &expected)`.
  - Removed raw byte equality comparison (`token_buf == secret`).
- In `guest/bridge-agent/src/main.rs`, replaced `std::process::exit(1)` on connection handshake failure with log output and `return`, terminating the connection thread cleanly without crashing the daemon process.
- Updated unit tests and empirical tests in `auth.rs` and `empirical_tests.rs`. All 31 Rust unit tests executed and passed (`cargo test` -> `31 passed; 0 failed`).

### File 3: `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` & `LinuxBridgeService.java`
- `getInstalledApps()`: Removed hardcoded fallback app list (`org.gnome.Terminal`, `org.mozilla.firefox`). When VM state is not `STATE_RUNNING` or `mBridgeService` is null/disconnected, returns `Collections.emptyList()`.
- `launchLinuxApp()`: Checks `mBridgeService != null && mBridgeService.isConnected()`. If not connected, returns `false` (no longer returns fake success `true`).
- `installGuestImage()`: Implemented genuine file streaming/writing from `ParcelFileDescriptor imageFd` to `/data/misc/linux/base_rootfs.img.tmp` via `ParcelFileDescriptor.AutoCloseInputStream` and `FileOutputStream`. Validates total written byte count against `size`, and performs atomic rename via `java.nio.file.Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`. Returns `false` on any I/O error, size mismatch, or null descriptor.
- `LinuxBridgeService.java`: Removed hardcoded fallback app list from `getCachedAppList()`.

## 2. Logic Chain
1. **Launch Script Fail-Fast Integrity**: Removing `TEST_MODE` and simulated sleep fallbacks ensures that invalid hardware (`/dev/kvm`) or missing binaries (`crosvm`) immediately produce genuine error codes (exit code 1 for missing KVM node, exit code 4 for missing crosvm binary) rather than sleeping or fabricating success.
2. **HMAC Cryptographic Authentication**: Replaces token equality comparison with genuine RFC 2104 HMAC-SHA256 challenge-response verification over 16-byte nonces. Suppressing `std::process::exit(1)` on individual handshake failures prevents client-initiated denial-of-service crashes of the bridge-agent daemon process.
3. **Framework Facade Cleanup**: Removing hardcoded app fallback lists (`org.gnome.Terminal`, `org.mozilla.firefox`) forces the SystemServer service to accurately reflect real VM state (returning empty lists when stopped/disconnected). Real disk writing and size validation in `installGuestImage` ensures real rootfs images are safely written and atomically committed to disk.

## 3. Caveats
- No caveats. All changes were validated against the full Rust test suite and Python E2E Tier 1 & Tier 2 test suites.

## 4. Conclusion
Phase B remediation tasks (1, 2, and 3) have been fully implemented, validated, and confirmed clean:
- `guest/scripts/launch_vm.sh`: 0 occurrences of `TEST_MODE`.
- `guest/bridge-agent/src/auth.rs`: 0 occurrences of `allow(dead_code)`.
- `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`: 0 occurrences of hardcoded app fallback strings (`org.gnome.Terminal`, `org.mozilla.firefox`).

## 5. Verification Method

To independently verify the implementations:

1. **Rust Unit Tests & Verification**:
   ```bash
   cd guest/bridge-agent
   ~/.cargo/bin/cargo check
   ~/.cargo/bin/cargo test
   ```
   *Expected Output*: 31 tests passed, 0 failed.

2. **Python E2E Tier 1 & Tier 2 Suite**:
   ```bash
   python3 tests/e2e/runner.py --tier 1
   python3 tests/e2e/runner.py --tier 2
   ```
   *Expected Output*: Tier 1 (20/20 passed, 100%), Tier 2 (10/10 passed, 100%).

3. **Absence Checks**:
   ```bash
   grep -n "TEST_MODE" guest/scripts/launch_vm.sh
   grep -n "allow(dead_code)" guest/bridge-agent/src/auth.rs
   grep -n "org.gnome.Terminal" frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java
   ```
   *Expected Output*: No results found for all three grep checks.
