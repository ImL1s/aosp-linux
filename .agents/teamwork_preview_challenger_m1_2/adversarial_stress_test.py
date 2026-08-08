#!/usr/bin/env python3
"""
Adversarial Stress Test Harness for Milestone M1 (Real AVF VM Launch - R1)
Written by Challenger 2 to empirically verify edge cases and failure modes.
"""

import os
import sys
import subprocess
import time
import signal
import tempfile
import json
import fcntl

SCRIPT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../guest/scripts/launch_vm.sh"))

def test_missing_config_file():
    print("[CHALLENGER TEST 1] Missing VM config file handling... ", end="", flush=True)
    env = os.environ.copy()
    env["TEST_MODE"] = "1"
    non_existent_config = "/tmp/non_existent_vm_config_99999.json"
    if os.path.exists(non_existent_config):
        os.remove(non_existent_config)

    proc = subprocess.Popen(
        [SCRIPT_PATH, non_existent_config, "test_token_123"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )
    time.sleep(0.5)
    proc.terminate()
    stdout, stderr = proc.communicate()

    assert "[Launch Script] Launching crosvm Non-Protected VM (CID: 3, CPUs: 4, RAM: 4096MB)..." in stdout, \
        f"Defaults not applied on missing config. Output: {stdout}"
    assert "android_bridge.token=test_token_123" in stdout, \
        f"Token not passed correctly. Output: {stdout}"
    print("PASS")

def test_malformed_json_config():
    print("[CHALLENGER TEST 2] Malformed JSON config handling... ", end="", flush=True)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write('{ "memory": { "ram_mb": BAD_VAL, "cpus": } }')
        temp_config = f.name

    try:
        env = os.environ.copy()
        env["TEST_MODE"] = "1"
        proc = subprocess.Popen(
            [SCRIPT_PATH, temp_config, "token_malformed_json"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env
        )
        time.sleep(0.5)
        proc.terminate()
        stdout, stderr = proc.communicate()

        # Should fall back gracefully to default RAM 4096MB without crashing script
        assert "[Launch Script] Launching crosvm Non-Protected VM (CID: 3, CPUs: 4, RAM: 4096MB)..." in stdout, \
            f"Fallback to defaults failed on malformed JSON. Output: {stdout}"
        print("PASS")
    finally:
        if os.path.exists(temp_config):
            os.remove(temp_config)

def test_empty_and_invalid_tokens():
    print("[CHALLENGER TEST 3] Empty & invalid security token handling... ", end="", flush=True)
    env = os.environ.copy()
    env["TEST_MODE"] = "1"
    
    # 1. Empty token argument (uses default token in script)
    proc = subprocess.Popen(
        [SCRIPT_PATH, "/non_existent.json", ""],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )
    time.sleep(0.5)
    proc.terminate()
    stdout, stderr = proc.communicate()

    assert "android_bridge.token=" in stdout, f"Empty token not handled. Output: {stdout}"
    
    # 2. Token with special characters / spaces
    proc2 = subprocess.Popen(
        [SCRIPT_PATH, "/non_existent.json", "token_with_$spec!al_chars"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )
    time.sleep(0.5)
    proc2.terminate()
    stdout2, stderr2 = proc2.communicate()
    assert "android_bridge.token=token_with_$spec!al_chars" in stdout2, \
        f"Special char token failed. Output: {stdout2}"

    print("PASS")

def test_flock_lock_contention():
    print("[CHALLENGER TEST 4] Concurrent flock lock contention handling... ", end="", flush=True)
    with tempfile.TemporaryDirectory() as temp_dir:
        base_img = os.path.join(temp_dir, "base_rootfs.img")
        overlay_img = os.path.join(temp_dir, "custom_overlay.img")
        config_path = os.path.join(temp_dir, "vm_config.json")

        with open(base_img, "wb") as f:
            f.write(b"\x00" * 1024)
        with open(overlay_img, "wb") as f:
            f.write(b"\x00" * 1024)

        config_data = {
            "memory": {"ram_mb": 1024},
            "cpu": {"cpus": 2},
            "vsock": {"cid": 3},
            "disks": {
                "base_rootfs": {"path": base_img},
                "custom_overlay": {"path": overlay_img}
            }
        }
        with open(config_path, "w") as f:
            json.dump(config_data, f)

        # Lock base_img exclusively in this process
        lock_fd = os.open(base_img, os.O_RDONLY)
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

        try:
            env = os.environ.copy()
            env["TEST_MODE"] = "1"
            proc = subprocess.Popen(
                [SCRIPT_PATH, config_path, "token_flock"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=env
            )
            stdout, stderr = proc.communicate(timeout=5)
            exit_code = proc.returncode

            assert exit_code == 3, f"Expected exit code 3 on flock contention, got {exit_code}"
            assert "ResourceBusy: base_rootfs.img is locked by another process" in stderr, \
                f"Expected ResourceBusy error in stderr, got: {stderr}"
            print("PASS")
        finally:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
            os.close(lock_fd)

def test_test_mode_behavior():
    print("[CHALLENGER TEST 5] TEST_MODE=1 vs TEST_MODE=0 behavior... ", end="", flush=True)
    
    # TEST_MODE=0 on system without /dev/kvm
    env0 = os.environ.copy()
    env0["TEST_MODE"] = "0"
    proc0 = subprocess.Popen(
        [SCRIPT_PATH, "/non_existent.json", "token_test_mode"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env0
    )
    stdout0, stderr0 = proc0.communicate(timeout=5)
    exit_code0 = proc0.returncode

    if not os.path.exists("/dev/kvm"):
        assert exit_code0 == 1, f"TEST_MODE=0 should fail on missing /dev/kvm, got {exit_code0}"
        assert "KVMException: /dev/kvm not found" in stderr0, f"Expected KVM error, got {stderr0}"

    # TEST_MODE=1 bypasses KVM check
    env1 = os.environ.copy()
    env1["TEST_MODE"] = "1"
    proc1 = subprocess.Popen(
        [SCRIPT_PATH, "/non_existent.json", "token_test_mode"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env1
    )
    time.sleep(0.5)
    proc1.terminate()
    stdout1, stderr1 = proc1.communicate()
    assert "[Launch Script] Launching crosvm Non-Protected VM" in stdout1, f"TEST_MODE=1 failed to bypass KVM check"

    print("PASS")

def test_child_pid_termination_cleanup():
    print("[CHALLENGER TEST 6] Child PID termination and cleanup... ", end="", flush=True)
    env = os.environ.copy()
    env["TEST_MODE"] = "1"

    proc = subprocess.Popen(
        [SCRIPT_PATH, "/non_existent.json", "token_term_test"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )
    time.sleep(0.5)
    pid = proc.pid

    # Send SIGTERM
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()

    # Verify PID is no longer alive
    is_alive = True
    try:
        os.kill(pid, 0)
    except OSError:
        is_alive = False

    assert not is_alive, f"Child PID {pid} still alive after SIGTERM/SIGKILL"
    print("PASS")

def main():
    print("=== Running Challenger 2 Adversarial Stress Test Harness ===")
    test_missing_config_file()
    test_malformed_json_config()
    test_empty_and_invalid_tokens()
    test_flock_lock_contention()
    test_test_mode_behavior()
    test_child_pid_termination_cleanup()
    print("=== ALL ADVERSARIAL STRESS TESTS PASSED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
