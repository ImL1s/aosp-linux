package com.android.virtualization.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import java.io.IOException;

import com.android.virtualization.terminal.ime.TerminalInputConnection;
import com.android.virtualization.terminal.net.PtySender;
import com.android.virtualization.terminal.net.VsockPtyFramer;
import com.android.virtualization.terminal.net.VsockTerminalClient;
import com.android.virtualization.terminal.parser.VTermParser;
import com.android.virtualization.terminal.touch.SgrMouseProtocolGenerator;
import com.android.virtualization.terminal.touch.TouchModeManager;
import com.android.virtualization.terminal.touch.TouchModeStateMachine;
import com.android.virtualization.terminal.touch.TouchpadController;

/**
 * Native Touch Terminal View integrating all M3 Features (F-R3-001 through F-R3-007).
 */
public class TerminalView extends View implements PtySender {
    private static final String TAG = "TerminalView";
    public static final int GUEST_CID = 3;
    public static final int VSOCK_PORT_5001 = 5001;

    private final TouchModeManager mTouchModeManager;
    private final SgrMouseProtocolGenerator mSgrMouseGenerator;
    private final TouchpadController mTouchpadController;
    private final VTermParser mVTermParser;
    private final VsockTerminalClient mVsockClient;

    private TerminalInputConnection mInputConnection;
    private Paint mTextPaint;

    private int mColumns = 80;
    private int mRows = 24;
    private int mCellWidth = 20;
    private int mCellHeight = 40;

    private byte[] mSessionId = "0123456789abcdef".getBytes();

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchModeManager = new TouchModeManager(context);
        mSgrMouseGenerator = new SgrMouseProtocolGenerator();
        mTouchpadController = new TouchpadController(context);
        mVTermParser = new VTermParser(mRows, mColumns);
        mVsockClient = new VsockTerminalClient();
        initView();
    }

    private void initView() {
        setFocusable(true);
        setFocusableInTouchMode(true);

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.GREEN);
        mTextPaint.setTextSize(36f);
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setAntiAlias(true);

        mTouchModeManager.getStateMachine().addListener((oldMode, newMode, isManual) -> {
            boolean isMouseMode = (newMode == TouchModeStateMachine.TouchMode.TUI_MOUSE_MODE 
                                || newMode == TouchModeStateMachine.TouchMode.TOUCHPAD_MODE);
            mSgrMouseGenerator.setMouseTrackingEnabled(isMouseMode);
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        connectVsock(GUEST_CID, mSessionId);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mVsockClient != null) {
            mVsockClient.close();
        }
    }

    public TouchModeStateMachine.TouchMode getTouchMode() {
        return mTouchModeManager.getStateMachine().getCurrentMode();
    }

    public void setTouchMode(TouchModeStateMachine.TouchMode mode) {
        mTouchModeManager.getStateMachine().setManualTouchMode(mode);
        invalidate();
    }

    public TouchModeManager getTouchModeManager() {
        return mTouchModeManager;
    }

    public SgrMouseProtocolGenerator getSgrMouseGenerator() {
        return mSgrMouseGenerator;
    }

    public TouchpadController getTouchpadController() {
        return mTouchpadController;
    }

    public VTermParser getVTermParser() {
        return mVTermParser;
    }

    public VsockTerminalClient getVsockTerminalClient() {
        return mVsockClient;
    }

    public void connectVsock(int guestCid, byte[] sessionId) {
        if (sessionId != null && sessionId.length == 16) {
            this.mSessionId = sessionId;
        }
        try {
            mVsockClient.connect(guestCid, mSessionId, new VsockTerminalClient.TerminalStreamListener() {
                @Override
                public void onDataReceived(byte[] data) {
                    if (mVTermParser != null && data != null && data.length > 0) {
                        mVTermParser.writeInput(data);
                        postInvalidate();
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Vsock terminal stream error", e);
                }
            });
            Log.i(TAG, "VsockTerminalClient connected to CID " + guestCid + ":" + VSOCK_PORT_5001);
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect Vsock terminal client to CID " + guestCid, e);
        }
    }

    @Override
    public void sendBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
        try {
            mVsockClient.sendFrame(frame);
            Log.d(TAG, "Transmitted DATA frame (" + frame.length + " bytes) over AF_VSOCK 5001");
        } catch (IOException e) {
            Log.e(TAG, "Failed to send PTY data frame over Vsock Port 5001", e);
        }
    }

    @Override
    public void sendFrame(byte[] sessionId, VsockPtyFramer.PacketType type, byte[] payload) {
        byte[] frame = VsockPtyFramer.serializeFrame(sessionId, type, payload);
        try {
            mVsockClient.sendFrame(frame);
            Log.d(TAG, "Transmitted frame type " + type + " (" + frame.length + " bytes) over AF_VSOCK 5001");
        } catch (IOException e) {
            Log.e(TAG, "Failed to send PTY frame type " + type + " over Vsock Port 5001", e);
        }
    }

    @Override
    public void sendResize(byte[] sessionId, int cols, int rows) {
        byte[] frame = VsockPtyFramer.serializeResizeFrame(sessionId, cols, rows);
        try {
            mVsockClient.sendFrame(frame);
            Log.d(TAG, "Transmitted RESIZE frame (" + cols + "x" + rows + ") over AF_VSOCK 5001");
        } catch (IOException e) {
            Log.e(TAG, "Failed to send PTY resize frame over Vsock Port 5001", e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);

        // Draw terminal cell matrix from VTermParser
        int[] codepoints = new int[mRows * mColumns];
        int[] fgColors = new int[mRows * mColumns];
        int[] bgColors = new int[mRows * mColumns];
        int[] attrs = new int[mRows * mColumns];
        int[] widths = new int[mRows * mColumns];

        mVTermParser.getScreenMatrix(codepoints, fgColors, bgColors, attrs, widths);

        for (int r = 0; r < mRows; r++) {
            for (int c = 0; c < mColumns; c++) {
                int idx = r * mColumns + c;
                int cp = codepoints[idx];
                if (cp != 0 && cp != ' ') {
                    float left = c * mCellWidth;
                    float top = (r + 1) * mCellHeight;
                    mTextPaint.setColor(fgColors[idx] != 0 ? fgColors[idx] : Color.GREEN);
                    canvas.drawText(new String(Character.toChars(cp)), left, top, mTextPaint);
                }
            }
        }

        // Draw Touch Mode Visual Badge Overlay (F-R3-005)
        mTouchModeManager.drawBadge(canvas, getWidth(), getHeight());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TouchModeStateMachine.TouchMode mode = mTouchModeManager.getStateMachine().getCurrentMode();

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
                byte[] sgrBytes = mSgrMouseGenerator.processMotionEvent(event, mCellWidth, mCellHeight, mColumns, mRows);
                if (sgrBytes.length > 0) {
                    sendBytes(sgrBytes);
                }
                return true;

            case TOUCHPAD_MODE:
                return mTouchpadController.handleTouchpadEvent(
                    event, mCellWidth, mCellHeight, mColumns, mRows, this, mSgrMouseGenerator
                );
        }

        return super.onTouchEvent(event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE;
        mInputConnection = new TerminalInputConnection(this, true, this);
        return mInputConnection;
    }
}
