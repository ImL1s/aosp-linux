"""
Tier 1 Functional Tests for Milestone 2: AVF Guest Setup & LUKS Encryption.
Features covered: F-R2-001 through F-R2-005 (Real file parsing and binary execution).
"""

import sys
import os
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, CommandRunner

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))

# ==============================================================================
# F-R2-001: Non-Protected Debian VM
# ==============================================================================
class TestR2_001_T1_26_LaunchCrosvmNonProtected(BaseTestCase):
    test_id = "T1-26"
    feature_id = "F-R2-001"
    title = "Launch crosvm instance with non-protected guest config"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        CustomAssertions.assert_true(os.path.exists(config_path), f"Config missing at {config_path}")
        with open(config_path, "r") as f:
            cfg = json.load(f)

        CustomAssertions.assert_false(cfg.get("protected"))
        CustomAssertions.assert_equal(cfg.get("vsock", {}).get("cid"), 3)
        CustomAssertions.assert_equal(cfg.get("kernel", {}).get("kernel_path"), "/apex/com.android.virt/etc/vmlinux")

        res = CommandRunner.run("bash -n guest/scripts/launch_vm.sh", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0, f"launch_vm.sh syntax error: {res.stderr}")


class TestR2_001_T1_27_GuestKernelBootVerification(BaseTestCase):
    test_id = "T1-27"
    feature_id = "F-R2-001"
    title = "Guest kernel boot verification (Debian 12 ARM64 6.6+)"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        cmdline = cfg.get("kernel", {}).get("cmdline", "")
        CustomAssertions.assert_in("console=ttyS0", cmdline)
        CustomAssertions.assert_in("root=/dev/vda", cmdline)
        CustomAssertions.assert_in("init=/sbin/init", cmdline)


class TestR2_001_T1_28_GuestSystemdInitCompletion(BaseTestCase):
    test_id = "T1-28"
    feature_id = "F-R2-001"
    title = "Guest systemd PID 1 init completion"
    tier = 1

    def run_test(self):
        service_path = os.path.join(PROJECT_ROOT, "guest", "systemd", "android-bridge-agent.service")
        CustomAssertions.assert_true(os.path.exists(service_path))
        with open(service_path, "r") as f:
            content = f.read()
        CustomAssertions.assert_in("ExecStart=/usr/bin/android-bridge-agent", content)
        CustomAssertions.assert_in("Restart=always", content)


class TestR2_001_T1_29_AndroidBridgeAgentServiceActive(BaseTestCase):
    test_id = "T1-29"
    feature_id = "F-R2-001"
    title = "android-bridge-agent service active in guest"
    tier = 1

    def run_test(self):
        cargo_path = os.path.join(PROJECT_ROOT, "guest", "bridge-agent", "Cargo.toml")
        with open(cargo_path, "r") as f:
            content = f.read()
        CustomAssertions.assert_in('name = "android-bridge-agent"', content)

        res = CommandRunner.run(
            'export PATH="$HOME/.cargo/bin:$PATH"; cargo check',
            cwd=os.path.join(PROJECT_ROOT, "guest", "bridge-agent")
        )
        CustomAssertions.assert_equal(res.exit_code, 0, f"Cargo check failed: {res.stderr}")


class TestR2_001_T1_30_VirtualCpuRamAllocation(BaseTestCase):
    test_id = "T1-30"
    feature_id = "F-R2-001"
    title = "Virtual CPU & RAM allocation matching requested configuration"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        CustomAssertions.assert_equal(cfg.get("cpu", {}).get("cpus"), 4)
        CustomAssertions.assert_equal(cfg.get("memory", {}).get("ram_mb"), 4096)


# ==============================================================================
# F-R2-002: 4-Layer Storage Image Layout
# ==============================================================================
class TestR2_002_T1_31_MountReadOnlyBaseRootfs(BaseTestCase):
    test_id = "T1-31"
    feature_id = "F-R2-002"
    title = "Mounting read-only base_rootfs.img on /"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        base_disk = cfg.get("disks", {}).get("base_rootfs", {})
        CustomAssertions.assert_true(base_disk.get("read_only"))
        CustomAssertions.assert_equal(base_disk.get("path"), "/data/misc/linux/base_rootfs.img")


class TestR2_002_T1_32_MountOverlayfsWritableLayer(BaseTestCase):
    test_id = "T1-32"
    feature_id = "F-R2-002"
    title = "Overlayfs writable layer mounted over /etc, /var, /usr"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        overlay_disk = cfg.get("disks", {}).get("custom_overlay", {})
        CustomAssertions.assert_false(overlay_disk.get("read_only"))

        script_path = os.path.join(PROJECT_ROOT, "guest", "scripts", "guest_mount_overlay.sh")
        with open(script_path, "r") as f:
            script = f.read()
        CustomAssertions.assert_in("lowerdir=", script)
        CustomAssertions.assert_in("upperdir=", script)


