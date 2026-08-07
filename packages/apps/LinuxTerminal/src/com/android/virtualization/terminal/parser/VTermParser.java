package com.android.virtualization.terminal.parser;

import android.util.Log;

/**
 * JNI Java Bridge for libvterm Parser with 10,000-line Ring Scrollback Buffer and multi-byte UTF-8 buffering.
 */
public class VTermParser {
    private static final String TAG = "VTermParser";

    static {
        System.loadLibrary("vterm_jni");
    }

    private long mNativePtr = 0;
    private final TerminalCallback mCallback;

    public interface TerminalCallback {
        void onDamage(int startRow, int endRow, int startCol, int endCol);
        void onCursorMove(int row, int col, boolean visible);
        void onBell();
        void onTitleChanged(String title);
        void onAltScreenChanged(boolean isAltScreen);
        void onMouseTrackingChanged(boolean enabled);
    }

    public VTermParser(int rows, int cols) {
        this(rows, cols, null);
    }

    public VTermParser(int rows, int cols, TerminalCallback callback) {
        this.mCallback = callback;
        this.mNativePtr = nativeInit(rows, cols, callback);
    }

    public synchronized void write(byte[] data, int length) {
        if (mNativePtr != 0 && data != null && length > 0) {
            nativeWrite(mNativePtr, data, length);
        }
    }

    public synchronized void processOutput(byte[] data) {
        if (data != null) {
            write(data, data.length);
        }
    }

    public synchronized void processOutput(byte[] data, int length) {
        write(data, length);
    }

    public synchronized void writeInput(byte[] data) {
        if (data != null) {
            write(data, data.length);
        }
    }

    public synchronized void resize(int rows, int cols) {
        if (mNativePtr != 0) {
            nativeResize(mNativePtr, rows, cols);
        }
    }

    public synchronized void getScreenMatrix(int[] codepoints, int[] fgColors, int[] bgColors, int[] attrs, int[] widths) {
        if (mNativePtr != 0) {
            nativeGetScreenMatrix(mNativePtr, codepoints, fgColors, bgColors, attrs, widths);
        }
    }

    public synchronized void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    // Native JNI Interface Declarations
    private native long nativeInit(int rows, int cols, TerminalCallback callback);
    private native void nativeWrite(long ptr, byte[] data, int length);
    private native void nativeResize(long ptr, int rows, int cols);
    private native void nativeGetScreenMatrix(long ptr, int[] codepoints, int[] fgColors, int[] bgColors, int[] attrs, int[] widths);
    private native void nativeDestroy(long ptr);
}
