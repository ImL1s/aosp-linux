# Handoff Report — Explorer 3 (Milestone M2)

## 1. Observation

Direct observations from codebase inspection, header files, source code, and test infrastructure:

- **Vsock Port & Framing Specification**:
  - `system/linux_bridge/vsock_framing.h:28-30`: Defines `VSOCK_PORT_CONTROL = 5000`, `VSOCK_PORT_PTY = 5001`, `VSOCK_PORT_WAYLAND = 5002`.
  - `system/linux_bridge/vsock_framing.h:43-48`: Header definition `struct VsockFrameHeader` (`magic=0x56534F4B`, `frameType`, `payloadLength`, `sequenceId`, 13 bytes packed).
  - `system/linux_bridge/vsock_server.h:34`: Defines `ALLOWED_GUEST_CID = 3`.
  - `tests/e2e/tier1_feature_coverage/test_m2_tier1.py:218-285`: Tier 1 tests `T1-41` to `T1-45` verify 3-port binding, bi-directional transmission, and independent port unbinding.

- **Host HMAC-SHA256 Auth Implementation**:
  - `system/linux_bridge/hmac_auth.h:31-34`: Payload format `struct AuthHandshakePayload` (`uint8_t token[32]`, `uint8_t signature[32]`).
  - `system/linux_bridge/hmac_auth.cpp:40-46`: Constant-time comparison `constantTimeCompare()` to prevent timing side-channel attacks.
  - `system/linux_bridge/hmac_auth.cpp:116-156`: `HmacAuth::verifyHandshake()` enforces 5-second timeout (`HANDSHAKE_TIMEOUT_SEC = 5.0`), token matching, single-use token check (`isTokenUsed()`, `markTokenUsed()`), and HMAC signature comparison.
  - `system/linux_bridge/vsock_server.cpp:98-114`: `VsockServer::processHandshake()` validates guest CID == 3, executes `HmacAuth::verifyHandshake()`, sets `mAuthenticated = true`, and unlocks payload forwarding for ports 5001 & 5002.
  - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py:349-440`: Tier 2 tests `T2-46` (invalid signature), `T2-47` (replayed token rejection), `T2-48` (5s timeout), `T2-49` (security alert logging), `T2-50` (re-auth after suspend).

- **Guest Bridge Agent Implementation**:
  - `guest/bridge-agent/src/main.rs:8-11`: Constants `VSOCK_PORT_CONTROL = 5000`, `VSOCK_PORT_PTY = 5001`, `VSOCK_PORT_WAYLAND = 5002`, `CID_HOST = 2`.
  - `guest/bridge-agent/src/main.rs:50-61`: `extract_auth_token_from_cmdline()` reads `/proc/cmdline` for `android_bridge.token=` or `linux_auth_token=`.
  - `guest/bridge-agent/src/main.rs:75-79`: `wipe_memory(&mut token_buf)` zeroes out memory after handshake.
  - `guest/bridge-agent/src/main.rs:108-131`: `perform_host_handshake()` implements the 4-step handshake protocol (`MSG_AUTH_INIT`, `MSG_AUTH_RESPONSE`, `MSG_AUTH_VERIFY`, `MSG_AUTH_SUCCESS`).
  - `systemd/android-bridge-agent.service`: Systemd service unit file for guest init.

- **Build Definitions & Existing Scripts**:
  - `system/linux_bridge/Android.bp:1-27`: Defines `cc_binary` `linux_bridge` with `main.cpp`, `socket_server.cpp`, `vsock_framing.cpp`, `vsock_server.cpp`, `hmac_auth.cpp`, linking `liblog`, `libutils`, `libcutils`, `libbinder`, `libcrypto`.
  - `scripts/run_m1_verification.sh`: Model script for M1 verification. `scripts/run_m2_verification.sh` needs to follow a similar 4-step structure for Milestone M2.

---

## 2. Logic Chain

1. **Observation**: `vsock_framing.h` reserves Port 5000 (Control RPC), Port 5001 (PTY Stream), and Port 5002 (Wayland Display), with CID 3 enforced in `vsock_server.h`.
   **Reasoning**: Vsock 3-port allocation isolates control signals, terminal streaming, and GUI window forwarding into separate virtual sockets while restricting connections to guest CID 3.

2. **Observation**: `VsockServer::bindPort()` and `processHandshake()` block Port 5001 and 5002 access until HMAC-SHA256 handshake on Port 5000 succeeds.
   **Reasoning**: Unauthenticated guest processes cannot access PTY stream or Wayland display sockets prior to host verification.

3. **Observation**: `HmacAuth::verifyHandshake()` checks token match, 5s window expiration, token replay in `sUsedTokens`, and constant-time signature match.
   **Reasoning**: Single-use token injection + constant-time HMAC-SHA256 verification guarantees replay attack prevention and timing side-channel resistance.

4. **Observation**: `guest/bridge-agent/src/main.rs` extracts token from `/proc/cmdline`, executes the 4-step handshake, and calls `wipe_memory()` immediately after completion.
   **Reasoning**: Token memory exposure in guest RAM is minimized, completing the security boundary lifecycle.

5. **Observation**: `scripts/run_m1_verification.sh` compiles native C++, verifies required files, runs Java unit tests, and executes native tests.
   **Reasoning**: `scripts/run_m2_verification.sh` must follow the same pattern to build and verify M2 components (including Python E2E suite `runner.py --filter F-R2` and empirical challenger tests).

---

## 3. Caveats

- **OpenSSL Availability**: `hmac_auth.cpp` uses `#if HAS_OPENSSL` to compile with OpenSSL `HMAC()` and `RAND_bytes()` when available, but provides a pure C++ fallback. In production AOSP target builds, `libcrypto` from BoringSSL is linked via `Android.bp`.
- **Rust vs C++ Guest Agent Alignment**: The current repository contains a Rust implementation in `guest/bridge-agent/src/main.rs`. Any alternative C++ agent references (`guest/android-bridge-agent/main.cpp`) must follow the exact 4-step protocol and memory wiping specification detailed in `analysis.md`.

