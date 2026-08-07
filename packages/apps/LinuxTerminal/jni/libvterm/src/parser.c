#include "vterm.h"
#include <stdlib.h>
#include <stdio.h>
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

struct VTerm {
    int rows;
    int cols;
    int is_utf8;
    VTermScreen screen;
};

static int get_cjk_width(uint32_t cp) {
    if ((cp >= 0x4E00 && cp <= 0x9FFF) ||
        (cp >= 0x3400 && cp <= 0x4DBF) ||
        (cp >= 0x20000 && cp <= 0x2A6DF) ||
        (cp >= 0xF900 && cp <= 0xFAFF) ||
        (cp >= 0xFF01 && cp <= 0xFF60)) {
        return 2;
    }
    return 1;
}

static void push_line_scroll(VTermScreen *vts) {
    if (!vts) return;
    if (vts->callbacks.sb_pushline) {
        vts->callbacks.sb_pushline(vts->cols, &vts->matrix[0], vts->user_data);
    }
    // Shift rows up
    memmove(&vts->matrix[0], &vts->matrix[vts->cols], (vts->rows - 1) * vts->cols * sizeof(VTermScreenCell));

    // Clear bottom row
    int last = (vts->rows - 1) * vts->cols;
    for (int c = 0; c < vts->cols; c++) {
        vts->matrix[last + c].chars[0] = ' ';
        vts->matrix[last + c].width = 1;
        vts->matrix[last + c].fg = vts->current_fg;
        vts->matrix[last + c].bg = vts->current_bg;
        memset(&vts->matrix[last + c].attrs, 0, sizeof(VTermScreenCellAttrs));
    }
}

static void parse_sgr(VTermScreen *vts, const char *param) {
    if (!param || *param == '\0') {
        vts->current_fg = (VTermColor){255, 255, 255};
        vts->current_bg = (VTermColor){0, 0, 0};
        memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
        return;
    }

    int code = atoi(param);
    if (code == 0) {
        vts->current_fg = (VTermColor){255, 255, 255};
        vts->current_bg = (VTermColor){0, 0, 0};
        memset(&vts->current_attrs, 0, sizeof(VTermScreenCellAttrs));
    } else if (code == 1) {
        vts->current_attrs.bold = 1;
    } else if (code == 3) {
        vts->current_attrs.italic = 1;
    } else if (code == 4) {
        vts->current_attrs.underline = 1;
    } else if (code == 7) {
        vts->current_attrs.reverse = 1;
    } else if (code == 9) {
        vts->current_attrs.strike = 1;
    } else if (code >= 30 && code <= 37) {
        static VTermColor ansi16[8] = {
            {0, 0, 0}, {205, 0, 0}, {0, 205, 0}, {205, 205, 0},
            {0, 0, 238}, {205, 0, 205}, {0, 205, 205}, {229, 229, 229}
        };
        vts->current_fg = ansi16[code - 30];
    } else if (code >= 90 && code <= 97) {
        static VTermColor ansi16_bright[8] = {
            {127, 127, 127}, {255, 0, 0}, {0, 255, 0}, {255, 254, 0},
            {92, 92, 255}, {255, 0, 255}, {0, 255, 255}, {255, 255, 255}
        };
        vts->current_fg = ansi16_bright[code - 90];
    } else if (code >= 40 && code <= 47) {
        static VTermColor ansi16[8] = {
            {0, 0, 0}, {205, 0, 0}, {0, 205, 0}, {205, 205, 0},
            {0, 0, 238}, {205, 0, 205}, {0, 205, 205}, {229, 229, 229}
        };
        vts->current_bg = ansi16[code - 40];
    }
}

