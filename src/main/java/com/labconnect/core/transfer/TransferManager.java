package com.labconnect.core.transfer;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class TransferManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TransferManager.class);

    private final ConnectionManager connectionManager;
    private final String localDeviceId;
    private final AppConfig.TransferConfig config;
    private final Path downloadDir;
    private final ScheduledExecutorService progressScheduler;
    
    private final TransferQueue transferQueue;
    
    private final Map<UUID, Transfer> activeTransfers = new ConcurrentHashMap<>();
    private final Consumer<Transfer> onTransferProgress;
    private final Consumer<Transfer> onTransferCompleted;
    private final Consumer<Transfer> onTransferFailed;

    public TransferManager(ConnectionManager connectionManager,
                           String localDeviceId,
                           AppConfig config,
                           Path downloadDir,
                           Consumer<Transfer> onTransferProgress,
                           Consumer<Transfer> onTransferCompleted,
                           Consumer<Transfer> onTransferFailed) {
        this.connectionManager = connectionManager;
        this.localDeviceId = localDeviceId;
        this.config = config.getTransfer();
        this.downloadDir = downloadDir;
        this.onTransferProgress = onTransferProgress;
        this.onTransferCompleted = onTransferCompleted;
        this.onTransferFailed = onTransferFailed;
        
        this.progressScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "transfer-progress"); t.setDaemon(true); return t; });
        
        // Create transfer queue
        this.transferQueue = new TransferQueue(
                this.config.getMaxConcurrentTransfers(),
                this::onTaskStart,
                this::onTaskComplete,
                this::onTaskFail,
                this::onTaskCancel,
                this::onTaskProgress
        );
        
        // Start progress reporting
        progressScheduler.scheduleAtFixedRate(this::reportProgress, 1, 1, TimeUnit.SECONDS);
        
        try {
            Files.createDirectories(downloadDir);
        } catch (IOException e) {
            log.error("Failed to create download directory", e);
        }
    }

    public void sendFile(String targetDeviceId, Path filePath) throws IOException {
        sendFile(targetDeviceId, filePath, TransferPriority.NORMAL);
    }

    public void sendFile(String targetDeviceId, Path filePath, TransferPriority priority) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        
        long fileSize = Files.size(filePath);
        String mimeType = Files.probeContentType(filePath);
        if (mimeType == null) mimeType = "application/octet-stream";
        
        String sha256 = ChecksumEngine.computeSHA256(filePath);
        log.info("Prepared file: {} ({} bytes, SHA256: {})", 
                filePath.getFileName(), fileSize, sha256);
        
        UUID transferId = UUID.randomUUID();
        TransferMetadata metadata = TransferMetadata.create(
                transferId, filePath.getFileName().toString(), fileSize,
                mimeType, sha256, config.getChunkSize(),
                localDeviceId, targetDeviceId
        );
        
        Transfer transfer = new Transfer(metadata, filePath, localDeviceId, true);
        activeTransfers.put(transferId, transfer);
        
        // Queue the transfer
        transferQueue.enqueueWithPath(transfer, priority, targetDeviceId, filePath);
        
        // Send FILE_REQUEST to receiver
        sendFileRequest(targetDeviceId, metadata);
    }

    public void receiveFile(UUID transferId, String senderId, TransferMetadata metadata) {
        Path filePath = downloadDir.resolve(metadata.fileName());
        
        // Check if partial file exists for resume
        long existingSize = 0;
        if (Files.exists(filePath)) {
            try {
                existingSize = Files.size(filePath);
                log.info("Found partial file: {} bytes", existingSize);
            } catch (IOException e) {
                log.warn("Could not read partial file size", e);
            }
        }
        
        Transfer transfer;
        try {
            transfer = new Transfer(metadata, filePath, localDeviceId, false);
        } catch (IOException e) {
            log.error("Failed to create transfer", e);
            return;
        }
        activeTransfers.put(transferId, transfer);
        
        // If resuming, verify existing data
        if (existingSize > 0) {
            try {
                verifyPartialFile(transfer, existingSize);
            } catch (IOException e) {
                log.warn("Partial file verification failed, starting fresh", e);
                try { Files.deleteIfExists(filePath); } catch (IOException ignored) {}
            }
        }
        
        // Send FILE_ACCEPT with resume offset
        sendFileAccept(senderId, transferId, existingSize);
    }

    private void verifyPartialFile(Transfer transfer, long existingSize) throws IOException {
        if (existingSize >= transfer.getMetadata().fileSize()) {
            if (transfer.verifyHash()) {
                transfer.complete();
                if (onTransferCompleted != null) onTransferCompleted.accept(transfer);
            }
            return;
        }
        
        // Set resume offset for receiver
        transfer.setResumeOffset(existingSize);
        
        // Verify partial file hash if possible
        // For now, we trust the existing data and resume from there
        log.info("Resuming transfer from offset {}", existingSize);
    }

    private void onTaskStart(TransferTask task) {
        // Task started - transfer is already in activeTransfers
        task.getTransfer().start();
        log.debug("Task started: {}", task.getTaskId());
    }

    private void onTaskComplete(TransferTask task) {
        Transfer transfer = task.getTransfer();
        if (transfer.verifyHash()) {
            transfer.complete();
            activeTransfers.remove(transfer.getMetadata().transferId());
            if (onTransferCompleted != null) {
                onTransferCompleted.accept(transfer);
            }
        } else {
            log.error("Hash verification failed for transfer {}", transfer.getMetadata().transferId());
            onTaskFail(task);
        }
    }

    private void onTaskFail(TransferTask task) {
        Transfer transfer = task.getTransfer();
        transfer.fail(task.getErrorMessage());
        activeTransfers.remove(transfer.getMetadata().transferId());
        if (onTransferFailed != null) {
            onTransferFailed.accept(transfer);
        }
    }

    private void onTaskCancel(TransferTask task) {
        Transfer transfer = task.getTransfer();
        transfer.cancel();
        activeTransfers.remove(transfer.getMetadata().transferId());
        // Could notify UI
    }

    private void onTaskProgress(TransferTask task) {
        Transfer transfer = task.getTransfer();
        if (onTransferProgress != null && transfer.getState() == TransferState.IN_PROGRESS) {
            onTransferProgress.accept(transfer);
        }
    }

    private void reportProgress() {
        // Progress reporting is handled via task callbacks
    }

    public void pauseTransfer(UUID transferId) {
        Optional<TransferTask> task = transferQueue.getTask(transferId);
        task.ifPresent(t -> transferQueue.pauseTask(t.getTaskId()));
    }

    public void resumeTransfer(UUID transferId) {
        Optional<TransferTask> task = transferQueue.getTask(transferId);
        task.ifPresent(t -> transferQueue.resumeTask(t.getTaskId()));
    }

    public void cancelTransfer(UUID transferId) {
        Optional<TransferTask> task = transferQueue.getTask(transferId);
        task.ifPresent(t -> transferQueue.cancelTask(t.getTaskId()));
    }

    public Optional<Transfer> getTransfer(UUID transferId) {
        return Optional.ofNullable(activeTransfers.get(transferId));
    }

    public Collection<Transfer> getActiveTransfers() {
        return Collections.unmodifiableCollection(activeTransfers.values());
    }

    public int getQueueSize() {
        return transferQueue.getPendingCount();
    }

    public int getRunningCount() {
        return transferQueue.getRunningCount();
    }

    public List<TransferTask> getAllTasks() {
        List<TransferTask> all = new ArrayList<>();
        all.addAll(transferQueue.getRunningTasks());
        all.addAll(transferQueue.getPendingTasks());
        all.addAll(transferQueue.getCompletedTasks());
        return all;
    }

    public void handleFrame(FrameCodec.Frame frame) {
        byte type = frame.type();
        if (type == MessageType.FILE_REQUEST.value()) handleFileRequest(frame);
        else if (type == MessageType.FILE_ACCEPT.value()) handleFileAccept(frame);
        else if (type == MessageType.FILE_REJECT.value()) handleFileReject(frame);
        else if (type == MessageType.FILE_CHUNK.value()) handleFileChunk(frame);
        else if (type == MessageType.FILE_CHUNK_ACK.value()) handleChunkAck(frame);
        else if (type == MessageType.FILE_COMPLETE.value()) handleFileComplete(frame);
        else if (type == MessageType.FILE_VERIFIED.value()) handleFileVerified(frame);
        else if (type == MessageType.FILE_CANCEL.value()) handleFileCancel(frame);
        else if (type == MessageType.FILE_RESUME.value()) handleFileResume(frame);
    }

    private void handleFileRequest(FrameCodec.Frame frame) {
        // Parse and create incoming transfer
    }

    private void handleFileAccept(FrameCodec.Frame frame) {
        // Parse accept, resume transfer
        // Frame payload contains: transferId, resumeOffset, receiverHash
        // Find the transfer and resume from the given offset
    }

    private void handleFileResume(FrameCodec.Frame frame) {
        // Parse resume request
        // Frame payload contains: transferId, requestedOffset, knownHash
        // Verify the known hash matches our file at that offset
        // Then resume from that offset
    }

    private void handleFileReject(FrameCodec.Frame frame) {
        // Notify sender
    }

    private void handleFileChunk(FrameCodec.Frame frame) {
        // Parse chunk and write to file
    }

    private void handleChunkAck(FrameCodec.Frame frame) {
        // Mark chunk as acknowledged
    }

    private void handleFileComplete(FrameCodec.Frame frame) {
        // Verify hash and complete
    }

    private void handleFileVerified(FrameCodec.Frame frame) {
        // Transfer fully complete
    }

    private void handleFileCancel(FrameCodec.Frame frame) {
        // Cancel transfer
    }

    private void sendFileRequest(String targetDeviceId, TransferMetadata metadata) {
        // Send FILE_REQUEST frame
    }

    private void sendFileAccept(String targetDeviceId, UUID transferId, long resumeOffset) {
        // Send FILE_ACCEPT frame
    }

    private void sendChunk(String targetDeviceId, UUID transferId, long chunkIndex, byte[] data) {
        // Send FILE_CHUNK frame
    }

    private void sendFileComplete(String targetDeviceId, UUID transferId) {
        // Send FILE_COMPLETE frame
    }

    @Override
    public void close() {
        progressScheduler.shutdownNow();
        transferQueue.shutdown();
        activeTransfers.values().forEach(Transfer::close);
        activeTransfers.clear();
        log.info("TransferManager stopped");
    }
}