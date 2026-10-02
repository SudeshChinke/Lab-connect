package com.labconnect.core.networking;

import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end frame delivery over a real TCP socket.
 *
 * <p>Regression test for a serious bug: {@link Connection#flush()} called
 * {@code buffer.flip()} on buffers that were already in read-ready state (as
 * produced by {@link FrameCodec#encode}). The double flip swapped position and
 * limit so {@code hasRemaining()} was always false and {@code channel.write()}
 * was never invoked. The socket stayed connected and every existing test passed,
 * but <em>no data was ever delivered</em> - discovery and the handshake worked
 * while all chat, heartbeat and file traffic silently vanished.
 */
class P2PFrameDeliveryTest {

    private ConnectionManager server;
    private ConnectionManager client;
    private int serverPort;

    /** Set by each test; the server's frame callback delegates here. */
    private Consumer<FrameCodec.Frame> serverInbox = frame -> {};
    private Consumer<FrameCodec.Frame> clientInbox = frame -> {};

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        serverPort = freePort();

        // Both managers are created once per test and their frame callbacks
        // delegate to the fields above, so no manager is ever closed/recreated
        // mid-test (that previously left stale sockets on the shared port).
        server = new ConnectionManager(serverPort, Duration.ofSeconds(30),
                conn -> {}, conn -> {}, frame -> serverInbox.accept(frame));
        server.start();

        client = new ConnectionManager(freePort(), Duration.ofSeconds(30),
                conn -> {}, conn -> {}, frame -> clientInbox.accept(frame));
        client.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) { }
        }
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("a text message actually reaches the peer")
    void textMessageIsDelivered() throws Exception {
        List<FrameCodec.Frame> inbox = new CopyOnWriteArrayList<>();
        CountDownLatch gotMessage = new CountDownLatch(1);
        serverInbox = frame -> {
            inbox.add(frame);
            if (frame.type() == MessageType.TEXT_MESSAGE.value()) {
                gotMessage.countDown();
            }
        };

        Connection conn = client.connect("localhost", serverPort);
        Thread.sleep(300);

        String body = "hello peer";
        client.sendFrame(conn, FrameCodec.Frame.create(
                MessageType.TEXT_MESSAGE.value(), (byte) 0, UUID.randomUUID(),
                body.getBytes(StandardCharsets.UTF_8)));

        assertThat(gotMessage.await(10, TimeUnit.SECONDS))
                .as("peer should receive the text message")
                .isTrue();

        FrameCodec.Frame received = inbox.stream()
                .filter(f -> f.type() == MessageType.TEXT_MESSAGE.value())
                .findFirst()
                .orElseThrow();

        assertThat(new String(received.payload(), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    @Timeout(30)
    @DisplayName("a large payload survives partial socket writes")
    void largePayloadIsDeliveredIntact() throws Exception {
        // 512KB exceeds typical socket buffers, forcing partial reads/writes.
        int payloadSize = 512 * 1024;
        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            payload[i] = (byte) (i % 251);
        }

        CountDownLatch gotLarge = new CountDownLatch(1);
        List<byte[]> inbox = new CopyOnWriteArrayList<>();
        serverInbox = frame -> {
            if (frame.type() == MessageType.FILE_CHUNK.value()) {
                inbox.add(frame.payload());
                gotLarge.countDown();
            }
        };

        Connection conn = client.connect("localhost", serverPort);
        Thread.sleep(300);

        client.sendFrame(conn, FrameCodec.Frame.create(
                MessageType.FILE_CHUNK.value(), (byte) 0, UUID.randomUUID(), payload));

        assertThat(gotLarge.await(20, TimeUnit.SECONDS))
                .as("peer should receive the large chunk")
                .isTrue();
        assertThat(inbox.get(0)).hasSize(payloadSize).isEqualTo(payload);
    }

    @Test
    @Timeout(25)
    @DisplayName("a default-size file chunk (64KB) is delivered")
    void defaultFileChunkIsDelivered() throws Exception {
        // This is the size config.yaml ships with (chunkSize: 65536); the frame
        // is 65560 bytes, just over the original 64KB read buffer.
        int payloadSize = 65536;
        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            payload[i] = (byte) (i % 251);
        }

        CountDownLatch got = new CountDownLatch(1);
        List<byte[]> inbox = new CopyOnWriteArrayList<>();
        serverInbox = frame -> {
            if (frame.type() == MessageType.FILE_CHUNK.value()) {
                inbox.add(frame.payload());
                got.countDown();
            }
        };

        Connection conn = client.connect("localhost", serverPort);
        Thread.sleep(300);

        client.sendFrame(conn, FrameCodec.Frame.create(
                MessageType.FILE_CHUNK.value(), (byte) 0, UUID.randomUUID(), payload));

        assertThat(got.await(20, TimeUnit.SECONDS))
                .as("a default 64KB chunk should arrive intact")
                .isTrue();
        assertThat(inbox.get(0)).isEqualTo(payload);
    }

    @Test
    @Timeout(25)
    @DisplayName("multiple frames all arrive intact")
    void multipleFramesAllArrive() throws Exception {
        int count = 25;
        List<String> inbox = new CopyOnWriteArrayList<>();
        CountDownLatch all = new CountDownLatch(count);
        serverInbox = frame -> {
            if (frame.type() == MessageType.TEXT_MESSAGE.value()) {
                inbox.add(new String(frame.payload(), StandardCharsets.UTF_8));
                all.countDown();
            }
        };

        Connection conn = client.connect("localhost", serverPort);
        Thread.sleep(300);

        // sendFrame dispatches onto a worker pool, so delivery order across
        // frames is not guaranteed; assert every payload arrives uncorrupted.
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String body = "msg-" + i;
            sent.add(body);
            client.sendFrame(conn, FrameCodec.Frame.create(
                    MessageType.TEXT_MESSAGE.value(), (byte) 0, UUID.randomUUID(),
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        assertThat(all.await(15, TimeUnit.SECONDS))
                .as("all frames should arrive")
                .isTrue();
        assertThat(inbox).hasSize(count);
        assertThat(inbox).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    @Timeout(20)
    @DisplayName("delivery works in the reverse direction too")
    void deliveryIsBidirectional() throws Exception {
        CountDownLatch clientGot = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        clientInbox = frame -> {
            if (frame.type() == MessageType.TEXT_MESSAGE.value()) {
                received.add(new String(frame.payload(), StandardCharsets.UTF_8));
                clientGot.countDown();
            }
        };

        Connection conn = client.connect("localhost", serverPort);
        Thread.sleep(500);

        List<Connection> serverSide = new ArrayList<>(server.getAllConnections());
        assertThat(serverSide).hasSize(1);

        server.sendFrame(serverSide.get(0), FrameCodec.Frame.create(
                MessageType.TEXT_MESSAGE.value(), (byte) 0, UUID.randomUUID(),
                "from server".getBytes(StandardCharsets.UTF_8)));

        assertThat(clientGot.await(10, TimeUnit.SECONDS))
                .as("client should receive the server's message")
                .isTrue();
        assertThat(received).containsExactly("from server");
    }
}
