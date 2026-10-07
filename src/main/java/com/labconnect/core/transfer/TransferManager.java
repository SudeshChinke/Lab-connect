package com.labconnect.core.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Base64;
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
    
    // Jackson for serializing TransferMetadata and other transfer frames
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

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

    public Path getDownloadDir() {
        return downloadDir;
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

    private void sendFrameToDevice(String targetDeviceId, FrameCodec.Frame frame) {
        connectionManager.getConnection(targetDeviceId).ifPresent(conn -> 
            connectionManager.sendFrame(conn, frame)
        );
    }

    private void sendFileRequest(String targetDeviceId, TransferMetadata metadata) {
        try {
            byte[] payload = mapper.writeValueAsBytes(metadata);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_REQUEST.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
            log.debug("Sent FILE_REQUEST for transfer {} to {}", metadata.transferId(), targetDeviceId);
        } catch (Exception e) {
            log.error("Failed to send FILE_REQUEST", e);
        }
    }

    private void sendFileAccept(String targetDeviceId, UUID transferId, long resumeOffset) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("resumeOffset", resumeOffset);
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_ACCEPT.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
            log.debug("Sent FILE_ACCEPT for transfer {} to {} at offset {}", transferId, targetDeviceId, resumeOffset);
        } catch (Exception e) {
            log.error("Failed to send FILE_ACCEPT", e);
        }
    }

    private void sendChunk(String targetDeviceId, UUID transferId, long chunkIndex, byte[] data) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("chunkIndex", chunkIndex);
            payloadMap.put("data", Base64.getEncoder().encodeToString(data));
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_CHUNK.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
            log.trace("Sent FILE_CHUNK {} for transfer {} to {}", chunkIndex, transferId, targetDeviceId);
        } catch (Exception e) {
            log.error("Failed to send FILE_CHUNK", e);
        }
    }

    private void sendFileComplete(String targetDeviceId, UUID transferId) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("finalHash", activeTransfers.get(transferId).computeFinalHash());
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_COMPLETE.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
            log.debug("Sent FILE_COMPLETE for transfer {} to {}", transferId, targetDeviceId);
        } catch (Exception e) {
            log.error("Failed to send FILE_COMPLETE", e);
        }
    }

    private void sendFileReject(String targetDeviceId, UUID transferId, String reason) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("reason", reason);
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_REJECT.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
        } catch (Exception e) {
            log.error("Failed to send FILE_REJECT", e);
        }
    }

    private void sendChunkAck(String targetDeviceId, UUID transferId, long chunkIndex) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("chunkIndex", chunkIndex);
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_CHUNK_ACK.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
        } catch (Exception e) {
            log.error("Failed to send FILE_CHUNK_ACK", e);
        }
    }

    private void sendFileCancel(String targetDeviceId, UUID transferId) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_CANCEL.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
        } catch (Exception e) {
            log.error("Failed to send FILE_CANCEL", e);
        }
    }

    private void sendFileVerified(String targetDeviceId, UUID transferId) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_VERIFIED.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
        } catch (Exception e) {
            log.error("Failed to send FILE_VERIFIED", e);
        }
    }

    private void sendFileResume(String targetDeviceId, UUID transferId, long requestedOffset, String knownHash) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("transferId", transferId.toString());
            payloadMap.put("requestedOffset", requestedOffset);
            payloadMap.put("knownHash", knownHash);
            byte[] payload = mapper.writeValueAsBytes(payloadMap);
            UUID messageId = UUID.randomUUID();
            FrameCodec.Frame frame = FrameCodec.Frame.create(MessageType.FILE_RESUME.value(), (byte) 0, messageId, payload);
            sendFrameToDevice(targetDeviceId, frame);
        } catch (Exception e) {
            log.error("Failed to send FILE_RESUME", e);
        }
    }

    private void handleFileRequest(FrameCodec.Frame frame) {
        try {
            TransferMetadata metadata = mapper.readValue(frame.payload(), TransferMetadata.class);
            // The sender is the source of the frame - we need to know who sent it
            // This is called from ConnectionManager which knows the source
            // For now, we'll use the senderId from metadata
            receiveFile(metadata.transferId(), metadata.senderId(), metadata);
        } catch (Exception e) {
            log.error("Failed to handle FILE_REQUEST", e);
        }
    }

    private void handleFileAccept(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            long resumeOffset = ((Number) payload.get("resumeOffset")).longValue();
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && transfer.isSender()) {
                transfer.setResumeOffset(resumeOffset);
                // Start sending from the resume offset
                startSendingChunks(transfer);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_ACCEPT", e);
        }
    }

    private void handleFileReject(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            String reason = (String) payload.get("reason");
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null) {
                transfer.fail("Rejected by peer: " + reason);
                activeTransfers.remove(transferId);
                if (onTransferFailed != null) onTransferFailed.accept(transfer);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_REJECT", e);
        }
    }

    private void handleFileChunk(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            long chunkIndex = ((Number) payload.get("chunkIndex")).longValue();
            String dataB64 = (String) payload.get("data");
            byte[] data = Base64.getDecoder().decode(dataB64);
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && !transfer.isSender()) {
                transfer.writeChunk(chunkIndex, data);
                // Acknowledge the chunk
                sendChunkAck(transfer.getMetadata().senderId(), transferId, chunkIndex);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_CHUNK", e);
        }
    }

    private void handleChunkAck(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            long chunkIndex = ((Number) payload.get("chunkIndex")).longValue();
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && transfer.isSender()) {
                transfer.markChunkAcked(chunkIndex);
                // Check if all chunks are acknowledged, then send FILE_COMPLETE
                checkAndSendFileComplete(transfer);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_CHUNK_ACK", e);
        }
    }

    private void handleFileComplete(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            String finalHash = (String) payload.get("finalHash");
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && !transfer.isSender()) {
                if (transfer.verifyHash()) {
                    transfer.complete();
                    activeTransfers.remove(transferId);
                    if (onTransferCompleted != null) onTransferCompleted.accept(transfer);
                    // Send FILE_VERIFIED to confirm
                    sendFileVerified(transfer.getMetadata().senderId(), transferId);
                } else {
                    transfer.fail("Hash verification failed");
                    activeTransfers.remove(transferId);
                    if (onTransferFailed != null) onTransferFailed.accept(transfer);
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_COMPLETE", e);
        }
    }

    private void handleFileVerified(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && transfer.isSender()) {
                transfer.complete();
                activeTransfers.remove(transferId);
                if (onTransferCompleted != null) onTransferCompleted.accept(transfer);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_VERIFIED", e);
        }
    }

    private void handleFileCancel(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null) {
                transfer.cancel();
                activeTransfers.remove(transferId);
                // No callback needed - transfer was cancelled
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_CANCEL", e);
        }
    }

    private void handleFileResume(FrameCodec.Frame frame) {
        try {
            Map<String, Object> payload = mapper.readValue(frame.payload(), Map.class);
            UUID transferId = UUID.fromString((String) payload.get("transferId"));
            long requestedOffset = ((Number) payload.get("requestedOffset")).longValue();
            String knownHash = (String) payload.get("knownHash");
            
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer != null && transfer.isSender()) {
                // Verify the partial hash
                transfer.verifyPartialHash(requestedOffset, knownHash);
                // Resume from that offset
                transfer.setResumeOffset(requestedOffset);
                startSendingChunks(transfer);
            }
        } catch (Exception e) {
            log.error("Failed to handle FILE_RESUME", e);
        }
    }

    private void startSendingChunks(Transfer transfer) {
        UUID transferId = transfer.getMetadata().transferId();
        String targetDeviceId = transfer.getMetadata().receiverId();
        
        try {
            // Send all chunks
            byte[] chunk;
            while ((chunk = transfer.readNextChunk()) != null) {
                if (transfer.isCancelled()) break;
                sendChunk(targetDeviceId, transferId, transfer.getNextChunkToSend() - 1, chunk);
            }
        } catch (IOException e) {
            log.error("Error sending chunks for transfer {}", transferId, e);
            transfer.fail("Send error: " + e.getMessage());
        }
    }
    
    private void checkAndSendFileComplete(Transfer transfer) {
        UUID transferId = transfer.getMetadata().transferId();
        String targetDeviceId = transfer.getMetadata().receiverId();
        
        if (!transfer.isCancelled() 
                && transfer.getBytesTransferred() >= transfer.getFileSize()
                && transfer.getMissingChunks().isEmpty()) {
            sendFileComplete(targetDeviceId, transferId);
        }
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