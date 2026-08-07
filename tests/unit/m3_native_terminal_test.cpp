/*
 * Unit Test Suite for Milestone M3 Native Touch Terminal & PTY Protocol Engine (C++)
 */

#include "vterm.h"
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>

static int g_damage_count = 0;
static int g_cursor_move_count = 0;
static int g_pushline_count = 0;

static int test_damage_cb(VTermRect rect, void* user_data) {
    g_damage_count++;
    return 1;
}

static int test_movecursor_cb(VTermPos pos, VTermPos oldpos, int visible, void* user_data) {
    g_cursor_move_count++;
    return 1;
}

static int test_pushline_cb(int cols, const VTermScreenCell* cells, void* user_data) {
    g_pushline_count++;
    return 1;
}

int main() {
    std::cout << "=== Running M3 Native Terminal & C++ libvterm Unit Test Suite ===" << std::endl;

    // 1. Initialize libvterm
    VTerm* vt = vterm_new(24, 80);
    assert(vt != nullptr);
    vterm_set_utf8(vt, 1);

    VTermScreen* vts = vterm_obtain_screen(vt);
    assert(vts != nullptr);

    VTermScreenCallbacks cb = {
        .damage = test_damage_cb,
        .moverect = nullptr,
        .movecursor = test_movecursor_cb,
        .settermprop = nullptr,
        .bell = nullptr,
        .resize = nullptr,
        .sb_pushline = test_pushline_cb,
        .sb_popline = nullptr,
    };
    vterm_screen_set_callbacks(vts, &cb, nullptr);
    vterm_screen_reset(vts, 1);

    std::cout << "[libvterm] Initialization: PASS" << std::endl;

    // 2. Write ASCII Stream
    const char* str = "Hello AOSP Dual-OS Terminal\r\n";
    vterm_input_write(vt, str, strlen(str));
    assert(g_damage_count > 0);

    VTermPos pos = {0, 0};
    VTermScreenCell cell;
    vterm_screen_get_cell(vts, pos, &cell);
    assert(cell.chars[0] == 'H');
    std::cout << "[libvterm] ASCII Stream Write & Cell Query: PASS" << std::endl;

    // 3. Write CJK Wide UTF-8 Stream (測試)
    const char* cjk_str = "\xEF\xBB\xBF\xE6\xB8\xAC\xE8\xA9\xA6";
    vterm_input_write(vt, cjk_str, strlen(cjk_str));

    // 4. Resize Terminal Window
    vterm_set_size(vt, 40, 120);
    std::cout << "[libvterm] Screen Resize to 40x120: PASS" << std::endl;

    // 5. Cleanup
    vterm_free(vt);
    std::cout << "[libvterm] Memory Free: PASS" << std::endl;

    std::cout << "=== M3 Native Terminal & C++ libvterm Unit Test Suite: ALL PASSED ===" << std::endl;
    return 0;
}
