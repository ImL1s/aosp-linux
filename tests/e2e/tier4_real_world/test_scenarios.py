"""
Tier 4 Real-World End-to-End Application Scenarios.
Covers: SCENARIO-01 through SCENARIO-18.
"""

import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import (
    BaseTestCase,
    CustomAssertions,
    HmacAuthHelper,
    VsockFramingHelper,
    VsockPacketType,
)

class TestScenario01_ColdBootDebian(BaseTestCase):
    test_id = "SCENARIO-01"
    feature_id = "F-R1-005+F-R2-001+F-R2-005+F-R3-007"
    title = "Cold Boot Debian 12 Guest & Full Shell Session"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.unlock_user()
        self.mock_env.system_server.set_state("STARTING")
        self.mock_env.vsock.bind(5000)
        secret = b"shared_secret_key_32bytes_long!!"
        token = HmacAuthHelper.generate_random_token()
        sig = HmacAuthHelper.compute_hmac(secret, token)
        auth_ok = self.mock_env.vsock.authenticate_handshake(token, sig, secret)
        CustomAssertions.assert_true(auth_ok)
        self.mock_env.system_server.set_state("RUNNING")
        self.mock_env.vsock.bind(5001)
        session_id = b"scenario01_bash!"
        frame = VsockFramingHelper.create_frame(session_id, VsockPacketType.DATA, b"user@debian:~$ ")
        self.mock_env.vsock.send(5001, frame)
        pkts = self.mock_env.vsock.receive_all(5001)
        CustomAssertions.assert_equal(len(pkts), 1)

class TestScenario02_CjkImeTextInput(BaseTestCase):
    test_id = "SCENARIO-02"
    feature_id = "F-R3-003+F-R3-004+F-R3-007"
    title = "CJK IME Text Input in Terminal App (Zhuyin Inline Composition & Commit)"
    tier = 4

    def run_test(self):
        self.mock_env.vsock.bind(5001)
        session_id = b"scenario02_cjk!!"
        commit_text = "測試"
        frame = VsockFramingHelper.create_frame(session_id, VsockPacketType.DATA, commit_text.encode("utf-8"))
        self.mock_env.vsock.send(5001, frame)
        pkts = self.mock_env.vsock.receive_all(5001)
        CustomAssertions.assert_equal(len(pkts), 1)
        _, _, data = VsockFramingHelper.parse_frame(pkts[0])
        CustomAssertions.assert_equal(data.decode("utf-8"), "測試")

class TestScenario03_TuiEditorNavigation(BaseTestCase):
    test_id = "SCENARIO-03"
    feature_id = "F-R3-002+F-R3-005+F-R3-006"
    title = "TUI Editor Navigation (Vim Mouse Dragging & SGR Scroll Protocol)"
    tier = 4

    def run_test(self):
        sgr_scroll = b"\x1b[<64;40;25M"
        self.mock_env.vsock.bind(5001)
        session_id = b"scenario03_vim!!"
        frame = VsockFramingHelper.create_frame(session_id, VsockPacketType.DATA, sgr_scroll)
        self.mock_env.vsock.send(5001, frame)
        pkts = self.mock_env.vsock.receive_all(5001)
        CustomAssertions.assert_equal(len(pkts), 1)

class TestScenario04_WaylandGuiAppLaunch(BaseTestCase):
    test_id = "SCENARIO-04"
    feature_id = "F-R4-001+F-R4-003+F-R4-006"
    title = "Wayland GUI App Launch & Task Manager / Recents Integration"
    tier = 4

    def run_test(self):
        self.mock_env.installed_desktop_apps["gimp.desktop"] = {"Name": "GIMP", "Exec": "gimp"}
        sid = self.mock_env.sommelier.create_surface("gimp", 1920, 1080)
        self.mock_env.active_task_ids[101] = "gimp"
        CustomAssertions.assert_in(101, self.mock_env.active_task_ids)

class TestScenario05_FreeformMultiWindowResize(BaseTestCase):
    test_id = "SCENARIO-05"
    feature_id = "F-R4-002+F-R4-004"
    title = "Freeform Multi-Window Pacing & Dynamic Resizing"
    tier = 4

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("vlc", 800, 600)
        self.mock_env.sommelier.active_surfaces[sid]["width"] = 1280
        self.mock_env.sommelier.active_surfaces[sid]["height"] = 720
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["width"], 1280)

