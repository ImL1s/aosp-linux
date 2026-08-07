/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "vsock_framing.h"
#include <cstring>
#include <cerrno>
#include <climits>
#include <unistd.h>
#include <arpa/inet.h>

namespace android {
namespace system {
namespace linux_bridge {

bool VsockFraming::readFull(int fd, void* buf, size_t count) {
    uint8_t* ptr = static_cast<uint8_t*>(buf);
    size_t totalRead = 0;
    while (totalRead < count) {
        ssize_t bytesRead = read(fd, ptr + totalRead, count - totalRead);
        if (bytesRead <= 0) {
            if (bytesRead < 0 && (errno == EINTR || errno == EAGAIN)) {
                continue;
            }
            return false;
        }
        totalRead += static_cast<size_t>(bytesRead);
    }
    return true;
}

bool VsockFraming::readFrame(int fd, VsockFrameHeader& outHeader, std::vector<uint8_t>& outPayload) {
    VsockFrameHeader header;
    if (!readFull(fd, &header, sizeof(header))) {
        return false;
    }

    header.magic = ntohl(header.magic);
    header.payloadLength = ntohl(header.payloadLength);
    header.sequenceId = ntohl(header.sequenceId);

    if (header.magic != VSOK_MAGIC) {
        return false;
    }

    if (header.payloadLength > MAX_PAYLOAD_SIZE) {
        return false;
    }

    if (sizeof(VsockFrameHeader) > SIZE_MAX - header.payloadLength) {
        return false;
    }

    std::vector<uint8_t> payload(header.payloadLength);
    if (header.payloadLength > 0) {
        if (!readFull(fd, payload.data(), header.payloadLength)) {
            return false;
        }
    }

    outHeader = header;
    outPayload = std::move(payload);
    return true;
}

std::vector<uint8_t> VsockFraming::packFrame(VsockFrameType frameType, uint32_t sequenceId, const std::vector<uint8_t>& payload) {
    if (payload.size() > MAX_PAYLOAD_SIZE) {
        return {};
    }
    if (sizeof(VsockFrameHeader) > SIZE_MAX - payload.size()) {
        return {};
    }

    VsockFrameHeader header;
    header.magic = htonl(VSOK_MAGIC);
    header.frameType = static_cast<uint8_t>(frameType);
    header.payloadLength = htonl(static_cast<uint32_t>(payload.size()));
    header.sequenceId = htonl(sequenceId);

    std::vector<uint8_t> result(sizeof(VsockFrameHeader) + payload.size());
    std::memcpy(result.data(), &header, sizeof(VsockFrameHeader));
    if (!payload.empty()) {
        std::memcpy(result.data() + sizeof(VsockFrameHeader), payload.data(), payload.size());
    }
    return result;
}

bool VsockFraming::unpackFrame(const std::vector<uint8_t>& buffer, VsockFrameHeader& outHeader, std::vector<uint8_t>& outPayload) {
    if (buffer.size() < sizeof(VsockFrameHeader)) {
        return false;
    }

    std::memcpy(&outHeader, buffer.data(), sizeof(VsockFrameHeader));
    outHeader.magic = ntohl(outHeader.magic);
    outHeader.payloadLength = ntohl(outHeader.payloadLength);
    outHeader.sequenceId = ntohl(outHeader.sequenceId);

    if (outHeader.magic != VSOK_MAGIC) {
        return false;
    }

    if (outHeader.payloadLength > MAX_PAYLOAD_SIZE) {
        return false;
    }

    if (sizeof(VsockFrameHeader) > SIZE_MAX - outHeader.payloadLength) {
        return false;
    }

    if (buffer.size() < sizeof(VsockFrameHeader) + outHeader.payloadLength) {
        return false;
    }

    outPayload.resize(outHeader.payloadLength);
    if (outHeader.payloadLength > 0) {
        std::memcpy(outPayload.data(), buffer.data() + sizeof(VsockFrameHeader), outHeader.payloadLength);
    }
    return true;
}

bool VsockFraming::constantTimeCompare(const uint8_t* a, const uint8_t* b, size_t len) {
    if (a == nullptr || b == nullptr) {
        return false;
    }
    uint8_t result = 0;
    for (size_t i = 0; i < len; ++i) {
        result |= (a[i] ^ b[i]);
    }
    return result == 0;
}

} // namespace linux_bridge
} // namespace system
} // namespace android
