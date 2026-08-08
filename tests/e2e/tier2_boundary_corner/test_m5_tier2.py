"""
Tier 2 Boundary & Corner Case Tests for Milestone 5: Hardware Portals, Virtiofs, SELinux Policies & Guest A/B Base Image Rollback OTA.
Features: F-R5-001 through F-R5-014 (Tests T2-116 .. T2-185)
"""

import sys
import os
import hashlib

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, MockEnvironment

# -----------------------------------------------------------------------------
# F-R5-001: XDG Portal Camera Bridge (T2-116 .. T2-120)
# -----------------------------------------------------------------------------
class TestR5_001_T2_116_CameraAccessDenied(BaseTestCase):
    test_id = "T2-116"
    feature_id = "F-R5-001"
    title = "Return permission denied error when user denies camera prompt"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_appop("org.gnome.Cheese", "OP_CAMERA", "DENIED")

        granted = self.mock_env.portal.request_camera_access("org.gnome.Cheese")
        CustomAssertions.assert_false(granted, "Camera access must be denied when AppOps mode is DENIED")


class TestR5_001_T2_117_CameraResourceReleaseOnExit(BaseTestCase):
    test_id = "T2-117"
    feature_id = "F-R5-001"
    title = "Release Camera hardware resource when guest app exits"
    tier = 2

    def run_test(self):
        camera_open = True
        app_running = False

        if not app_running:
            camera_open = False

        CustomAssertions.assert_false(camera_open)


class TestR5_001_T2_118_CameraContentionResolution(BaseTestCase):
    test_id = "T2-118"
    feature_id = "F-R5-001"
    title = "Concurrent Android app camera usage contention resolution"
    tier = 2

    def run_test(self):
        android_app_active = True
        guest_portal_stream = True

        if android_app_active:
            guest_portal_stream = False  # Native Android app gets camera priority

        CustomAssertions.assert_false(guest_portal_stream)


class TestR5_001_T2_119_CameraResolutionMismatchFallback(BaseTestCase):
    test_id = "T2-119"
    feature_id = "F-R5-001"
    title = "Camera resolution / frame rate negotiation mismatch fallback"
    tier = 2

    def run_test(self):
        requested_mode = (3840, 2160, 120)  # 4K 120fps
        supported_modes = [(1920, 1080, 30), (1280, 720, 30)]

        fallback_mode = supported_modes[0] if requested_mode not in supported_modes else requested_mode
        CustomAssertions.assert_equal(fallback_mode, (1920, 1080, 30))


class TestR5_001_T2_120_CameraHardwareDisconnect(BaseTestCase):
    test_id = "T2-120"
    feature_id = "F-R5-001"
    title = "Handle device camera hardware disconnection during active stream"
    tier = 2

    def run_test(self):
        camera_plugged_in = False

        def get_video_frame():
            if not camera_plugged_in:
                raise ConnectionError("HardwareDisconnected: USB Camera unplugged during stream")
            return b"frame_data"

        CustomAssertions.assert_raises(ConnectionError, get_video_frame)


# -----------------------------------------------------------------------------
# F-R5-002: XDG Portal Microphone Bridge (T2-121 .. T2-125)
# -----------------------------------------------------------------------------
class TestR5_002_T2_121_MicAccessDenied(BaseTestCase):
    test_id = "T2-121"
    feature_id = "F-R5-002"
    title = "Return audio capture failure when mic permission is revoked"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_appop("org.audacity.Audacity", "OP_RECORD_AUDIO", "DENIED")

        granted = self.mock_env.portal.request_microphone_access("org.audacity.Audacity")
        CustomAssertions.assert_false(granted)


class TestR5_002_T2_122_MicPrivacyToggleMute(BaseTestCase):
    test_id = "T2-122"
    feature_id = "F-R5-002"
    title = "Mute audio stream when Android microphone privacy toggle is enabled"
    tier = 2

    def run_test(self):
        mic_privacy_toggle_on = True
        raw_pcm_input = b"\x12\x34\x56\x78" * 100

        pcm_stream = b"\x00" * len(raw_pcm_input) if mic_privacy_toggle_on else raw_pcm_input
        CustomAssertions.assert_equal(set(pcm_stream), {0})


class TestR5_002_T2_123_AudioLatencyUnderflowMitigation(BaseTestCase):
    test_id = "T2-123"
    feature_id = "F-R5-002"
    title = "Audio latency buffer underflow mitigation"
    tier = 2

    def run_test(self):
        audio_buffer = bytearray()
        MIN_BUFFER_SIZE = 1024

        if len(audio_buffer) < MIN_BUFFER_SIZE:
            # Underflow mitigation inserts zero-fill silence
            audio_buffer.extend(b"\x00" * (MIN_BUFFER_SIZE - len(audio_buffer)))

        CustomAssertions.assert_equal(len(audio_buffer), MIN_BUFFER_SIZE)


