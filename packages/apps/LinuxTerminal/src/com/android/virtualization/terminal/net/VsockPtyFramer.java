package com.android.virtualization.terminal.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Vsock Port 5001 PTY Binary Packet Framer and Stream Serializer / Parser.
 * Protocol Format: [SessionID (16B)][Type (1B)][Length (4B Big-Endian)][Payload]
 */
public class VsockPtyFramer {
    public static final int HEADER_SIZE = 21; // 16 + 1 + 4
    public static final int MAX_PAYLOAD_SIZE = 65536; // 64 KB limit

    public enum PacketType {
        DATA(0x01),
        RESIZE(0x02),
        PING(0x03),
        PONG(0x04),
        EOS(0x05);

        private final byte mValue;

        PacketType(int value) {
            mValue = (byte) value;
        }

        public byte getValue() {
            return mValue;
        }

        public static PacketType fromByte(byte b) {
            for (PacketType type : values()) {
                if (type.mValue == b) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid Vsock frame type byte: 0x" + Integer.toHexString(b & 0xFF));
        }
    }

    public static class Frame {
        public final byte[] sessionId;
        public final PacketType type;
        public final byte[] payload;

        public Frame(byte[] sessionId, PacketType type, byte[] payload) {
            if (sessionId == null || sessionId.length != 16) {
                throw new IllegalArgumentException("Session ID must be exactly 16 bytes");
            }
            this.sessionId = sessionId;
            this.type = type;
            this.payload = (payload != null) ? payload : new byte[0];
        }
    }

    public interface OnFrameParsedListener {
        void onFrameParsed(Frame frame);
        void onError(Exception e);
    }

    /**
     * Serializes a Frame into a 21-byte header binary packet.
     */
    public static byte[] serializeFrame(byte[] sessionId, PacketType type, byte[] payload) {
        if (sessionId == null || sessionId.length != 16) {
            throw new IllegalArgumentException("Session ID must be exactly 16 bytes");
        }
        if (type == null) {
            throw new IllegalArgumentException("PacketType cannot be null");
        }
        byte[] data = (payload != null) ? payload : new byte[0];
        if (data.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload length " + data.length + " exceeds maximum " + MAX_PAYLOAD_SIZE);
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(sessionId);
        buffer.put(type.getValue());
        buffer.putInt(data.length);
        buffer.put(data);

        return buffer.array();
    }

    /**
     * Serializes a RESIZE frame with 4-byte payload containing cols (uint16_t BE) and rows (uint16_t BE).
     */
    public static byte[] serializeResizeFrame(byte[] sessionId, int cols, int rows) {
        ByteBuffer payload = ByteBuffer.allocate(4);
        payload.order(ByteOrder.BIG_ENDIAN);
        payload.putShort((short) cols);
        payload.putShort((short) rows);
        return serializeFrame(sessionId, PacketType.RESIZE, payload.array());
    }

    /**
     * Parses a 4-byte RESIZE payload into [cols, rows].
     */
    public static int[] parseResizePayload(byte[] payload) {
        if (payload == null || payload.length != 4) {
            throw new IllegalArgumentException("Resize payload must be exactly 4 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int cols = buffer.getShort() & 0xFFFF;
        int rows = buffer.getShort() & 0xFFFF;
        return new int[]{cols, rows};
    }

    /**
     * Parses a binary frame buffer into a Frame object.
     */
    public static Frame parseFrameHeaderAndPayload(byte[] buffer) {
        if (buffer == null || buffer.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer length must be at least HEADER_SIZE");
        }
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.BIG_ENDIAN);
        byte[] sessionId = new byte[16];
        bb.get(sessionId);
        byte typeByte = bb.get();
        int payloadLength = bb.getInt();
        PacketType type = PacketType.fromByte(typeByte);
        byte[] payload = new byte[payloadLength];
        if (buffer.length >= HEADER_SIZE + payloadLength) {
            bb.get(payload);
        }
        return new Frame(sessionId, type, payload);
    }

    /**
     * Stateful Parser for reassembling stream chunks into complete binary Frames.
     */
    public static class StreamParser {
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

        public synchronized void appendAndParse(byte[] chunk, int offset, int length, byte[] expectedSessionId, OnFrameParsedListener listener) {
            if (chunk == null || length <= 0) {
                return;
            }
            mBuffer.write(chunk, offset, length);
            byte[] bytes = mBuffer.toByteArray();
            int readOffset = 0;

            while (bytes.length - readOffset >= HEADER_SIZE) {
                ByteBuffer headerBuf = ByteBuffer.wrap(bytes, readOffset, HEADER_SIZE);
                headerBuf.order(ByteOrder.BIG_ENDIAN);

                byte[] sessionId = new byte[16];
                headerBuf.get(sessionId);
                byte typeByte = headerBuf.get();
                int payloadLength = headerBuf.getInt();

                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
                    if (listener != null) {
                        listener.onError(new IllegalArgumentException("Invalid payload length: " + payloadLength));
                    }
                    readOffset += 1;
                    continue;
                }

                PacketType type;
                try {
                    type = PacketType.fromByte(typeByte);
                } catch (IllegalArgumentException e) {
                    if (listener != null) {
                        listener.onError(e);
                    }
                    readOffset += 1;
                    continue;
                }

                int totalFrameLength = HEADER_SIZE + payloadLength;
                if (bytes.length - readOffset < totalFrameLength) {
                    // Incomplete frame, wait for more data
                    break;
                }

                try {
                    byte[] payload = Arrays.copyOfRange(bytes, readOffset + HEADER_SIZE, readOffset + totalFrameLength);

                    if (expectedSessionId != null && !Arrays.equals(sessionId, expectedSessionId)) {
                        // Drop frame due to SessionID mismatch
                    } else if (listener != null) {
                        listener.onFrameParsed(new Frame(sessionId, type, payload));
                    }
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError(e);
                    }
                }

                readOffset += totalFrameLength;
            }

            // Retain unparsed trailing bytes in buffer
            byte[] remaining = Arrays.copyOfRange(bytes, readOffset, bytes.length);
            mBuffer.reset();
            mBuffer.write(remaining, 0, remaining.length);
        }
    }
}
