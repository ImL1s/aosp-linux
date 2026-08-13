package com.android.virtualization.terminal.renderer;

import android.graphics.Rect;

/**
 * Double-buffered Terminal Cell Grid Matrix with dirty rect tracking.
 */
public class TerminalScreenMatrix {
    private int mRows;
    private int mCols;

    private TerminalCell[][] mCells;
    private int mCursorRow = 0;
    private int mCursorCol = 0;
    private boolean mCursorVisible = true;

    private int mDirtyLeft, mDirtyTop, mDirtyRight, mDirtyBottom;
    private boolean mHasDirty = false;

    public TerminalScreenMatrix(int rows, int cols) {
        resize(rows, cols);
    }

    public synchronized void resize(int rows, int cols) {
        this.mRows = Math.max(1, rows);
        this.mCols = Math.max(1, cols);

        mCells = new TerminalCell[mRows][mCols];
        for (int r = 0; r < mRows; r++) {
            for (int c = 0; c < mCols; c++) {
                mCells[r][c] = new TerminalCell();
            }
        }
        markAllDirty();
    }

    public synchronized int getRows() {
        return mRows;
    }

    public synchronized int getCols() {
        return mCols;
    }

    public synchronized TerminalCell getCell(int row, int col) {
        if (row >= 0 && row < mRows && col >= 0 && col < mCols) {
            return mCells[row][col];
        }
        return null;
    }

    public synchronized void setCursor(int row, int col, boolean visible) {
        this.mCursorRow = Math.max(0, Math.min(mRows - 1, row));
        this.mCursorCol = Math.max(0, Math.min(mCols - 1, col));
        this.mCursorVisible = visible;
        markDirtyCell(mCursorRow, mCursorCol);
    }

    public synchronized int getCursorRow() {
        return mCursorRow;
    }

    public synchronized int getCursorCol() {
        return mCursorCol;
    }

    public synchronized boolean isCursorVisible() {
        return mCursorVisible;
    }

    public synchronized void markDirtyCell(int row, int col) {
        if (!mHasDirty) {
            mDirtyLeft = col;
            mDirtyTop = row;
            mDirtyRight = col + 1;
            mDirtyBottom = row + 1;
            mHasDirty = true;
        } else {
            mDirtyLeft = Math.min(mDirtyLeft, col);
            mDirtyTop = Math.min(mDirtyTop, row);
            mDirtyRight = Math.max(mDirtyRight, col + 1);
            mDirtyBottom = Math.max(mDirtyBottom, row + 1);
        }
    }

    public synchronized void markDirtyRegion(int startRow, int endRow, int startCol, int endCol) {
        int r1 = Math.max(0, Math.min(mRows, startRow));
        int r2 = Math.max(0, Math.min(mRows, endRow));
        int c1 = Math.max(0, Math.min(mCols, startCol));
        int c2 = Math.max(0, Math.min(mCols, endCol));

        if (!mHasDirty) {
            mDirtyLeft = c1;
            mDirtyTop = r1;
            mDirtyRight = c2;
            mDirtyBottom = r2;
            mHasDirty = true;
        } else {
            mDirtyLeft = Math.min(mDirtyLeft, c1);
            mDirtyTop = Math.min(mDirtyTop, r1);
            mDirtyRight = Math.max(mDirtyRight, c2);
            mDirtyBottom = Math.max(mDirtyBottom, r2);
        }
    }

    public synchronized void markAllDirty() {
        mDirtyLeft = 0;
        mDirtyTop = 0;
        mDirtyRight = mCols;
        mDirtyBottom = mRows;
        mHasDirty = true;
    }

    public synchronized boolean getAndClearDirtyRect(Rect outRect) {
        if (!mHasDirty) {
            return false;
        }
        if (outRect != null) {
            try {
                outRect.left = mDirtyLeft;
                outRect.top = mDirtyTop;
                outRect.right = mDirtyRight;
                outRect.bottom = mDirtyBottom;
            } catch (Throwable ignored) {}
        }
        mHasDirty = false;
        return true;
    }
}
