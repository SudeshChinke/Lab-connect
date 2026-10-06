package com.labconnect.desktop.services;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers B1 (real device identity from KeyPairGenerator, persisted) and B2
 * (live device-list callbacks) at the DesktopService level. The UI thread
 * hop around these callbacks is JavaFX-only; what is tested here is that
 * the service actually fires them.
 */
class DesktopServiceTest {

    @TempDir
    Path identityDir;

    private DesktopService serviceA;
    private DesktopService serviceB;

    private static AppConfig config(String name) {
        AppConfig config = new AppConfig();
        config.getNetwork().setTcpPort(0);
        config.getNetwork().setHeartbeatIntervalSec(30);
        config.getDevice().setName(name);
        config.getDevice().setType(AppConfig.DeviceType.DESKTOP);
        return config;
    }

    @AfterEach
    void tearDown() {
        if (serviceA != null) serviceA.shutdown();
        if (serviceB != null) serviceB.shutdown();
    }

    @Test
    @Timeout(15)
    void eachInstanceGetsARealDistinctPublicKey() throws IOException {
        serviceA = new DesktopService(config("Alice"), identityDir.resolve("a.dat"));
        serviceB = new DesktopService(config("Bob"), identityDir.resolve("b.dat"));

        String keyA = serviceA.getLocalDeviceInfo().publicKey();
        String keyB = serviceB.getLocalDeviceInfo().publicKey();

        // B1: no shared placeholder key.
        assertThat(keyA).isNotEqualTo("local-public-key");
        assertThat(keyB).isNotEqualTo("local-public-key");
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(serviceA.getLocalDeviceId()).isNotEqualTo(serviceB.getLocalDeviceId());

        // The identity handed out for HELLO/TLS is the persisted one.
        assertThat(serviceA.getIdentity().publicKeyBase64()).isEqualTo(keyA);

        // deviceId is derived from the key, not random.
        assertThat(serviceA.getLocalDeviceId())
                .isEqualTo(com.labconnect.core.security.KeyPairGenerator.deriveDeviceId(keyA));
    }

    @Test
    @Timeout(15)
    void identitySurvivesRestart() throws IOException {
        Path file = identityDir.resolve("restart.dat");

        serviceA = new DesktopService(config("Peer"), file);
        String firstId = serviceA.getLocalDeviceId();
        String firstKey = serviceA.getLocalDeviceInfo().publicKey();
        serviceA.shutdown();
        serviceA = null;

        serviceA = new DesktopService(config("Peer"), file);

        assertThat(serviceA.getLocalDeviceId()).isEqualTo(firstId);
        assertThat(serviceA.getLocalDeviceInfo().publicKey()).isEqualTo(firstKey);
    }

    @Test
    @Timeout(15)
    void firesDeviceListCallbackWhenRegistryChanges() throws Exception {
        serviceA = new DesktopService(config("Watcher"), identityDir.resolve("watcher.dat"));

        CountDownLatch added = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        AtomicReference<DeviceInfo> seen = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        serviceA.setOnDeviceListChanged(device -> {
            seen.set(device);
            if (events.getAndIncrement() == 0) added.countDown();
            else removed.countDown();
        });

        DeviceInfo peer = DeviceInfo.createLocal(
                "DEVICE-LIVE0001", "LivePeer", DeviceInfo.DeviceType.DESKTOP,
                "10.0.0.5", 5000, "peer-public-key");
        serviceA.getDiscoveryManager().registerDevice(peer);

        assertThat(added.await(5, TimeUnit.SECONDS))
                .as("registering a device must notify the list listener").isTrue();
        assertThat(seen.get().deviceId()).isEqualTo("DEVICE-LIVE0001");

        // Registry removal (e.g. TTL expiry) must notify as well, so stale
        // rows disappear from the list without a manual refresh.
        serviceA.getDiscoveryManager().removeDevice("DEVICE-LIVE0001");
        assertThat(removed.await(5, TimeUnit.SECONDS))
                .as("removing a device must notify the list listener").isTrue();
    }
}
