package com.android.virtualization.terminal.renderer;

/**
 * Single cell on Terminal Grid matrix holding character, colors, attributes, and width.
 */
public class TerminalCell {
    public static final int ATTR_BOLD = 1;
    public static final int ATTR_ITALIC = 2;
    public static final int ATTR_UNDERLINE = 4;
    public static final int ATTR_REVERSE = 8;
    public static final int ATTR_STRIKE = 16;
    public static final int ATTR_BLINK = 32;

    public int codepoint = ' ';
    public int fgColor = 0xFFFFFFFF; // White
    public int bgColor = 0xFF000000; // Black
    public int attributes = 0;
    public int width = 1; // 1=Normal, 2=CJK Wide, 0=Continuation cell

    public void reset() {
        this.codepoint = ' ';
        this.fgColor = 0xFFFFFFFF;
        this.bgColor = 0xFF000000;
        this.attributes = 0;
        this.width = 1;
    }

    public void set(int codepoint, int fgColor, int bgColor, int attributes, int width) {
        this.codepoint = codepoint;
        this.fgColor = fgColor;
        this.bgColor = bgColor;
        this.attributes = attributes;
        this.width = width;
    }
}
