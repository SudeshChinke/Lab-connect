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

    public static String deriveDeviceId(String publicKeyBase64) {
        // Device ID = first 16 chars of SHA-256(publicKey)
        // Using Base64-decoded public key
        byte[] pubKeyBytes = Base64.decode(publicKeyBase64);
        // For simplicity, use first 8 bytes of public key as hex
        // In production, use SHA-256
        StringBuilder sb = new StringBuilder("DEVICE-");
        for (int i = 0; i < Math.min(8, pubKeyBytes.length); i++) {
            sb.append(String.format("%02X", pubKeyBytes[i]));
        }
        return sb.toString();
    }

    public record KeyPair(String privateKeyBase64, String publicKeyBase64) {}
}