package com.labconnect.core.security;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.desktop.services.DesktopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopPairingEndToEndTest {
    @TempDir Path directory;
    private DesktopService alice;
    private DesktopService bob;

    @AfterEach void tearDown() {
        if (alice != null) alice.shutdown();
        if (bob != null) bob.shutdown();
    }

    private static AppConfig config(String name) {
        AppConfig config = new AppConfig();
        config.getNetwork().setTcpPort(0);
        config.getNetwork().setHeartbeatIntervalSec(30);
        config.getDevice().setName(name);
        return config;
    }

    @Test @Timeout(20)
    void requestIsShownAcceptedAndSavedOnBothRealDesktopServices() throws Exception {
        Path aliceDir = Files.createDirectory(directory.resolve("alice"));
        Path bobDir = Files.createDirectory(directory.resolve("bob"));
        alice = new DesktopService(config("Alice"), aliceDir.resolve("identity.dat"));
        bob = new DesktopService(config("Bob"), bobDir.resolve("identity.dat"));
        alice.getDiscoveryManager().registerDevice(DeviceInfo.createLocal(
                bob.getLocalDeviceId(), "Bob", DeviceInfo.DeviceType.DESKTOP, "127.0.0.1",
                bob.getConnectionManager().getLocalPort(), bob.getLocalDeviceInfo().publicKey()));

        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<PairingManager.PairingResult> result = new AtomicReference<>();
        bob.getPairingManager().setOnIncomingPairingRequest(request -> {
            assertThat(request.requesterDeviceId()).isEqualTo(alice.getLocalDeviceId());
            bob.getPairingManager().acceptPairing(request.sessionId());
        });
        assertThat(alice.connectToDevice(bob.getLocalDeviceId())).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (alice.getConnectionManager().getConnection(bob.getLocalDeviceId())
                .filter(com.labconnect.core.networking.Connection::isConnected).isEmpty()
                && System.nanoTime() < deadline) Thread.sleep(20);

        alice.getPairingManager().initiatePairing(bob.getLocalDeviceId(), "Bob",
                bob.getLocalDeviceInfo().publicKey(), value -> { result.set(value); accepted.countDown(); });

        assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().success()).isTrue();
        assertThat(new TrustStore(aliceDir.resolve("truststore.properties")).isTrusted(bob.getLocalDeviceId())).isTrue();
        assertThat(new TrustStore(bobDir.resolve("truststore.properties")).isTrusted(alice.getLocalDeviceId())).isTrue();
    }
}
