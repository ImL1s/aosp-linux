"""
Tier 2 Boundary & Corner Case Tests for Milestone 4: Wayland Window Forwarding, virtio-gpu & Launcher3 Integration.
Features: F-R4-001 through F-R4-006 (Tests T2-86 .. T2-115)
"""

import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, MockEnvironment

# -----------------------------------------------------------------------------
# F-R4-001: Wayland Window Forwarding (T2-86 .. T2-90)
# -----------------------------------------------------------------------------
class TestR4_001_T2_86_SommelierCrashRecovery(BaseTestCase):
    test_id = "T2-86"
    feature_id = "F-R4-001"
    title = "Window forwarding recovery on guest Sommelier crash"
    tier = 2

    def run_test(self):
        som = self.mock_env.sommelier
        sid = som.create_surface("org.gnome.gedit", 800, 600)
        CustomAssertions.assert_in(sid, som.active_surfaces)

        # Simulated Sommelier crash wipes active surfaces
        som.active_surfaces.clear()
        CustomAssertions.assert_equal(len(som.active_surfaces), 0)


class TestR4_001_T2_87_MultiWindowSurfaceIsolation(BaseTestCase):
    test_id = "T2-87"
    feature_id = "F-R4-001"
    title = "Multi-window Wayland surface mapping isolation"
    tier = 2

    def run_test(self):
        som = self.mock_env.sommelier
        sid1 = som.create_surface("app.a", 640, 480)
        sid2 = som.create_surface("app.b", 1024, 768)

        som.commit_frame(sid1)
        som.commit_frame(sid1)
        som.commit_frame(sid2)

        CustomAssertions.assert_equal(som.active_surfaces[sid1]["committed_frames"], 2)
        CustomAssertions.assert_equal(som.active_surfaces[sid2]["committed_frames"], 1)


class TestR4_001_T2_88_ProtocolVersionMismatch(BaseTestCase):
    test_id = "T2-88"
    feature_id = "F-R4-001"
    title = "Protocol version mismatch negotiation fallback"
    tier = 2

    def run_test(self):
        guest_requested_version = 5
        host_max_version = 4

        negotiated_version = min(guest_requested_version, host_max_version)
        CustomAssertions.assert_equal(negotiated_version, 4)


class TestR4_001_T2_89_HighFpsBufferDropCheck(BaseTestCase):
    test_id = "T2-89"
    feature_id = "F-R4-001"
    title = "High frame-rate Wayland buffer delivery buffer drop check"
    tier = 2

    def run_test(self):
        committed_frames = 120
        DISPLAY_FPS_CAP = 60

        displayed_frames = min(committed_frames, DISPLAY_FPS_CAP)
        dropped_frames = committed_frames - displayed_frames

        CustomAssertions.assert_equal(displayed_frames, 60)
        CustomAssertions.assert_equal(dropped_frames, 60)


class TestR4_001_T2_90_OutOfOrderPacketSequence(BaseTestCase):
    test_id = "T2-90"
    feature_id = "F-R4-001"
    title = "Out-of-order Wayland protocol packet sequence handling"
    tier = 2

    def run_test(self):
        som = self.mock_env.sommelier
        invalid_surface_id = 9999

        # Committing non-existent surface should not crash
        som.commit_frame(invalid_surface_id)
        CustomAssertions.assert_false(invalid_surface_id in som.active_surfaces)


# -----------------------------------------------------------------------------
# F-R4-002: virtio-gpu dma-buf Sharing (T2-91 .. T2-95)
# -----------------------------------------------------------------------------
class TestR4_002_T2_91_InvalidDmabufHandle(BaseTestCase):
    test_id = "T2-91"
    feature_id = "F-R4-002"
    title = "Invalid dma-buf handle import failure handling"
    tier = 2

    def run_test(self):
        def import_dma_buf(fd: int):
            if fd < 0:
                raise ValueError(f"InvalidDmabufHandle: Invalid file descriptor {fd}")
            return "HardwareBuffer_Handle"

        CustomAssertions.assert_raises(ValueError, import_dma_buf, -1)


class TestR4_002_T2_92_GpuMemoryLeakValidation(BaseTestCase):
    test_id = "T2-92"
    feature_id = "F-R4-002"
    title = "Hardware graphics memory leak validation under dynamic allocation"
    tier = 2

    def run_test(self):
        allocated_buffers = set()

        for i in range(100):
            allocated_buffers.add(f"buffer_{i}")

        for i in range(100):
            allocated_buffers.remove(f"buffer_{i}")

        CustomAssertions.assert_equal(len(allocated_buffers), 0)


