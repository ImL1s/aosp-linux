#include <jni.h>
#include <android/log.h>
#include <vterm.h>
#include <vector>
#include <deque>
#include <cstring>
#include <mutex>

#define LOG_TAG "VTermJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_SCROLLBACK_LINES 10000

struct NativeVTermContext {
    VTerm* vt;
    VTermScreen* vts;
    jobject callbackObj;
    jmethodID onDamageMethod;
    jmethodID onCursorMoveMethod;
    jmethodID onAltScreenMethod;
    jmethodID onMouseTrackingMethod;
    JavaVM* jvm;
    int rows;
    int cols;
    int cursorRow = 0;
    int cursorCol = 0;
    std::mutex mtx;
    std::vector<uint8_t> partialUtf8Buffer;
    std::deque<std::vector<VTermScreenCell>> scrollbackBuffer;
};

static int cb_damage(VTermRect rect, void* user_data) {
    auto* ctx = static_cast<NativeVTermContext*>(user_data);
    if (!ctx || !ctx->jvm) return 1;
    JNIEnv* env = nullptr;
    bool needsDetach = false;
    jint res = ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        }
    }
    if (env && ctx->callbackObj && ctx->onDamageMethod) {
        env->CallVoidMethod(ctx->callbackObj, ctx->onDamageMethod,
                            rect.start_row, rect.end_row, rect.start_col, rect.end_col);
    }
    if (needsDetach) {
        ctx->jvm->DetachCurrentThread();
    }
    return 1;
}

static int cb_movecursor(VTermPos pos, VTermPos oldpos, int visible, void* user_data) {
    auto* ctx = static_cast<NativeVTermContext*>(user_data);
    if (!ctx) return 1;
    ctx->cursorRow = pos.row;
    ctx->cursorCol = pos.col;
    if (!ctx->jvm) return 1;
    JNIEnv* env = nullptr;
    bool needsDetach = false;
    jint res = ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        }
    }
    if (env && ctx->callbackObj && ctx->onCursorMoveMethod) {
        env->CallVoidMethod(ctx->callbackObj, ctx->onCursorMoveMethod, pos.row, pos.col, visible != 0);
    }
    if (needsDetach) {
        ctx->jvm->DetachCurrentThread();
    }
    return 1;
}

static int cb_settermprop(int prop, void* val, void* user_data) {
    auto* ctx = static_cast<NativeVTermContext*>(user_data);
    if (!ctx || !ctx->jvm) return 1;
    JNIEnv* env = nullptr;
    bool needsDetach = false;
    jint res = ctx->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        }
    }
    if (env && ctx->callbackObj) {
        int is_set = val ? *static_cast<int*>(val) : 0;
        if (prop == 1049 && ctx->onAltScreenMethod) {
            env->CallVoidMethod(ctx->callbackObj, ctx->onAltScreenMethod, is_set != 0);
        } else if (prop == 1006 && ctx->onMouseTrackingMethod) {
            env->CallVoidMethod(ctx->callbackObj, ctx->onMouseTrackingMethod, is_set != 0);
        }
    }
    if (needsDetach) {
        ctx->jvm->DetachCurrentThread();
    }
    return 1;
}

static int cb_sb_pushline(int cols, const VTermScreenCell* cells, void* user_data) {
    auto* ctx = static_cast<NativeVTermContext*>(user_data);
    if (!ctx || !cells || cols <= 0) return 1;

    std::vector<VTermScreenCell> line(cells, cells + cols);
    ctx->scrollbackBuffer.push_back(line);
    if (ctx->scrollbackBuffer.size() > MAX_SCROLLBACK_LINES) {
        ctx->scrollbackBuffer.pop_front();
    }
    return 1;
}

