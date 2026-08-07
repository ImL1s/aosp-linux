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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Slog;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AudioFocus Policy Handler managing automatic Linux audio ducking, pausing, and stopping.
 * Handles virtio-snd PCM audio buffer management, volume scaling, format conversion, and mixing.
 * {@hide}
 */
public class LinuxAudioPolicyHandler implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "LinuxAudioPolicyHandler";

    private final Context mContext;
    private final AudioManager mAudioManager;
    private AudioFocusRequest mFocusRequest;

    private float mCurrentVolumeFactor = 1.0f;
    private boolean mIsPaused = false;
    private String mCurrentFocusState = "NONE";
    private String mSavedFocusState = "NONE";
    private String mPreTransientFocusState = "NONE";

    private final ConcurrentLinkedQueue<String> mAudioBufferQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_AUDIO_QUEUE = 100;

    public LinuxAudioPolicyHandler(Context context) {
        mContext = context;
        if (context != null) {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } else {
            mAudioManager = null;
        }
    }

    public boolean requestAudioFocus(boolean isBackground, boolean hasForegroundSvc) {
        if (isBackground && !hasForegroundSvc) {
            Slog.w(TAG, "Audio focus request rejected: app backgrounded without foreground service");
            return false;
        }

        if (mAudioManager != null) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this)
                    .build();

            int res = mAudioManager.requestAudioFocus(mFocusRequest);
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mCurrentFocusState = "GAIN";
                mCurrentVolumeFactor = 1.0f;
                mIsPaused = false;
                return true;
            }
            mCurrentFocusState = "NONE";
            return false;
        }

        // Default mock / direct mode
        mCurrentFocusState = "GAIN";
        mCurrentVolumeFactor = 1.0f;
        mIsPaused = false;
        return true;
    }

    public void abandonAudioFocus() {
        if (mAudioManager != null && mFocusRequest != null) {
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
        }
        mCurrentFocusState = "NONE";
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Slog.i(TAG, "AudioFocus GAIN delivered");
                if ("LOSS_TRANSIENT_CAN_DUCK".equals(mPreTransientFocusState)) {
                    Slog.i(TAG, "Restoring to ducked state (0.2f volume) because call is still active");
                    mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK";
                    mCurrentVolumeFactor = 0.2f;
                    mIsPaused = false;
                } else {
                    mCurrentFocusState = "GAIN";
                    mCurrentVolumeFactor = 1.0f;
                    mIsPaused = false;
                }
                mPreTransientFocusState = "NONE";
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK -> ducking volume to 0.2");
                mCurrentFocusState = "LOSS_TRANSIENT_CAN_DUCK";
                mCurrentVolumeFactor = 0.2f;
                mIsPaused = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Slog.i(TAG, "AudioFocus LOSS_TRANSIENT -> pausing audio playback");
                mPreTransientFocusState = mCurrentFocusState;
                mCurrentFocusState = "LOSS_TRANSIENT";
                mIsPaused = true;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Slog.i(TAG, "AudioFocus LOSS -> stopping audio stream");
                mCurrentFocusState = "LOSS";
                mIsPaused = true;
                mPreTransientFocusState = "NONE";
                abandonAudioFocus();
                break;
            default:
                break;
        }
    }

    public void setFocusState(String state) {
        mCurrentFocusState = state;
        if ("LOSS_TRANSIENT_CAN_DUCK".equals(state)) {
            mCurrentVolumeFactor = 0.2f;
            mIsPaused = false;
        } else if ("LOSS_TRANSIENT".equals(state)) {
            mIsPaused = true;
        } else if ("LOSS".equals(state)) {
            mIsPaused = true;
        } else if ("GAIN".equals(state)) {
            mCurrentVolumeFactor = 1.0f;
            mIsPaused = false;
        }
    }

    public void saveFocusState() {
        mSavedFocusState = mCurrentFocusState;
    }

    public void restoreFocusState() {
        setFocusState(mSavedFocusState);
    }

    public float getVolumeFactor() {
        return mCurrentVolumeFactor;
    }

    public boolean isPaused() {
        return mIsPaused;
    }

    public String getFocusState() {
        return mCurrentFocusState;
    }

    // Audio sample conversions & mixing helpers
    public float convertInt16ToFloat32(short sample) {
        return sample / 32768.0f;
    }

    public float mixAudioSamples(float s1, float s2) {
        return Math.min(1.0f, Math.max(-1.0f, s1 + s2));
    }

    public byte[] generateZeroFillSilence(int requiredBytes) {
        byte[] silence = new byte[requiredBytes];
        java.util.Arrays.fill(silence, (byte) 0);
        return silence;
    }

    public void enqueueFrame(String frame) {
        mAudioBufferQueue.add(frame);
        while (mAudioBufferQueue.size() > MAX_AUDIO_QUEUE) {
            mAudioBufferQueue.poll();
        }
    }

    public int getQueueSize() {
        return mAudioBufferQueue.size();
    }
}