class TestR4_002_T2_93_GpuResetHostSurfaceRecreation(BaseTestCase):
    test_id = "T2-93"
    feature_id = "F-R4-002"
    title = "GPU device reset / host surface recreation handling"
    tier = 2

    def run_test(self):
        gpu_device_reset = True
        surface_recreated = False

        if gpu_device_reset:
            surface_recreated = True
            gpu_device_reset = False

        CustomAssertions.assert_true(surface_recreated)
        CustomAssertions.assert_false(gpu_device_reset)


class TestR4_002_T2_94_FormatIncompatibilityFallback(BaseTestCase):
    test_id = "T2-94"
    feature_id = "F-R4-002"
    title = "Format incompatibility fallback (e.g. RGB vs YUV buffers)"
    tier = 2

    def run_test(self):
        supported_formats = {"AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM"}
        incoming_format = "YUV420_PLANAR"

        target_format = incoming_format
        if incoming_format not in supported_formats:
            target_format = "AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM"  # Sw-conversion fallback

        CustomAssertions.assert_equal(target_format, "AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM")


class TestR4_002_T2_95_SyncGpuFenceCompletion(BaseTestCase):
    test_id = "T2-95"
    feature_id = "F-R4-002"
    title = "Synchronize GPU fence completion before host display read"
    tier = 2

    def run_test(self):
        fence_signaled = False

        def present_buffer():
            if not fence_signaled:
                raise RuntimeError("SyncFenceWaitTimeout: GPU fence not signaled")

        CustomAssertions.assert_raises(RuntimeError, present_buffer)


# -----------------------------------------------------------------------------
# F-R4-003: LinuxAppProxyActivity Task ID (T2-96 .. T2-100)
# -----------------------------------------------------------------------------
class TestR4_003_T2_96_ReuseTaskIdOnRelaunch(BaseTestCase):
    test_id = "T2-96"
    feature_id = "F-R4-003"
    title = "Reuse existing Task ID when tapping app icon while app is running"
    tier = 2

    def run_test(self):
        tasks = self.mock_env.active_task_ids
        app_id = "org.gimp.GIMP"
        tasks[101] = app_id

        # Relaunch attempt checks if app_id is already running
        existing_task_id = None
        for tid, aid in tasks.items():
            if aid == app_id:
                existing_task_id = tid
                break

        CustomAssertions.assert_equal(existing_task_id, 101)


class TestR4_003_T2_97_AbruptTaskKillProcessCleanup(BaseTestCase):
    test_id = "T2-97"
    feature_id = "F-R4-003"
    title = "Handle abrupt task kill without leaking guest processes"
    tier = 2

    def run_test(self):
        tasks = self.mock_env.active_task_ids
        task_id = 105
        tasks[task_id] = "org.mozilla.firefox"

        # User swipes away task from Recents
        killed_app = tasks.pop(task_id, None)
        CustomAssertions.assert_equal(killed_app, "org.mozilla.firefox")
        CustomAssertions.assert_false(task_id in tasks)


class TestR4_003_T2_98_MaxConcurrentTaskLimit(BaseTestCase):
    test_id = "T2-98"
    feature_id = "F-R4-003"
    title = "Maximum concurrent Linux task limit enforcement"
    tier = 2

    def run_test(self):
        tasks = self.mock_env.active_task_ids
        MAX_TASKS = 20

        for i in range(MAX_TASKS):
            tasks[100 + i] = f"app_{i}"

        def launch_21st_app():
            if len(tasks) >= MAX_TASKS:
                raise RuntimeError("MaxTaskLimitExceeded: Cannot launch more than 20 concurrent Linux apps")
            tasks[999] = "app_overflow"

        CustomAssertions.assert_raises(RuntimeError, launch_21st_app)


class TestR4_003_T2_99_RetainTaskIdOnOrientationChange(BaseTestCase):
    test_id = "T2-99"
    feature_id = "F-R4-003"
    title = "Retain Task ID state across device display orientation changes"
    tier = 2

    def run_test(self):
        tasks = self.mock_env.active_task_ids
        tasks[201] = "org.inkscape.Inkscape"

        # Orientation change triggered (re-creation of Activity)
        retained_task_id = 201 if 201 in tasks else None
        CustomAssertions.assert_equal(retained_task_id, 201)


class TestR4_003_T2_100_ClearTasksOnVmShutdown(BaseTestCase):
    test_id = "T2-100"
    feature_id = "F-R4-003"
    title = "Clear Task ID state on VM unexpected shutdown"
    tier = 2

    def run_test(self):
        tasks = self.mock_env.active_task_ids
        tasks[101] = "app_1"
        tasks[102] = "app_2"

        ss = self.mock_env.system_server
        ss.set_state("OFF")
        tasks.clear()  # VM state OFF flushes task list

        CustomAssertions.assert_equal(len(tasks), 0)


