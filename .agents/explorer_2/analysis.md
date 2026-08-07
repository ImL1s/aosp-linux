# R5 Technical Analysis: Hardware Portals, Virtiofs, SELinux Policies & Guest A/B Rollback OTA

**Author**: explorer_2  
**Date**: 2026-08-06  
**Scope**: Requirement 5 (R5) and Cross-Cutting Security & Verification Architecture  
**Target Repository**: `/Users/iml1s/Documents/mine/aosp-linux/`  

---

## 1. Executive Summary

This report provides a deep-dive architectural analysis of **Requirement 5 (R5)** for the AOSP Dual-OS system ("一個 AOSP 產品，兩個隔離的執行環境，一個統一的使用者體驗"). R5 bridges the non-protected ARM64 Debian Linux Guest VM with the Host AOSP environment across hardware acceleration, hardware permission portals, shared storage, strict SELinux isolation, and resilient Guest OS update/rollback mechanics.

Key architecture highlights:
- **Hardware Isolation & Portals**: Guest Linux is forbidden from raw `/dev/` access. All hardware access (Camera, Microphone, GPS) is proxied via **XDG Desktop Portal API over vsock**, terminating in Host `LinuxPortalService` enforced by Android `AppOps` and `PermissionManager`.
- **Audio Integration**: Audio I/O uses `virtio-snd` mapped to Host `AudioService`, fully integrated with Android `AudioFocus` (handling calls, notifications, ducking/muting).
- **Zero-Copy File Sharing**: Host-to-Guest sharing utilizes `virtiofs` (`/data/media/0/LinuxShared` <-> `/mnt/shared`). Guest-to-Host access utilizes `LinuxStorageProvider` (`DocumentsProvider`), making Debian `/home/user` accessible to Android apps via SAF.
- **SELinux Hardening**: Enforces new domains (`linux_manager.te`, `linux_bridge.te`) and strict `neverallow` constraints preventing Guest-orchestrated daemons from touching host system binaries or raw EFS partitions, ensuring 100% CTS/VTS compliance.
- **Guest A/B Base Image Rollback OTA**: Uses read-only EROFS A/B base images (`base_a.img`/`base_b.img`), Android Verified Boot (AVB) key signature validation, and an automatic boot watchdog counter for fallback on failure.

---

## 2. Reference Document Citations

This investigation is built directly upon the following primary artifacts:
1. **Original User Request**: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. **Technical Blueprint**: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`
3. **Agent Task Dispatch**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_2/DISPATCH.md`

---

## 3. Deep Dive: Hardware Portals Subsystem (Camera, Mic, GPS)

### 3.1 Problem Statement & Anti-Patterns
In standard virtualization, passing physical USB/PCI devices (e.g. VFIO passthrough for `/dev/video0` or `/dev/ttyUSB0`) to a Guest VM causes severe issues on mobile SOCs:
1. **Hardware Exclusivity**: Host Android loses access to camera/mic hardware while VM is active.
2. **Security Bypass**: Guest `root` could record video/audio without Android Runtime Permission prompts or status bar indicator green dots.
3. **Race Conditions**: Concurrent access crashes host camera HAL driver.

### 3.2 Hardware Portal Architecture
All hardware requests inside Debian are intercepted at the desktop framework level via the **Freedesktop XDG Desktop Portal API** over `virtio-vsock`.

```
[ Debian Guest Linux App ]
        │ (D-Bus call: org.freedesktop.portal.Camera)
        ▼
[ Guest portal-agent (D-Bus Proxy Daemon) ]
        │ (Encapsulated Protobuf RPC over AF_VSOCK Port 5000)
        ▼
[ Host LinuxBridgeService (SELinux: linux_bridge) ]
        │ (Internal Binder IPC)
        ▼
[ Host LinuxPortalService (SELinux: linux_manager) ]
        ├── 1. Check Permission via AppOpsManager (OP_CAMERA / OP_RECORD_AUDIO)
        ├── 2. Trigger Android Runtime Permission Dialog (if not granted)
        └── 3. Bind to Android Camera2 API / AudioRecord / LocationManager
        │
        ▼ (Encoded Media Buffer Stream via Vsock Shared Memory)
[ Guest Virtual Driver (v4l2loopback / virtio-camera / PulseAudio VirtSource / Geoclue) ]
        │
        ▼
[ Debian Guest Linux App ]
```