size_t vterm_input_write(VTerm *vt, const char *bytes, size_t len) {
    if (!vt || !bytes || len == 0) return 0;
    VTermScreen *vts = &vt->screen;
    size_t i = 0;

    while (i < len) {
        unsigned char c = (unsigned char)bytes[i];

        if (c == 0x1B) { // Escape Sequence Parsing
            if (i + 1 < len && bytes[i + 1] == '[') {
                size_t start = i;
                i += 2;
                char seq[64] = {0};
                size_t sidx = 0;
                while (i < len && sidx < 63 && bytes[i] >= 0x20 && bytes[i] <= 0x3F) {
                    seq[sidx++] = bytes[i++];
                }
                if (i < len && sidx < 63) {
                    seq[sidx++] = bytes[i++];
                }
                seq[sidx] = '\0';

                char final_char = (sidx > 0) ? seq[sidx - 1] : 0;
                if (final_char == 'H' || final_char == 'f') { // Cursor Move \e[r;cH
                    int r = 1, c_col = 1;
                    sscanf(seq, "%d;%d", &r, &c_col);
                    vts->cursor.row = (r > 0) ? r - 1 : 0;
                    vts->cursor.col = (c_col > 0) ? c_col - 1 : 0;
                    if (vts->cursor.row >= vts->rows) vts->cursor.row = vts->rows - 1;
                    if (vts->cursor.col >= vts->cols) vts->cursor.col = vts->cols - 1;
                } else if (final_char == 'J') { // Clear Screen \e[2J
                    vterm_screen_reset(vts, 0);
                } else if (final_char == 'm') { // SGR Format \e[32m
                    parse_sgr(vts, seq);
                } else if (final_char == 'h' || final_char == 'l') { // DEC Modes
                    int enabled = (final_char == 'h');
                    if (strstr(seq, "?1049")) {
                        vts->is_alt_screen = enabled;
                        if (vts->callbacks.settermprop) {
                            vts->callbacks.settermprop(1049, &enabled, vts->user_data);
                        }
                    } else if (strstr(seq, "?1000") || strstr(seq, "?1006")) {
                        vts->mouse_tracking = enabled;
                        if (vts->callbacks.settermprop) {
                            vts->callbacks.settermprop(1006, &enabled, vts->user_data);
                        }
                    }
                }
                continue;
            } else {
                i++;
                continue;
            }
        }

        if (c == '\r') {
            vts->cursor.col = 0;
            i++;
            continue;
        }

        if (c == '\n') {
            vts->cursor.row++;
            if (vts->cursor.row >= vts->rows) {
                push_line_scroll(vts);
                vts->cursor.row = vts->rows - 1;
            }
            i++;
            continue;
        }

        if (c == '\b' || c == 0x7F) {
            if (vts->cursor.col > 0) {
                vts->cursor.col--;
            }
            i++;
            continue;
        }

        // UTF-8 Sequence Decoding
        uint32_t codepoint = c;
        size_t seq_len = 1;
        if ((c & 0xE0) == 0xC0) {
            seq_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            seq_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            seq_len = 4;
        }

        if (i + seq_len <= len) {
            if (seq_len == 2) {
                codepoint = ((c & 0x1F) << 6) | (bytes[i + 1] & 0x3F);
            } else if (seq_len == 3) {
                codepoint = ((c & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
            } else if (seq_len == 4) {
                codepoint = ((c & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
            }
            i += seq_len;
        } else {
            // Partial UTF-8 at chunk end
            break;
        }

        int width = get_cjk_width(codepoint);
        if (vts->cursor.col >= vts->cols) {
            vts->cursor.col = 0;
            vts->cursor.row++;
            if (vts->cursor.row >= vts->rows) {
                push_line_scroll(vts);
                vts->cursor.row = vts->rows - 1;
            }
        }

        int idx = vts->cursor.row * vts->cols + vts->cursor.col;
        vts->matrix[idx].chars[0] = codepoint;
        vts->matrix[idx].width = width;
        vts->matrix[idx].fg = vts->current_fg;
        vts->matrix[idx].bg = vts->current_bg;
        vts->matrix[idx].attrs = vts->current_attrs;

        if (width == 2 && vts->cursor.col + 1 < vts->cols) {
            int cidx = idx + 1;
            vts->matrix[cidx].chars[0] = 0;
            vts->matrix[cidx].width = 0; // Continuation cell
        }

        vts->cursor.col += width;
    }

    if (vts->callbacks.damage) {
        VTermRect r = {0, vts->rows, 0, vts->cols};
        vts->callbacks.damage(r, vts->user_data);
    }
    if (vts->callbacks.movecursor) {
        VTermPos oldp = vts->cursor;
        vts->callbacks.movecursor(vts->cursor, oldp, vts->cursor_visible, vts->user_data);
    }

    return i;
}
