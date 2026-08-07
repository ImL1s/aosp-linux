package com.android.virtualization.terminal.ime;

import android.text.SpannableString;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import com.android.virtualization.terminal.net.PtySender;
import java.nio.charset.StandardCharsets;

/**
 * Custom InputConnection for Linux Terminal supporting multi-stage CJK IME commit,
 * keycode translation, and direct PTY Vsock streaming.
 */
public class TerminalInputConnection extends BaseInputConnection {
    private static final String TAG = "TerminalInputConnection";

    private final View mTargetView;
    private final PtySender mPtySender;
    private final CjkComposingTextManager mComposingManager;
    private boolean mCtrlLatched = false;
    private boolean mAltLatched = false;

    public interface ComposingListener {
        void onComposingTextUpdated(String composingText, int cursorPosition);
        void onComposingCleared();
    }

    private ComposingListener mComposingListener;

    public TerminalInputConnection(View targetView, boolean fullEditor, PtySender ptySender) {
        super(targetView, fullEditor);
        this.mTargetView = targetView;
        this.mPtySender = ptySender;
        this.mComposingManager = new CjkComposingTextManager();
    }

    public void setComposingListener(ComposingListener listener) {
        this.mComposingListener = listener;
    }

    public void setCtrlLatched(boolean latched) {
        this.mCtrlLatched = latched;
    }

    public void setAltLatched(boolean latched) {
        this.mAltLatched = latched;
    }

    public CjkComposingTextManager getComposingTextManager() {
        return mComposingManager;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        Log.d(TAG, "Committed IME Text: " + text);
        if (text != null && text.length() > 0) {
            mComposingManager.clear();
            if (mComposingListener != null) {
                mComposingListener.onComposingCleared();
            }

            byte[] utf8Bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            dispatchBytesToPty(utf8Bytes);
        }
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        Log.d(TAG, "IME Composing Preview: " + text);
        if (text == null) {
            text = "";
        }
        mComposingManager.setComposingText(text, newCursorPosition);
        if (mComposingListener != null) {
            if (mComposingManager.isComposing()) {
                mComposingListener.onComposingTextUpdated(mComposingManager.getComposingText(), mComposingManager.getCursorPosition());
            } else {
                mComposingListener.onComposingCleared();
            }
        }
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (mComposingManager.isComposing()) {
            commitText(mComposingManager.getComposingText(), 1);
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (mComposingManager.isComposing()) {
            mComposingManager.deleteBeforeCursor(beforeLength);
            if (mComposingListener != null) {
                if (mComposingManager.isComposing()) {
                    mComposingListener.onComposingTextUpdated(mComposingManager.getComposingText(), mComposingManager.getCursorPosition());
                } else {
                    mComposingListener.onComposingCleared();
                }
            }
        } else {
            // Send Backspace bytes (\x7f) to PTY
            for (int i = 0; i < beforeLength; i++) {
                dispatchBytesToPty(new byte[]{(byte) 0x7F});
            }
        }
        return true;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        if (mComposingManager.isComposing()) {
            String text = mComposingManager.getComposingText();
            int pos = mComposingManager.getCursorPosition();
            int start = Math.max(0, pos - n);
            return text.substring(start, pos);
        }
        return "";
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        if (mComposingManager.isComposing()) {
            String text = mComposingManager.getComposingText();
            int pos = mComposingManager.getCursorPosition();
            int end = Math.min(text.length(), pos + n);
            return text.substring(pos, end);
        }
        return "";
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        return null;
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        ExtractedText et = new ExtractedText();
        et.text = mComposingManager.isComposing() ? mComposingManager.getComposingText() : "";
        et.startOffset = 0;
        et.selectionStart = mComposingManager.getCursorPosition();
        et.selectionEnd = mComposingManager.getCursorPosition();
        return et;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            byte[] encoded = TerminalKeyEncoder.encodeKeyEvent(event, event.getMetaState(), mCtrlLatched, mAltLatched);
            mCtrlLatched = false;
            mAltLatched = false;
            if (encoded.length > 0) {
                dispatchBytesToPty(encoded);
                return true;
            }
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        if (actionCode == EditorInfo.IME_ACTION_DONE ||
            actionCode == EditorInfo.IME_ACTION_GO ||
            actionCode == EditorInfo.IME_ACTION_SEND) {
            dispatchBytesToPty(new byte[]{(byte) '\r'});
            return true;
        }
        return super.performEditorAction(actionCode);
    }

    public void cancelComposing() {
        mComposingManager.clear();
        if (mComposingListener != null) {
            mComposingListener.onComposingCleared();
        }
    }

    private void dispatchBytesToPty(byte[] bytes) {
        if (mPtySender != null && bytes != null && bytes.length > 0) {
            // Chunk large pastes to 1KB blocks
            int chunkSize = 1024;
            for (int i = 0; i < bytes.length; i += chunkSize) {
                int len = Math.min(chunkSize, bytes.length - i);
                byte[] chunk = new byte[len];
                System.arraycopy(bytes, i, chunk, 0, len);
                mPtySender.sendBytes(chunk);
            }
        }
    }
}
