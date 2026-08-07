#!/usr/bin/env python3
"""
Empirical Stress Test Harness for Milestone M2 Iteration 3
Author: Challenger 1 (teamwork_preview_challenger)
Date: 2026-08-06

Empirical verification of:
1. launch_vm.sh ZERO file truncation (exec 200< fix)
2. init_storage_layout.sh 0-byte image auto-recovery ([ ! -s ] fix)
3. launch_vm.sh custom vm_config.json parameter override to crosvm CLI
4. Lock contention handling (flock exit code 3)
"""

import os
import sys
import json
import time
import fcntl
import shutil
import tempfile
import subprocess
import unittest

WORKSPACE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LAUNCH_SCRIPT = os.path.join(WORKSPACE_ROOT, "guest", "scripts", "launch_vm.sh")
INIT_STORAGE_SCRIPT = os.path.join(WORKSPACE_ROOT, "guest", "scripts", "init_storage_layout.sh")
MOUNT_OVERLAY_SCRIPT = os.path.join(WORKSPACE_ROOT, "guest", "scripts", "guest_mount_overlay.sh")

class TestM2EmpiricalVerification(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="challenger_m2_i3_")
        self.bin_dir = os.path.join(self.temp_dir, "bin")
        os.makedirs(self.bin_dir, exist_ok=True)

        # Environment setup with mock crosvm logger
        self.crosvm_log = os.path.join(self.temp_dir, "crosvm_args.log")
        crosvm_mock = os.path.join(self.bin_dir, "crosvm")
        with open(crosvm_mock, "w") as f:
            f.write(f"""#!/bin/sh
echo "$@" > "{self.crosvm_log}"
exit 0
""")
        os.chmod(crosvm_mock, 0o755)

        self.env = os.environ.copy()
        self.env["PATH"] = f"{self.bin_dir}:{self.env.get('PATH', '')}"
        self.env["TEST_MODE"] = "1"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_01_zero_truncation_on_launch_vm(self):
        """Verify launch_vm.sh execution results in ZERO truncation of image files."""
        # 1. Create storage layout
        init_res = subprocess.run(["bash", INIT_STORAGE_SCRIPT, self.temp_dir], capture_output=True, text=True)
        self.assertEqual(init_res.returncode, 0, f"Storage init failed: {init_res.stderr}")

        base_img = os.path.join(self.temp_dir, "base_rootfs.img")
        overlay_img = os.path.join(self.temp_dir, "custom_overlay.img")
        user_home = os.path.join(self.temp_dir, "user_home.img")

        base_size_before = os.path.getsize(base_img)
        overlay_size_before = os.path.getsize(overlay_img)

        self.assertEqual(base_size_before, 2621440000, "Initial base_rootfs.img size should be 2500MB")
        self.assertEqual(overlay_size_before, 4194304000, "Initial custom_overlay.img size should be 4000MB")

        # 2. Write custom config matching these paths
        config_path = os.path.join(self.temp_dir, "vm_config.json")
        cfg_data = {
            "disks": {
                "base_rootfs": {"path": base_img},
                "custom_overlay": {"path": overlay_img},
                "user_home": {"path": user_home}
            }
        }
        with open(config_path, "w") as f:
            json.dump(cfg_data, f)

        # 3. Execute launch_vm.sh
        launch_res = subprocess.run(["bash", LAUNCH_SCRIPT, config_path], capture_output=True, text=True, env=self.env)
        self.assertEqual(launch_res.returncode, 0, f"launch_vm.sh failed: {launch_res.stderr}")

        # 4. Measure sizes after execution
        base_size_after = os.path.getsize(base_img)
        overlay_size_after = os.path.getsize(overlay_img)

        print(f"[EMPIRICAL EVIDENCE] Base image size before: {base_size_before}, after: {base_size_after}")
        print(f"[EMPIRICAL EVIDENCE] Overlay image size before: {overlay_size_before}, after: {overlay_size_after}")

        self.assertEqual(base_size_after, base_size_before, f"Truncation detected in base_rootfs.img! Was {base_size_before}, now {base_size_after}")
        self.assertEqual(overlay_size_after, overlay_size_before, f"Truncation detected in custom_overlay.img! Was {overlay_size_before}, now {overlay_size_after}")

    def test_02_zero_byte_image_auto_recovery(self):
        """Verify init_storage_layout.sh automatically recovers 0-byte corrupted image files."""
        base_img = os.path.join(self.temp_dir, "base_rootfs.img")
        overlay_img = os.path.join(self.temp_dir, "custom_overlay.img")
        home_img = os.path.join(self.temp_dir, "user_home.img")

        # Create empty 0-byte files
        open(base_img, "wb").close()
        open(overlay_img, "wb").close()
        open(home_img, "wb").close()

        self.assertEqual(os.path.getsize(base_img), 0)
        self.assertEqual(os.path.getsize(overlay_img), 0)
        self.assertEqual(os.path.getsize(home_img), 0)

        # Run storage initialization
        init_res = subprocess.run(["bash", INIT_STORAGE_SCRIPT, self.temp_dir], capture_output=True, text=True)
        self.assertEqual(init_res.returncode, 0, f"init_storage_layout.sh failed: {init_res.stderr}")

        # Verify recovered file sizes
        base_size_rec = os.path.getsize(base_img)
        overlay_size_rec = os.path.getsize(overlay_img)
        home_size_rec = os.path.getsize(home_img)

        print(f"[EMPIRICAL EVIDENCE] Recovered base size: {base_size_rec} bytes (expected 2621440000)")
        print(f"[EMPIRICAL EVIDENCE] Recovered overlay size: {overlay_size_rec} bytes (expected 4194304000)")
        print(f"[EMPIRICAL EVIDENCE] Recovered user home size: {home_size_rec} bytes (expected 5242880000)")

        self.assertEqual(base_size_rec, 2621440000, "base_rootfs.img 0-byte recovery failed")
        self.assertEqual(overlay_size_rec, 4194304000, "custom_overlay.img 0-byte recovery failed")
        self.assertEqual(home_size_rec, 5242880000, "user_home.img 0-byte recovery failed")

    def test_03_custom_vm_config_parameter_override(self):
        """Verify custom vm_config.json parameters (RAM, vCPU, CID, paths) are passed to crosvm CLI."""
        config_path = os.path.join(self.temp_dir, "custom_vm_config.json")
        base_img = os.path.join(self.temp_dir, "custom_base.img")
        overlay_img = os.path.join(self.temp_dir, "custom_overlay.img")
        home_mapper = "/dev/mapper/custom_home_decrypted"
        kernel_path = os.path.join(self.temp_dir, "custom_vmlinux")
        initrd_path = os.path.join(self.temp_dir, "custom_initrd.img")

        # Create dummy kernel/initrd/disk files
        open(base_img, "wb").write(b"BASE")
        open(overlay_img, "wb").write(b"OVERLAY")
        open(kernel_path, "wb").write(b"KERNEL")
        open(initrd_path, "wb").write(b"INITRD")

        custom_cfg = {
            "vm_name": "custom_debian_vm",
            "protected": False,
            "cpu": {
                "cpus": 8
            },
            "memory": {
                "ram_mb": 8192
            },
            "vsock": {
                "cid": 42
            },
            "kernel": {
                "kernel_path": kernel_path,
                "initrd_path": initrd_path
            },
            "disks": {
                "base_rootfs": {"path": base_img},
                "custom_overlay": {"path": overlay_img},
                "user_home": {"path": home_mapper}
            }
        }
        with open(config_path, "w") as f:
            json.dump(custom_cfg, f)

        # Run launch_vm.sh with custom config
        launch_res = subprocess.run(["bash", LAUNCH_SCRIPT, config_path], capture_output=True, text=True, env=self.env)
        self.assertEqual(launch_res.returncode, 0, f"launch_vm.sh failed: {launch_res.stderr}\nStdout: {launch_res.stdout}")

        # Read captured crosvm CLI arguments
        self.assertTrue(os.path.exists(self.crosvm_log), "crosvm was not executed!")
        with open(self.crosvm_log, "r") as f:
            captured_args = f.read().strip()

        print(f"[EMPIRICAL EVIDENCE] Captured crosvm arguments:\n{captured_args}")

        # Assert parameters were correctly overridden
        self.assertIn("--cid 42", captured_args, "CID parameter override failed")
        self.assertIn("--cpus 8", captured_args, "CPUs parameter override failed")
        self.assertIn("--mem 8192", captured_args, "RAM parameter override failed")
        self.assertIn(f"--kernel {kernel_path}", captured_args, "Kernel path parameter override failed")
        self.assertIn(f"--initrd {initrd_path}", captured_args, "Initrd path parameter override failed")
        self.assertIn(f"--rodisk {base_img}", captured_args, "Base rootfs path parameter override failed")
        self.assertIn(f"--rwdisk {overlay_img}", captured_args, "Custom overlay path parameter override failed")
        self.assertIn(f"--rwdisk {home_mapper}", captured_args, "User home path parameter override failed")

    def test_04_flock_lock_contention(self):
        """Verify lock contention on base_rootfs.img and custom_overlay.img triggers exit code 3."""
        base_img = os.path.join(self.temp_dir, "base_rootfs.img")
        overlay_img = os.path.join(self.temp_dir, "custom_overlay.img")
        open(base_img, "wb").write(b"BASE")
        open(overlay_img, "wb").write(b"OVERLAY")

        config_path = os.path.join(self.temp_dir, "vm_config.json")
        cfg_data = {
            "disks": {
                "base_rootfs": {"path": base_img},
                "custom_overlay": {"path": overlay_img}
            }
        }
        with open(config_path, "w") as f:
            json.dump(cfg_data, f)

        # 1. Lock base_rootfs.img
        lock_fd = open(base_img, "r")
        fcntl.flock(lock_fd.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

        launch_res1 = subprocess.run(["bash", LAUNCH_SCRIPT, config_path], capture_output=True, text=True, env=self.env)
        self.assertEqual(launch_res1.returncode, 3, f"Expected return code 3 for base_rootfs lock, got {launch_res1.returncode}")
        self.assertIn("ResourceBusy: base_rootfs.img is locked", launch_res1.stderr)

        fcntl.flock(lock_fd.fileno(), fcntl.LOCK_UN)
        lock_fd.close()

        # 2. Lock custom_overlay.img
        lock_fd2 = open(overlay_img, "r")
        fcntl.flock(lock_fd2.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

        launch_res2 = subprocess.run(["bash", LAUNCH_SCRIPT, config_path], capture_output=True, text=True, env=self.env)
        self.assertEqual(launch_res2.returncode, 3, f"Expected return code 3 for custom_overlay lock, got {launch_res2.returncode}")
        self.assertIn("ResourceBusy: custom_overlay.img is locked", launch_res2.stderr)

        fcntl.flock(lock_fd2.fileno(), fcntl.LOCK_UN)
        lock_fd2.close()

        print("[EMPIRICAL EVIDENCE] Lock contention behavior (exit code 3, ResourceBusy stderr) verified.")

if __name__ == "__main__":
    unittest.main()
