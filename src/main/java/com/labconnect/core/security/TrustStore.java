package com.labconnect.core.security;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TrustStore implements Serializable {
    private final Map<String, TrustedPeer> trustedPeers = new java.util.concurrent.ConcurrentHashMap<>();

    public static class TrustedPeer implements Serializable {
        private final String deviceId;
        private final String deviceName;
        private final byte[] certificate;
        private final String publicKey;
        private final long pairedAt;

        public TrustedPeer(String deviceId, String deviceName, byte[] certificate, String publicKey, long pairedAt) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.certificate = certificate;
            this.publicKey = publicKey;
            this.pairedAt = pairedAt;
        }

        public String deviceId() { return deviceId; }
        public String deviceName() { return deviceName; }
        public byte[] certificate() { return certificate; }
        public String publicKey() { return publicKey; }
        public long pairedAt() { return pairedAt; }
    }

    public void addTrustedPeer(String deviceId, String deviceName, byte[] certificate, String publicKey) {
        trustedPeers.put(deviceId, new TrustedPeer(deviceId, deviceName, certificate, publicKey, System.currentTimeMillis()));
    }

    public boolean isTrusted(String deviceId) {
        return trustedPeers.containsKey(deviceId);
    }

    public Optional<TrustedPeer> getTrustedPeer(String deviceId) {
        return Optional.ofNullable(trustedPeers.get(deviceId));
    }

    public Map<String, TrustedPeer> getTrustedPeers() {
        return new ConcurrentHashMap<>(trustedPeers);
    }
}