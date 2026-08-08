# Survey & Investigation Report: R1 (Real AVF VM Launch) & R2 (Production Guest Agent Loop)

**Explorer Agent**: Explorer 1  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_1`  
**Date**: 2026-08-08  

---

## 1. Observation

### 1.1 R1 (Real AVF VM Launch) Observations

1. **`LinuxManagerService.java`** (`frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`)
   - Lines 305–329: `startVm()` sets `mCurrentState = LinuxManager.STATE_STARTING`, schedules a 15-second timeout (`BOOT_TIMEOUT_MS = 15000L`), and calls `mBridgeService.notifyVmStarting()`.
   - Lines 151–161: `notifyVmStarted()` is triggered by `mBridgeService.onVmHandshakeCompleted()`, transitioning the state from `STATE_STARTING` to `STATE_RUNNING`.
   - Lines 170–180: `handleBootTimeout()` sets state to `STATE_ERROR` if handshake does not occur within 15 seconds.

2. **`LinuxBridgeService.java`** (`frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`)
   - Lines 115–130: Connects to `/dev/socket/linux_bridge` via LocalSocket.
   - Lines 271–273: `notifyVmStarting()` sends `CMD_VM_START` (0x0001) over the socket.

3. **`socket_server.cpp` (Native Daemon `linux_bridge`)** (`system/linux_bridge/socket_server.cpp`)
   - Lines 173–177: **Simulated VM Launch & Fake Handshake**:
     ```cpp
     if (header.cmdType == 0x0001) { // CMD_VM_START
         // Respond with CMD_HANDSHAKE_COMPLETE
         std::vector<uint8_t> response = serializePacket(0x0003, header.transactionId, {});
         write(clientFd, response.data(), response.size());
     }
     ```
     *Finding*: When `CMD_VM_START` is received, `socket_server.cpp` immediately writes back `CMD_HANDSHAKE_COMPLETE` (0x0003). It does **NOT** spawn `launch_vm.sh`, execute `crosvm`, or invoke AVF `IVirtualizationService` AIDL calls.

4. **`launch_vm.sh`** (`guest/scripts/launch_vm.sh`)
   - Lines 88–99:
     ```bash
     if command -v crosvm >/dev/null 2>&1; then
         crosvm run \
           --cid "$CID" \
           --cpus "$CPUS" \
           --mem "$REQ_RAM_MB" \
           --kernel "$KERNEL_PATH" \
           --initrd "$INITRD_PATH" \
           --params "${CMDLINE}" \
           --shared-dir "/data/media/0/LinuxShared:linux_shared:type=fs:cache=always:timeout=1" \
           --rodisk "$BASE_IMG" \
           --rwdisk "$OVERLAY_IMG" \
           --rwdisk "$HOME_MAPPER"
     ```
     *Finding*: The script properly parses configuration, acquires file locks (`flock`), checks available host RAM (`/proc/meminfo`), checks `/dev/kvm`, constructs kernel parameters, and launches `crosvm`. However, it is completely disconnected from `linux_bridge` and `LinuxManagerService`.

---

### 1.2 R2 (Production Guest Agent Loop) Observations

1. **`guest/bridge-agent/src/main.rs`** (`guest/bridge-agent/src/main.rs`)
   - Lines 48–52: **Fake Server Loop**:
     ```rust
     println!("[Guest Agent] Listening on Vsock Ports (5000 Control, 5001 PTY, 5002 Wayland)...");
     
     // Main event loop handling RPC requests
     loop {
         std::thread::sleep(Duration::from_secs(5));
     }
     ```
     *Finding*: The daemon logs that it is listening on Vsock ports, but instead enters an infinite sleeping loop (`sleep(5s)`). No listening sockets are bound and no incoming RPC requests are handled.

2. **Hardcoded Secrets & Token Fallback**:
   - `guest/bridge-agent/src/main.rs` line 72:
     ```rust
     let shared_secret = b"shared_secret_key_32bytes_long!!";
     ```
   - `guest/bridge-agent/src/auth.rs` lines 21–22:
     ```rust
     // Fallback default 32-byte zero token for testing environment when /proc/cmdline does not contain token
     Ok(vec![0u8; 32])
     ```
     *Finding*: Hardcoded secret bytes are used for HMAC generation, and token extraction falls back to 32 zero bytes when `/proc/cmdline` does not contain `android_bridge.token=`.

3. **Handshake Error Handling**:
   - `guest/bridge-agent/src/main.rs` lines 36–40:
     ```rust
     if let Err(e) = perform_host_handshake(&mut token_buf) {
         eprintln!("[Guest Agent] Vsock handshake failed: {}", e);
     } else {
         println!("[Guest Agent] Host authenticated successfully.");
     }
     ```
     *Finding*: When `perform_host_handshake` fails, `main()` prints an error to stderr and continues into the loop anyway, rather than aborting process execution (`std::process::exit(1)`).

4. **Missing RPC Handlers for PTY, Wayland, and Portals**:
   - `guest/bridge-agent/src/vsock.rs` defines endpoint constants (`PORT_CONTROL = 5000`, `PORT_PTY = 5001`, `PORT_WAYLAND = 5002`), but `bridge-agent` has zero code to handle incoming connections or data frames for PTY allocation (`posix_openpt`), Wayland socket proxying (`/run/user/1000/wayland-0`), or Portal RPCs.

---

## 2. Logic Chain

1. **R1 Analysis**:
   - *Observation*: `LinuxManagerService` calls `LinuxBridgeService.notifyVmStarting()`, which sends `CMD_VM_START` (0x0001) over socket `/dev/socket/linux_bridge`.
   - *Observation*: `socket_server.cpp` (lines 173–177) catches `CMD_VM_START` and instantly responds with `CMD_HANDSHAKE_COMPLETE` (0x0003).
   - *Observation*: `launch_vm.sh` exists in `guest/scripts/launch_vm.sh` with full `crosvm` invocation logic, but is never executed by `socket_server.cpp` or `linux_bridge`.
   - *Reasoning*: The current VM lifecycle implementation is a mock/simulation where `socket_server.cpp` pretends the VM started immediately. To make AVF VM launching real, `linux_bridge` (or a helper process invoked by `socket_server.cpp`) must execute `launch_vm.sh` with the generated security token, track the child PID/process status, and only return `CMD_HANDSHAKE_COMPLETE` once the real guest connects over Vsock and completes the HMAC handshake.

2. **R2 Analysis**:
   - *Observation*: In `guest/bridge-agent/src/main.rs` (lines 48–52), after `perform_host_handshake` completes or fails, the code enters `loop { std::thread::sleep(Duration::from_secs(5)); }`.
   - *Observation*: In `main.rs` line 72, `shared_secret` is hardcoded as `b"shared_secret_key_32bytes_long!!"`.
   - *Observation*: In `main.rs` lines 36–40, handshake errors are printed to stderr, but execution continues into the dummy loop.
   - *Observation*: In `auth.rs` lines 21–22, missing cmdline token returns `vec![0u8; 32]`.
   - *Reasoning*: `bridge-agent` lacks a real event/dispatch loop. To achieve production readiness:
     1. Remove all hardcoded secrets and all-zero token fallbacks.
     2. Require authentication failure to abort the process immediately (`std::process::exit(1)`).
     3. Replace `loop { sleep(5s); }` with a multi-threaded vsock server loop listening on Ports 5000 (Control/Portal), 5001 (PTY sessions), and 5002 (Wayland proxy).

---

## 3. Caveats

- **Host Kernel & Hardware KVM Requirements**: Testing real `crosvm` execution requires an ARM64 Linux/Android environment with `/dev/kvm` enabled and `crosvm` binary installed at `/apex/com.android.virt/bin/crosvm` or in system PATH. In environments without `/dev/kvm`, `launch_vm.sh` returns exit code 1 unless `TEST_MODE=1` is exported.
- **SELinux Policies**: `linux_manager.te` and `linux_bridge.te` grant basic KVM and vsock permissions, but launching subprocesses or executing scripts from native daemons may require additional `exec` rules depending on enforcement mode.

---

## 4. Conclusion

- **Defect R1**: Can be fully resolved by modifying `system/linux_bridge/socket_server.cpp` to remove the immediate `CMD_HANDSHAKE_COMPLETE` response. Instead, `socket_server.cpp` must invoke `guest/scripts/launch_vm.sh` (passing the generated token), manage the spawned `crosvm` process, and defer `CMD_HANDSHAKE_COMPLETE` until the real guest completes the Vsock handshake.
- **Defect R2**: Can be fully resolved by refactoring `guest/bridge-agent`:
  1. Mandate token presence in `/proc/cmdline` and eliminate all-zero fallbacks & hardcoded secret strings.
  2. Cause handshake failure to abort the process (`exit(1)`).
  3. Implement a multi-threaded Vsock server loop handling Port 5000 (Control/Portal), Port 5001 (PTY terminal session allocation & I/O), and Port 5002 (Wayland socket proxying).

---

## 5. Verification Method

1. **Verify R1 (Real VM Launch & Integration)**:
   - Check `system/linux_bridge/socket_server.cpp` lines 173–177: Ensure fake `CMD_HANDSHAKE_COMPLETE` output is removed.
   - Execute `bash -n guest/scripts/launch_vm.sh` to verify syntax.
   - Verify `launch_vm.sh` invocation with test token:
     `TEST_MODE=1 bash guest/scripts/launch_vm.sh /data/misc/linux/vm_config.json testtoken123`
   - Run unit tests:
     `./build_out/bin/linux_bridge_test`

2. **Verify R2 (Guest Agent Loop & Auth Abort)**:
   - Run Cargo check on `bridge-agent`:
     `cd guest/bridge-agent && cargo check`
   - Run HMAC auth tests:
     `./build_out/bin/challenger_m2_hmac_test`
   - Inspect `guest/bridge-agent/src/main.rs`: Ensure `loop { sleep(5s); }` is replaced with multi-port server listening loop and that handshake failures invoke `std::process::exit(1)`.
