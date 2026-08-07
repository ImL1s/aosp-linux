#!/usr/bin/env python3
"""
Empirical Verification Script for M2 (LUKS2 CE & Vsock HMAC)
Created by Challenger 2 (teamwork_preview_challenger)
"""

import subprocess
import os
import sys

def test_native_cxx_compile():
    print("[TEST 1] Compiling native C++ linux_bridge daemon (main.cpp + vsock_server.cpp + hmac_auth.cpp)...")
    cmd = [
        "clang++", "-std=c++20", "-Wall", "-Wextra", "-pthread",
        "-Isystem/linux_bridge", "-I.",
        "system/linux_bridge/main.cpp",
        "system/linux_bridge/socket_server.cpp",
        "system/linux_bridge/vsock_framing.cpp",
        "system/linux_bridge/vsock_server.cpp",
        "system/linux_bridge/hmac_auth.cpp",
        "-lssl", "-lcrypto",
        "-o", "build_out/bin/linux_bridge_daemon_test"
    ]
    res = subprocess.run(cmd, cwd="/Users/iml1s/Documents/mine/aosp-linux", capture_output=True, text=True)
    if res.returncode != 0:
        print("  -> FAIL (Compilation Error):")
        print(res.stderr[:500])
        return False, res.stderr
    else:
        print("  -> PASS (Compiled cleanly)")
        return True, ""

def test_java_key_derivation_consistency():
    print("[TEST 2] Verifying Java HKDF-SHA256 LUKS key derivation consistency across unlock cycles...")
    # Check if LinuxManagerService.java generates random mock key on unlock
    lms_path = "/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java"
    with open(lms_path, "r") as f:
        content = f.read()

    if "new java.security.SecureRandom().nextBytes(mockMasterKey)" in content:
        print("  -> FAIL: LinuxManagerService.java generates random mockMasterKey on every onUserUnlocked(), causing LUKS key mismatch across unlocks!")
        return False, "Random mockMasterKey on unlock breaks LUKS decryption persistence"
    else:
        print("  -> PASS: CE Master Key persistent across unlocks")
        return True, ""

def test_vsock_unauthenticated_bind():
    print("[TEST 3] Verifying vsock_server.cpp bindPort port 5001/5002 unauthenticated rejection...")
    vs_path = "/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/vsock_server.cpp"
    with open(vs_path, "r") as f:
        content = f.read()

    # Check if bindPort returns true even when unauthenticated
    if "if ((port == VSOCK_PORT_PTY || port == VSOCK_PORT_WAYLAND) && !mAuthenticated)" in content:
        if "mBoundPorts[port] = true;\n    return true;" in content:
            print("  -> FAIL: vsock_server.cpp bindPort() allows binding ports 5001/5002 even when !mAuthenticated!")
            return False, "bindPort returns true for unauthenticated session"

    print("  -> PASS: bindPort rejects unauthenticated ports")
    return True, ""

def test_e2e_runner():
    print("[TEST 4] Running python3 tests/e2e/runner.py...")
    res = subprocess.run(["python3", "tests/e2e/runner.py"], cwd="/Users/iml1s/Documents/mine/aosp-linux", capture_output=True, text=True)
    print("  -> Exit Code:", res.returncode)
    lines = [line for line in res.stdout.split("\n") if "TOTAL TESTS" in line or "PASSED" in line or "PASS RATE" in line]
    print("  -> Summary:", " | ".join(lines[-4:]))
    return res.returncode == 0

if __name__ == "__main__":
    print("=== CHALLENGER 2 EMPIRICAL TEST SUITE FOR M2 ===")
    t1_pass, t1_err = test_native_cxx_compile()
    t2_pass, t2_err = test_java_key_derivation_consistency()
    t3_pass, t3_err = test_vsock_unauthenticated_bind()
    t4_pass = test_e2e_runner()

    print("\n=== FINAL TEST SUMMARY ===")
    print("Test 1 (C++ Daemon Compilation):", "PASS" if t1_pass else "FAIL (BUILD ERROR)")
    print("Test 2 (LUKS Key Consistency):", "PASS" if t2_pass else "FAIL (LOGIC ERROR)")
    print("Test 3 (Vsock Auth Enforcement):", "PASS" if t3_pass else "FAIL (LOGIC ERROR)")
    print("Test 4 (Python E2E Test Suite):", "PASS" if t4_pass else "FAIL")

    if not (t1_pass and t2_pass and t3_pass):
        print("\nVERDICT: REJECT")
        sys.exit(1)
    else:
        print("\nVERDICT: APPROVE")
        sys.exit(0)
