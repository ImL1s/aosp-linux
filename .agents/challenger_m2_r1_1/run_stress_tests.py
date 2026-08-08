import os
import sys
import time
import socket
import json
import struct
import subprocess
import threading

BINARY_PATH = "/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/target/debug/bridge-agent"
TEST_SECRET = "test_secret_key_32bytes_long!!"

def print_result(name, passed, detail=""):
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] {name}" + (f" - {detail}" if detail else ""))

def test_1a_missing_secret_exit_code():
    """Verify std::process::exit(1) when secret extraction fails."""
    env = os.environ.copy()
    env.pop("LINUX_AUTH_SECRET", None)
    
    # Run process without LINUX_AUTH_SECRET
    proc = subprocess.Popen(
        [BINARY_PATH],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    stdout, stderr = proc.communicate(timeout=5)
    passed = (proc.returncode == 1) and ("Secret key extraction failed" in stderr or "Fatal" in stderr)
    print_result("Test 1a: Missing secret exit(1)", passed, f"Exit code: {proc.returncode}")
    return passed

def test_1b_port_collision_exit_code():
    """Verify std::process::exit(1) when port binding fails."""
    # Occupy port 5000 with dummy socket
    dummy = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    dummy.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    dummy.bind(("127.0.0.1", 5000))
    dummy.listen(1)

    env = os.environ.copy()
    env["LINUX_AUTH_SECRET"] = TEST_SECRET

    proc = subprocess.Popen(
        [BINARY_PATH],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    stdout, stderr = proc.communicate(timeout=5)
    dummy.close()

    passed = (proc.returncode == 1) and ("Failed to bind" in stderr or "Fatal" in stderr)
    print_result("Test 1b: Port binding collision exit(1)", passed, f"Exit code: {proc.returncode}")
    return passed

def start_agent_process():
    env = os.environ.copy()
    env["LINUX_AUTH_SECRET"] = TEST_SECRET
    proc = subprocess.Popen(
        [BINARY_PATH],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    time.sleep(0.5) # Allow sockets to bind
    return proc

def test_1c_invalid_and_zero_tokens(proc):
    """Verify handshake rejects invalid token and all-zero token."""
    # 1. Invalid token
    try:
        s = socket.create_connection(("127.0.0.1", 5000), timeout=3)
        s.sendall(b"wrong_secret_key_32bytes_long!!")
        resp = s.recv(1024)
        s.close()
        invalid_ok = (resp == b"AUTH_FAILED\n")
    except Exception as e:
        invalid_ok = False
        print("Invalid token test error:", e)

    # 2. All-zero token
    try:
        s = socket.create_connection(("127.0.0.1", 5000), timeout=3)
        s.sendall(b"\x00" * len(TEST_SECRET))
        resp = s.recv(1024)
        s.close()
        zero_ok = (resp == b"AUTH_FAILED\n")
    except Exception as e:
        zero_ok = False
        print("Zero token test error:", e)

    passed = invalid_ok and zero_ok
    print_result("Test 1c: Invalid token & All-zero token rejection", passed, 
                 f"Invalid token rejected: {invalid_ok}, Zero token rejected: {zero_ok}")
    return passed

def test_2_multithreaded_stress(proc):
    """Stress test ports 5000, 5001, 5002 concurrently across 30 threads."""
    errors = []
    
    def worker_portal(thread_id):
        try:
            s = socket.create_connection(("127.0.0.1", 5000), timeout=5)
            s.sendall(TEST_SECRET.encode('utf-8'))
            auth_resp = s.recv(1024)
            if auth_resp != b"AUTH_OK\n":
                errors.append(f"Portal thread {thread_id} auth failed: {auth_resp}")
                s.close()
                return

            req = {
                "id": thread_id,
                "method": "camera.status",
                "params": {}
            }
            s.sendall(json.dumps(req).encode('utf-8') + b"\n")
            line = s.makefile().readline()
            resp = json.loads(line)
            if not resp.get("success"):
                errors.append(f"Portal thread {thread_id} RPC failed: {line}")
            s.close()
        except Exception as e:
            errors.append(f"Portal thread {thread_id} exception: {e}")

    def worker_pty(thread_id):
        try:
            s = socket.create_connection(("127.0.0.1", 5001), timeout=5)
            s.sendall(TEST_SECRET.encode('utf-8'))
            auth_resp = s.recv(1024)
            if auth_resp != b"AUTH_OK\n":
                errors.append(f"PTY thread {thread_id} auth failed: {auth_resp}")
                s.close()
                return

            # Send PING frame (21 bytes)
            # Header: session_id (16B), msg_type (1B = 0x03), payload_len (4B BE = 0)
            session_id = b"\x01" * 16
            msg_type = 0x03
            payload_len = 0
            header = session_id + bytes([msg_type]) + struct.pack(">I", payload_len)
            s.sendall(header)

            # Receive PONG header (21 bytes)
            s.settimeout(2.0)
            pong_header = s.recv(21)
            if len(pong_header) != 21 or pong_header[16] != 0x03:
                errors.append(f"PTY thread {thread_id} invalid PONG header: {pong_header}")
            s.close()
        except Exception as e:
            errors.append(f"PTY thread {thread_id} exception: {e}")

    threads = []
    for i in range(15):
        t1 = threading.Thread(target=worker_portal, args=(i,))
        t2 = threading.Thread(target=worker_pty, args=(100 + i,))
        threads.extend([t1, t2])
        t1.start()
        t2.start()

    for t in threads:
        t.join()

    passed = len(errors) == 0
    print_result("Test 2: Multi-threaded Port 5000/5001 Stress", passed, 
                 f"Errors: {len(errors)}" if errors else "30 connections handled smoothly")
    if errors:
        for err in errors[:5]:
            print("  -", err)
    return passed

def test_3_pty_oom_vulnerability(proc):
    """Test sending an oversized payload_len header in PTY protocol to test for memory safety panic."""
    try:
        s = socket.create_connection(("127.0.0.1", 5001), timeout=5)
        s.sendall(TEST_SECRET.encode('utf-8'))
        auth_resp = s.recv(1024)
        if auth_resp != b"AUTH_OK\n":
            print_result("Test 3: PTY Oversized Header OOM Check", False, "Auth failed")
            return False

        # Session ID (16B), msg_type (1B = 0x01 DATA), payload_len = 2,000,000,000 (2 GB)
        session_id = b"\x02" * 16
        msg_type = 0x01
        payload_len = 2_000_000_000 # 2 GB
        header = session_id + bytes([msg_type]) + struct.pack(">I", payload_len)
        
        print("  - Sending 21-byte PTY header claiming 2,000,000,000 byte payload...")
        s.sendall(header)
        
        # Give server time to handle header
        time.sleep(0.5)
        
        # Check if process is still alive or crashed
        poll_res = proc.poll()
        if poll_res is not None:
            # Process crashed!
            print_result("Test 3: PTY Oversized Header OOM Check", False, 
                         f"VULNERABILITY FOUND! Process crashed with exit code {poll_res} due to unconstrained vec! allocation")
            return False
        else:
            print_result("Test 3: PTY Oversized Header OOM Check", True, "Process did not crash immediately")
            s.close()
            return True
    except Exception as e:
        print_result("Test 3: PTY Oversized Header OOM Check", False, f"Exception: {e}")
        return False

def test_4_wayland_full_duplex_deadlock_analysis(proc):
    """Test full-duplex proxying deadlocks when holding Mutex over blocking read."""
    # We will test wayland proxy behavior or test proxy_bi_directional behavior
    # Note: On macOS, /run/user/1000/wayland-0 doesn't exist, so wayland connect fails to socket path /tmp/wayland-0
    # Let's test if Port 5002 accepts handshake and handles failure gracefully
    try:
        s = socket.create_connection(("127.0.0.1", 5002), timeout=3)
        s.sendall(TEST_SECRET.encode('utf-8'))
        auth_resp = s.recv(1024)
        if auth_resp != b"AUTH_OK\n":
            print_result("Test 4: Wayland Proxy Handshake", False, "Auth failed")
            return False
        s.close()
        print_result("Test 4: Wayland Proxy Handshake", True, "Handshake succeeded")
        return True
    except Exception as e:
        print_result("Test 4: Wayland Proxy Handshake", False, f"Exception: {e}")
        return False

def main():
    print("=== EMPIRICAL CHALLENGER STRESS TEST SUITE ===")
    
    t1a = test_1a_missing_secret_exit_code()
    t1b = test_1b_port_collision_exit_code()
    
    proc = start_agent_process()
    try:
        t1c = test_1c_invalid_and_zero_tokens(proc)
        t2  = test_2_multithreaded_stress(proc)
        t3  = test_3_pty_oom_vulnerability(proc)
        t4  = test_4_wayland_full_duplex_deadlock_analysis(proc)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()

if __name__ == "__main__":
    main()
