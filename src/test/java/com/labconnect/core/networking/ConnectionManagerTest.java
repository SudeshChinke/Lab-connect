package com.labconnect.core.networking;

import com.labconnect.core.protocol.FrameCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ConnectionManagerTest {

    private ConnectionManager serverManager;
    private ConnectionManager clientManager;
    private static final int TEST_PORT = 5001;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        serverManager = new ConnectionManager(TEST_PORT, Duration.ofSeconds(5),
                conn -> {},
                conn -> {},
                frame -> {}
        );
        serverManager.start();
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        if (clientManager != null) {
            try { clientManager.close(); } catch (Exception ignored) {}
        }
        if (serverManager != null) {
            try { serverManager.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @Timeout(10)
    void testClientServerConnection() throws IOException, InterruptedException {
        CountDownLatch clientConnected = new CountDownLatch(1);
        AtomicReference<Connection> clientConnRef = new AtomicReference<>();

        clientManager = new ConnectionManager(0, Duration.ofSeconds(5),
                conn -> {
                    clientConnRef.set(conn);
                    clientConnected.countDown();
                },
                conn -> {},
                frame -> {}
        );
        clientManager.start();

        Connection clientConn = clientManager.connect("localhost", TEST_PORT);

        // Wait for connection to establish
        assertThat(clientConnected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(clientConnRef.get()).isNotNull();
        assertThat(clientConnRef.get().isConnected()).isTrue();

        // Give server time to register the connection
        Thread.sleep(200);
        assertThat(serverManager.getAllConnections()).hasSize(1);

        // Verify server connection is also established
        Connection serverConn = serverManager.getAllConnections().iterator().next();
        assertThat(serverConn.isConnected()).isTrue();
    }

    // Frame delivery used to live here as a stub:
    //   // Disabled - frame delivery timing issue in test environment
    // That "timing issue" was a real defect: Connection.flush() double-flipped
    // buffers that FrameCodec.encode() had already flipped, so channel.write()
    // was never called and no data ever reached a peer. The stub was removed
    // rather than re-enabled because real delivery coverage now lives in
    // P2PFrameDeliveryTest, which asserts actual payload arrival (single frame,
    // default 64KB chunk, 512KB partial-write, many frames, and reverse
    // direction) rather than only connection state.
}