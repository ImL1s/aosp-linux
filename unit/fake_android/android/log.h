#ifndef FAKE_ANDROID_LOG_H
#define FAKE_ANDROID_LOG_H

#include <stdio.h>

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    return 0;
}

#endif
