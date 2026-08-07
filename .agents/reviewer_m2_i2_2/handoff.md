# Handoff Report: Milestone M2 Independent Review & Adversarial Audit

**Reviewer**: Reviewer 2 (`reviewer_m2_i2_2`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m2_i2_2`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Milestone**: M2 (AVF Guest Setup & CE Storage Encryption)  
**Verdict**: **APPROVE**  

---

## 1. Observation

Direct evidence collected from code inspection, architectural verification, and command outputs:

### 1.1 4-Layer Storage Layout (`F-R2-002`) & LUKS2 CE Encryption (`F-R2-003`)
- **Files Inspected**:
  - `guest/scripts/init_storage_layout.sh`: Allocates `base_rootfs.img` (2500MB RO), `custom_overlay.img` (4000MB RW overlayfs upperdir), `user_home.img` (5000MB LUKS2 container formatted with `aes-xts-plain64` and 512-bit key size), and `vm_state.snapshot` placeholder.
  - `guest/config/vm_config.json`: Formats disk configurations binding `base_rootfs` (RO), `custom_overlay` (RW), `user_home` mapped to `/dev/mapper/user_home_decrypted`, and `snapshot` path.
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java`: Implements HKDF-SHA256 key derivation (`derive512BitKey`) deriving a 64-byte key for LUKS2, validates `LUKS\xba\xbe` magic header, and includes memory zeroization (`wipeMemoryKey` / `Arrays.fill`).
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`: Implements `getOrGeneratePersistentMasterKey(userId)` storing/loading key material at `/data/system/users/<userId>/linux_ce_master.key`, derives LUKS key on unlock (`onUserUnlocked`), and zeroizes key bytes on lock (`onUserLocked`).

### 1.2 AVF crosvm Launcher & Pre-flight Safety Checks (`F-R2-001`)
- **File Inspected**: `guest/scripts/launch_vm.sh`
- **Pre-flight Checks**:
  1. Checks `/dev/kvm` character device availability; exits with code 1 on missing device.
  2. Parses `/proc/meminfo` (`MemAvailable`) to enforce 4096MB RAM requirement; exits with code 2 on low memory.
  3. Uses POSIX file locks (`flock -n 200`, `flock -n 201`) on `base_rootfs.img` and `custom_overlay.img` to prevent concurrent instance races; exits with code 3 on lock collision.
- **VM Execution**: Constructs crosvm CLI parameters with `--cid 3`, `--cpus 4`, `--mem 4096`, `--kernel /apex/com.android.virt/etc/vmlinux`, and passes single-use auth token via kernel parameter `android_bridge.token=${AUTH_TOKEN}`.

### 1.3 Vsock Binary Framing & HMAC-SHA256 Handshake (`F-R2-004` & `F-R2-005`)
- **Files Inspected**:
  - `system/linux_bridge/vsock_framing.h` & `.cpp`: Defines `struct VsockFrameHeader` with `__attribute__((packed))` (exactly 13 bytes: magic 4B, frameType 1B, payloadLength 4B, sequenceId 4B). Enforces `VSOK_MAGIC = 0x56534F4B`, `MAX_PAYLOAD_SIZE = 16MB`, Network Byte Order conversions (`htonl`/`ntohl`), arithmetic overflow protections, and constant-time memory comparisons.
  - `system/linux_bridge/hmac_auth.h` & `.cpp`: Implements authentic FIPS 180-4 SHA-256 compression engine and RFC 2104 HMAC-SHA256 engine for OpenSSL fallback. Enforces 5-second handshake expiration window, single-use token tracking via `sUsedTokens` hash set to block replay attacks, and `SECURITY_ALERT` logging on signature mismatch.
  - `system/linux_bridge/vsock_server.h` & `.cpp`: Restricts connections to Guest CID 3 (`ALLOWED_GUEST_CID`), manages port bindings (5000 Control, 5001 PTY, 5002 Wayland), and blocks PTY/Wayland ports until authentication succeeds.
  - `guest/bridge-agent/src/main.rs`, `src/auth.rs`, `src/vsock.rs`: Rust guest daemon connects to Host CID 2 Port 5000 via POSIX `AF_VSOCK` socket (`libc::socket`, `libc::connect`), computes authentic HMAC-SHA256 via `hmac` and `sha2` crates, transmits 13-byte header + 64-byte payload frame, verifies `MSG_AUTH_SUCCESS`, and immediately wipes token memory using `zeroize::Zeroize`.

### 1.4 SELinux Policies & systemd Service Configuration
- **Files Inspected**:
  - `system/sepolicy/private/linux_manager.te`: Configures `linux_manager` domain, grants `kvm_device` chr_file access, `virtualizationservice` AIDL calls, vsock socket permissions, and strict `neverallow` protections for `efs_file` and system data files.
  - `system/sepolicy/private/linux_bridge.te`: Defines `linux_bridge` domain, unix domain socket at `/dev/socket/linux_bridge`, binder calls with `system_server`, vsock socket permissions, and `neverallow` rules.
  - `guest/systemd/android-bridge-agent.service`: Defines systemd unit `android-bridge-agent.service` with `ExecStart=/usr/bin/android-bridge-agent`, `Restart=always`, `RestartSec=3`, target bindings `WantedBy=default.target multi-user.target`.

### 1.5 Automated Build & Test Execution
- Executed `./scripts/run_m2_verification.sh`:
  - **Stage 1 (File Compliance)**: PASS (All 21 required M2 files present)
  - **Stage 2 (Java Compilation)**: PASS (`LinuxManagerService` and `LinuxCeKeyManager` compiled cleanly)
  - **Stage 3 (Native C++ Compilation & Test)**: PASS (`linux_bridge_test`, `challenger_m2_framing_test`, `challenger_m2_hmac_test`, `challenger_m2_empirical_test` built and executed cleanly)
  - **Stage 4 (Rust Guest Agent)**: PASS (`cargo check` and `cargo test` clean)
  - **Stage 5 (Shell Script Syntax)**: PASS (`launch_vm.sh`, `init_storage_layout.sh`, `guest_mount_overlay.sh` clean)
  - **Stage 6 (Python E2E Suite)**: PASS (430 tests passing with 100.0% pass rate)

---

## 2. Logic Chain

1. **Remediation Verification**:
   - Inspected `guest/bridge-agent/src/main.rs` & `auth.rs`: Verified that fake XOR loops were removed and replaced with authentic `HmacSha256` (from `hmac` crate) and POSIX `AF_VSOCK` sockets (`libc::socket(AF_VSOCK, ...)`).
   - Inspected `system/linux_bridge/hmac_auth.cpp`: Verified that non-cryptographic XOR fallback was replaced with a FIPS 180-4 compliant SHA-256 and RFC 2104 HMAC-SHA256 calculation engine.
   - Inspected `LinuxManagerService.java`: Verified that random key generation on unlock was replaced with persistent key storage at `/data/system/users/<userId>/linux_ce_master.key`.
   - Inspected `tests/e2e/tier1_feature_coverage/test_m2_tier1.py` & `test_m2_tier2.py`: Verified that tests invoke compiled C++ binaries and parse actual repository files rather than using self-certifying mock dictionaries.

2. **Integrity Violation Analysis**:
   - Actively checked for hardcoded test results, facade implementations, shortcut bypasses, self-certifying work, and fabricated log outputs.
   - Found zero integrity violations. All calculations use real cryptographic routines, sockets execute real POSIX calls, storage layout enforces genuine image specifications, and test suites execute compiled binaries.

3. **Architecture & Interface Conformance**:
   - The 4-layer storage layout, VM launcher pre-flight checks, 13-byte packed vsock framing header, 3-port vsock allocation, HMAC-SHA256 handshake, SELinux policy rules, and systemd service configuration conform to `PROJECT.md` and `SCOPE.md` contracts.

---

## 3. Caveats

- **No caveats**: All 5 features of Milestone M2 (F-R2-001 through F-R2-005) have been verified through static code review, structural inspection, build verification, native binary execution, and E2E test suite runs.

---

## 4. Conclusion

Milestone M2 implementation (`AVF Guest Setup & CE Storage Encryption`) is hereby **APPROVED**.

- **Verdict**: **APPROVE**
- **Quality Score**: 100%
- **Integrity Status**: CLEAN (0 facade patterns, 0 hardcoded cheats, 0 self-certifying mocks)

---

## 5. Verification Method

To independently reproduce this verification:

1. **Run Full Verification Suite**:
   ```bash
   ./scripts/run_m2_verification.sh
   ```
   *Expected Output*: `M2 VERIFICATION COMPLETE: ALL 6/6 STAGES PASSED SUCCESSFULLY`.

2. **Run Native C++ Test Executables**:
   ```bash
   ./build_out/bin/linux_bridge_test
   ./build_out/bin/challenger_m2_framing_test
   ./build_out/bin/challenger_m2_hmac_test
   ./build_out/bin/challenger_m2_empirical_test
   ```
   *Expected Output*: All binaries report `PASS` / `ALL PASSED`.

3. **Verify Rust Guest Agent**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cd guest/bridge-agent && cargo check && cargo test
   ```
   *Expected Output*: Clean build and test execution with 0 errors.

4. **Verify E2E Suite Execution**:
   ```bash
   python3 tests/e2e/runner.py
   ```
   *Expected Output*: `430/430 tests PASSED (100.0% pass rate)`.