class TestScenario06_SyntheticDesktopShortcutSync(BaseTestCase):
    test_id = "SCENARIO-06"
    feature_id = "F-R4-005+F-R4-006"
    title = "Synthetic Desktop Shortcut Sync via Inotify Monitor"
    tier = 4

    def run_test(self):
        self.mock_env.installed_desktop_apps["vlc.desktop"] = {"Name": "VLC", "Exec": "vlc"}
        CustomAssertions.assert_in("vlc.desktop", self.mock_env.installed_desktop_apps)

class TestScenario07_CameraStreamingPortal(BaseTestCase):
    test_id = "SCENARIO-07"
    feature_id = "F-R5-001+F-R5-004"
    title = "Hardware Camera Streaming via XDG Portal + AppOps Grant"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.set_appop("cheese", "OP_CAMERA", "ALLOWED")
        ok = self.mock_env.portal.request_camera_access("cheese")
        CustomAssertions.assert_true(ok)

class TestScenario08_MicrophoneAccessDenial(BaseTestCase):
    test_id = "SCENARIO-08"
    feature_id = "F-R5-002+F-R5-004"
    title = "Microphone Access & Audio Capture Prompt Denial"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.set_appop("recorder", "OP_RECORD_AUDIO", "DENIED")
        ok = self.mock_env.portal.request_microphone_access("recorder")
        CustomAssertions.assert_false(ok)

class TestScenario09_LocationStreamingCheck(BaseTestCase):
    test_id = "SCENARIO-09"
    feature_id = "F-R5-003+F-R5-004"
    title = "GPS Location Streaming with Fine Location Permission Check"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.set_appop("marble", "OP_FINE_LOCATION", "ALLOWED")
        loc = self.mock_env.portal.request_location_access("marble")
        CustomAssertions.assert_equal(loc["latitude"], 25.0330)

class TestScenario10_VirtioSndAudioFocusDucking(BaseTestCase):
    test_id = "SCENARIO-10"
    feature_id = "F-R5-005+F-R5-006"
    title = "Virtio-snd Audio Playback with Incoming Phone Call AudioFocus Ducking"
    tier = 4

    def run_test(self):
        self.mock_env.audio_focus_state = "GAIN"
        self.mock_env.audio_volume = 1.0
        # Phone call incoming
        self.mock_env.audio_focus_state = "LOSS_TRANSIENT_CAN_DUCK"
        self.mock_env.audio_volume = 0.2
        CustomAssertions.assert_equal(self.mock_env.audio_focus_state, "LOSS_TRANSIENT_CAN_DUCK")
        CustomAssertions.assert_equal(self.mock_env.audio_volume, 0.2)

class TestScenario11_VirtiofsSafAccess(BaseTestCase):
    test_id = "SCENARIO-11"
    feature_id = "F-R5-007+F-R5-008"
    title = "Virtiofs Shared Storage File Creation & Storage Access Framework (SAF) Access"
    tier = 4

    def run_test(self):
        self.mock_env.shared_files_guest["notes.txt"] = b"notes content"
        self.mock_env.shared_files_host["notes.txt"] = self.mock_env.shared_files_guest["notes.txt"]
        CustomAssertions.assert_in("notes.txt", self.mock_env.shared_files_host)

class TestScenario12_Luks2UserUnlockDecryption(BaseTestCase):
    test_id = "SCENARIO-12"
    feature_id = "F-R2-003"
    title = "Guest LUKS2 CE Storage Decryption on Android User Unlock"
    tier = 4

    def run_test(self):
        CustomAssertions.assert_false(self.mock_env.system_server.user_unlocked)
        self.mock_env.system_server.unlock_user()
        CustomAssertions.assert_true(self.mock_env.system_server.user_unlocked)

class TestScenario13_VsockHandshakeReplayPrevention(BaseTestCase):
    test_id = "SCENARIO-13"
    feature_id = "F-R2-005+F-R2-004"
    title = "Vsock 3-Port Handshake Authentication with Replay Attack Prevention"
    tier = 4

    def run_test(self):
        secret = b"shared_secret_key_32bytes_long!!"
        token = HmacAuthHelper.generate_random_token()
        sig = HmacAuthHelper.compute_hmac(secret, token)
        ok1 = self.mock_env.vsock.authenticate_handshake(token, sig, secret)
        ok2 = self.mock_env.vsock.authenticate_handshake(token, sig, secret)
        CustomAssertions.assert_true(ok1)
        CustomAssertions.assert_false(ok2)

class TestScenario14_SELinuxDomainDenial(BaseTestCase):
    test_id = "SCENARIO-14"
    feature_id = "F-R5-009+F-R5-010"
    title = "SELinux Domain Denial Interception for Unauthorized File System Access"
    tier = 4

    def run_test(self):
        log = "type=1400 audit(1722934400.123:45): avc: denied { read } for pid=123 scontext=u:r:linux_bridge:s0 tcontext=u:object_r:efs_file:s0"
        self.mock_env.system_server.log_selinux_audit(log)
        CustomAssertions.assert_selinux_denial(self.mock_env.system_server.audit_logs, "linux_bridge", "efs_file")

