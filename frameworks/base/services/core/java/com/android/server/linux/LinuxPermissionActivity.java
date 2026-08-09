package com.android.server.linux;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class LinuxPermissionActivity extends Activity {
    public static void launchPrompt(Context context, String appId, String op) {
        Intent intent = new Intent(context, LinuxPermissionActivity.class);
        intent.putExtra("app_id", appId);
        intent.putExtra("op", op);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
