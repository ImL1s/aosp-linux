/*
 * Empirical Stress Test for VsockFraming C++ implementation.
 */

#include "system/linux_bridge/vsock_framing.h"
#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <cstring>

using namespace android::system::linux_bridge;

int main() {
    std::cout << "=== Running VsockFraming C++ Stress Verification ===" << std::endl;

    // 1. Pack / Unpack normal frame
    uint32_t seqId = 999;
    std::vector<uint8_t> payload = {0x11, 0x22, 0x33, 0x44};
    auto frame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seqId, payload);
    assert(frame.size() == sizeof(VsockFrameHeader) + payload.size());

    VsockFrameHeader header;
    std::vector<uint8_t> unpacked;
    bool ok = VsockFraming::unpackFrame(frame, header, unpacked);
    assert(ok);
    assert(header.magic == VsockFraming::VSOK_MAGIC);
    assert(header.sequenceId == seqId);
    assert(unpacked == payload);
    std::cout << "[VsockFraming] Pack/Unpack valid frame: PASS" << std::endl;

    // 2. Corrupt framing magic
    std::vector<uint8_t> corruptFrame = frame;
    uint32_t badMagic = 0xDEADBEEF;
    std::memcpy(corruptFrame.data(), &badMagic, 4);

    VsockFrameHeader badHeader;
    std::vector<uint8_t> badUnpacked;
    bool corruptOk = VsockFraming::unpackFrame(corruptFrame, badHeader, badUnpacked);
    assert(!corruptOk);
    std::cout << "[VsockFraming] Corrupt magic 0xDEADBEEF rejection: PASS" << std::endl;

    // 3. Payload size > 16MB limit
    std::vector<uint8_t> hugePayload(16 * 1024 * 1024 + 1, 0xAA);
    auto hugeFrame = VsockFraming::packFrame(VsockFrameType::PTY_DATA, seqId, hugePayload);
    assert(hugeFrame.empty());
    std::cout << "[VsockFraming] Payload >16MB rejection: PASS" << std::endl;

    std::cout << "=== VsockFraming C++ Stress Verification: ALL PASSED ===" << std::endl;
    return 0;
}
