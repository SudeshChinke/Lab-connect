package com.labconnect.core.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Transfer {
    private static final Logger log = LoggerFactory.getLogger(Transfer.class);

    private final TransferMetadata metadata;
    private final Path filePath;
    private final String localDeviceId;
    private final boolean isSender;
    
    private volatile TransferState state = TransferState.PENDING;
    private volatile long bytesTransferred = 0;
    private volatile long lastProgressUpdate = 0;
    private volatile Instant startTime;
    private volatile Instant endTime;
    private volatile String errorMessage;
    
    private RandomAccessFile raf;
    private MessageDigest digest;
    private final Map<Long, byte[]> sentChunks = new ConcurrentHashMap<>();
    private final Set<Long> ackedChunks = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextChunkToSend = new AtomicLong(0);
    
    private final Object pauseLock = new Object();
    private volatile boolean paused = false;
    private volatile boolean cancelled = false;
    
    // Resume support
    private volatile long resumeOffset = 0;
    private volatile long lastCheckpointOffset = 0;
    private final Object checkpointLock = new Object();

    public Transfer(TransferMetadata metadata, Path filePath, String localDeviceId, boolean isSender) 
            throws IOException {
        this.metadata = metadata;
        this.filePath = filePath;
        this.localDeviceId = localDeviceId;
        this.isSender = isSender;
        
        try {
            if (isSender) {
                this.raf = new RandomAccessFile(filePath.toFile(), "r");
                this.digest = MessageDigest.getInstance("SHA-256");
            } else {
                // Receiver: create file for writing
                Files.createDirectories(filePath.getParent());
                this.raf = new RandomAccessFile(filePath.toFile(), "rw");
                this.digest = MessageDigest.getInstance("SHA-256");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public TransferMetadata getMetadata() { return metadata; }
    public TransferState getState() { return state; }
    public long getBytesTransferred() { return bytesTransferred; }
    public long getFileSize() { return metadata.fileSize(); }
    public double getProgress() { 
        return metadata.fileSize() > 0 ? (double) bytesTransferred / metadata.fileSize() : 0; 
    }
    public double getSpeedBytesPerSec() {
        if (startTime == null) return 0;
        long elapsed = System.currentTimeMillis() - startTime.toEpochMilli();
        return elapsed > 0 ? (bytesTransferred * 1000.0 / elapsed) : 0;
    }
    public long getEtaMillis() {
        double speed = getSpeedBytesPerSec();
        return speed > 0 ? (long) ((metadata.fileSize() - bytesTransferred) / speed * 1000) : -1;
    }
    public String getErrorMessage() { return errorMessage; }
    public boolean isSender() { return isSender; }
    public boolean isPaused() { return paused; }
    public boolean isCancelled() { return cancelled; }
    public Instant getStartTime() { return startTime; }

    public void start() {
        this.state = TransferState.IN_PROGRESS;
        this.startTime = Instant.now();
    }

    public void pause() {
        if (state == TransferState.IN_PROGRESS) {
            this.state = TransferState.PAUSED;
            this.paused = true;
        }
    }

    public void resume() {
        if (state == TransferState.PAUSED) {
            this.state = TransferState.IN_PROGRESS;
            this.paused = false;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    public void cancel() {
        this.cancelled = true;
        this.state = TransferState.CANCELLED;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public void fail(String error) {
        this.errorMessage = error;
        this.state = TransferState.FAILED;
    }

    public void complete() {
        this.state = TransferState.COMPLETED;
        this.endTime = Instant.now();
        this.bytesTransferred = metadata.fileSize();
    }

    public byte[] readNextChunk() throws IOException {
        if (cancelled) return null;
        
        long chunkIndex = nextChunkToSend.getAndIncrement();
        if (chunkIndex >= metadata.totalChunks()) {
            return null; // No more chunks
        }
        
        long offset = chunkIndex * metadata.chunkSize();
        int chunkLen = (int) Math.min(metadata.chunkSize(), metadata.fileSize() - offset);
        
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                try {
                    pauseLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (cancelled) return null;
        }
        
        raf.seek(offset);
        byte[] chunk = new byte[chunkLen];
        int read = raf.read(chunk);
        if (read != chunkLen) {
            throw new IOException("Short read: expected " + chunkLen + ", got " + read);
        }
        
        // Update digest
        digest.update(chunk);
        
        // Update progress
        bytesTransferred += chunkLen;
        
        // Store for potential retransmit
        sentChunks.put(chunkIndex, chunk);
        
        return chunk;
    }

    public void writeChunk(long chunkIndex, byte[] data) throws IOException {
        long offset = chunkIndex * metadata.chunkSize();
        raf.seek(offset);
        raf.write(data);
        
        // Update digest
        digest.update(data);
        
        bytesTransferred += data.length;
        ackedChunks.add(chunkIndex);
    }

    public String computeFinalHash() {
        return bytesToHex(digest.digest());
    }

    public boolean verifyHash() {
        String computed = computeFinalHash();
        boolean match = computed.equalsIgnoreCase(metadata.sha256());
        log.info("Hash verification: computed={}, expected={}, match={}", 
                computed, metadata.sha256(), match);
        return match;
    }

    public void waitForUnpause() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                pauseLock.wait();
            }
        }
    }

    public void close() {
        try {
            if (raf != null) raf.close();
        } catch (IOException e) {
            log.debug("Error closing transfer file", e);
        }
    }

    public boolean isChunkAcked(long chunkIndex) {
        return ackedChunks.contains(chunkIndex);
    }

    public void markChunkAcked(long chunkIndex) {
        ackedChunks.add(chunkIndex);
        sentChunks.remove(chunkIndex); // Free memory
    }

    public Set<Long> getMissingChunks() {
        Set<Long> missing = new HashSet<>();
        for (long i = 0; i < metadata.totalChunks(); i++) {
            if (!ackedChunks.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    public void seekToChunk(long chunkIndex) throws IOException {
        long offset = chunkIndex * metadata.chunkSize();
        raf.seek(offset);
        nextChunkToSend.set(chunkIndex);
        this.resumeOffset = offset;
    }

    // Resumable transfer support
    public void setResumeOffset(long offset) {
        this.resumeOffset = offset;
        long chunkIndex = offset / metadata.chunkSize();
        nextChunkToSend.set(chunkIndex);
        try {
            raf.seek(offset);
        } catch (IOException e) {
            log.error("Failed to seek to resume offset", e);
        }
    }

    public long getResumeOffset() {
        return resumeOffset;
    }

    public long getLastCheckpointOffset() {
        return lastCheckpointOffset;
    }

    public void checkpoint() {
        synchronized (checkpointLock) {
            lastCheckpointOffset = bytesTransferred;
            log.debug("Checkpoint saved at offset {}", lastCheckpointOffset);
        }
    }

    public long getBytesRemaining() {
        return metadata.fileSize() - bytesTransferred;
    }

    public double getResumeProgress() {
        if (metadata.fileSize() == 0) return 0.0;
        return (double) resumeOffset / metadata.fileSize();
    }

    public void verifyPartialHash(long expectedOffset, String expectedHash) throws IOException {
        if (!isSender()) {
            throw new IllegalStateException("verifyPartialHash only for sender");
        }
        if (expectedOffset > metadata.fileSize()) {
            throw new IllegalArgumentException("Offset exceeds file size");
        }
        
        long originalPosition = raf.getFilePointer();
        try {
            raf.seek(0);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long bytesToRead = expectedOffset;
            int bytesRead;
            
            while (bytesToRead > 0 && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                digest.update(buffer, 0, bytesRead);
                bytesToRead -= bytesRead;
            }
            
            String computed = bytesToHex(digest.digest());
            if (!computed.equalsIgnoreCase(expectedHash)) {
                throw new IOException("Partial hash mismatch at offset " + expectedOffset + 
                        ": expected " + expectedHash + ", got " + computed);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        } finally {
            raf.seek(originalPosition);
        }
    }

    public void verifyReceiverPartialHash(long expectedOffset, String expectedHash) throws IOException {
        if (isSender()) {
            throw new IllegalStateException("verifyReceiverPartialHash only for receiver");
        }
        if (expectedOffset > metadata.fileSize()) {
            throw new IllegalArgumentException("Offset exceeds file size");
        }
        
        long originalPosition = raf.getFilePointer();
        try {
            raf.seek(0);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long bytesToRead = expectedOffset;
            int bytesRead;
            
            while (bytesToRead > 0 && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                digest.update(buffer, 0, bytesRead);
                bytesToRead -= bytesRead;
            }
            
            String computed = bytesToHex(digest.digest());
            if (!computed.equalsIgnoreCase(expectedHash)) {
                throw new IOException("Receiver partial hash mismatch at offset " + expectedOffset + 
                        ": expected " + expectedHash + ", got " + computed);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        } finally {
            raf.seek(originalPosition);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}