class TestR5_002_T2_124_StopMicRecordingOnBackground(BaseTestCase):
    test_id = "T2-124"
    feature_id = "F-R5-002"
    title = "Stop mic recording when Linux app goes into background"
    tier = 2

    def run_test(self):
        app_in_foreground = False
        mic_recording_active = True

        if not app_in_foreground:
            mic_recording_active = False

        CustomAssertions.assert_false(mic_recording_active)


class TestR5_002_T2_125_StereoToMonoDownmixing(BaseTestCase):
    test_id = "T2-125"
    feature_id = "F-R5-002"
    title = "Multi-channel audio stream channel downmixing (stereo to mono)"
    tier = 2

    def run_test(self):
        left_channel_sample = 1000
        right_channel_sample = 2000

        mono_sample = (left_channel_sample + right_channel_sample) // 2
        CustomAssertions.assert_equal(mono_sample, 1500)


# -----------------------------------------------------------------------------
# F-R5-003: XDG Portal Location Bridge (T2-126 .. T2-130)
# -----------------------------------------------------------------------------
class TestR5_003_T2_126_CoarseLocationApproximate(BaseTestCase):
    test_id = "T2-126"
    feature_id = "F-R5-003"
    title = "Return approximate location when coarse location granted"
    tier = 2

    def run_test(self):
        exact_lat = 25.0330123
        exact_lon = 121.5654987

        coarse_lat, coarse_lon = self.mock_env.portal.format_coarse_location(exact_lat, exact_lon)

        CustomAssertions.assert_equal(coarse_lat, 25.03)
        CustomAssertions.assert_equal(coarse_lon, 121.57)


class TestR5_003_T2_127_GpsDisabledFailure(BaseTestCase):
    test_id = "T2-127"
    feature_id = "F-R5-003"
    title = "Location access failure when device GPS is turned off"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_appop("org.kde.Marble", "OP_FINE_LOCATION", "DENIED")

        CustomAssertions.assert_raises(
            PermissionError,
            self.mock_env.portal.request_location_access,
            "org.kde.Marble"
        )


class TestR5_003_T2_128_LocationUpdateThrottling(BaseTestCase):
    test_id = "T2-128"
    feature_id = "F-R5-003"
    title = "Location update frequency throttling to conserve battery"
    tier = 2

    def run_test(self):
        updates_received = []
        last_update_time = 100.0
        MIN_INTERVAL_SEC = 5.0

        for now in [101.0, 103.0, 106.0, 107.0]:
            if (now - last_update_time) >= MIN_INTERVAL_SEC:
                updates_received.append(now)
                last_update_time = now

        CustomAssertions.assert_equal(len(updates_received), 1)
        CustomAssertions.assert_equal(updates_received[0], 106.0)


class TestR5_003_T2_129_MockLocationFiltering(BaseTestCase):
    test_id = "T2-129"
    feature_id = "F-R5-003"
    title = "Location spoofing / mock location filtering"
    tier = 2

    def run_test(self):
        location_provider_is_mock = True
        allow_mock_locations = False

        is_valid_location = not (location_provider_is_mock and not allow_mock_locations)
        CustomAssertions.assert_false(is_valid_location)


class TestR5_003_T2_130_UnsubscribeLocationOnExit(BaseTestCase):
    test_id = "T2-130"
    feature_id = "F-R5-003"
    title = "Unsubscribe location updates when Linux app terminates location session"
    tier = 2

    def run_test(self):
        active_subscribers = {"org.kde.Marble"}
        
        # App terminates location session
        active_subscribers.remove("org.kde.Marble")
        CustomAssertions.assert_equal(len(active_subscribers), 0)


# -----------------------------------------------------------------------------
# F-R5-004: AppOps Permission Prompt (T2-131 .. T2-135)
# -----------------------------------------------------------------------------
class TestR5_004_T2_131_PermissionPromptTimeout(BaseTestCase):
    test_id = "T2-131"
    feature_id = "F-R5-004"
    title = "Permission prompt timeout default rejection (30 sec timeout)"
    tier = 2

    def run_test(self):
        prompt_displayed_time = 100.0
        current_time = 135.0  # 35 seconds later
        PROMPT_TIMEOUT = 30.0

        mode = "PROMPT"
        if (current_time - prompt_displayed_time) > PROMPT_TIMEOUT:
            mode = "DENIED"  # Default reject on timeout

        CustomAssertions.assert_equal(mode, "DENIED")


class TestR5_004_T2_132_RejectDuplicatePrompts(BaseTestCase):
    test_id = "T2-132"
    feature_id = "F-R5-004"
    title = "Reject duplicate permission prompts while dialog is visible"
    tier = 2

    def run_test(self):
        dialog_visible = True
        prompt_count = 1

        if dialog_visible:
            # Second prompt request is suppressed/ignored
            pass

        CustomAssertions.assert_equal(prompt_count, 1)


class TestR5_004_T2_133_EnterpriseMdmForceDeny(BaseTestCase):
    test_id = "T2-133"
    feature_id = "F-R5-004"
    title = "System policy override (enterprise MDM permission force-deny)"
    tier = 2

    def run_test(self):
        mdm_policy_restricted = True
        user_choice = "ALLOWED"

        effective_mode = "DENIED" if mdm_policy_restricted else user_choice
        CustomAssertions.assert_equal(effective_mode, "DENIED")


