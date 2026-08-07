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

package com.android.launcher3.linux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.system.linux.LinuxAppInfo;
import android.system.linux.LinuxManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launcher3 Synthetic Shortcut Generator & App Drawer Integration (F-R4-006).
 * Manages dynamic shortcuts, icon formatting & fallback, XML escaping, deduplication,
 * Launcher restart persistence, and tapping action dispatch to LinuxAppProxyActivity.
 */
public class LinuxAppTracker {
    private static final String TAG = "LinuxAppTracker";
    public static final String ACTION_LINUX_APPS_CHANGED = "android.system.linux.action.LINUX_APPS_CHANGED";
    public static final String DEFAULT_ICON_PATH = "/usr/share/icons/default_linux_app_icon.png";

    public static class SyntheticShortcut {
        public final String appId;
        public final String title;
        public final String escapedTitle;
        public final String execCommand;
        public final String iconPath;
        public final int userId;
        public final Bitmap iconBitmap;

        public SyntheticShortcut(String appId, String title, String execCommand, String iconPath, int userId, Bitmap iconBitmap) {
            this.appId = appId;
            this.title = title;
            this.escapedTitle = escapeXml(title);
            this.execCommand = escapeXml(execCommand);
            this.iconPath = iconPath;
            this.userId = userId;
            this.iconBitmap = iconBitmap;
        }
    }

    private final Context mContext;
    private final Map<String, SyntheticShortcut> mShortcuts = new ConcurrentHashMap<>();
    private final BroadcastReceiver mReceiver;
    private boolean mIsListening = false;

    public LinuxAppTracker(Context context) {
        mContext = context;
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_LINUX_APPS_CHANGED.equals(intent.getAction())) {
                    Log.i(TAG, "Received LINUX_APPS_CHANGED broadcast. Refreshing Launcher shortcuts...");
                    syncLinuxApps();
                }
            }
        };
    }

    public synchronized void startTracking() {
        if (!mIsListening && mContext != null) {
            IntentFilter filter = new IntentFilter(ACTION_LINUX_APPS_CHANGED);
            mContext.registerReceiver(mReceiver, filter);
            mIsListening = true;
            syncLinuxApps();
        }
    }

    public synchronized void stopTracking() {
        if (mIsListening && mContext != null) {
            mContext.unregisterReceiver(mReceiver);
            mIsListening = false;
        }
    }

    public synchronized void syncLinuxApps() {
        if (mContext == null) return;
        try {
            LinuxManager manager = (LinuxManager) mContext.getSystemService(Context.LINUX_SERVICE);
            if (manager != null) {
                List<LinuxAppInfo> apps = manager.getInstalledApps();
                updateShortcutsFromList(apps, 0 /* default userId */);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query LinuxManager installed apps: " + e.getMessage());
        }
    }

    public synchronized void updateShortcutsFromList(List<LinuxAppInfo> apps, int userId) {
        if (apps == null) return;

        Map<String, LinuxAppInfo> newAppMap = new HashMap<>();
        for (LinuxAppInfo app : apps) {
            newAppMap.put(app.getAppId(), app);
        }

        // Remove deleted shortcuts
        List<String> toRemove = new ArrayList<>();
        for (SyntheticShortcut shortcut : mShortcuts.values()) {
            if (shortcut.userId == userId && !newAppMap.containsKey(shortcut.appId)) {
                toRemove.add(shortcut.appId);
            }
        }
        for (String id : toRemove) {
            mShortcuts.remove(id);
            Log.i(TAG, "Removed uninstalled Linux shortcut: " + id);
        }

        // Add or update shortcuts (Deduplication)
        for (LinuxAppInfo app : apps) {
            Bitmap icon = resolveIconBitmap(app.getIconPath());
            SyntheticShortcut shortcut = new SyntheticShortcut(
                    app.getAppId(),
                    app.getDisplayName(),
                    app.getExecCommand(),
                    app.getIconPath(),
                    userId,
                    icon
            );
            mShortcuts.put(app.getAppId(), shortcut);
            Log.d(TAG, "Added/Updated synthetic shortcut: " + app.getDisplayName() + " (" + app.getAppId() + ")");
        }
    }

    public void launchShortcut(String appId) {
        SyntheticShortcut shortcut = mShortcuts.get(appId);
        if (shortcut == null) {
            Log.w(TAG, "Cannot launch shortcut: Unknown appId " + appId);
            return;
        }

        Log.i(TAG, "Launching synthetic shortcut for " + shortcut.title + " (" + appId + ")");
        if (mContext != null) {
            Intent intent = new Intent();
            intent.setClassName("com.android.virtualization.terminal", "com.android.virtualization.terminal.LinuxAppProxyActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.putExtra("EXTRA_APP_ID", shortcut.appId);
            intent.putExtra("EXTRA_APP_TITLE", shortcut.title);
            intent.putExtra("EXTRA_ICON_PATH", shortcut.iconPath);
            intent.putExtra("EXTRA_EXEC_COMMAND", shortcut.execCommand);

            mContext.startActivity(intent);
        }
    }

    public List<SyntheticShortcut> getShortcutsForUser(int userId) {
        List<SyntheticShortcut> userShortcuts = new ArrayList<>();
        for (SyntheticShortcut shortcut : mShortcuts.values()) {
            if (shortcut.userId == userId) {
                userShortcuts.add(shortcut);
            }
        }
        return Collections.unmodifiableList(userShortcuts);
    }

    public SyntheticShortcut getShortcut(String appId) {
        return mShortcuts.get(appId);
    }

    public int getShortcutCount() {
        return mShortcuts.size();
    }

    private Bitmap resolveIconBitmap(String iconPath) {
        if (iconPath != null && !iconPath.isEmpty()) {
            String lower = iconPath.toLowerCase();
            // Handle unsupported / unknown binary formats like .xpm
            if (lower.endsWith(".xpm") || lower.endsWith(".bmp") || lower.endsWith(".ico")) {
                Log.w(TAG, "Unsupported icon format " + iconPath + " -> Falling back to default icon");
                return createFallbackBitmap();
            }

            File file = new File(iconPath);
            if (file.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(iconPath);
                    if (bmp != null) return bmp;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to decode icon bitmap at " + iconPath + ": " + e.getMessage());
                }
            }
        }
        return createFallbackBitmap();
    }

    private Bitmap createFallbackBitmap() {
        Bitmap bitmap = null;
        if (DEFAULT_ICON_PATH != null && new File(DEFAULT_ICON_PATH).exists()) {
            try {
                bitmap = BitmapFactory.decodeFile(DEFAULT_ICON_PATH);
            } catch (Exception ignored) {}
        }
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        }
        return bitmap;
    }

    public static String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