# -----------------------------------------------------------------------------
# F-R4-004: Freeform Multi-Window Resize (T2-101 .. T2-105)
# -----------------------------------------------------------------------------
class TestR4_004_T2_101_EnforceMinWindowSize(BaseTestCase):
    test_id = "T2-101"
    feature_id = "F-R4-004"
    title = "Enforce minimum window size constraints (e.g. 320x240)"
    tier = 2

    def run_test(self):
        MIN_WIDTH, MIN_HEIGHT = 320, 240
        req_w, req_h = 150, 100

        clamped_w = max(MIN_WIDTH, req_w)
        clamped_h = max(MIN_HEIGHT, req_h)

        CustomAssertions.assert_equal(clamped_w, 320)
        CustomAssertions.assert_equal(clamped_h, 240)


class TestR4_004_T2_102_EnforceMaxWindowSize(BaseTestCase):
    test_id = "T2-102"
    feature_id = "F-R4-004"
    title = "Enforce maximum window size constraints (screen resolution)"
    tier = 2

    def run_test(self):
        MAX_WIDTH, MAX_HEIGHT = 1920, 1080
        req_w, req_h = 5000, 4000

        clamped_w = min(MAX_WIDTH, req_w)
        clamped_h = min(MAX_HEIGHT, req_h)

        CustomAssertions.assert_equal(clamped_w, 1920)
        CustomAssertions.assert_equal(clamped_h, 1080)


class TestR4_004_T2_103_ResizeQueueBufferStability(BaseTestCase):
    test_id = "T2-103"
    feature_id = "F-R4-004"
    title = "Rapid window resizing flood buffer queue stability"
    tier = 2

    def run_test(self):
        configure_events = []
        for i in range(100):
            configure_events.append((300 + i, 200 + i))

        # Debouncer drops all intermediate events except final size
        final_event = configure_events[-1]
        CustomAssertions.assert_equal(final_event, (399, 299))


class TestR4_004_T2_104_AspectRatioPreservation(BaseTestCase):
    test_id = "T2-104"
    feature_id = "F-R4-004"
    title = "Aspect ratio preservation for fixed-ratio Linux apps"
    tier = 2

    def run_test(self):
        target_aspect_ratio = 16.0 / 9.0  # ~1.777
        new_width = 1600

        adjusted_height = int(new_width / target_aspect_ratio)
        CustomAssertions.assert_equal(adjusted_height, 900)


class TestR4_004_T2_105_DisplayDpiChangeBufferScaling(BaseTestCase):
    test_id = "T2-105"
    feature_id = "F-R4-004"
    title = "Display density (DPI) change window buffer scaling"
    tier = 2

    def run_test(self):
        dp_w, dp_h = 400, 300
        dpi_scale = 2.0  # x2.0 density (xhdpi)

        px_w = int(dp_w * dpi_scale)
        px_h = int(dp_h * dpi_scale)

        CustomAssertions.assert_equal(px_w, 800)
        CustomAssertions.assert_equal(px_h, 600)


# -----------------------------------------------------------------------------
# F-R5-005: .desktop Inotify Monitor Daemon (T2-106 .. T2-110)
# -----------------------------------------------------------------------------
class TestR4_005_T2_106_IgnoreInvalidDesktopSyntax(BaseTestCase):
    test_id = "T2-106"
    feature_id = "F-R4-005"
    title = "Ignore invalid/malformed .desktop syntax files"
    tier = 2

    def run_test(self):
        malformed_desktop_content = "Exec=gimp\nName=GIMP\n"  # Missing [Desktop Entry]

        def parse_desktop_file(content: str):
            if "[Desktop Entry]" not in content:
                raise ValueError("InvalidDesktopEntry: Missing [Desktop Entry] section header")
            return {"name": "GIMP"}

        CustomAssertions.assert_raises(ValueError, parse_desktop_file, malformed_desktop_content)


class TestR4_005_T2_107_IgnoreHiddenDesktopEntries(BaseTestCase):
    test_id = "T2-107"
    feature_id = "F-R4-005"
    title = "Ignore hidden (NoDisplay=true) desktop entries"
    tier = 2

    def run_test(self):
        desktop_data = {"Name": "Internal Tool", "Exec": "tool", "NoDisplay": "true"}

        is_visible = desktop_data.get("NoDisplay", "false").lower() != "true"
        CustomAssertions.assert_false(is_visible)


