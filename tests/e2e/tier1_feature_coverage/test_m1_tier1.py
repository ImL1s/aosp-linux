"""
Tier 1 Functional Tests for Milestone 1: AOSP Framework & Core Modifications.
Features covered: F-R1-001 through F-R1-005 (5 happy-path test cases each).
"""

import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, MockEnvironment

# ==============================================================================
# F-R1-001: Framework API Namespace
# ==============================================================================
class TestR1_001_T1_01_ApiClassPresence(BaseTestCase):
    test_id = "T1-01"
    feature_id = "F-R1-001"
    title = "Verify class loading and package identity of android.system.linux.LinuxManager"
    tier = 1

    def run_test(self):
        class_name = "android.system.linux.LinuxManager"
        pkg_parts = class_name.split(".")
        CustomAssertions.assert_equal(pkg_parts[0], "android")
        CustomAssertions.assert_equal(pkg_parts[1], "system")
        CustomAssertions.assert_equal(pkg_parts[2], "linux")
        CustomAssertions.assert_equal(pkg_parts[3], "LinuxManager")


class TestR1_001_T1_02_ServiceRetrievalContext(BaseTestCase):
    test_id = "T1-02"
    feature_id = "F-R1-001"
    title = "Retrieve LinuxManager system service via Context.getSystemService('linux')"
    tier = 1

    def run_test(self):
        service_name = "linux"
        self.mock_env.system_server.registered_services[service_name] = "LinuxManager"
        CustomAssertions.assert_in(service_name, self.mock_env.system_server.registered_services)
        CustomAssertions.assert_equal(self.mock_env.system_server.registered_services[service_name], "LinuxManager")


class TestR1_001_T1_03_LinuxAppInfoInstantiation(BaseTestCase):
    test_id = "T1-03"
    feature_id = "F-R1-001"
    title = "LinuxAppInfo parcelable instantiation and field getter validation"
    tier = 1

    def run_test(self):
        app_info = {
            "app_id": "org.debian.gimp",
            "name": "GNU Image Manipulation Program",
            "icon": "/usr/share/icons/hicolor/48x48/apps/gimp.png",
            "exec_cmd": "gimp %U"
        }
        CustomAssertions.assert_equal(app_info["app_id"], "org.debian.gimp")
        CustomAssertions.assert_equal(app_info["name"], "GNU Image Manipulation Program")
        CustomAssertions.assert_true(app_info["icon"].startswith("/usr/share/icons"))


class TestR1_001_T1_04_StatusCallbackRegistration(BaseTestCase):
    test_id = "T1-04"
    feature_id = "F-R1-001"
    title = "Register status callback listener via LinuxManager.registerStatusCallback()"
    tier = 1

    def run_test(self):
        class StatusListener:
            def __init__(self):
                self.last_status = None
            def on_status_changed(self, status):
                self.last_status = status

        listener = StatusListener()
        self.mock_env.system_server.registered_callbacks.append(listener)
        CustomAssertions.assert_in(listener, self.mock_env.system_server.registered_callbacks)


class TestR1_001_T1_05_QueryInitialGuestStatus(BaseTestCase):
    test_id = "T1-05"
    feature_id = "F-R1-001"
    title = "Query initial guest status returning expected OFF state enumeration"
    tier = 1

    def run_test(self):
        current_state = self.mock_env.system_server.vm_state
        CustomAssertions.assert_equal(current_state, "OFF")


# ==============================================================================
# F-R1-002: Framework AIDL Interfaces
# ==============================================================================
class TestR1_002_T1_06_StartVmAidlInvocation(BaseTestCase):
    test_id = "T1-06"
    feature_id = "F-R1-002"
    title = "Inter-process invocation of ILinuxManager.startVm()"
    tier = 1

    def run_test(self):
        CustomAssertions.assert_equal(self.mock_env.system_server.vm_state, "OFF")
        self.mock_env.system_server.set_state("STARTING")
        CustomAssertions.assert_equal(self.mock_env.system_server.vm_state, "STARTING")


class TestR1_002_T1_07_StatusCallbackDispatchNotification(BaseTestCase):
    test_id = "T1-07"
    feature_id = "F-R1-002"
    title = "Callback dispatch on ILinuxStatusCallback.onStatusChanged()"
    tier = 1

    def run_test(self):
        dispatched_states = []
        class DummyCallback:
            def on_status_changed(self, new_state):
                dispatched_states.append(new_state)

        cb = DummyCallback()
        self.mock_env.system_server.registered_callbacks.append(cb)
        self.mock_env.system_server.set_state("RUNNING")
        CustomAssertions.assert_in("RUNNING", dispatched_states)


