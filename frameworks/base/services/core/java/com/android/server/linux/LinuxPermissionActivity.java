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

package com.android.server.linux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * System UI permission prompt Activity triggered when guest portal requests access to hardware resources.
 * Handles 30-second prompt timeout, duplicate prompt suppression, lockscreen queueing, and MDM policy overrides.
 * {@hide}
 */
public class LinuxPermissionActivity extends Activity {
    private static final String TAG = "LinuxPermissionActivity";
    public static final long PROMPT_TIMEOUT_MS = 30000L;

    private static final Object sLock = new Object();
    private static final List<String> sPendingPromptsQueue = new ArrayList<>();
    private static boolean sIsDialogVisible = false;
    private static boolean sIsMdmRestricted = false;
    private static boolean sIsScreenLocked = false;

    private String mAppName;
    private String mPermissionOp;
    private String mUserChoice = "PROMPT";
    private Handler mHandler;
    private Runnable mTimeoutRunnable;

    public static void launchPrompt(Context context, String appName, String permissionOp) {
        if (context == null) return;
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("EXTRA_APP_NAME", appName);
        intent.putExtra("EXTRA_PERMISSION_OP", permissionOp);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        if (getIntent() != null) {
            mAppName = getIntent().getStringExtra("EXTRA_APP_NAME");
            mPermissionOp = getIntent().getStringExtra("EXTRA_PERMISSION_OP");
        }
        showPrompt(mAppName, mPermissionOp);
    }

    public boolean showPrompt(String appName, String permissionOp) {
        this.mAppName = appName;
        this.mPermissionOp = permissionOp;

        synchronized (sLock) {
            if (sIsMdmRestricted) {
                Slog.w(TAG, "MDM Policy restricted: force-denying prompt for " + appName + " [" + permissionOp + "]");
                mUserChoice = "DENIED";
                return false;
            }

            if (sIsScreenLocked) {
                Slog.i(TAG, "Screen locked: queueing prompt " + appName + ":" + permissionOp);
                sPendingPromptsQueue.add(appName + ":" + permissionOp);
                return false;
            }

            if (sIsDialogVisible) {
                Slog.i(TAG, "Dialog visible: queueing concurrent prompt for " + appName);
                sPendingPromptsQueue.add(appName + ":" + permissionOp);
                return false;
            }

            sIsDialogVisible = true;
        }

        mUserChoice = "PROMPT";

        // Schedule 30s timeout
        if (mHandler != null) {
            mTimeoutRunnable = () -> {
                if ("PROMPT".equals(mUserChoice)) {
                    Slog.w(TAG, "Permission prompt timed out after 30s -> defaulting to DENIED");
                    mUserChoice = "DENIED";
                    dismissPrompt();
                }
            };
            mHandler.postDelayed(mTimeoutRunnable, PROMPT_TIMEOUT_MS);
        }
        return true;
    }

    public void respondUserChoice(String choice) {
        mUserChoice = choice;
        if (mTimeoutRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mTimeoutRunnable);
        }
        Slog.i(TAG, "User choice recorded: " + mAppName + " [" + mPermissionOp + "] -> " + choice);
        dismissPrompt();
    }

    public void onScreenUnlocked() {
        synchronized (sLock) {
            sIsScreenLocked = false;
            if (!sPendingPromptsQueue.isEmpty() && !sIsDialogVisible) {
                String nextPrompt = sPendingPromptsQueue.remove(0);
                String[] parts = nextPrompt.split(":");
                if (parts.length == 2) {
                    showPrompt(parts[0], parts[1]);
                }
            }
        }
    }

    public void dismissPrompt() {
        synchronized (sLock) {
            sIsDialogVisible = false;
            if (!sPendingPromptsQueue.isEmpty()) {
                String nextPrompt = sPendingPromptsQueue.remove(0);
                String[] parts = nextPrompt.split(":");
                if (parts.length == 2) {
                    showPrompt(parts[0], parts[1]);
                }
            }
        }
    }

    public void setMdmRestricted(boolean restricted) {
        synchronized (sLock) {
            sIsMdmRestricted = restricted;
        }
    }

    public void setScreenLocked(boolean locked) {
        synchronized (sLock) {
            sIsScreenLocked = locked;
        }
    }


    public String getUserChoice() {
        return mUserChoice;
    }

    public static boolean isDialogVisible() {
        synchronized (sLock) {
            return sIsDialogVisible;
        }
    }

    public static List<String> getPendingPromptsQueue() {
        synchronized (sLock) {
            return new ArrayList<>(sPendingPromptsQueue);
        }
    }
}

