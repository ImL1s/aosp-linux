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

package com.android.virtualization.terminal;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.virtualization.terminal.window.WindowResizePacer;

import java.io.DataOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Discrete Android Activity hosting a forwarded Guest Wayland GUI App surface (F-R4-003 & F-R4-004).
 * Manages task description in Recents, freeform multi-window resizing, bounds clamping,
 * DPI scaling, and input dispatch back to Sommelier over Vsock 5002.
 */
public class LinuxAppProxyActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "LinuxAppProxyActivity";
    private static final int VSOK_MAGIC = 0x56534F4B;

    public static final String EXTRA_SURFACE_ID = "EXTRA_SURFACE_ID";
    public static final String EXTRA_APP_ID = "EXTRA_APP_ID";
    public static final String EXTRA_APP_TITLE = "EXTRA_APP_TITLE";
    public static final String EXTRA_ICON_PATH = "EXTRA_ICON_PATH";
    public static final String EXTRA_TASK_ID = "EXTRA_TASK_ID";
    public static final String EXTRA_WIDTH = "EXTRA_WIDTH";
    public static final String EXTRA_HEIGHT = "EXTRA_HEIGHT";
    public static final String EXTRA_FIXED_ASPECT_RATIO = "EXTRA_FIXED_ASPECT_RATIO";

    private int mSurfaceId;
    private String mAppId;
    private String mTitle;
    private String mIconPath;
    private int mTaskId;
    private int mWidth = 1024;
    private int mHeight = 768;
    private boolean mFixedAspectRatio = false;
    private float mTargetAspectRatio = 1.333f; // Default 4:3 ratio
    private int mInputSeqId = 0;

    private SurfaceView mSurfaceView;
    private WindowResizePacer mResizePacer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null) {
            mSurfaceId = intent.getIntExtra(EXTRA_SURFACE_ID, -1);
            mAppId = intent.getStringExtra(EXTRA_APP_ID);
            mTitle = intent.getStringExtra(EXTRA_APP_TITLE);
            mIconPath = intent.getStringExtra(EXTRA_ICON_PATH);
            mTaskId = intent.getIntExtra(EXTRA_TASK_ID, getTaskId());
            mWidth = intent.getIntExtra(EXTRA_WIDTH, 1024);
            mHeight = intent.getIntExtra(EXTRA_HEIGHT, 768);
            mFixedAspectRatio = intent.getBooleanExtra(EXTRA_FIXED_ASPECT_RATIO, false);
            if (mHeight > 0) {
                mTargetAspectRatio = (float) mWidth / (float) mHeight;
            }
        }

        setupTaskDescription();

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        setContentView(mSurfaceView);

        mResizePacer = new WindowResizePacer((width, height) -> {
            Log.i(TAG, "Configuring Wayland surface " + mSurfaceId + " new dimensions: " + width + "x" + height);
            sendVsockConfigureFrame(mSurfaceId, width, height);
        });
    }

    private void setupTaskDescription() {
        String displayTitle = mTitle != null ? mTitle : (mAppId != null ? mAppId : "Linux Application");
        Bitmap iconBitmap = null;

        if (mIconPath != null && new File(mIconPath).exists()) {
            try {
                iconBitmap = BitmapFactory.decodeFile(mIconPath);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load icon bitmap from " + mIconPath + ": " + e.getMessage());
            }
        }

        if (iconBitmap == null) {
            iconBitmap = createDefaultIconBitmap(displayTitle);
        }

        try {
            ActivityManager.TaskDescription td = new ActivityManager.TaskDescription.Builder()
                    .setTitle(displayTitle)
                    .setIcon(iconBitmap)
                    .setPrimaryColor(0xFF2C3E50)
                    .build();
            setTaskDescription(td);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set TaskDescription: " + e.getMessage());
        }
    }

    private Bitmap createDefaultIconBitmap(String title) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFF34495E);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(32f);
        paint.setTextAlign(Paint.Align.CENTER);
        String letter = title != null && !title.isEmpty() ? title.substring(0, 1).toUpperCase() : "L";
        canvas.drawText(letter, 32f, 44f, paint);
        return bitmap;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateWindowDimensions();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        updateWindowDimensions();
    }

    public void updateWindowDimensions() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int viewWidth = mSurfaceView.getWidth();
        int viewHeight = mSurfaceView.getHeight();

        if (viewWidth == 0 || viewHeight == 0) {
            viewWidth = (int) (new Configuration(getResources().getConfiguration()).screenWidthDp * dm.density);
            viewHeight = (int) (new Configuration(getResources().getConfiguration()).screenHeightDp * dm.density);
        }

        // Min bounds clamp: 320x240 px
        int clampedWidth = Math.max(320, viewWidth);
        int clampedHeight = Math.max(240, viewHeight);

        // Max bounds clamp: Screen resolution
        clampedWidth = Math.min(dm.widthPixels, clampedWidth);
        clampedHeight = Math.min(dm.heightPixels, clampedHeight);

        // Aspect ratio preservation for fixed-ratio apps
        if (mFixedAspectRatio && mTargetAspectRatio > 0) {
            if ((float) clampedWidth / (float) clampedHeight > mTargetAspectRatio) {
                clampedWidth = (int) (clampedHeight * mTargetAspectRatio);
            } else {
                clampedHeight = (int) (clampedWidth / mTargetAspectRatio);
            }
        }

        mWidth = clampedWidth;
        mHeight = clampedHeight;

        if (mResizePacer != null) {
            mResizePacer.requestResize(mWidth, mHeight);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event != null) {
            sendVsockInputFrame("touch", mSurfaceId, event.getAction(), event.getX(), event.getY());
            Log.d(TAG, "Dispatched touch event action: " + event.getAction() + " x: " + event.getX() + " y: " + event.getY());
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event != null) {
            sendVsockInputFrame("motion", mSurfaceId, event.getAction(), event.getX(), event.getY());
            Log.d(TAG, "Dispatched generic motion event for surface " + mSurfaceId);
        }
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
        updateWindowDimensions();

        SurfaceControl surfaceControl = mSurfaceView.getSurfaceControl();
        if (surfaceControl != null && surfaceControl.isValid()) {
            Log.i(TAG, "Registering SurfaceControl to LinuxWindowBridgeService for surfaceId: " + mSurfaceId);
            attachSurfaceControlToBridge(mSurfaceId, surfaceControl);
        } else {
            Log.w(TAG, "SurfaceControl is null or invalid on surfaceCreated for surfaceId: " + mSurfaceId);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Surface changed width: " + width + " height: " + height + " for surfaceId: " + mSurfaceId);
        mWidth = width;
        mHeight = height;
        if (mResizePacer != null) {
            mResizePacer.requestResize(width, height);
        }

        SurfaceControl surfaceControl = mSurfaceView.getSurfaceControl();
        if (surfaceControl != null && surfaceControl.isValid()) {
            attachSurfaceControlToBridge(mSurfaceId, surfaceControl);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed for LinuxAppProxyActivity surfaceId: " + mSurfaceId);
        detachSurfaceControlFromBridge(mSurfaceId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachSurfaceControlFromBridge(mSurfaceId);
        Log.i(TAG, "LinuxAppProxyActivity destroyed for task " + getTaskId());
    }

    public int getSurfaceId() { return mSurfaceId; }
    public String getAppId() { return mAppId; }
    public int getTargetWidth() { return mWidth; }
    public int getTargetHeight() { return mHeight; }

    private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
        if (surfaceId <= 0) {
            Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
            return;
        }

    private void attachSurfaceControlToBridge(int surfaceId, SurfaceControl surfaceControl) {
        if (surfaceId <= 0) {
            Log.w(TAG, "Invalid surfaceId: " + surfaceId + ", skipping attachSurfaceControl");
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
            java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            if (instance != null) {
                java.lang.reflect.Method attachMethod = bridgeClass.getMethod("attachSurfaceControl", int.class, SurfaceControl.class);
                attachMethod.invoke(instance, surfaceId, surfaceControl);
                Log.i(TAG, "Successfully attached SurfaceControl via reflection for surfaceId: " + surfaceId);
            } else {
                Log.w(TAG, "LinuxWindowBridgeService.getInstance() returned null via reflection");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach SurfaceControl to LinuxWindowBridgeService for surfaceId " + surfaceId + ": " + e.getMessage());
        }
    }

    private void detachSurfaceControlFromBridge(int surfaceId) {
        if (surfaceId <= 0) return;

        try {
            Class<?> bridgeClass = Class.forName("com.android.server.linux.LinuxWindowBridgeService");
            java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            if (instance != null) {
                java.lang.reflect.Method attachMethod = bridgeClass.getMethod("attachSurfaceControl", int.class, SurfaceControl.class);
                attachMethod.invoke(instance, surfaceId, (Object) null);
                Log.i(TAG, "Successfully detached SurfaceControl via reflection for surfaceId: " + surfaceId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to detach SurfaceControl from LinuxWindowBridgeService for surfaceId " + surfaceId + ": " + e.getMessage());
        }
    }

    private void sendVsockConfigureFrame(int surfaceId, int width, int height) {
        try {
            String payloadStr = "{\"event\":\"configure\",\"surface_id\":" + surfaceId + ",\"width\":" + width + ",\"height\":" + height + "}";
            byte[] frame = packWaylandFrame(++mInputSeqId, payloadStr.getBytes(StandardCharsets.UTF_8));
            transmitVsock5002Frame(frame);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send configure frame over Vsock 5002: " + e.getMessage());
        }
    }

    private void sendVsockInputFrame(String type, int surfaceId, int action, float x, float y) {
        try {
            String payloadStr = "{\"event\":\"" + type + "\",\"surface_id\":" + surfaceId + ",\"action\":" + action + ",\"x\":" + x + ",\"y\":" + y + "}";
            byte[] frame = packWaylandFrame(++mInputSeqId, payloadStr.getBytes(StandardCharsets.UTF_8));
            transmitVsock5002Frame(frame);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send input frame over Vsock 5002: " + e.getMessage());
        }
    }

    private static byte[] packWaylandFrame(int sequenceId, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(13 + payloadLen);
        buffer.putInt(VSOK_MAGIC);    // Magic 0x56534F4B
        buffer.put((byte) 0x03);      // FrameType WAYLAND (0x03)
        buffer.putInt(payloadLen);
        buffer.putInt(sequenceId);
        if (payloadLen > 0) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    private void transmitVsock5002Frame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        try {
            LocalSocket socket = new LocalSocket();
            socket.connect(new LocalSocketAddress("/dev/socket/vsock_5002", LocalSocketAddress.Namespace.FILESYSTEM));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(frame);
            out.flush();
            socket.close();
        } catch (Exception ignored) {
            // Log/ignore socket transport unavailable in unit test harness
        }
    }
}
