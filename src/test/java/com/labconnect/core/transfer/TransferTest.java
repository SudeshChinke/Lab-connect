package com.labconnect.core.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TransferTest {

    private Path testFile;
    private Path downloadDir;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = Files.createTempDirectory("labconnect-transfer-test");
        testFile = downloadDir.resolve("test-file.txt");
        Files.writeString(testFile, "Hello, World! This is a test file for transfer.\n".repeat(100));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testFile)) Files.delete(testFile);
        deleteRecursively(downloadDir);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        }
    }

    @Test
    @Timeout(10)
    void testTransferMetadataCreation() {
        UUID transferId = UUID.randomUUID();
        TransferMetadata metadata = TransferMetadata.create(
                transferId, "test.txt", 1024, "text/plain",
                "abc123", 65536, "DEVICE-1", "DEVICE-2"
        );

        assertThat(metadata.transferId()).isEqualTo(transferId);
        assertThat(metadata.fileName()).isEqualTo("test.txt");
        assertThat(metadata.fileSize()).isEqualTo(1024);
        assertThat(metadata.chunkSize()).isEqualTo(65536);
        assertThat(metadata.totalChunks()).isEqualTo(1); // 1024 bytes < 64KB
    }

    @Test
    @Timeout(10)
    void testTransferMetadataMultipleChunks() {
        UUID transferId = UUID.randomUUID();
        TransferMetadata metadata = TransferMetadata.create(
                transferId, "large.bin", 131072, "application/octet-stream",
                "sha256hash", 65536, "DEVICE-1", "DEVICE-2"
        );

        assertThat(metadata.totalChunks()).isEqualTo(2); // 128KB / 64KB = 2 chunks
    }

    @Test
    @Timeout(10)
    void testChecksumEngine() throws IOException {
        String hash = ChecksumEngine.computeSHA256(testFile);
        
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 = 64 hex chars
        
        // Same file = same hash
        String hash2 = ChecksumEngine.computeSHA256(testFile);
        assertThat(hash).isEqualTo(hash2);
    }

    @Test
    @Timeout(10)
    void testChecksumMismatchException() throws IOException {
        String wrongHash = "0".repeat(64);
        
        assertThatThrownBy(() -> 
            ChecksumEngine.verifyAndCompute(testFile, wrongHash, null)
        ).isInstanceOf(ChecksumEngine.ChecksumMismatchException.class);
    }

    @Test
    @Timeout(10)
    void testStreamingVerifier() throws IOException {
        ChecksumEngine.StreamingVerifier verifier = new ChecksumEngine.StreamingVerifier(
                ChecksumEngine.computeSHA256(testFile)
        );
        
        byte[] data = Files.readAllBytes(testFile);
        verifier.update(data);
        verifier.verify(); // Should not throw
        
        String computed = verifier.getComputedHash();
        assertThat(computed).hasSize(64);
    }

    @Test
    @Timeout(10)
    void testTransferState() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", 100, "text/plain",
                "hash", 64, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-test-", ".tmp");
        try {
            Files.write(tempFile, new byte[100]);
            
            Transfer transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            
            assertThat(transfer.getState()).isEqualTo(TransferState.PENDING);
            assertThat(transfer.getBytesTransferred()).isEqualTo(0);
            assertThat(transfer.getProgress()).isEqualTo(0.0);
            assertThat(transfer.isSender()).isTrue();
            assertThat(transfer.isPaused()).isFalse();
            assertThat(transfer.isCancelled()).isFalse();
            
            transfer.start();
            assertThat(transfer.getState()).isEqualTo(TransferState.IN_PROGRESS);
            assertThat(transfer.getStartTime()).isNotNull();
            
            transfer.pause();
            assertThat(transfer.getState()).isEqualTo(TransferState.PAUSED);
            assertThat(transfer.isPaused()).isTrue();
            
            transfer.resume();
            assertThat(transfer.getState()).isEqualTo(TransferState.IN_PROGRESS);
            assertThat(transfer.isPaused()).isFalse();
            
            transfer.cancel();
            assertThat(transfer.getState()).isEqualTo(TransferState.CANCELLED);
            assertThat(transfer.isCancelled()).isTrue();
            
            transfer.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @Timeout(15)
    void testTransferReadChunks() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", testFile.toFile().length(), "text/plain",
                ChecksumEngine.computeSHA256(testFile), 1024, "DEVICE-1", "DEVICE-2"
        );
        
        Path tempFile = Files.createTempFile("transfer-", ".tmp");
        Transfer transfer = null;
        try {
            Files.copy(testFile, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            transfer = new Transfer(metadata, tempFile, "DEVICE-1", true);
            transfer.start();
            
            int chunksRead = 0;
            byte[] chunk;
            while ((chunk = transfer.readNextChunk()) != null) {
                assertThat(chunk.length).isLessThanOrEqualTo(1024);
                chunksRead++;
            }
            
            assertThat(chunksRead).isEqualTo(metadata.totalChunks());
            assertThat(transfer.getBytesTransferred()).isEqualTo(metadata.fileSize());
            assertThat(transfer.getProgress()).isCloseTo(1.0, within(0.01));
            
        } finally {
            if (transfer != null) transfer.close();
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @Timeout(15)
    void testTransferWriteChunks() throws IOException {
        TransferMetadata metadata = TransferMetadata.create(
                UUID.randomUUID(), "test.txt", testFile.toFile().length(), "text/plain",
                ChecksumEngine.computeSHA256(testFile), 1024, "DEVICE-1", "DEVICE-2"
        );
        
        Path outputFile = downloadDir.resolve("output.txt");
        
        Transfer transfer = new Transfer(metadata, outputFile, "DEVICE-2", false);
        transfer.start();
        
        byte[] fileData = Files.readAllBytes(testFile);
        int chunkSize = 1024;
        int chunks = 0;
        
        for (int i = 0; i < fileData.length; i += chunkSize) {
            int len = Math.min(chunkSize, fileData.length - i);
            byte[] chunk = new byte[len];
            System.arraycopy(fileData, i, chunk, 0, len);
            transfer.writeChunk(chunks, chunk);
            chunks++;
        }
        
        assertThat(transfer.getBytesTransferred()).isEqualTo(metadata.fileSize());
        assertThat(transfer.verifyHash()).isTrue();
        
        // Close first to flush and release file handle
        transfer.close();
        
        // Verify written file
        String writtenHash = ChecksumEngine.computeSHA256(outputFile);
        assertThat(writtenHash).isEqualTo(metadata.sha256());
    }
}