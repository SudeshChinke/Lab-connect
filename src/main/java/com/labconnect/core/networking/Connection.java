package com.labconnect.core.networking;

import com.labconnect.core.protocol.FrameCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class Connection {
    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final SocketChannel channel;
    private ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
    private final BiConsumer<Connection, FrameCodec.Frame> onFrame;
    private final Consumer<Connection> onClose;
    private final Runnable onWriteInterestNeeded;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private volatile boolean writeInterestRegistered = false;
    private String remoteDeviceId;
    private volatile long lastHeartbeat = System.currentTimeMillis();

    public Connection(SocketChannel channel,
                      BiConsumer<Connection, FrameCodec.Frame> onFrame,
                      Consumer<Connection> onClose,
                      Runnable onWriteInterestNeeded) {
        this.channel = channel;
        this.onFrame = onFrame;
        this.onClose = onClose;
        this.onWriteInterestNeeded = onWriteInterestNeeded;
    }

    public boolean read() throws IOException {
        ensureWritableSpace();

        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            log.debug("Connection.read: EOF from {}", channel.getRemoteAddress());
            return false; // EOF
        }
        log.debug("Connection.read: read {} bytes from {}", bytesRead, channel.getRemoteAddress());

        readBuffer.flip();
        while (FrameCodec.hasCompleteFrame(readBuffer)) {
            FrameCodec.Frame frame = FrameCodec.decode(readBuffer);
            if (frame != null) {
                log.debug("Connection.read: decoded frame type={}, payload={} bytes", frame.type(), frame.payload().length);
                onFrame.accept(this, frame);
            }
        }
        readBuffer.compact();

        // A frame larger than the buffer can only be assembled once the buffer
        // grows to hold it. Without this, a file chunk (64KB payload + header)
        // never completes and the connection stalls forever.
        if (!readBuffer.hasRemaining() && FrameCodec.peekFrameLength(readBuffer) > readBuffer.capacity()) {
            growReadBuffer();
        }
        return true;
    }

    /**
     * Guarantees at least one byte of free space so a read can make progress,
     * growing the buffer when a partially-received frame fills it.
     */
    private void ensureWritableSpace() {
        if (!readBuffer.hasRemaining()) {
            growReadBuffer();
        }
    }

    /**
     * Doubles the read buffer, up to the maximum frame size. A partially
     * received frame is preserved across the reallocation.
     */
    private void growReadBuffer() {
        int required = FrameCodec.peekFrameLength(readBuffer);
        int newCapacity = readBuffer.capacity() * 2;
        if (required > newCapacity) {
            newCapacity = required;
        }
        if (newCapacity > FrameCodec.MAX_FRAME_SIZE) {
            newCapacity = FrameCodec.MAX_FRAME_SIZE;
        }
        if (newCapacity <= readBuffer.capacity()) {
            // Already at the cap and still full: the frame exceeds what we allow,
            // or the peer is flooding. Drop the stale partial frame and resync.
            log.warn("Read buffer at maximum size {} with incomplete frame, discarding buffer",
                    readBuffer.capacity());
            readBuffer.clear();
            return;
        }

        ByteBuffer grown = ByteBuffer.allocate(newCapacity);
        readBuffer.flip();
        grown.put(readBuffer);
        readBuffer = grown;
        log.debug("Grew read buffer to {} bytes", newCapacity);
    }

    public void updateLastHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public boolean isHeartbeatStale(Duration timeout) {
        return Duration.ofMillis(System.currentTimeMillis() - lastHeartbeat).compareTo(timeout) > 0;
    }

    public void write(ByteBuffer buffer) throws IOException {
        synchronized (writeQueue) {
            writeQueue.add(buffer);
        }
        if (onWriteInterestNeeded != null) {
            onWriteInterestNeeded.run();
        }
    }

    /**
     * Drains the write queue to the socket.
     *
     * <p>Buffers are expected to be in read-ready state (as produced by
     * {@code FrameCodec.encode}), so they are written as-is - no flip. A buffer
     * is only removed from the queue once it has been fully written, so a
     * partial write (a full socket buffer, e.g. on a 64KB file chunk) resumes
     * on the next writable event.
     */
    public void flush() throws IOException {
        int totalWritten = 0;
        while (true) {
            ByteBuffer buffer;
            synchronized (writeQueue) {
                buffer = writeQueue.peek();
            }
            if (buffer == null) {
                break;
            }

            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                totalWritten += written;
                if (written == 0) {
                    // Socket send buffer is full; wait for the next OP_WRITE.
                    break;
                }
            }

            if (buffer.hasRemaining()) {
                // Partially written: keep it queued for the next flush.
                break;
            }
            synchronized (writeQueue) {
                writeQueue.poll();
            }
        }
        if (totalWritten > 0) {
            log.debug("Connection.flush: wrote {} bytes to {}", totalWritten, channel.getRemoteAddress());
        }
    }

    public boolean hasPendingWrites() {
        synchronized (writeQueue) {
            return !writeQueue.isEmpty();
        }
    }

    public void setWriteInterest(boolean interested) {
        this.writeInterestRegistered = interested;
    }

    public boolean isWriteInterestRegistered() {
        return writeInterestRegistered;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    public String getRemoteAddress() {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public String getRemoteDeviceId() {
        return remoteDeviceId;
    }

    public void setRemoteDeviceId(String remoteDeviceId) {
        this.remoteDeviceId = remoteDeviceId;
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        channel.close();
        onClose.accept(this);
    }

    public void sendFrame(FrameCodec.Frame frame) {
        log.debug("Connection.sendFrame: type={}, payload={} bytes", frame.type(), frame.payload().length);
        try {
            write(FrameCodec.encode(frame));
        } catch (IOException e) {
            log.error("Failed to queue frame", e);
        }
    }

    public void sendFrame(byte type, byte flags, UUID messageId, byte[] payload) {
        FrameCodec.Frame frame = FrameCodec.Frame.create(type, flags, messageId, payload);
        sendFrame(frame);
    }

    public void sendAck(UUID originalMessageId, String status) {
        String payload = "{\"originalMessageId\":\"" + originalMessageId + "\",\"status\":\"" + status + "\"," +
                "\"timestamp\":" + System.currentTimeMillis() + "}";
        sendFrame(FrameCodec.Frame.create(
                (byte) 0x11, (byte) 0, UUID.randomUUID(), payload.getBytes()
        ));
    }
}