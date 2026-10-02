package com.labconnect.core.discovery;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class DiscoveryManagerTest {

    private DiscoveryManager manager1;
    private DiscoveryManager manager2;
    private AppConfig config1;
    private AppConfig config2;
    private DeviceInfo device1;
    private DeviceInfo device2;

    @BeforeEach
    void setUp() throws IOException {
        config1 = new AppConfig();
        config1.getNetwork().setDiscoveryPort(50001);
        config1.getNetwork().setAnnounceIntervalSec(1);
        config1.getDevice().setName("Test-Device-1");
        config1.getDevice().setType(AppConfig.DeviceType.DESKTOP);

        config2 = new AppConfig();
        config2.getNetwork().setDiscoveryPort(50002);
        config2.getNetwork().setAnnounceIntervalSec(1);
        config2.getDevice().setName("Test-Device-2");
        config2.getDevice().setType(AppConfig.DeviceType.DESKTOP);

        device1 = DeviceInfo.createLocal(
                "DEVICE-TEST-1", "Test-Device-1", DeviceInfo.DeviceType.DESKTOP,
                "127.0.0.1", 5000, "test-public-key-1"
        );
        device2 = DeviceInfo.createLocal(
                "DEVICE-TEST-2", "Test-Device-2", DeviceInfo.DeviceType.DESKTOP,
                "127.0.0.1", 5001, "test-public-key-2"
        );

        manager1 = new DiscoveryManager(config1, device1);
        manager2 = new DiscoveryManager(config2, device2);
    }

    @AfterEach
    void tearDown() {
        if (manager1 != null) manager1.close();
        if (manager2 != null) manager2.close();
    }

    @Test
    @Timeout(5)
    void testDeviceRegistry() {
        // Test the registry directly
        manager1.start();

        DiscoveredDevice discovered = new DiscoveredDevice(
                "DEVICE-TEST-2", "Test-Device-2", DeviceInfo.DeviceType.DESKTOP,
                "1.0", "127.0.0.1", 5001,
                Set.of("FILE_TRANSFER", "GROUPS", "ENCRYPTION"),
                "test-public-key-2",
                java.time.Instant.now()
        );

        manager1.getRegistry().addOrUpdate(discovered);

        assertThat(manager1.getAllDevices()).hasSize(1);
        DeviceInfo found = manager1.getAllDevices().iterator().next();
        assertThat(found.deviceId()).isEqualTo("DEVICE-TEST-2");
        assertThat(found.deviceName()).isEqualTo("Test-Device-2");
        assertThat(found.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(found.tcpPort()).isEqualTo(5001);
    }

    @Test
    @Timeout(5)
    void testManualConnect() {
        manager1.start();
        manager1.manualConnect("192.168.1.100", 5000);

        assertThat(manager1.getAllDevices()).hasSize(1);
        DeviceInfo manual = manager1.getAllDevices().iterator().next();
        assertThat(manual.deviceId()).contains("192.168.1.100");
        assertThat(manual.ipAddress()).isEqualTo("192.168.1.100");
        assertThat(manual.tcpPort()).isEqualTo(5000);
    }

    @Test
    @Timeout(5)
    void testCallbacks() throws InterruptedException {
        manager1.start();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DeviceInfo> callbackDevice = new AtomicReference<>();

        manager1.addCallback("test", info -> {
            callbackDevice.set(info);
            latch.countDown();
        });

        DiscoveredDevice discovered = new DiscoveredDevice(
                "DEVICE-CALLBACK", "Callback-Device", DeviceInfo.DeviceType.DESKTOP,
                "1.0", "10.0.0.1", 6000,
                Set.of("FILE_TRANSFER"),
                "key",
                java.time.Instant.now()
        );

        manager1.getRegistry().addOrUpdate(discovered);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackDevice.get()).isNotNull();
        assertThat(callbackDevice.get().deviceId()).isEqualTo("DEVICE-CALLBACK");
    }
}