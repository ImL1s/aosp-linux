import os
import sys
import time
import socket
import struct
import subprocess

BINARY_PATH = "/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/target/debug/bridge-agent"
TEST_SECRET = "test_secret_key_32bytes_long!!"

def run_pty_oom_test():
    print("\n--- Testing Bug 2: PTY Protocol Payload OOM Allocation ---")
    
    env = os.environ.copy()
    env["LINUX_AUTH_SECRET"] = TEST_SECRET
    proc = subprocess.Popen(
        [BINARY_PATH],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    time.sleep(0.5)

    try:
        s = socket.create_connection(("127.0.0.1", 5001), timeout=3)
        s.sendall(TEST_SECRET.encode('utf-8'))
        auth_resp = s.recv(1024)
        if auth_resp != b"AUTH_OK\n":
            print("Auth failed:", auth_resp)
            return False

        # Header: 16B session ID + 1B msg_type (0x01 DATA) + 4B payload_len (3,000,000,000 = 3 GB)
        session_id = b"\x05" * 16
        msg_type = 0x01
        payload_len = 3_000_000_000
        header = session_id + bytes([msg_type]) + struct.pack(">I", payload_len)

        print("Sending 21-byte header requesting 3,000,000,000 byte memory allocation...")
        s.sendall(header)
        time.sleep(1.0)
        s.close()
    except Exception as e:
        print("Socket error:", e)

    stdout, stderr = proc.communicate(timeout=3)
    print("Process exit code:", proc.returncode)
    print("Process stderr snippet:", stderr[:500])

    if proc.returncode != 0 and ("allocation" in stderr or "memory" in stderr or proc.returncode in (-6, 101)):
        print("CONFIRMED BUG 2: Unconstrained payload_len caused OOM memory allocation panic/crash!")
        return True
    else:
        return False

if __name__ == "__main__":
    run_pty_oom_test()
