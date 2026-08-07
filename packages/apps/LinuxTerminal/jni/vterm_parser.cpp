#include "vterm_parser.h"
#if __has_include(<android/log.h>)
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) printf("[" tag "] " __VA_ARGS__)
#endif
#include <cstring>
#include <cstdlib>

#define LOG_TAG "VTermParser"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

VTermParserBridge::VTermParserBridge(int rows, int cols)
    : mRows(rows), mCols(cols) {
    mVterm = vterm_new(rows, cols);
    if (mVterm) {
        vterm_set_utf8(mVterm, 1);
        mVtermScreen = vterm_obtain_screen(mVterm);
        VTermScreenCallbacks callbacks;
        memset(&callbacks, 0, sizeof(callbacks));
        callbacks.damage = cbDamage;
        callbacks.movecursor = cbMoveCursor;
        callbacks.settermprop = cbSetTermProp;
        callbacks.sb_pushline = cbPushLine;
        callbacks.sb_popline = cbPopLine;
        vterm_screen_set_callbacks(mVtermScreen, &callbacks, this);
        vterm_screen_reset(mVtermScreen, 1);
    }
}

VTermParserBridge::~VTermParserBridge() {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (mVterm) {
        vterm_free(mVterm);
        mVterm = nullptr;
        mVtermScreen = nullptr;
    }
}

void VTermParserBridge::feedBytes(const uint8_t* data, size_t length) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (!mVterm || length == 0) return;

    std::vector<uint8_t> buffer = mUtf8PartialBuffer;
    buffer.insert(buffer.end(), data, data + length);
    mUtf8PartialBuffer.clear();

    size_t validLen = buffer.size();
    size_t searchLimit = (buffer.size() > 4) ? buffer.size() - 4 : 0;
    size_t i = buffer.size();

    while (i > searchLimit) {
        i--;
        uint8_t b = buffer[i];
        if ((b & 0x80) == 0) {
            // ASCII byte
            break;
        }
        if ((b & 0xE0) == 0xC0) {
            // 2-byte UTF-8 lead byte
            if (buffer.size() - i < 2) {
                validLen = i;
            }
            break;
        }
        if ((b & 0xF0) == 0xE0) {
            // 3-byte UTF-8 lead byte
            if (buffer.size() - i < 3) {
                validLen = i;
            }
            break;
        }
        if ((b & 0xF8) == 0xF0) {
            // 4-byte UTF-8 lead byte
            if (buffer.size() - i < 4) {
                validLen = i;
            }
            break;
        }
        // Continuation byte (0x80~0xBF): Continue scanning backwards WITHOUT decrementing validLen.
    }

    if (validLen < buffer.size()) {
        mUtf8PartialBuffer.assign(buffer.begin() + validLen, buffer.end());
    }

    if (validLen > 0) {
        vterm_input_write(mVterm, reinterpret_cast<const char*>(buffer.data()), validLen);
    }
}

void VTermParserBridge::resize(int rows, int cols) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mRows = rows;
    mCols = cols;
    if (mVterm) {
        vterm_set_size(mVterm, rows, cols);
    }
}

void VTermParserBridge::getScreenGrid(std::vector<TerminalCellNative>& outCells, int& outCols, int& outRows) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    outRows = mRows;
    outCols = mCols;
    outCells.resize(mRows * mCols);

    if (mVtermScreen) {
        for (int r = 0; r < mRows; ++r) {
            for (int c = 0; c < mCols; ++c) {
                size_t idx = r * mCols + c;
                VTermPos pos = {.row = r, .col = c};
                VTermScreenCell cell;
                vterm_screen_get_cell(mVtermScreen, pos, &cell);
                outCells[idx].codepoint = cell.chars[0] ? cell.chars[0] : ' ';
                outCells[idx].fg_color = 0xFF000000 | (cell.fg.red << 16) | (cell.fg.green << 8) | cell.fg.blue;
                outCells[idx].bg_color = 0xFF000000 | (cell.bg.red << 16) | (cell.bg.green << 8) | cell.bg.blue;
                outCells[idx].attributes = (cell.attrs.bold ? 1 : 0) |
                                          ((cell.attrs.italic ? 1 : 0) << 1) |
                                          ((cell.attrs.underline ? 1 : 0) << 2) |
                                          ((cell.attrs.reverse ? 1 : 0) << 3) |
                                          ((cell.attrs.strike ? 1 : 0) << 4) |
                                          ((cell.attrs.blink ? 1 : 0) << 5);
                outCells[idx].width = cell.width;
            }
        }
    } else {
        std::fill(outCells.begin(), outCells.end(), TerminalCellNative{32, 0xFFFFFFFF, 0xFF000000, 0, 1});
    }
}

size_t VTermParserBridge::getScrollbackLineCount() const {
    std::lock_guard<std::mutex> lock(mStateMutex);
    return mScrollbackBuffer.size();
}

std::vector<TerminalCellNative> VTermParserBridge::getScrollbackLine(size_t index) const {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (index < mScrollbackBuffer.size()) {
        return mScrollbackBuffer[index];
    }
    return std::vector<TerminalCellNative>(mCols, {32, 0xFFFFFFFF, 0xFF000000, 0, 1});
}

void VTermParserBridge::clearScrollback() {
    std::lock_guard<std::mutex> lock(mStateMutex);
    mScrollbackBuffer.clear();
}

int VTermParserBridge::cbDamage(VTermRect rect, void* user) { return 0; }
int VTermParserBridge::cbMoveCursor(VTermPos pos, VTermPos oldpos, int visible, void* user) { return 0; }

int VTermParserBridge::cbSetTermProp(int prop, void* val, void* user) {
    auto* self = reinterpret_cast<VTermParserBridge*>(user);
    if (self && val) {
        if (prop == 1049 || prop == 3) {
            int enabled = *reinterpret_cast<int*>(val);
            self->mIsAltScreen = (enabled != 0);
        }
    }
    return 1;
}

int VTermParserBridge::cbPushLine(int cols, const VTermScreenCell* cells, void* user) {
    auto* self = reinterpret_cast<VTermParserBridge*>(user);
    if (!self || self->mIsAltScreen) return 0;

    std::vector<TerminalCellNative> line(cols);
    for (int i = 0; i < cols; ++i) {
        line[i].codepoint = cells[i].chars[0];
        line[i].fg_color = 0xFF000000 | (cells[i].fg.red << 16) | (cells[i].fg.green << 8) | cells[i].fg.blue;
        line[i].bg_color = 0xFF000000 | (cells[i].bg.red << 16) | (cells[i].bg.green << 8) | cells[i].bg.blue;
        line[i].attributes = 0;
        line[i].width = cells[i].width;
    }

    self->mScrollbackBuffer.push_back(line);
    if (self->mScrollbackBuffer.size() > MAX_SCROLLBACK_LINES) {
        self->mScrollbackBuffer.pop_front();
    }
    return 1;
}

int VTermParserBridge::cbPopLine(int cols, VTermScreenCell* cells, void* user) { return 0; }
