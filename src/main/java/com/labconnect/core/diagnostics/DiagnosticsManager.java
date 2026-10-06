package com.labconnect.core.diagnostics;

import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.discovery.DiscoveryManager;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.core.networking.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public final class DiagnosticsManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticsManager.class);

    private final ConnectionManager connectionManager;
    private final DiscoveryManager discoveryManager;
    private final String localDeviceId;
    private final ScheduledExecutorService metricsExecutor = Executors.newSingleThreadScheduledExecutor();
    
    private final Map<String, ConnectionMetrics> connectionMetrics = new ConcurrentHashMap<>();
    private final List<DiagnosticEvent> eventLog = Collections.synchronizedList(new ArrayList<>());
    private final int maxEventLogSize = 10000;

    public DiagnosticsManager(ConnectionManager connectionManager, DiscoveryManager discoveryManager, String localDeviceId) {
        this.connectionManager = connectionManager;
        this.discoveryManager = discoveryManager;
        this.localDeviceId = localDeviceId;
        
        // Periodic metrics collection
        metricsExecutor.scheduleAtFixedRate(this::collectMetrics, 5, 5, TimeUnit.SECONDS);
        
        log.info("DiagnosticsManager initialized for device: {}", localDeviceId);
    }

    // === Network Interface Information ===

    public NetworkInfo getNetworkInfo() {
        NetworkInfo.Builder builder = NetworkInfo.builder();
        
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                
                // Skip loopback, down, virtual interfaces
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                
                InterfaceInfo iface = new InterfaceInfo(
                        ni.getName(),
                        ni.getDisplayName(),
                        ni.getInetAddresses().hasMoreElements() 
                            ? ni.getInetAddresses().nextElement().getHostAddress() 
                            : "N/A",
                        ni.isUp() ? "UP" : "DOWN",
                        ni.getMTU()
                );
                builder.addInterface(iface);
            }
        } catch (SocketException e) {
            log.warn("Failed to enumerate network interfaces", e);
        }
        
        // Get local IP
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            builder.localIp(localHost.getHostAddress());
            builder.hostname(localHost.getHostName());
        } catch (UnknownHostException e) {
            log.warn("Failed to get local host info", e);
        }
        
        // Get default gateway (approximate)
        builder.gateway(getDefaultGateway());
        
        return builder.build();
    }

    private String getDefaultGateway() {
        try {
            // Try to get from network interface
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
                    for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                        InetAddress broadcast = addr.getBroadcast();
                        if (broadcast != null) {
                            return broadcast.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Failed to get gateway", e);
        }
        return "Unknown";
    }

    // === Connection Status ===

    public List<ConnectionStatus> getConnectionStatuses() {
        return connectionManager.getAllConnections().stream()
                .map(conn -> {
                    // Connections are not yet bound to a deviceId until their
                    // HELLO arrives; fall back to the socket address.
                    String deviceId = conn.getRemoteDeviceId();
                    String displayId = deviceId != null ? deviceId : conn.getRemoteAddress();
                    ConnectionMetrics metrics = deviceId != null ? connectionMetrics.get(deviceId) : null;
                    return new ConnectionStatus(
                            displayId,
                            conn.getRemoteAddress(),
                            conn.isConnected() ? "CONNECTED" : "DISCONNECTED",
                            deviceId != null && deviceId.equals(localDeviceId) ? "LOCAL" : "REMOTE",
                            metrics != null ? metrics.bytesSent : 0,
                            metrics != null ? metrics.bytesReceived : 0,
                            metrics != null ? metrics.lastActivity : null,
                            metrics != null ? metrics.errorCount : 0
                    );
                })
                .collect(Collectors.toList());
    }

    public int getActiveConnectionCount() {
        return (int) connectionManager.getAllConnections().stream()
                .filter(Connection::isConnected)
                .count();
    }

    // === Discovery Status ===

    public DiscoveryStatus getDiscoveryStatus() {
        // Guard: a null discovery manager must not take down the caller (the UI
        // calls this on every refresh). Report an empty status instead.
        if (discoveryManager == null) {
            return new DiscoveryStatus(0, Collections.emptyList(), 0, 0, 0);
        }
        List<DeviceInfo> devices = new ArrayList<>(discoveryManager.getAllDevices());
        return new DiscoveryStatus(
                devices.size(),
                devices.stream().map(DeviceInfo::deviceId).collect(Collectors.toList()),
                devices.stream().filter(d -> d.status() == DeviceInfo.DeviceStatus.ONLINE).count(),
                devices.stream().filter(d -> d.status() == DeviceInfo.DeviceStatus.OFFLINE).count(),
                discoveryManager.getDeviceCount()
        );
    }

    // === System Health ===

    public SystemHealth getSystemHealth() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        File root = new File("/");
        long totalDisk = root.getTotalSpace();
        long freeDisk = root.getFreeSpace();
        long usedDisk = totalDisk - freeDisk;
        
        return new SystemHealth(
                Instant.now(),
                usedMemory, maxMemory, totalMemory,
                usedDisk, totalDisk,
                getActiveConnectionCount(),
                connectionMetrics.size(),
                eventLog.size()
        );
    }

    // === Event Logging ===

    public void logEvent(DiagnosticEvent event) {
        eventLog.add(event);
        if (eventLog.size() > maxEventLogSize) {
            eventLog.remove(0);
        }
    }

    public List<DiagnosticEvent> getEvents(Instant since) {
        return eventLog.stream()
                .filter(e -> e.timestamp().isAfter(since))
                .collect(Collectors.toList());
    }

    public List<DiagnosticEvent> getEventsByType(DiagnosticEvent.Type type) {
        return eventLog.stream()
                .filter(e -> e.type() == type)
                .collect(Collectors.toList());
    }

    public List<DiagnosticEvent> getRecentEvents(int limit) {
        if (eventLog.size() <= limit) return new ArrayList<>(eventLog);
        return new ArrayList<>(eventLog.subList(eventLog.size() - limit, eventLog.size()));
    }

    // === Metrics Collection ===

    private void collectMetrics() {
        connectionManager.getAllConnections().forEach(conn -> {
            // remoteDeviceId is null until HELLO arrives; ConcurrentHashMap
            // rejects null keys, and an NPE here would cancel this task
            // permanently.
            if (conn.isConnected() && conn.getRemoteDeviceId() != null) {
                ConnectionMetrics metrics = connectionMetrics.computeIfAbsent(
                        conn.getRemoteDeviceId(), k -> new ConnectionMetrics(conn.getRemoteDeviceId())
                );
                metrics.updateActivity();
            }
        });
        
        // Log system health periodically
        SystemHealth health = getSystemHealth();
        if (health.memoryUsagePercent() > 85) {
            logEvent(new DiagnosticEvent(
                    DiagnosticEvent.Type.WARNING,
                    "High memory usage: " + health.memoryUsagePercent() + "%",
                    "SYSTEM"
            ));
        }
        if (health.diskUsagePercent() > 90) {
            logEvent(new DiagnosticEvent(
                    DiagnosticEvent.Type.WARNING,
                    "High disk usage: " + health.diskUsagePercent() + "%",
                    "SYSTEM"
            ));
        }
    }

    public void recordBytesSent(String deviceId, long bytes) {
        connectionMetrics.computeIfAbsent(deviceId, k -> new ConnectionMetrics(deviceId))
                .addBytesSent(bytes);
    }

    public void recordBytesReceived(String deviceId, long bytes) {
        connectionMetrics.computeIfAbsent(deviceId, k -> new ConnectionMetrics(deviceId))
                .addBytesReceived(bytes);
    }

    public void recordError(String deviceId, String error) {
        connectionMetrics.computeIfAbsent(deviceId, k -> new ConnectionMetrics(deviceId))
                .incrementErrors();
        logEvent(new DiagnosticEvent(
                DiagnosticEvent.Type.ERROR,
                "Connection error with " + deviceId + ": " + error,
                deviceId
        ));
    }

    // === Export/Report ===

    public String generateDiagnosticReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== LabConnect Diagnostic Report ===\n");
        sb.append("Generated: ").append(Instant.now()).append("\n");
        sb.append("Device ID: ").append(localDeviceId).append("\n\n");
        
        sb.append("--- Network Info ---\n");
        NetworkInfo net = getNetworkInfo();
        sb.append("Hostname: ").append(net.hostname()).append("\n");
        sb.append("Local IP: ").append(net.localIp()).append("\n");
        sb.append("Gateway: ").append(net.gateway()).append("\n");
        sb.append("Interfaces:\n");
        net.interfaces().forEach(i -> sb.append("  ").append(i.name()).append(": ")
                .append(i.ip()).append(" (").append(i.status()).append(")\n"));
        
        sb.append("\n--- Connections ---\n");
        getConnectionStatuses().forEach(c -> sb.append("  ")
                .append(c.deviceId()).append(": ").append(c.status())
                .append(" (sent: ").append(formatBytes(c.bytesSent()))
                .append(", recv: ").append(formatBytes(c.bytesReceived())).append(")\n"));
        
        sb.append("\n--- Discovery ---\n");
        DiscoveryStatus disc = getDiscoveryStatus();
        sb.append("Total devices: ").append(disc.totalDevices()).append("\n");
        sb.append("Online: ").append(disc.onlineCount()).append("\n");
        sb.append("Offline: ").append(disc.offlineCount()).append("\n");
        
        sb.append("\n--- System Health ---\n");
        SystemHealth health = getSystemHealth();
        sb.append("Memory: ").append(formatBytes(health.usedMemory())).append(" / ")
                .append(formatBytes(health.maxMemory())).append(" (").append(health.memoryUsagePercent()).append("%)\n");
        sb.append("Disk: ").append(formatBytes(health.usedDisk())).append(" / ")
                .append(formatBytes(health.totalDisk())).append(" (").append(health.diskUsagePercent()).append("%)\n");
        sb.append("Active connections: ").append(health.activeConnections()).append("\n");
        
        sb.append("\n--- Recent Events ---\n");
        getRecentEvents(20).forEach(e -> sb.append("  [")
                .append(e.timestamp()).append("] ").append(e.type()).append(": ")
                .append(e.message()).append("\n"));
        
        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }

    @Override
    public void close() {
        metricsExecutor.shutdown();
        try {
            if (!metricsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                metricsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            metricsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // === Data Classes ===

    public static class NetworkInfo {
        private final String hostname;
        private final String localIp;
        private final String gateway;
        private final List<InterfaceInfo> interfaces;

        private NetworkInfo(Builder b) {
            this.hostname = b.hostname;
            this.localIp = b.localIp;
            this.gateway = b.gateway;
            this.interfaces = b.interfaces;
        }

        public String hostname() { return hostname; }
        public String localIp() { return localIp; }
        public String gateway() { return gateway; }
        public List<InterfaceInfo> interfaces() { return interfaces; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            String hostname;
            String localIp;
            String gateway;
            List<InterfaceInfo> interfaces = new ArrayList<>();

            public Builder hostname(String h) { this.hostname = h; return this; }
            public Builder localIp(String ip) { this.localIp = ip; return this; }
            public Builder gateway(String g) { this.gateway = g; return this; }
            public Builder addInterface(InterfaceInfo i) { this.interfaces.add(i); return this; }
            public NetworkInfo build() { return new NetworkInfo(this); }
        }
    }

    public record InterfaceInfo(String name, String displayName, String ip, String status, int mtu) {}

    public record ConnectionStatus(
            String deviceId,
            String remoteAddress,
            String status,
            String direction,
            long bytesSent,
            long bytesReceived,
            Instant lastActivity,
            int errorCount
    ) {}

    public record DiscoveryStatus(
            int totalDevices,
            List<String> deviceIds,
            long onlineCount,
            long offlineCount,
            int registrySize
    ) {}

    public record SystemHealth(
            Instant timestamp,
            long usedMemory,
            long maxMemory,
            long totalMemory,
            long usedDisk,
            long totalDisk,
            int activeConnections,
            int trackedConnections,
            int eventCount
    ) {
        public double memoryUsagePercent() { return (double) usedMemory / maxMemory * 100; }
        public double diskUsagePercent() { return (double) usedDisk / totalDisk * 100; }
    }

    public static class ConnectionMetrics {
        private final String deviceId;
        private long bytesSent = 0;
        private long bytesReceived = 0;
        private int errorCount = 0;
        private Instant lastActivity = Instant.now();
        private long messagesSent = 0;
        private long messagesReceived = 0;

        public ConnectionMetrics(String deviceId) { this.deviceId = deviceId; }

        public void addBytesSent(long bytes) { this.bytesSent += bytes; this.lastActivity = Instant.now(); }
        public void addBytesReceived(long bytes) { this.bytesReceived += bytes; this.lastActivity = Instant.now(); }
        public void incrementMessagesSent() { this.messagesSent++; }
        public void incrementMessagesReceived() { this.messagesReceived++; }
        public void incrementErrors() { this.errorCount++; }
        public void updateActivity() { this.lastActivity = Instant.now(); }

        public String deviceId() { return deviceId; }
        public long bytesSent() { return bytesSent; }
        public long bytesReceived() { return bytesReceived; }
        public int errorCount() { return errorCount; }
        public Instant lastActivity() { return lastActivity; }
        public long messagesSent() { return messagesSent; }
        public long messagesReceived() { return messagesReceived; }
    }

    public record DiagnosticEvent(
            Type type,
            String message,
            String source,
            Instant timestamp
    ) {
        public enum Type { INFO, WARNING, ERROR, DEBUG }

        public DiagnosticEvent(Type type, String message, String source) {
            this(type, message, source, Instant.now());
        }
    }
}