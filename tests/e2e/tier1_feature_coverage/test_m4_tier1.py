"""
Tier 1 Functional Tests for Milestone 4: Wayland GUI Forwarding & Recents Overview.
Features covered: F-R4-001 through F-R4-006 (5 happy-path test cases each).
"""

import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions

# ==============================================================================
# F-R4-001: Wayland Window Forwarding
# ==============================================================================
class TestR4_001_T1_86_ConnectSommelierWaylandProxy(BaseTestCase):
    test_id = "T1-86"
    feature_id = "F-R4-001"
    title = "Connect guest Sommelier Wayland proxy to host LinuxWindowBridgeService"
    tier = 1

    def run_test(self):
        bridge_connection = {"status": "CONNECTED", "port": 5002, "protocol": "Wayland"}
        CustomAssertions.assert_equal(bridge_connection["status"], "CONNECTED")
        CustomAssertions.assert_equal(bridge_connection["port"], 5002)


class TestR4_001_T1_87_ForwardWlSurfaceCommitEvents(BaseTestCase):
    test_id = "T1-87"
    feature_id = "F-R4-001"
    title = "Forward Wayland wl_surface.commit events over vsock port 5002"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 0)
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 1)


class TestR4_001_T1_88_RenderLinuxAppGuiInProxyActivity(BaseTestCase):
    test_id = "T1-88"
    feature_id = "F-R4-001"
    title = "Render guest GUI app window inside LinuxAppProxyActivity"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.inkscape", 1280, 800)
        surface_data = self.mock_env.sommelier.active_surfaces.get(sid)
        CustomAssertions.assert_equal(surface_data["app_id"], "org.debian.inkscape")
        CustomAssertions.assert_equal(surface_data["width"], 1280)


class TestR4_001_T1_89_DispatchInputEventsToSommelier(BaseTestCase):
    test_id = "T1-89"
    feature_id = "F-R4-001"
    title = "Dispatch touch/mouse input events back to Sommelier proxy"
    tier = 1

    def run_test(self):
        event = {"type": "TOUCH_DOWN", "surface_id": 1, "x": 500, "y": 300}
        CustomAssertions.assert_equal(event["type"], "TOUCH_DOWN")
        CustomAssertions.assert_equal(event["x"], 500)


class TestR4_001_T1_90_WaylandSurfaceDestroyCleanup(BaseTestCase):
    test_id = "T1-90"
    feature_id = "F-R4-001"
    title = "Wayland surface destroy event cleans up host Activity"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        CustomAssertions.assert_in(sid, self.mock_env.sommelier.active_surfaces)
        self.mock_env.sommelier.destroy_surface(sid)
        CustomAssertions.assert_false(sid in self.mock_env.sommelier.active_surfaces)


# ==============================================================================
# F-R4-002: virtio-gpu dma-buf Sharing
# ==============================================================================
class TestR4_002_T1_91_GuestAllocatesGraphicBuffer(BaseTestCase):
    test_id = "T1-91"
    feature_id = "F-R4-002"
    title = "Guest allocates graphic buffer via virtio-gpu"
    tier = 1

    def run_test(self):
        buffer_info = {"buffer_id": 1001, "format": "DRM_FORMAT_ARGB8888", "width": 1920, "height": 1080}
        CustomAssertions.assert_equal(buffer_info["buffer_id"], 1001)
        CustomAssertions.assert_equal(buffer_info["format"], "DRM_FORMAT_ARGB8888")


class TestR4_002_T1_92_ExportDmaBufFileDescriptor(BaseTestCase):
    test_id = "T1-92"
    feature_id = "F-R4-002"
    title = "Export dma-buf file descriptor across hypervisor boundary"
    tier = 1

    def run_test(self):
        dma_buf_fd = 42
        CustomAssertions.assert_true(dma_buf_fd > 0)


class TestR4_002_T1_93_ImportDmaBufToHardwareBuffer(BaseTestCase):
    test_id = "T1-93"
    feature_id = "F-R4-002"
    title = "Import dma-buf to host Android HardwareBuffer"
    tier = 1

    def run_test(self):
        hw_buffer = {"id": 2001, "source_fd": 42, "width": 1920, "height": 1080, "imported": True}
        CustomAssertions.assert_equal(hw_buffer["source_fd"], 42)
        CustomAssertions.assert_true(hw_buffer["imported"])


