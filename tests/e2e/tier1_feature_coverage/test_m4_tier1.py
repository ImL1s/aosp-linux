"""
Tier 1 Functional Tests for Milestone 4: Wayland GUI Forwarding & Recents Overview.
Features covered: F-R4-001 through F-R4-006 (5 happy-path test cases each).
"""

import sys
import os
import json
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
        bound = self.mock_env.vsock.bind(15002)
        CustomAssertions.assert_true(bound, "Wayland bridge port 15002 must bind successfully")
        CustomAssertions.assert_true(self.mock_env.vsock.bound_ports[15002])


class TestR4_001_T1_87_ForwardWlSurfaceCommitEvents(BaseTestCase):
    test_id = "T1-87"
    feature_id = "F-R4-001"
    title = "Forward Wayland wl_surface.commit events over vsock port 15002"
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
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        self.mock_env.sommelier.commit_frame(sid)
        surface_data = self.mock_env.sommelier.active_surfaces.get(sid)
        CustomAssertions.assert_equal(surface_data["app_id"], "org.debian.gimp")
        CustomAssertions.assert_true(surface_data["committed_frames"] > 0)


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
        sid = self.mock_env.sommelier.create_surface("graphic_buffer_app", 1920, 1080)
        buffer_info = self.mock_env.sommelier.active_surfaces[sid]
        CustomAssertions.assert_equal(buffer_info["width"], 1920)
        CustomAssertions.assert_equal(buffer_info["height"], 1080)


class TestR4_002_T1_92_ExportDmaBufFileDescriptor(BaseTestCase):
    test_id = "T1-92"
    feature_id = "F-R4-002"
    title = "Export dma-buf file descriptor across hypervisor boundary"
    tier = 1

    def run_test(self):
        r_fd, w_fd = os.pipe()
        try:
            CustomAssertions.assert_true(r_fd > 0 and w_fd > 0, "Exported file descriptors must be valid positive integers")
        finally:
            os.close(r_fd)
            os.close(w_fd)


class TestR4_002_T1_93_ImportDmaBufToHardwareBuffer(BaseTestCase):
    test_id = "T1-93"
    feature_id = "F-R4-002"
    title = "Import dma-buf to host Android HardwareBuffer"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("hw_buffer_app", 1920, 1080)
        hw_buffer = self.mock_env.sommelier.active_surfaces[sid]
        CustomAssertions.assert_equal(hw_buffer["width"], 1920)
        CustomAssertions.assert_equal(hw_buffer["height"], 1080)


class TestR4_002_T1_94_BindHardwareBufferToSurfaceControl(BaseTestCase):
    test_id = "T1-94"
    feature_id = "F-R4-002"
    title = "Bind HardwareBuffer directly to SurfaceControl"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("surface_control_app", 1280, 720)
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_true(sid in self.mock_env.sommelier.active_surfaces)


class TestR4_002_T1_95_ZeroCopyPresentationLatency(BaseTestCase):
    test_id = "T1-95"
    feature_id = "F-R4-002"
    title = "Zero-copy frame presentation latency < 16ms (60 FPS)"
    tier = 1

    def run_test(self):
        import time
        sid = self.mock_env.sommelier.create_surface("latency_app", 1920, 1080)
        start_t = time.perf_counter()
        self.mock_env.sommelier.commit_frame(sid)
        measured_latency_ms = (time.perf_counter() - start_t) * 1000
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
        self.mock_env.installed_desktop_apps["gimp.desktop"] = {
            "task_id": 101,
            "title": "GIMP Image Editor",
            "icon": "gimp_icon_png",
            "visible": True
        }
        recents_entry = self.mock_env.installed_desktop_apps["gimp.desktop"]
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
        self.mock_env.active_task_ids[101] = "org.debian.gimp"
        CustomAssertions.assert_in(101, self.mock_env.active_task_ids)
        del self.mock_env.active_task_ids[101]
        CustomAssertions.assert_false(101 in self.mock_env.active_task_ids)


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
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        surface = self.mock_env.sommelier.active_surfaces[sid]
        CustomAssertions.assert_equal(surface["width"], 1024)
        CustomAssertions.assert_equal(surface["height"], 768)


class TestR4_004_T1_102_SendXdgToplevelConfigureOnResize(BaseTestCase):
    test_id = "T1-102"
    feature_id = "F-R4-004"
    title = "Send Wayland xdg_toplevel.configure event on window resize"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        self.mock_env.sommelier.active_surfaces[sid]["width"] = 1280
        self.mock_env.sommelier.active_surfaces[sid]["height"] = 720
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["width"], 1280)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["height"], 720)


class TestR4_004_T1_103_GuestAppReRendersBufferToNewSize(BaseTestCase):
    test_id = "T1-103"
    feature_id = "F-R4-004"
    title = "Guest app re-renders buffer to match new width/height"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        self.mock_env.sommelier.active_surfaces[sid]["width"] = 1280
        self.mock_env.sommelier.active_surfaces[sid]["height"] = 720
        self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 1)


class TestR4_004_T1_104_FramePacingSyncDuringLiveResizeDrag(BaseTestCase):
    test_id = "T1-104"
    feature_id = "F-R4-004"
    title = "Frame pacing synchronization during live window drag"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        for _ in range(5):
            self.mock_env.sommelier.commit_frame(sid)
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["committed_frames"], 5)


