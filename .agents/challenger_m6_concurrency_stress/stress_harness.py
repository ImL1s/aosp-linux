#!/usr/bin/env python3
"""
Empirical Concurrency and Socket Stress Harness for Challenger M6
Location: .agents/challenger_m6_concurrency_stress/stress_harness.py
"""

import sys
import os
import time
import socket
import struct
import threading
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

# Add tests/e2e to sys.path
BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "tests", "e2e"))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from framework import SystemEnvironment, resolve_socket_path, VsockFramingHelper, VsockPacketType

def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)

def test_repeated_runner_execution(runs=3):
    log(f"=== TEST 1: REPEATED RUNNER EXECUTION ({runs} runs) ===")
    runner_script = os.path.join(BASE_DIR, "runner.py")
    results = []
    
    for i in range(1, runs + 1):
        log(f"Starting Run {i}/{runs}...")
        start_t = time.time()
        res = subprocess.run(
            [sys.executable, runner_script, "--tier", "1", "--tier", "2", "--tier", "3", "--tier", "4"],
            capture_output=True,
            text=True
        )
        duration = time.time() - start_t
        log(f"Run {i} finished in {duration:.2f}s with Exit Code {res.returncode}")
        
        # Check summary line in stdout
        passed_line = [line for line in res.stdout.splitlines() if "PASSED" in line and "TOTAL TESTS" in line]
        if passed_line:
            log(f"  Summary: {passed_line[0].strip()}")
        else:
            log(f"  Stdout last lines:\n" + "\n".join(res.stdout.splitlines()[-10:]))
            
        if res.returncode != 0:
            log(f"ERROR: Run {i} failed! stderr:\n{res.stderr}")
            return False, results
        results.append((i, duration, res.returncode))
        
    log(f"All {runs} runs completed successfully with Exit Code 0!\n")
    return True, results

def test_socket_lifecycle_cleanup():
    log("=== TEST 2: SOCKET LIFECYCLE & CLEANUP STRESS ===")
    env = SystemEnvironment()
    
    # 1. Start harness
    log("Starting SystemEnvironment harness...")
    env.start_harness()
    
    # Verify unix socket exists
    unix_path = resolve_socket_path("/dev/socket/linux_bridge")
    if not os.path.exists(unix_path):
        log(f"ERROR: UNIX socket path {unix_path} does not exist!")
        env.stop_harness()
        return False
    log(f"UNIX socket created successfully at: {unix_path}")
    
    # Verify ports 15000, 15001, 15002 are listening
    for port in (15000, 15001, 15002):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1.0)
        try:
            s.connect(("127.0.0.1", port))
            s.close()
            log(f"Port {port} is active and accepting connections.")
        except Exception as e:
            log(f"ERROR: Failed to connect to port {port}: {e}")
            env.stop_harness()
            return False

    # 2. Stop harness and verify cleanup
    log("Stopping SystemEnvironment harness...")
    env.stop_harness()
    time.sleep(0.1)
    
    # Verify unix socket file unlinked
    if os.path.exists(unix_path):
        log(f"ERROR: UNIX socket path {unix_path} still exists after stop_harness()!")
        return False
    log("UNIX socket file cleanly unlinked.")
    
    # Verify ports are closed
    for port in (15000, 15001, 15002):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.5)
        try:
            s.connect(("127.0.0.1", port))
            s.close()
            log(f"ERROR: Port {port} still open after stop_harness()!")
            return False
        except (ConnectionRefusedError, OSError):
            log(f"Port {port} closed cleanly.")
            
    # 3. Rapid Start/Stop Cycling (10 cycles)
    log("Running 10 rapid start/stop cycles of SocketHarnessServer...")
    for cycle in range(1, 11):
        env.start_harness()
        time.sleep(0.02)
        env.stop_harness()
        time.sleep(0.02)
    log("Rapid start/stop cycling completed with zero errors!\n")
    return True

