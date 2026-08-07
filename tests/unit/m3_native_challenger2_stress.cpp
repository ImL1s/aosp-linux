/*
 * Native C++ Empirical Stress Test Harness by Challenger 2 for Milestone M3.
 * Targets: sgr_mouse_generator.cpp, pty_framing_handler.cpp, vterm_parser.cpp, CJK IME & UTF-8 Fragmentation
 */

#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>
#include <chrono>
#include <thread>
#include <arpa/inet.h>

#include "../../packages/apps/LinuxTerminal/jni/sgr_mouse_generator.h"
#include "../../packages/apps/LinuxTerminal/jni/pty_framing_handler.h"
#include "../../packages/apps/LinuxTerminal/jni/vterm_parser.h"

void test_sgr_generator_high_rate() {
    std::cout << "[CPP STRESS 01] SGR Mouse Generator High Rate Benchmark..." << std::endl;
    SgrMouseGeneratorNative gen;
    gen.setTrackingEnabled(true);

    auto start = std::chrono::high_resolution_clock::now();
    int count = 100000;
    for (int i = 0; i < count; ++i) {
        int col = (i % 80) + 1;
        int row = (i % 24) + 1;
        std::string pkt = gen.generateMotion(0, col, row, 0); // button 0 + motion
        assert(!pkt.empty());
    }
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - start).count();

    std::cout << "       Generated " << count << " SGR motion packets in " << elapsed << " ms ("
              << (count * 1000.0 / elapsed) << " pkts/sec)." << std::endl;
}

void test_sgr_generator_modifiers() {
    std::cout << "[CPP STRESS 02] SGR Mouse Generator Modifier Key Combinations..." << std::endl;
    SgrMouseGeneratorNative gen;
    gen.setTrackingEnabled(true);

    // Shift = +4, Alt = +8, Ctrl = +16
    // Button 0 (Press) + Shift (+4) -> Cb = 4
    std::string s_shift = gen.generateButtonPress(0, 10, 5, 4);
    assert(s_shift == "\x1b[<4;10;5M");

    // Button 0 (Press) + Ctrl (+16) -> Cb = 16
    std::string s_ctrl = gen.generateButtonPress(0, 10, 5, 16);
    assert(s_ctrl == "\x1b[<16;10;5M");

    // Button 0 (Press) + Alt (+8) -> Cb = 8
    std::string s_alt = gen.generateButtonPress(0, 10, 5, 8);
    assert(s_alt == "\x1b[<8;10;5M");

    // Button 0 (Press) + Ctrl + Shift (+20) -> Cb = 20
    std::string s_ctrl_shift = gen.generateButtonPress(0, 10, 5, 20);
    assert(s_ctrl_shift == "\x1b[<20;10;5M");

    // Coordinate boundary clamping test
    int col = 0, row = 0;
    gen.pixelToGrid(-50.0f, -100.0f, 20, 40, 80, 24, col, row);
    assert(col == 1 && row == 1);

    gen.pixelToGrid(9999.0f, 9999.0f, 20, 40, 80, 24, col, row);
    assert(col == 80 && row == 24);

    std::cout << "       Native C++ SGR modifier key combination generation: PASS" << std::endl;
}

void test_pty_framing_fuzzing() {
    std::cout << "[CPP STRESS 03] Vsock Port 5001 PTY Framing Header Fuzzing..." << std::endl;
    PtyFramingHandlerNative handler;

    uint8_t session_id[16] = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};

    // 1. Valid Frame Creation
    uint8_t payload[] = "Hello Vsock";
    std::vector<uint8_t> valid_frame = handler.createFrame(session_id, VsockPacketTypeNative::DATA, payload, sizeof(payload));
    assert(valid_frame.size() == 21 + sizeof(payload));

    int parsed_count = 0;
    handler.processIncomingChunk(valid_frame.data(), valid_frame.size(), session_id, [&](const VsockFrameNative& frame) {
        parsed_count++;
        assert(frame.type == VsockPacketTypeNative::DATA);
    });
    assert(parsed_count == 1);
    std::cout << "       Valid frame creation & parsing: PASS" << std::endl;

    // 2. Fuzzing: Malformed 21-byte Header with Invalid Type Byte (0xFF)
    parsed_count = 0;
    uint8_t malformed_header[21];
    std::memcpy(malformed_header, session_id, 16);
    malformed_header[16] = 0xFF; // Invalid type byte
    uint32_t len_be = htonl(10);
    std::memcpy(malformed_header + 17, &len_be, 4);

    handler.processIncomingChunk(malformed_header, 21, session_id, [&](const VsockFrameNative& frame) {
        parsed_count++;
    });
    assert(parsed_count == 0); // Must reject invalid type byte
    std::cout << "       Invalid type byte rejection: PASS" << std::endl;

    // 3. Fuzzing: Payload Length > 64KB (65536)
    uint8_t overflow_header[21];
    std::memcpy(overflow_header, session_id, 16);
    overflow_header[16] = 0x01; // DATA
    uint32_t oversized_len_be = htonl(100000); // 100KB > 64KB
    std::memcpy(overflow_header + 17, &oversized_len_be, 4);

    parsed_count = 0;
    handler.processIncomingChunk(overflow_header, 21, session_id, [&](const VsockFrameNative& frame) {
        parsed_count++;
    });
    assert(parsed_count == 0); // Must drop oversized payload frame
    std::cout << "       Oversized payload length (>64KB) rejection: PASS" << std::endl;

    // 4. Session ID Mismatch Drop
    uint8_t wrong_session[16] = {9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9};
    std::vector<uint8_t> wrong_frame = handler.createFrame(wrong_session, VsockPacketTypeNative::DATA, payload, sizeof(payload));
    parsed_count = 0;
    handler.processIncomingChunk(wrong_frame.data(), wrong_frame.size(), session_id, [&](const VsockFrameNative& frame) {
        parsed_count++;
    });
    assert(parsed_count == 0); // Must drop session mismatch frame
    std::cout << "       Session ID mismatch drop: PASS" << std::endl;

    // 5. Fragmented socket reads reassembly
    std::vector<uint8_t> frame = handler.createFrame(session_id, VsockPacketTypeNative::DATA, payload, sizeof(payload));
    parsed_count = 0;
    // Push in 2-byte chunks
    for (size_t i = 0; i < frame.size(); i += 2) {
        size_t chunk_len = std::min(size_t(2), frame.size() - i);
        handler.processIncomingChunk(frame.data() + i, chunk_len, session_id, [&](const VsockFrameNative& f) {
            parsed_count++;
        });
    }
    assert(parsed_count == 1);
    std::cout << "       Fragmented byte stream reassembly: PASS" << std::endl;
}

