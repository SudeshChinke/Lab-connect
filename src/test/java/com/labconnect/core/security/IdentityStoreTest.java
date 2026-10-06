package com.labconnect.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityStoreTest {

    @TempDir
    Path dir;

    private Path identityFile() {
        return dir.resolve("keystore.dat");
    }

    @Test
    @Timeout(5)
    void generatesAndPersistsAnEd25519IdentityOnFirstRun() throws Exception {
        Path file = identityFile();

        KeyPairGenerator.KeyPair identity = IdentityStore.loadOrCreate(file);

        assertThat(Files.exists(file)).isTrue();
        // Ed25519 keys are 32 bytes each, Base64-encoded.
        assertThat(org.bouncycastle.util.encoders.Base64.decode(identity.privateKeyBase64())).hasSize(32);
        assertThat(org.bouncycastle.util.encoders.Base64.decode(identity.publicKeyBase64())).hasSize(32);

        // The persisted form must round-trip to the same identity.
        KeyPairGenerator.KeyPair reloaded = IdentityStore.loadOrCreate(file);
        assertThat(reloaded).isEqualTo(identity);
    }

    @Test
    @Timeout(5)
    void identityIsStableAcrossRestart() {
        Path file = identityFile();

        KeyPairGenerator.KeyPair first = IdentityStore.loadOrCreate(file);
        // Simulate a restart: a fresh load from the same file.
        KeyPairGenerator.KeyPair second = IdentityStore.loadOrCreate(file);

        assertThat(second.publicKeyBase64()).isEqualTo(first.publicKeyBase64());
        assertThat(KeyPairGenerator.deriveDeviceId(second.publicKeyBase64()))
                .isEqualTo(KeyPairGenerator.deriveDeviceId(first.publicKeyBase64()));
    }

    @Test
    @Timeout(5)
    void corruptIdentityFileIsRegeneratedRatherThanFatal() throws Exception {
        Path file = identityFile();
        Files.writeString(file, "not a key pair\n");

        KeyPairGenerator.KeyPair identity = IdentityStore.loadOrCreate(file);

        assertThat(org.bouncycastle.util.encoders.Base64.decode(identity.publicKeyBase64())).hasSize(32);
        // And the replacement is itself persisted for the next run.
        assertThat(IdentityStore.loadOrCreate(file)).isEqualTo(identity);
    }

    @Test
    @Timeout(5)
    void deriveDeviceIdIsDeterministicAndKeyDependent() {
        String keyA = KeyPairGenerator.generate().publicKeyBase64();
        String keyB = KeyPairGenerator.generate().publicKeyBase64();

        String idA = KeyPairGenerator.deriveDeviceId(keyA);

        assertThat(idA).startsWith("DEVICE-");
        assertThat(idA).hasSize("DEVICE-".length() + 16);          // 8 bytes as hex
        assertThat(KeyPairGenerator.deriveDeviceId(keyA)).isEqualTo(idA);   // deterministic
        assertThat(KeyPairGenerator.deriveDeviceId(keyB)).isNotEqualTo(idA); // key-dependent
    }
}
