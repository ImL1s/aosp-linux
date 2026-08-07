#ifndef VTERM_H
#define VTERM_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int row;
    int col;
} VTermPos;

typedef struct {
    int start_row;
    int end_row;
    int start_col;
    int end_col;
} VTermRect;

typedef struct {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
} VTermColor;

typedef struct {
    unsigned int bold:1;
    unsigned int underline:2;
    unsigned int italic:1;
    unsigned int blink:1;
    unsigned int reverse:1;
    unsigned int strike:1;
    unsigned int font:4;
    unsigned int dwl:1;
    unsigned int dhl:2;
} VTermScreenCellAttrs;

typedef struct {
    uint32_t chars[6];
    char width;
    VTermScreenCellAttrs attrs;
    VTermColor fg;
    VTermColor bg;
} VTermScreenCell;

typedef struct VTerm VTerm;
typedef struct VTermScreen VTermScreen;
typedef struct VTermState VTermState;

typedef struct {
    int (*damage)(VTermRect rect, void *user_data);
    int (*moverect)(VTermRect dest, VTermRect src, void *user_data);
    int (*movecursor)(VTermPos pos, VTermPos oldpos, int visible, void *user_data);
    int (*settermprop)(int prop, void *val, void *user_data);
    int (*bell)(void *user_data);
    int (*resize)(int rows, int cols, void *user_data);
    int (*sb_pushline)(int cols, const VTermScreenCell *cells, void *user_data);
    int (*sb_popline)(int cols, VTermScreenCell *cells, void *user_data);
} VTermScreenCallbacks;

VTerm *vterm_new(int rows, int cols);
void vterm_free(VTerm *vt);

void vterm_set_utf8(VTerm *vt, int is_utf8);
void vterm_set_size(VTerm *vt, int rows, int cols);

VTermScreen *vterm_obtain_screen(VTerm *vt);
void vterm_screen_set_callbacks(VTermScreen *vts, const VTermScreenCallbacks *callbacks, void *user_data);
void vterm_screen_reset(VTermScreen *vts, int hard);
int vterm_screen_get_cell(const VTermScreen *vts, VTermPos pos, VTermScreenCell *cell);

size_t vterm_input_write(VTerm *vt, const char *bytes, size_t len);

#ifdef __cplusplus
}
#endif

#endif // VTERM_H
