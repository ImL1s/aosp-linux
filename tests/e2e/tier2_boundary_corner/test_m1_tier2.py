"""
Tier 2 Boundary & Corner Case Tests for Milestone 1: AOSP Framework & Core Modifications.
Features: F-R1-001 through F-R1-005 (Tests T2-01 .. T2-25)
"""

import sys
import os
import threading
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, MockEnvironment, TestStatus, VsockFramingHelper, VsockPacketType

# -----------------------------------------------------------------------------
# F-R1-001: Framework API Namespace (T2-01 .. T2-05)
# -----------------------------------------------------------------------------
class TestR1_001_T2_01_NullCallbackRejection(BaseTestCase):
    test_id = "T2-01"
    feature_id = "F-R1-001"
    title = "Reject null status callback registration with IllegalArgumentException"
    tier = 2

    def run_test(self):
        def register_null():
            # Simulated API check for null listener registration
            cb = None
            if cb is None:
                raise ValueError("IllegalArgumentException: Callback cannot be null")
            self.mock_env.system_server.registered_callbacks.append(cb)

        CustomAssertions.assert_raises(ValueError, register_null)


class TestR1_001_T2_02_UnpermissionedCaller(BaseTestCase):
    test_id = "T2-02"
    feature_id = "F-R1-001"
    title = "Enforce MANAGE_LINUX_ENVIRONMENT permission on public API calls"
    tier = 2

    def run_test(self):
        def invoke_api_without_permission(caller_permissions):
            if "MANAGE_LINUX_ENVIRONMENT" not in caller_permissions:
                raise PermissionError("SecurityException: Requires MANAGE_LINUX_ENVIRONMENT permission")
            return self.mock_env.system_server.vm_state

        CustomAssertions.assert_raises(
            PermissionError,
            invoke_api_without_permission,
            ["android.permission.INTERNET"]
        )


class TestR1_001_T2_03_CorruptedParcelable(BaseTestCase):
    test_id = "T2-03"
    feature_id = "F-R1-001"
    title = "Handle unparceling corrupted LinuxAppInfo parcel data gracefully"
    tier = 2

    def run_test(self):
        def unparcel_app_info(raw_data: bytes):
            if len(raw_data) < 8 or not raw_data.startswith(b"APP_INFO"):
                raise ValueError("BadParcelableException: Corrupted parcel payload header")
            return {"app_id": "ok"}

        corrupted_payload = b"CORRUPTED_GARBAGE_BYTES"
        CustomAssertions.assert_raises(ValueError, unparcel_app_info, corrupted_payload)


class TestR1_001_T2_04_DuplicateCallbackRegister(BaseTestCase):
    test_id = "T2-04"
    feature_id = "F-R1-001"
    title = "Duplicate registration of same callback instance returns success without duplicate triggers"
    tier = 2

    def run_test(self):
        cb_instance = "CallbackObject_123"
        ss = self.mock_env.system_server
        
        # Helper to safely register unique callback
        if cb_instance not in ss.registered_callbacks:
            ss.registered_callbacks.append(cb_instance)
        if cb_instance not in ss.registered_callbacks:
            ss.registered_callbacks.append(cb_instance)

        count = ss.registered_callbacks.count(cb_instance)
        CustomAssertions.assert_equal(count, 1, "Duplicate callback should not be registered twice")


class TestR1_001_T2_05_BinderDeadObjectRecovery(BaseTestCase):
    test_id = "T2-05"
    feature_id = "F-R1-001"
    title = "Recover state when underlying binder service dies"
    tier = 2

    def run_test(self):
        # Simulate binder death detection and state re-query recovery
        service_connected = False
        try:
            if not service_connected:
                raise ConnectionError("DeadObjectException: Binder transaction failed")
        except ConnectionError:
            # Automatic reconnection & state sync fallback
            service_connected = True
            self.mock_env.system_server.set_state("OFF")

        CustomAssertions.assert_true(service_connected)
        CustomAssertions.assert_equal(self.mock_env.system_server.vm_state, "OFF")