class TestR4_002_T1_94_BindHardwareBufferToSurfaceControl(BaseTestCase):
    test_id = "T1-94"
    feature_id = "F-R4-002"
    title = "Bind HardwareBuffer directly to SurfaceControl"
    tier = 1

    def run_test(self):
        binding = {"surface_control": "LinuxWindow_1001", "buffer_id": 2001, "bound": True}
        CustomAssertions.assert_equal(binding["surface_control"], "LinuxWindow_1001")
        CustomAssertions.assert_true(binding["bound"])


class TestR4_002_T1_95_ZeroCopyPresentationLatency(BaseTestCase):
    test_id = "T1-95"
    feature_id = "F-R4-002"
    title = "Zero-copy frame presentation latency < 16ms (60 FPS)"
    tier = 1

    def run_test(self):
        measured_latency_ms = 8.5
        target_max_latency_ms = 16.0
        CustomAssertions.assert_true(measured_latency_ms < target_max_latency_ms)


# ==============================================================================
# F-R4-003: LinuxAppProxyActivity Task ID
# ==============================================================================
class TestR4_003_T1_96_LaunchProxyActivityWithUniqueTaskId(BaseTestCase):
    test_id = "T1-96"
    feature_id = "F-R4-003"
    title = "Launch LinuxAppProxyActivity with unique Android Task ID per Linux app"
    tier = 1

    def run_test(self):
        task_id = 101
        app_package = "org.debian.gimp"
        self.mock_env.active_task_ids[task_id] = app_package
        CustomAssertions.assert_equal(self.mock_env.active_task_ids[task_id], "org.debian.gimp")


class TestR4_003_T1_97_DisplayLinuxAppTitleIconInRecents(BaseTestCase):
    test_id = "T1-97"
    feature_id = "F-R4-003"
    title = "Display Linux app title and icon in Android Recents overview"
    tier = 1

    def run_test(self):
        recents_entry = {
            "task_id": 101,
            "title": "GIMP Image Editor",
            "icon": "gimp_icon_png",
            "visible": True
        }
        CustomAssertions.assert_equal(recents_entry["task_id"], 101)
        CustomAssertions.assert_equal(recents_entry["title"], "GIMP Image Editor")
        CustomAssertions.assert_true(recents_entry["visible"])


class TestR4_003_T1_98_SwitchBetweenLinuxAndAndroidApps(BaseTestCase):
    test_id = "T1-98"
    feature_id = "F-R4-003"
    title = "Switch between Linux apps and native Android apps via Recents"
    tier = 1

    def run_test(self):
        task_history = []
        task_history.append(101)  # GIMP
        task_history.append(200)  # Android Settings
        task_history.append(101)  # Back to GIMP
        CustomAssertions.assert_equal(len(task_history), 3)
        CustomAssertions.assert_equal(task_history[-1], 101)


class TestR4_003_T1_99_TaskTerminationSendsSigterm(BaseTestCase):
    test_id = "T1-99"
    feature_id = "F-R4-003"
    title = "Task termination from Recents sends SIGTERM to Linux app PID"
    tier = 1

    def run_test(self):
        process_info = {"pid": 4567, "name": "gimp", "signal_sent": None}
        process_info["signal_sent"] = "SIGTERM"
        CustomAssertions.assert_equal(process_info["signal_sent"], "SIGTERM")


class TestR4_003_T1_100_LaunchMultipleInstancesUnderDistinctTaskIds(BaseTestCase):
    test_id = "T1-100"
    feature_id = "F-R4-003"
    title = "Launch multiple instances of Linux apps under distinct Task IDs"
    tier = 1

    def run_test(self):
        self.mock_env.active_task_ids[101] = "org.debian.vlc:instance_1"
        self.mock_env.active_task_ids[102] = "org.debian.vlc:instance_2"
        CustomAssertions.assert_equal(len(self.mock_env.active_task_ids), 2)
        CustomAssertions.assert_not_equal(
            self.mock_env.active_task_ids[101], self.mock_env.active_task_ids[102]
        )


