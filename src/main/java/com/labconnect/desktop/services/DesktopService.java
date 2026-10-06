package com.labconnect.desktop.services;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.diagnostics.DiagnosticsManager;
import com.labconnect.core.discovery.DiscoveryManager;
import com.labconnect.core.messaging.ChatManager;
import com.labconnect.core.messaging.TextMessage;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.core.networking.Connection;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import com.labconnect.core.security.IdentityStore;
import com.labconnect.core.security.KeyPairGenerator;
import com.labconnect.core.security.PairingManager;
import com.labconnect.core.security.TrustStore;
import com.labconnect.core.transfer.TransferManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Application service wiring the core managers together: owns the local
 * device identity, routes inbound frames to the manager that handles them,
 * and exposes the connect / chat operations the UI calls.
 */
public class DesktopService {
    private static final Logger log = LoggerFactory.getLogger(DesktopService.class);

    private final AppConfig config;
    private final ConnectionManager connectionManager;
    private final DiscoveryManager discoveryManager;
    private final ChatManager chatManager;
    private final TransferManager transferManager;
    private final PairingManager pairingManager;
    private final DiagnosticsManager diagnosticsManager;
    private final String localDeviceId;
    private final DeviceInfo localDeviceInfo;
    private final KeyPairGenerator.KeyPair identity;

    private volatile Consumer<TextMessage> onMessageReceived = m -> {};
    private volatile Consumer<TextMessage> onMessageDelivered = m -> {};
    private volatile Runnable onConnectionChange = () -> {};
    private volatile Consumer<DeviceInfo> onDeviceListChanged = d -> {};

    /** Uses the default identity file ({@code ./keystore.dat}) in the working directory. */
    public DesktopService(AppConfig config) throws IOException {
        this(config, IdentityStore.defaultPath());
    }

    /**
     * @param identityFile where this device's Ed25519 identity is loaded from
     *                     (or generated to on first run). Distinct instances
     *                     must use distinct files.
     */
    public DesktopService(AppConfig config, Path identityFile) throws IOException {
        this.config = config;

        // Real device identity (B1): derive both the deviceId and the
        // advertised public key from one persisted Ed25519 key pair, so this
        // device is uniquely identifiable to peers and stays so across
        // restarts. A random UUID per launch would break future TOFU pairing.
        this.identity = IdentityStore.loadOrCreate(identityFile);
        this.localDeviceId = KeyPairGenerator.deriveDeviceId(identity.publicKeyBase64());

        // Initialize core managers. The callbacks below are the only path by
        // which inbound frames reach the application, so routing must be
        // registered here rather than as no-ops.
        this.connectionManager = new ConnectionManager(
            config.getNetwork().getTcpPort(),
            config.getNetwork().getHeartbeatInterval(),
            this::handleConnectionEstablished,
            this::handleConnectionClosed,
            this::routeFrame
        );

        // Convert AppConfig.DeviceType to DeviceInfo.DeviceType
        com.labconnect.core.models.DeviceInfo.DeviceType deviceType = 
            config.getDevice().getType() == AppConfig.DeviceType.DESKTOP 
                ? com.labconnect.core.models.DeviceInfo.DeviceType.DESKTOP 
                : com.labconnect.core.models.DeviceInfo.DeviceType.MOBILE;

        // deviceId and deviceName must stay distinct: the deviceId announced
        // over discovery and the one sent in HELLO have to match, or peers
        // cannot map a connection back to the discovered device.
        this.localDeviceInfo = DeviceInfo.createLocal(
            localDeviceId,
            config.getDevice().getName(),
            deviceType,
            "127.0.0.1",
            config.getNetwork().getTcpPort(),
            identity.publicKeyBase64()
        );

        this.discoveryManager = new DiscoveryManager(config, localDeviceInfo);
        // B2: push registry changes (added/updated/expired) out to listeners
        // so the device list updates live instead of only on manual refresh.
        this.discoveryManager.addCallback("desktop-service", device -> onDeviceListChanged.accept(device));
        this.chatManager = new ChatManager(
            connectionManager,
            localDeviceId,
            msg -> onMessageReceived.accept(msg),
            msg -> {}, // onMessageSent - the sender already knows about its own message
            msg -> onMessageDelivered.accept(msg),
            msg -> {}
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
        
        // Start managers. All assignments above happen before start(), so the
        // callbacks can safely reference the sibling managers.
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
    public DeviceInfo getLocalDeviceInfo() { return localDeviceInfo; }
    /** This device's persisted Ed25519 identity (needed by TLS when it lands). */
    public KeyPairGenerator.KeyPair getIdentity() { return identity; }

    public void discoverDevices() {
        discoveryManager.getAllDevices(); // Triggers discovery
    }

    /** Registers a handler for messages arriving from peers. */
    public void setOnMessageReceived(Consumer<TextMessage> handler) {
        this.onMessageReceived = handler != null ? handler : m -> {};
    }

    /** Registers a handler for delivery receipts of our own messages. */
    public void setOnMessageDelivered(Consumer<TextMessage> handler) {
        this.onMessageDelivered = handler != null ? handler : m -> {};
    }

    /** Registers a handler fired whenever connections appear or disappear. */
    public void setOnConnectionChange(Runnable handler) {
        this.onConnectionChange = handler != null ? handler : () -> {};
    }

    /**
     * Registers a handler fired whenever the discovery registry changes:
     * a device appeared, was updated, or timed out and was removed. Fired
     * from networking threads.
     */
    public void setOnDeviceListChanged(Consumer<DeviceInfo> handler) {
        this.onDeviceListChanged = handler != null ? handler : d -> {};
    }

    /**
     * Opens a TCP connection to a discovered peer. Once the handshake
     * completes, {@link #handleConnectionEstablished} sends HELLO so the peer
     * can map the connection to our deviceId (and we learn theirs).
     *
     * @return true when a connection exists or was initiated; false when the
     *         device is unknown or the connection could not be started.
     */
    public boolean connectToDevice(String deviceId) {
        DeviceInfo device = discoveryManager.getDevice(deviceId).orElse(null);
        if (device == null) {
            log.warn("Cannot connect to {}: device not discovered", deviceId);
            return false;
        }
        if (connectionManager.getConnection(deviceId).map(Connection::isConnected).orElse(false)) {
            log.info("Already connected to {}", deviceId);
            return true;
        }
        if (connectionManager.getConnectionByAddress(device.ipAddress(), device.tcpPort()).isPresent()) {
            // Connection (or connect handshake) to this address already in flight.
            return true;
        }
        try {
            connectionManager.connect(device.ipAddress(), device.tcpPort());
            log.info("Connecting to {} at {}:{}", deviceId, device.ipAddress(), device.tcpPort());
            return true;
        } catch (IOException e) {
            log.error("Failed to connect to {} at {}:{}", deviceId, device.ipAddress(), device.tcpPort(), e);
            diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
                DiagnosticsManager.DiagnosticEvent.Type.ERROR,
                "Connect failed: " + e.getMessage(),
                deviceId
            ));
            return false;
        }
    }