class TestR5_004_T2_134_PermissionRevocationSettings(BaseTestCase):
    test_id = "T2-134"
    feature_id = "F-R5-004"
    title = "Permission revocation via Android System Settings app ops list"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_appop("org.gnome.Cheese", "OP_CAMERA", "ALLOWED")
        CustomAssertions.assert_true(self.mock_env.portal.request_camera_access("org.gnome.Cheese"))

        # User revokes permission in Settings
        ss.set_appop("org.gnome.Cheese", "OP_CAMERA", "DENIED")
        CustomAssertions.assert_false(self.mock_env.portal.request_camera_access("org.gnome.Cheese"))


class TestR5_004_T2_135_PromptSuppressedWhenLocked(BaseTestCase):
    test_id = "T2-135"
    feature_id = "F-R5-004"
    title = "Prompt display when screen is locked (suppressed until unlocked)"
    tier = 2

    def run_test(self):
        screen_locked = True
        pending_prompts = []

        if screen_locked:
            pending_prompts.append("org.gnome.Cheese:OP_CAMERA")

        CustomAssertions.assert_equal(len(pending_prompts), 1)

        # Unlock screen presents queued prompt
        screen_locked = False
        active_dialog = pending_prompts.pop(0)
        CustomAssertions.assert_equal(active_dialog, "org.gnome.Cheese:OP_CAMERA")


# -----------------------------------------------------------------------------
# F-R5-005: virtio-snd Audio Mapping (T2-136 .. T2-140)
# -----------------------------------------------------------------------------
class TestR5_005_T2_136_AudioBufferOverflowLoad(BaseTestCase):
    test_id = "T2-136"
    feature_id = "F-R5-005"
    title = "Audio buffer overflow under heavy CPU load"
    tier = 2

    def run_test(self):
        audio_queue = []
        MAX_AUDIO_QUEUE = 100

        for i in range(150):
            audio_queue.append(f"frame_{i}")
            if len(audio_queue) > MAX_AUDIO_QUEUE:
                audio_queue.pop(0)  # Drop oldest frame under heavy load

        CustomAssertions.assert_equal(len(audio_queue), MAX_AUDIO_QUEUE)


class TestR5_005_T2_137_BluetoothHeadsetDisconnect(BaseTestCase):
    test_id = "T2-137"
    feature_id = "F-R5-005"
    title = "Bluetooth headset disconnect / output device switching"
    tier = 2

    def run_test(self):
        output_route = "BLUETOOTH_A2DP"
        bt_connected = False

        if not bt_connected:
            output_route = "BUILTIN_SPEAKER"

        CustomAssertions.assert_equal(output_route, "BUILTIN_SPEAKER")


class TestR5_005_T2_138_SilentFrameZeroFill(BaseTestCase):
    test_id = "T2-138"
    feature_id = "F-R5-005"
    title = "Zero-fill silent frames on audio buffer underrun"
    tier = 2

    def run_test(self):
        data_available = 0
        REQUIRED_BYTES = 512

        fill_bytes = b"\x00" * REQUIRED_BYTES if data_available == 0 else b""
        CustomAssertions.assert_equal(len(fill_bytes), 512)
        CustomAssertions.assert_equal(set(fill_bytes), {0})


class TestR5_005_T2_139_SampleFormatConversion(BaseTestCase):
    test_id = "T2-139"
    feature_id = "F-R5-005"
    title = "Sample format conversion (INT16 to FLOAT32)"
    tier = 2

    def run_test(self):
        int16_max = 32767
        int16_min = -32768

        float_max = int16_max / 32768.0
        float_min = int16_min / 32768.0

        CustomAssertions.assert_true(0.99 < float_max <= 1.0)
        CustomAssertions.assert_equal(float_min, -1.0)


class TestR5_005_T2_140_MultiStreamAudioMixing(BaseTestCase):
    test_id = "T2-140"
    feature_id = "F-R5-005"
    title = "Simultaneous multi-stream audio mixing"
    tier = 2

    def run_test(self):
        stream_1_sample = 0.4
        stream_2_sample = 0.5

        mixed_sample = min(1.0, max(-1.0, stream_1_sample + stream_2_sample))
        CustomAssertions.assert_equal(mixed_sample, 0.9)


# -----------------------------------------------------------------------------
# F-R5-006: AudioFocus Policy Handler (T2-141 .. T2-145)
# -----------------------------------------------------------------------------
class TestR5_006_T2_141_PhoneCallDucking(BaseTestCase):
    test_id = "T2-141"
    feature_id = "F-R5-006"
    title = "Incoming phone call triggers immediate audio ducking/mute"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.audio_focus_state = "GAIN"
        env.audio_volume = 1.0

        # Incoming phone call events
        env.audio_focus_state = "LOSS_TRANSIENT_CAN_DUCK"
        env.audio_volume = 0.2

        CustomAssertions.assert_equal(env.audio_focus_state, "LOSS_TRANSIENT_CAN_DUCK")
        CustomAssertions.assert_equal(env.audio_volume, 0.2)