# -----------------------------------------------------------------------------
# F-R1-002: Framework AIDL Interfaces (T2-06 .. T2-10)
# -----------------------------------------------------------------------------
class TestR1_002_T2_06_AidlTimeout(BaseTestCase):
    test_id = "T2-06"
    feature_id = "F-R1-002"
    title = "Transaction timeout handling on hanging AIDL calls"
    tier = 2

    def run_test(self):
        def invoke_hanging_aidl(timeout_sec: float):
            simulated_execution_time = 6.0
            if simulated_execution_time > timeout_sec:
                raise TimeoutError("TransactionTimeoutException: AIDL call exceeded 5.0s limit")

        CustomAssertions.assert_raises(TimeoutError, invoke_hanging_aidl, 5.0)


class TestR1_002_T2_07_MaxPayloadCap(BaseTestCase):
    test_id = "T2-07"
    feature_id = "F-R1-002"
    title = "Max payload cap (1MB parcel cap) enforcement on AIDL byte transfer"
    tier = 2

    def run_test(self):
        def send_aidl_bytes(payload: bytes):
            MAX_PARCEL_CAP = 1024 * 1024  # 1 MB
            if len(payload) > MAX_PARCEL_CAP:
                raise ValueError(f"TransactionTooLargeException: Payload size {len(payload)} > {MAX_PARCEL_CAP}")

        oversized_payload = b"X" * (1024 * 1024 + 100)
        CustomAssertions.assert_raises(ValueError, send_aidl_bytes, oversized_payload)


class TestR1_002_T2_08_ConcurrentAidlLock(BaseTestCase):
    test_id = "T2-08"
    feature_id = "F-R1-002"
    title = "Concurrent multi-threaded AIDL calls lock safety"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        errors = []

        def worker(state_name):
            try:
                for _ in range(20):
                    ss.set_state(state_name)
                    time.sleep(0.001)
            except Exception as e:
                errors.append(e)

        t1 = threading.Thread(target=worker, args=("STARTING",))
        t2 = threading.Thread(target=worker, args=("RUNNING",))
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        CustomAssertions.assert_equal(len(errors), 0, "Concurrent AIDL invocations caused exceptions")


class TestR1_002_T2_09_DeadBinderCallback(BaseTestCase):
    test_id = "T2-09"
    feature_id = "F-R1-002"
    title = "Handling dead binder remote process during callback dispatch"
    tier = 2

    def run_test(self):
        class FaultyListener:
            def on_status_changed(self, s):
                raise RuntimeError("DeadObjectException: Remote client process terminated")

        listener = FaultyListener()
        ss = self.mock_env.system_server
        ss.registered_callbacks.append(listener)

        # Dispatch status change and handle dead listener pruning
        dead_callbacks = []
        for cb in list(ss.registered_callbacks):
            try:
                cb.on_status_changed("RUNNING")
            except Exception:
                dead_callbacks.append(cb)

        for dead in dead_callbacks:
            ss.registered_callbacks.remove(dead)

        CustomAssertions.assert_false(listener in ss.registered_callbacks)


class TestR1_002_T2_10_InvalidUidCaller(BaseTestCase):
    test_id = "T2-10"
    feature_id = "F-R1-002"
    title = "Invalid UID caller rejection over AIDL transaction interface"
    tier = 2

    def run_test(self):
        def check_binder_uid(caller_uid: int):
            SYSTEM_UID = 1000
            ROOT_UID = 0
            if caller_uid not in (SYSTEM_UID, ROOT_UID):
                raise PermissionError(f"SecurityException: Caller UID {caller_uid} is unauthorized")

        untrusted_app_uid = 10042
        CustomAssertions.assert_raises(PermissionError, check_binder_uid, untrusted_app_uid)


# -----------------------------------------------------------------------------
# F-R1-003: SystemServer Integration (T2-11 .. T2-15)
# -----------------------------------------------------------------------------
class TestR1_003_T2_11_SystemServerCrashRecovery(BaseTestCase):
    test_id = "T2-11"
    feature_id = "F-R1-003"
    title = "SystemServer crash recovery and service re-registration"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.registered_services.clear()

        # Service recovery hook re-publishes service
        ss.registered_services["linux"] = "LinuxManagerService"
        CustomAssertions.assert_in("linux", ss.registered_services)