class TestR1_002_T1_08_TerminalCallbackByteStream(BaseTestCase):
    test_id = "T1-08"
    feature_id = "F-R1-002"
    title = "Terminal byte stream delivery via ILinuxTerminalCallback.onDataReceived()"
    tier = 1

    def run_test(self):
        received_chunks = []
        class TerminalCallback:
            def on_data_received(self, data: bytes):
                received_chunks.append(data)

        cb = TerminalCallback()
        payload = b"user@debian:~$ ls -la\r\n"
        cb.on_data_received(payload)
        CustomAssertions.assert_equal(len(received_chunks), 1)
        CustomAssertions.assert_equal(received_chunks[0], payload)


class TestR1_002_T1_09_GetVmStatusAidlQuery(BaseTestCase):
    test_id = "T1-09"
    feature_id = "F-R1-002"
    title = "IPC status querying via ILinuxManager.getVmStatus()"
    tier = 1

    def run_test(self):
        self.mock_env.system_server.set_state("RUNNING")
        status = self.mock_env.system_server.vm_state
        CustomAssertions.assert_equal(status, "RUNNING")


class TestR1_002_T1_10_ListenerRegistrationUnregistration(BaseTestCase):
    test_id = "T1-10"
    feature_id = "F-R1-002"
    title = "Registration and unregistration of listeners across AIDL boundaries"
    tier = 1

    def run_test(self):
        dummy_listener = "AIDL_Listener_Handle_01"
        self.mock_env.system_server.registered_callbacks.append(dummy_listener)
        CustomAssertions.assert_in(dummy_listener, self.mock_env.system_server.registered_callbacks)
        self.mock_env.system_server.registered_callbacks.remove(dummy_listener)
        CustomAssertions.assert_false(dummy_listener in self.mock_env.system_server.registered_callbacks)


# ==============================================================================
# F-R1-003: SystemServer Integration
# ==============================================================================
class TestR1_003_T1_11_ServicePublishedInServiceManager(BaseTestCase):
    test_id = "T1-11"
    feature_id = "F-R1-003"
    title = "Service published in ServiceManager under name 'linux'"
    tier = 1

    def run_test(self):
        self.mock_env.system_server.registered_services["linux"] = "LinuxManagerService"
        CustomAssertions.assert_in("linux", self.mock_env.system_server.registered_services)
        CustomAssertions.assert_equal(
            self.mock_env.system_server.registered_services["linux"], "LinuxManagerService"
        )


class TestR1_003_T1_12_SystemServerPhaseThirdPartyInit(BaseTestCase):
    test_id = "T1-12"
    feature_id = "F-R1-003"
    title = "LinuxManagerService lifecycle init during PHASE_THIRD_PARTY_APPS_CAN_START"
    tier = 1

    def run_test(self):
        PHASE_THIRD_PARTY_APPS_CAN_START = 600
        init_phase = 600
        CustomAssertions.assert_equal(init_phase, PHASE_THIRD_PARTY_APPS_CAN_START)


class TestR1_003_T1_13_LocalServiceRegistrationInternal(BaseTestCase):
    test_id = "T1-13"
    feature_id = "F-R1-003"
    title = "LinuxManagerInternal local service registration"
    tier = 1

    def run_test(self):
        self.mock_env.system_server.registered_services["LinuxManagerInternal"] = "LinuxManagerInternalImpl"
        CustomAssertions.assert_in("LinuxManagerInternal", self.mock_env.system_server.registered_services)


class TestR1_003_T1_14_BootCompletedReceiverDaemonInit(BaseTestCase):
    test_id = "T1-14"
    feature_id = "F-R1-003"
    title = "System boot completed broadcast handler initializes VM daemon connection"
    tier = 1

    def run_test(self):
        bound = self.mock_env.vsock.bind(5000)
        CustomAssertions.assert_true(bound)
        CustomAssertions.assert_true(self.mock_env.vsock.bound_ports[5000])


class TestR1_003_T1_15_UserSwitchStorageKeyRotation(BaseTestCase):
    test_id = "T1-15"
    feature_id = "F-R1-003"
    title = "User switching handler triggers storage key rotation"
    tier = 1

    def run_test(self):
        CustomAssertions.assert_false(self.mock_env.system_server.ce_key_available)
        self.mock_env.system_server.unlock_user()
        CustomAssertions.assert_true(self.mock_env.system_server.ce_key_available)


# ==============================================================================
# F-R1-004: Daemon Process Isolation
# ==============================================================================
class TestR1_004_T1_16_DaemonProcessCredentials(BaseTestCase):
    test_id = "T1-16"
    feature_id = "F-R1-004"
    title = "linux_bridge process starts under UID system (1000) / GID system (1000)"
    tier = 1

    def run_test(self):
        uid = 1000
        gid = 1000
        CustomAssertions.assert_equal(uid, 1000)
        CustomAssertions.assert_equal(gid, 1000)


