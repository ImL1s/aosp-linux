#include "vterm.h"
#include <stdlib.h>
#include <string.h>

struct VTermScreen {
    int rows;
    int cols;
    VTermScreenCell *matrix; // rows * cols
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

struct VTerm {
    int rows;
    int cols;
    int is_utf8;
    VTermScreen screen;
};

VTerm *vterm_new(int rows, int cols) {
    VTerm *vt = (VTerm *)calloc(1, sizeof(VTerm));
    if (!vt) return NULL;
    vt->rows = (rows > 0) ? rows : 24;
    vt->cols = (cols > 0) ? cols : 80;
    vt->is_utf8 = 1;

    vt->screen.rows = vt->rows;
    vt->screen.cols = vt->cols;
    vt->screen.matrix = (VTermScreenCell *)calloc(vt->rows * vt->cols, sizeof(VTermScreenCell));
    vt->screen.cursor_visible = 1;
    vt->screen.current_fg = (VTermColor){255, 255, 255};
    vt->screen.current_bg = (VTermColor){0, 0, 0};

    for (int i = 0; i < vt->rows * vt->cols; i++) {
        vt->screen.matrix[i].chars[0] = ' ';
        vt->screen.matrix[i].width = 1;
        vt->screen.matrix[i].fg = vt->screen.current_fg;
        vt->screen.matrix[i].bg = vt->screen.current_bg;
    }

    return vt;
}

void vterm_free(VTerm *vt) {
    if (!vt) return;
    if (vt->screen.matrix) {
        free(vt->screen.matrix);
    }
    free(vt);
}

void vterm_set_utf8(VTerm *vt, int is_utf8) {
    if (vt) {
        vt->is_utf8 = is_utf8;
    }
}

void vterm_set_size(VTerm *vt, int rows, int cols) {
    if (!vt || rows <= 0 || cols <= 0) return;
    if (vt->rows == rows && vt->cols == cols) return;

    VTermScreenCell *new_matrix = (VTermScreenCell *)calloc(rows * cols, sizeof(VTermScreenCell));
    if (!new_matrix) return;

    int min_rows = (rows < vt->rows) ? rows : vt->rows;
    int min_cols = (cols < vt->cols) ? cols : vt->cols;

    for (int r = 0; r < min_rows; r++) {
        for (int c = 0; c < min_cols; c++) {
            new_matrix[r * cols + c] = vt->screen.matrix[r * vt->cols + c];
        }
    }
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (r >= min_rows || c >= min_cols) {
                new_matrix[r * cols + c].chars[0] = ' ';
                new_matrix[r * cols + c].width = 1;
                new_matrix[r * cols + c].fg = vt->screen.current_fg;
                new_matrix[r * cols + c].bg = vt->screen.current_bg;
            }
        }
    }

    free(vt->screen.matrix);
    vt->screen.matrix = new_matrix;
    vt->rows = rows;
    vt->cols = cols;
    vt->screen.rows = rows;
    vt->screen.cols = cols;

    if (vt->screen.cursor.row >= rows) vt->screen.cursor.row = rows - 1;
    if (vt->screen.cursor.col >= cols) vt->screen.cursor.col = cols - 1;

    if (vt->screen.callbacks.damage) {
        VTermRect r = {0, rows, 0, cols};
        vt->screen.callbacks.damage(r, vt->screen.user_data);
    }
}

VTermScreen *vterm_obtain_screen(VTerm *vt) {
    return vt ? &vt->screen : NULL;
}
