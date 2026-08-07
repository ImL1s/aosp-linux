#ifndef TERMINAL_RENDERER_H
#define TERMINAL_RENDERER_H

#include <cstdint>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <chrono>

#if __has_include(<android/native_window.h>)
#include <android/native_window.h>
#include <android/native_window_jni.h>
#else
struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void* bits;
};
struct ANativeWindow;
struct ARect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};
#define WINDOW_FORMAT_RGBA_8888 1
inline void ANativeWindow_release(ANativeWindow*) {}
inline int ANativeWindow_setBuffersGeometry(ANativeWindow*, int, int, int) { return 0; }
inline int ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, ARect*) { return 0; }
inline int ANativeWindow_unlockAndPost(ANativeWindow*) { return 0; }
#endif

struct ColorRGB {
    uint8_t r, g, b, a;
};

struct TerminalCellNative {
    uint32_t codepoint;
    uint32_t fg_color;  // 0xAARRGGBB
    uint32_t bg_color;  // 0xAARRGGBB
    uint8_t attributes; // Bit 0: Bold, Bit 1: Underline, Bit 2: Reverse, Bit 3: Italic
    uint8_t width;      // 1 (ASCII) or 2 (CJK)
};

class TerminalRenderer {
public:
    TerminalRenderer();
    ~TerminalRenderer();

    void setNativeWindow(ANativeWindow* window);
    void releaseNativeWindow();

    void setDimensions(int surfaceWidth, int surfaceHeight);
    void setFontMetrics(int fontWidth, int fontHeight);

    void renderGrid(const std::vector<TerminalCellNative>& cells, int cols, int rows, ARect* dirtyRect = nullptr);
    void invalidateAll();

    static uint32_t resolveColor(uint32_t colorValue, bool isForeground);
    double getLastFrameTimeMs() const { return mLastFrameTimeMs; }

private:
    ANativeWindow* mNativeWindow = nullptr;
    int mSurfaceWidth = 1024;
    int mSurfaceHeight = 768;
    int mFontWidth = 10;
    int mFontHeight = 20;
    bool mFullInvalidate = true;
    double mLastFrameTimeMs = 0.0;

    std::unordered_map<uint32_t, std::vector<uint8_t>> mGlyphCache;
    std::mutex mWindowMutex;

    void rasterizeGlyph(uint32_t codepoint, std::vector<uint8_t>& outAlphaMap);
    void drawCell(ANativeWindow_Buffer& buffer, int col, int row, const TerminalCellNative& cell);
};

#endif // TERMINAL_RENDERER_H
