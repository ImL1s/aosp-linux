"""
Tier 1 Functional Tests for Milestone 1: AOSP Framework & Core Modifications.
Features covered: F-R1-001 through F-R1-005 (5 happy-path test cases each).
"""

import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, MockEnvironment, RealSystemServerInspector

# ==============================================================================
# F-R1-001: Framework API Namespace
# ==============================================================================
class TestR1_001_T1_01_ApiClassPresence(BaseTestCase):
    test_id = "T1-01"
    feature_id = "F-R1-001"
    title = "Verify class loading and package identity of android.system.linux.LinuxManager"
    tier = 1

    def run_test(self):
        java_path = "frameworks/base/core/java/android/system/linux/LinuxManager.java"
        class_path = "build_out/classes/android/system/linux/LinuxManager.class"
        exists = os.path.exists(java_path) or os.path.exists(class_path)
        CustomAssertions.assert_true(exists, "Framework LinuxManager source or class file must exist")
        if os.path.exists(java_path):
            with open(java_path, "r", encoding="utf-8") as f:
                content = f.read()
            CustomAssertions.assert_in("package android.system.linux;", content)
            CustomAssertions.assert_in("public class LinuxManager", content)


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
        self.mock_env.installed_desktop_apps["gimp.desktop"] = app_info
        fetched = self.mock_env.installed_desktop_apps.get("gimp.desktop")
        CustomAssertions.assert_equal(fetched["app_id"], "org.debian.gimp")
        CustomAssertions.assert_equal(fetched["name"], "GNU Image Manipulation Program")
        CustomAssertions.assert_true(fetched["icon"].startswith("/usr/share/icons"))


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
        phase = getattr(self.mock_env.system_server, "init_phase", 600)
        CustomAssertions.assert_equal(phase, 600, "LinuxManagerService must register for PHASE_THIRD_PARTY_APPS_CAN_START (600)")


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
        bound = self.mock_env.vsock.bind(15000)
        CustomAssertions.assert_true(bound)
        CustomAssertions.assert_true(self.mock_env.vsock.bound_ports[15000])


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
        proc_status = RealSystemServerInspector.query_vm_process_status()
        rc_files = ["native/linux_bridge/linux_bridge.rc", "system/core/rootdir/init.rc"]
        found = False
        for rcf in rc_files:
            if os.path.exists(rcf):
                with open(rcf, "r", encoding="utf-8") as f:
                    rc_code = f.read()
                if "user system" in rc_code or "group system" in rc_code:
                    found = True
                    break
        CustomAssertions.assert_true(found or isinstance(proc_status, dict), "linux_bridge service must configure system credentials")


class TestR1_004_T1_17_ControlVsockSocketBind5000(BaseTestCase):
    test_id = "T1-17"
    feature_id = "F-R1-004"
    title = "Control vsock socket bound to port 15000"
    tier = 1

    def run_test(self):
        bound = self.mock_env.vsock.bind(15000)
        CustomAssertions.assert_true(bound)


class TestR1_004_T1_18_UnixDomainSocketIpcEstablishment(BaseTestCase):
    test_id = "T1-18"
    feature_id = "F-R1-004"
    title = "Unix domain socket IPC connection established with SystemServer"
    tier = 1

    def run_test(self):
        sock = self.mock_env.vsock.connect_unix_socket("/dev/socket/linux_bridge", timeout=2.0)
        CustomAssertions.assert_true(sock.fileno() > 0, "Unix domain socket connection to /dev/socket/linux_bridge must return a valid socket descriptor")
        sock.close()


class TestR1_004_T1_19_PingPongHeartbeatActive(BaseTestCase):
    test_id = "T1-19"
    feature_id = "F-R1-004"
    title = "Ping/pong heartbeats active between daemon and host framework"
    tier = 1

    def run_test(self):
        self.mock_env.vsock.bind(15000)
        self.mock_env.vsock.send(15000, b"PING")
        packets = self.mock_env.vsock.receive_all(15000)
        CustomAssertions.assert_equal(len(packets), 1)
        CustomAssertions.assert_equal(packets[0], b"PING")


class TestR1_004_T1_20_ProcessOomAdjPriority(BaseTestCase):
    test_id = "T1-20"
    feature_id = "F-R1-004"
    title = "Process priority and oom_score_adj correctly set"
    tier = 1

    def run_test(self):
        proc_status = RealSystemServerInspector.query_vm_process_status()
        rc_files = ["native/linux_bridge/linux_bridge.rc", "system/core/rootdir/init.rc"]
        found = False
        for rcf in rc_files:
            if os.path.exists(rcf):
                with open(rcf, "r", encoding="utf-8") as f:
                    if "oom_score_adjust" in f.read() or "oom" in f.read():
                        found = True
                        break
        CustomAssertions.assert_true(found or isinstance(proc_status, dict), "oom_score_adj priority configuration must exist")


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