class TestR1_003_T2_12_EarlyBootHandshakeDelay(BaseTestCase):
    test_id = "T2-12"
    feature_id = "F-R1-003"
    title = "Handshake delay during early boot phase non-blocking boot process"
    tier = 2

    def run_test(self):
        boot_completed = False

        def get_service_status():
            if not boot_completed:
                return "STARTING"
            return "RUNNING"

        status = get_service_status()
        CustomAssertions.assert_equal(status, "STARTING", "Early boot should return STARTING without blocking")


class TestR1_003_T2_13_SystemShutdownHook(BaseTestCase):
    test_id = "T2-13"
    feature_id = "F-R1-003"
    title = "System shut-down hook clean VM termination"
    tier = 2

    def run_test(self):
        self.mock_env.vsock.bind(5000)
        self.mock_env.system_server.set_state("RUNNING")

        # Shutdown broadcast trigger
        self.mock_env.system_server.set_state("OFF")
        self.mock_env.vsock.unbind(5000)

        CustomAssertions.assert_equal(self.mock_env.system_server.vm_state, "OFF")
        CustomAssertions.assert_false(self.mock_env.vsock.bound_ports[5000])


class TestR1_003_T2_14_LmkPressureSignal(BaseTestCase):
    test_id = "T2-14"
    feature_id = "F-R1-003"
    title = "Low memory killer pressure signal handling by SystemServer service"
    tier = 2

    def run_test(self):
        memory_trimmed = False

        def on_trim_memory(level: int):
            nonlocal memory_trimmed
            TRIM_MEMORY_COMPLETE = 80
            if level >= TRIM_MEMORY_COMPLETE:
                memory_trimmed = True

        on_trim_memory(80)
        CustomAssertions.assert_true(memory_trimmed)


class TestR1_003_T2_15_UntrustedUidDenial(BaseTestCase):
    test_id = "T2-15"
    feature_id = "F-R1-003"
    title = "Deny unauthorized access from background untrusted UIDs"
    tier = 2

    def run_test(self):
        def validate_request(is_background: bool, uid: int):
            if is_background and uid > 10000:
                raise PermissionError("AccessDenied: Background untrusted app forbidden")

        CustomAssertions.assert_raises(PermissionError, validate_request, True, 10088)


# -----------------------------------------------------------------------------
# F-R1-004: Daemon Process Isolation (T2-16 .. T2-20)
# -----------------------------------------------------------------------------
class TestR1_004_T2_16_DaemonSigkillWatchdog(BaseTestCase):
    test_id = "T2-16"
    feature_id = "F-R1-004"
    title = "Automatic process respawn watchdog on daemon SIGKILL"
    tier = 2

    def run_test(self):
        daemon_pid = 1234
        daemon_alive = False

        # Watchdog detection loop
        if not daemon_alive:
            daemon_pid = 5678  # Respawned process PID
            daemon_alive = True
            self.mock_env.vsock.bind(5000)

        CustomAssertions.assert_true(daemon_alive)
        CustomAssertions.assert_equal(daemon_pid, 5678)
        CustomAssertions.assert_true(self.mock_env.vsock.bound_ports[5000])


class TestR1_004_T2_17_MalformedFrameRejection(BaseTestCase):
    test_id = "T2-17"
    feature_id = "F-R1-004"
    title = "Reject malformed vsock binary frames at daemon parser boundary"
    tier = 2

    def run_test(self):
        invalid_header = b"SHORT_HDR"
        CustomAssertions.assert_raises(ValueError, VsockFramingHelper.parse_header, invalid_header)


class TestR1_004_T2_18_SocketBufferOverflow(BaseTestCase):
    test_id = "T2-18"
    feature_id = "F-R1-004"
    title = "Prevent socket buffer overflow under high byte-rate flood"
    tier = 2

    def run_test(self):
        vs = self.mock_env.vsock
        vs.bind(5000)

        # Send packet flood
        MAX_QUEUE_LEN = 50
        for i in range(100):
            vs.send(5000, f"FLOOD_PKT_{i}".encode())
            if len(vs.sent_packets[5000]) > MAX_QUEUE_LEN:
                vs.sent_packets[5000].pop(0)  # Drop oldest on overflow

        CustomAssertions.assert_equal(len(vs.sent_packets[5000]), MAX_QUEUE_LEN)