### 3.3 Detailed Portal Protocol Specifications

#### A. Camera Portal Protocol
1. **Request Phase**: Linux App calls `org.freedesktop.portal.Camera.AccessCamera()`. Guest `portal-agent` packages request (`app_id`, `session_token`, `resolution`, `fps`) and sends over vsock.
2. **Authorization Phase**: `LinuxPortalService` checks `AppOpsManager.noteOp(OP_CAMERA)`. If result is `MODE_IGNORED` or `MODE_ERRORED`, Host displays standard Android permission dialog.
3. **Stream Phase**: Host `CameraManager` captures YUV420/NV12 frames, hardware-encodes them via `MediaCodec` (H.264/MJPEG low latency) or zero-copy shared memory buffer, and streams to vsock. Guest kernel receives frames via `v4l2loopback` or `virtio-camera`, exposing `/dev/video0` inside Guest VM.
4. **Privacy Indicator Sync**: Android status bar displays camera indicator (green dot). When Linux app stops camera, Host closes `CameraDevice` and status bar indicator turns off.

#### B. Microphone Portal Protocol
1. **Request Phase**: App requests audio input. Intercepted by PipeWire/PulseAudio portal module.
2. **Authorization Phase**: `LinuxPortalService` calls `AppOpsManager.noteOp(OP_RECORD_AUDIO)`.
3. **Stream Phase**: Host `AudioRecord` captures PCM audio, streams chunked PCM over vsock to Guest PipeWire virtual source node.

#### C. GPS / Location Portal Protocol
1. **Request Phase**: Guest app queries location via `geoclue2` D-Bus service.
2. **Authorization Phase**: `LinuxPortalService` verifies `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`.
3. **Data Phase**: Host `LocationManager` supplies latitude/longitude/accuracy JSON objects over vsock. Raw NMEA strings are sanitized to prevent baseband/GPS hardware probing.

---

## 4. Deep Dive: Audio Subsystem (`virtio-snd` & AudioFocus)

### 4.1 VirtIO Sound Architecture
To provide ultra-low-latency audio playback without custom D-Bus IPC overhead for every audio byte, the system uses **`virtio-snd`**.

- **Guest Side**: Standard ALSA / PipeWire / PulseAudio stack using Guest Linux kernel `snd-virtio` driver.
- **Transport**: crosvm `virtio-snd` PCI device.
- **Host Side**: `LinuxBridgeService` receives PCM stream buffers from crosvm virtio-snd backend and passes them to Android `AudioTrack`.

### 4.2 AudioFocus Policy Integration
Guest audio playback must behave like a native Android app:

```
                  +--------------------------+
                  | Guest Linux App Playback |
                  +------------+-------------+
                               |
                        (PCM Stream Start)
                               v
                  +--------------------------+
                  | Host LinuxBridgeService  |
                  +------------+-------------+
                               |
                   (requestAudioFocus())
                               v
                  +--------------------------+
                  |  Android AudioManager    |
                  +------------+-------------+
                               |
         +---------------------+---------------------+
         |                                           |
 (Focus Granted)                             (Focus Loss / Duck)
         |                                           |
         v                                           v
[ Play sound on Host ]                     [ Mute/Pause virtio-snd ]
[ AudioTrack output  ]                     [ Notify Guest PipeWire ]
```

- **Incoming Phone Call / Alarm**: Android `AudioManager` triggers `AUDIOFOCUS_LOSS_TRANSIENT`. Host `LinuxBridgeService` immediately pauses/mutes `virtio-snd` ring buffer. Guest apps do not crash; PipeWire buffers fill gracefully.
- **Media Playback Switching**: If user plays YouTube on Android Host, Android grants focus to YouTube and notifies `LinuxBridgeService` (`AUDIOFOCUS_LOSS`). Guest Linux audio output is suspended until Host relinquishes focus (`AUDIOFOCUS_GAIN`).

---

## 5. Deep Dive: File Sharing Subsystem (`virtiofs` & SAF `DocumentsProvider`)

### 5.1 Host-to-Guest Sharing via `virtiofs`
- **Host Directory**: `/data/media/0/LinuxShared` (exposed in Android Files app under "Linux Shared").
- **Guest Mount Point**: `/mnt/shared` mounted automatically in Debian `/etc/fstab`:
  ```mount
  LinuxShared /mnt/shared virtiofs defaults,_netdev 0 0
  ```
