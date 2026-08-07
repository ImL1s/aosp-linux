#!/usr/bin/env python3
"""
Empirical Stress Test Harness for VM Boot & 4-Layer Storage Image Layout (M2 Iteration 3)
Challenger: challenger_m2_i3_1
"""

import os
import sys
import json
import time
import shutil
import tempfile
import subprocess
import fcntl

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
INIT_SCRIPT = os.path.join(PROJECT_ROOT, "guest", "scripts", "init_storage_layout.sh")
LAUNCH_SCRIPT = os.path.join(PROJECT_ROOT, "guest", "scripts", "launch_vm.sh")

EXPECTED_BASE_SIZE = 2621440000     # 2500M
EXPECTED_OVERLAY_SIZE = 4194304000  # 4000M
EXPECTED_HOME_SIZE = 5242880000     # 5000M

def run_cmd(cmd, env=None):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True, env=env or os.environ.copy())
    return res

def test_1_storage_init(temp_dir):
    print("--- Test 1: Storage Layout Initialization & Size Verification ---")
    res = run_cmd(f"bash '{INIT_SCRIPT}' '{temp_dir}'")
    assert res.returncode == 0, f"Init script failed: {res.stderr}"
    
    base_img = os.path.join(temp_dir, "base_rootfs.img")
    overlay_img = os.path.join(temp_dir, "custom_overlay.img")
    home_img = os.path.join(temp_dir, "user_home.img")
    
    s_base = os.path.getsize(base_img)
    s_overlay = os.path.getsize(overlay_img)
    s_home = os.path.getsize(home_img)
    
    print(f"base_rootfs.img size: {s_base} bytes (Expected: {EXPECTED_BASE_SIZE})")
    print(f"custom_overlay.img size: {s_overlay} bytes (Expected: {EXPECTED_OVERLAY_SIZE})")
    print(f"user_home.img size: {s_home} bytes (Expected: {EXPECTED_HOME_SIZE})")
    
    assert s_base == EXPECTED_BASE_SIZE, f"base_rootfs.img size mismatch: {s_base}"
    assert s_overlay == EXPECTED_OVERLAY_SIZE, f"custom_overlay.img size mismatch: {s_overlay}"
    assert s_home == EXPECTED_HOME_SIZE, f"user_home.img size mismatch: {s_home}"
    print("Test 1 PASS!\n")

def test_2_multiple_boots(temp_dir, iterations=50):
    print(f"--- Test 2: Sequential {iterations} Boot Attempts Zero-Truncation Check ---")
    base_img = os.path.join(temp_dir, "base_rootfs.img")
    overlay_img = os.path.join(temp_dir, "custom_overlay.img")
    home_img = os.path.join(temp_dir, "user_home.img")
    config_file = os.path.join(temp_dir, "vm_config.json")
    
    cfg = {
        "disks": {
            "base_rootfs": {"path": base_img},
            "custom_overlay": {"path": overlay_img},
            "user_home": {"path": home_img}
        }
    }
    with open(config_file, "w") as f:
        json.dump(cfg, f)
        
    env = os.environ.copy()
    env["TEST_MODE"] = "1"
    
    for i in range(1, iterations + 1):
        res = run_cmd(f"bash '{LAUNCH_SCRIPT}' '{config_file}'", env=env)
        assert res.returncode == 0, f"Boot attempt {i} failed: {res.stderr}"
        
        s_base = os.path.getsize(base_img)
        s_overlay = os.path.getsize(overlay_img)
        s_home = os.path.getsize(home_img)
        
        assert s_base == EXPECTED_BASE_SIZE, f"Iteration {i}: base_rootfs.img truncated to {s_base}"
        assert s_overlay == EXPECTED_OVERLAY_SIZE, f"Iteration {i}: custom_overlay.img truncated to {s_overlay}"
        assert s_home == EXPECTED_HOME_SIZE, f"Iteration {i}: user_home.img truncated to {s_home}"
        
    print(f"Test 2 PASS! All {iterations} boots preserved exact image sizes without zero-truncation.\n")

