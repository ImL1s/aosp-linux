package com.android.virtualization.terminal.touch;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Touch Modes State Machine for Terminal Engine:
 * - SHELL_MODE: Scrollback history & text selection
 * - TUI_MOUSE_MODE: SGR Terminal Mouse Tracking for Vim/tmux/htop
 * - TOUCHPAD_MODE: Virtual touchpad relative motion controller
 */
public class TouchModeStateMachine {
    public enum TouchMode {
        SHELL_MODE,
        TUI_MOUSE_MODE,
        TOUCHPAD_MODE
    }

    public interface OnTouchModeChangeListener {
        void onTouchModeChanged(TouchMode oldMode, TouchMode newMode, boolean isManual);
    }

    private static final String PREF_NAME = "terminal_touch_prefs";
    private static final String KEY_PREF_MODE = "saved_touch_mode";
    public static final String KEY_PREF_MANUAL_LOCKED = "saved_manual_locked";

    private TouchMode mCurrentMode = TouchMode.SHELL_MODE;
    private boolean mIsManualLocked = false;
    private boolean mMouseTrackingRequested = false;
    private final CopyOnWriteArrayList<OnTouchModeChangeListener> mListeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences mPrefs;

    public TouchModeStateMachine(Context context) {
        if (context != null) {
            mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String saved = mPrefs.getString(KEY_PREF_MODE, TouchMode.SHELL_MODE.name());
            mIsManualLocked = mPrefs.getBoolean(KEY_PREF_MANUAL_LOCKED, false);
            try {
                mCurrentMode = TouchMode.valueOf(saved);
            } catch (Exception e) {
                mCurrentMode = TouchMode.SHELL_MODE;
            }
        } else {
            mPrefs = null;
            mCurrentMode = TouchMode.SHELL_MODE;
        }
    }

    public synchronized TouchMode getCurrentMode() {
        return mCurrentMode;
    }

    public synchronized boolean isManualLocked() {
        return mIsManualLocked;
    }

    public synchronized void setManualTouchMode(TouchMode mode) {
        mIsManualLocked = true;
        if (mPrefs != null) {
            mPrefs.edit().putBoolean(KEY_PREF_MANUAL_LOCKED, true).apply();
        }
        transitionTo(mode, true);
    }

    public synchronized void unlockAutoMode() {
        mIsManualLocked = false;
        if (mPrefs != null) {
            mPrefs.edit().putBoolean(KEY_PREF_MANUAL_LOCKED, false).apply();
        }
        if (mMouseTrackingRequested) {
            transitionTo(TouchMode.TUI_MOUSE_MODE, false);
        } else {
            transitionTo(TouchMode.SHELL_MODE, false);
        }
    }

    public synchronized void onTerminalEscapeMouseTrackingChanged(boolean enabled) {
        mMouseTrackingRequested = enabled;
        if (!mIsManualLocked) {
            TouchMode target = enabled ? TouchMode.TUI_MOUSE_MODE : TouchMode.SHELL_MODE;
            transitionTo(target, false);
        }
    }

    private void transitionTo(TouchMode newMode, boolean isManual) {
        if (mCurrentMode != newMode) {
            TouchMode oldMode = mCurrentMode;
            mCurrentMode = newMode;
            if (mPrefs != null) {
                mPrefs.edit().putString(KEY_PREF_MODE, newMode.name()).apply();
            }
            for (OnTouchModeChangeListener listener : mListeners) {
                listener.onTouchModeChanged(oldMode, newMode, isManual);
            }
        }
    }

    public void addListener(OnTouchModeChangeListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(OnTouchModeChangeListener listener) {
        mListeners.remove(listener);
    }
}
