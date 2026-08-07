package android.graphics;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;

    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    public enum Style {
        FILL,
        STROKE,
        FILL_AND_STROKE
    }

    public static class FontMetrics {
        public float top = -10f;
        public float ascent = -8f;
        public float descent = 2f;
        public float bottom = 4f;
        public float leading = 0f;
    }

    public Paint() {}
    public Paint(int flags) {}
    public Paint(Paint paint) {}

    public void setColor(int color) {}
    public void setTextSize(float textSize) {}
    public void setTextAlign(Align align) {}
    public void setStyle(Style style) {}
    public void setTypeface(Typeface typeface) {}
    public void setAntiAlias(boolean aa) {}
    public void setUnderlineText(boolean underline) {}
    public void setStrokeWidth(float width) {}
    public void setFakeBoldText(boolean bold) {}
    public void setTextSkewX(float skew) {}
    public void setStrikeThruText(boolean strike) {}

    public float measureText(String text) { return text != null ? text.length() * 10f : 0f; }
    public FontMetrics getFontMetrics() { return new FontMetrics(); }
}
