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

#ifndef LINUX_BRIDGE_VSOCK_FRAMING_H
#define LINUX_BRIDGE_VSOCK_FRAMING_H

#include <cstdint>
#include <vector>
#include <string>

namespace android {
namespace system {
namespace linux_bridge {

constexpr uint32_t VSOCK_PORT_CONTROL = 5000;
constexpr uint32_t VSOCK_PORT_PTY     = 5001;
constexpr uint32_t VSOCK_PORT_WAYLAND = 5002;

enum class VsockFrameType : uint8_t {
    CONTROL = 0x01,
    PTY_DATA = 0x02,
    WAYLAND = 0x03,
    HEARTBEAT = 0x04,
    MSG_AUTH_INIT = 0x10,
    MSG_AUTH_RESPONSE = 0x11,
    MSG_AUTH_VERIFY = 0x12,
    MSG_AUTH_SUCCESS = 0x13
};

struct VsockFrameHeader {
    uint32_t magic;         // 0x56534F4B ("VSOK")
    uint8_t  frameType;     // VsockFrameType
    uint32_t payloadLength; // Length of payload
    uint32_t sequenceId;    // Transaction sequence number
} __attribute__((packed)); // 13 bytes header

struct AuthHandshakePayload {
    uint8_t token[32];     // 256-bit Single-use Random Token
    uint8_t signature[32]; // HMAC-SHA256(Secret, Token)
} __attribute__((packed));

class VsockFraming {
public:
    static constexpr uint32_t VSOK_MAGIC = 0x56534F4B;
    static constexpr uint32_t MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16MB

    static std::vector<uint8_t> packFrame(VsockFrameType frameType, uint32_t sequenceId, const std::vector<uint8_t>& payload);
    static bool unpackFrame(const std::vector<uint8_t>& buffer, VsockFrameHeader& outHeader, std::vector<uint8_t>& outPayload);
    static bool readFull(int fd, void* buf, size_t count);
    static bool readFrame(int fd, VsockFrameHeader& outHeader, std::vector<uint8_t>& outPayload);
    static bool constantTimeCompare(const uint8_t* a, const uint8_t* b, size_t len);
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_VSOCK_FRAMING_H
