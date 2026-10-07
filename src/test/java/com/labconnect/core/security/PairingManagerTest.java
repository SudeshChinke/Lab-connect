package com.labconnect.core.security;

import com.labconnect.core.protocol.FrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PairingManagerTest {
    @TempDir Path directory;

    @Test
    void pairingFramesExchangeAndPersistTrustOnBothPeers() throws Exception {
        var aliceIdentity = KeyPairGenerator.generate();
        var bobIdentity = KeyPairGenerator.generate();
        String aliceId = KeyPairGenerator.deriveDeviceId(aliceIdentity.publicKeyBase64());
        String bobId = KeyPairGenerator.deriveDeviceId(bobIdentity.publicKeyBase64());

        TrustStore aliceTrust = new TrustStore(directory.resolve("alice-trust.properties"));
        TrustStore bobTrust = new TrustStore(directory.resolve("bob-trust.properties"));
        AtomicReference<PairingManager> aliceRef = new AtomicReference<>();
        AtomicReference<PairingManager> bobRef = new AtomicReference<>();
        PairingManager alice = new PairingManager(aliceTrust, aliceId, "Alice", aliceIdentity.publicKeyBase64(),
                (to, frame) -> bobRef.get().handleFrame(aliceId, frame));
        PairingManager bob = new PairingManager(bobTrust, bobId, "Bob", bobIdentity.publicKeyBase64(),
                (to, frame) -> aliceRef.get().handleFrame(bobId, frame));
        aliceRef.set(alice);
        bobRef.set(bob);
        bob.setOnIncomingPairingRequest(request -> bob.acceptPairing(request.sessionId()));

        AtomicReference<PairingManager.PairingResult> result = new AtomicReference<>();
        alice.initiatePairing(bobId, "Bob", bobIdentity.publicKeyBase64(), result::set);

        assertThat(result.get()).isNotNull();
        assertThat(result.get().success()).isTrue();
        assertThat(aliceTrust.isTrusted(bobId)).isTrue();
        assertThat(bobTrust.isTrusted(aliceId)).isTrue();
        assertThat(new TrustStore(directory.resolve("alice-trust.properties")).isTrusted(bobId)).isTrue();

        alice.shutdown();
        bob.shutdown();
    }

    @Test
    void refusesAdvertisedKeyThatDoesNotMatchDeviceId() {
        var localIdentity = KeyPairGenerator.generate();
        var remoteIdentity = KeyPairGenerator.generate();
        TrustStore store = new TrustStore(directory.resolve("trust.properties"));
        PairingManager manager = new PairingManager(store,
                KeyPairGenerator.deriveDeviceId(localIdentity.publicKeyBase64()), "Local",
                localIdentity.publicKeyBase64(), (id, frame) -> {});
        AtomicReference<PairingManager.PairingResult> result = new AtomicReference<>();

        manager.initiatePairing("DEVICE-wrong", "Remote", remoteIdentity.publicKeyBase64(), result::set);

        assertThat(result.get()).isNotNull();
        assertThat(result.get().success()).isFalse();
        assertThat(store.getTrustedPeers()).isEmpty();
        manager.shutdown();
    }
}
