#ifndef VTERM_PARSER_H
#define VTERM_PARSER_H

#include "libvterm/include/vterm.h"
#include "terminal_renderer.h"
#include <vector>
#include <deque>
#include <mutex>
#include <string>

class VTermParserBridge {
public:
    VTermParserBridge(int rows, int cols);
    ~VTermParserBridge();

    void feedBytes(const uint8_t* data, size_t length);
    void resize(int rows, int cols);

    bool isAltScreenActive() const { return mIsAltScreen; }
    void getScreenGrid(std::vector<TerminalCellNative>& outCells, int& outCols, int& outRows);

    size_t getScrollbackLineCount() const;
    std::vector<TerminalCellNative> getScrollbackLine(size_t index) const;
    void clearScrollback();

    int getRows() const { return mRows; }
    int getCols() const { return mCols; }

private:
    VTerm* mVterm = nullptr;
    VTermScreen* mVtermScreen = nullptr;
    int mRows = 24;
    int mCols = 80;
    bool mIsAltScreen = false;

    static constexpr size_t MAX_SCROLLBACK_LINES = 10000;
    std::deque<std::vector<TerminalCellNative>> mScrollbackBuffer;
    std::vector<uint8_t> mUtf8PartialBuffer;

    mutable std::mutex mStateMutex;

    // Callbacks
    static int cbDamage(VTermRect rect, void* user);
    static int cbMoveCursor(VTermPos pos, VTermPos oldpos, int visible, void* user);
    static int cbSetTermProp(int prop, void* val, void* user);
    static int cbPushLine(int cols, const VTermScreenCell* cells, void* user);
    static int cbPopLine(int cols, VTermScreenCell* cells, void* user);
};

#endif // VTERM_PARSER_H
