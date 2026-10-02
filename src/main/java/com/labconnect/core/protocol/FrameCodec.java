package com.labconnect.core.protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class FrameCodec {
    public static final int HEADER_SIZE = 8;
    public static final int MESSAGE_ID_SIZE = 16;
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024; // 16MB

    public static final byte FRAME_PING = 0x00;
    public static final byte FRAME_PONG = 0x01;
    public static final byte FRAME_FRAGMENT_START = (byte) 0xFE;
    public static final byte FRAME_FRAGMENT_CONT = (byte) 0xFF;

    public static final int FLAG_COMPRESSED = 0x01;
    public static final int FLAG_ENCRYPTED_APP = 0x02;
    public static final int FLAG_FRAGMENTED = 0x04;

    private FrameCodec() {}

    public static ByteBuffer encode(Frame frame) {
        byte[] payload = frame.payload();
        int totalLength = HEADER_SIZE + MESSAGE_ID_SIZE + payload.length;

        if (totalLength > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("Frame too large: " + totalLength);
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.putInt(totalLength);
        buffer.put(frame.type());
        buffer.put(frame.flags());
        buffer.putShort((short) 0); // reserved
        buffer.put(frame.messageId());
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    public static Frame decode(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return null; // Need more data
        }

        buffer.mark();
        int length = buffer.getInt();
        byte type = buffer.get();
        byte flags = buffer.get();
        buffer.getShort(); // reserved

        if (length < HEADER_SIZE + MESSAGE_ID_SIZE || length > MAX_FRAME_SIZE) {
            throw new ProtocolException("Invalid frame length: " + length);
        }

        if (buffer.remaining() < length - HEADER_SIZE) {
            buffer.reset();
            return null; // Need more data
        }

        byte[] messageId = new byte[MESSAGE_ID_SIZE];
        buffer.get(messageId);

        int payloadLength = length - HEADER_SIZE - MESSAGE_ID_SIZE;
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        return new Frame(type, flags, messageId, payload);
    }

    public static boolean hasCompleteFrame(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return false;
        }
        buffer.mark();
        int length = buffer.getInt();
        buffer.reset();
        return buffer.remaining() >= length;
    }

    /**
     * Returns the declared length of the frame at the buffer's current position,
     * or -1 if the header is not fully buffered yet. The position is restored.
     */
    public static int peekFrameLength(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return -1;
        }
        int position = buffer.position();
        int length = buffer.getInt();
        buffer.position(position);
        return length;
    }

    public record Frame(byte type, byte flags, byte[] messageId, byte[] payload) {
        public UUID getMessageIdUUID() {
            ByteBuffer bb = ByteBuffer.wrap(messageId);
            long msb = bb.getLong();
            long lsb = bb.getLong();
            return new UUID(msb, lsb);
        }

        public static Frame create(byte type, byte flags, UUID messageId, byte[] payload) {
            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.putLong(messageId.getMostSignificantBits());
            bb.putLong(messageId.getLeastSignificantBits());
            return new Frame(type, flags, bb.array(), payload);
        }

        public static Frame ping(UUID messageId) {
            return create(FRAME_PING, (byte) 0, messageId, new byte[0]);
        }

        public static Frame pong(UUID messageId) {
            return create(FRAME_PONG, (byte) 0, messageId, new byte[0]);
        }
    }

    public static class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}