# Handoff Report — Explorer 1 (Milestone M4 Focus Area 1)

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m4/explorer_1`  
**Target Scope**: 
1. **F-R4-001**: Wayland Window Forwarding — Guest Sommelier Wayland proxy buffer forwarding over Vsock Port 5002.
2. **F-R4-002**: virtio-gpu dma-buf Sharing — Zero-copy dma-buf memory buffer binding to Host SurfaceControl.

---

## 1. Observation

### 1.1 Existing Codebase Infrastructure
- **Architecture Specification**:
  - `PROJECT.md` (lines 60-61, 108-110): Defines F-R4-001 (Sommelier Wayland proxy buffer forwarding over Vsock 5002) and F-R4-002 (virtio-gpu dma-buf zero-copy binding to Host `SurfaceControl`). Interface Contract #3: Host `LinuxWindowBridgeService` ↔ Guest `Sommelier` over Vsock Port 5002 + `virtio-gpu` dma-buf.
  - `aosp_linux_system_architecture_plan.md` (§11.1, lines 343-352): Outlines Guest Wayland Proxy (`Sommelier`), Vsock 5002 control channel, `virtio-gpu` shared memory (`dma-buf`), Host `LinuxWindowBridgeService`, and `LinuxAppProxyActivity` with `SurfaceControl`.

- **Existing Native & Guest Vsock Port Allocation**:
  - `system/linux_bridge/vsock_framing.h` (lines 28-41): Defines `VSOCK_PORT_WAYLAND = 5002`, `VsockFrameType::WAYLAND = 0x03`, and magic header `0x56534F4B` ("VSOK").
  - `system/linux_bridge/vsock_server.h` & `vsock_server.cpp` (lines 48-50, 97-101): Configures Vsock server for Port 5002 (`mBoundPorts[VSOCK_PORT_WAYLAND]`), enforces Guest `clientAddr.svm_cid == 3`, and restricts Port 5002 binding until HMAC-SHA256 authentication succeeds on Port 5000.
  - `guest/bridge-agent/src/main.rs` (line 45): Listens on Vsock Port 5002 (Wayland Display) following HMAC auth.

- **Existing Host Services**:
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxBridgeService.java`: Manages Unix Domain Socket IPC (`/dev/socket/linux_bridge`) with native `linux_bridge` daemon. Currently supports CMD_VM_*, CMD_PTY_*, CMD_APP_SYNC.
  - `frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java`: Central SystemServer VM state machine and bridge callback orchestrator.

- **Existing E2E Test Suite**:
  - `tests/e2e/tier1_feature_coverage/test_m4_tier1.py`:
    - `T1-86`: `TestR4_001_T1_86_ConnectSommelierWaylandProxy` (Vsock 5002 connection)
    - `T1-87`: `TestR4_001_T1_87_ForwardWlSurfaceCommitEvents` (`wl_surface.commit` event forwarding)
    - `T1-88`: `TestR4_001_T1_88_RenderLinuxAppGuiInProxyActivity` (Render Linux App GUI in proxy activity)
    - `T1-89`: `TestR4_001_T1_89_DispatchInputEventsToSommelier` (Dispatch input events back to Sommelier)
    - `T1-90`: `TestR4_001_T1_90_WaylandSurfaceDestroyCleanup` (Surface destroy cleanup)
    - `T1-91`: `TestR4_002_T1_91_GuestAllocatesGraphicBuffer` (Guest graphic buffer allocation via virtio-gpu)
    - `T1-92`: `TestR4_002_T1_92_ExportDmaBufFileDescriptor` (Export dma-buf FD across hypervisor)
    - `T1-93`: `TestR4_002_T1_93_ImportDmaBufToHardwareBuffer` (Import dma-buf to HardwareBuffer)
    - `T1-94`: `TestR4_002_T1_94_BindHardwareBufferToSurfaceControl` (Bind HardwareBuffer to SurfaceControl)
    - `T1-95`: `TestR4_002_T1_95_ZeroCopyPresentationLatency` (Presentation latency < 16ms)
  - `tests/e2e/tier2_boundary_corner/test_m4_tier2.py`:
    - `T2-86`: `TestR4_001_T2_86_SommelierCrashRecovery` (Sommelier crash recovery)
    - `T2-87`: `TestR4_001_T2_87_MultiWindowSurfaceIsolation` (Multi-window surface isolation)
    - `T2-88`: `TestR4_001_T2_88_ProtocolVersionMismatch` (Protocol version mismatch fallback)
    - `T2-89`: `TestR4_001_T2_89_HighFpsBufferDropCheck` (High FPS buffer drop check)
    - `T2-90`: `TestR4_001_T2_90_OutOfOrderPacketSequence` (Out-of-order packet sequence handling)
    - `T2-91`: `TestR4_002_T2_91_InvalidDmabufHandle` (Invalid dma-buf handle import failure)
    - `T2-92`: `TestR4_002_T2_92_GpuMemoryLeakValidation` (GPU graphics memory leak validation)
    - `T2-93`: `TestR4_002_T2_93_GpuResetHostSurfaceRecreation` (GPU reset / surface recreation)
    - `T2-94`: `TestR4_002_T2_94_FormatIncompatibilityFallback` (Format incompatibility software fallback)
    - `T2-95`: `TestR4_002_T2_95_SyncGpuFenceCompletion` (Synchronize GPU fence completion)
  - `tests/e2e/tier3_cross_feature/test_pairwise_matrix.py`:
    - `T3-PAIR-04`: Wayland surface commit triggers zero-copy dma-buf buffer binding to Host `SurfaceControl`.

