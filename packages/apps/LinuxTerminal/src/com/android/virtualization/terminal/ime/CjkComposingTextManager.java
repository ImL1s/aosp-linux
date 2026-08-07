package com.android.virtualization.terminal.ime;

/**
 * Manages CJK IME Composing Text state (Zhuyin / Cangjie / Pinyin) with buffering and cursor tracking.
 */
public class CjkComposingTextManager {
    public static final int MAX_COMPOSING_LENGTH = 256;

    private final StringBuilder mComposingBuffer = new StringBuilder();
    private int mCursorPosition = 0;

    public synchronized void setComposingText(CharSequence text, int newCursorPosition) {
        mComposingBuffer.setLength(0);
        if (text != null) {
            String s = text.toString();
            if (s.length() > MAX_COMPOSING_LENGTH) {
                s = s.substring(0, MAX_COMPOSING_LENGTH);
            }
            mComposingBuffer.append(s);
        }

        int len = mComposingBuffer.length();
        int targetCursor;
        if (newCursorPosition > 0) {
            targetCursor = len + (newCursorPosition - 1);
        } else {
            targetCursor = newCursorPosition;
        }
        mCursorPosition = Math.max(0, Math.min(len, targetCursor));
    }

    public synchronized String getComposingText() {
        return mComposingBuffer.toString();
    }

    public synchronized int getCursorPosition() {
        return mCursorPosition;
    }

    public synchronized boolean isComposing() {
        return mComposingBuffer.length() > 0;
    }

    public synchronized void deleteBeforeCursor(int length) {
        int bufferLen = mComposingBuffer.length();
        if (bufferLen == 0 || length <= 0) {
            return;
        }

        mCursorPosition = Math.max(0, Math.min(bufferLen, mCursorPosition));
        int deleteCount = Math.min(mCursorPosition, length);
        if (deleteCount <= 0) {
            return;
        }

        int start = Math.max(0, mCursorPosition - deleteCount);
        int end = Math.min(bufferLen, mCursorPosition);

        if (start < end) {
            mComposingBuffer.delete(start, end);
            mCursorPosition = start;
        }
    }

    public synchronized void clear() {
        mComposingBuffer.setLength(0);
        mCursorPosition = 0;
    }
}
