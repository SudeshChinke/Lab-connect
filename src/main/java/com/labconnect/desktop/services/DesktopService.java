package com.labconnect.desktop.services;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.diagnostics.DiagnosticsManager;
import com.labconnect.core.discovery.DiscoveryManager;
import com.labconnect.core.messaging.ChatManager;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.security.PairingManager;
import com.labconnect.core.security.TrustStore;
import com.labconnect.core.transfer.TransferManager;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DesktopService {
    private final AppConfig config;
    private final ConnectionManager connectionManager;
    private final DiscoveryManager discoveryManager;
    private final ChatManager chatManager;
    private final TransferManager transferManager;
    private final PairingManager pairingManager;
    private final DiagnosticsManager diagnosticsManager;
    private final String localDeviceId;

    public DesktopService(AppConfig config) throws IOException {
        this.config = config;
        this.localDeviceId = "DEVICE-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // Initialize core managers
        this.connectionManager = new ConnectionManager(
            config.getNetwork().getTcpPort(),
            config.getNetwork().getHeartbeatInterval(),
            conn -> {}, // onConnectionEstablished
            conn -> {}, // onConnectionClosed
            frame -> {} // onFrameReceived
        );

        // Convert AppConfig.DeviceType to DeviceInfo.DeviceType
        com.labconnect.core.models.DeviceInfo.DeviceType deviceType = 
            config.getDevice().getType() == AppConfig.DeviceType.DESKTOP 
                ? com.labconnect.core.models.DeviceInfo.DeviceType.DESKTOP 
                : com.labconnect.core.models.DeviceInfo.DeviceType.MOBILE;

        DeviceInfo localDevice = DeviceInfo.createLocal(
            config.getDevice().getName(),
            localDeviceId,
            deviceType,
            "127.0.0.1",
            config.getNetwork().getTcpPort(),
            "local-public-key"
        );

        this.discoveryManager = new DiscoveryManager(config, localDevice);
        this.chatManager = new ChatManager(
            connectionManager,
            localDeviceId,
            msg -> {}, // onMessageReceived
            msg -> {}, // onMessageSent
            msg -> {}, // onMessageDelivered
            msg -> {}  // onMessageRead
        );
        this.transferManager = new TransferManager(
            connectionManager,
            localDeviceId,
            config,
            java.nio.file.Paths.get("downloads"),
            tr -> {}, // onTransferProgress
            tr -> {},  // onTransferCompleted
            tr -> {}   // onTransferFailed
        );
        
        TrustStore trustStore = new TrustStore();
        this.pairingManager = new PairingManager(
            trustStore,
            localDeviceId,
            config.getDevice().getName()
        );
        
        this.diagnosticsManager = new DiagnosticsManager(
            connectionManager,
            discoveryManager,
            localDeviceId
        );
        
        // Start managers
        connectionManager.start();
        discoveryManager.start();
    }

    public ConnectionManager getConnectionManager() { return connectionManager; }
    public DiscoveryManager getDiscoveryManager() { return discoveryManager; }
    public ChatManager getChatManager() { return chatManager; }
    public TransferManager getTransferManager() { return transferManager; }
    public PairingManager getPairingManager() { return pairingManager; }
    public DiagnosticsManager getDiagnosticsManager() { return diagnosticsManager; }
    public String getLocalDeviceId() { return localDeviceId; }

    public void discoverDevices() {
        discoveryManager.getAllDevices(); // Triggers discovery
    }

    /**
     * Releases the network resources held by the core managers. Safe to call
     * more than once, and never throws, so it can be wired straight into the
     * JavaFX lifecycle stop hook.
     */
    public void shutdown() {
        closeQuietly("discovery", discoveryManager::close);
        closeQuietly("transfer", transferManager::close);
        closeQuietly("connection", connectionManager::close);
    }

    private void closeQuietly(String name, AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            System.err.println("Error closing " + name + " manager: " + e.getMessage());
        }
    }

    public void connectToDevice(String deviceId) {
        // TODO: Implement connection logic
    }
}