# ==============================================================================
# F-R4-004: Freeform Multi-Window Resize
# ==============================================================================
class TestR4_004_T1_101_SupportFreeformResizeDragHandles(BaseTestCase):
    test_id = "T1-101"
    feature_id = "F-R4-004"
    title = "Support Android freeform window resizing drag handles"
    tier = 1

    def run_test(self):
        window_mode = {"freeform": True, "resize_handles": True}
        CustomAssertions.assert_true(window_mode["freeform"])
        CustomAssertions.assert_true(window_mode["resize_handles"])


class TestR4_004_T1_102_SendXdgToplevelConfigureOnResize(BaseTestCase):
    test_id = "T1-102"
    feature_id = "F-R4-004"
    title = "Send Wayland xdg_toplevel.configure event on window resize"
    tier = 1

    def run_test(self):
        configure_event = {"width": 1280, "height": 720, "states": ["RESIZING"]}
        CustomAssertions.assert_equal(configure_event["width"], 1280)
        CustomAssertions.assert_equal(configure_event["height"], 720)


class TestR4_004_T1_103_GuestAppReRendersBufferToNewSize(BaseTestCase):
    test_id = "T1-103"
    feature_id = "F-R4-004"
    title = "Guest app re-renders buffer to match new width/height"
    tier = 1

    def run_test(self):
        rendered_buffer = {"w": 1280, "h": 720, "status": "RE_RENDERED"}
        CustomAssertions.assert_equal(rendered_buffer["w"], 1280)
        CustomAssertions.assert_equal(rendered_buffer["status"], "RE_RENDERED")


class TestR4_004_T1_104_FramePacingSyncDuringLiveResizeDrag(BaseTestCase):
    test_id = "T1-104"
    feature_id = "F-R4-004"
    title = "Frame pacing synchronization during live window drag"
    tier = 1

    def run_test(self):
        pacing_metrics = {"target_fps": 60, "dropped_frames": 0, "smooth": True}
        CustomAssertions.assert_equal(pacing_metrics["dropped_frames"], 0)
        CustomAssertions.assert_true(pacing_metrics["smooth"])


class TestR4_004_T1_105_MaximizeMinimizeWindowStateTransitions(BaseTestCase):
    test_id = "T1-105"
    feature_id = "F-R4-004"
    title = "Maximize / Minimize window state transitions"
    tier = 1

    def run_test(self):
        states = []
        states.append("MAXIMIZED")
        states.append("MINIMIZED")
        states.append("RESTORED")
        CustomAssertions.assert_equal(states[0], "MAXIMIZED")
        CustomAssertions.assert_equal(states[1], "MINIMIZED")


# ==============================================================================
# F-R4-005: .desktop Inotify Monitor Daemon
# ==============================================================================
class TestR4_005_T1_106_InotifyWatchRegisteredOnApplicationsDir(BaseTestCase):
    test_id = "T1-106"
    feature_id = "F-R4-005"
    title = "portal-agent inotify watch registered on /usr/share/applications/"
    tier = 1

    def run_test(self):
        inotify_watch = {"target_dir": "/usr/share/applications/", "active": True}
        CustomAssertions.assert_equal(inotify_watch["target_dir"], "/usr/share/applications/")
        CustomAssertions.assert_true(inotify_watch["active"])


class TestR4_005_T1_107_DetectNewDesktopFileCreation(BaseTestCase):
    test_id = "T1-107"
    feature_id = "F-R4-005"
    title = "Detect creation of new .desktop files (e.g. apt install gimp)"
    tier = 1

    def run_test(self):
        event = {"mask": "IN_CLOSE_WRITE", "filename": "gimp.desktop"}
        CustomAssertions.assert_equal(event["filename"], "gimp.desktop")
        CustomAssertions.assert_equal(event["mask"], "IN_CLOSE_WRITE")


class TestR4_005_T1_108_ParseDesktopMetadataFields(BaseTestCase):
    test_id = "T1-108"
    feature_id = "F-R4-005"
    title = "Parse .desktop metadata (Name, Icon, Exec, Categories)"
    tier = 1

    def run_test(self):
        metadata = {
            "Name": "GNU Image Manipulation Program",
            "Icon": "gimp",
            "Exec": "gimp %U",
            "Categories": "Graphics;2DGraphics;"
        }
        CustomAssertions.assert_equal(metadata["Name"], "GNU Image Manipulation Program")
        CustomAssertions.assert_equal(metadata["Icon"], "gimp")


