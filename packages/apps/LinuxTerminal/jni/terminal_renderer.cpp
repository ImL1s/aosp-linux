#include "terminal_renderer.h"
#if __has_include(<android/log.h>)
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) printf("[" tag "] " __VA_ARGS__)
#endif
#include <cstring>
#include <algorithm>

#define LOG_TAG "TerminalRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

TerminalRenderer::TerminalRenderer() {
}

TerminalRenderer::~TerminalRenderer() {
    releaseNativeWindow();
}

void TerminalRenderer::setNativeWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mWindowMutex);
    if (mNativeWindow != nullptr) {
        ANativeWindow_release(mNativeWindow);
    }
    mNativeWindow = window;
    if (mNativeWindow != nullptr) {
        ANativeWindow_setBuffersGeometry(mNativeWindow, mSurfaceWidth, mSurfaceHeight, WINDOW_FORMAT_RGBA_8888);
        mFullInvalidate = true;
    }
}

void TerminalRenderer::releaseNativeWindow() {
    std::lock_guard<std::mutex> lock(mWindowMutex);
    if (mNativeWindow != nullptr) {
        ANativeWindow_release(mNativeWindow);
        mNativeWindow = nullptr;
    }
}

void TerminalRenderer::setDimensions(int surfaceWidth, int surfaceHeight) {
    mSurfaceWidth = surfaceWidth;
    mSurfaceHeight = surfaceHeight;
    std::lock_guard<std::mutex> lock(mWindowMutex);
    if (mNativeWindow != nullptr) {
        ANativeWindow_setBuffersGeometry(mNativeWindow, mSurfaceWidth, mSurfaceHeight, WINDOW_FORMAT_RGBA_8888);
        mFullInvalidate = true;
    }
}

void TerminalRenderer::setFontMetrics(int fontWidth, int fontHeight) {
    if (fontWidth > 0 && fontHeight > 0) {
        mFontWidth = fontWidth;
        mFontHeight = fontHeight;
        mGlyphCache.clear();
        mFullInvalidate = true;
    }
}

void TerminalRenderer::invalidateAll() {
    mFullInvalidate = true;
}

uint32_t TerminalRenderer::resolveColor(uint32_t colorValue, bool isForeground) {
    // If alpha byte is 0xFF, treat as TrueColor 24-bit (0xFFRRGGBB)
    if ((colorValue & 0xFF000000) == 0xFF000000) {
        return colorValue;
    }

    uint8_t index = colorValue & 0xFF;
    if (index < 16) {
        // Standard ANSI 16 Palette
        static const uint32_t ansiPalette[16] = {
            0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
            0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
            0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
            0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
        };
        return ansiPalette[index];
    } else if (index < 232) {
        // 6x6x6 Color Cube (16..231)
        int idx = index - 16;
        int r = (idx / 36) * 51;
        int g = ((idx / 6) % 6) * 51;
        int b = (idx % 6) * 51;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    } else {
        // Grayscale 24 steps (232..255)
        int gray = 8 + (index - 232) * 10;
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }
}

void TerminalRenderer::rasterizeGlyph(uint32_t codepoint, std::vector<uint8_t>& outAlphaMap) {
    size_t glyphSize = mFontWidth * mFontHeight;
    outAlphaMap.resize(glyphSize, 0);

    if (codepoint == 0 || codepoint == ' ') {
        return;
    }

    // Simple procedural rasterizer for standard ASCII glyphs & CJK fallback box
    for (int y = 0; y < mFontHeight; ++y) {
        for (int x = 0; x < mFontWidth; ++x) {
            bool drawPixel = false;
            if (codepoint >= 32 && codepoint <= 126) {
                // Procedural cross/box outline for ascii demo rendering
                if (x == 0 || x == mFontWidth - 1 || y == 0 || y == mFontHeight - 1) {
                    drawPixel = false;
                } else {
                    drawPixel = (x + y) % 2 == 0;
                }
            } else {
                // Unicode fallback symbol box \uFFFD
                if (x == 1 || x == mFontWidth - 2 || y == 1 || y == mFontHeight - 2) {
                    drawPixel = true;
                }
            }
            if (drawPixel) {
                outAlphaMap[y * mFontWidth + x] = 255;
            }
        }
    }
}

void TerminalRenderer::drawCell(ANativeWindow_Buffer& buffer, int col, int row, const TerminalCellNative& cell) {
    int startX = col * mFontWidth;
    int startY = row * mFontHeight;
    int cellW = (cell.width == 2) ? mFontWidth * 2 : mFontWidth;

    uint32_t fg = resolveColor(cell.fg_color, true);
    uint32_t bg = resolveColor(cell.bg_color, false);

    if (cell.attributes & 0x04) { // Reverse attribute
        std::swap(fg, bg);
    }

    uint32_t* pixels = reinterpret_cast<uint32_t*>(buffer.bits);
    int stride = buffer.stride;

    // Fill background
    for (int y = 0; y < mFontHeight; ++y) {
        int py = startY + y;
        if (py >= buffer.height) break;
        for (int x = 0; x < cellW; ++x) {
            int px = startX + x;
            if (px >= buffer.width) break;
            pixels[py * stride + px] = bg;
        }
    }

    // Draw glyph foreground
    if (cell.codepoint != 0 && cell.codepoint != ' ') {
        auto it = mGlyphCache.find(cell.codepoint);
        if (it == mGlyphCache.end()) {
            std::vector<uint8_t> alphaMap;
            rasterizeGlyph(cell.codepoint, alphaMap);
            mGlyphCache[cell.codepoint] = alphaMap;
            it = mGlyphCache.find(cell.codepoint);
        }

        const auto& alphaMap = it->second;
        for (int y = 0; y < mFontHeight; ++y) {
            int py = startY + y;
            if (py >= buffer.height) break;
            for (int x = 0; x < mFontWidth; ++x) {
                int px = startX + x;
                if (px >= buffer.width) break;
                uint8_t alpha = alphaMap[y * mFontWidth + x];
                if (alpha > 0) {
                    pixels[py * stride + px] = fg;
                }
            }
        }
    }

    // Draw underline attribute (Bit 1)
    if (cell.attributes & 0x02) {
        int py = startY + mFontHeight - 2;
        if (py < buffer.height) {
            for (int x = 0; x < cellW; ++x) {
                int px = startX + x;
                if (px < buffer.width) {
                    pixels[py * stride + px] = fg;
                }
            }
        }
    }
}

