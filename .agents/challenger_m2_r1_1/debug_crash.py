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

def run_debug_test():
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

    def print_logs():
        stdout, stderr = proc.communicate()
        print("=== PROCESS STDOUT ===")
        print(stdout)
        print("=== PROCESS STDERR ===")
        print(stderr)

    print("Sending concurrent connections...")
    errors = []

    def worker_pty(thread_id):
        try:
            s = socket.create_connection(("127.0.0.1", 5001), timeout=5)
            s.sendall(TEST_SECRET.encode('utf-8'))
            auth_resp = s.recv(1024)
            if auth_resp != b"AUTH_OK\n":
                errors.append(f"PTY {thread_id} auth failed: {auth_resp}")
                s.close()
                return

            session_id = b"\x01" * 16
            msg_type = 0x03 # PING
            header = session_id + bytes([msg_type]) + struct.pack(">I", 0)
            s.sendall(header)
            pong = s.recv(21)
            s.close()
        except Exception as e:
            errors.append(f"PTY {thread_id} exc: {e}")

    threads = []
    for i in range(10):
        t = threading.Thread(target=worker_pty, args=(i,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    print("Finished worker threads. Errors:", len(errors))
    for e in errors:
        print(" -", e)

    time.sleep(0.5)
    if proc.poll() is not None:
        print("PROCESS DIED with exit code:", proc.returncode)
    else:
        proc.terminate()

    print_logs()

if __name__ == "__main__":
    run_debug_test()
