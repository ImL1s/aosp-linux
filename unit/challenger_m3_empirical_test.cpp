#include <iostream>
#include <cassert>
#include <vector>
#include <chrono>
#include <cstring>
#include "vterm_parser.h"
#include "terminal_renderer.h"

// Fake ANativeWindow for testing TerminalRenderer without Android display subsystem
struct ANativeWindow_Fake {
    int width;
    int height;
    std::vector<uint32_t> bufferBits;
};

void run_scrollback_test() {
    std::cout << "--- Test 1: Scrollback Buffer (10,000 lines boundary) ---" << std::endl;
    VTermParserBridge parser(24, 80);

    // Feed 12,000 lines of output into parser
    for (int i = 0; i < 12000; ++i) {
        std::string line = "Line " + std::to_string(i) + "\n";
        parser.feedBytes(reinterpret_cast<const uint8_t*>(line.c_str()), line.length());
    }

    size_t scrollbackCount = parser.getScrollbackLineCount();
    std::cout << "[OBSERVATION] Total scrollback lines in buffer after 12,000 lines input: " << scrollbackCount << std::endl;
    if (scrollbackCount == 0) {
        std::cout << "[FAIL] BUG CONFIRMED: Scrollback buffer is 0! Line push callback cbPushLine is never triggered by parser stub!" << std::endl;
    } else if (scrollbackCount > 10000) {
        std::cout << "[FAIL] BUG CONFIRMED: Scrollback buffer size (" << scrollbackCount << ") exceeded 10,000 limit!" << std::endl;
    } else {
        std::cout << "[PASS] Scrollback buffer correctly capped at: " << scrollbackCount << std::endl;
    }
}

void run_ansi_escape_test() {
    std::cout << "\n--- Test 2: ANSI Escape Sequence & Alt Screen Parsing ---" << std::endl;
    VTermParserBridge parser(24, 80);

    // Test Alt Screen toggle \x1b[?1049h
    std::string altScreenSeq = "\x1b[?1049h";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(altScreenSeq.c_str()), altScreenSeq.length());
    std::cout << "[OBSERVATION] Alt Screen status after \\x1b[?1049h: " << (parser.isAltScreenActive() ? "ACTIVE" : "INACTIVE") << std::endl;
    if (!parser.isAltScreenActive()) {
        std::cout << "[FAIL] BUG CONFIRMED: Alt Screen activation sequence \\x1b[?1049h ignored!" << std::endl;
    }

    // Test ANSI Color \x1b[31m (Red)
    std::string colorSeq = "\x1b[31mTEXT\x1b[0m\n";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(colorSeq.c_str()), colorSeq.length());

    std::vector<TerminalCellNative> cells;
    int cols, rows;
    parser.getScreenGrid(cells, cols, rows);

    // Inspect cells where "TEXT" was written
    std::cout << "[OBSERVATION] Cell 0 codepoint: " << (char)cells[0].codepoint 
              << " fg_color: 0x" << std::hex << cells[0].fg_color << std::dec << std::endl;

    // Check if ESC sequence '\x1b' was literally printed as '[' and '3' or processed as color
    if (cells[0].codepoint == '[' || cells[0].codepoint == '3') {
        std::cout << "[FAIL] BUG CONFIRMED: ANSI Escape sequence \\x1b[31m was printed as literal text!" << std::endl;
    }
}

void run_utf8_cjk_test() {
    std::cout << "\n--- Test 3: UTF-8 Multi-byte CJK Byte Boundary & Grid Alignment ---" << std::endl;
    VTermParserBridge parser(24, 80);

    // UTF-8 for "測試" : 6 bytes total (\xE4\xB8\xAD \xE6\xB9\xA7 -> 0xE6 0xB8\xAC 0xE8\xA9\xA6)
    // Split across chunk boundary: Chunk 1 has 2 bytes of first char (\xE6 \xB8), Chunk 2 has remaining bytes
    uint8_t chunk1[] = {0xE6, 0xB8};
    uint8_t chunk2[] = {0xAC, 0xE8, 0xA9, 0xA6, '\n'};

    parser.feedBytes(chunk1, sizeof(chunk1));
    parser.feedBytes(chunk2, sizeof(chunk2));

    std::vector<TerminalCellNative> cells;
    int cols, rows;
    parser.getScreenGrid(cells, cols, rows);

    std::cout << "[OBSERVATION] Cell 0 codepoint: 0x" << std::hex << cells[0].codepoint 
              << " (Expected Unicode 0x6E2C '測')" << std::dec << std::endl;
    std::cout << "[OBSERVATION] Cell 1 codepoint: 0x" << std::hex << cells[1].codepoint << std::dec << std::endl;

    if (cells[0].codepoint == 0xE6 || cells[0].codepoint == 0xB8) {
        std::cout << "[FAIL] BUG CONFIRMED: Multi-byte UTF-8 split into raw individual bytes per cell!" << std::endl;
    }
}

void run_renderer_palette_test() {
    std::cout << "\n--- Test 4: Terminal Renderer Palette & TrueColor Resolution ---" << std::endl;
    
    // Check resolveColor for 16-color ANSI, 256-color cube, and TrueColor
    uint32_t c_ansi_red = TerminalRenderer::resolveColor(1, true); // ANSI red
    uint32_t c_truecolor = TerminalRenderer::resolveColor(0xFF123456, true); // Truecolor
    uint32_t c_256_cube = TerminalRenderer::resolveColor(196, true); // 256 color red (index 196)

    std::cout << "[OBSERVATION] ANSI Red (index 1): 0x" << std::hex << c_ansi_red << std::dec << std::endl;
    std::cout << "[OBSERVATION] TrueColor (0xFF123456): 0x" << std::hex << c_truecolor << std::dec << std::endl;
    std::cout << "[OBSERVATION] 256-Color (index 196): 0x" << std::hex << c_256_cube << std::dec << std::endl;

    assert(c_ansi_red == 0xFFCD0000);
    assert(c_truecolor == 0xFF123456);
    std::cout << "[PASS] Renderer color palette resolution math." << std::endl;
}

int main() {
    std::cout << "=======================================================" << std::endl;
    std::cout << " M3 EMPIRICAL STRESS TEST SUITE (Challenger 1)" << std::endl;
    std::cout << "=======================================================" << std::endl;
    
    run_scrollback_test();
    run_ansi_escape_test();
    run_utf8_cjk_test();
    run_renderer_palette_test();

    std::cout << "=======================================================" << std::endl;
    return 0;
}