void TerminalRenderer::renderGrid(const std::vector<TerminalCellNative>& cells, int cols, int rows, ARect* dirtyRect) {
    auto startTime = std::chrono::high_resolution_clock::now();

    std::lock_guard<std::mutex> lock(mWindowMutex);
    if (mNativeWindow == nullptr) {
        return;
    }

    ANativeWindow_Buffer buffer;
    ARect lockRect;

    if (dirtyRect != nullptr && !mFullInvalidate) {
        lockRect = *dirtyRect;
    } else {
        lockRect.left = 0;
        lockRect.top = 0;
        lockRect.right = mSurfaceWidth;
        lockRect.bottom = mSurfaceHeight;
    }

    if (ANativeWindow_lock(mNativeWindow, &buffer, &lockRect) < 0) {
        LOGE("ANativeWindow_lock failed");
        return;
    }

    int startCol = lockRect.left / mFontWidth;
    int endCol = (lockRect.right + mFontWidth - 1) / mFontWidth;
    int startRow = lockRect.top / mFontHeight;
    int endRow = (lockRect.bottom + mFontHeight - 1) / mFontHeight;

    startCol = std::max(0, std::min(startCol, cols));
    endCol = std::max(0, std::min(endCol, cols));
    startRow = std::max(0, std::min(startRow, rows));
    endRow = std::max(0, std::min(endRow, rows));

    for (int r = startRow; r < endRow; ++r) {
        for (int c = startCol; c < endCol; ++c) {
            size_t idx = r * cols + c;
            if (idx < cells.size()) {
                drawCell(buffer, c, r, cells[idx]);
            }
        }
    }

    ANativeWindow_unlockAndPost(mNativeWindow);
    mFullInvalidate = false;

    auto endTime = std::chrono::high_resolution_clock::now();
    mLastFrameTimeMs = std::chrono::duration<double, std::milli>(endTime - startTime).count();
}

#if __has_include(<jni.h>) && __has_include(<android/native_window_jni.h>)
#include <jni.h>
#include <android/native_window_jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeInitRenderer(
        JNIEnv* env, jobject thiz) {
    auto* renderer = new TerminalRenderer();
    return reinterpret_cast<jlong>(renderer);
}

JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeSetSurface(
        JNIEnv* env, jobject thiz, jlong ptr, jobject surface, jint width, jint height) {
    auto* renderer = reinterpret_cast<TerminalRenderer*>(ptr);
    if (!renderer) return;
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    renderer->setDimensions(width, height);
    renderer->setNativeWindow(window);
}

JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeReleaseSurface(
        JNIEnv* env, jobject thiz, jlong ptr) {
    auto* renderer = reinterpret_cast<TerminalRenderer*>(ptr);
    if (!renderer) return;
    renderer->releaseNativeWindow();
}

JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeRenderFrame(
        JNIEnv* env, jobject thiz, jlong ptr, jintArray codepoints, jintArray fgColors, jintArray bgColors, jintArray attrs, jint cols, jint rows) {
    auto* renderer = reinterpret_cast<TerminalRenderer*>(ptr);
    if (!renderer || cols <= 0 || rows <= 0) return;

    jint* cp = codepoints ? env->GetIntArrayElements(codepoints, nullptr) : nullptr;
    jint* fg = fgColors ? env->GetIntArrayElements(fgColors, nullptr) : nullptr;
    jint* bg = bgColors ? env->GetIntArrayElements(bgColors, nullptr) : nullptr;
    jint* at = attrs ? env->GetIntArrayElements(attrs, nullptr) : nullptr;

    if (cp && fg && bg && at) {
        std::vector<TerminalCellNative> cells(cols * rows);
        for (int i = 0; i < cols * rows; ++i) {
            cells[i].codepoint = cp[i];
            cells[i].fg_color = fg[i];
            cells[i].bg_color = bg[i];
            cells[i].attributes = at[i];
            cells[i].width = 1;
        }
        renderer->renderGrid(cells, cols, rows, nullptr);
    }

    if (cp) env->ReleaseIntArrayElements(codepoints, cp, JNI_ABORT);
    if (fg) env->ReleaseIntArrayElements(fgColors, fg, JNI_ABORT);
    if (bg) env->ReleaseIntArrayElements(bgColors, bg, JNI_ABORT);
    if (at) env->ReleaseIntArrayElements(attrs, at, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_renderer_TerminalSurfaceView_nativeDestroyRenderer(
        JNIEnv* env, jobject thiz, jlong ptr) {
    auto* renderer = reinterpret_cast<TerminalRenderer*>(ptr);
    if (!renderer) return;
    delete renderer;
}

} // extern "C"
#endif

