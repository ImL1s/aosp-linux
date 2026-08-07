package com.android.virtualization.terminal.renderer;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import com.android.virtualization.terminal.ime.TerminalInputConnection;
import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.touch.SgrMouseProtocolGenerator;
import com.android.virtualization.terminal.touch.TouchModeStateMachine;
import com.android.virtualization.terminal.touch.TouchpadController;

/**
 * SurfaceView Terminal Component binding Canvas Renderer, Touch State Machine, SGR Mouse Generator, and Custom InputConnection.
 */
public class TerminalSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private TerminalScreenMatrix mScreenMatrix;
    private NativeSurfaceCanvasRenderer mRenderer;
    private TouchModeStateMachine mStateMachine;
    private SgrMouseProtocolGenerator mSgrGenerator;
    private TouchpadController mTouchpadController;
    private PtySender mPtySender;
    private TerminalInputConnection mInputConnection;

    public TerminalSurfaceView(Context context) {
        super(context);
        init(context);
    }

    public TerminalSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setFocusable(true);
        setFocusableInTouchMode(true);

        mScreenMatrix = new TerminalScreenMatrix(24, 80);
        mStateMachine = new TouchModeStateMachine(context);
        mSgrGenerator = new SgrMouseProtocolGenerator();
        mTouchpadController = new TouchpadController(context);

        mStateMachine.addListener((oldMode, newMode, isManual) -> {
            boolean isMouseMode = (newMode == TouchModeStateMachine.TouchMode.TUI_MOUSE_MODE
                                || newMode == TouchModeStateMachine.TouchMode.TOUCHPAD_MODE);
            mSgrGenerator.setMouseTrackingEnabled(isMouseMode);
        });

        getHolder().addCallback(this);
        mRenderer = new NativeSurfaceCanvasRenderer(getHolder(), mScreenMatrix);
    }

    public void setPtySender(PtySender ptySender) {
        this.mPtySender = ptySender;
    }

    public TerminalScreenMatrix getScreenMatrix() {
        return mScreenMatrix;
    }

    public TouchModeStateMachine getTouchModeStateMachine() {
        return mStateMachine;
    }

    public SgrMouseProtocolGenerator getSgrMouseProtocolGenerator() {
        return mSgrGenerator;
    }

    public TouchpadController getTouchpadController() {
        return mTouchpadController;
    }

    public NativeSurfaceCanvasRenderer getRenderer() {
        return mRenderer;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mRenderer.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mRenderer.onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mRenderer.stop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchModeStateMachine.TouchMode mode = mStateMachine.getCurrentMode();

        switch (mode) {
            case SHELL_MODE:
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    requestFocus();
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
                return true;

            case TUI_MOUSE_MODE:
                if (mPtySender != null) {
                    byte[] sgrBytes = mSgrGenerator.processMotionEvent(
                        event,
                        (int) mRenderer.getCellWidth(),
                        (int) mRenderer.getCellHeight(),
                        mScreenMatrix.getCols(),
                        mScreenMatrix.getRows()
                    );
                    if (sgrBytes.length > 0) {
                        mPtySender.sendBytes(sgrBytes);
                    }
                }
                return true;

            case TOUCHPAD_MODE:
                if (mPtySender != null) {
                    return mTouchpadController.handleTouchpadEvent(
                        event,
                        (int) mRenderer.getCellWidth(),
                        (int) mRenderer.getCellHeight(),
                        mScreenMatrix.getCols(),
                        mScreenMatrix.getRows(),
                        mPtySender,
                        mSgrGenerator
                    );
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE;

        mInputConnection = new TerminalInputConnection(this, true, mPtySender);
        mInputConnection.setComposingListener(new TerminalInputConnection.ComposingListener() {
            @Override
            public void onComposingTextUpdated(String composingText, int cursorPosition) {
                mRenderer.setInlineComposing(composingText, cursorPosition);
            }

            @Override
            public void onComposingCleared() {
                mRenderer.setInlineComposing("", 0);
            }
        });

        return mInputConnection;
    }
}