    /**
     * Invoked by ConnectionManager when a TCP connection is established in
     * either direction. Identifies this device to the peer and records the
     * event for diagnostics.
     */
    private void handleConnectionEstablished(Connection connection) {
        connectionManager.sendHello(
            connection,
            localDeviceId,
            config.getDevice().getName(),
            localDeviceInfo.publicKey(),
            localDeviceInfo.capabilities()
        );
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
            DiagnosticsManager.DiagnosticEvent.Type.INFO,
            "Connection established: " + connection.getRemoteAddress(),
            connection.getRemoteAddress()
        ));
        onConnectionChange.run();
    }

    private void handleConnectionClosed(Connection connection) {
        diagnosticsManager.logEvent(new DiagnosticsManager.DiagnosticEvent(
            DiagnosticsManager.DiagnosticEvent.Type.WARNING,
            "Connection closed: " + connection.getRemoteAddress(),
            connection.getRemoteAddress()
        ));
        onConnectionChange.run();
    }

    /**
     * The single inbound frame router: dispatches by MessageType into the
     * manager that owns that part of the protocol. Frames not routed here are
     * dropped silently, so every new wire feature needs a case below.
     */
    private void routeFrame(FrameCodec.Frame frame) {
        MessageType type = MessageType.fromValue(frame.type());
        if (type == null) {
            log.warn("Dropping frame with unknown type 0x{}", Integer.toHexString(frame.type() & 0xFF));
            return;
        }
        switch (type) {
            // HELLO was already consumed by ConnectionManager to bind the
            // connection to the peer's deviceId; nothing left to do here.
            case HELLO -> log.debug("HELLO from peer handled by ConnectionManager");
            case TEXT_MESSAGE, GROUP_MESSAGE, MESSAGE_ACK, MESSAGE_READ ->
                chatManager.handleFrame(frame);
            case FILE_REQUEST, FILE_ACCEPT, FILE_REJECT, FILE_CHUNK, FILE_CHUNK_ACK,
                 FILE_COMPLETE, FILE_VERIFIED, FILE_CANCEL, FILE_RESUME, FILE_PAUSE ->
                transferManager.handleFrame(frame);
            case PAIR_REQUEST, PAIR_ACCEPT, PAIR_REJECT, KEY_ROTATE ->
                log.debug("Pairing frame {} received but pairing over the wire is not implemented yet", type);
            case GOODBYE -> log.debug("GOODBYE received");
            default -> log.debug("No handler registered for frame type {}", type);
        }
    }

    /**
     * Releases the network resources held by the core managers. Safe to call
     * more than once, and never throws, so it can be wired straight into the
     * JavaFX lifecycle stop hook.
     */
    public void shutdown() {
        runQuietly("discovery", discoveryManager::close);
        runQuietly("transfer", transferManager::close);
        runQuietly("connection", connectionManager::close);
        runQuietly("pairing", pairingManager::shutdown);
        runQuietly("diagnostics", diagnosticsManager::close);
    }

    private void runQuietly(String name, Runnable closeAction) {
        try {
            closeAction.run();
        } catch (Exception e) {
            System.err.println("Error closing " + name + " manager: " + e.getMessage());
        }
    }
}