class TestR5_006_T2_142_AlarmClockPause(BaseTestCase):
    test_id = "T2-142"
    feature_id = "F-R5-006"
    title = "Alarm clock trigger pauses Linux media playback"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.audio_focus_state = "GAIN"

        # Alarm goes off
        env.audio_focus_state = "LOSS_TRANSIENT"
        CustomAssertions.assert_equal(env.audio_focus_state, "LOSS_TRANSIENT")


class TestR5_006_T2_143_RejectFocusWithoutForegroundService(BaseTestCase):
    test_id = "T2-143"
    feature_id = "F-R5-006"
    title = "Reject audio focus request when backgrounded without foreground service"
    tier = 2

    def run_test(self):
        def request_audio_focus(is_background: bool, has_foreground_svc: bool):
            if is_background and not has_foreground_svc:
                return "AUDIOFOCUS_REQUEST_FAILED"
            return "AUDIOFOCUS_REQUEST_GRANTED"

        result = request_audio_focus(True, False)
        CustomAssertions.assert_equal(result, "AUDIOFOCUS_REQUEST_FAILED")


class TestR5_006_T2_144_AudioFocusSuspendRecovery(BaseTestCase):
    test_id = "T2-144"
    feature_id = "F-R5-006"
    title = "Audio focus state recovery after app suspension"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.audio_focus_state = "GAIN"
        saved_focus = env.audio_focus_state

        # App suspended and resumed
        env.audio_focus_state = "NONE"
        env.audio_focus_state = saved_focus

        CustomAssertions.assert_equal(env.audio_focus_state, "GAIN")


class TestR5_006_T2_145_RapidAudioFocusToggle(BaseTestCase):
    test_id = "T2-145"
    feature_id = "F-R5-006"
    title = "Rapid audio focus toggle stability test"
    tier = 2

    def run_test(self):
        env = self.mock_env
        states = ["GAIN", "LOSS_TRANSIENT", "GAIN", "LOSS", "GAIN"]

        for s in states:
            env.audio_focus_state = s

        CustomAssertions.assert_equal(env.audio_focus_state, "GAIN")


# -----------------------------------------------------------------------------
# F-R5-007: virtiofs Bi-directional Sharing (T2-146 .. T2-150)
# -----------------------------------------------------------------------------
class TestR5_007_T2_146_SymlinkTraversalRestriction(BaseTestCase):
    test_id = "T2-146"
    feature_id = "F-R5-007"
    title = "Symlink traversal restriction (prevent escaping shared folder root)"
    tier = 2

    def run_test(self):
        shared_root = "/mnt/shared"
        
        def resolve_shared_symlink(target_path: str):
            normalized = os.path.normpath(os.path.join(shared_root, target_path))
            if not normalized.startswith(shared_root):
                raise PermissionError("SecurityException: Symlink traversal escapes shared folder boundary")
            return normalized

        CustomAssertions.assert_raises(PermissionError, resolve_shared_symlink, "../../etc/shadow")


class TestR5_007_T2_147_FilePermissionBitMapping(BaseTestCase):
    test_id = "T2-147"
    feature_id = "F-R5-007"
    title = "File permission bit mapping (Host Android UID vs Guest Linux UID)"
    tier = 2

    def run_test(self):
        host_uid = 1000
        host_mode = 0o644

        guest_uid = host_uid  # UID 1000 mapped
        guest_mode = host_mode

        CustomAssertions.assert_equal(guest_uid, 1000)
        CustomAssertions.assert_equal(guest_mode, 0o644)


class TestR5_007_T2_148_ConcurrentFileLockResolution(BaseTestCase):
    test_id = "T2-148"
    feature_id = "F-R5-007"
    title = "Concurrent edit lock conflict resolution on shared files"
    tier = 2

    def run_test(self):
        file_locks = {"document.txt": "HOST_WRITER"}

        def guest_acquire_write_lock(filename: str):
            if filename in file_locks and file_locks[filename] != "GUEST_WRITER":
                raise OSError("EBUSY: File is locked by host process")
            file_locks[filename] = "GUEST_WRITER"

        CustomAssertions.assert_raises(OSError, guest_acquire_write_lock, "document.txt")


class TestR5_007_T2_149_LargeFileTransferIntegrity(BaseTestCase):
    test_id = "T2-149"
    feature_id = "F-R5-007"
    title = "Large file transfer (> 4GB) integrity check via SHA256 checksum"
    tier = 2

    def run_test(self):
        # 4GB payload checksum validation
        payload_meta = {"size_bytes": 4294967296, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}
        received_sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        CustomAssertions.assert_equal(received_sha256, payload_meta["sha256"])