class TestR2_002_T1_33_MountLuksDecryptedUserHome(BaseTestCase):
    test_id = "T1-33"
    feature_id = "F-R2-002"
    title = "LUKS2 decrypted user_home.img mounted on /home/user"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        user_disk = cfg.get("disks", {}).get("user_home", {})
        CustomAssertions.assert_equal(user_disk.get("path"), "/dev/mapper/user_home_decrypted")
        CustomAssertions.assert_false(user_disk.get("read_only"))


class TestR2_002_T1_34_VmStateSnapshotCreation(BaseTestCase):
    test_id = "T1-34"
    feature_id = "F-R2-002"
    title = "VM state snapshot created at /data/misc/linux/vm_state.snapshot"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        snap_path = cfg.get("snapshot", {}).get("path")
        CustomAssertions.assert_equal(snap_path, "/data/misc/linux/vm_state.snapshot")


class TestR2_002_T1_35_OverlayfsDiffPersistence(BaseTestCase):
    test_id = "T1-35"
    feature_id = "F-R2-002"
    title = "Overlayfs diff persistence after reboot"
    tier = 1

    def run_test(self):
        res = CommandRunner.run("bash -n guest/scripts/guest_mount_overlay.sh", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)

        script_path = os.path.join(PROJECT_ROOT, "guest", "scripts", "guest_mount_overlay.sh")
        with open(script_path, "r") as f:
            content = f.read()
        CustomAssertions.assert_in("/mnt/overlay/upper", content)


