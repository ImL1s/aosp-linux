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

package android.content;

import android.annotation.SystemApi;
import java.util.concurrent.Executor;

/**
 * Android Context constant extension for Linux dual-OS execution environment.
 * @hide
 */
public abstract class Context {

    @SystemApi
    public static final String LINUX_SERVICE = "linux";
    public static final String INPUT_METHOD_SERVICE = "input_method";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String AUDIO_SERVICE = "audio";
    public static final String APP_OPS_SERVICE = "appops";
    public static final String CAMERA_SERVICE = "camera";
    public static final String LOCATION_SERVICE = "location";
    public static final int MODE_PRIVATE = 0;

    public abstract Object getSystemService(String name);

    @SuppressWarnings("unchecked")
    public <T> T getSystemService(Class<T> serviceClass) {
        String serviceName = getSystemServiceName(serviceClass);
        return serviceName != null ? (T) getSystemService(serviceName) : null;
    }

    public String getSystemServiceName(Class<?> serviceClass) {
        if ("android.app.AppOpsManager".equals(serviceClass.getName())) return APP_OPS_SERVICE;
        if ("android.hardware.camera2.CameraManager".equals(serviceClass.getName())) return CAMERA_SERVICE;
        if ("android.location.LocationManager".equals(serviceClass.getName())) return LOCATION_SERVICE;
        if ("android.media.AudioManager".equals(serviceClass.getName())) return AUDIO_SERVICE;
        return null;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public abstract void enforceCallingOrSelfPermission(String permission, String message);
    public abstract Executor getMainExecutor();
    public String getPackageName() { return "com.android.server.linux"; }
    public ContentResolver getContentResolver() { return null; }
    public void startActivity(Intent intent) {}

    public void sendBroadcast(Intent intent) {}
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
    public void unregisterReceiver(BroadcastReceiver receiver) {}
}