- **Performance & Security**:
  - `virtiofs` uses DAX (Direct Access) page cache sharing between Host kernel and Guest VM kernel.
  - File access is locked to `/data/media/0/LinuxShared`. Guest root cannot escape `/mnt/shared` to traverse host `/data/system/` or `/data/user/0/`.

### 5.2 Guest-to-Host Sharing via `LinuxStorageProvider` (`DocumentsProvider`)
To allow Android applications (e.g. Files app, Gmail attachments, Office apps) to browse and edit Linux Guest files:

- **Implementation Class**: `com.android.server.linux.storage.LinuxStorageProvider extends DocumentsProvider`.
- **Root Node**: Exposes `/home/user` of Guest Linux as a Storage Root in Android's Storage Access Framework (SAF).
- **Communication Protocol**: `LinuxStorageProvider` communicates with Guest `android-bridge-agent` via vsock file RPC protocol (or isolated secondary virtiofs mount):
  - `queryRoots()`: Returns "Linux Home (`/home/user`)".
  - `queryChildDocuments()`: Fetches directory listings from Guest.
  - `openDocument()`: Provides a ParcelFileDescriptor linked to host/guest shared memory buffer.
- **Permissions**: Fully governed by Android SAF user permissions. Android apps cannot read `/home/user` unless granted document access via standard SAF system UI file picker.

---

## 6. Deep Dive: SELinux Policies & Security Hardening

### 6.1 SELinux Domain Separation Architecture
To ensure Guest VM compromise or vsock parser vulnerabilities cannot compromise Host `system_server` or Android kernel, execution is strictly partitioned into distinct SELinux domains:

```
[ system_server ] (system_app / system_server domain)
       │ (Binder IPC)
       ▼
[ linux_manager ] (coredomain for VM lifecycle & config)
       │ (Manages crosvm child process & AVF)
       ▼
[ linux_bridge ] (isolated process for vsock & media stream parsing)
       │ (AF_VSOCK)
       ▼
[ Guest Debian VM (crosvm / dev/kvm boundary) ]
```

### 6.2 SELinux Policy Definitions (`system/sepolicy/private/`)

#### A. `linux_manager.te`
```sepolicy
type linux_manager, domain, coredomain;
type linux_manager_exec, exec_type, file_type, system_file_type;
type linux_vm_data_file, file_type, data_file_type, core_data_file_type;

init_daemon_domain(linux_manager)

# KVM & Virtualization access
allow linux_manager kvm_device:chr_file rw_file_perms;
binder_call(linux_manager, virtualizationservice)

# VM Image storage access
allow linux_manager linux_vm_data_file:dir create_dir_perms;
allow linux_manager linux_vm_data_file:file create_file_perms;
```

#### B. `linux_bridge.te`
```sepolicy
type linux_bridge, domain, coredomain;
type linux_bridge_exec, exec_type, file_type, system_file_type;

init_daemon_domain(linux_bridge)

# Vsock IPC creation and operations
allow linux_bridge self:vsock_socket { create_socket_perms_fd listen accept read write getattr bind connect };
allow linux_bridge linux_manager:unix_stream_socket { read write connectto };
```

#### C. Strict NEVERALLOW Rules (Mandatory System Protection)
```sepolicy
# Never allow Linux Manager or Bridge to access modem raw telemetry / EFS partitions
neverallow { linux_manager linux_bridge } efs_file:dir *;
neverallow { linux_manager linux_bridge } efs_file:file *;

# Never allow Linux Manager or Bridge to write to Host system partition binaries
neverallow { linux_manager linux_bridge } system_file:file { write create execmod };
neverallow { linux_manager linux_bridge } system_data_file:file { write create };

# Never allow raw socket access to host network interfaces without system_server VPN routing
neverallow linux_bridge self:rawip_socket *;
```

### 6.3 CTS & VTS Compatibility Enforcement
- **Binder Service Registration**: `LinuxManagerService` registers under `Context.LINUX_MANAGEMENT_SERVICE` ("linux_management"). Must use `@SystemApi` or `@hide` annotations to prevent unprivileged third-party Android apps from invoking VM APIs.
- **SELinux Test Cases**: System must pass `cts-tradefed run cts -m CtsSELinuxHostTestCases` and `CtsSecurityTestCases` without any audit denials.

---

