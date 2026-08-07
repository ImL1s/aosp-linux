#ifndef FAKE_ANDROID_NATIVE_WINDOW_H
#define FAKE_ANDROID_NATIVE_WINDOW_H

#include <stdint.h>

#define WINDOW_FORMAT_RGBA_8888 1

struct ANativeWindow {};

struct ARect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};

struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void* bits;
    uint32_t reserved[6];
};

inline void ANativeWindow_release(ANativeWindow* window) {}
inline int32_t ANativeWindow_setBuffersGeometry(ANativeWindow* window, int32_t width, int32_t height, int32_t format) { return 0; }
inline int32_t ANativeWindow_lock(ANativeWindow* window, ANativeWindow_Buffer* outBuffer, ARect* inOutDirtyBounds) { return 0; }
inline int32_t ANativeWindow_unlockAndPost(ANativeWindow* window) { return 0; }

#endif