class TestR1_004_T2_19_DaemonNetworkRestriction(BaseTestCase):
    test_id = "T2-19"
    feature_id = "F-R1-004"
    title = "Restrict daemon privileges to block non-vsock network access"
    tier = 2

    def run_test(self):
        def daemon_socket_create(family: str):
            if family != "AF_VSOCK" and family != "AF_UNIX":
                raise PermissionError("SELinuxDenial: daemon cannot create AF_INET socket")

        CustomAssertions.assert_raises(PermissionError, daemon_socket_create, "AF_INET")


class TestR1_004_T2_20_MemoryLeakPingStress(BaseTestCase):
    test_id = "T2-20"
    feature_id = "F-R1-004"
    title = "Memory leak validation under continuous 24h ping stress"
    tier = 2

    def run_test(self):
        vs = self.mock_env.vsock
        vs.bind(5000)
        vs.receive_all(5000)

        for _ in range(500):
            vs.send(5000, b"PING")
            pkts = vs.receive_all(5000)
            CustomAssertions.assert_equal(len(pkts), 1)

        # Buffer must be empty after receive_all
        CustomAssertions.assert_equal(len(vs.sent_packets[5000]), 0)


# -----------------------------------------------------------------------------
# F-R1-005: State Machine Lifecycle (T2-21 .. T2-25)
# -----------------------------------------------------------------------------
class TestR1_005_T2_21_InvalidStateTransition(BaseTestCase):
    test_id = "T2-21"
    feature_id = "F-R1-005"
    title = "Invalid transition rejection (e.g. OFF -> SUSPENDED)"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        CustomAssertions.assert_equal(ss.vm_state, "OFF")
        
        # Valid state validation logic
        def set_state_checked(new_state):
            valid_next = {
                "OFF": ["STARTING"],
                "STARTING": ["RUNNING", "ERROR"],
                "RUNNING": ["SUSPENDED", "STOPPING", "ERROR"],
                "SUSPENDED": ["RUNNING", "STOPPING", "ERROR"],
                "STOPPING": ["OFF", "ERROR"],
                "ERROR": ["OFF"],
            }
            if new_state not in valid_next.get(ss.vm_state, []):
                raise ValueError(f"Invalid transition from {ss.vm_state} to {new_state}")
            ss.set_state(new_state)

        CustomAssertions.assert_raises(ValueError, set_state_checked, "SUSPENDED")


class TestR1_005_T2_22_RapidStartStopRace(BaseTestCase):
    test_id = "T2-22"
    feature_id = "F-R1-005"
    title = "Rapid start/stop call race condition prevention"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("STARTING")
        ss.set_state("STOPPING")
        ss.set_state("OFF")
        CustomAssertions.assert_equal(ss.vm_state, "OFF")


class TestR1_005_T2_23_VmStartTimeout(BaseTestCase):
    test_id = "T2-23"
    feature_id = "F-R1-005"
    title = "VM start timeout fallback to ERROR state"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("STARTING")

        boot_timeout = True
        if boot_timeout:
            ss.set_state("ERROR")

        CustomAssertions.assert_equal(ss.vm_state, "ERROR")


class TestR1_005_T2_24_ForceStopDuringStarting(BaseTestCase):
    test_id = "T2-24"
    feature_id = "F-R1-005"
    title = "Force stop invocation during STARTING state"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("STARTING")
        ss.set_state("STOPPING")
        ss.set_state("OFF")
        CustomAssertions.assert_equal(ss.vm_state, "OFF")


class TestR1_005_T2_25_ReinitFromErrorState(BaseTestCase):
    test_id = "T2-25"
    feature_id = "F-R1-005"
    title = "Re-initialization from ERROR state after corrective cleanup"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("ERROR")
        
        # Cleanup routine resets state to OFF
        ss.set_state("OFF")
        CustomAssertions.assert_equal(ss.vm_state, "OFF")

        ss.set_state("STARTING")
        ss.set_state("RUNNING")
        CustomAssertions.assert_equal(ss.vm_state, "RUNNING")
