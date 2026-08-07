package com.android.virtualization.terminal.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.LruCache;

/**
 * LRU Bitmap Cache for pre-rendered Monospace ASCII Glyphs to accelerate Surface Canvas rendering.
 */
public class GlyphCache {
    private final LruCache<String, Bitmap> mCache;
    private final Paint mPaint;
    private int mCellWidth;
    private int mCellHeight;

    public GlyphCache(int maxCacheSize, Paint paint, int cellWidth, int cellHeight) {
        this.mCache = new LruCache<String, Bitmap>(maxCacheSize) {
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != null && !oldValue.isRecycled()) {
                    oldValue.recycle();
                }
            }
        };
        this.mPaint = new Paint(paint);
        this.mCellWidth = cellWidth;
        this.mCellHeight = cellHeight;
    }

    public void updateMetrics(int cellWidth, int cellHeight, float textSize) {
        if (this.mCellWidth != cellWidth || this.mCellHeight != cellHeight) {
            this.mCellWidth = cellWidth;
            this.mCellHeight = cellHeight;
            this.mPaint.setTextSize(textSize);
            clear();
        }
    }

    public Bitmap getGlyphBitmap(char ch, int fgColor, boolean bold, boolean italic) {
        String key = ch + "_" + fgColor + "_" + (bold ? 1 : 0) + "_" + (italic ? 1 : 0);
        Bitmap bitmap = mCache.get(key);
        if (bitmap == null && mCellWidth > 0 && mCellHeight > 0) {
            bitmap = Bitmap.createBitmap(mCellWidth, mCellHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            mPaint.setColor(fgColor);
            mPaint.setFakeBoldText(bold);
            mPaint.setTextSkewX(italic ? -0.25f : 0f);

            Paint.FontMetrics fm = mPaint.getFontMetrics();
            float baseline = -fm.top;
            canvas.drawText(String.valueOf(ch), 0, baseline, mPaint);
            mCache.put(key, bitmap);
        }
        return bitmap;
    }

    public void clear() {
        mCache.evictAll();
    }
}
