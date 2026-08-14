package com.android.virtualization.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * System UI permission prompt Activity for Linux container application permission requests.
 */
public class LinuxPermissionActivity extends Activity {
    private static final String TAG = "LinuxPermissionActivity";

    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_OP = "op";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        String appId = intent.getStringExtra(EXTRA_APP_ID);
        if (appId == null || appId.isEmpty()) {
            appId = intent.getStringExtra("app_name");
        }
        if (appId == null) {
            appId = "org.debian.terminal";
        }

        String opStr = intent.getStringExtra(EXTRA_OP);
        if (opStr == null) {
            opStr = "android:camera";
        }

        showPermissionPromptDialog(appId, opStr);
    }

    private void showPermissionPromptDialog(final String appId, final String opStr) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Linux App Permission Request");
        builder.setMessage("Debian Linux application '" + appId + "' is requesting access to:\n\n• " 
                + opStr + "\n\nDo you want to grant hardware access to this Linux app?");
        builder.setCancelable(false);

        builder.setPositiveButton("Allow (授權)", (dialog, which) -> {
            Log.i(TAG, "Granted permission " + opStr + " for " + appId);
            finish();
        });

        builder.setNegativeButton("Deny (拒絕)", (dialog, which) -> {
            Log.i(TAG, "Denied permission " + opStr + " for " + appId);
            finish();
        });

        builder.show();
    }
}
