package com.labconnect.core.messaging;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.desktop.services.DesktopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end chat over real TCP sockets between two complete DesktopService
 * instances: connect (E2), frame routing (E1), chat send (E3) and inbound
 * delivery plus ack round-trip (E4).
 *
 * <p>Per the project's testing policy this asserts the actual payload arrives
 * at the peer's ChatManager callback - not merely that a connection exists.
 * UDP discovery is bypassed by seeding the registry directly so the test is
 * deterministic; everything after that (TCP, HELLO identity exchange, frame
 * routing, JSON serialization, acks) is the real production path.
 */
class ChatEndToEndTest {

    private DesktopService alice;
    private DesktopService bob;

    /** Distinct identity files per instance - sharing one would mean sharing an identity. */
    @TempDir
    Path identityDir;

    private static AppConfig config(String name) {
        AppConfig config = new AppConfig();
        config.getNetwork().setTcpPort(0);                    // ephemeral port
        config.getNetwork().setHeartbeatIntervalSec(30);      // keep stale-detection away from assertions
        config.getDevice().setName(name);
        config.getDevice().setType(AppConfig.DeviceType.DESKTOP);
        return config;
    }

    @BeforeEach
    void setUp() throws IOException {
        alice = new DesktopService(config("Alice"), identityDir.resolve("alice.dat"));
        bob = new DesktopService(config("Bob"), identityDir.resolve("bob.dat"));
    }

    @AfterEach
    void tearDown() {
        if (alice != null) alice.shutdown();
        if (bob != null) bob.shutdown();
    }

    /** Seeds Alice's registry with Bob's real identity (as discovery would). */
    private void aliceDiscoversBob() {
        alice.getDiscoveryManager().registerDevice(DeviceInfo.createLocal(
                bob.getLocalDeviceId(), "Bob", DeviceInfo.DeviceType.DESKTOP,
                "127.0.0.1", bob.getConnectionManager().getLocalPort(), "bob-public-key"));
    }

    @Test
    @Timeout(20)
    void textMessageTravelsEndToEndWithAckRoundTrip() throws Exception {
        aliceDiscoversBob();

        CountDownLatch bobInbox = new CountDownLatch(1);
        CountDownLatch aliceInbox = new CountDownLatch(1);
        CountDownLatch aliceDelivered = new CountDownLatch(1);
        AtomicReference<TextMessage> receivedByBob = new AtomicReference<>();
        AtomicReference<TextMessage> receivedByAlice = new AtomicReference<>();
        AtomicReference<TextMessage> deliveredOnAlice = new AtomicReference<>();

        // Register every listener before sending: acks race the assertions.
        bob.setOnMessageReceived(m -> { receivedByBob.set(m); bobInbox.countDown(); });
        alice.setOnMessageReceived(m -> { receivedByAlice.set(m); aliceInbox.countDown(); });
        alice.setOnMessageDelivered(m -> { deliveredOnAlice.set(m); aliceDelivered.countDown(); });

        // E2: connect via discovery lookup + HELLO identity exchange.
        assertThat(alice.connectToDevice(bob.getLocalDeviceId())).isTrue();
        await("HELLO handshake binding Bob's deviceId on Alice", () ->
                alice.getConnectionManager().getConnection(bob.getLocalDeviceId())
                        .filter(c -> c.isConnected()).isPresent());

        // E3: serialize and put the frame on the wire.
        assertThat(alice.getChatManager().sendMessage(bob.getLocalDeviceId(), "hello over the LAN"))
                .isTrue();

        // E1 + E4: the frame is routed through DesktopService into Bob's
        // ChatManager and out to the application callback with the payload intact.
        assertThat(bobInbox.await(5, TimeUnit.SECONDS))
                .as("inbound message should arrive at Bob").isTrue();
        assertThat(receivedByBob.get().content()).isEqualTo("hello over the LAN");
        assertThat(receivedByBob.get().senderId()).isEqualTo(alice.getLocalDeviceId());

        // Bob's MESSAGE_ACK travels back and settles Alice's PendingAck.
        assertThat(aliceDelivered.await(5, TimeUnit.SECONDS))
                .as("delivery receipt should return to Alice").isTrue();
        assertThat(deliveredOnAlice.get().content()).isEqualTo("hello over the LAN");
        assertThat(deliveredOnAlice.get().status()).isEqualTo(MessageStatus.DELIVERED);

        // Reverse direction over the same connection.
        assertThat(bob.getChatManager().sendMessage(alice.getLocalDeviceId(), "reply from Bob"))
                .isTrue();
        assertThat(aliceInbox.await(5, TimeUnit.SECONDS))
                .as("reply should arrive at Alice").isTrue();
        assertThat(receivedByAlice.get().content()).isEqualTo("reply from Bob");
        assertThat(receivedByAlice.get().senderId()).isEqualTo(bob.getLocalDeviceId());
    }

    @Test
    @Timeout(10)
    void sendMessageWithoutConnectionReturnsFalse() {
        assertThat(alice.getChatManager().sendMessage("DEVICE-never-seen", "anyone there?"))
                .as("send with no connection must fail, not silently drop").isFalse();
    }

    @Test
    @Timeout(10)
    void connectToUnknownDeviceReturnsFalse() {
        assertThat(alice.connectToDevice("DEVICE-not-discovered")).isFalse();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }
}
