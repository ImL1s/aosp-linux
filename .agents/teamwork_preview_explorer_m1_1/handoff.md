# Milestone M1 (Real AVF VM Launch - R1) Technical Implementation & Analysis Report

**Agent**: Explorer 1  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_m1_1`  
**Date**: 2026-08-08  
**Scope**: `system/linux_bridge/socket_server.cpp`, `system/linux_bridge/socket_server.h`, `guest/scripts/launch_vm.sh`, `system/linux_bridge/tests/linux_bridge_test.cpp`

---

## 1. Observation

Direct code observations across affected files:

### 1.1 `system/linux_bridge/socket_server.cpp`
- **Lines 173–177**: Current fake handshake implementation:
  ```cpp
  if (header.cmdType == 0x0001) { // CMD_VM_START
      // Respond with CMD_HANDSHAKE_COMPLETE
      std::vector<uint8_t> response = serializePacket(0x0003, header.transactionId, {});
      write(clientFd, response.data(), response.size());
  }
  ```
  *Finding*: Immediately writes back `CMD_HANDSHAKE_COMPLETE` (0x0003) to the Java `LinuxBridgeService` upon receiving `CMD_VM_START` (0x0001). No VM process is launched, no security token is generated, and no Vsock authentication occurs.

### 1.2 `guest/scripts/launch_vm.sh`
- **Lines 5–6**: Parameter defaults:
  ```bash
  CONFIG_FILE="${1:-/data/misc/linux/vm_config.json}"
  AUTH_TOKEN="${2:-0000000000000000000000000000000000000000000000000000000000000000}"
  ```
- **Lines 82–86**: Kernel parameter construction:
  ```bash
  CMDLINE="console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token=${AUTH_TOKEN} panic=1 quiet"
  ```
- **Lines 88–100**: `crosvm` invocation:
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
  *Finding*: `launch_vm.sh` has full resource checks (RAM, `/dev/kvm`, disk locks via `flock`) and `crosvm` startup logic, but line 89 lacks an `exec` prefix (running `crosvm` as a child of shell rather than replacing process image), and is currently disconnected from `socket_server.cpp`.

### 1.3 `system/linux_bridge/vsock_server.cpp` & `hmac_auth.cpp`
- **`VsockServer` capabilities**:
  - `setAuthToken(token, secret)`: Registers expected token and shared secret.
  - `processHandshake(cid, payload)`: Validates HMAC-SHA256 signature from Guest CID 3. Sets `mAuthenticated = true` on success.
  - `resetSession()`: Resets authentication state.
  *Finding*: `VsockServer` has complete HMAC verification logic ready, but `socket_server.cpp` currently does not link `CMD_VM_START` to `setAuthToken()` nor defer `CMD_HANDSHAKE_COMPLETE` to `processHandshake()`.

---

## 2. Logic Chain

From the observations above:

1. **Root Cause**: `socket_server.cpp` simulates the entire VM boot sequence by synchronously returning `CMD_HANDSHAKE_COMPLETE` when `CMD_VM_START` is received.
2. **Required Behavior**:
   - `CMD_VM_START` must trigger `socket_server.cpp` to:
     a) Generate a 32-byte security token and shared secret.
     b) Register the token/secret with `VsockServer::setAuthToken()`.
     c) Spawn `launch_vm.sh` passing the 64-char hex token.
     d) Track the child `crosvm` PID.
     e) **Defer** replying with `CMD_HANDSHAKE_COMPLETE`.
   - Guest VM boots, `bridge-agent` reads `android_bridge.token` from `/proc/cmdline`, and connects over AF_VSOCK port 5000 to perform HMAC authentication.
   - Upon successful HMAC verification in `VsockServer::processHandshake()`, `socket_server.cpp` writes `CMD_HANDSHAKE_COMPLETE` (0x0003) to the waiting `clientFd`.
   - On `CMD_VM_STOP` (0x0002), `socket_server.cpp` terminates the child process (`SIGTERM` -> `SIGKILL`), calls `vsockServer.resetSession()`, and resets state.

---

## 3. Detailed Technical Implementation Plan

### 3.1 `socket_server.cpp` & `socket_server.h` Refactoring

#### A. State Management & Struct Updates in `socket_server.h`:
Add fields to `SocketServer` class:
```cpp
enum class VmState {
    STOPPED,
    STARTING,
    RUNNING
};

