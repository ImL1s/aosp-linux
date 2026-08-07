package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.res.Resources;

public class Activity extends Context {
    private Intent mIntent;
    private int mTaskId = 1001;

    protected void onCreate(Bundle savedInstanceState) {}
    protected void onDestroy() {}
    public void onConfigurationChanged(Configuration newConfig) {}
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {}
    public boolean onTouchEvent(MotionEvent event) { return false; }
    public boolean onGenericMotionEvent(MotionEvent event) { return false; }

    public Intent getIntent() { return mIntent; }
    public void setIntent(Intent intent) { mIntent = intent; }
    public int getTaskId() { return mTaskId; }
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {}
    public void setContentView(View view) {}
    public Window getWindow() { return new Window(); }
    public WindowManager getWindowManager() { return new WindowManager(); }
    public Resources getResources() { return new Resources(); }

    @Override public Object getSystemService(String name) { return null; }
    @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
    @Override public java.util.concurrent.Executor getMainExecutor() { return null; }
}
