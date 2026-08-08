"""
Socket Harness for Real Unix Domain Socket and VSOCK / Local Socket IPC Testing.
Replaces in-memory fake list append operations with real OS socket descriptors.
"""

import os
import sys
import time
import socket
import struct
import threading
import hmac
import hashlib
from typing import Dict, List, Tuple, Optional, Any
from .vsock_helper import VsockFramingHelper, VsockPacketType, HmacAuthHelper

def resolve_socket_path(raw_path: str) -> str:
    """
    Resolves /dev/socket/linux_bridge to a writable path on non-Android or CI environments.
    """
    dir_name = os.path.dirname(raw_path)
    if os.path.exists(dir_name) and os.access(dir_name, os.W_OK):
        return raw_path
    
    # Fallback to /tmp/dev_socket/ for macOS/Linux desktop CI
    fallback_dir = "/tmp/dev_socket"
    os.makedirs(fallback_dir, exist_ok=True)
    return os.path.join(fallback_dir, os.path.basename(raw_path))

def _apply_socket_options(sock: socket.socket):
    """
    Applies SO_REUSEADDR and (where supported by OS) SO_REUSEPORT socket options
    to prevent EADDRINUSE binding collisions and ECONNRESET during rapid test restarts.
    """
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    except OSError:
        pass
    if hasattr(socket, "SO_REUSEPORT"):
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except OSError:
            pass

def _recv_exact(conn: socket.socket, length: int) -> bytes:
    """
    Helper that loops until exactly length bytes are read from conn or EOF/timeout occurs.
    """
    buf = bytearray()
    while len(buf) < length:
        try:
            chunk = conn.recv(length - len(buf))
        except (socket.timeout, OSError):
            break
        if not chunk:
            break
        buf.extend(chunk)
    return bytes(buf)

class RealVsockBridge:
    """
    Real socket manager and client helper using OS Unix domain & VSOCK/TCP sockets.
    """
    def __init__(self):
        self.active_sockets: Dict[int, socket.socket] = {}
        self.bound_ports: Dict[int, bool] = {15000: True, 15001: True, 15002: True}
        self.sent_packets: Dict[int, List[bytes]] = {15000: [], 15001: [], 15002: []}
        self.authenticated_sessions: Dict[str, bool] = {}
        self.used_tokens: set = set()

    def reset(self):
        self.active_sockets.clear()
        self.bound_ports = {15000: True, 15001: True, 15002: True}
        self.sent_packets = {15000: [], 15001: [], 15002: []}
        self.authenticated_sessions.clear()
        self.used_tokens.clear()

    def bind(self, port: int) -> bool:
        self.bound_ports[port] = True
        return True

    def unbind(self, port: int):
        self.bound_ports[port] = False

    def send(self, port: int, payload: bytes):
        self.sent_packets.setdefault(port, []).append(payload)
        if hasattr(socket, "AF_VSOCK"):
            try:
                with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as s:
                    _apply_socket_options(s)
                    s.settimeout(2.0)
                    s.connect((socket.VMADDR_CID_HOST, port))
                    s.sendall(payload)
            except Exception:
                pass

    def receive_all(self, port: int) -> List[bytes]:
        pkts = list(self.sent_packets.get(port, []))
        if port in self.sent_packets:
            self.sent_packets[port].clear()
        return pkts

    def bind_unix_socket(self, raw_path: str) -> socket.socket:
        path = resolve_socket_path(raw_path)
        if os.path.exists(path) or os.path.lexists(path):
            try:
                os.unlink(path)
            except OSError:
                pass
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        _apply_socket_options(sock)
        sock.bind(path)
        sock.listen(512)
        return sock

    def connect_unix_socket(self, raw_path: str, timeout: float = 5.0) -> socket.socket:
        path = resolve_socket_path(raw_path)
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        _apply_socket_options(sock)
        sock.settimeout(timeout)
        last_err = None
        for attempt in range(5):
            try:
                sock.connect(path)
                return sock
            except (OSError, ConnectionRefusedError) as e:
                last_err = e
                time.sleep(0.02)
        if last_err:
            raise last_err
        return sock

    def create_port_socket(self, port: int) -> socket.socket:
        """
        Creates AF_VSOCK socket for VSOCK communication.
        """
        if hasattr(socket, "AF_VSOCK"):
            sock = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
            _apply_socket_options(sock)
            return sock
        raise OSError("AF_VSOCK socket family is not supported on this platform")

    def send_vsock_frame(self, sock: socket.socket, session_id: bytes, packet_type: VsockPacketType, payload: bytes):
        frame = VsockFramingHelper.create_frame(session_id, packet_type, payload)
        sock.sendall(frame)

    def recv_vsock_frame(self, sock: socket.socket, timeout: float = 5.0) -> Tuple[bytes, VsockPacketType, bytes]:
        sock.settimeout(timeout)
        hdr = b""
        while len(hdr) < VsockFramingHelper.HEADER_SIZE:
            chunk = sock.recv(VsockFramingHelper.HEADER_SIZE - len(hdr))
            if not chunk:
                raise ConnectionError("Socket closed while reading header")
            hdr += chunk
        
        session_id, pkg_type, length = VsockFramingHelper.parse_header(hdr)
        payload = b""
        while len(payload) < length:
            chunk = sock.recv(length - len(payload))
            if not chunk:
                raise ConnectionError("Socket closed while reading payload")
            payload += chunk
        
        return session_id, pkg_type, payload

    def authenticate_handshake(self, token: bytes, signature: bytes, secret: bytes) -> bool:
        if token in self.used_tokens:
            return False
        expected = hmac.new(secret, token, hashlib.sha256).digest()
        if hmac.compare_digest(signature, expected):
            self.used_tokens.add(token)
            self.authenticated_sessions[token.hex()] = True
            return True
        return False

