package android.view;

public class KeyEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int META_ALT_ON = 2;
    public static final int META_SHIFT_ON = 1;
    public static final int META_CTRL_ON = 4096;

    public static final int KEYCODE_DEL = 67;
    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_NUMPAD_ENTER = 160;
    public static final int KEYCODE_TAB = 61;
    public static final int KEYCODE_ESCAPE = 111;
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_MOVE_HOME = 122;
    public static final int KEYCODE_MOVE_END = 123;
    public static final int KEYCODE_INSERT = 124;
    public static final int KEYCODE_FORWARD_DEL = 112;
    public static final int KEYCODE_PAGE_UP = 92;
    public static final int KEYCODE_PAGE_DOWN = 93;
    public static final int KEYCODE_F1 = 131;
    public static final int KEYCODE_F2 = 132;
    public static final int KEYCODE_F3 = 133;
    public static final int KEYCODE_F4 = 134;
    public static final int KEYCODE_F5 = 135;
    public static final int KEYCODE_F6 = 136;
    public static final int KEYCODE_F7 = 137;
    public static final int KEYCODE_F8 = 138;
    public static final int KEYCODE_F9 = 139;
    public static final int KEYCODE_F10 = 140;
    public static final int KEYCODE_F11 = 141;
    public static final int KEYCODE_F12 = 142;

    public static final int KEYCODE_A = 29;
    public static final int KEYCODE_C = 31;
    public static final int KEYCODE_Z = 54;
    public static final int KEYCODE_LEFT_BRACKET = 71;
    public static final int KEYCODE_BACKSLASH = 73;
    public static final int KEYCODE_RIGHT_BRACKET = 72;
    public static final int KEYCODE_SPACE = 62;

    public int getAction() { return 0; }
    public int getKeyCode() { return 0; }
    public int getMetaState() { return 0; }
    public int getUnicodeChar(int metaState) { return 0; }
    public int getUnicodeChar() { return 0; }
}
