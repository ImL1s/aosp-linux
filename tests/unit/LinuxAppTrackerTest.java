/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tests.unit;

import android.system.linux.LinuxAppInfo;
import com.android.launcher3.linux.LinuxAppTracker;

import java.util.ArrayList;
import java.util.List;

public class LinuxAppTrackerTest {

    public static void testSyntheticShortcutGenerationAndDeduplication() {
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        List<LinuxAppInfo> apps = new ArrayList<>();
        apps.add(new LinuxAppInfo("org.gnome.Terminal", "Terminal", "gnome-terminal", "/usr/share/icons/terminal.png", "text/plain"));
        apps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox", "firefox", "/usr/share/icons/firefox.png", "text/html"));

        tracker.updateShortcutsFromList(apps, 0);
        assert tracker.getShortcutCount() == 2 : "Expected 2 shortcuts";

        // Update existing app metadata (deduplication check)
        List<LinuxAppInfo> updatedApps = new ArrayList<>();
        updatedApps.add(new LinuxAppInfo("org.gnome.Terminal", "Terminal Pro", "gnome-terminal --pro", "/usr/share/icons/terminal_pro.png", "text/plain"));
        updatedApps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox", "firefox", "/usr/share/icons/firefox.png", "text/html"));

        tracker.updateShortcutsFromList(updatedApps, 0);
        assert tracker.getShortcutCount() == 2 : "Deduplication failed: shortcut count should still be 2";
        assert tracker.getShortcut("org.gnome.Terminal").title.equals("Terminal Pro") : "Title update failed";

        System.out.println("[PASS] testSyntheticShortcutGenerationAndDeduplication");
    }

    public static void testShortcutRemovalOnUninstall() {
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        List<LinuxAppInfo> apps = new ArrayList<>();
        apps.add(new LinuxAppInfo("org.gimp.Gimp", "GIMP", "gimp", "/icons/gimp.png", "image/png"));
        apps.add(new LinuxAppInfo("org.vlc.Vlc", "VLC Player", "vlc", "/icons/vlc.png", "video/mp4"));
        tracker.updateShortcutsFromList(apps, 0);
        assert tracker.getShortcutCount() == 2;

        // Uninstall VLC (remove from list)
        List<LinuxAppInfo> appsAfterUninstall = new ArrayList<>();
        appsAfterUninstall.add(new LinuxAppInfo("org.gimp.Gimp", "GIMP", "gimp", "/icons/gimp.png", "image/png"));
        tracker.updateShortcutsFromList(appsAfterUninstall, 0);

        assert tracker.getShortcutCount() == 1 : "Expected 1 shortcut after uninstall";
        assert tracker.getShortcut("org.vlc.Vlc") == null : "VLC shortcut should be removed";
        System.out.println("[PASS] testShortcutRemovalOnUninstall");
    }

    public static void testSpecialCharacterXmlEscaping() {
        String inputTitle = "R&D App <Alpha & Beta> \"Special\" 'Edition'";
        String escaped = LinuxAppTracker.escapeXml(inputTitle);

        assert escaped.equals("R&amp;D App &lt;Alpha &amp; Beta&gt; &quot;Special&quot; &apos;Edition&apos;")
                : "XML escaping failed: " + escaped;
        System.out.println("[PASS] testSpecialCharacterXmlEscaping");
    }

    public static void testUnsupportedIconFormatFallback() {
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        List<LinuxAppInfo> apps = new ArrayList<>();
        apps.add(new LinuxAppInfo("app.legacy.xpm", "Legacy App", "legacy", "/usr/share/pixmaps/legacy.xpm", "text/plain"));
        tracker.updateShortcutsFromList(apps, 0);

        LinuxAppTracker.SyntheticShortcut shortcut = tracker.getShortcut("app.legacy.xpm");
        assert shortcut != null;
        assert shortcut.iconBitmap != null : "Fallback bitmap should be created for unsupported .xpm format";
        System.out.println("[PASS] testUnsupportedIconFormatFallback");
    }

    public static void testMultiUserIsolation() {
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        List<LinuxAppInfo> user0Apps = new ArrayList<>();
        user0Apps.add(new LinuxAppInfo("app.user0", "User 0 App", "user0", "/icon.png", ""));
        tracker.updateShortcutsFromList(user0Apps, 0);

        List<LinuxAppInfo> user10Apps = new ArrayList<>();
        user10Apps.add(new LinuxAppInfo("app.user10", "Work Profile App", "work", "/icon.png", ""));
        tracker.updateShortcutsFromList(user10Apps, 10);

        assert tracker.getShortcutsForUser(0).size() == 1;
        assert tracker.getShortcutsForUser(10).size() == 1;
        assert tracker.getShortcutsForUser(0).get(0).appId.equals("app.user0");
        assert tracker.getShortcutsForUser(10).get(0).appId.equals("app.user10");

        System.out.println("[PASS] testMultiUserIsolation");
    }

    public static void main(String[] args) {
        System.out.println("Running LinuxAppTrackerTest unit tests...");
        testSyntheticShortcutGenerationAndDeduplication();
        testShortcutRemovalOnUninstall();
        testSpecialCharacterXmlEscaping();
        testUnsupportedIconFormatFallback();
        testMultiUserIsolation();
        System.out.println("ALL LinuxAppTrackerTest UNIT TESTS PASSED!");
    }
}
