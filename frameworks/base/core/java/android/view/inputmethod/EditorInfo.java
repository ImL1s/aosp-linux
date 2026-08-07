package android.view.inputmethod;

public class EditorInfo {
    public static final int TYPE_CLASS_TEXT = 1;
    public static final int TYPE_TEXT_FLAG_NO_SUGGESTIONS = 2;
    public static final int IME_ACTION_NONE = 0;
    public static final int IME_ACTION_DONE = 2;
    public static final int IME_ACTION_GO = 3;
    public static final int IME_ACTION_SEND = 4;

    public int inputType;
    public int imeOptions;
}
