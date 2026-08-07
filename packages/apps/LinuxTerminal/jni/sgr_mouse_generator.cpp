#include "sgr_mouse_generator.h"
#include <cstdio>
#include <algorithm>

SgrMouseGeneratorNative::SgrMouseGeneratorNative() {}
SgrMouseGeneratorNative::~SgrMouseGeneratorNative() {}

void SgrMouseGeneratorNative::setTrackingEnabled(bool enabled) {
    mTrackingEnabled = enabled;
}

void SgrMouseGeneratorNative::pixelToGrid(float pxX, float pxY, int cellW, int cellH, int totalCols, int totalRows, int& outCol, int& outRow) {
    if (cellW <= 0) cellW = 1;
    if (cellH <= 0) cellH = 1;

    int c = static_cast<int>(pxX / cellW) + 1;
    int r = static_cast<int>(pxY / cellH) + 1;

    outCol = std::max(1, std::min(c, totalCols));
    outRow = std::max(1, std::min(r, totalRows));
}

std::string SgrMouseGeneratorNative::generateButtonPress(int button, int col, int row, int modifiers) {
    if (!mTrackingEnabled) return "";
    int cb = button + modifiers;
    char buf[64];
    snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
    return std::string(buf);
}

std::string SgrMouseGeneratorNative::generateButtonRelease(int button, int col, int row, int modifiers) {
    if (!mTrackingEnabled) return "";
    int cb = button + modifiers;
    char buf[64];
    snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dm", cb, col, row);
    return std::string(buf);
}

std::string SgrMouseGeneratorNative::generateMotion(int button, int col, int row, int modifiers) {
    if (!mTrackingEnabled) return "";
    int cb = button + 32 + modifiers;
    char buf[64];
    snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
    return std::string(buf);
}

std::string SgrMouseGeneratorNative::generateWheel(int direction, int col, int row, int modifiers) {
    if (!mTrackingEnabled) return "";
    int cb = (direction < 0) ? 65 : 64; // 64 = Up, 65 = Down
    cb += modifiers;
    char buf[64];
    snprintf(buf, sizeof(buf), "\x1b[<%d;%d;%dM", cb, col, row);
    return std::string(buf);
}
