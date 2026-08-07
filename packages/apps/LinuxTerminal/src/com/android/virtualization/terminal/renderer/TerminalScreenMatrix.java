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

    private final Rect mDirtyRect = new Rect();
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
            mDirtyRect.set(col, row, col + 1, row + 1);
            mHasDirty = true;
        } else {
            mDirtyRect.left = Math.min(mDirtyRect.left, col);
            mDirtyRect.top = Math.min(mDirtyRect.top, row);
            mDirtyRect.right = Math.max(mDirtyRect.right, col + 1);
            mDirtyRect.bottom = Math.max(mDirtyRect.bottom, row + 1);
        }
    }

    public synchronized void markDirtyRegion(int startRow, int endRow, int startCol, int endCol) {
        int r1 = Math.max(0, Math.min(mRows, startRow));
        int r2 = Math.max(0, Math.min(mRows, endRow));
        int c1 = Math.max(0, Math.min(mCols, startCol));
        int c2 = Math.max(0, Math.min(mCols, endCol));

        if (!mHasDirty) {
            mDirtyRect.set(c1, r1, c2, r2);
            mHasDirty = true;
        } else {
            mDirtyRect.left = Math.min(mDirtyRect.left, c1);
            mDirtyRect.top = Math.min(mDirtyRect.top, r1);
            mDirtyRect.right = Math.max(mDirtyRect.right, c2);
            mDirtyRect.bottom = Math.max(mDirtyRect.bottom, r2);
        }
    }

    public synchronized void markAllDirty() {
        mDirtyRect.set(0, 0, mCols, mRows);
        mHasDirty = true;
    }

    public synchronized boolean getAndClearDirtyRect(Rect outRect) {
        if (!mHasDirty) {
            return false;
        }
        outRect.set(mDirtyRect);
        mHasDirty = false;
        return true;
    }
}