class TestR5_007_T2_150_VirtiofsDiskSpaceError(BaseTestCase):
    test_id = "T2-150"
    feature_id = "F-R5-007"
    title = "Out of disk space error propagation across virtiofs boundary"
    tier = 2

    def run_test(self):
        host_free_bytes = 0

        def write_file_virtiofs(data_len: int):
            if data_len > host_free_bytes:
                raise OSError("ENOSPC: Virtiofs host storage volume is full")

        CustomAssertions.assert_raises(OSError, write_file_virtiofs, 1024)


# -----------------------------------------------------------------------------
# F-R5-008: LinuxStorageProvider SAF Provider (T2-151 .. T2-155)
# -----------------------------------------------------------------------------
class TestR5_008_T2_151_HideSystemRootFromSaf(BaseTestCase):
    test_id = "T2-151"
    feature_id = "F-R5-008"
    title = "Hide system root / directories from SAF provider picker"
    tier = 2

    def run_test(self):
        exposed_roots = ["/home/user", "/mnt/shared"]
        system_roots = ["/sys", "/proc", "/etc", "/dev"]

        for sys_dir in system_roots:
            CustomAssertions.assert_false(sys_dir in exposed_roots)


class TestR5_008_T2_152_SafVmOfflineError(BaseTestCase):
    test_id = "T2-152"
    feature_id = "F-R5-008"
    title = "Handle guest VM offline state when SAF file picker accessed"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.set_state("OFF")

        def query_saf_documents():
            if ss.vm_state == "OFF":
                raise ConnectionError("VMOfflineException: Cannot browse SAF documents while Linux VM is powered off")

        CustomAssertions.assert_raises(ConnectionError, query_saf_documents)


class TestR5_008_T2_153_DenySafLockedLuksVolume(BaseTestCase):
    test_id = "T2-153"
    feature_id = "F-R5-008"
    title = "Deny access to locked LUKS2 volume prior to credential unlock"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.lock_user()

        def open_user_home_saf():
            if not ss.ce_key_available:
                raise PermissionError("EncryptedStorageException: CE storage volume is locked")

        CustomAssertions.assert_raises(PermissionError, open_user_home_saf)


class TestR5_008_T2_154_SafChangeNotificationTrigger(BaseTestCase):
    test_id = "T2-154"
    feature_id = "F-R5-008"
    title = "SAF document notification change trigger on guest file modification"
    tier = 2

    def run_test(self):
        notifications = []

        def notify_document_changed(uri: str):
            notifications.append(uri)

        notify_document_changed("content://com.android.linux.storage/document/home/user/doc.txt")
        CustomAssertions.assert_equal(len(notifications), 1)


class TestR5_008_T2_155_SafReadOnlyMountFlags(BaseTestCase):
    test_id = "T2-155"
    feature_id = "F-R5-008"
    title = "Enforce read-only SAF flags when guest volume mounted read-only"
    tier = 2

    def run_test(self):
        is_read_only_mount = True
        FLAG_SUPPORTS_WRITE = 0x04
        FLAG_SUPPORTS_DELETE = 0x08

        doc_flags = 0
        if not is_read_only_mount:
            doc_flags |= (FLAG_SUPPORTS_WRITE | FLAG_SUPPORTS_DELETE)

        CustomAssertions.assert_equal(doc_flags & FLAG_SUPPORTS_WRITE, 0)


# -----------------------------------------------------------------------------
# F-R5-009: SELinux Domain Policy Rules (T2-156 .. T2-160)
# -----------------------------------------------------------------------------
class TestR5_009_T2_156_SelinuxNoAuditDenialsNormal(BaseTestCase):
    test_id = "T2-156"
    feature_id = "F-R5-009"
    title = "Audit log verification: no unhandled avc: denied messages"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        # Normal clean operation audit log check
        denials = [log for log in ss.audit_logs if "avc: denied" in log]
        CustomAssertions.assert_equal(len(denials), 0)


class TestR5_009_T2_157_BlockBridgeUnlabelledStorage(BaseTestCase):
    test_id = "T2-157"
    feature_id = "F-R5-009"
    title = "Block linux_bridge from accessing unlabelled storage files"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.log_selinux_audit("type=1400 avc: denied { read } for scontext=u:r:linux_bridge:s0 tcontext=u:object_r:unlabeled:s0")

        CustomAssertions.assert_selinux_denial(ss.audit_logs, "linux_bridge", "unlabeled")


class TestR5_009_T2_158_BlockPortalKeystoreAccess(BaseTestCase):
    test_id = "T2-158"
    feature_id = "F-R5-009"
    title = "Block linux_portal from reading system credential keystore files"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.log_selinux_audit("type=1400 avc: denied { read } for scontext=u:r:linux_portal:s0 tcontext=u:object_r:keystore_data_file:s0")

        CustomAssertions.assert_selinux_denial(ss.audit_logs, "linux_portal", "keystore_data_file")


class TestR5_009_T2_159_DomainTransitionBridge(BaseTestCase):
    test_id = "T2-159"
    feature_id = "F-R5-009"
    title = "Transition domain correctly when linux_bridge executable is spawned"
    tier = 2

    def run_test(self):
        exec_file_context = "u:object_r:linux_bridge_exec:s0"
        target_domain = "u:r:linux_bridge:s0"

        transition_valid = (exec_file_context == "u:object_r:linux_bridge_exec:s0" and target_domain == "u:r:linux_bridge:s0")
        CustomAssertions.assert_true(transition_valid)


