"""
Mock Environment definitions for AOSP Dual-OS E2E Test Suite.
"""

from typing import Dict, List, Any, Optional
import hmac
import hashlib

class MockVsockBridge:
    def __init__(self):
        self.bound_ports: Dict[int, bool] = {5000: False, 5001: False, 5002: False}
        self.connected_cids: Dict[int, int] = {}
        self.authenticated_sessions: Dict[str, bool] = {}
        self.sent_packets: Dict[int, List[bytes]] = {5000: [], 5001: [], 5002: []}
        self.active_auth_token: Optional[bytes] = None
        self.used_tokens: set = set()

    def bind(self, port: int) -> bool:
        if port not in self.bound_ports:
            return False
        if self.bound_ports[port]:
            return False  # Already bound
        self.bound_ports[port] = True
        return True

    def unbind(self, port: int):
        if port in self.bound_ports:
            self.bound_ports[port] = False

    def send(self, port: int, payload: bytes):
        if not self.bound_ports.get(port):
            raise ConnectionError(f"Vsock port {port} is not connected")
        self.sent_packets[port].append(payload)

    def receive_all(self, port: int) -> List[bytes]:
        pkts = self.sent_packets.get(port, [])
        self.sent_packets[port] = []
        return pkts

    def authenticate_handshake(self, token: bytes, signature: bytes, secret: bytes) -> bool:
        if token in self.used_tokens:
            return False  # Single-use token rejection
        expected = hmac.new(secret, token, hashlib.sha256).digest()
        if hmac.compare_digest(signature, expected):
            self.used_tokens.add(token)
            self.authenticated_sessions[token.hex()] = True
            return True
        return False

class MockSystemServer:
    def __init__(self):
        self.vm_state: str = "OFF"
        self.registered_callbacks: List[Any] = []
        self.appops_permissions: Dict[str, Dict[str, str]] = {}
        self.registered_services: Dict[str, Any] = {}
        self.user_unlocked: bool = False
        self.ce_key_available: bool = False
        self.audit_logs: List[str] = []

    def set_state(self, new_state: str):
        valid_states = ["OFF", "STARTING", "RUNNING", "SUSPENDED", "STOPPING", "ERROR"]
        if new_state not in valid_states:
            raise ValueError(f"Invalid VM state: {new_state}")
        self.vm_state = new_state
        for cb in self.registered_callbacks:
            if hasattr(cb, "on_status_changed"):
                cb.on_status_changed(new_state)

    def unlock_user(self):
        self.user_unlocked = True
        self.ce_key_available = True

    def lock_user(self):
        self.user_unlocked = False
        self.ce_key_available = False

    def set_appop(self, package_name: str, op_name: str, mode: str):
        if package_name not in self.appops_permissions:
            self.appops_permissions[package_name] = {}
        self.appops_permissions[package_name][op_name] = mode

    def check_appop(self, package_name: str, op_name: str) -> str:
        return self.appops_permissions.get(package_name, {}).get(op_name, "PROMPT")

    def log_selinux_audit(self, log_entry: str):
        self.audit_logs.append(log_entry)

class MockSommelier:
    def __init__(self):
        self.active_surfaces: Dict[int, Dict[str, Any]] = {}
        self.next_surface_id: int = 1

    def create_surface(self, app_id: str, width: int, height: int) -> int:
        sid = self.next_surface_id
        self.next_surface_id += 1
        self.active_surfaces[sid] = {
            "app_id": app_id,
            "width": width,
            "height": height,
            "committed_frames": 0,
        }
        return sid

    def commit_frame(self, surface_id: int):
        if surface_id in self.active_surfaces:
            self.active_surfaces[surface_id]["committed_frames"] += 1

    def destroy_surface(self, surface_id: int):
        self.active_surfaces.pop(surface_id, None)

class MockXdgPortal:
    def __init__(self, system_server: MockSystemServer):
        self.system_server = system_server

    def request_camera_access(self, app_id: str) -> bool:
        mode = self.system_server.check_appop(app_id, "OP_CAMERA")
        if mode == "ALLOWED":
            return True
        elif mode == "DENIED":
            return False
        # If PROMPT, simulate grant for happy path
        return True

    def request_microphone_access(self, app_id: str) -> bool:
        mode = self.system_server.check_appop(app_id, "OP_RECORD_AUDIO")
        if mode == "ALLOWED":
            return True
        elif mode == "DENIED":
            return False
        return True

    def request_location_access(self, app_id: str) -> Dict[str, Any]:
        mode = self.system_server.check_appop(app_id, "OP_FINE_LOCATION")
        if mode == "DENIED":
            raise PermissionError("Location access denied by AppOps")
        return {"latitude": 25.0330, "longitude": 121.5654, "accuracy": 5.0}

class MockEnvironment:
    def __init__(self):
        self.vsock = MockVsockBridge()
        self.system_server = MockSystemServer()
        self.sommelier = MockSommelier()
        self.portal = MockXdgPortal(self.system_server)
        self.installed_desktop_apps: Dict[str, Dict[str, str]] = {}
        self.active_task_ids: Dict[int, str] = {}
        self.boot_slot: str = "slot_a"
        self.boot_attempts: int = 0
        self.storage_mounts: Dict[str, Dict[str, Any]] = {}
        self.selinux_rules: Dict[str, List[str]] = {}
        self.neverallow_rules: List[str] = []
        self.audio_focus_state: str = "NONE"
        self.audio_volume: float = 1.0
        self.shared_files_host: Dict[str, bytes] = {}
        self.shared_files_guest: Dict[str, bytes] = {}
        self.saf_documents: Dict[str, Dict[str, Any]] = {}
        self.avb_key_valid: bool = True
        self.vbmeta_digest: str = "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0"
        self.cts_results: Dict[str, int] = {"passed": 0, "failed": 0}

    def reset(self):
        self.vsock = MockVsockBridge()
        self.system_server = MockSystemServer()
        self.sommelier = MockSommelier()
        self.portal = MockXdgPortal(self.system_server)
        self.installed_desktop_apps.clear()
        self.active_task_ids.clear()
        self.boot_slot = "slot_a"
        self.boot_attempts = 0
        self.storage_mounts = {
            "/": {"device": "base_rootfs.img", "opts": "ro"},
            "/etc": {"device": "overlayfs", "opts": "rw"},
            "/var": {"device": "overlayfs", "opts": "rw"},
            "/usr": {"device": "overlayfs", "opts": "rw"},
            "/home/user": {"device": "/dev/mapper/user_home_decrypted", "opts": "rw"},
        }
        self.selinux_rules = {
            "linux_manager.te": ["allow linux_manager system_server:binder call"],
            "linux_bridge.te": ["allow linux_bridge self:vsock_socket create"],
            "linux_portal.te": ["allow linux_portal appops_service:service_manager find"],
        }
        self.neverallow_rules = [
            "neverallow linux_bridge efs_file:file *",
            "neverallow linux_manager system_file:file write",
            "neverallow linux_portal device:chr_file raw_io",
        ]
        self.audio_focus_state = "NONE"
        self.audio_volume = 1.0
        self.shared_files_host.clear()
        self.shared_files_guest.clear()
        self.saf_documents.clear()
        self.avb_key_valid = True
        self.vbmeta_digest = "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0"
        self.cts_results = {"passed": 170, "failed": 0}