private:
    std::mutex mVmMutex;
    VmState mVmState{VmState::STOPPED};
    pid_t mVmPid{-1};
    int mPendingClientFd{-1};
    uint32_t mPendingTransactionId{0};
    int mVmLogPipeFd{-1};
    std::thread mVmLogThread;
    std::thread mChildWatcherThread;
    
    // Reference to VsockServer (passed in constructor or set via setter)
    VsockServer* mVsockServer{nullptr};
```

#### B. `CMD_VM_START` (0x0001) Handling in `socket_server.cpp`:
1. Lock `mVmMutex`. If `mVmState != VmState::STOPPED`, reject request or return error packet.
2. Generate 32-byte token and 32-byte secret:
   ```cpp
   std::vector<uint8_t> token = HmacAuth::generateRandomToken();
   std::vector<uint8_t> secret = {'s', 'e', 'c', 'r', 'e', 't', '_', 'b', 'r', 'i', 'd', 'g', 'e', '_', 'k', 'e', 'y'};
   std::string tokenHex = HmacAuth::hexEncode(token);
   ```
3. Register token with `VsockServer`:
   ```cpp
   if (mVsockServer) {
       mVsockServer->setAuthToken(token, secret);
   }
   ```
4. Save pending handshake client descriptor:
   ```cpp
   mPendingClientFd = clientFd;
   mPendingTransactionId = header.transactionId;
   mVmState = VmState::STARTING;
   ```
5. Launch `guest/scripts/launch_vm.sh`:
   - Create stdout/stderr pipe: `int pipefds[2]; pipe2(pipefds, O_CLOEXEC);`.
   - Setup `posix_spawn_file_actions_t` to redirect child stdout & stderr to `pipefds[1]`.
   - Arguments: `{"/system/bin/sh", "/guest/scripts/launch_vm.sh", "/data/misc/linux/vm_config.json", tokenHex.c_str(), nullptr}`.
   - Execute `posix_spawn(&mVmPid, "/system/bin/sh", &fileActions, nullptr, const_cast<char**>(argv), environ)`.
   - Close `pipefds[1]` in host parent.
   - Spawn `mVmLogThread` to read `pipefds[0]` lines and print to `std::cout` / logcat.
   - Spawn `mChildWatcherThread` to call `waitpid(mVmPid, &status, 0)`:
     - If process exits while state is `VM_STARTING`: clear `mVmPid = -1`, set `mVmState = VmState::STOPPED`, reset `mPendingClientFd = -1`.

6. **Do NOT write `CMD_HANDSHAKE_COMPLETE` response here.**

#### C. Deferred Handshake Completion Dispatch:
Wire `VsockServer` handshake success callback:
```cpp
void SocketServer::onVsockHandshakeSuccess(uint32_t cid) {
    std::lock_guard<std::mutex> lock(mVmMutex);
    if (mVmState == VmState::STARTING && mPendingClientFd >= 0) {
        mVmState = VmState::RUNNING;
        std::vector<uint8_t> response = serializePacket(0x0003, mPendingTransactionId, {});
        write(mPendingClientFd, response.data(), response.size());
        std::cout << "[linux_bridge] Real VM Vsock handshake complete. CMD_HANDSHAKE_COMPLETE sent to framework." << std::endl;
        mPendingClientFd = -1;
        mPendingTransactionId = 0;
    }
}
```

#### D. `CMD_VM_STOP` (0x0002) Handling:
```cpp
if (header.cmdType == 0x0002) { // CMD_VM_STOP
    bool force = (!payload.empty() && payload[0] == 1);
    stopVmProcess(force);
    std::vector<uint8_t> response = serializePacket(0x0002, header.transactionId, {0x00});
    write(clientFd, response.data(), response.size());
}
```
Helper `stopVmProcess(bool force)`:
1. Lock `mVmMutex`.
2. If `mVmPid > 0`:
   - Send `kill(mVmPid, SIGTERM)`.
   - Poll `waitpid(mVmPid, &status, WNOHANG)` up to 2000ms.
   - If process still running and `force == true`: `kill(mVmPid, SIGKILL)`.
   - Reap child with `waitpid(mVmPid, &status, 0)`.
   - Set `mVmPid = -1`.
3. Reset `mVsockServer->resetSession()`.
4. Set `mVmState = VmState::STOPPED`.

---

### 3.2 `guest/scripts/launch_vm.sh` Enhancements

1. **`exec` Prefix for Process Image Replacement**:
   Update lines 89–99 to use `exec crosvm run`:
   ```bash
   if command -v crosvm >/dev/null 2>&1; then
       exec crosvm run \
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
   *Rationale*: Using `exec` replaces the shell process image with `crosvm`. The PID returned by `posix_spawn` in `socket_server.cpp` directly tracks `crosvm` instead of a wrapper shell.