## 7. Deep Dive: Guest A/B Base Image Rollback & OTA Update Engine

### 7.1 Disk Image Hierarchy & Storage Encryption
The Linux Guest storage consists of three distinct disk layers:

```
/data/system/linux/
├── base_a.img            (Read-Only EROFS Base OS Image - Slot A, ~2.5GB)
├── base_b.img            (Read-Only EROFS Base OS Image - Slot B, ~2.5GB)
├── custom_overlay.img    (Writable ext4 OverlayFS for system-wide APT changes)
├── user_home.img         (Writable ext4 mounted to /home, LUKS/fscrypt bound to Android CE key)
└── metadata.json         (A/B active slot state, boot counter, image hash)
```

1. **Base RootFS (`base_a.img` / `base_b.img`)**: Read-only EROFS (Enhanced Read-Only File System) compressed image. Contains base Debian binaries (`/usr`, `/bin`, `/lib`). Immutable and protected from Guest malware.
2. **User Home (`user_home.img`)**: Encrypted using LUKS/fscrypt. Key is managed by Android Keystore and tied to Android Credential Encrypted (CE) storage. Unlocked only when user enters device PIN/pattern/fingerprint.
3. **Custom Overlay (`custom_overlay.img`)**: OverlayFS upperdir for system configuration adjustments (`/etc`, `/var`).

### 7.2 Android Verified Boot (AVB) Key Signature Validation
Before launching any Guest base image:
1. `LinuxManagerService` verifies `base_x.img` signature against Host OEM / AVB public root key.
2. If image signature is invalid or modified, `LinuxManagerService` refuses to pass file descriptor to `virtualizationservice`, blocking untrusted kernel/rootfs execution.

### 7.3 A/B Dual Partition OTA Flow & Failure Rollback Watchdog

```
[ Host Downloads Guest OTA Package ]
                 │
                 ▼
[ Write to Inactive Slot (e.g. base_b.img) ]
                 │
                 ▼
[ Verify AVB Signature against Host Key ] ──(Fail)──> [ Abort OTA & Delete base_b ]
                 │ (Pass)
                 ▼
[ Update metadata.json: ActiveSlot = B, BootAttempts = 3 ]
                 │
                 ▼
[ Start Guest VM with base_b.img ]
                 │
      ┌──────────┴──────────┐
      │                     │
(Handshake OK in 30s)  (Crash / Panic / Hang / Timeout)
      │                     │
      ▼                     ▼
[ Mark Boot SUCCESSFUL ] [ Decrement BootAttempts ]
[ BootAttempts = 0 ]        │
                            ├──(BootAttempts > 0)──> [ Retry Booting Slot B ]
                            └──(BootAttempts == 0)──> [ AUTOMATIC ROLLBACK ]
                                                      │  - ActiveSlot = A
                                                      │  - Send Android Notification
                                                      ▼
                                            [ Boot Verified Slot A ]
```

- **Boot Success Condition**: Guest `android-bridge-agent` boots, reaches `multi-user.target`, connects over vsock port 5000, and sends signed `BOOT_SUCCESSFUL` challenge within 30 seconds.
- **Rollback Guarantee**: If `base_b.img` crashes during boot, hangs, or fails vsock handshake 3 times, `LinuxManagerService` automatically toggles `ActiveSlot` back to `Slot A` (`base_a.img`). User data in `user_home.img` remains untouched and safe.

---

## 8. Requirement 5 Complete Feature Inventory