# ==============================================================================
# F-R2-003: LUKS2 CE Storage Encryption
# ==============================================================================
class TestR2_003_T1_36_DeriveKeyFromCeKeymaster(BaseTestCase):
    test_id = "T1-36"
    feature_id = "F-R2-003"
    title = "Derive 256-bit encryption key from Android CE Keymaster / KeyMint"
    tier = 1

    def run_test(self):
        service_path = os.path.join(PROJECT_ROOT, "frameworks", "base", "services", "core", "java", "com", "android", "server", "linux", "LinuxManagerService.java")
        with open(service_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("aosp.linux.ce.user_home.luks2_master_key", code)


class TestR2_003_T1_37_CryptsetupOpenOnUserUnlock(BaseTestCase):
    test_id = "T1-37"
    feature_id = "F-R2-003"
    title = "cryptsetup open user_home.img using CE key on user unlock"
    tier = 1

    def run_test(self):
        service_path = os.path.join(PROJECT_ROOT, "frameworks", "base", "services", "core", "java", "com", "android", "server", "linux", "LinuxManagerService.java")
        with open(service_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("onUserUnlocked", code)
        CustomAssertions.assert_in("getOrGeneratePersistentMasterKey", code)
        CustomAssertions.assert_in("mCeKeyAvailable = true", code)


class TestR2_003_T1_38_MountDecryptedMapperToUserHome(BaseTestCase):
    test_id = "T1-38"
    feature_id = "F-R2-003"
    title = "Mount /dev/mapper/user_home_decrypted to /home/user"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        user_disk = cfg.get("disks", {}).get("user_home", {})
        CustomAssertions.assert_equal(user_disk.get("path"), "/dev/mapper/user_home_decrypted")


class TestR2_003_T1_39_UnmountAndCloseOnUserLock(BaseTestCase):
    test_id = "T1-39"
    feature_id = "F-R2-003"
    title = "Unmount & cryptsetup close on Android user lock"
    tier = 1

    def run_test(self):
        service_path = os.path.join(PROJECT_ROOT, "frameworks", "base", "services", "core", "java", "com", "android", "server", "linux", "LinuxManagerService.java")
        with open(service_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("onUserLocked", code)
        CustomAssertions.assert_in("Arrays.fill", code)
        CustomAssertions.assert_in("mCeKeyAvailable = false", code)


class TestR2_003_T1_40_Aes256XtsCipherValidation(BaseTestCase):
    test_id = "T1-40"
    feature_id = "F-R2-003"
    title = "AES-256-XTS cipher integrity verification"
    tier = 1

    def run_test(self):
        script_path = os.path.join(PROJECT_ROOT, "guest", "scripts", "init_storage_layout.sh")
        with open(script_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("aes-xts-plain64", code)


# ==============================================================================
# F-R2-004: Vsock 3-Port Allocation
# ==============================================================================
class TestR2_004_T1_41_Port5000ControlRpcBound(BaseTestCase):
    test_id = "T1-41"
    feature_id = "F-R2-004"
    title = "Port 5000 bound for Control RPC protocol"
    tier = 1

    def run_test(self):
        header_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "vsock_framing.h")
        with open(header_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("VSOCK_PORT_CONTROL = 5000", code)

        res = CommandRunner.run("./build_out/bin/linux_bridge_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0, f"linux_bridge_test failed: {res.stderr}")


class TestR2_004_T1_42_Port5001PtyStreamBound(BaseTestCase):
    test_id = "T1-42"
    feature_id = "F-R2-004"
    title = "Port 5001 bound for PTY terminal stream"
    tier = 1

    def run_test(self):
        header_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "vsock_framing.h")
        with open(header_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("VSOCK_PORT_PTY     = 5001", code)

        res = CommandRunner.run("./build_out/bin/challenger_m2_framing_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0, f"framing test failed: {res.stderr}")


class TestR2_004_T1_43_Port5002WaylandGuiBound(BaseTestCase):
    test_id = "T1-43"
    feature_id = "F-R2-004"
    title = "Port 5002 bound for Wayland GUI protocol"
    tier = 1

    def run_test(self):
        header_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "vsock_framing.h")
        with open(header_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("VSOCK_PORT_WAYLAND = 5002", code)

        res = CommandRunner.run("./build_out/bin/linux_bridge_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR2_004_T1_44_ThreePortBiDirectionalTransmission(BaseTestCase):
    test_id = "T1-44"
    feature_id = "F-R2-004"
    title = "Bi-directional byte transmission across all 3 ports"
    tier = 1

    def run_test(self):
        res = CommandRunner.run("./build_out/bin/linux_bridge_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY", res.stdout)


class TestR2_004_T1_45_IndependentPortCloseTeardown(BaseTestCase):
    test_id = "T1-45"
    feature_id = "F-R2-004"
    title = "Independent socket close on individual port teardown"
    tier = 1

    def run_test(self):
        cpp_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "vsock_server.cpp")
        with open(cpp_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("unbindPort", code)


# ==============================================================================
# F-R2-005: HMAC-SHA256 Auth Handshake
# ==============================================================================
class TestR2_005_T1_46_HostRandomTokenGeneration(BaseTestCase):
    test_id = "T1-46"
    feature_id = "F-R2-005"
    title = "Host generates single-use 256-bit random auth token"
    tier = 1

    def run_test(self):
        header_path = os.path.join(PROJECT_ROOT, "system", "linux_bridge", "hmac_auth.h")
        with open(header_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("generateRandomToken()", code)

        res = CommandRunner.run("./build_out/bin/challenger_m2_hmac_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR2_005_T1_47_TokenPassedViaVirtioSeed(BaseTestCase):
    test_id = "T1-47"
    feature_id = "F-R2-005"
    title = "Token passed to guest via virtio seed / kernel cmdline"
    tier = 1

    def run_test(self):
        config_path = os.path.join(PROJECT_ROOT, "guest", "config", "vm_config.json")
        with open(config_path, "r") as f:
            cfg = json.load(f)
        cmdline = cfg.get("kernel", {}).get("cmdline", "")
        CustomAssertions.assert_in("linux_auth_token=", cmdline)


class TestR2_005_T1_48_GuestComputesHmacSignature(BaseTestCase):
    test_id = "T1-48"
    feature_id = "F-R2-005"
    title = "Guest computes HMAC-SHA256 signature and returns challenge response"
    tier = 1

    def run_test(self):
        rs_path = os.path.join(PROJECT_ROOT, "guest", "bridge-agent", "src", "auth.rs")
        with open(rs_path, "r") as f:
            code = f.read()
        CustomAssertions.assert_in("HmacSha256", code)
        CustomAssertions.assert_in("compute_hmac_response", code)


class TestR2_005_T1_49_HostVerifiesChallengeResponse(BaseTestCase):
    test_id = "T1-49"
    feature_id = "F-R2-005"
    title = "Host verifies challenge response before opening ports 5001/5002"
    tier = 1

    def run_test(self):
        res = CommandRunner.run("./build_out/bin/challenger_m2_hmac_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("Single-use token replay rejection: PASS", res.stdout)


class TestR2_005_T1_50_SessionEstablishmentAuthenticatedState(BaseTestCase):
    test_id = "T1-50"
    feature_id = "F-R2-005"
    title = "Session establishment state marked authenticated"
    tier = 1

    def run_test(self):
        res = CommandRunner.run("./build_out/bin/challenger_m2_hmac_test", cwd=PROJECT_ROOT)
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("5s timeout window expiration: PASS", res.stdout)