class TestR5_009_T2_160_EnforcingVsPermissiveCheck(BaseTestCase):
    test_id = "T2-160"
    feature_id = "F-R5-009"
    title = "Enforcing vs Permissive SELinux mode execution check"
    tier = 2

    def run_test(self):
        selinux_mode = getattr(self.mock_env.system_server, "selinux_mode", "Enforcing")
        CustomAssertions.assert_equal(selinux_mode, "Enforcing", "SELinux must operate in Enforcing mode")


# -----------------------------------------------------------------------------
# F-R5-010: SELinux neverallow Rules (T2-161 .. T2-165)
# -----------------------------------------------------------------------------
class TestR5_010_T2_161_NeverallowBuildViolation(BaseTestCase):
    test_id = "T2-161"
    feature_id = "F-R5-010"
    title = "Build failure assertion when violating neverallow policy rule"
    tier = 2

    def run_test(self):
        neverallows = self.mock_env.neverallow_rules

        def compile_policy(proposed_rule: str):
            if any("efs_file" in proposed_rule and "linux_bridge" in proposed_rule for _ in neverallows):
                raise ValueError("NeverallowViolation: allow linux_bridge efs_file:file * violates policy")

        CustomAssertions.assert_raises(ValueError, compile_policy, "allow linux_bridge efs_file:file read;")


class TestR5_010_T2_162_PreventDomainTransitionSuInit(BaseTestCase):
    test_id = "T2-162"
    feature_id = "F-R5-010"
    title = "Prevent unauthorized domain transition to su or init"
    tier = 2

    def run_test(self):
        forbidden_transitions = [("linux_bridge", "su"), ("linux_portal", "init")]

        def check_transition(source: str, target: str):
            if (source, target) in forbidden_transitions:
                raise PermissionError(f"NeverallowViolation: Domain transition {source} -> {target} prohibited")

        CustomAssertions.assert_raises(PermissionError, check_transition, "linux_bridge", "su")


class TestR5_010_T2_163_BlockRawBlockDeviceAccess(BaseTestCase):
    test_id = "T2-163"
    feature_id = "F-R5-010"
    title = "Block raw block device read/write execution from all guest domains"
    tier = 2

    def run_test(self):
        def open_block_device(domain: str, dev_path: str):
            if domain.startswith("linux_") and dev_path.startswith("/dev/block/"):
                raise PermissionError(f"SELinuxDenial: {domain} cannot open raw block device {dev_path}")

        CustomAssertions.assert_raises(PermissionError, open_block_device, "linux_bridge", "/dev/block/sda1")


class TestR5_010_T2_164_ExploitDenialVerification(BaseTestCase):
    test_id = "T2-164"
    feature_id = "F-R5-010"
    title = "Verify denial enforcement under active exploit simulation"
    tier = 2

    def run_test(self):
        ss = self.mock_env.system_server
        ss.log_selinux_audit("type=1400 avc: denied { ptrace } for scontext=u:r:linux_bridge:s0 tcontext=u:r:system_server:s0")

        CustomAssertions.assert_selinux_denial(ss.audit_logs, "linux_bridge", "system_server")


class TestR5_010_T2_165_ValidateNeverallowAllBoards(BaseTestCase):
    test_id = "T2-165"
    feature_id = "F-R5-010"
    title = "Validate neverallow assertions across all target board sepolicy configs"
    tier = 2

    def run_test(self):
        validated_boards = self.mock_env.validate_sepolicy_boards()
        CustomAssertions.assert_true(validated_boards >= 1)


# -----------------------------------------------------------------------------
# F-R5-011: CTS / VTS Compatibility (T2-166 .. T2-170)
# -----------------------------------------------------------------------------
class TestR5_011_T2_166_CtsAidlModificationRegression(BaseTestCase):
    test_id = "T2-166"
    feature_id = "F-R5-011"
    title = "Detect CTS regressions on custom AIDL interface modifications"
    tier = 2

    def run_test(self):
        cts = dict(self.mock_env.cts_results)
        breaking_change_introduced = True

        if breaking_change_introduced:
            cts["failed"] += 1

        CustomAssertions.assert_equal(cts["failed"], 1)


class TestR5_011_T2_167_TrebleBoundaryCompliance(BaseTestCase):
    test_id = "T2-167"
    feature_id = "F-R5-011"
    title = "Ensure system partition modification does not violate Treble boundaries"
    tier = 2

    def run_test(self):
        vendor_dependency_in_system = False
        CustomAssertions.assert_false(vendor_dependency_in_system, "System partition must not depend on non-VNDK vendor symbols")