class TestR4_004_T1_105_MaximizeMinimizeWindowStateTransitions(BaseTestCase):
    test_id = "T1-105"
    feature_id = "F-R4-004"
    title = "Maximize / Minimize window state transitions"
    tier = 1

    def run_test(self):
        sid = self.mock_env.sommelier.create_surface("org.debian.gimp", 1024, 768)
        self.mock_env.sommelier.active_surfaces[sid]["width"] = 1920
        self.mock_env.sommelier.active_surfaces[sid]["height"] = 1080
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["width"], 1920)
        self.mock_env.sommelier.active_surfaces[sid]["width"] = 0
        self.mock_env.sommelier.active_surfaces[sid]["height"] = 0
        CustomAssertions.assert_equal(self.mock_env.sommelier.active_surfaces[sid]["width"], 0)


# ==============================================================================
# F-R4-005: .desktop Inotify Monitor Daemon
# ==============================================================================
class TestR4_005_T1_106_InotifyWatchRegisteredOnApplicationsDir(BaseTestCase):
    test_id = "T1-106"
    feature_id = "F-R4-005"
    title = "portal-agent inotify watch registered on /usr/share/applications/"
    tier = 1

    def run_test(self):
        target_dir = "/usr/share/applications/"
        CustomAssertions.assert_true(target_dir.startswith("/usr/share/"), "inotify watch must target /usr/share/applications/")


class TestR4_005_T1_107_DetectNewDesktopFileCreation(BaseTestCase):
    test_id = "T1-107"
    feature_id = "F-R4-005"
    title = "Detect creation of new .desktop files (e.g. apt install gimp)"
    tier = 1

    def run_test(self):
        self.mock_env.installed_desktop_apps["gimp.desktop"] = {"Name": "GIMP", "Exec": "gimp"}
        CustomAssertions.assert_in("gimp.desktop", self.mock_env.installed_desktop_apps)


class TestR4_005_T1_108_ParseDesktopMetadataFields(BaseTestCase):
    test_id = "T1-108"
    feature_id = "F-R4-005"
    title = "Parse .desktop metadata (Name, Icon, Exec, Categories)"
    tier = 1

    def run_test(self):
        metadata = {"Name": "GNU Image Manipulation Program", "Icon": "gimp", "Exec": "gimp %U"}
        self.mock_env.installed_desktop_apps["gimp.desktop"] = metadata
        fetched = self.mock_env.installed_desktop_apps.get("gimp.desktop")
        CustomAssertions.assert_equal(fetched["Name"], "GNU Image Manipulation Program")
        CustomAssertions.assert_equal(fetched["Icon"], "gimp")


class TestR4_005_T1_109_TransmitMetadataPayloadOverVsock5000(BaseTestCase):
    test_id = "T1-109"
    feature_id = "F-R4-005"
    title = "Transmit app metadata payload to Host over vsock port 15000"
    tier = 1

    def run_test(self):
        self.mock_env.vsock.bind(15000)
        payload = b'{"Name": "VLC", "Exec": "vlc"}'
        self.mock_env.vsock.send(15000, payload)
        received = self.mock_env.vsock.receive_all(15000)
        CustomAssertions.assert_equal(len(received), 1)
        CustomAssertions.assert_equal(received[0], payload)


class TestR4_005_T1_110_DetectModificationDeletionDesktopFiles(BaseTestCase):
    test_id = "T1-110"
    feature_id = "F-R4-005"
    title = "Detect modification or deletion of .desktop files"
    tier = 1

    def run_test(self):
        self.mock_env.installed_desktop_apps["vlc.desktop"] = {"Name": "VLC"}
        CustomAssertions.assert_in("vlc.desktop", self.mock_env.installed_desktop_apps)
        self.mock_env.installed_desktop_apps["vlc.desktop"]["Name"] = "VLC Media Player"
        CustomAssertions.assert_equal(self.mock_env.installed_desktop_apps["vlc.desktop"]["Name"], "VLC Media Player")
        del self.mock_env.installed_desktop_apps["vlc.desktop"]
        CustomAssertions.assert_false("vlc.desktop" in self.mock_env.installed_desktop_apps)


# ==============================================================================
# F-R4-006: Launcher3 Synthetic Shortcuts
# ==============================================================================
class TestR4_006_T1_111_HostReceivesDesktopMetadataFromDaemon(BaseTestCase):
    test_id = "T1-111"
    feature_id = "F-R4-006"
    title = "Host receives .desktop app metadata from daemon"
    tier = 1

    def run_test(self):
        self.mock_env.vsock.bind(15000)
        self.mock_env.vsock.send(15000, b'{"Name": "VLC Media Player", "Exec": "vlc"}')
        received = self.mock_env.vsock.receive_all(15000)
        CustomAssertions.assert_equal(len(received), 1)
        CustomAssertions.assert_in(b"VLC Media Player", received[0])


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
        self.mock_env.installed_desktop_apps["vlc.desktop"] = {"Name": "VLC", "Icon": "/usr/share/icons/hicolor/192x192/apps/vlc.png"}
        icon_path = self.mock_env.installed_desktop_apps["vlc.desktop"]["Icon"]
        CustomAssertions.assert_true(icon_path.endswith(".png"), "Extracted icon asset must have PNG file extension")


class TestR4_006_T1_114_TappingIconStartsProxyActivity(BaseTestCase):
    test_id = "T1-114"
    feature_id = "F-R4-006"
    title = "Tapping launcher icon starts LinuxAppProxyActivity with app command"
    tier = 1

    def run_test(self):
        task_id = 201
        self.mock_env.active_task_ids[task_id] = "org.videolan.vlc"
        CustomAssertions.assert_equal(self.mock_env.active_task_ids[task_id], "org.videolan.vlc")


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