void test_pty_framing_crc32() {
    std::cout << "[CPP STRESS 04] CRC32 Calculation & Integrity Check..." << std::endl;
    uint8_t data[] = "123456789";
    uint32_t crc = PtyFramingHandlerNative::calculateCrc32(data, 9);
    assert(crc == 0xCBF43926); // Standard IEEE 802.3 CRC32 of "123456789"
    std::cout << "       IEEE 802.3 CRC32 Calculation (0xCBF43926): PASS" << std::endl;
}

void test_utf8_cjk_fragmentation_stress() {
    std::cout << "[CPP STRESS 05] CJK IME UTF-8 Socket Fragmentation & Wide-Char Parsing..." << std::endl;

    VTermParserBridge parser(24, 80);

    // 3-byte CJK UTF-8: "測試" -> 0xE6 0xB8 0xAC (測), 0xE8 0xA9 0xA6 (試)
    // 4-byte Emoji: "😀" -> 0xF0 0x9F 0x98 0x80
    uint8_t cjk_bytes[] = {
        0xE6, 0xB8, 0xAC, // 測 (0x6E2C)
        0xE8, 0xA9, 0xA6, // 試 (0x8A66)
        0xF0, 0x9F, 0x98, 0x80 // 😀
    };

    // Feed 1 byte at a time (Extreme fragmentation)
    for (size_t i = 0; i < sizeof(cjk_bytes); ++i) {
        parser.feedBytes(&cjk_bytes[i], 1);
    }

    std::vector<TerminalCellNative> cells;
    int cols = 0, rows = 0;
    parser.getScreenGrid(cells, cols, rows);
    assert(cols == 80 && rows == 24);

    // Verify cell codepoints after 1-byte fragmented assembly
    assert(cells[0].codepoint == 0x6E2C); // '測'
    assert(cells[0].width == 2);
    assert(cells[2].codepoint == 0x8A66); // '試'
    assert(cells[2].width == 2);

    std::cout << "       1-Byte Fragmented CJK & Emoji Multi-byte Reassembly: PASS" << std::endl;

    // Test Malformed/Corrupted UTF-8 Sequences (unexpected continuation bytes, missing leads)
    uint8_t malformed_utf8[] = { 0x80, 0x80, 0xE6, 0x41, 0xFF, 0xFE, 0xE8, 0xA9, 0xA6 };
    parser.feedBytes(malformed_utf8, sizeof(malformed_utf8));

    // Ensure parser stays stable without crashes or memory corruption
    parser.getScreenGrid(cells, cols, rows);
    assert(cols == 80 && rows == 24);
    std::cout << "       Malformed UTF-8 Stream Parser Resilience: PASS" << std::endl;
}

int main() {
    std::cout << "================================================================================" << std::endl;
    std::cout << "     NATIVE C++ EMPIRICAL STRESS TEST SUITE (MILESTONE M3 - CHALLENGER 2)" << std::endl;
    std::cout << "================================================================================" << std::endl;

    test_sgr_generator_high_rate();
    test_sgr_generator_modifiers();
    test_pty_framing_fuzzing();
    test_pty_framing_crc32();
    test_utf8_cjk_fragmentation_stress();

    std::cout << "================================================================================" << std::endl;
    std::cout << "               ALL NATIVE C++ STRESS TESTS PASSED SUCCESSFULLY                  " << std::endl;
    std::cout << "================================================================================" << std::endl;
    return 0;
}