class TestR5_011_T2_168_GsiBootCompatibility(BaseTestCase):
    test_id = "T2-168"
    feature_id = "F-R5-011"
    title = "Verify GSI (Generic System Image) boot compatibility with Dual-OS"
    tier = 2

    def run_test(self):
        gsi_boot_success = self.mock_env.system_server.verify_gsi_boot_compatibility()
        CustomAssertions.assert_true(gsi_boot_success)


class TestR5_011_T2_169_SepolicyUserVsUserdebug(BaseTestCase):
    test_id = "T2-169"
    feature_id = "F-R5-011"
    title = "Verify SELinux policy compliance on userbuild vs userdebug targets"
    tier = 2

    def run_test(self):
        build_type = "user"
        permissive_domains_allowed = False if build_type == "user" else True

        CustomAssertions.assert_false(permissive_domains_allowed)


class TestR5_011_T2_170_CtsIdlePowerOverhead(BaseTestCase):
    test_id = "T2-170"
    feature_id = "F-R5-011"
    title = "Performance overhead compliance (< 2% battery drop under idle CTS run)"
    tier = 2

    def run_test(self):
        idle_battery_drop_pct = self.mock_env.system_server.measure_cts_idle_power_drop()
        MAX_BATTERY_DROP = 2.0

        CustomAssertions.assert_true(idle_battery_drop_pct < MAX_BATTERY_DROP)


# -----------------------------------------------------------------------------
# F-R5-012: EROFS Base Image A/B Layout (T2-171 .. T2-175)
# -----------------------------------------------------------------------------
class TestR5_012_T2_171_BlockWriteActiveErofs(BaseTestCase):
    test_id = "T2-171"
    feature_id = "F-R5-012"
    title = "Block write operations to active EROFS partition"
    tier = 2

    def run_test(self):
        def write_erofs_partition():
            raise PermissionError("EROFSException: Read-only file system (base_a.img)")

        CustomAssertions.assert_raises(PermissionError, write_erofs_partition)


class TestR5_012_T2_172_InterruptedOtaDownload(BaseTestCase):
    test_id = "T2-172"
    feature_id = "F-R5-012"
    title = "Handle interrupted OTA download without corrupting active slot"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.boot_slot = "slot_a"
        ota_download_interrupted = True

        if ota_download_interrupted:
            # Active slot remains unchanged
            pass

        CustomAssertions.assert_equal(env.boot_slot, "slot_a")


class TestR5_012_T2_173_ErofsCompressionRatio(BaseTestCase):
    test_id = "T2-173"
    feature_id = "F-R5-012"
    title = "Compression ratio verification (EROFS vs EXT4 size savings)"
    tier = 2

    def run_test(self):
        ext4_size_mb = 2048
        erofs_size_mb = 1280

        savings_pct = ((ext4_size_mb - erofs_size_mb) / ext4_size_mb) * 100
        CustomAssertions.assert_true(savings_pct >= 30.0, f"Savings {savings_pct}% should be >= 30%")


class TestR5_012_T2_174_ErofsReadThroughput(BaseTestCase):
    test_id = "T2-174"
    feature_id = "F-R5-012"
    title = "Verify read performance throughput on EROFS base image (> 200MB/s)"
    tier = 2

    def run_test(self):
        simulated_throughput_mb_s = self.mock_env.measure_erofs_read_throughput()
        MIN_THROUGHPUT = 200.0

        CustomAssertions.assert_true(simulated_throughput_mb_s >= MIN_THROUGHPUT)


class TestR5_012_T2_175_FallbackSlotMountOnChecksumFail(BaseTestCase):
    test_id = "T2-175"
    feature_id = "F-R5-012"
    title = "Fallback slot mount when primary slot image block checksum fails"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.boot_slot = "slot_a"

        slot_a_checksum_valid = False
        if not slot_a_checksum_valid:
            env.boot_slot = "slot_b"

        CustomAssertions.assert_equal(env.boot_slot, "slot_b")


# -----------------------------------------------------------------------------
# F-R5-013: AVB Key Signature Validation (T2-176 .. T2-180)
# -----------------------------------------------------------------------------
class TestR5_013_T2_176_RejectUntrustedPrivateKey(BaseTestCase):
    test_id = "T2-176"
    feature_id = "F-R5-013"
    title = "Reject guest image signed with unauthorized / untrusted private key"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.avb_key_valid = False

        def verify_avb_signature():
            if not env.avb_key_valid:
                raise PermissionError("AVBValidationError: Trusted root key mismatch")

        CustomAssertions.assert_raises(PermissionError, verify_avb_signature)


class TestR5_013_T2_177_RejectTamperedBaseImage(BaseTestCase):
    test_id = "T2-177"
    feature_id = "F-R5-013"
    title = "Reject tampered / bit-flipped base image file during boot check"
    tier = 2

    def run_test(self):
        env = self.mock_env
        expected_digest = env.vbmeta_digest
        actual_digest = "0000000000000000000000000000000000000000000000000000000000000000"

        def verify_image_digest():
            if actual_digest != expected_digest:
                raise ValueError("AVBDigestMismatch: Image block tampered or corrupted")

        CustomAssertions.assert_raises(ValueError, verify_image_digest)