def test_concurrent_socket_hammer(num_workers=50, requests_per_worker=20):
    log(f"=== TEST 3: CONCURRENT SOCKET HAMMER ({num_workers} workers, {requests_per_worker} reqs each = {num_workers * requests_per_worker} requests) ===")
    env = SystemEnvironment()
    env.start_harness()
    
    unix_path = resolve_socket_path("/dev/socket/linux_bridge")
    success_count = 0
    fail_count = 0
    lock = threading.Lock()
    
    def worker_task(worker_id):
        nonlocal success_count, fail_count
        local_success = 0
        local_fail = 0
        
        for r in range(requests_per_worker):
            try:
                # 1. UNIX socket request (CMD_VM_START or CMD_VM_STOP or Frame)
                s_unix = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s_unix.settimeout(2.0)
                s_unix.connect(unix_path)
                hdr = struct.pack(">HHI", 0x414F, 0x0001, 4)
                payload = struct.pack(">I", 2048)
                s_unix.sendall(hdr + payload)
                resp = s_unix.recv(16)
                s_unix.close()
                if len(resp) == 16:
                    local_success += 1
                else:
                    local_fail += 1

                # 2. Port 15001 PTY socket request
                s_15001 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s_15001.settimeout(2.0)
                s_15001.connect(("127.0.0.1", 15001))
                sid = b"session_" + str(worker_id).zfill(8).encode("utf-8")
                frame = VsockFramingHelper.create_frame(sid, VsockPacketType.DATA, f"hello_worker_{worker_id}_{r}".encode("utf-8"))
                s_15001.sendall(frame)
                resp_hdr = s_15001.recv(VsockFramingHelper.HEADER_SIZE)
                if len(resp_hdr) == VsockFramingHelper.HEADER_SIZE:
                    _, _, plen = VsockFramingHelper.parse_header(resp_hdr)
                    resp_payload = s_15001.recv(plen)
                    if len(resp_payload) == plen:
                        local_success += 1
                    else:
                        local_fail += 1
                else:
                    local_fail += 1
                s_15001.close()
                
            except Exception as e:
                local_fail += 2
                
        with lock:
            success_count += local_success
            fail_count += local_fail

    start_t = time.time()
    with ThreadPoolExecutor(max_workers=num_workers) as executor:
        futures = [executor.submit(worker_task, i) for i in range(num_workers)]
        for f in as_completed(futures):
            f.result()
            
    elapsed = time.time() - start_t
    env.stop_harness()
    
    total_ops = num_workers * requests_per_worker * 2
    log(f"Concurrent Hammer finished in {elapsed:.2f}s")
    log(f"Total IPC Operations: {total_ops}")
    log(f"Successful Operations: {success_count}")
    log(f"Failed Operations: {fail_count}")
    
    if fail_count == 0 and success_count == total_ops:
        log("Concurrent Socket Hammer PASSED with 100% success!\n")
        return True
    else:
        log(f"ERROR: {fail_count} operations failed under concurrency!\n")
        return False

def main():
    log("Starting Empirical Stress Verification for Milestone M6...")
    
    # Run repeated runner test (3 runs)
    t1_pass, t1_res = test_repeated_runner_execution(runs=3)
    
    # Run socket lifecycle cleanup test
    t2_pass = test_socket_lifecycle_cleanup()
    
    # Run concurrent hammer test
    t3_pass = test_concurrent_socket_hammer(num_workers=50, requests_per_worker=20)
    
    log("================ STRESS TEST SUMMARY ================")
    log(f"1. Repeated Execution (3 Runs, 430 tests each): {'PASS' if t1_pass else 'FAIL'}")
    log(f"2. Socket Lifecycle & Rapid Cycling (10 cycles)  : {'PASS' if t2_pass else 'FAIL'}")
    log(f"3. High Concurrency Hammer (2000 parallel ops)   : {'PASS' if t3_pass else 'FAIL'}")
    log("=====================================================")
    
    if t1_pass and t2_pass and t3_pass:
        log("OVERALL VERDICT: APPROVE")
        sys.exit(0)
    else:
        log("OVERALL VERDICT: REJECT")
        sys.exit(1)

if __name__ == "__main__":
    main()
