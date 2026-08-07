#include "vterm.h"

// Unicode width helper for libvterm
int vterm_unicode_width(uint32_t cp) {
    if ((cp >= 0x4E00 && cp <= 0x9FFF) ||
        (cp >= 0x3400 && cp <= 0x4DBF) ||
        (cp >= 0x20000 && cp <= 0x2A6DF) ||
        (cp >= 0xF900 && cp <= 0xFAFF) ||
        (cp >= 0xFF01 && cp <= 0xFF60)) {
        return 2;
    }
    return 1;
}
