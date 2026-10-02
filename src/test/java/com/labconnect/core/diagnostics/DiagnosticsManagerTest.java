package com.labconnect.core.diagnostics;

import com.labconnect.core.diagnostics.DiagnosticsManager;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.discovery.DiscoveryManager;
import com.labconnect.core.models.DeviceInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DiagnosticsManagerTest {

    private DiagnosticsManager diagnosticsManager;
    private ConnectionManager connectionManager;
    private DiscoveryManager discoveryManager;
    private String localDeviceId;

    @BeforeEach
    void setUp() throws IOException {
        localDeviceId = "TEST-DEVICE-" + UUID.randomUUID().toString().substring(0, 8);
        
        connectionManager = new ConnectionManager(0, Duration.ofSeconds(5),
                conn -> {}, conn -> {}, frame -> {});
        connectionManager.start();
        
        // Use a minimal config for discovery
        com.labconnect.core.config.AppConfig config = new com.labconnect.core.config.AppConfig();
        config.getNetwork().setDiscoveryPort(50001);
        config.getNetwork().setAnnounceIntervalSec(1);
        config.getNetwork().setHeartbeatIntervalSec(1);
        config.getDevice().setName("Test-Device");
        
        DeviceInfo deviceInfo = DeviceInfo.createLocal(
                localDeviceId, "Test-Device", DeviceInfo.DeviceType.DESKTOP,
                "127.0.0.1", 5000, "test-key"
        );
        
        discoveryManager = new DiscoveryManager(config, deviceInfo);
        
        diagnosticsManager = new DiagnosticsManager(connectionManager, discoveryManager, localDeviceId);
    }

    @AfterEach
    void tearDown() {
        if (diagnosticsManager != null) diagnosticsManager.close();
        if (connectionManager != null) connectionManager.close();
        if (discoveryManager != null) discoveryManager.close();
    }

    @Test
    @Timeout(10)
    void testGetNetworkInfo() {
        var netInfo = diagnosticsManager.getNetworkInfo();
        
        assertThat(netInfo.hostname()).isNotNull();
        assertThat(netInfo.localIp()).isNotNull();
        assertThat(netInfo.interfaces()).isNotEmpty();
    }

    @Test
    @Timeout(10)
    void testGetSystemHealth() {
        var health = diagnosticsManager.getSystemHealth();
        
        assertThat(health.timestamp()).isNotNull();
        assertThat(health.usedMemory()).isGreaterThan(0);
        assertThat(health.maxMemory()).isGreaterThan(0);
        assertThat(health.totalDisk()).isGreaterThan(0);
        assertThat(health.memoryUsagePercent()).isBetween(0.0, 100.0);
        assertThat(health.diskUsagePercent()).isBetween(0.0, 100.0);
    }

    @Test
    @Timeout(10)
    void testEventLogging() {
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.INFO,
                "Test event",
                "TEST"
        ));
        
        var events = diagnosticsManager.getRecentEvents(10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).message()).isEqualTo("Test event");
        assertThat(events.get(0).type()).isEqualTo(DiagnosticsManager.DiagnosticEvent.Type.INFO);
    }

    @Test
    @Timeout(10)
    void testGetRecentEvents() {
        for (int i = 0; i < 5; i++) {
            diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                    DiagnosticsManager.DiagnosticEvent.Type.INFO,
                    "Event " + i,
                    "TEST"
            ));
        }
        
        var events = diagnosticsManager.getRecentEvents(3);
        assertThat(events).hasSize(3);
        // Should be the last 3
        assertThat(events.get(0).message()).isEqualTo("Event 2");
        assertThat(events.get(2).message()).isEqualTo("Event 4");
    }

    @Test
    @Timeout(10)
    void testGetEventsByType() {
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.INFO, "Info event", "TEST"));
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.WARNING, "Warning event", "TEST"));
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.ERROR, "Error event", "TEST"));
        
        var warnings = diagnosticsManager.getEventsByType(DiagnosticsManager.DiagnosticEvent.Type.WARNING);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message()).isEqualTo("Warning event");
    }

    @Test
    @Timeout(10)
    void testGetEventsSince() throws InterruptedException {
        // Add first event
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.INFO, "Before", "TEST"));
        
        // Small delay to ensure different timestamps
        Thread.sleep(50);
        
        // Capture "after" time BEFORE creating the second event
        Instant after = Instant.now();
        
        // Small delay to ensure the next event has a timestamp strictly after 'after'
        Thread.sleep(50);
        
        // Add second event with explicit timestamp AFTER 'after'
        Instant afterPlus = after.plusMillis(10);
        DiagnosticsManager.DiagnosticEvent afterEvent = new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.INFO, "After", "TEST", afterPlus);
        diagnosticsManager.logEvent(afterEvent);
        
        var sinceAfter = diagnosticsManager.getEvents(after);
        assertThat(sinceAfter).hasSize(1);
        assertThat(sinceAfter.get(0).message()).isEqualTo("After");
    }

    @Test
    @Timeout(10)
    void testRecordBytes() {
        String deviceId = "TEST-DEVICE";
        
        diagnosticsManager.recordBytesSent(deviceId, 1024);
        diagnosticsManager.recordBytesReceived(deviceId, 512);
        
        var connStatuses = diagnosticsManager.getConnectionStatuses();
        // Note: connection statuses are for active connections, metrics are separate
        // This tests the metrics recording doesn't throw
    }

    @Test
    @Timeout(10)
    void testRecordError() {
        String deviceId = "TEST-DEVICE";
        
        diagnosticsManager.recordError(deviceId, "Test error");
        
        var events = diagnosticsManager.getEventsByType(DiagnosticsManager.DiagnosticEvent.Type.ERROR);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).message()).contains("Test error");
        assertThat(events.get(0).source()).isEqualTo(deviceId);
    }

    @Test
    @Timeout(10)
    void testGenerateDiagnosticReport() {
        String report = diagnosticsManager.generateDiagnosticReport();
        
        assertThat(report).contains("LabConnect Diagnostic Report");
        assertThat(report).contains(localDeviceId);
        assertThat(report).contains("Network Info");
        assertThat(report).contains("Connections");
        assertThat(report).contains("Discovery");
        assertThat(report).contains("System Health");
    }
}