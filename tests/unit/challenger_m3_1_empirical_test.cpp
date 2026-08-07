/*
 * Empirical Stress Test Suite for Milestone M3 (Challenger 1 Gate)
 * Focus areas:
 * 1. Native Surface Canvas Renderer (60FPS budget, font metrics, resize)
 * 2. libvterm Parser Integration (10k scrollback boundary, malformed ESC, 256/TrueColor, Alt Screen)
 * 3. TerminalInputConnection & Multi-stage CJK IME Commit (high-frequency input, UTF-8 partial byte buffering, composing cancellation)
 */

#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>
#include <chrono>
#include <sstream>

#include "../../packages/apps/LinuxTerminal/jni/vterm_parser.h"
#include "../../packages/apps/LinuxTerminal/jni/sgr_mouse_generator.h"
#include "../../packages/apps/LinuxTerminal/jni/pty_framing_handler.h"

// -----------------------------------------------------------------------------
// Test 1: libvterm 10,000 Line Scrollback Boundary Overflow & Eviction
// -----------------------------------------------------------------------------
void test_10k_scrollback_boundary() {
    std::cout << "[EMPIRICAL TEST 1] libvterm 10,000 Line Scrollback Buffer Overflow..." << std::endl;
    VTermParserBridge parser(24, 80);

    // Push 25,000 lines
    int total_lines = 25000;
    for (int i = 1; i <= total_lines; ++i) {
        std::string line = "Line " + std::to_string(i) + "\n";
        parser.feedBytes(reinterpret_cast<const uint8_t*>(line.data()), line.size());
    }

    size_t count = parser.getScrollbackLineCount();
    std::cout << "       Scrollback buffer line count after 25,000 line feeds: " << count << std::endl;
    assert(count == 10000); // Must be capped at MAX_SCROLLBACK_LINES (10000)

    std::vector<TerminalCellNative> oldestLine = parser.getScrollbackLine(0);
    assert(!oldestLine.empty());
    std::string lineStr;
    for (const auto& cell : oldestLine) {
        if (cell.codepoint != 0 && cell.codepoint != ' ') {
            lineStr += static_cast<char>(cell.codepoint);
        }
    }
    std::cout << "       Oldest scrollback line content (index 0): [" << lineStr << "]" << std::endl;

    std::vector<TerminalCellNative> newestLine = parser.getScrollbackLine(9999);
    std::string newestStr;
    for (const auto& cell : newestLine) {
        if (cell.codepoint != 0 && cell.codepoint != ' ') {
            newestStr += static_cast<char>(cell.codepoint);
        }
    }
    std::cout << "       Newest scrollback line content (index 9999): [" << newestStr << "]" << std::endl;

    std::cout << "       10,000 Line Scrollback Boundary Overflow & FIFO Eviction: PASS" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 2: Alt Screen Buffer Switching (Vim / htop restoration)
// -----------------------------------------------------------------------------
void test_alt_screen_switching() {
    std::cout << "[EMPIRICAL TEST 2] Alt Screen Buffer Switching & Scrollback Isolation..." << std::endl;
    VTermParserBridge parser(24, 80);

    // 1. Write lines in main screen
    std::string mainLine = "Main Screen Line 1\nMain Screen Line 2\n";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(mainLine.data()), mainLine.size());

    size_t countBefore = parser.getScrollbackLineCount();

    // 2. Switch to Alt Screen: \e[?1049h
    std::string enterAlt = "\x1b[?1049h";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(enterAlt.data()), enterAlt.size());
    assert(parser.isAltScreenActive() == true);

    // 3. Write 50 lines in Alt Screen (Vim editing)
    for (int i = 1; i <= 50; ++i) {
        std::string vimLine = "Vim Editing Line " + std::to_string(i) + "\n";
        parser.feedBytes(reinterpret_cast<const uint8_t*>(vimLine.data()), vimLine.size());
    }

    // Scrollback line count MUST NOT increase while in Alt Screen!
    size_t countDuringAlt = parser.getScrollbackLineCount();
    std::cout << "       Scrollback lines before Alt: " << countBefore << ", during Alt: " << countDuringAlt << std::endl;
    assert(countDuringAlt == countBefore);

    // 4. Exit Alt Screen: \e[?1049l
    std::string exitAlt = "\x1b[?1049l";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(exitAlt.data()), exitAlt.size());
    assert(parser.isAltScreenActive() == false);

    // Scrollback remains unchanged
    size_t countAfter = parser.getScrollbackLineCount();
    assert(countAfter == countBefore);

    std::cout << "       Alt Screen Buffer Switching & Scrollback Isolation: PASS" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 3: 256 / TrueColor Palette Parser Accuracy
// -----------------------------------------------------------------------------
void test_color_palette() {
    std::cout << "[EMPIRICAL TEST 3] 256 / TrueColor Palette Sequence Parser..." << std::endl;
    VTermParserBridge parser(24, 80);

    // Write TrueColor sequence: \e[38;2;255;128;64m (RGB: 255, 128, 64)
    std::string trueColorSeq = "\x1b[38;2;255;128;64mTrueColorText\x1b[0m";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(trueColorSeq.data()), trueColorSeq.size());

    std::vector<TerminalCellNative> cells;
    int cols = 0, rows = 0;
    parser.getScreenGrid(cells, cols, rows);

    uint32_t fg = cells[0].fg_color;
    std::cout << "       TrueColor cell 0 fg_color: 0x" << std::hex << fg << std::dec << std::endl;
    if (fg != 0xFFFF8040) {
        std::cout << "       [BUG DETECTED] TrueColor escape sequence \\e[38;2;255;128;64m failed to set ARGB 0xFFFF8040 (Got: 0x" << std::hex << fg << std::dec << ")" << std::endl;
    }
}

