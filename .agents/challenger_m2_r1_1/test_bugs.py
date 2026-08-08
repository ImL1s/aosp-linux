import os
import sys
import time
import socket
import struct
import subprocess

BINARY_PATH = "/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/target/debug/bridge-agent"
TEST_SECRET = "test_secret_key_32bytes_long!!"

def run_bug1_pty_crash_test():
    print("\n--- Testing Bug 1: PTY spawn_shell I/O Safety Abort ---")
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
        resp = s.recv(1024)
        print("Auth response:", resp)
        s.close()
    except Exception as e:
        print("Socket error:", e)

    time.sleep(0.5)
    ret = proc.poll()
    stdout, stderr = proc.communicate()
    print("Process return code:", ret)
    print("Process stderr:\n", stderr)

    if ret == -6 or "IO Safety violation" in stderr:
        print("CONFIRMED BUG 1: PTY connection triggers fatal IO Safety violation SIGABRT (-6)!")
        return True
    else:
        print("Bug 1 not reproduced as SIGABRT.")
        return False

if __name__ == "__main__":
    run_bug1_pty_crash_test()
