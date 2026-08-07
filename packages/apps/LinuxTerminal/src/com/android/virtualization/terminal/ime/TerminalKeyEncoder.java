package com.android.virtualization.terminal.ime;

import android.view.KeyEvent;
import java.nio.charset.StandardCharsets;

/**
 * KeyCode to ANSI / VT100 Escape Sequence Encoder for TerminalInputConnection.
 */
public class TerminalKeyEncoder {

    public static byte[] encodeKeyEvent(KeyEvent event, int metaState, boolean ctrlLatched, boolean altLatched) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return new byte[0];
        }

        int keyCode = event.getKeyCode();
        boolean isCtrl = (metaState & KeyEvent.META_CTRL_ON) != 0 || ctrlLatched;
        boolean isAlt = (metaState & KeyEvent.META_ALT_ON) != 0 || altLatched;
        boolean isShift = (metaState & KeyEvent.META_SHIFT_ON) != 0;

        // Handle Ctrl Combinations
        if (isCtrl) {
            byte[] ctrlSeq = encodeCtrlKey(keyCode);
            if (ctrlSeq.length > 0) {
                return ctrlSeq;
            }
        }

        // Handle Alt Combinations
        if (isAlt) {
            byte[] altSeq = encodeAltKey(keyCode, event);
            if (altSeq.length > 0) {
                return altSeq;
            }
        }

        // Special KeyCodes
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                return new byte[]{(byte) 0x7F}; // ASCII DEL
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return new byte[]{(byte) '\r'};
            case KeyEvent.KEYCODE_TAB:
                if (isShift) {
                    return "\033[Z".getBytes(StandardCharsets.US_ASCII); // Shift+Tab
                }
                return new byte[]{(byte) '\t'};
            case KeyEvent.KEYCODE_ESCAPE:
                return new byte[]{(byte) 0x1B};
            case KeyEvent.KEYCODE_DPAD_UP:
                return "\033[A".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "\033[B".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "\033[C".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "\033[D".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "\033[H".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_MOVE_END:
                return "\033[F".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_INSERT:
                return "\033[2~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "\033[3~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_PAGE_UP:
                return "\033[5~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "\033[6~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F1:
                return "\033OP".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F2:
                return "\033OQ".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F3:
                return "\033OR".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F4:
                return "\033OS".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F5:
                return "\033[15~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F6:
                return "\033[17~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F7:
                return "\033[18~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F8:
                return "\033[19~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F9:
                return "\033[20~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F10:
                return "\033[21~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F11:
                return "\033[23~".getBytes(StandardCharsets.US_ASCII);
            case KeyEvent.KEYCODE_F12:
                return "\033[24~".getBytes(StandardCharsets.US_ASCII);
        }

        // Printable Unicode Character
        int unicodeChar = event.getUnicodeChar(metaState);
        if (unicodeChar != 0) {
            String charStr = new String(Character.toChars(unicodeChar));
            return charStr.getBytes(StandardCharsets.UTF_8);
        }

        return new byte[0];
    }

    public static byte[] encodeCtrlKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            int ctrlCode = (keyCode - KeyEvent.KEYCODE_A) + 1;
            return new byte[]{(byte) ctrlCode};
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return new byte[]{(byte) 0x1B}; // Ctrl+[ -> ESC
            case KeyEvent.KEYCODE_BACKSLASH:
                return new byte[]{(byte) 0x1C}; // Ctrl+\ -> FS
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return new byte[]{(byte) 0x1D}; // Ctrl+] -> GS
            case KeyEvent.KEYCODE_SPACE:
                return new byte[]{(byte) 0x00}; // Ctrl+Space -> NUL
        }
        return new byte[0];
    }

    public static byte[] encodeAltKey(int keyCode, KeyEvent event) {
        int unicodeChar = event.getUnicodeChar();
        if (unicodeChar != 0) {
            byte[] charBytes = new String(Character.toChars(unicodeChar)).getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[1 + charBytes.length];
            result[0] = 0x1B; // ESC prefix
            System.arraycopy(charBytes, 0, result, 1, charBytes.length);
            return result;
        }
        return new byte[0];
    }
}
