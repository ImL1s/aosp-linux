package com.android.virtualization.terminal.renderer;

/**
 * Terminal ANSI 16 / 256 / TrueColor Palette Manager.
 */
public class ColorPalette {
    public static final int[] PALETTE_256 = new int[256];

    static {
        // Standard 16 ANSI Colors
        PALETTE_256[0]  = 0xFF000000; // Black
        PALETTE_256[1]  = 0xFFCD0000; // Red
        PALETTE_256[2]  = 0xFF00CD00; // Green
        PALETTE_256[3]  = 0xFFCDCD00; // Yellow
        PALETTE_256[4]  = 0xFF0000EE; // Blue
        PALETTE_256[5]  = 0xFFCD00CD; // Magenta
        PALETTE_256[6]  = 0xFF00CDCD; // Cyan
        PALETTE_256[7]  = 0xFFE5E5E5; // Light Gray

        PALETTE_256[8]  = 0xFF7F7F7F; // Dark Gray (Bright Black)
        PALETTE_256[9]  = 0xFFFF0000; // Bright Red
        PALETTE_256[10] = 0xFF00FF00; // Bright Green
        PALETTE_256[11] = 0xFFFFFE00; // Bright Yellow
        PALETTE_256[12] = 0xFF5C5CFF; // Bright Blue
        PALETTE_256[13] = 0xFFFF00FF; // Bright Magenta
        PALETTE_256[14] = 0xFF00FFFF; // Bright Cyan
        PALETTE_256[15] = 0xFFFFFFFF; // White

        // 6x6x6 RGB Color Cube (16..231)
        int[] steps = {0, 95, 135, 175, 215, 255};
        int idx = 16;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    PALETTE_256[idx++] = 0xFF000000 | (steps[r] << 16) | (steps[g] << 8) | steps[b];
                }
            }
        }

        // 24 Grayscale Steps (232..255)
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            PALETTE_256[idx++] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
    }

    public static int getAnsiColor(int index) {
        if (index >= 0 && index < 256) {
            return PALETTE_256[index];
        }
        return 0xFFFFFFFF;
    }

    public static int getTrueColor(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
