#include "pty_framing_handler.h"
#include <cstring>
#include <stdexcept>
#include <arpa/inet.h>

PtyFramingHandlerNative::PtyFramingHandlerNative() {}
PtyFramingHandlerNative::~PtyFramingHandlerNative() {}

std::vector<uint8_t> PtyFramingHandlerNative::createFrame(const uint8_t sessionId[16], VsockPacketTypeNative type, const uint8_t* payloadData, size_t payloadLen) {
    if (payloadLen > MAX_PAYLOAD_SIZE) {
        throw std::invalid_argument("Payload length exceeds maximum 64KB");
    }

    std::vector<uint8_t> frame(HEADER_SIZE + payloadLen);
    std::memcpy(frame.data(), sessionId, 16);
    frame[16] = static_cast<uint8_t>(type);

    uint32_t lenBe = htonl(static_cast<uint32_t>(payloadLen));
    std::memcpy(frame.data() + 17, &lenBe, 4);

    if (payloadLen > 0 && payloadData != nullptr) {
        std::memcpy(frame.data() + HEADER_SIZE, payloadData, payloadLen);
    }
    return frame;
}

std::vector<uint8_t> PtyFramingHandlerNative::createResizeFrame(const uint8_t sessionId[16], uint16_t cols, uint16_t rows) {
    uint16_t payload[2];
    payload[0] = htons(cols);
    payload[1] = htons(rows);
    return createFrame(sessionId, VsockPacketTypeNative::RESIZE, reinterpret_cast<const uint8_t*>(payload), sizeof(payload));
}

bool PtyFramingHandlerNative::parseResizePayload(const std::vector<uint8_t>& payload, uint16_t& outCols, uint16_t& outRows) {
    if (payload.size() != 4) return false;
    uint16_t colsBe, rowsBe;
    std::memcpy(&colsBe, payload.data(), 2);
    std::memcpy(&rowsBe, payload.data() + 2, 2);
    outCols = ntohs(colsBe);
    outRows = ntohs(rowsBe);
    return true;
}

void PtyFramingHandlerNative::processIncomingChunk(const uint8_t* data, size_t len, const uint8_t expectedSessionId[16], std::function<void(const VsockFrameNative&)> onFrame) {
    std::lock_guard<std::mutex> lock(mBufferMutex);
    mBuffer.insert(mBuffer.end(), data, data + len);

    size_t readOffset = 0;
    while (mBuffer.size() - readOffset >= HEADER_SIZE) {
        const uint8_t* header = mBuffer.data() + readOffset;

        uint8_t typeByte = header[16];
        if (typeByte < 0x01 || typeByte > 0x05) {
            // Invalid header type, perform 1-byte stream resynchronization
            readOffset += 1;
            continue;
        }

        uint32_t payloadLenBe;
        std::memcpy(&payloadLenBe, header + 17, 4);
        uint32_t payloadLen = ntohl(payloadLenBe);

        if (payloadLen > MAX_PAYLOAD_SIZE) {
            // High-watermark payload overflow control, perform 1-byte stream resynchronization
            readOffset += 1;
            continue;
        }

        size_t totalFrameLength = HEADER_SIZE + payloadLen;
        if (mBuffer.size() - readOffset < totalFrameLength) {
            // Fragmented frame, wait for next socket read
            break;
        }

        VsockFrameNative frame;
        std::memcpy(frame.sessionId, header, 16);
        frame.type = static_cast<VsockPacketTypeNative>(typeByte);
        frame.payload.assign(mBuffer.begin() + readOffset + HEADER_SIZE, mBuffer.begin() + readOffset + totalFrameLength);

        bool sessionMatch = (expectedSessionId == nullptr) || (std::memcmp(frame.sessionId, expectedSessionId, 16) == 0);
        if (sessionMatch && onFrame) {
            onFrame(frame);
        }

        readOffset += totalFrameLength;
    }

    if (readOffset > 0) {
        mBuffer.erase(mBuffer.begin(), mBuffer.begin() + readOffset);
    }
}

uint32_t PtyFramingHandlerNative::calculateCrc32(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int j = 0; j < 8; ++j) {
            crc = (crc >> 1) ^ (0xEDB88320 & (-(crc & 1)));
        }
    }
    return ~crc;
}