| Feature ID | Category | Feature Description | Primary Components | Key Constraints & Protocols |
|---|---|---|---|---|
| **F-R5-001** | Hardware Portal | XDG Desktop Camera Portal over vsock | `portal-agent`, `LinuxPortalService`, `CameraManager` | Intercept D-Bus `org.freedesktop.portal.Camera`, AppOps `OP_CAMERA`, V4L2 loopback stream |
| **F-R5-002** | Hardware Portal | XDG Desktop Mic Portal over vsock | `portal-agent`, `LinuxPortalService`, `AudioRecord` | AppOps `OP_RECORD_AUDIO`, PipeWire virtual audio source |
| **F-R5-003** | Hardware Portal | XDG Desktop Location Portal over vsock | `portal-agent`, `LinuxPortalService`, `LocationManager` | AppOps `ACCESS_FINE_LOCATION`, Geoclue D-Bus provider, JSON/NMEA stream |
| **F-R5-004** | Audio | `virtio-snd` ALSA audio playback | crosvm, `LinuxBridgeService`, `AudioTrack` | Standard ALSA Guest driver, PCM stream forwarding |
| **F-R5-005** | Audio | Android AudioFocus lifecycle management | `LinuxBridgeService`, `AudioManager` | Request `AUDIOFOCUS_GAIN`, auto duck/mute on calls or Android playback |
| **F-R5-006** | Storage | Host-to-Guest `virtiofs` file share | crosvm `virtiofs`, `/data/media/0/LinuxShared` | Mount to `/mnt/shared`, zero-copy DAX, strict host directory scoping |
| **F-R5-007** | Storage | Guest-to-Host SAF `LinuxStorageProvider` | `LinuxStorageProvider`, `android-bridge-agent` | Implements `DocumentsProvider`, exposes `/home/user` to Android Files app via SAF |
| **F-R5-008** | Security | SELinux `linux_manager` domain policy | `system/sepolicy/private/linux_manager.te` | KVM access, VirtualizationService Binder access, coredomain enforcement |
| **F-R5-009** | Security | SELinux `linux_bridge` domain policy | `system/sepolicy/private/linux_bridge.te` | AF_VSOCK socket binding, IPC serialization isolation |
| **F-R5-010** | Security | SELinux NEVERALLOW security rules | `system/sepolicy/private/` | Neverallow access to `efs_file`, system write operations, raw IP sockets |
| **F-R5-011** | Security | CTS/VTS compliance & non-standard API hiding | `LinuxManagerService` API layer | `@SystemApi`/`@hide` for platform services, pass CtsSELinuxHostTestCases |
| **F-R5-012** | Update/OTA | Read-Only EROFS Base Image A/B partition setup | `base_a.img`, `base_b.img`, `custom_overlay.img` | EROFS compressed rootfs, OverlayFS upperdir, immutable base system |
| **F-R5-013** | Update/OTA | Android Verified Boot (AVB) signature check | `LinuxManagerService`, Keystore | Verify `base_x.img` signature against OEM AVB key before crosvm execution |
| **F-R5-014** | Update/OTA | Guest A/B Base Image Rollback Watchdog | `LinuxManagerService`, `android-bridge-agent` | 3-boot attempt watchdog counter, auto fallback to Slot A on failure, user data protection |

---

## 9. Cross-Cutting Verification & Test Plan

To ensure R5 meets production quality and security standards, the following verification suite is required:

### 9.1 Unit & Integration Test Suite
1. **`LinuxPortalServiceTest`**: Mock `AppOpsManager` states (ALLOWED, DENIED, ASK) and verify permission dialog triggers and camera/mic stream lifecycle.
2. **`LinuxStorageProviderTest`**: Test SAF `DocumentsProvider` CRUD operations (`queryRoots`, `queryChildDocuments`, `openDocument`) against a mock vsock file bridge agent.
3. **`LinuxBridgeVsockParserFuzzTest`**: Use LLVM `libFuzzer` to send malformed RPC payloads to `LinuxBridgeService` over vsock socket to verify crash resistance.

### 9.2 Security & SELinux Compliance Verification
1. **SELinux Policy Audit**:
   ```bash
   $ cts-tradefed run cts -m CtsSELinuxHostTestCases
   ```
   Must yield zero avc audit denial logs for `linux_manager` and `linux_bridge` domains.
2. **CTS Framework Compatibility**:
   ```bash
   $ cts-tradefed run cts -m CtsSecurityTestCases
   ```
3. **Privilege Escalation Penetration Testing**:
   - Inside Guest VM (as Guest `root`), execute exploit scripts attempting to read `/data/system/users/0/` on Host. Verify SELinux and KVM hypervisor block all access (`Permission denied`).

### 9.3 Rollback & AVB Verification Method
1. **AVB Signature Corruption Test**:
   - Corrupt 1 byte in `base_b.img`. Launch VM using `base_b.img`. Verify `LinuxManagerService` detects signature mismatch and aborts launch.
2. **Automated Rollback Test**:
   - Inject kernel panic into `base_b.img` init script. Trigger boot. Verify system attempts 3 boots, declares Slot B failed, toggles `metadata.json` back to `Slot A`, boots successfully, and displays Android notification "Linux update failed. Reverted to previous version."
