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
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Slog;

/**
 * System UI permission prompt Activity for Linux container application permission requests.
 * {@hide}
 */
public class LinuxPermissionActivity extends Activity {
    private static final String TAG = "LinuxPermissionActivity";

    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_OP = "op";

    public static void launchPrompt(Context context, String appId, String op) {
        if (context == null) {
            Slog.w(TAG, "Cannot launch permission prompt: null context");
            return;
        }
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.putExtra(EXTRA_APP_ID, appId);
        intent.putExtra(EXTRA_OP, op);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void launchPrompt(Context context, String appId, int op) {
        if (context == null) {
            Slog.w(TAG, "Cannot launch permission prompt: null context");
            return;
        }
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.putExtra(EXTRA_APP_ID, appId);
        intent.putExtra(EXTRA_OP, op);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            Slog.w(TAG, "No Intent provided to LinuxPermissionActivity");
            finish();
            return;
        }

        String appId = intent.getStringExtra(EXTRA_APP_ID);
        if (appId == null || appId.isEmpty()) {
            appId = intent.getStringExtra("appId");
        }

        String opStr = null;
        int opInt = -1;

        if (intent.hasExtra(EXTRA_OP)) {
            Object extra = intent.getExtras().get(EXTRA_OP);
            if (extra instanceof Integer) {
                opInt = (Integer) extra;
                opStr = mapOpIntToString(opInt);
            } else if (extra instanceof String) {
                opStr = (String) extra;
                opInt = mapOpStringToCode(opStr);
            } else if (extra instanceof Number) {
                opInt = ((Number) extra).intValue();
                opStr = mapOpIntToString(opInt);
            }
        } else if (intent.hasExtra("op_code")) {
            opInt = intent.getIntExtra("op_code", -1);
            opStr = mapOpIntToString(opInt);
        }

        if (appId == null || appId.isEmpty() || (opStr == null && opInt == -1)) {
            Slog.w(TAG, "Invalid or missing extras in LinuxPermissionActivity (appId=" + appId 
                    + ", opStr=" + opStr + ", opInt=" + opInt + ")");
            finish();
            return;
        }

        showPermissionPromptDialog(appId, opStr, opInt);
    }

    private void showPermissionPromptDialog(final String appId, final String opStr, final int opInt) {
        String friendlyName = getFriendlyPermissionName(opStr, opInt);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Linux Application Permission Request");
        builder.setMessage("Linux application '" + appId + "' is requesting permission for " 
                + friendlyName + ".\n\nDo you want to grant this permission?");
        builder.setCancelable(false);

        builder.setPositiveButton("Allow", (dialog, which) -> {
            handlePermissionDecision(appId, opStr, opInt, AppOpsManager.MODE_ALLOWED);
            finish();
        });

        builder.setNegativeButton("Deny", (dialog, which) -> {
            handlePermissionDecision(appId, opStr, opInt, AppOpsManager.MODE_ERRORED);
            finish();
        });

        builder.setOnCancelListener(dialog -> {
            handlePermissionDecision(appId, opStr, opInt, AppOpsManager.MODE_ERRORED);
            finish();
        });

        try {
            builder.show();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to show permission dialog: " + e.getMessage(), e);
            // Default to denied if dialog creation/display fails
            handlePermissionDecision(appId, opStr, opInt, AppOpsManager.MODE_ERRORED);
            finish();
        }
    }

    private void handlePermissionDecision(String appId, String opStr, int opInt, int mode) {
        String modeStr = (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND)
                ? LinuxPortalService.MODE_ALLOWED : LinuxPortalService.MODE_DENIED;

        Slog.i(TAG, "User permission decision for " + appId + " [" + opStr + " / " + opInt + "] -> mode: " + modeStr + " (" + mode + ")");

        LinuxPortalService portalService = LinuxPortalService.getInstance();
        if (portalService != null) {
            if (opStr != null) {
                portalService.setAppOp(appId, opStr, modeStr);
                portalService.setAppOp(appId, opStr, mode);
            }
            if (opInt != -1) {
                portalService.setAppOp(appId, opInt, modeStr);
                portalService.setAppOp(appId, opInt, mode);
            }
        } else {
            Slog.w(TAG, "LinuxPortalService instance unavailable to update appOp state");
        }

        updateSystemAppOpsManager(appId, opStr, opInt, mode);
    }

    private void updateSystemAppOpsManager(String appId, String opStr, int opInt, int mode) {
        try {
            AppOpsManager appOps = getSystemService(AppOpsManager.class);
            if (appOps == null) return;
            int uid = Process.myUid();
            int code = opInt != -1 ? opInt : mapOpStringToCode(opStr);
            if (code != -1) {
                try {
                    java.lang.reflect.Method setModeMethod = AppOpsManager.class.getMethod(
                            "setMode", int.class, int.class, String.class, int.class);
                    setModeMethod.invoke(appOps, code, uid, appId, mode);
                    Slog.i(TAG, "Updated system AppOpsManager setMode(" + code + ", " + uid + ", " + appId + ", " + mode + ")");
                } catch (NoSuchMethodException e) {
                    if (opStr != null) {
                        java.lang.reflect.Method setModeMethod = AppOpsManager.class.getMethod(
                                "setMode", String.class, int.class, String.class, int.class);
                        setModeMethod.invoke(appOps, opStr, uid, appId, mode);
                    }
                }
            }
        } catch (Throwable t) {
            Slog.d(TAG, "Non-fatal fallback updating system AppOpsManager: " + t.getMessage());
        }
    }

    public static String mapOpIntToString(int op) {
        switch (op) {
            case 26:
                return LinuxPortalService.OP_CAMERA;
            case 27:
                return LinuxPortalService.OP_RECORD_AUDIO;
            case 1:
                return LinuxPortalService.OP_FINE_LOCATION;
            case 0:
                return LinuxPortalService.OP_COARSE_LOCATION;
            default:
                return "OP_" + op;
        }
    }

    public static int mapOpStringToCode(String opStr) {
        if (opStr == null) return -1;
        if (LinuxPortalService.OP_CAMERA.equals(opStr) || AppOpsManager.OPSTR_CAMERA.equals(opStr) || "26".equals(opStr)) {
            return 26;
        }
        if (LinuxPortalService.OP_RECORD_AUDIO.equals(opStr) || AppOpsManager.OPSTR_RECORD_AUDIO.equals(opStr) || "27".equals(opStr)) {
            return 27;
        }
        if (LinuxPortalService.OP_FINE_LOCATION.equals(opStr) || AppOpsManager.OPSTR_FINE_LOCATION.equals(opStr) || "1".equals(opStr)) {
            return 1;
        }
        if (LinuxPortalService.OP_COARSE_LOCATION.equals(opStr) || AppOpsManager.OPSTR_COARSE_LOCATION.equals(opStr) || "0".equals(opStr)) {
            return 0;
        }
        try {
            return Integer.parseInt(opStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String getFriendlyPermissionName(String opStr, int opInt) {
        int code = opInt != -1 ? opInt : mapOpStringToCode(opStr);
        switch (code) {
            case 26:
                return "Camera Access";
            case 27:
                return "Microphone Recording";
            case 1:
                return "Fine Location Access";
            case 0:
                return "Coarse Location Access";
            default:
                return opStr != null ? opStr : ("Operation (" + opInt + ")");
        }
    }
}

