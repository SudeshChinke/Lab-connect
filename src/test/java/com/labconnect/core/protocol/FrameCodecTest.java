package com.labconnect.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class FrameCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        UUID messageId = UUID.randomUUID();
        byte[] payload = "Hello, World!".getBytes();
        FrameCodec.Frame frame = FrameCodec.Frame.create(
                MessageType.HELLO.value(), (byte) 0, messageId, payload);

        ByteBuffer encoded = FrameCodec.encode(frame);
        FrameCodec.Frame decoded = FrameCodec.decode(encoded);

        assertThat(decoded).isNotNull();
        assertThat(decoded.type()).isEqualTo(MessageType.HELLO.value());
        assertThat(decoded.flags()).isEqualTo((byte) 0);
        assertThat(decoded.getMessageIdUUID()).isEqualTo(messageId);
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    void encodeDecodeWithFlags() {
        UUID messageId = UUID.randomUUID();
        byte[] payload = new byte[1024];
        FrameCodec.Frame frame = FrameCodec.Frame.create(
                MessageType.FILE_CHUNK.value(),
                (byte) (FrameCodec.FLAG_COMPRESSED | FrameCodec.FLAG_ENCRYPTED_APP),
                messageId, payload);

        ByteBuffer encoded = FrameCodec.encode(frame);
        FrameCodec.Frame decoded = FrameCodec.decode(encoded);

        assertThat(decoded).isNotNull();
        assertThat(decoded.flags()).isEqualTo((byte) (FrameCodec.FLAG_COMPRESSED | FrameCodec.FLAG_ENCRYPTED_APP));
    }

    @Test
    void pingPongFrames() {
        UUID messageId = UUID.randomUUID();
        FrameCodec.Frame ping = FrameCodec.Frame.ping(messageId);
        FrameCodec.Frame pong = FrameCodec.Frame.pong(messageId);

        assertThat(FrameCodec.decode(FrameCodec.encode(ping)).type()).isEqualTo(FrameCodec.FRAME_PING);
        assertThat(FrameCodec.decode(FrameCodec.encode(pong)).type()).isEqualTo(FrameCodec.FRAME_PONG);
    }

    @Test
    void hasCompleteFrame() {
        UUID messageId = UUID.randomUUID();
        FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.HELLO.value(), (byte) 0, messageId, "test".getBytes());
        ByteBuffer buffer = FrameCodec.encode(frame);

        assertThat(FrameCodec.hasCompleteFrame(buffer)).isTrue();

        // Create a truncated buffer
        ByteBuffer truncated = ByteBuffer.allocate(buffer.capacity() - 1);
        buffer.rewind();
        truncated.put(buffer.array(), 0, buffer.capacity() - 1);
        truncated.flip();
        assertThat(FrameCodec.hasCompleteFrame(truncated)).isFalse();
    }

    @Test
    void maxFrameSizeRejected() {
        UUID messageId = UUID.randomUUID();
        byte[] hugePayload = new byte[FrameCodec.MAX_FRAME_SIZE];
        FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_CHUNK.value(), (byte) 0, messageId, hugePayload);

        assertThatThrownBy(() -> FrameCodec.encode(frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Frame too large");
    }

    @Test
    void invalidLengthTooSmallRejected() {
        // Length less than minimum (HEADER_SIZE + MESSAGE_ID_SIZE = 24)
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(20); // too small
        buffer.put((byte) 0x10);
        buffer.put((byte) 0);
        buffer.putShort((short) 0);
        buffer.flip();

        assertThatThrownBy(() -> FrameCodec.decode(buffer))
                .isInstanceOf(FrameCodec.ProtocolException.class)
                .hasMessageContaining("Invalid frame length");
    }

    @Test
    void invalidLengthTooLargeRejected() {
        // Length greater than MAX_FRAME_SIZE
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(FrameCodec.MAX_FRAME_SIZE + 1);
        buffer.put((byte) 0x10);
        buffer.put((byte) 0);
        buffer.putShort((short) 0);
        buffer.flip();

        assertThatThrownBy(() -> FrameCodec.decode(buffer))
                .isInstanceOf(FrameCodec.ProtocolException.class)
                .hasMessageContaining("Invalid frame length");
    }

    @Test
    void incompleteFrameReturnsNull() {
        // Buffer has header but not full frame
        UUID messageId = UUID.randomUUID();
        FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.HELLO.value(), (byte) 0, messageId, "test payload".getBytes());
        ByteBuffer fullBuffer = FrameCodec.encode(frame);

        // Truncate to have header + msgId but not full payload
        ByteBuffer partial = ByteBuffer.allocate(20);
        fullBuffer.rewind();
        byte[] data = new byte[20];
        fullBuffer.get(data);
        partial.put(data);
        partial.flip();

        FrameCodec.Frame result = FrameCodec.decode(partial);
        assertThat(result).isNull();
    }
}