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

import com.android.server.linux.LinuxWindowBridgeService;

public class LinuxWindowBridgeServiceTest {

    public static void testCreateSurfaceAndAllocateTaskId() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid1 = service.createSurface("org.debian.gimp", "GIMP Image Editor", "/usr/share/icons/gimp.png", 1024, 768);
        assert sid1 > 0 : "Surface ID should be positive";
        assert service.getActiveTaskCount() == 1 : "Expected 1 active task";

        LinuxWindowBridgeService.WaylandSurface surface = service.getSurface(sid1);
        assert surface != null;
        assert surface.appId.equals("org.debian.gimp");
        assert surface.title.equals("GIMP Image Editor");
        assert surface.taskId > 0;
        System.out.println("[PASS] testCreateSurfaceAndAllocateTaskId");
    }

    public static void testTaskReuseForRunningApp() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid1 = service.createSurface("org.mozilla.firefox", "Firefox", "/icons/firefox.png", 1280, 800);
        int sid2 = service.createSurface("org.mozilla.firefox", "Firefox", "/icons/firefox.png", 1280, 800);

        assert sid1 == sid2 : "Re-launching running app should reuse existing surface / Task ID";
        assert service.getActiveTaskCount() == 1 : "Task count should remain 1";
        System.out.println("[PASS] testTaskReuseForRunningApp");
    }

    public static void testMaxConcurrentTaskLimit() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        for (int i = 1; i <= LinuxWindowBridgeService.MAX_CONCURRENT_TASKS; i++) {
            int sid = service.createSurface("app.test." + i, "Test App " + i, null, 800, 600);
            assert sid > 0 : "Surface creation failed at index " + i;
        }

        // 21st surface attempt should fail with -1
        int sidOverflow = service.createSurface("app.overflow", "Overflow", null, 800, 600);
        assert sidOverflow == -1 : "Expected -1 error code when exceeding max task limit";
        assert service.getActiveTaskCount() == LinuxWindowBridgeService.MAX_CONCURRENT_TASKS;
        System.out.println("[PASS] testMaxConcurrentTaskLimit");
    }

    public static void testRecentsCloseTask() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        int sid = service.createSurface("org.inkscape", "Inkscape", null, 1024, 768);
        LinuxWindowBridgeService.WaylandSurface surface = service.getSurface(sid);
        int taskId = surface.taskId;

        service.closeTaskFromRecents(taskId);
        assert service.getSurface(sid) == null : "Surface should be removed after closeTaskFromRecents";
        assert service.getActiveTaskCount() == 0;
        System.out.println("[PASS] testRecentsCloseTask");
    }

    public static void testFlushTasksOnVmShutdown() {
        LinuxWindowBridgeService service = new LinuxWindowBridgeService(null);
        service.createSurface("app.one", "App 1", null, 800, 600);
        service.createSurface("app.two", "App 2", null, 800, 600);
        assert service.getActiveTaskCount() == 2;

        service.onVmStateChanged(false); // VM Stopped
        assert service.getActiveTaskCount() == 0 : "flushTasks should clear all surface registries";
        System.out.println("[PASS] testFlushTasksOnVmShutdown");
    }

    public static void main(String[] args) {
        System.out.println("Running LinuxWindowBridgeServiceTest unit tests...");
        testCreateSurfaceAndAllocateTaskId();
        testTaskReuseForRunningApp();
        testMaxConcurrentTaskLimit();
        testRecentsCloseTask();
        testFlushTasksOnVmShutdown();
        System.out.println("ALL LinuxWindowBridgeServiceTest UNIT TESTS PASSED!");
    }
}