2. **Test Mode Process Spawning**:
   In `TEST_MODE=1` where `crosvm` is absent, spawn a dummy background sleep process to allow PID tracking and Vsock handshake unit tests to pass:
   ```bash
   else
       echo "[Launch Script] crosvm binary not in PATH (Simulated execution mode)"
       if [ "${TEST_MODE:-0}" = "1" ]; then
           exec sleep 3600
       fi
   fi
   ```

---

### 3.3 Unit Test Updates in `system/linux_bridge/tests/linux_bridge_test.cpp`

Update `testSocketServerLifecycle()`:
1. Initialize `VsockServer` and link with `SocketServer`.
2. Set `TEST_MODE=1` in environment.
3. Send `CMD_VM_START` (0x0001). Verify that `read()` on `clientFd` **blocks** (does not immediately receive `CMD_HANDSHAKE_COMPLETE`).
4. Trigger `vsockServer.processHandshake(3, validPayload)`.
5. Verify `read()` on `clientFd` now receives `CMD_HANDSHAKE_COMPLETE` (0x0003).
6. Send `CMD_VM_STOP` (0x0002). Verify child process teardown and `CMD_VM_STOP` response.

---

## 4. Caveats

- **Host Environment & `/dev/kvm`**: Real `crosvm` execution requires an ARM64 Linux/Android target with KVM enabled. On developer machines without `/dev/kvm`, `TEST_MODE=1` must be used for unit testing.
- **SELinux Permissions**: Executing `launch_vm.sh` from `socket_server.cpp` requires `linux_bridge.te` SELinux rules allowing `exec` on `shell_exec` and domain transition to `crosvm`.

---

## 5. Conclusion

By implementing this technical plan:
1. Immediate fake `CMD_HANDSHAKE_COMPLETE` response is completely removed from `socket_server.cpp`.
2. `socket_server.cpp` securely generates an HMAC token, invokes `launch_vm.sh`, passes `android_bridge.token` to kernel parameters, and tracks child `crosvm` PID.
3. `CMD_HANDSHAKE_COMPLETE` is deferred until real Vsock HMAC authentication completes on Port 5000.

---

## 6. Verification Method

1. **Syntax & Build Verification**:
   - `bash -n guest/scripts/launch_vm.sh`
   - Run C++ test suite build: `./build_out/bin/linux_bridge_test`
2. **Integration Verification**:
   - Verify `CMD_VM_START` blocks until Vsock handshake occurs.
   - Verify child process termination on `CMD_VM_STOP`.