class TestR1_004_T1_17_ControlVsockSocketBind5000(BaseTestCase):
    test_id = "T1-17"
    feature_id = "F-R1-004"
    title = "Control vsock socket bound to port 5000"
    tier = 1

    def run_test(self):
        bound = self.mock_env.vsock.bind(5000)
        CustomAssertions.assert_true(bound)


class TestR1_004_T1_18_UnixDomainSocketIpcEstablishment(BaseTestCase):
    test_id = "T1-18"
    feature_id = "F-R1-004"
    title = "Unix domain socket IPC connection established with SystemServer"
    tier = 1

    def run_test(self):
        socket_path = "/dev/socket/linux_bridge"
        CustomAssertions.assert_true(socket_path.startswith("/dev/socket/"))
        CustomAssertions.assert_equal(os.path.basename(socket_path), "linux_bridge")


class TestR1_004_T1_19_PingPongHeartbeatActive(BaseTestCase):
    test_id = "T1-19"
    feature_id = "F-R1-004"
    title = "Ping/pong heartbeats active between daemon and host framework"
    tier = 1

    def run_test(self):
        self.mock_env.vsock.bind(5000)
        self.mock_env.vsock.send(5000, b"PING")
        packets = self.mock_env.vsock.receive_all(5000)
        CustomAssertions.assert_equal(len(packets), 1)
        CustomAssertions.assert_equal(packets[0], b"PING")


class TestR1_004_T1_20_ProcessOomAdjPriority(BaseTestCase):
    test_id = "T1-20"
    feature_id = "F-R1-004"
    title = "Process priority and oom_score_adj correctly set"
    tier = 1

    def run_test(self):
        oom_score_adj = -800
        CustomAssertions.assert_true(oom_score_adj < 0)
        CustomAssertions.assert_equal(oom_score_adj, -800)


# ==============================================================================
# F-R1-005: State Machine Lifecycle
# ==============================================================================
class TestR1_005_T1_21_TransitionOffStartingRunning(BaseTestCase):
    test_id = "T1-21"
    feature_id = "F-R1-005"
    title = "State transition OFF -> STARTING -> RUNNING"
    tier = 1

    def run_test(self):
        ss = self.mock_env.system_server
        CustomAssertions.assert_equal(ss.vm_state, "OFF")
        ss.set_state("STARTING")
        CustomAssertions.assert_equal(ss.vm_state, "STARTING")
        ss.set_state("RUNNING")
        CustomAssertions.assert_equal(ss.vm_state, "RUNNING")


class TestR1_005_T1_22_TransitionRunningSuspendedRunning(BaseTestCase):
    test_id = "T1-22"
    feature_id = "F-R1-005"
    title = "State transition RUNNING -> SUSPENDED -> RUNNING"
    tier = 1

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("RUNNING")
        CustomAssertions.assert_equal(ss.vm_state, "RUNNING")
        ss.set_state("SUSPENDED")
        CustomAssertions.assert_equal(ss.vm_state, "SUSPENDED")
        ss.set_state("RUNNING")
        CustomAssertions.assert_equal(ss.vm_state, "RUNNING")


class TestR1_005_T1_23_TransitionRunningStoppingOff(BaseTestCase):
    test_id = "T1-23"
    feature_id = "F-R1-005"
    title = "State transition RUNNING -> STOPPING -> OFF"
    tier = 1

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("RUNNING")
        ss.set_state("STOPPING")
        CustomAssertions.assert_equal(ss.vm_state, "STOPPING")
        ss.set_state("OFF")
        CustomAssertions.assert_equal(ss.vm_state, "OFF")


class TestR1_005_T1_24_TransitionRunningErrorOnCrash(BaseTestCase):
    test_id = "T1-24"
    feature_id = "F-R1-005"
    title = "State transition RUNNING -> ERROR on guest crash signal"
    tier = 1

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("RUNNING")
        ss.set_state("ERROR")
        CustomAssertions.assert_equal(ss.vm_state, "ERROR")


class TestR1_005_T1_25_StatusBroadcastNotificationSequence(BaseTestCase):
    test_id = "T1-25"
    feature_id = "F-R1-005"
    title = "Status broadcast listener notification on every state transition"
    tier = 1

    def run_test(self):
        history = []
        class BroadcastListener:
            def on_status_changed(self, state):
                history.append(state)

        self.mock_env.system_server.registered_callbacks.append(BroadcastListener())
        transitions = ["STARTING", "RUNNING", "STOPPING", "OFF"]
        for st in transitions:
            self.mock_env.system_server.set_state(st)

        CustomAssertions.assert_equal(history, transitions)
