package com.labconnect.core.transfer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransferTask implements Comparable<TransferTask> {
    private final UUID taskId;
    private final Transfer transfer;
    private final TransferPriority priority;
    private final Instant queuedAt;
    private final String targetDeviceId;
    private final Path sourcePath;
    
    private volatile TaskStatus status = TaskStatus.QUEUED;
    private volatile int retryCount = 0;
    private volatile String errorMessage;
    
    private static final AtomicInteger sequenceGenerator = new AtomicInteger(0);
    private final int sequence;

    public TransferTask(Transfer transfer, TransferPriority priority, String targetDeviceId, Path sourcePath) {
        this.taskId = UUID.randomUUID();
        this.transfer = transfer;
        this.priority = priority;
        this.queuedAt = Instant.now();
        this.targetDeviceId = targetDeviceId;
        this.sourcePath = sourcePath;
        this.sequence = sequenceGenerator.incrementAndGet();
    }

    public UUID getTaskId() { return taskId; }
    public Transfer getTransfer() { return transfer; }
    public TransferPriority getPriority() { return priority; }
    public Instant getQueuedAt() { return queuedAt; }
    public String getTargetDeviceId() { return targetDeviceId; }
    public Path getSourcePath() { return sourcePath; }
    public TaskStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getErrorMessage() { return errorMessage; }
    public int getSequence() { return sequence; }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public int compareTo(TransferTask other) {
        // Higher priority first, then earlier queued, then lower sequence
        int priorityCompare = Integer.compare(other.priority.getValue(), this.priority.getValue());
        if (priorityCompare != 0) return priorityCompare;
        
        int timeCompare = this.queuedAt.compareTo(other.queuedAt);
        if (timeCompare != 0) return timeCompare;
        
        return Integer.compare(this.sequence, other.sequence);
    }

    public enum TaskStatus {
        QUEUED,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}