static VTermScreenCallbacks screen_callbacks = {
    .damage = cb_damage,
    .moverect = nullptr,
    .movecursor = cb_movecursor,
    .settermprop = cb_settermprop,
    .bell = nullptr,
    .resize = nullptr,
    .sb_pushline = cb_sb_pushline,
    .sb_popline = nullptr,
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit(
        JNIEnv* env, jobject thiz, jint rows, jint cols, jobject callback) {
    auto* ctx = new NativeVTermContext();
    env->GetJavaVM(&ctx->jvm);
    ctx->callbackObj = callback ? env->NewGlobalRef(callback) : nullptr;
    ctx->rows = rows;
    ctx->cols = cols;
    ctx->onDamageMethod = nullptr;
    ctx->onCursorMoveMethod = nullptr;
    ctx->onAltScreenMethod = nullptr;
    ctx->onMouseTrackingMethod = nullptr;

    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass) {
            ctx->onDamageMethod = env->GetMethodID(cbClass, "onDamage", "(IIII)V");
            ctx->onCursorMoveMethod = env->GetMethodID(cbClass, "onCursorMove", "(IIZ)V");
            ctx->onAltScreenMethod = env->GetMethodID(cbClass, "onAltScreenChanged", "(Z)V");
            ctx->onMouseTrackingMethod = env->GetMethodID(cbClass, "onMouseTrackingChanged", "(Z)V");
            env->DeleteLocalRef(cbClass);
        }
    }

    ctx->vt = vterm_new(rows, cols);
    vterm_set_utf8(ctx->vt, 1);
    ctx->vts = vterm_obtain_screen(ctx->vt);
    vterm_screen_set_callbacks(ctx->vts, &screen_callbacks, ctx);
    vterm_screen_reset(ctx->vts, 1);

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeWrite(
        JNIEnv* env, jobject thiz, jlong ptr, jbyteArray data, jint length) {
    auto* ctx = reinterpret_cast<NativeVTermContext*>(ptr);
    if (!ctx || !data || length <= 0) return;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
        std::lock_guard<std::mutex> lock(ctx->mtx);

        std::vector<uint8_t> input;
        if (!ctx->partialUtf8Buffer.empty()) {
            input.insert(input.end(), ctx->partialUtf8Buffer.begin(), ctx->partialUtf8Buffer.end());
            ctx->partialUtf8Buffer.clear();
        }
        input.insert(input.end(), reinterpret_cast<uint8_t*>(bytes), reinterpret_cast<uint8_t*>(bytes) + length);

        size_t total = input.size();
        if (total > 0) {
            size_t i = total - 1;
            while (i < total && (input[i] & 0xC0) == 0x80) {
                if (i == 0) break;
                i--;
            }
            if (i < total) {
                uint8_t lead = input[i];
                size_t expected = 1;
                if ((lead & 0xE0) == 0xC0) expected = 2;
                else if ((lead & 0xF0) == 0xE0) expected = 3;
                else if ((lead & 0xF8) == 0xF0) expected = 4;

                if (total - i < expected) {
                    ctx->partialUtf8Buffer.assign(input.begin() + i, input.end());
                    total = i;
                }
            }
        }

        if (total > 0) {
            vterm_input_write(ctx->vt, reinterpret_cast<const char*>(input.data()), total);
        }
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeResize(
        JNIEnv* env, jobject thiz, jlong ptr, jint rows, jint cols) {
    auto* ctx = reinterpret_cast<NativeVTermContext*>(ptr);
    if (!ctx || rows <= 0 || cols <= 0) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->rows = rows;
    ctx->cols = cols;
    vterm_set_size(ctx->vt, rows, cols);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeGetScreenMatrix(
        JNIEnv* env, jobject thiz, jlong ptr, jintArray codepoints, jintArray fgColors, jintArray bgColors, jintArray attrs, jintArray widths) {
    auto* ctx = reinterpret_cast<NativeVTermContext*>(ptr);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mtx);
    jint* cp = env->GetIntArrayElements(codepoints, nullptr);
    jint* fg = env->GetIntArrayElements(fgColors, nullptr);
    jint* bg = env->GetIntArrayElements(bgColors, nullptr);
    jint* at = env->GetIntArrayElements(attrs, nullptr);
    jint* wd = widths ? env->GetIntArrayElements(widths, nullptr) : nullptr;

    for (int r = 0; r < ctx->rows; r++) {
        for (int c = 0; c < ctx->cols; c++) {
            int idx = r * ctx->cols + c;
            VTermPos pos = {.row = r, .col = c};
            VTermScreenCell cell;
            vterm_screen_get_cell(ctx->vts, pos, &cell);

            cp[idx] = cell.chars[0] ? cell.chars[0] : ' ';
            fg[idx] = 0xFF000000 | (cell.fg.red << 16) | (cell.fg.green << 8) | cell.fg.blue;
            bg[idx] = 0xFF000000 | (cell.bg.red << 16) | (cell.bg.green << 8) | cell.bg.blue;
            at[idx] = (cell.attrs.bold ? 1 : 0) |
                      ((cell.attrs.italic ? 1 : 0) << 1) |
                      ((cell.attrs.underline ? 1 : 0) << 2) |
                      ((cell.attrs.reverse ? 1 : 0) << 3) |
                      ((cell.attrs.strike ? 1 : 0) << 4) |
                      ((cell.attrs.blink ? 1 : 0) << 5);
            if (wd) {
                wd[idx] = cell.width;
            }
        }
    }

    env->ReleaseIntArrayElements(codepoints, cp, 0);
    env->ReleaseIntArrayElements(fgColors, fg, 0);
    env->ReleaseIntArrayElements(bgColors, bg, 0);
    env->ReleaseIntArrayElements(attrs, at, 0);
    if (wd) {
        env->ReleaseIntArrayElements(widths, wd, 0);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeGetCursorPos(
        JNIEnv* env, jobject thiz, jlong ptr, jintArray outPos) {
    auto* ctx = reinterpret_cast<NativeVTermContext*>(ptr);
    if (!ctx || !outPos) return;

    std::lock_guard<std::mutex> lock(ctx->mtx);
    jint posArr[2] = {ctx->cursorRow, ctx->cursorCol};
    env->SetIntArrayRegion(outPos, 0, 2, posArr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_virtualization_terminal_parser_VTermParser_nativeDestroy(
        JNIEnv* env, jobject thiz, jlong ptr) {
    auto* ctx = reinterpret_cast<NativeVTermContext*>(ptr);
    if (!ctx) return;
    if (ctx->callbackObj) {
        env->DeleteGlobalRef(ctx->callbackObj);
    }
    vterm_free(ctx->vt);
    delete ctx;
}