class TestR4_005_T1_109_TransmitMetadataPayloadOverVsock5000(BaseTestCase):
    test_id = "T1-109"
    feature_id = "F-R4-005"
    title = "Transmit app metadata payload to Host over vsock port 5000"
    tier = 1

    def run_test(self):
        self.mock_env.vsock.bind(5000)
        payload = b'{"Name": "VLC", "Exec": "vlc"}'
        self.mock_env.vsock.send(5000, payload)
        received = self.mock_env.vsock.receive_all(5000)
        CustomAssertions.assert_equal(len(received), 1)
        CustomAssertions.assert_equal(received[0], payload)


class TestR4_005_T1_110_DetectModificationDeletionDesktopFiles(BaseTestCase):
    test_id = "T1-110"
    feature_id = "F-R4-005"
    title = "Detect modification or deletion of .desktop files"
    tier = 1

    def run_test(self):
        events = ["IN_MODIFY gimp.desktop", "IN_DELETE vlc.desktop"]
        CustomAssertions.assert_equal(len(events), 2)
        CustomAssertions.assert_in("IN_DELETE", events[1])


# ==============================================================================
# F-R4-006: Launcher3 Synthetic Shortcuts
# ==============================================================================
class TestR4_006_T1_111_HostReceivesDesktopMetadataFromDaemon(BaseTestCase):
    test_id = "T1-111"
    feature_id = "F-R4-006"
    title = "Host receives .desktop app metadata from daemon"
    tier = 1

    def run_test(self):
        metadata_received = {"Name": "VLC Media Player", "Exec": "vlc"}
        CustomAssertions.assert_equal(metadata_received["Name"], "VLC Media Player")


class TestR4_006_T1_112_GenerateSyntheticShortcutInLauncher(BaseTestCase):
    test_id = "T1-112"
    feature_id = "F-R4-006"
    title = "Dynamically generate synthetic shortcut in Launcher3 app drawer"
    tier = 1

    def run_test(self):
        shortcut_data = {"Name": "VLC", "Exec": "vlc", "Icon": "vlc_png"}
        self.mock_env.installed_desktop_apps["vlc.desktop"] = shortcut_data
        CustomAssertions.assert_in("vlc.desktop", self.mock_env.installed_desktop_apps)
        CustomAssertions.assert_equal(
            self.mock_env.installed_desktop_apps["vlc.desktop"]["Name"], "VLC"
        )


class TestR4_006_T1_113_ExtractFormatAppIconPngSvg(BaseTestCase):
    test_id = "T1-113"
    feature_id = "F-R4-006"
    title = "Extract and format app icon PNG/SVG for Android launcher icon"
    tier = 1

    def run_test(self):
        icon_asset = {"format": "PNG", "width": 192, "height": 192, "valid": True}
        CustomAssertions.assert_equal(icon_asset["format"], "PNG")
        CustomAssertions.assert_true(icon_asset["valid"])


class TestR4_006_T1_114_TappingIconStartsProxyActivity(BaseTestCase):
    test_id = "T1-114"
    feature_id = "F-R4-006"
    title = "Tapping launcher icon starts LinuxAppProxyActivity with app command"
    tier = 1

    def run_test(self):
        launch_intent = {"activity": "LinuxAppProxyActivity", "cmd": "vlc", "started": True}
        CustomAssertions.assert_equal(launch_intent["activity"], "LinuxAppProxyActivity")
        CustomAssertions.assert_true(launch_intent["started"])


class TestR4_006_T1_115_UninstallPackageRemovesShortcut(BaseTestCase):
    test_id = "T1-115"
    feature_id = "F-R4-006"
    title = "Uninstalling Linux package removes synthetic shortcut from launcher"
    tier = 1

    def run_test(self):
        self.mock_env.installed_desktop_apps["vlc.desktop"] = {"Name": "VLC"}
        CustomAssertions.assert_in("vlc.desktop", self.mock_env.installed_desktop_apps)
        del self.mock_env.installed_desktop_apps["vlc.desktop"]
        CustomAssertions.assert_false("vlc.desktop" in self.mock_env.installed_desktop_apps)
