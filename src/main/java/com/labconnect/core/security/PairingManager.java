package com.labconnect.core.security;

import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class PairingManager {
    private static final Logger log = LoggerFactory.getLogger(PairingManager.class);

    private final TrustStore trustStore;
    private final String localDeviceId;
    private final String localDeviceName;
    
    private final Map<String, PairingSession> pendingSessions = new ConcurrentHashMap<>();
    private final ExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    
    private Consumer<PairingRequest> onIncomingPairingRequest;
    private Consumer<PairingResult> onPairingResult;

    public PairingManager(TrustStore trustStore, String localDeviceId, String localDeviceName) {
        this.trustStore = trustStore;
        this.localDeviceId = localDeviceId;
        this.localDeviceName = localDeviceName;
    }

    public void setOnIncomingPairingRequest(Consumer<PairingRequest> handler) {
        this.onIncomingPairingRequest = handler;
    }

    public void setOnPairingResult(Consumer<PairingResult> handler) {
        this.onPairingResult = handler;
    }

    public void initiatePairing(String targetDeviceId, String targetDeviceName, String targetPublicKey, Consumer<PairingResult> callback) {
        String sessionId = UUID.randomUUID().toString();
        PairingSession session = new PairingSession(
                sessionId, targetDeviceId, targetDeviceName, targetPublicKey, 
                PairingSession.State.PENDING, System.currentTimeMillis()
        );
        pendingSessions.put(sessionId, session);

        log.info("Initiated pairing with {} ({})", targetDeviceName, targetDeviceId);

        // Set timeout
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            PairingSession s = pendingSessions.remove(sessionId);
            if (s != null && s.state() == PairingSession.State.PENDING) {
                log.warn("Pairing timeout for {}", targetDeviceId);
                callback.accept(new PairingResult(sessionId, targetDeviceId, false, "Timeout"));
            }
        }, 30, TimeUnit.SECONDS);
    }

    public void handleIncomingPairingRequest(String sessionId, String requesterDeviceId, 
                                             String requesterDeviceName, String requesterPublicKey) {
        if (trustStore.isTrusted(requesterDeviceId)) {
            // Already trusted, auto-accept
            acceptPairing(sessionId);
            return;
        }

        PairingSession session = new PairingSession(
                sessionId, requesterDeviceId, requesterDeviceName, requesterPublicKey,
                PairingSession.State.WAITING_USER, System.currentTimeMillis()
        );
        pendingSessions.put(sessionId, session);

        if (onIncomingPairingRequest != null) {
            PairingRequest request = new PairingRequest(
                    sessionId, requesterDeviceId, requesterDeviceName, requesterPublicKey
            );
            onIncomingPairingRequest.accept(request);
        } else {
            // Auto-reject if no handler
            rejectPairing(sessionId);
        }
    }

    public void acceptPairing(String sessionId) {
        PairingSession session = pendingSessions.remove(sessionId);
        if (session == null) return;

        try {
            // Add to trust store
            trustStore.addTrustedPeer(
                    session.targetDeviceId(), 
                    session.targetDeviceName(), 
                    org.bouncycastle.util.encoders.Base64.decode(session.targetPublicKey()),
                    session.targetPublicKey()
            );

            log.info("Accepted pairing with {} ({})", session.targetDeviceName(), session.targetDeviceId());
            
            if (onPairingResult != null) {
                onPairingResult.accept(new PairingResult(
                        sessionId, session.targetDeviceId(), true, "Accepted"
                ));
            }
        } catch (Exception e) {
            log.error("Failed to accept pairing", e);
            callbackError(sessionId, session.targetDeviceId(), e.getMessage());
        }
    }

    public void rejectPairing(String sessionId) {
        PairingSession session = pendingSessions.remove(sessionId);
        if (session == null) return;

        log.info("Rejected pairing with {} ({})", session.targetDeviceName(), session.targetDeviceId());
        
        callbackError(sessionId, session.targetDeviceId(), "Rejected by user");
    }

    public void handlePairingResponse(String sessionId, boolean accepted) {
        PairingSession session = pendingSessions.get(sessionId);
        if (session == null) return;

        if (accepted) {
            acceptPairing(sessionId);
        } else {
            rejectPairing(sessionId);
        }
    }

    public void handleIncomingAccept(String sessionId) {
        PairingSession session = pendingSessions.remove(sessionId);
        if (session == null) return;

        try {
            trustStore.addTrustedPeer(
                    session.targetDeviceId(),
                    session.targetDeviceName(),
                    org.bouncycastle.util.encoders.Base64.decode(session.targetPublicKey()),
                    session.targetPublicKey()
            );

            log.info("Peer accepted our pairing request: {}", session.targetDeviceId());
            
            if (onPairingResult != null) {
                onPairingResult.accept(new PairingResult(
                        sessionId, session.targetDeviceId(), true, "Accepted by peer"
                ));
            }
        } catch (Exception e) {
            log.error("Failed to process accepted pairing", e);
        }
    }

    public void handleIncomingReject(String sessionId) {
        PairingSession session = pendingSessions.remove(sessionId);
        if (session == null) return;

        log.info("Peer rejected our pairing request: {}", session.targetDeviceId());
        callbackError(sessionId, session.targetDeviceId(), "Rejected by peer");
    }

    private void callbackError(String sessionId, String deviceId, String error) {
        if (onPairingResult != null) {
            onPairingResult.accept(new PairingResult(sessionId, deviceId, false, error));
        }
    }

    public void shutdown() {
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record PairingRequest(
            String sessionId,
            String requesterDeviceId,
            String requesterDeviceName,
            String requesterPublicKey
    ) {}

    public record PairingResult(
            String sessionId,
            String deviceId,
            boolean success,
            String message
    ) {}

    public static class PairingSession implements Serializable {
        public enum State { PENDING, WAITING_USER, ACCEPTED, REJECTED, TIMEOUT }

        private final String sessionId;
        private final String targetDeviceId;
        private final String targetDeviceName;
        private final String targetPublicKey;
        private final State state;
        private final long createdAt;

        public PairingSession(String sessionId, String targetDeviceId, String targetDeviceName,
                              String targetPublicKey, State state, long createdAt) {
            this.sessionId = sessionId;
            this.targetDeviceId = targetDeviceId;
            this.targetDeviceName = targetDeviceName;
            this.targetPublicKey = targetPublicKey;
            this.state = state;
            this.createdAt = createdAt;
        }

        public String sessionId() { return sessionId; }
        public String targetDeviceId() { return targetDeviceId; }
        public String targetDeviceName() { return targetDeviceName; }
        public String targetPublicKey() { return targetPublicKey; }
        public State state() { return state; }
        public long createdAt() { return createdAt; }
    }
}