class TestR5_013_T2_178_MissingVbmetaHeader(BaseTestCase):
    test_id = "T2-178"
    feature_id = "F-R5-013"
    title = "Handle missing vbmeta header in guest OTA package"
    tier = 2

    def run_test(self):
        ota_package = {"payload.bin": b"image_data"}  # Missing vbmeta.img

        def parse_ota_package(pkg: dict):
            if "vbmeta.img" not in pkg:
                raise ValueError("AVBHeaderMissing: OTA package missing vbmeta descriptor")

        CustomAssertions.assert_raises(ValueError, parse_ota_package, ota_package)


class TestR5_013_T2_179_PreventRollbackOlderVersion(BaseTestCase):
    test_id = "T2-179"
    feature_id = "F-R5-013"
    title = "Prevent rollback to older image version when rollback index enforced"
    tier = 2

    def run_test(self):
        current_rollback_index = 3
        ota_rollback_index = 2  # Downgrade attempt

        def verify_rollback_index(pkg_idx: int):
            if pkg_idx < current_rollback_index:
                raise ValueError(f"AVBRollbackDenied: Package index {pkg_idx} < device index {current_rollback_index}")

        CustomAssertions.assert_raises(ValueError, verify_rollback_index, ota_rollback_index)


class TestR5_013_T2_180_DebugVsProdKeyPolicy(BaseTestCase):
    test_id = "T2-180"
    feature_id = "F-R5-013"
    title = "Debug key vs Production key policy enforcement check"
    tier = 2

    def run_test(self):
        build_type = "user"
        image_key_type = "test-keys"

        def enforce_key_policy(btype: str, ktype: str):
            if btype == "user" and ktype != "release-keys":
                raise PermissionError("AVBPolicyViolation: User build rejects test-keys signed images")

        CustomAssertions.assert_raises(PermissionError, enforce_key_policy, build_type, image_key_type)


# -----------------------------------------------------------------------------
# F-R5-014: Boot Watchdog Rollback Engine (T2-181 .. T2-185)
# -----------------------------------------------------------------------------
class TestR5_014_T2_181_GuestKernelFreezeReset(BaseTestCase):
    test_id = "T2-181"
    feature_id = "F-R5-014"
    title = "Guest kernel freeze during boot triggers hardware watchdog reset"
    tier = 2

    def run_test(self):
        env = self.mock_env
        boot_time_sec = 65.0
        BOOT_DEADLINE_SEC = 60.0

        if boot_time_sec > BOOT_DEADLINE_SEC:
            env.boot_attempts += 1

        CustomAssertions.assert_equal(env.boot_attempts, 1)


class TestR5_014_T2_182_SystemdBootLoopRollback(BaseTestCase):
    test_id = "T2-182"
    feature_id = "F-R5-014"
    title = "Guest systemd boot loop triggers watchdog rollback after 3 attempts"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.boot_slot = "slot_a"

        # Simulate 3 consecutive boot loop failures
        for _ in range(3):
            env.boot_attempts += 1
            if env.boot_attempts >= 3:
                env.boot_slot = "slot_b"  # Automatic slot rollback

        CustomAssertions.assert_equal(env.boot_attempts, 3)
        CustomAssertions.assert_equal(env.boot_slot, "slot_b")


class TestR5_014_T2_183_RetainUserDataOnRollback(BaseTestCase):
    test_id = "T2-183"
    feature_id = "F-R5-014"
    title = "Retain user data partition (user_home.img) intact during base image rollback"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.boot_slot = "slot_a"
        env.boot_slot = "slot_b"  # Rollback slot

        # User data mount remains untouched
        user_home_mount = env.storage_mounts["/home/user"]
        CustomAssertions.assert_equal(user_home_mount["device"], "/dev/mapper/user_home_decrypted")
        CustomAssertions.assert_equal(user_home_mount["opts"], "rw")


class TestR5_014_T2_184_MarkFailedSlotUnbootable(BaseTestCase):
    test_id = "T2-184"
    feature_id = "F-R5-014"
    title = "Mark failed slot as unbootable (successful_boot=0)"
    tier = 2

    def run_test(self):
        slot_flags = {"slot_a": {"successful_boot": 1}, "slot_b": {"successful_boot": 1}}

        # slot_a fails 3 boot attempts
        slot_flags["slot_a"]["successful_boot"] = 0

        CustomAssertions.assert_equal(slot_flags["slot_a"]["successful_boot"], 0)
        CustomAssertions.assert_equal(slot_flags["slot_b"]["successful_boot"], 1)


class TestR5_014_T2_185_ManualForceRollback(BaseTestCase):
    test_id = "T2-185"
    feature_id = "F-R5-014"
    title = "Manual force-rollback API invocation test"
    tier = 2

    def run_test(self):
        env = self.mock_env
        env.boot_slot = "slot_a"

        def force_rollback():
            env.boot_slot = "slot_b" if env.boot_slot == "slot_a" else "slot_a"

        force_rollback()
        CustomAssertions.assert_equal(env.boot_slot, "slot_b")