---

## 4. Conclusion

Features **F-R2-004 (Vsock 3-Port Allocation)** and **F-R2-005 (HMAC-SHA256 Auth Handshake)** are fully specified, architected, and supported by existing code structures (`system/linux_bridge/` host daemon, `guest/bridge-agent/` guest daemon, `Android.bp`, E2E test suites, and empirical stress tests).

Implementation recommendations:
1. Workers should ensure host `HmacAuth` and `VsockServer` enforce port isolation, 5-second handshake timeouts, token replay checks, and constant-time signature verification.
2. Workers should ensure guest agent extracts token from `/proc/cmdline`, executes 4-step handshake, and immediately zero-wipes token memory.
3. Reviewers and Challengers should verify `scripts/run_m2_verification.sh` executes structural checks, native C++ tests, E2E python tests (`runner.py --filter F-R2`), and empirical challenger stress tests cleanly.

---

## 5. Verification Method

To independently verify the investigation findings and test suite compliance:

1. **Inspect Header & Source Files**:
   ```bash
   cat system/linux_bridge/vsock_framing.h
   cat system/linux_bridge/vsock_server.h
   cat system/linux_bridge/hmac_auth.h
   cat guest/bridge-agent/src/main.rs
   ```

2. **Run Native Bridge Tests**:
   ```bash
   clang++ -std=c++20 -Wall -Wextra -pthread -I. \
       system/linux_bridge/socket_server.cpp \
       system/linux_bridge/vsock_framing.cpp \
       system/linux_bridge/tests/linux_bridge_test.cpp \
       -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test
   ```

3. **Run E2E Test Suite for Feature F-R2-004 and F-R2-005**:
   ```bash
   python3 tests/e2e/runner.py --filter "F-R2-004"
   python3 tests/e2e/runner.py --filter "F-R2-005"
   ```

4. **Run Empirical Challenger M2 Stress Test**:
   ```bash
   python3 tests/unit/challenger_m2_empirical_stress_test.py
   ```
