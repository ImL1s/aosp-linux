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

package com.android.virtualization.terminal.window;

import android.os.Handler;
import android.os.Looper;

/**
 * Debouncer and frame pacing handler for live freeform resize drag operations (F-R4-004).
 * Limits configure event rates to ~60 FPS (16ms) to prevent buffer queue flooding.
 */
public class WindowResizePacer {
    public interface ResizeCallback {
        void onResizeConfigured(int width, int height);
    }

    private static final long DEBOUNCE_INTERVAL_MS = 16L; // ~60 FPS
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ResizeCallback mCallback;
    private Runnable mPendingResizeRunnable;
    private long mLastResizeTimeMs = 0;
    private int mTargetWidth;
    private int mTargetHeight;

    public WindowResizePacer(ResizeCallback callback) {
        mCallback = callback;
    }

    public synchronized void requestResize(int width, int height) {
        mTargetWidth = width;
        mTargetHeight = height;

        long now = System.currentTimeMillis();
        long timeSinceLast = now - mLastResizeTimeMs;

        if (mPendingResizeRunnable != null) {
            mHandler.removeCallbacks(mPendingResizeRunnable);
        }

        if (timeSinceLast >= DEBOUNCE_INTERVAL_MS) {
            mLastResizeTimeMs = now;
            mCallback.onResizeConfigured(mTargetWidth, mTargetHeight);
        } else {
            long delay = DEBOUNCE_INTERVAL_MS - timeSinceLast;
            mPendingResizeRunnable = () -> {
                synchronized (WindowResizePacer.this) {
                    mPendingResizeRunnable = null;
                    mLastResizeTimeMs = System.currentTimeMillis();
                    mCallback.onResizeConfigured(mTargetWidth, mTargetHeight);
                }
            };
            mHandler.postDelayed(mPendingResizeRunnable, delay);
        }
    }

    public synchronized void flushPendingResize() {
        if (mPendingResizeRunnable != null) {
            mHandler.removeCallbacks(mPendingResizeRunnable);
            mPendingResizeRunnable = null;
            mCallback.onResizeConfigured(mTargetWidth, mTargetHeight);
        }
    }
}