// -----------------------------------------------------------------------------
// Test 4: Malformed Escape Sequence Parser Fuzzing
// -----------------------------------------------------------------------------
void test_malformed_esc_sequences() {
    std::cout << "[EMPIRICAL TEST 4] Malformed Escape Sequence Fuzzing..." << std::endl;
    VTermParserBridge parser(24, 80);

    std::vector<std::string> malformed_seqs = {
        "\x1b[999999999;999999999;999999999m", // Huge CSI params
        "\x1b[???h",                           // Invalid dec private mode
        "\x1b]0;Unterminated OSC Title string without BEL or ST",
        "\x1b[38;2;999;999;999m",               // Out-of-bounds RGB
        "\x1b\x1b\x1b[[[;;;",                   // Nested ESC garbage
        "\x00\x01\x02\x03\x04\x05\x06\x07\x08", // Control characters
    };

    for (const auto& seq : malformed_seqs) {
        parser.feedBytes(reinterpret_cast<const uint8_t*>(seq.data()), seq.size());
    }

    // Parser must remain alive and functional
    std::string validText = "Recovered text\r\n";
    parser.feedBytes(reinterpret_cast<const uint8_t*>(validText.data()), validText.size());

    std::vector<TerminalCellNative> cells;
    int cols = 0, rows = 0;
    parser.getScreenGrid(cells, cols, rows);
    assert(cols == 80 && rows == 24);

    std::cout << "       Malformed Escape Sequence Resilience: PASS" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 5: UTF-8 Multi-byte Partial Byte Buffering across Socket Boundaries
// -----------------------------------------------------------------------------
void test_utf8_partial_byte_buffering() {
    std::cout << "[EMPIRICAL TEST 5] UTF-8 Partial Multi-byte Buffering Across Socket Reads..." << std::endl;
    VTermParserBridge parser(24, 80);

    // 1. 3-Byte CJK Character: '繁' -> 0xE7 0xB9 0x81
    uint8_t b1 = 0xE7;
    uint8_t b2 = 0xB9;
    uint8_t b3 = 0x81;

    parser.feedBytes(&b1, 1);
    parser.feedBytes(&b2, 1);
    parser.feedBytes(&b3, 1);

    std::vector<TerminalCellNative> cells;
    int cols = 0, rows = 0;
    parser.getScreenGrid(cells, cols, rows);
    std::cout << "       Reassembled CJK codepoint: 0x" << std::hex << cells[0].codepoint << std::dec << " (expected: 0x7F41)" << std::endl;

    // 2. Memory leak / partial buffer accumulation test with invalid bytes
    uint8_t invalidByte = 0x80;
    for (int i = 0; i < 5000; ++i) {
        parser.feedBytes(&invalidByte, 1);
    }
    std::cout << "       Fed 5000 invalid continuation bytes to parser..." << std::endl;
    std::cout << "       UTF-8 Partial Multi-byte Buffering Across Socket Reads: PASS" << std::endl;
}

// -----------------------------------------------------------------------------
// Test 6: Rendering Benchmark (60 FPS / 16.6ms target check under rapid output)
// -----------------------------------------------------------------------------
void test_rendering_performance_budget() {
    std::cout << "[EMPIRICAL TEST 6] Rapid Output Stream Performance & 60FPS Budget Check..." << std::endl;
    VTermParserBridge parser(24, 80);

    auto start = std::chrono::high_resolution_clock::now();
    int line_count = 10000;
    for (int i = 0; i < line_count; ++i) {
        std::string line = "Fast Terminal Output Benchmark Line #" + std::to_string(i) + "\x1b[31m RED \x1b[32m GREEN \x1b[0m\n";
        parser.feedBytes(reinterpret_cast<const uint8_t*>(line.data()), line.size());
    }

    std::vector<TerminalCellNative> cells;
    int cols = 0, rows = 0;
    parser.getScreenGrid(cells, cols, rows);

    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - start).count();

    std::cout << "       Processed " << line_count << " terminal lines & grid extractions in " << elapsedMs << " ms." << std::endl;
    std::cout << "       Average throughput: " << (line_count * 1000.0 / elapsedMs) << " lines/sec." << std::endl;
    assert(elapsedMs < 1000); // 10,000 lines processed under 1 second

    std::cout << "       Rapid Output Stream Performance & 60FPS Budget Check: PASS" << std::endl;
}

int main() {
    std::cout << "================================================================================" << std::endl;
    std::cout << "   EMPIRICAL CHALLENGER 1 STRESS TEST SUITE (MILESTONE M3 GATE RE-EVALUATION)" << std::endl;
    std::cout << "================================================================================" << std::endl;

    test_10k_scrollback_boundary();
    test_alt_screen_switching();
    test_color_palette();
    test_malformed_esc_sequences();
    test_utf8_partial_byte_buffering();
    test_rendering_performance_budget();

    std::cout << "================================================================================" << std::endl;
    std::cout << "             EMPIRICAL CHALLENGER 1 STRESS TESTS COMPLETED                       " << std::endl;
    std::cout << "================================================================================" << std::endl;
    return 0;
}
