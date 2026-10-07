package com.labconnect.core.security;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Properties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TrustStore implements Serializable {
    private final Map<String, TrustedPeer> trustedPeers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path path;

    public TrustStore() { this(Path.of("truststore.properties")); }

    public TrustStore(Path path) {
        this.path = path;
        load();
    }

    public static class TrustedPeer implements Serializable {
        private final String deviceId;
        private final String deviceName;
        private final byte[] publicKeyBytes;
        private final String publicKey;
        private final long pairedAt;

        public TrustedPeer(String deviceId, String deviceName, byte[] publicKeyBytes, String publicKey, long pairedAt) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.publicKeyBytes = publicKeyBytes.clone();
            this.publicKey = publicKey;
            this.pairedAt = pairedAt;
        }

        public String deviceId() { return deviceId; }
        public String deviceName() { return deviceName; }
        public byte[] publicKeyBytes() { return publicKeyBytes.clone(); }
        public String publicKey() { return publicKey; }
        public long pairedAt() { return pairedAt; }
    }

    public void addTrustedPeer(String deviceId, String deviceName, byte[] certificate, String publicKey) {
        trustedPeers.put(deviceId, new TrustedPeer(deviceId, deviceName, certificate, publicKey, System.currentTimeMillis()));
        persist();
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

    private void load() {
        if (!Files.isRegularFile(path)) return;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
            for (String property : properties.stringPropertyNames()) {
                if (!property.startsWith("peer.")) continue;
                int separator = property.indexOf('.', "peer.".length());
                if (separator < 0) continue;
                String id = property.substring("peer.".length(), separator);
                String prefix = "peer." + id + ".";
                String name = properties.getProperty(prefix + "name", id);
                String key = properties.getProperty(prefix + "publicKey", "");
                byte[] encoded = Base64.getDecoder().decode(properties.getProperty(prefix + "keyBytes", ""));
                long pairedAt = Long.parseLong(properties.getProperty(prefix + "pairedAt", "0"));
                trustedPeers.put(id, new TrustedPeer(id, name, encoded, key, pairedAt));
            }
        } catch (IOException | RuntimeException e) {
            throw new UncheckedIOException(new IOException("Could not load trust store " + path, e));
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        trustedPeers.forEach((id, peer) -> {
            String prefix = "peer." + id + ".";
            properties.setProperty(prefix + "name", peer.deviceName());
            properties.setProperty(prefix + "publicKey", peer.publicKey());
            properties.setProperty(prefix + "keyBytes", Base64.getEncoder().encodeToString(peer.publicKeyBytes()));
            properties.setProperty(prefix + "pairedAt", Long.toString(peer.pairedAt()));
        });
        Path parent = path.toAbsolutePath().getParent();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (var output = Files.newOutputStream(temp)) {
                properties.store(output, "LabConnect trusted peers");
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save trust store " + path, e);
        }
    }
}