### 1.2 Identified Gaps (What is Missing)
1. **Missing Host Service**: `LinuxWindowBridgeService.java` is missing in `frameworks/base/services/core/java/com/android/server/linux/`.
2. **Missing Native Buffer Sharing & JNI Engine**: C++/JNI implementation for wrapping Linux `dma-buf` file descriptors into Android `AHardwareBuffer` (`AHardwareBuffer_importAndAcquireNativeHandle`) and binding to NDK `ASurfaceControl` (`ASurfaceTransaction_setBuffer`) does not exist yet.
3. **Missing Guest Wayland Proxy Client**: `guest/bridge-agent` lacks a dedicated Wayland proxy forwarder module for routing Sommelier sockets over Vsock Port 5002.
4. **Missing Unit Tests**: M4 unit tests (`WaylandWindowBridgeTest.java` and `VirtioGpuDmabufTest.cpp`) in `tests/unit/` are missing.

---

## 2. Logic Chain

1. **Vsock Port 5002 Communication (F-R4-001)**:
   - The native bridge daemon (`vsock_server`) already reserves Port 5002 and enforces CID 3 authentication.
   - Creating `LinuxWindowBridgeService.java` will provide SystemServer with a manager for Wayland surface lifecycle (`createSurface`, `commitFrame`, `destroySurface`, `dispatchInputEvent`).
   - Adding Wayland command routing to `LinuxBridgeService.java` links the SystemServer Java layer to the native Vsock 5002 socket stream.

2. **virtio-gpu dma-buf Zero-Copy Sharing (F-R4-002)**:
   - When Guest GUI apps (e.g. GIMP/VSCode via Sommelier) render a frame, virtio-gpu allocates a graphics memory buffer and exports a `dma-buf` file descriptor.
   - A native JNI module (`system/linux_bridge/wayland_buffer_sharing.cpp`) can import this `dma-buf` FD into Android `AHardwareBuffer` using NDK APIs.
   - The `AHardwareBuffer` handle is bound directly to the target `SurfaceControl` via `ASurfaceTransaction_setBuffer`.
   - Before presenting the buffer to host display, GPU fence completion (`SyncFence`) is waited on to prevent rendering artifacts or screen tearing.
   - If an incoming buffer format (e.g. YUV420) is incompatible with native display hardware, a software conversion fallback (`AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM`) is applied.

3. **Resilience & Frame Pacing**:
   - Frame delivery is capped at 60 FPS (host display refresh rate). Frames committed faster than 16ms intervals are safely dropped.
   - If Sommelier or the Guest VM crashes, `LinuxWindowBridgeService` flushes active surface registries without crashing `SystemServer`.

---

## 3. Caveats

- **Host NDK / AHardwareBuffer Availability**: Testing in non-Android host environments (e.g. macOS host during development) uses mock handles or stubbed NDK wrappers.
- **GPU Fence Sync Timeout**: GPU fence sync must handle timeout scenarios gracefully (`RuntimeError("SyncFenceWaitTimeout")` as defined in `T2-95`).
- **Memory Allocation Tracking**: Native `AHardwareBuffer` handles must be explicitly released (`AHardwareBuffer_release`) upon surface destroy or GPU reset to prevent memory leaks (`T2-92`).

---

## 4. Conclusion

Features F-R4-001 and F-R4-002 are fully specified by `PROJECT.md`, `SCOPE.md`, `aosp_linux_system_architecture_plan.md`, and the E2E test suite. The underlying Vsock 5002 infrastructure exists in `vsock_server.cpp`. To achieve full implementation:
1. Implement `LinuxWindowBridgeService.java` in SystemServer.
2. Implement native JNI module `wayland_buffer_sharing.cpp` for `dma-buf` to `AHardwareBuffer` import & `SurfaceControl` binding.
3. Update `guest/bridge-agent` for Wayland proxy forwarding over Vsock Port 5002.
4. Add unit test suites (`WaylandWindowBridgeTest.java` and `VirtioGpuDmabufTest.cpp`).

---

## 5. Verification Method

To independently verify F-R4-001 and F-R4-002:

### 5.1 Run E2E Test Cases for F-R4-001 & F-R4-002
```bash
python3 tests/e2e/runner.py --feature F-R4-001
python3 tests/e2e/runner.py --feature F-R4-002
```

### 5.2 Build & Run Unit Tests
```bash
# Compile Java Services
javac -d build_out/classes frameworks/base/core/java/android/system/linux/*.java frameworks/base/services/core/java/com/android/server/linux/*.java

# Compile C++ Native Bridge & Unit Tests
clang++ -std=c++17 -I system/linux_bridge system/linux_bridge/vsock_server.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/hmac_auth.cpp tests/unit/VirtioGpuDmabufTest.cpp -o build_out/bin/VirtioGpuDmabufTest

# Execute Unit Tests
./build_out/bin/VirtioGpuDmabufTest
```

### 5.3 Run Full E2E Test Suite
```bash
python3 tests/e2e/runner.py
```
Expected result: **430 / 430 PASS (100.0%)**, exit code 0.