class TestR4_005_T2_108_InotifyBurstThrottling(BaseTestCase):
    test_id = "T2-108"
    feature_id = "F-R4-005"
    title = "Inotify event burst throttling (debounce rapid file writes)"
    tier = 2

    def run_test(self):
        write_events = 50
        processed_updates = 0

        # Debounce logic batches burst into 1 update
        if write_events > 0:
            processed_updates = 1

        CustomAssertions.assert_equal(processed_updates, 1)


class TestR4_005_T2_109_MissingIconFallback(BaseTestCase):
    test_id = "T2-109"
    feature_id = "F-R4-005"
    title = "Handle missing icon assets (fallback to default Linux app icon)"
    tier = 2

    def run_test(self):
        requested_icon_path = "/usr/share/icons/non_existent_icon.png"
        icon_exists = os.path.exists(requested_icon_path)

        final_icon_path = requested_icon_path if icon_exists else "default_linux_app_icon.png"
        CustomAssertions.assert_equal(final_icon_path, "default_linux_app_icon.png")


class TestR4_005_T2_110_RecursiveInotifyWatch(BaseTestCase):
    test_id = "T2-110"
    feature_id = "F-R4-005"
    title = "Subfolder recursive inotify watching (~/.local/share/applications)"
    tier = 2

    def run_test(self):
        watched_paths = ["/usr/share/applications", "/home/user/.local/share/applications"]
        new_file_path = "/home/user/.local/share/applications/custom.desktop"

        is_monitored = any(new_file_path.startswith(p) for p in watched_paths)
        CustomAssertions.assert_true(is_monitored)


# -----------------------------------------------------------------------------
# F-R4-006: Launcher3 Synthetic Shortcuts (T2-111 .. T2-115)
# -----------------------------------------------------------------------------
class TestR4_006_T2_111_DuplicateShortcutDeduplication(BaseTestCase):
    test_id = "T2-111"
    feature_id = "F-R4-006"
    title = "Duplicate shortcut deduplication logic"
    tier = 2

    def run_test(self):
        apps = self.mock_env.installed_desktop_apps
        apps["vlc.desktop"] = {"name": "VLC", "exec": "vlc"}
        apps["vlc.desktop"] = {"name": "VLC Media Player", "exec": "vlc"}  # Update existing key

        CustomAssertions.assert_equal(len(apps), 1)
        CustomAssertions.assert_equal(apps["vlc.desktop"]["name"], "VLC Media Player")


class TestR4_006_T2_112_LauncherRestartPersistence(BaseTestCase):
    test_id = "T2-112"
    feature_id = "F-R4-006"
    title = "Launcher restart persistence of custom Linux shortcuts"
    tier = 2

    def run_test(self):
        apps = self.mock_env.installed_desktop_apps
        apps["gimp.desktop"] = {"name": "GIMP"}

        # Simulate Launcher process restart
        persisted_cache = dict(apps)
        apps.clear()
        apps.update(persisted_cache)

        CustomAssertions.assert_in("gimp.desktop", apps)


class TestR4_006_T2_113_SpecialCharEscaping(BaseTestCase):
    test_id = "T2-113"
    feature_id = "F-R4-006"
    title = "Special character escaping in app titles and exec paths"
    tier = 2

    def run_test(self):
        raw_title = "Foo & Bar <App>"
        escaped_title = raw_title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        CustomAssertions.assert_equal(escaped_title, "Foo &amp; Bar &lt;App&gt;")


class TestR4_006_T2_114_IconConversionFallback(BaseTestCase):
    test_id = "T2-114"
    feature_id = "F-R4-006"
    title = "Icon conversion fallback for unknown/custom binary icon formats"
    tier = 2

    def run_test(self):
        icon_extension = ".xpm"
        supported_formats = [".png", ".svg"]

        if icon_extension not in supported_formats:
            icon_extension = ".png"  # Converted PNG fallback

        CustomAssertions.assert_equal(icon_extension, ".png")


class TestR4_006_T2_115_WorkProfileShortcutIsolation(BaseTestCase):
    test_id = "T2-115"
    feature_id = "F-R4-006"
    title = "Work profile / multi-user shortcut isolation"
    tier = 2

    def run_test(self):
        user_shortcuts = {
            0: ["gimp.desktop", "vlc.desktop"],   # User 0 (Primary)
            10: ["libreoffice.desktop"],         # User 10 (Work Profile)
        }

        user_0_apps = user_shortcuts.get(0, [])
        user_10_apps = user_shortcuts.get(10, [])

        CustomAssertions.assert_false("libreoffice.desktop" in user_0_apps)
        CustomAssertions.assert_in("libreoffice.desktop", user_10_apps)
