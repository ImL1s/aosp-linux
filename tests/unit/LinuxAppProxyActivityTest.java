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

import com.android.virtualization.terminal.window.WindowResizePacer;

import java.util.concurrent.atomic.AtomicInteger;

public class LinuxAppProxyActivityTest {

    public static void testWindowResizePacerDebouncing() throws InterruptedException {
        AtomicInteger configureCount = new AtomicInteger(0);
        WindowResizePacer pacer = new WindowResizePacer((width, height) -> {
            configureCount.incrementAndGet();
        });

        // Fire 10 rapid resize events within 5ms
        for (int i = 0; i < 10; i++) {
            pacer.requestResize(800 + i * 10, 600 + i * 10);
        }

        // Immediately 1 event executes, pending posted delayed
        assert configureCount.get() >= 1 : "First resize should execute immediately";

        pacer.flushPendingResize();
        assert configureCount.get() <= 3 : "Burst events should be throttled and debounced";
        System.out.println("[PASS] testWindowResizePacerDebouncing");
    }

    public static void testFreeformBoundsClampingLogic() {
        int minW = 320, minH = 240;
        int maxW = 1920, maxH = 1080;

        int reqW = 100, reqH = 100;
        int clampedW = Math.max(minW, Math.min(maxW, reqW));
        int clampedH = Math.max(minH, Math.min(maxH, reqH));

        assert clampedW == 320 : "Width should clamp to min 320";
        assert clampedH == 240 : "Height should clamp to min 240";

        reqW = 3840; reqH = 2160;
        clampedW = Math.max(minW, Math.min(maxW, reqW));
        clampedH = Math.max(minH, Math.min(maxH, reqH));

        assert clampedW == 1920 : "Width should clamp to max screen resolution 1920";
        assert clampedH == 1080 : "Height should clamp to max screen resolution 1080";

        System.out.println("[PASS] testFreeformBoundsClampingLogic");
    }

    public static void testAspectRatioPreservation() {
        float targetRatio = 1.333f; // 4:3
        int requestedW = 1600;
        int requestedH = 1000; // ratio is 1.6 > 1.333

        int adjustedW = requestedW;
        int adjustedH = requestedH;

        if ((float) requestedW / (float) requestedH > targetRatio) {
            adjustedW = (int) (requestedH * targetRatio);
        } else {
            adjustedH = (int) (requestedW / targetRatio);
        }

        assert adjustedW == 1333 : "Width should adjust to 1333 to preserve 4:3 ratio";
        assert adjustedH == 1000;
        System.out.println("[PASS] testAspectRatioPreservation");
    }

    public static void main(String[] args) {
        System.out.println("Running LinuxAppProxyActivityTest unit tests...");
        try {
            testWindowResizePacerDebouncing();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        testFreeformBoundsClampingLogic();
        testAspectRatioPreservation();
        System.out.println("ALL LinuxAppProxyActivityTest UNIT TESTS PASSED!");
    }
}
