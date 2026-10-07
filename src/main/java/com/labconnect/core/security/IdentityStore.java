package com.labconnect.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.labconnect.core.config.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Loads this device's Ed25519 identity from disk, generating and persisting
 * one on first run (as SECURITY.md specifies: "Ed25519 key pair generated on
 * first run, stored in keystore.dat").
 *
 * <p>Source runs keep this file in the current working directory. Packaged
 * builds store it under the operating system's per-user application data folder.
 *
 * <p>Format: two Base64 lines - private key, then public key. A malformed or
 * unreadable file is replaced with a freshly generated identity (logged),
 * because refusing to start would leave the user with no way to run at all.
 */
public final class IdentityStore {
    private static final Logger log = LoggerFactory.getLogger(IdentityStore.class);

    /** Relative to the working directory, mirroring config.yaml. */
    public static final String DEFAULT_IDENTITY_FILE = "keystore.dat";

    /** Ed25519 keys are always 32 bytes. */
    private static final int KEY_LENGTH_BYTES = 32;

    private IdentityStore() {}

    /** Default identity path, scoped to this source run or installed user. */
    public static Path defaultPath() {
        return AppPaths.identityPath();
    }

    /**
     * Returns the identity stored at {@code path}, creating and persisting a
     * new one if the file does not exist or cannot be used.
     */
    public static KeyPairGenerator.KeyPair loadOrCreate(Path path) {
        KeyPairGenerator.KeyPair existing = read(path);
        if (existing != null) {
            log.info("Loaded identity from {} (device {})",
                    path, KeyPairGenerator.deriveDeviceId(existing.publicKeyBase64()));
            return existing;
        }

        KeyPairGenerator.KeyPair fresh = KeyPairGenerator.generate();
        try {
            write(path, fresh);
            log.info("Generated new identity at {} (device {})",
                    path, KeyPairGenerator.deriveDeviceId(fresh.publicKeyBase64()));
        } catch (IOException e) {
            // Usable for this run; it just will not survive a restart.
            log.warn("Could not persist identity to {}: {}", path, e.getMessage());
        }
        return fresh;
    }

    /** Reads the identity, or returns null when the file is absent/unusable. */
    private static KeyPairGenerator.KeyPair read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
            if (lines.size() < 2) {
                log.warn("Identity file {} is incomplete, regenerating", path);
                return null;
            }
            String privateKey = lines.get(0);
            String publicKey = lines.get(1);
            if (decodedLength(privateKey) != KEY_LENGTH_BYTES
                    || decodedLength(publicKey) != KEY_LENGTH_BYTES) {
                log.warn("Identity file {} does not contain an Ed25519 key pair, regenerating", path);
                return null;
            }
            return new KeyPairGenerator.KeyPair(privateKey, publicKey);
        } catch (Exception e) {
            log.warn("Identity file {} could not be read ({}), regenerating", path, e.getMessage());
            return null;
        }
    }

    private static int decodedLength(String base64) {
        return org.bouncycastle.util.encoders.Base64.decode(base64).length;
    }

    private static void write(Path path, KeyPairGenerator.KeyPair pair) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String content = pair.privateKeyBase64() + "\n" + pair.publicKeyBase64() + "\n";
        Files.writeString(path, content, StandardCharsets.UTF_8);

        // Owner-readable-only where the filesystem supports POSIX permissions:
        // this file holds the private key. (Write-then-tighten: a brief window
        // with default perms is acceptable for a single-user LAN tool.)
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows): no permission attributes.
        } catch (IOException e) {
            log.warn("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }
}
