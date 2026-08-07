package com.android.virtualization.terminal.ime;

import android.view.View;
import java.nio.charset.StandardCharsets;
import com.android.virtualization.terminal.net.PtySender;

/**
 * Multi-stage CJK IME Handler & Commit Pipeline (F-R3-004).
 * Supports Zhuyin (注音), Cangjie (倉頡), and Pinyin (拼音) composing windows,
 * inline cursor span rendering, and UTF-8 byte commit pipeline via Vsock Port 5001.
 */
public class CJKImeHandler {
    private final CjkComposingTextManager mComposingManager;
    private final CjkComposingWindow mComposingWindow;
    private final PtySender mPtySender;
    private final View mTargetView;

    public CJKImeHandler(View targetView, PtySender ptySender) {
        mComposingManager = new CjkComposingTextManager();
        mComposingWindow = new CjkComposingWindow();
        mTargetView = targetView;
        mPtySender = ptySender;
    }

    public CjkComposingTextManager getManager() {
        return mComposingManager;
    }

    public CjkComposingWindow getWindow() {
        return mComposingWindow;
    }

    public synchronized boolean setComposingText(CharSequence text, int newCursorPosition, int cursorCol, int cursorRow, int cellW, int cellH) {
        mComposingManager.setComposingText(text, newCursorPosition);
        if (mTargetView != null) {
            mComposingWindow.notifyCursorAnchorInfo(mTargetView, cursorCol, cursorRow, cellW, cellH);
        }
        return true;
    }

    public synchronized boolean commitText(CharSequence text, int newCursorPosition) {
        if (text != null && text.length() > 0) {
            mComposingManager.clear();
            byte[] utf8Bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            if (mPtySender != null) {
                mPtySender.sendBytes(utf8Bytes);
            }
        }
        return true;
    }

    public synchronized void cancelComposing() {
        mComposingManager.clear();
    }
}