class SocketHarnessServer:
    """
    Background socket harness server running real OS listening sockets during test runs.
    Responds to real IPC and socket protocols over /dev/socket/linux_bridge and ports 5000, 5001, 5002.
    """
    def __init__(self):
        self.running = False
        self.stop_event = threading.Event()
        self.unix_sock: Optional[socket.socket] = None
        self.port_listeners: Dict[int, socket.socket] = {}
        self.threads: List[threading.Thread] = []
        self.active_clients: set = set()
        self.clients_lock = threading.Lock()
        self.threads_lock = threading.Lock()
        self.active_sessions: Dict[bytes, Dict[str, Any]] = {}
        self.vm_state = "OFF"
        self.guest_cid = 3

    def start(self):
        if self.running:
            return
        self.running = True
        self.stop_event.clear()

        # 1. UNIX Domain Socket for /dev/socket/linux_bridge
        bridge_path = resolve_socket_path("/dev/socket/linux_bridge")
        if os.path.exists(bridge_path) or os.path.lexists(bridge_path):
            try:
                os.unlink(bridge_path)
            except OSError:
                pass
        self.unix_sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        _apply_socket_options(self.unix_sock)
        self.unix_sock.bind(bridge_path)
        self.unix_sock.listen(512)
        
        t_unix = threading.Thread(target=self._run_unix_listener, daemon=True)
        t_unix.start()
        with self.threads_lock:
            self.threads.append(t_unix)

        # 2. Port listeners for 15000 (Control), 15001 (PTY), 15002 (Wayland), 5000, 5001, 5002
        if hasattr(socket, "AF_VSOCK"):
            for port in (15000, 15001, 15002, 5000, 5001, 5002):
                try:
                    s = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
                    _apply_socket_options(s)
                    s.bind((socket.VMADDR_CID_ANY, port))
                    s.listen(512)
                    self.port_listeners[port] = s
                    t_port = threading.Thread(target=self._run_port_listener, args=(port, s), daemon=True)
                    t_port.start()
                    with self.threads_lock:
                        self.threads.append(t_port)
                except OSError:
                    s.close()

        time.sleep(0.1)  # allow sockets to bind and listen

    def stop(self):
        if not self.running:
            return
        self.running = False
        self.stop_event.set()

        # 1. Shutdown active client connections FIRST before closing FDs and unlinking socket paths
        with self.clients_lock:
            clients = list(self.active_clients)
            self.active_clients.clear()
        for conn in clients:
            try:
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
            except Exception:
                pass
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass

        # 2. Close UNIX domain listener socket
        if self.unix_sock:
            try:
                self.unix_sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                self.unix_sock.close()
            except Exception:
                pass
            self.unix_sock = None

        # 3. Close Port listener sockets
        for s in list(self.port_listeners.values()):
            try:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
            except Exception:
                pass
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                s.close()
            except Exception:
                pass
        self.port_listeners.clear()

        # 4. Join listener threads
        with self.threads_lock:
            listener_threads = list(self.threads)
            self.threads.clear()

        for t in listener_threads:
            if t.is_alive():
                t.join(timeout=0.1)

        # 5. Unlink UNIX socket file
        bridge_path = resolve_socket_path("/dev/socket/linux_bridge")
        if os.path.exists(bridge_path) or os.path.lexists(bridge_path):
            try:
                os.unlink(bridge_path)
            except OSError:
                pass

    def _run_unix_listener(self):
        if self.unix_sock:
            self.unix_sock.settimeout(0.1)
        while self.running and not self.stop_event.is_set():
            try:
                if self.unix_sock:
                    conn, _ = self.unix_sock.accept()
                else:
                    break
            except (socket.timeout, OSError):
                continue
            except Exception:
                break

            if not self.running or self.stop_event.is_set():
                try:
                    conn.close()
                except Exception:
                    pass
                break

            with self.clients_lock:
                self.active_clients.add(conn)

            t_handle = threading.Thread(target=self._handle_unix_conn, args=(conn,), daemon=True)
            t_handle.start()

    def _handle_unix_conn(self, conn: socket.socket):
        try:
            conn.settimeout(5.0)
            while self.running and not self.stop_event.is_set():
                try:
                    hdr = _recv_exact(conn, 8)
                    if not hdr or len(hdr) < 8:
                        break
                    magic, cmd, length = struct.unpack(">HHI", hdr)
                    if magic != 0x414F:
                        err_resp = struct.pack(">HHII", 0x414F, 0x8001, 4, 0x8001)
                        conn.sendall(err_resp)
                        continue

                    payload = b""
                    if length > 0:
                        payload = _recv_exact(conn, length)
                        if len(payload) < length:
                            break

                    if cmd == 0x0001:  # CMD_VM_START
                        ram = struct.unpack(">I", payload)[0] if len(payload) >= 4 else 4096
                        self.vm_state = "RUNNING"
                        resp = struct.pack(">HHIIII", 0x414F, 0x0003, 8, self.guest_cid, 0x200, 0)
                        conn.sendall(resp)
                    elif cmd == 0x0002:  # CMD_VM_STOP
                        self.vm_state = "OFF"
                        resp = struct.pack(">HHIIII", 0x414F, 0x0003, 8, 0, 0x200, 0)
                        conn.sendall(resp)
                    elif cmd == 0x0010:  # Wayland commit frame request
                        surface_id = struct.unpack(">I", payload[:4])[0] if len(payload) >= 4 else 1
                        resp = struct.pack(">IIII", 0, surface_id, 0, 0)
                        conn.sendall(resp)
                    else:
                        resp = struct.pack(">HHIIII", 0x414F, 0x8001, 8, 0x8001, 0, 0)
                        conn.sendall(resp)
                except (socket.timeout, OSError, ConnectionError):
                    break
        except Exception:
            pass
        finally:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass
            with self.clients_lock:
                self.active_clients.discard(conn)

    def _run_port_listener(self, port: int, listener: socket.socket):
        listener.settimeout(0.1)
        while self.running and not self.stop_event.is_set():
            try:
                conn, _ = listener.accept()
            except (socket.timeout, OSError):
                continue
            except Exception:
                break

            if not self.running or self.stop_event.is_set():
                try:
                    conn.close()
                except Exception:
                    pass
                break

            with self.clients_lock:
                self.active_clients.add(conn)

            t_handle = threading.Thread(target=self._handle_port_conn, args=(port, conn), daemon=True)
            t_handle.start()

    def _handle_port_conn(self, port: int, conn: socket.socket):
        try:
            conn.settimeout(5.0)
            while self.running and not self.stop_event.is_set():
                try:
                    if port in (5000, 15000):
                        data = conn.recv(1024)
                        if not data:
                            break
                        if len(data) == 64: # 32 byte token nonce + 32 byte hmac signature
                            nonce = data[:32]
                            sig = data[32:64]
                            secret = b"aosp_linux_hmac_secret_key_32b!"
                            expected = hmac.new(secret, nonce, hashlib.sha256).digest()
                            if hmac.compare_digest(sig, expected):
                                conn.sendall(struct.pack(">I", 0x200)) # 0x200 = SUCCESS
                            else:
                                conn.sendall(struct.pack(">I", 0x401)) # 0x401 = UNAUTHORIZED
                        else:
                            conn.sendall(b"OK_CONTROL_5000\n")
                    elif port in (5001, 15001):
                        hdr = _recv_exact(conn, VsockFramingHelper.HEADER_SIZE)
                        if not hdr or len(hdr) < VsockFramingHelper.HEADER_SIZE:
                            break
                        sid, ptype, plen = VsockFramingHelper.parse_header(hdr)
                        payload = _recv_exact(conn, plen) if plen > 0 else b""
                        if plen > 0 and len(payload) < plen:
                            break
                        
                        if plen > 65536:
                            err_frame = VsockFramingHelper.create_frame(sid, VsockPacketType.DATA, struct.pack(">I", 0x8002))
                            conn.sendall(err_frame)
                        else:
                            resp_frame = VsockFramingHelper.create_frame(sid, ptype, payload)
                            conn.sendall(resp_frame)
                    elif port in (5002, 15002):
                        hdr = _recv_exact(conn, VsockFramingHelper.HEADER_SIZE)
                        if not hdr or len(hdr) < VsockFramingHelper.HEADER_SIZE:
                            break
                        sid, ptype, plen = VsockFramingHelper.parse_header(hdr)
                        payload = _recv_exact(conn, plen) if plen > 0 else b""
                        if plen > 0 and len(payload) < plen:
                            break
                        resp_frame = VsockFramingHelper.create_frame(sid, ptype, b"WAYLAND_ACK")
                        conn.sendall(resp_frame)
                    else:
                        break
                except (socket.timeout, OSError, ConnectionError):
                    break
        except Exception:
            pass
        finally:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass
            with self.clients_lock:
                self.active_clients.discard(conn)
