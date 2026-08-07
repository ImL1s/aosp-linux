"""
Vsock packet framing and HMAC authentication helpers.
"""

import struct
import hmac
import hashlib
import os
from enum import IntEnum
from typing import Tuple

class VsockPacketType(IntEnum):
    DATA = 0x01
    RESIZE = 0x02
    PING = 0x03
    PONG = 0x04
    EOS = 0x05

class VsockFramingHelper:
    """
    Protocol header format:
    [SessionID (16 bytes)][Type (1 byte)][Length (4 bytes)][Payload (N bytes)]
    """
    HEADER_SIZE = 16 + 1 + 4

    @classmethod
    def create_frame(cls, session_id: bytes, packet_type: VsockPacketType, payload: bytes) -> bytes:
        if len(session_id) != 16:
            raise ValueError("Session ID must be exactly 16 bytes")
        length = len(payload)
        header = session_id + bytes([int(packet_type)]) + struct.pack(">I", length)
        return header + payload

    @classmethod
    def parse_header(cls, header: bytes) -> Tuple[bytes, VsockPacketType, int]:
        if len(header) < cls.HEADER_SIZE:
            raise ValueError(f"Header length {len(header)} is less than required {cls.HEADER_SIZE} bytes")
        session_id = header[0:16]
        pkg_type = VsockPacketType(header[16])
        length = struct.unpack(">I", header[17:21])[0]
        return session_id, pkg_type, length

    @classmethod
    def parse_frame(cls, frame: bytes) -> Tuple[bytes, VsockPacketType, bytes]:
        if len(frame) < cls.HEADER_SIZE:
            raise ValueError(f"Frame length {len(frame)} is less than required {cls.HEADER_SIZE} bytes")
        session_id, pkg_type, length = cls.parse_header(frame[:cls.HEADER_SIZE])
        payload = frame[cls.HEADER_SIZE:cls.HEADER_SIZE + length]
        return session_id, pkg_type, payload

    @classmethod
    def create_resize_frame(cls, session_id: bytes, cols: int, rows: int) -> bytes:
        payload = struct.pack(">HH", cols, rows)
        return cls.create_frame(session_id, VsockPacketType.RESIZE, payload)

    @classmethod
    def parse_resize_payload(cls, payload: bytes) -> Tuple[int, int]:
        if len(payload) != 4:
            raise ValueError("Resize payload must be 4 bytes (2x uint16)")
        cols, rows = struct.unpack(">HH", payload)
        return cols, rows

class HmacAuthHelper:
    @staticmethod
    def generate_random_token() -> bytes:
        return os.urandom(32)  # 256-bit token

    @staticmethod
    def compute_hmac(secret: bytes, token: bytes) -> bytes:
        return hmac.new(secret, token, hashlib.sha256).digest()

    @staticmethod
    def verify_hmac(secret: bytes, token: bytes, signature: bytes) -> bool:
        expected = HmacAuthHelper.compute_hmac(secret, token)
        return hmac.compare_digest(expected, signature)