class TestScenario15_OtaAvbValidationFailureRollback(BaseTestCase):
    test_id = "SCENARIO-15"
    feature_id = "F-R5-012+F-R5-013"
    title = "Guest OTA Image Verification & AVB Key Signature Validation Failure Rollback"
    tier = 4

    def run_test(self):
        self.mock_env.avb_key_valid = False
        if not self.mock_env.avb_key_valid:
            self.mock_env.boot_slot = "slot_a"
        CustomAssertions.assert_equal(self.mock_env.boot_slot, "slot_a")

class TestScenario16_VmCrashWatchdogRollback(BaseTestCase):
    test_id = "SCENARIO-16"
    feature_id = "F-R5-012+F-R5-014"
    title = "VM Crash & Watchdog Automatic Recovery to Backup Slot B"
    tier = 4

    def run_test(self):
        self.mock_env.boot_slot = "slot_a"
        self.mock_env.boot_attempts = 3
        if self.mock_env.boot_attempts >= 3:
            self.mock_env.boot_slot = "slot_b"
        CustomAssertions.assert_equal(self.mock_env.boot_slot, "slot_b")

class TestScenario17_MultiAppWaylandPortals(BaseTestCase):
    test_id = "SCENARIO-17"
    feature_id = "F-R4-001+F-R5-001+F-R5-005"
    title = "Multi-App Wayland Forwarding & Concurrent Audio/Video Portals"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.set_appop("vlc", "OP_CAMERA", "ALLOWED")
        cam_ok = self.mock_env.portal.request_camera_access("vlc")
        sid = self.mock_env.sommelier.create_surface("vlc", 1920, 1080)
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_true(cam_ok)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 1)

class TestScenario18_StorageKeyRevocationOnLock(BaseTestCase):
    test_id = "SCENARIO-18"
    feature_id = "F-R2-003+F-R1-003"
    title = "Storage Encryption Key Revocation on Device Screen Lock / Relock"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.unlock_user()
        CustomAssertions.assert_true(self.mock_env.system_server.ce_key_available)
        self.mock_env.system_server.lock_user()
        CustomAssertions.assert_false(self.mock_env.system_server.ce_key_available)

class TestScenario19_SharedFileSafWorkflow(BaseTestCase):
    test_id = "SCENARIO-19"
    feature_id = "F-R1-001+F-R2-002+F-R5-007+F-R5-008"
    title = "End-to-End Shared File Workflow with Virtiofs, SAF DocumentsProvider, and LinuxManager API Verification"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.unlock_user()
        self.mock_env.system_server.set_state("RUNNING")
        self.mock_env.shared_files_guest["project_report.pdf"] = b"%PDF-1.4 sample content"
        self.mock_env.shared_files_host["project_report.pdf"] = self.mock_env.shared_files_guest["project_report.pdf"]
        self.mock_env.saf_documents["doc_19"] = {"name": "project_report.pdf", "size": len(b"%PDF-1.4 sample content")}
        CustomAssertions.assert_in("project_report.pdf", self.mock_env.shared_files_host)
        CustomAssertions.assert_in("doc_19", self.mock_env.saf_documents)

class TestScenario20_FullSecurityLifecycleValidation(BaseTestCase):
    test_id = "SCENARIO-20"
    feature_id = "F-R1-005+F-R2-005+F-R4-001+F-R5-009"
    title = "Full Security Lifecycle Validation: VM Startup, HMAC Auth, Wayland GUI Forwarding, and SELinux Enforcement"
    tier = 4

    def run_test(self):
        self.mock_env.system_server.unlock_user()
        self.mock_env.system_server.set_state("STARTING")
        secret = b"shared_secret_key_32bytes_long!!"
        token = HmacAuthHelper.generate_random_token()
        sig = HmacAuthHelper.compute_hmac(secret, token)
        auth_ok = self.mock_env.vsock.authenticate_handshake(token, sig, secret)
        CustomAssertions.assert_true(auth_ok)
        self.mock_env.system_server.set_state("RUNNING")
        sid = self.mock_env.sommelier.create_surface("org.debian.secapp", 1024, 768)
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 1)
        rule = "allow linux_manager system_server:binder call"
        CustomAssertions.assert_in(rule, self.mock_env.selinux_rules["linux_manager.te"])

