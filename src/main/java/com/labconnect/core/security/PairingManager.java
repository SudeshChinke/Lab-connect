package com.labconnect.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;

public final class PairingManager {
    private static final Logger log = LoggerFactory.getLogger(PairingManager.class);

    private final TrustStore trustStore;
    private final String localDeviceId;
    private final String localDeviceName;
    private final String localPublicKey;
    private final BiConsumer<String, FrameCodec.Frame> sendFrame;
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pairing-timeouts"); t.setDaemon(true); return t;
    });
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Consumer<PairingResult>> callbacks = new ConcurrentHashMap<>();
    
    private final Map<String, PairingSession> pendingSessions = new ConcurrentHashMap<>();
    
    private volatile Consumer<PairingRequest> onIncomingPairingRequest;
    private volatile Consumer<PairingResult> onPairingResult;

    public PairingManager(TrustStore trustStore, String localDeviceId, String localDeviceName) {
        this(trustStore, localDeviceId, localDeviceName, "", (deviceId, frame) -> {});
    }

    public PairingManager(TrustStore trustStore, String localDeviceId, String localDeviceName,
                          String localPublicKey, BiConsumer<String, FrameCodec.Frame> sendFrame) {
        this.trustStore = trustStore;
        this.localDeviceId = localDeviceId;
        this.localDeviceName = localDeviceName;
        this.localPublicKey = localPublicKey;
        this.sendFrame = sendFrame;
    }

    public void setOnIncomingPairingRequest(Consumer<PairingRequest> handler) {
        this.onIncomingPairingRequest = handler;
    }

    public void setOnPairingResult(Consumer<PairingResult> handler) {
        this.onPairingResult = handler;
    }

    public void initiatePairing(String targetDeviceId, String targetDeviceName, String targetPublicKey, Consumer<PairingResult> callback) {
        if (targetPublicKey == null || targetPublicKey.isBlank()
                || !targetDeviceId.equals(KeyPairGenerator.deriveDeviceId(targetPublicKey))) {
            if (callback != null) callback.accept(new PairingResult("", targetDeviceId, false,
                    "Peer identity does not match its advertised public key"));
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        PairingSession session = new PairingSession(
                sessionId, targetDeviceId, targetDeviceName, targetPublicKey, 
                PairingSession.State.PENDING, System.currentTimeMillis()
        );
        pendingSessions.put(sessionId, session);
        if (callback != null) callbacks.put(sessionId, callback);

        log.info("Initiated pairing with {} ({})", targetDeviceName, targetDeviceId);

        // Set timeout
        send(targetDeviceId, MessageType.PAIR_REQUEST, Map.of(
                "sessionId", sessionId, "deviceId", localDeviceId,
                "deviceName", localDeviceName, "publicKey", localPublicKey));
        timeoutExecutor.schedule(() -> {
            PairingSession s = pendingSessions.remove(sessionId);
            if (s != null && s.state() == PairingSession.State.PENDING) {
                log.warn("Pairing timeout for {}", targetDeviceId);
                complete(sessionId, new PairingResult(sessionId, targetDeviceId, false, "Timeout"));
            }
        }, 30, TimeUnit.SECONDS);
    }

    /** Routes a pairing protocol frame from an already identified TCP peer. */
    public void handleFrame(String senderDeviceId, FrameCodec.Frame frame) {
        try {
            JsonNode body = mapper.readTree(frame.payload());
            String sessionId = requiredText(body, "sessionId");
            MessageType type = MessageType.fromValue(frame.type());
            switch (type) {
                case PAIR_REQUEST -> {
                    String claimedId = requiredText(body, "deviceId");
                    if (!senderDeviceId.equals(claimedId)) throw new IllegalArgumentException("Pairing identity mismatch");
                    String publicKey = requiredText(body, "publicKey");
                    if (!senderDeviceId.equals(KeyPairGenerator.deriveDeviceId(publicKey)))
                        throw new IllegalArgumentException("Public key does not match peer identity");
                    handleIncomingPairingRequest(sessionId, senderDeviceId,
                            requiredText(body, "deviceName"), publicKey);
                }
                case PAIR_ACCEPT -> handleIncomingAccept(sessionId, senderDeviceId);
                case PAIR_REJECT -> handleIncomingReject(sessionId, senderDeviceId);
                default -> log.warn("Unexpected pairing frame {}", type);
            }
        } catch (Exception e) {
            log.warn("Ignoring malformed pairing frame: {}", e.getMessage());
        }
    }

    public void handleIncomingPairingRequest(String sessionId, String requesterDeviceId, 
                                             String requesterDeviceName, String requesterPublicKey) {
        if (trustStore.isTrusted(requesterDeviceId)) {
            // The peer's identity is already established; acknowledge without
            // replacing the stored key from an unauthenticated request.
            send(requesterDeviceId, MessageType.PAIR_ACCEPT, Map.of("sessionId", sessionId));
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
            send(session.targetDeviceId(), MessageType.PAIR_ACCEPT, Map.of("sessionId", sessionId));
            
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
        send(session.targetDeviceId(), MessageType.PAIR_REJECT, Map.of("sessionId", sessionId));
        
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
            complete(sessionId, new PairingResult(sessionId, session.targetDeviceId(), true, "Accepted by peer"));
            
            if (onPairingResult != null) {
                onPairingResult.accept(new PairingResult(
                        sessionId, session.targetDeviceId(), true, "Accepted by peer"
                ));
            }
        } catch (Exception e) {
            log.error("Failed to process accepted pairing", e);
        }
    }

    private void handleIncomingAccept(String sessionId, String senderDeviceId) {
        PairingSession session = pendingSessions.get(sessionId);
        if (session == null || !session.targetDeviceId().equals(senderDeviceId)) {
            log.warn("Ignoring pairing acceptance from unexpected peer {}", senderDeviceId);
            return;
        }
        handleIncomingAccept(sessionId);
    }

    public void handleIncomingReject(String sessionId) {
        PairingSession session = pendingSessions.remove(sessionId);
        if (session == null) return;

        log.info("Peer rejected our pairing request: {}", session.targetDeviceId());
        callbackError(sessionId, session.targetDeviceId(), "Rejected by peer");
    }

    private void handleIncomingReject(String sessionId, String senderDeviceId) {
        PairingSession session = pendingSessions.get(sessionId);
        if (session == null || !session.targetDeviceId().equals(senderDeviceId)) {
            log.warn("Ignoring pairing rejection from unexpected peer {}", senderDeviceId);
            return;
        }
        handleIncomingReject(sessionId);
    }

    private void callbackError(String sessionId, String deviceId, String error) {
        complete(sessionId, new PairingResult(sessionId, deviceId, false, error));
    }

    private void complete(String sessionId, PairingResult result) {
        Consumer<PairingResult> callback = callbacks.remove(sessionId);
        if (callback != null) callback.accept(result);
        if (onPairingResult != null) onPairingResult.accept(result);
    }

    private void send(String deviceId, MessageType type, Object payload) {
        try {
            sendFrame.accept(deviceId, FrameCodec.Frame.create(type.value(), (byte) 0, UUID.randomUUID(),
                    mapper.writeValueAsBytes(payload)));
        } catch (Exception e) {
            log.warn("Could not send pairing frame to {}: {}", deviceId, e.getMessage());
        }
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("Missing " + name);
        return value.asText();
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
