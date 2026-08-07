package com.android.virtualization.terminal.ime;

/**
 * Visual styling configuration for CJK Inline Composing Text Span (F-R3-004).
 */
public class ComposingTextSpan {
    public static final int BACKGROUND_COLOR = 0xCC223344; // Translucent navy dark gray
    public static final int TEXT_COLOR = 0xFFFFD700;       // Bright yellow
    public static final int UNDERLINE_COLOR = 0xFFFFD700;  // Yellow underline
    public static final float UNDERLINE_THICKNESS_DP = 2.0f;

    private String mText = "";
    private int mCursorIndex = 0;

    public ComposingTextSpan(String text, int cursorIndex) {
        this.mText = (text != null) ? text : "";
        this.mCursorIndex = cursorIndex;
    }

    public String getText() {
        return mText;
    }

    public int getCursorIndex() {
        return mCursorIndex;
    }
}
