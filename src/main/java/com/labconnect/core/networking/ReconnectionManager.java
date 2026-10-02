package com.labconnect.core.networking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class ReconnectionManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReconnectionManager.class);

    private final ConnectionManager connectionManager;
    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reconnection-manager");
        t.setDaemon(true);
        return t;
    });
    private final Duration reconnectInterval;
    private final int maxRetries;
    private volatile boolean running = false;

    public ReconnectionManager(ConnectionManager connectionManager,
                               Duration reconnectInterval,
                               int maxRetries) {
        this.connectionManager = connectionManager;
        this.reconnectInterval = reconnectInterval;
        this.maxRetries = maxRetries;
    }

    public void start() {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(this::attemptReconnections, 
                reconnectInterval.toMillis(), reconnectInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Reconnection manager started");
    }

    public void registerPeer(String deviceId, String host, int port) {
        knownPeers.put(deviceId, new PeerInfo(deviceId, host, port, 0));
        log.debug("Registered peer for reconnection: {} at {}:{}", deviceId, host, port);
    }

    public void unregisterPeer(String deviceId) {
        knownPeers.remove(deviceId);
        log.debug("Unregistered peer: {}", deviceId);
    }

    public void markConnected(String deviceId) {
        PeerInfo peer = knownPeers.get(deviceId);
        if (peer != null) {
            peer.retryCount = 0;
            peer.lastAttempt = 0;
        }
    }

    public void markDisconnected(String deviceId) {
        PeerInfo peer = knownPeers.get(deviceId);
        if (peer != null) {
            peer.retryCount++;
            peer.lastAttempt = System.currentTimeMillis();
        }
    }

    private void attemptReconnections() {
        if (!running) return;
        
        knownPeers.forEach((deviceId, peer) -> {
            if (peer.retryCount >= maxRetries) {
                log.warn("Max retries reached for peer {}, giving up", deviceId);
                return;
            }

            // Check if already connected
            if (connectionManager.getConnection(deviceId).isPresent()) {
                peer.retryCount = 0;
                return;
            }

            // Check if enough time has passed since last attempt
            long sinceLastAttempt = System.currentTimeMillis() - peer.lastAttempt;
            if (sinceLastAttempt < reconnectInterval.toMillis()) {
                return;
            }

            log.info("Attempting reconnection to {} (attempt {}/{})", 
                    deviceId, peer.retryCount + 1, maxRetries);
            
            try {
                Connection conn = connectionManager.connect(peer.host, peer.port);
                conn.setRemoteDeviceId(deviceId);
                peer.lastAttempt = System.currentTimeMillis();
            } catch (IOException e) {
                log.debug("Reconnection attempt failed for {}: {}", deviceId, e.getMessage());
                peer.retryCount++;
                peer.lastAttempt = System.currentTimeMillis();
            }
        });
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        knownPeers.clear();
        log.info("Reconnection manager stopped");
    }

    private static class PeerInfo {
        final String deviceId;
        final String host;
        final int port;
        int retryCount;
        long lastAttempt;

        PeerInfo(String deviceId, String host, int port, int retryCount) {
            this.deviceId = deviceId;
            this.host = host;
            this.port = port;
            this.retryCount = retryCount;
        }
    }
}