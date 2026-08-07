package android.view.inputmethod;

public interface InputConnection {
    boolean commitText(CharSequence text, int newCursorPosition);
    boolean deleteSurroundingText(int beforeLength, int afterLength);
    boolean sendKeyEvent(android.view.KeyEvent event);
    boolean performEditorAction(int editorAction);
}
