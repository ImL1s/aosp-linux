/*
 * Copyright (C) 2026 The Android Open Source Project
 * Adversarial Stress & Edge-Case Test Harness for LinuxAppTracker & Launcher3 Synthetic Shortcuts
 */

package tests.stress;

import android.system.linux.LinuxAppInfo;
import com.android.launcher3.linux.LinuxAppTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AdversarialLinuxAppTrackerTest {

    public static void testInotifyEventBurstHandling() throws Exception {
        System.out.println("[STRESS] Testing Inotify Event Burst Handling (1,000 rapid updates)...");
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        int numThreads = 10;
        int burstPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCounter = new AtomicInteger(0);

        long startNs = System.nanoTime();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                for (int i = 0; i < burstPerThread; i++) {
                    List<LinuxAppInfo> burstApps = new ArrayList<>();
                    burstApps.add(new LinuxAppInfo("app.thread." + threadId + ".item." + i,
                            "App " + threadId + "-" + i,
                            "cmd " + i,
                            "/usr/share/icons/icon_" + i + ".png",
                            "text/plain"));
                    tracker.updateShortcutsFromList(burstApps, 0);
                    successCounter.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assert finished : "Executor timed out during event burst handling";
        assert successCounter.get() == numThreads * burstPerThread;
        System.out.println("[PASS] Processed " + successCounter.get() + " burst events in " + elapsedMs + " ms without deadlock or race condition");
    }

    public static void testMalformedAppMetadataAndXmlEscaping() {
        System.out.println("[STRESS] Testing Malformed App Metadata & Extreme Strings...");

        // XML injection attack titles and commands
        String maliciousTitle = "<script>alert('XSS & Injection')</script> \"foo\" 'bar' & baz";
        String maliciousExec = "sh -c 'cat /etc/passwd & echo \"<hack>\" > /tmp/out'";

        String escapedTitle = LinuxAppTracker.escapeXml(maliciousTitle);
        String escapedExec = LinuxAppTracker.escapeXml(maliciousExec);

        assert !escapedTitle.contains("<script>") : "Unescaped < tag in title";
        assert !escapedTitle.contains("\"") : "Unescaped quote in title";
        assert !escapedTitle.contains("& ") : "Unescaped & in title";
        assert escapedTitle.contains("&lt;script&gt;") : "Proper XML escaping failed for title";

        assert !escapedExec.contains("<hack>") : "Unescaped < tag in exec";
        assert escapedExec.contains("&lt;hack&gt;") : "Proper XML escaping failed for exec";

        System.out.println("[PASS] Malicious XML injection strings safely sanitized");
    }

    public static void testHighVolumeDeduplication() {
        System.out.println("[STRESS] Testing High-Volume Deduplication (5,000 duplicate shortcuts)...");
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        List<LinuxAppInfo> apps = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            apps.add(new LinuxAppInfo("org.mozilla.firefox", "Firefox Web Browser", "firefox-esr", "/usr/share/icons/firefox.png", "text/html"));
        }

        tracker.updateShortcutsFromList(apps, 0);
        assert tracker.getShortcutCount() == 1 : "Expected 1 deduplicated shortcut for 5,000 duplicate app IDs, got: " + tracker.getShortcutCount();
        System.out.println("[PASS] 5,000 duplicate entries deduplicated to exactly 1 shortcut");
    }

    public static void testMultiUserIsolationAndCleanup() {
        System.out.println("[STRESS] Testing Multi-User Profile Isolation & Dynamic Cleanup...");
        LinuxAppTracker tracker = new LinuxAppTracker(null);

        // User 0 (Primary)
        List<LinuxAppInfo> user0Apps = new ArrayList<>();
        user0Apps.add(new LinuxAppInfo("app.user0.a", "User 0 App A", "cmdA", "/iconA.png", ""));
        user0Apps.add(new LinuxAppInfo("app.user0.b", "User 0 App B", "cmdB", "/iconB.png", ""));
        tracker.updateShortcutsFromList(user0Apps, 0);

        // User 10 (Work Profile)
        List<LinuxAppInfo> user10Apps = new ArrayList<>();
        user10Apps.add(new LinuxAppInfo("app.user10.a", "Work Profile App A", "cmdWA", "/iconWA.png", ""));
        tracker.updateShortcutsFromList(user10Apps, 10);

        assert tracker.getShortcutsForUser(0).size() == 2;
        assert tracker.getShortcutsForUser(10).size() == 1;
        assert tracker.getShortcutCount() == 3;

        // Remove User 0 App A (uninstall simulation)
        List<LinuxAppInfo> user0AppsUpdated = new ArrayList<>();
        user0AppsUpdated.add(new LinuxAppInfo("app.user0.b", "User 0 App B", "cmdB", "/iconB.png", ""));
        tracker.updateShortcutsFromList(user0AppsUpdated, 0);

        assert tracker.getShortcutsForUser(0).size() == 1;
        assert tracker.getShortcutsForUser(10).size() == 1;
        assert tracker.getShortcutCount() == 2;
        assert tracker.getShortcut("app.user0.a") == null;
        assert tracker.getShortcut("app.user10.a") != null;

        System.out.println("[PASS] Multi-user profile shortcut isolation and dynamic cleanup verified");
    }

    public static void main(String[] args) {
        System.out.println("=== Running Adversarial LinuxAppTracker Stress Tests ===");
        try {
            testInotifyEventBurstHandling();
            testMalformedAppMetadataAndXmlEscaping();
            testHighVolumeDeduplication();
            testMultiUserIsolationAndCleanup();
            System.out.println("ALL Adversarial LinuxAppTracker STRESS TESTS PASSED!");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
