package com.labconnect.core.security;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

public final class KeyPairGenerator {
    private static final Logger log = LoggerFactory.getLogger(KeyPairGenerator.class);

    public static KeyPair generate() {
        var generator = new Ed25519KeyPairGenerator();
        generator.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();

        Ed25519PrivateKeyParameters priv = (Ed25519PrivateKeyParameters) pair.getPrivate();
        Ed25519PublicKeyParameters pub = (Ed25519PublicKeyParameters) pair.getPublic();

        return new KeyPair(
                Base64.toBase64String(priv.getEncoded()),
                Base64.toBase64String(pub.getEncoded())
        );
    }

    /**
     * Stable device identity derived from the public key: {@code DEVICE-}
     * followed by the first 8 bytes of the SHA-256 fingerprint as hex. The
     * same key always yields the same id, so a persisted identity keeps its
     * deviceId across restarts (which TOFU pairing depends on).
     */
    public static String deriveDeviceId(String publicKeyBase64) {
        byte[] fingerprint;
        try {
            fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Base64.decode(publicKeyBase64));
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; reaching here is impossible.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder sb = new StringBuilder("DEVICE-");
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02X", fingerprint[i]));
        }
        return sb.toString();
    }

    public record KeyPair(String privateKeyBase64, String publicKeyBase64) {}
}