#include "vterm.h"
#include <string.h>

struct VTermScreen {
    int rows;
    int cols;
    VTermScreenCell *matrix;
    VTermPos cursor;
    int cursor_visible;
    VTermScreenCallbacks callbacks;
    void *user_data;
    VTermColor current_fg;
    VTermColor current_bg;
    VTermScreenCellAttrs current_attrs;
    int is_alt_screen;
    int mouse_tracking;
};

void vterm_screen_set_callbacks(VTermScreen *vts, const VTermScreenCallbacks *callbacks, void *user_data) {
    if (!vts) return;
    if (callbacks) {
        vts->callbacks = *callbacks;
    } else {
        memset(&vts->callbacks, 0, sizeof(VTermScreenCallbacks));
    }
    vts->user_data = user_data;
}

void vterm_screen_reset(VTermScreen *vts, int hard) {
    if (!vts || !vts->matrix) return;
    vts->cursor.row = 0;
    vts->cursor.col = 0;
    vts->cursor_visible = 1;
    vts->current_fg = (VTermColor){255, 255, 255};
    vts->current_bg = (VTermColor){0, 0, 0};
    memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));

    for (int i = 0; i < vts->rows * vts->cols; i++) {
        vts->matrix[i].chars[0] = ' ';
        vts->matrix[i].width = 1;
        vts->matrix[i].fg = vts->current_fg;
        vts->matrix[i].bg = vts->current_bg;
        memset(&vts->matrix[i].attrs, 0, sizeof(VTermScreenCellAttrs));
    }

    if (vts->callbacks.damage) {
        VTermRect r = {0, vts->rows, 0, vts->cols};
        vts->callbacks.damage(r, vts->user_data);
    }
}

int vterm_screen_get_cell(const VTermScreen *vts, VTermPos pos, VTermScreenCell *cell) {
    if (!vts || !cell || pos.row < 0 || pos.row >= vts->rows || pos.col < 0 || pos.col >= vts->cols) {
        return 0;
    }
    *cell = vts->matrix[pos.row * vts->cols + pos.col];
    return 1;
}
