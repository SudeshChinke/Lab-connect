package com.labconnect.core.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ResumableTransferTest {

    private Path testFile;
    private Path downloadDir;

    @Test
    @Timeout(10)
    void testSetResumeOffset() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", 1000, "text/plain",
                "hash", 100, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-", ".tmp");
        try {
            Files.write(tempFile, new byte[1000]);
            
            Transfer transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            transfer.start();
            
            // Test resume offset
            transfer.setResumeOffset(500);
            assertThat(transfer.getResumeOffset()).isEqualTo(500);
            
            // Test getBytesRemaining
            assertThat(transfer.getBytesRemaining()).isEqualTo(1000);
            
            // Test checkpoint
            transfer.checkpoint();
            assertThat(transfer.getLastCheckpointOffset()).isEqualTo(0); // Not transferred yet
            
            transfer.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @Timeout(10)
    void testSeekToChunk() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", 1000, "text/plain",
                "hash", 100, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-", ".tmp");
        Transfer transfer = null;
        try {
            Files.write(tempFile, new byte[1000]);
            
            transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            transfer.start();
            
            // Seek to chunk 5 (offset 500)
            transfer.seekToChunk(5);
            assertThat(transfer.getResumeOffset()).isEqualTo(500);
            
        } finally {
            if (transfer != null) transfer.close();
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @Timeout(10)
    void testCheckpointAndResume() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", 1000, "text/plain",
                "hash", 100, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-", ".tmp");
        try {
            Files.write(tempFile, new byte[1000]);
            
            Transfer transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            transfer.start();
            
            // Simulate some progress
            transfer.checkpoint();
            assertThat(transfer.getLastCheckpointOffset()).isEqualTo(0);
            
            // Set resume offset
            transfer.setResumeOffset(500);
            assertThat(transfer.getResumeOffset()).isEqualTo(500);
            assertThat(transfer.getResumeProgress()).isEqualTo(0.5);
            
            transfer.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @Timeout(15)
    void testPartialHashVerification() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", 1000, "text/plain",
                "incorrect_hash", 100, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-", ".tmp");
        try {
            Files.write(tempFile, new byte[1000]);
            
            Transfer transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            transfer.start();
            
            // Should fail with incorrect hash
            assertThatThrownBy(() -> transfer.verifyPartialHash(500, "wrong_hash"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Partial hash mismatch");
            
            transfer.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}