# Technical Analysis & Implementation Strategy: Milestone M5 Features (F-R5-009 to F-R5-014)

**Author**: Explorer 3 (`explorer_m5_3`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m5_3`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Date**: 2026-08-06  

---

## 1. Executive Summary & Scope Mapping

This document details the architecture, policy definitions, file locations, build system integrations, and implementation strategies for Milestone M5 security and OTA/Rollback features (**F-R5-009** through **F-R5-014**).

### Feature Scope & Target Components

| Feature ID | Feature Name | Description & Target Components | Primary Files / Policy Targets |
|------------|--------------|---------------------------------|--------------------------------|
| **F-R5-009** | SELinux Domain Policy Rules | Domain policy definitions for SystemServer, Bridge, and Portal daemons | `system/sepolicy/private/linux_manager.te`<br>`system/sepolicy/private/linux_bridge.te`<br>`system/sepolicy/private/linux_portal.te`<br>`system/sepolicy/private/file_contexts` |
| **F-R5-010** | SELinux neverallow Rules | Strict compile-time protection for `efs_file`, system partition writes, raw device IO, and domain transitions | `system/sepolicy/private/linux_manager.te`<br>`system/sepolicy/private/linux_bridge.te`<br>`system/sepolicy/private/linux_portal.te` |
| **F-R5-011** | CTS / VTS Compatibility | Guarantee zero failures in `CtsSELinuxHostTestCases`, `CtsSecurityTestCases`, Treble VNDK compliance, and GSI boot | `cts/hostsidetests/sepolicy/`<br>`cts/tests/tests/security/` |
| **F-R5-012** | EROFS Base Image A/B Layout | Immutable read-only EROFS `base_a.img`/`base_b.img` dual slot layout with background streaming OTA | `/data/system/linux/base_a.img`<br>`/data/system/linux/base_b.img`<br>`/data/system/linux/slot_metadata.json` |
| **F-R5-013** | AVB Key Signature Validation | Android Verified Boot (AVB) key chain verification for guest image OTA with anti-rollback protection | `system/vold/AvbVerifier.cpp`<br>`/system/etc/security/avb/guest_root_key.pub` |
| **F-R5-014** | Boot Watchdog Rollback Engine | 3-boot attempt watchdog fallback to previous base slot on boot failure | `system/linux_bridge/guest_ota_rollback_watchdog.cpp`<br>`guest/bridge-agent/src/ota_rollback.rs` |

---

## 2. F-R5-009: SELinux Domain Policy Rules

### 2.1 File Contexts (`system/sepolicy/private/file_contexts`)

```sepolicy
# system/sepolicy/private/file_contexts
/dev/socket/linux_bridge    u:object_r:linux_bridge_socket:s0
/dev/socket/linux_portal    u:object_r:linux_portal_socket:s0
/system/bin/linux_bridge    u:object_r:linux_bridge_exec:s0
/system/bin/linux_portal    u:object_r:linux_portal_exec:s0
/data/system/linux(/.*)?    u:object_r:linux_vm_data_file:s0
```

### 2.2 Domain Definitions

#### `system/sepolicy/private/linux_manager.te`
```sepolicy
# Domain definition for LinuxManagerService and AVF guest management
type linux_manager, domain, coredomain;
type linux_manager_exec, exec_type, file_type, system_file_type;
type linux_vm_data_file, file_type, data_file_type, core_data_file_type;

# Grant system_server interaction via Binder IPC
binder_use(linux_manager)
binder_call(linux_manager, system_server)
binder_call(system_server, linux_manager)

# Allow KVM access and VirtualizationService AIDL calls
allow linux_manager kvm_device:chr_file rw_file_perms;
allow linux_manager virtualizationservice_service:service_manager find;
binder_call(linux_manager, virtualizationservice)

# Access VM disk images in /data/system/linux/
allow linux_manager linux_vm_data_file:dir create_dir_perms;
allow linux_manager linux_vm_data_file:file create_file_perms;

# Vsock socket creation & communication
allow linux_manager self:vsock_socket { create read write bind connect listen accept getattr setopt };
```

#### `system/sepolicy/private/linux_bridge.te`
```sepolicy
# Domain definition for linux_bridge native daemon
type linux_bridge, domain, coredomain;
type linux_bridge_exec, exec_type, file_type, system_file_type;
type linux_bridge_socket, file_type, coredomain_socket;

init_daemon_domain(linux_bridge)

# Unix domain socket communication with SystemServer
allow linux_bridge linux_bridge_socket:sock_file create_file_perms;
allow system_server linux_bridge_socket:sock_file rw_file_perms;
unix_socket_connect(system_server, linux_bridge, linux_bridge)

# Binder IPC with system_server
binder_use(linux_bridge)
binder_call(linux_bridge, system_server)
binder_call(system_server, linux_bridge)

# Vsock socket operations (Ports 5000 Control, 5001 PTY, 5002 Wayland)
allow linux_bridge self:vsock_socket { create read write bind connect listen accept getattr setopt };
```

#### `system/sepolicy/private/linux_portal.te`
```sepolicy
# Domain definition for linux_portal XDG portal bridge daemon
type linux_portal, domain, coredomain;
type linux_portal_exec, exec_type, file_type, system_file_type;
type linux_portal_socket, file_type, coredomain_socket;

init_daemon_domain(linux_portal)

# Unix socket communication with SystemServer / LinuxPortalService
allow linux_portal linux_portal_socket:sock_file create_file_perms;
allow system_server linux_portal_socket:sock_file rw_file_perms;
unix_socket_connect(system_server, linux_portal, linux_portal)

# Binder IPC for AppOps, Camera2, AudioRecord, LocationManager
binder_use(linux_portal)
binder_call(linux_portal, system_server)
binder_call(system_server, linux_portal)

# Service manager lookups
allow linux_portal appops_service:service_manager find;
allow linux_portal camera_service:service_manager find;
allow linux_portal audioserver_service:service_manager find;
allow linux_portal location_service:service_manager find;

# Vsock IPC for guest portal-agent communications
allow linux_portal self:vsock_socket { create read write bind connect listen accept getattr setopt };
```

---

## 3. F-R5-010: SELinux neverallow Enforcements

To guarantee system integrity and prevent guest VM exploits from breaking out of host boundaries, strict `neverallow` rules are compiled directly into policy:

```sepolicy
# HARD SECURITY BOUNDARY NEVERALLOW RULES

# 1. Never allow any linux_* domain to access EFS/NVRAM sensitive storage partitions
neverallow { linux_manager linux_bridge linux_portal } efs_file:dir *;
neverallow { linux_manager linux_bridge linux_portal } efs_file:file *;

# 2. Never allow linux_* domains to write or create files in the host system_data_file
neverallow { linux_manager linux_bridge linux_portal } system_data_file:file { write create delete };

# 3. Never allow direct raw block device read/write execution from any linux_* domain
neverallow { linux_manager linux_bridge linux_portal } block_device:blk_file { read write open ioctl };

# 4. Never allow linux_portal direct hardware raw IO access
neverallow linux_portal device:chr_file raw_io;

# 5. Prohibit direct access to cellular baseband / modem
neverallow { linux_manager linux_bridge linux_portal } radio_data_file:file *;
neverallow { linux_manager linux_bridge linux_portal } radio_service:service_manager find;

# 6. Prohibit unauthorized domain transitions to su or init
neverallow { linux_manager linux_bridge linux_portal } { su init }:process transition;
```

---

## 4. F-R5-011: CTS / VTS Compatibility Strategy

### 4.1 CTS Compliance Goals
- Zero failures on `CtsSELinuxHostTestCases` and `CtsSecurityTestCases`.
- Strict compliance with Treble VNDK interface stability (no illegal references to private vendor symbols).
- Full GSI (Generic System Image) boot compatibility.
- Zero permissive domains on `user` builds.

### 4.2 Verification Matrix

```
+------------------------------------+-----------------------------------------------------+-----------------------------------------------+
| Test Target                        | Command                                             | Pass Criteria                                 |
+------------------------------------+-----------------------------------------------------+-----------------------------------------------+
| SELinux Policy Compilation         | secilc -M true -G -c 30 system/sepolicy/private/... | Zero syntax or neverallow compilation errors  |
| CTS SELinux Host Test              | cts-tradefed run cts -m CtsSELinuxHostTestCases     | 0 failures, 0 denials                         |
| CTS Security Test                  | cts-tradefed run cts -m CtsSecurityTestCases        | 0 failures                                    |
| Audit Denial Inspection            | adb shell dmesg \| grep "avc: denied"               | Clean log output during guest boot/operation  |
+------------------------------------+-----------------------------------------------------+-----------------------------------------------+
```

---

## 5. F-R5-012: EROFS Base Image A/B Layout

### 5.1 Dual-Slot Storage Architecture

To provide atomic updates and instant rollback capabilities, guest system files are partitioned into read-only base layers and encrypted user layers:

```
/data/system/linux/
├── base_a.img             (Read-Only EROFS Base Image Slot A, ~1.8GB)
├── base_b.img             (Read-Only EROFS Base Image Slot B, ~1.8GB)
├── custom_overlay.img     (Writable ext4 Overlayfs layer)
├── user_home.img          (LUKS2 CE Encrypted ext4 user home directory)
└── slot_metadata.json     (Slot state, active_slot, boot_attempts, rollback_index)
```

### 5.2 Slot Metadata Contract (`slot_metadata.json`)

```json
{
  "active_slot": "slot_a",
  "slots": {
    "slot_a": {
      "image_path": "/data/system/linux/base_a.img",
      "version": "12.5.0-aosp1",
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "successful_boot": 1,
      "rollback_index": 1001
    },
    "slot_b": {
      "image_path": "/data/system/linux/base_b.img",
      "version": "12.4.0-aosp1",
      "sha256": "4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b",
      "successful_boot": 1,
      "rollback_index": 1000
    }
  },
  "boot_attempts": 0,
  "max_boot_attempts": 3
}
```

### 5.3 EROFS Advantages & Mounting
- **Compression**: EROFS LZ4/LZMA compression achieves ~35% storage reduction compared to standard ext4.
- **Performance**: High random-read performance (> 240 MB/s), ideal for container/VM base rootfs.
- **Immutability**: Read-only mount prevents guest malware from altering system binaries.

---

## 6. F-R5-013: AVB Key Signature Validation Engine

### 6.1 AVB Verification Workflow

```
[OTA Base Package] ---> [Parse vbmeta.img] ---> [Verify Public Key vs /system/etc/security/avb/guest_root_key.pub]
                                                         |
                                                         v
[Calculate Image SHA256] <--- [Compare Header Digest] <--- [Check Rollback Index >= Device Rollback Index]
         |
         v
[Write to Inactive Slot]
```

### 6.2 Implementation Structure (`system/vold/AvbVerifier.cpp`)

```cpp
#include <openssl/evp.h>
#include <openssl/rsa.h>
#include <openssl/sha.h>
#include <fstream>
#include <vector>
#include <string>

struct VbmetaHeader {
    char magic[4]; // "AVB0"
    uint32_t required_libavb_version_major;
    uint32_t required_libavb_version_minor;
    uint64_t authentication_data_block_size;
    uint64_t auxiliary_data_block_size;
    uint32_t algorithm_type; // AVB_ALGORITHM_TYPE_SHA256_RSA4096
    uint64_t rollback_index;
    uint32_t flags;
};

class AvbVerifier {
public:
    static bool VerifyGuestImage(const std::string& imagePath, const std::string& vbmetaPath, const std::string& trustedPubKeyPath, uint64_t currentRollbackIndex) {
        // 1. Read vbmeta header
        VbmetaHeader header;
        std::ifstream vbmetaFile(vbmetaPath, std::ios::binary);
        if (!vbmetaFile.is_open()) return false;
        vbmetaFile.read(reinterpret_cast<char*>(&header), sizeof(VbmetaHeader));

        // 2. Validate magic
        if (std::string(header.magic, 4) != "AVB0") return false;

        // 3. Rollback index enforcement
        if (header.rollback_index < currentRollbackIndex) {
            // Anti-rollback restriction violated
            return false;
        }

        // 4. RSA-4096 Signature Validation against trusted root key
        // ... OpenSSL EVP_DigestVerifyInit using guest_root_key.pub ...

        return true;
    }
};
```

---

## 7. F-R5-014: Boot Watchdog Rollback Engine

### 7.1 Watchdog State Machine & Sequence

```
                    +---------------------------+
                    |   VM Launch Triggered     |
                    | (boot_attempts += 1)      |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    |  Start 60s Watchdog Timer |
                    +-------------+-------------+
                                  |
           +----------------------+----------------------+
           |                                             |
[Heartbeat Received within 60s]             [Timeout or Guest Crash]
           |                                             |
           v                                             v
+---------------------------+             +---------------------------+
| boot_attempts = 0         |             | boot_attempts >= 3 ?      |
| successful_boot = 1       |             +--------------+------------+
+---------------------------+                            |
                                               +---------+---------+
                                               |                   |
                                             [YES]                [NO]
                                               |                   |
                                               v                   v
                                  +------------------+   +------------------+
                                  | Active Slot Flip |   | Retry Same Slot  |
                                  | (slot_a <-> b)   |   | (Relaunch VM)    |
                                  +------------------+   +------------------+
```

### 7.2 Host Watchdog Engine (`system/linux_bridge/guest_ota_rollback_watchdog.cpp`)

```cpp
#include <iostream>
#include <thread>
#include <chrono>
#include <atomic>
#include <fstream>

class BootWatchdogEngine {
private:
    std::atomic<bool> heartbeatReceived{false};
    std::thread timerThread;
    int maxTimeoutSec = 60;

public:
    void StartWatchdog(int attempts, const std::string& currentSlot) {
        heartbeatReceived = false;
        timerThread = std::thread([this, attempts, currentSlot]() {
            auto start = std::chrono::steady_clock::now();
            while (std::chrono::duration_cast<std::chrono::seconds>(
                       std::chrono::steady_clock::now() - start).count() < maxTimeoutSec) {
                if (heartbeatReceived) {
                    // Reset boot attempts on successful heartbeat
                    UpdateSlotMetadata(currentSlot, true, 0);
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
            }
            // Timeout reached!
            HandleBootFailure(attempts, currentSlot);
        });
    }

    void OnHeartbeatReceived() {
        heartbeatReceived = true;
    }

    void HandleBootFailure(int attempts, const std::string& failedSlot) {
        int nextAttempts = attempts + 1;
        if (nextAttempts >= 3) {
            // Trigger automatic slot rollback
            std::string rollbackSlot = (failedSlot == "slot_a") ? "slot_b" : "slot_a";
            PerformSlotRollback(failedSlot, rollbackSlot);
        } else {
            UpdateBootAttempts(failedSlot, nextAttempts);
        }
    }

private:
    void UpdateSlotMetadata(const std::string& slot, bool success, int attempts) {
        // Writes updated status to slot_metadata.json
    }

    void PerformSlotRollback(const std::string& failedSlot, const std::string& targetSlot) {
        // Flips active_slot in slot_metadata.json, marks failedSlot as successful_boot=0
        // Retains user_home.img intact!
    }

    void UpdateBootAttempts(const std::string& slot, int attempts) {
        // Increments boot attempt counter
    }
};
```

---

## 8. Build System Integration & Verification Strategy

### 8.1 `Android.bp` Additions (`system/linux_bridge/Android.bp`)

```bp
cc_binary {
    name: "guest_ota_rollback_watchdog",
    srcs: [
        "guest_ota_rollback_watchdog.cpp",
    ],
    shared_libs: [
        "libbase",
        "liblog",
        "libutils",
        "libcrypto",
    ],
    cflags: [
        "-Wall",
        "-Werror",
    ],
    init_rc: ["guest_ota_rollback_watchdog.rc"],
}
```

### 8.2 Test Suite Verification

Run all Tier 1 and Tier 2 test suites for M5 Features F-R5-009 to F-R5-014:

```bash
python3 tests/e2e/runner.py --tier 1 --filter F-R5-009
python3 tests/e2e/runner.py --tier 1 --filter F-R5-010
python3 tests/e2e/runner.py --tier 1 --filter F-R5-011
python3 tests/e2e/runner.py --tier 1 --filter F-R5-012
python3 tests/e2e/runner.py --tier 1 --filter F-R5-013
python3 tests/e2e/runner.py --tier 1 --filter F-R5-014

python3 tests/e2e/runner.py --tier 2 --filter F-R5-009
python3 tests/e2e/runner.py --tier 2 --filter F-R5-010
python3 tests/e2e/runner.py --tier 2 --filter F-R5-011
python3 tests/e2e/runner.py --tier 2 --filter F-R5-012
python3 tests/e2e/runner.py --tier 2 --filter F-R5-013
python3 tests/e2e/runner.py --tier 2 --filter F-R5-014
```

---

## 9. Conclusion

The technical strategy for features **F-R5-009 through F-R5-014** provides:
1. **Hardened SELinux Isolation**: Dedicated domains (`linux_manager`, `linux_bridge`, `linux_portal`) and strict `neverallow` rules protecting `efs_file`, system partition, and raw IO.
2. **CTS Compliance**: Zero-regression design matching `CtsSELinuxHostTestCases` and `CtsSecurityTestCases`.
3. **Resilient Dual-Slot OTA & AVB**: Immutable EROFS `base_a.img`/`base_b.img` layout with AVB RSA-4096 signature verification and anti-rollback protection.
4. **Automated Rollback Engine**: 3-boot attempt watchdog with automatic slot rollback on boot failure while preserving user home data intact.
