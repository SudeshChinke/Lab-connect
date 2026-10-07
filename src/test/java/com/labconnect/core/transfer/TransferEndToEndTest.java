package com.labconnect.core.transfer;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.core.transfer.Transfer;
import com.labconnect.core.transfer.TransferState;
import com.labconnect.desktop.services.DesktopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end file transfer test over live sockets.
 * 
 * This test verifies that a file sent from one DesktopService instance
 * actually arrives at the peer's TransferManager with correct content,
 * and the SHA-256 hash matches.
 */
class TransferEndToEndTest {

    @TempDir
    Path identityDir;

    private DesktopService alice;
    private DesktopService bob;

    private static AppConfig config(String name) {
        AppConfig config = new AppConfig();
        config.getNetwork().setTcpPort(0);
        config.getNetwork().setHeartbeatIntervalSec(30);
        config.getDevice().setName(name);
        config.getDevice().setType(AppConfig.DeviceType.DESKTOP);
        config.getTransfer().setChunkSize(4096); // Small chunks for test
        config.getTransfer().setMaxConcurrentTransfers(1);
        return config;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (alice != null) alice.shutdown();
        if (bob != null) bob.shutdown();
        Thread.sleep(500); // Let connections close cleanly
    }

    private void aliceDiscoversBob() {
        alice.getDiscoveryManager().registerDevice(DeviceInfo.createLocal(
            bob.getLocalDeviceId(), "Bob", DeviceInfo.DeviceType.DESKTOP,
            "127.0.0.1", bob.getConnectionManager().getLocalPort(), bob.getLocalDeviceInfo().publicKey()
        ));
    }

    @Test
    @Timeout(30)
    void testFileTransferEndToEnd() throws Exception {
        alice = new DesktopService(config("Alice"), identityDir.resolve("alice.dat"));
        bob = new DesktopService(config("Bob"), identityDir.resolve("bob.dat"));

        // Create a test file with unique name to avoid conflicts with previous runs
        String uniqueName = "test-file-" + UUID.randomUUID() + ".txt";
        Path testFile = identityDir.resolve(uniqueName);
        String content = "Hello from Alice! This is a test file for transfer.\n".repeat(100);
        Files.writeString(testFile, content);

        // Seed Alice's registry with Bob
        aliceDiscoversBob();

        // Latch for transfer completion on Bob's side
        CountDownLatch receivedLatch = new CountDownLatch(1);
        AtomicReference<Transfer> receivedTransfer = new AtomicReference<>();

        bob.setOnTransferCompleted(t -> {
            receivedTransfer.set(t);
            receivedLatch.countDown();
        });
        bob.setOnTransferFailed(t -> receivedLatch.countDown());

        // Connect Alice to Bob
        boolean connected = alice.connectToDevice(bob.getLocalDeviceId());
        assertThat(connected).isTrue();

        // Wait for connection to establish (HELLO exchange)
        Thread.sleep(1500);

        // Send file from Alice to Bob
        alice.getTransferManager().sendFile(bob.getLocalDeviceId(), testFile);

        // Wait for transfer to complete
        assertThat(receivedLatch.await(25, TimeUnit.SECONDS))
            .as("File transfer should complete within 25 seconds").isTrue();

        Transfer t = receivedTransfer.get();
        assertThat(t).isNotNull();
        assertThat(t.getState()).isEqualTo(TransferState.COMPLETED);
        assertThat(t.verifyHash()).isTrue();

        // Verify file content on receiver side
        Path receivedFile = bob.getTransferManager().getDownloadDir().resolve(uniqueName);
        assertThat(Files.exists(receivedFile)).isTrue();
        String receivedContent = Files.readString(receivedFile);
        assertThat(receivedContent).isEqualTo(content);
    }

    @Test
    @Timeout(30)
    void testMultipleFilesTransfer() throws Exception {
        alice = new DesktopService(config("Alice"), identityDir.resolve("alice.dat"));
        bob = new DesktopService(config("Bob"), identityDir.resolve("bob.dat"));

        // Create multiple test files with unique names
        String unique1 = "file1-" + UUID.randomUUID() + ".txt";
        String unique2 = "file2-" + UUID.randomUUID() + ".txt";
        Path file1 = identityDir.resolve(unique1);
        Path file2 = identityDir.resolve(unique2);
        Files.writeString(file1, "File 1 content\n".repeat(50));
        Files.writeString(file2, "File 2 content\n".repeat(50));

        // Seed Alice's registry with Bob
        aliceDiscoversBob();

        CountDownLatch receivedLatch = new CountDownLatch(2);
        AtomicReference<Transfer> t1 = new AtomicReference<>();
        AtomicReference<Transfer> t2 = new AtomicReference<>();

        bob.setOnTransferCompleted(transfer -> {
            if (t1.get() == null) t1.set(transfer);
            else t2.set(transfer);
            receivedLatch.countDown();
        });
        bob.setOnTransferFailed(t -> receivedLatch.countDown());

        boolean connected = alice.connectToDevice(bob.getLocalDeviceId());
        assertThat(connected).isTrue();
        Thread.sleep(1500);

        // Send both files
        alice.getTransferManager().sendFile(bob.getLocalDeviceId(), file1);
        alice.getTransferManager().sendFile(bob.getLocalDeviceId(), file2);

        assertThat(receivedLatch.await(30, TimeUnit.SECONDS))
            .as("Both file transfers should complete").isTrue();

        assertThat(t1.get()).isNotNull();
        assertThat(t2.get()).isNotNull();
        assertThat(t1.get().verifyHash()).isTrue();
        assertThat(t2.get().verifyHash()).isTrue();
    }
}