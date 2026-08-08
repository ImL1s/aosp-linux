import os
import sys
import time
import socket
import threading
import subprocess

BINARY_PATH = "/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent-m2/target/debug/bridge-agent"
TEST_SECRET = "test_secret_key_32bytes_long!!"
WAYLAND_SOCKET_PATH = "/tmp/wayland-0"

def run_wayland_duplex_test():
    print("\n--- Testing Bug 3: Wayland Proxy Full-Duplex Deadlock ---")
    
    # Clean up existing socket file if any
    if os.path.exists(WAYLAND_SOCKET_PATH):
        try:
            os.unlink(WAYLAND_SOCKET_PATH)
        except Exception:
            pass

    # 1. Start mock Wayland server listening on /tmp/wayland-0
    wayland_server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    wayland_server.bind(WAYLAND_SOCKET_PATH)
    wayland_server.listen(1)

    # 2. Start bridge-agent
    env = os.environ.copy()
    env["LINUX_AUTH_SECRET"] = TEST_SECRET
    env["WAYLAND_DISPLAY"] = WAYLAND_SOCKET_PATH
    proc = subprocess.Popen(
        [BINARY_PATH],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    time.sleep(0.5)

    try:
        # 3. Connect client over TCP Port 5002
        client_vsock = socket.create_connection(("127.0.0.1", 5002), timeout=3)
        client_vsock.sendall(TEST_SECRET.encode('utf-8'))
        auth_resp = client_vsock.recv(1024)
        if auth_resp != b"AUTH_OK\n":
            print("Auth failed:", auth_resp)
            return False

        # 4. Mock Wayland server accepts connection from proxy
        server_conn, _ = wayland_server.accept()

        print("Connected proxy between client socket and mock Wayland socket.")

        # Test Scenario:
        # The client is IDLE (not sending any data to proxy).
        # The mock Wayland server attempts to send an asynchronous event/frame to client.
        
        deadlock_detected = False
        
        def server_send():
            nonlocal deadlock_detected
            try:
                server_conn.settimeout(2.0)
                server_conn.sendall(b"WAYLAND_SERVER_FRAME_DATA")
            except Exception as e:
                print("Server send exception:", e)

        t_send = threading.Thread(target=server_send)
        t_send.start()

        # Client receives from proxy
        client_vsock.settimeout(2.0)
        try:
            data = client_vsock.recv(1024)
            print("Client received frame:", data)
            if data == b"WAYLAND_SERVER_FRAME_DATA":
                print("Data received successfully.")
            else:
                print("Unexpected data:", data)
        except socket.timeout:
            print("CLIENT TIMEOUT waiting for server frame!")
            deadlock_detected = True

        t_send.join()
        client_vsock.close()
        server_conn.close()

        if deadlock_detected:
            print("CONFIRMED BUG 3: Wayland proxy blocked server frame while client was idle due to Mutex lock during blocking read!")
            return True
        else:
            return False

    finally:
        wayland_server.close()
        if os.path.exists(WAYLAND_SOCKET_PATH):
            os.unlink(WAYLAND_SOCKET_PATH)
        proc.terminate()
        proc.wait(timeout=2)

if __name__ == "__main__":
    run_wayland_duplex_test()
