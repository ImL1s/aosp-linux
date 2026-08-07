package android.view.inputmethod;

public class CursorAnchorInfo {
    public static class Builder {
        public Builder setInsertionMarkerLocation(float horizontalPosition, float lineTop, float lineBaseline, float lineBottom, int flags) {
            return this;
        }
        public CursorAnchorInfo build() { return new CursorAnchorInfo(); }
    }
}