def test_3_flock_contention(temp_dir):
    print("--- Test 3: Process flock Lock Contention & Non-Truncation Check ---")
    base_img = os.path.join(temp_dir, "base_rootfs.img")
    overlay_img = os.path.join(temp_dir, "custom_overlay.img")
    config_file = os.path.join(temp_dir, "vm_config.json")
    
    env = os.environ.copy()
    env["TEST_MODE"] = "1"
    
    # 3.1 Contention on base_rootfs.img
    print("Subtest 3.1: Exclusive lock on base_rootfs.img...")
    f_base = open(base_img, "r")
    fcntl.flock(f_base.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        res = run_cmd(f"bash '{LAUNCH_SCRIPT}' '{config_file}'", env=env)
        print(f"Exit code: {res.returncode}, Output: {res.stderr.strip()}")
        assert res.returncode == 3, f"Expected exit code 3, got {res.returncode}"
        assert "ResourceBusy" in res.stderr or "ResourceBusy" in res.stdout, "Expected 'ResourceBusy' error message"
        assert os.path.getsize(base_img) == EXPECTED_BASE_SIZE, "base_rootfs.img size changed during lock contention!"
    finally:
        fcntl.flock(f_base.fileno(), fcntl.LOCK_UN)
        f_base.close()

    # 3.2 Contention on custom_overlay.img
    print("Subtest 3.2: Exclusive lock on custom_overlay.img...")
    f_overlay = open(overlay_img, "r")
    fcntl.flock(f_overlay.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        res = run_cmd(f"bash '{LAUNCH_SCRIPT}' '{config_file}'", env=env)
        print(f"Exit code: {res.returncode}, Output: {res.stderr.strip()}")
        assert res.returncode == 3, f"Expected exit code 3, got {res.returncode}"
        assert "ResourceBusy" in res.stderr or "ResourceBusy" in res.stdout, "Expected 'ResourceBusy' error message"
        assert os.path.getsize(overlay_img) == EXPECTED_OVERLAY_SIZE, "custom_overlay.img size changed during lock contention!"
    finally:
        fcntl.flock(f_overlay.fileno(), fcntl.LOCK_UN)
        f_overlay.close()

    print("Test 3 PASS!\n")

def test_4_parallel_boot_race(temp_dir):
    print("--- Test 4: Concurrent Parallel VM Boot Race Condition Test ---")
    config_file = os.path.join(temp_dir, "vm_config.json")
    base_img = os.path.join(temp_dir, "base_rootfs.img")
    overlay_img = os.path.join(temp_dir, "custom_overlay.img")
    
    num_procs = 10
    print(f"Spawning {num_procs} parallel launch_vm.sh processes...")
    
    env = os.environ.copy()
    env["TEST_MODE"] = "1"
    
    procs = []
    for _ in range(num_procs):
        p = subprocess.Popen(["bash", LAUNCH_SCRIPT, config_file], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        procs.append(p)
        
    results = []
    for p in procs:
        out, err = p.communicate()
        results.append((p.returncode, out, err))
        
    successes = [r for r in results if r[0] == 0]
    busies = [r for r in results if r[0] == 3]
    others = [r for r in results if r[0] not in (0, 3)]
    
    print(f"Parallel launch results: {len(successes)} succeeded (code 0), {len(busies)} locked out (code 3), {len(others)} other errors")
    assert len(others) == 0, f"Unexpected exit codes in parallel race: {others}"
    
    # Check image sizes after parallel race
    s_base = os.path.getsize(base_img)
    s_overlay = os.path.getsize(overlay_img)
    assert s_base == EXPECTED_BASE_SIZE, f"Parallel race modified base_rootfs.img size: {s_base}"
    assert s_overlay == EXPECTED_OVERLAY_SIZE, f"Parallel race modified custom_overlay.img size: {s_overlay}"
    print("Test 4 PASS!\n")

def test_5_script_syntax_and_locking_mode():
    print("--- Test 5: Launch Script Lock Mode Verification (exec < vs exec >) ---")
    with open(LAUNCH_SCRIPT, "r") as f:
        content = f.read()
        
    assert 'exec 200<"$BASE_IMG"' in content, "launch_vm.sh missing read-mode redirection exec 200<"
    assert 'exec 201<"$OVERLAY_IMG"' in content, "launch_vm.sh missing read-mode redirection exec 201<"
    assert 'exec 200>' not in content, "launch_vm.sh still contains write-mode truncation exec 200>"
    assert 'exec 201>' not in content, "launch_vm.sh still contains write-mode truncation exec 201>"
    print("Test 5 PASS!\n")

def main():
    temp_dir = tempfile.mkdtemp(prefix="challenger_m2_i3_")
    try:
        test_5_script_syntax_and_locking_mode()
        test_1_storage_init(temp_dir)
        test_2_multiple_boots(temp_dir, iterations=50)
        test_3_flock_contention(temp_dir)
        test_4_parallel_boot_race(temp_dir)
        print("==================================================")
        print("ALL EMPIRICAL STRESS TESTS PASSED SUCCESSFULLY!")
        print("==================================================")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

if __name__ == "__main__":
    main()
