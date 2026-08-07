#ifndef PTY_FRAMING_HANDLER_H
#define PTY_FRAMING_HANDLER_H

#include <cstdint>
#include <vector>
#include <string>
#include <mutex>
#include <functional>

enum class VsockPacketTypeNative : uint8_t {
    DATA = 0x01,
    RESIZE = 0x02,
    PING = 0x03,
    PONG = 0x04,
    EOS = 0x05
};

struct VsockFrameNative {
    uint8_t sessionId[16];
    VsockPacketTypeNative type;
    std::vector<uint8_t> payload;
};

class PtyFramingHandlerNative {
public:
    static constexpr size_t HEADER_SIZE = 21;
    static constexpr size_t MAX_PAYLOAD_SIZE = 65536;

    PtyFramingHandlerNative();
    ~PtyFramingHandlerNative();

    static std::vector<uint8_t> createFrame(const uint8_t sessionId[16], VsockPacketTypeNative type, const uint8_t* payloadData, size_t payloadLen);
    static std::vector<uint8_t> createResizeFrame(const uint8_t sessionId[16], uint16_t cols, uint16_t rows);
    static bool parseResizePayload(const std::vector<uint8_t>& payload, uint16_t& outCols, uint16_t& outRows);

    void processIncomingChunk(const uint8_t* data, size_t len, const uint8_t expectedSessionId[16], std::function<void(const VsockFrameNative&)> onFrame);

    static uint32_t calculateCrc32(const uint8_t* data, size_t len);

private:
    std::vector<uint8_t> mBuffer;
    std::mutex mBufferMutex;
};

#endif // PTY_FRAMING_HANDLER_H
