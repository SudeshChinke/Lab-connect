package com.labconnect.core.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class TransferQueue {
    private static final Logger log = LoggerFactory.getLogger(TransferQueue.class);

    private final int maxConcurrent;
    private final PriorityQueue<TransferTask> pendingQueue = new PriorityQueue<>();
    private final Map<UUID, TransferTask> runningTasks = new ConcurrentHashMap<>();
    private final Map<UUID, TransferTask> completedTasks = new ConcurrentHashMap<>();
    private final Object queueLock = new Object();
    
    private final Consumer<TransferTask> onTaskStart;
    private final Consumer<TransferTask> onTaskComplete;
    private final Consumer<TransferTask> onTaskFail;
    private final Consumer<TransferTask> onTaskCancel;
    private final Consumer<TransferTask> onTaskProgress;

    public TransferQueue(int maxConcurrent,
                         Consumer<TransferTask> onTaskStart,
                         Consumer<TransferTask> onTaskComplete,
                         Consumer<TransferTask> onTaskFail,
                         Consumer<TransferTask> onTaskCancel,
                         Consumer<TransferTask> onTaskProgress) {
        this.maxConcurrent = maxConcurrent;
        this.onTaskStart = onTaskStart;
        this.onTaskComplete = onTaskComplete;
        this.onTaskFail = onTaskFail;
        this.onTaskCancel = onTaskCancel;
        this.onTaskProgress = onTaskProgress;
    }

    public void enqueue(TransferTask task) {
        synchronized (queueLock) {
            pendingQueue.add(task);
            log.debug("Enqueued task {} with priority {}", task.getTaskId(), task.getPriority());
            tryStartNext();
        }
    }

    public void enqueueWithPath(Transfer transfer, TransferPriority priority, String targetDeviceId, Path sourcePath) {
        TransferTask task = new TransferTask(transfer, priority, targetDeviceId, sourcePath);
        enqueue(task);
    }

    private void tryStartNext() {
        synchronized (queueLock) {
            while (runningTasks.size() < maxConcurrent && !pendingQueue.isEmpty()) {
                TransferTask task = pendingQueue.poll();
                if (task == null) break;
                
                if (task.getStatus() == TransferTask.TaskStatus.CANCELLED) {
                    continue;
                }
                
                startTask(task);
            }
        }
    }

    private void startTask(TransferTask task) {
        task.setStatus(TransferTask.TaskStatus.RUNNING);
        runningTasks.put(task.getTaskId(), task);
        
        log.info("Starting transfer task {} ({}) to {}", task.getTaskId(), task.getPriority(), task.getTargetDeviceId());
        
        if (onTaskStart != null) {
            onTaskStart.accept(task);
        }
    }

    public void completeTask(UUID taskId) {
        synchronized (queueLock) {
            TransferTask task = runningTasks.remove(taskId);
            if (task == null) {
                log.warn("Complete called for unknown task: {}", taskId);
                return;
            }
            
            task.setStatus(TransferTask.TaskStatus.COMPLETED);
            completedTasks.put(taskId, task);
            
            log.info("Completed transfer task {}", taskId);
            
            if (onTaskComplete != null) {
                onTaskComplete.accept(task);
            }
            
            tryStartNext();
        }
    }

    public void failTask(UUID taskId, String errorMessage) {
        synchronized (queueLock) {
            TransferTask task = runningTasks.remove(taskId);
            if (task == null) {
                log.warn("Fail called for unknown task: {}", taskId);
                return;
            }
            
            task.setStatus(TransferTask.TaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            completedTasks.put(taskId, task);
            
            log.error("Failed transfer task {}: {}", taskId, errorMessage);
            
            if (onTaskFail != null) {
                onTaskFail.accept(task);
            }
            
            tryStartNext();
        }
    }

    public void cancelTask(UUID taskId) {
        synchronized (queueLock) {
            // Check running tasks
            TransferTask task = runningTasks.remove(taskId);
            if (task != null) {
                task.setStatus(TransferTask.TaskStatus.CANCELLED);
                completedTasks.put(taskId, task);
                
                if (onTaskCancel != null) {
                    onTaskCancel.accept(task);
                }
                tryStartNext();
                return;
            }
            
            // Check pending queue
            boolean removed = false;
            Iterator<TransferTask> iterator = pendingQueue.iterator();
            while (iterator.hasNext()) {
                TransferTask t = iterator.next();
                if (t.getTaskId().equals(taskId)) {
                    iterator.remove();
                    t.setStatus(TransferTask.TaskStatus.CANCELLED);
                    completedTasks.put(taskId, t);
                    removed = true;
                    break;
                }
            }
            
            if (removed) {
                log.info("Cancelled queued task {}", taskId);
                if (onTaskCancel != null) {
                    onTaskCancel.accept(task);
                }
            } else {
                log.warn("Cancel called for unknown task: {}", taskId);
            }
        }
    }

    public void pauseTask(UUID taskId) {
        TransferTask task = runningTasks.get(taskId);
        if (task != null) {
            task.setStatus(TransferTask.TaskStatus.PAUSED);
            log.info("Paused task {}", taskId);
        }
    }

    public void resumeTask(UUID taskId) {
        TransferTask task = runningTasks.get(taskId);
        if (task != null && task.getStatus() == TransferTask.TaskStatus.PAUSED) {
            task.setStatus(TransferTask.TaskStatus.RUNNING);
            log.info("Resumed task {}", taskId);
        }
    }

    public void requeueTask(UUID taskId, TransferPriority newPriority) {
        synchronized (queueLock) {
            TransferTask task = runningTasks.remove(taskId);
            if (task != null) {
                task.setStatus(TransferTask.TaskStatus.QUEUED);
                TransferPriority oldPriority = task.getPriority();
                // Note: priority is final in TransferTask, so we'd need to create new task
                // For now, just requeue with same priority
                pendingQueue.add(task);
                log.info("Requeued task {} from priority {}", taskId, oldPriority);
                tryStartNext();
            }
        }
    }

    public void updateProgress(UUID taskId) {
        TransferTask task = runningTasks.get(taskId);
        if (task != null && onTaskProgress != null) {
            onTaskProgress.accept(task);
        }
    }

    public int getRunningCount() {
        return runningTasks.size();
    }

    public int getPendingCount() {
        return pendingQueue.size();
    }

    public int getCompletedCount() {
        return completedTasks.size();
    }

    public List<TransferTask> getRunningTasks() {
        return new ArrayList<>(runningTasks.values());
    }

    public List<TransferTask> getPendingTasks() {
        synchronized (queueLock) {
            return new ArrayList<>(pendingQueue);
        }
    }

    public List<TransferTask> getCompletedTasks() {
        return new ArrayList<>(completedTasks.values());
    }

    public Optional<TransferTask> getTask(UUID taskId) {
        TransferTask running = runningTasks.get(taskId);
        if (running != null) return Optional.of(running);
        
        synchronized (queueLock) {
            return pendingQueue.stream()
                    .filter(t -> t.getTaskId().equals(taskId))
                    .findFirst();
        }
    }

    public void clearCompleted() {
        completedTasks.clear();
    }

    public void shutdown() {
        synchronized (queueLock) {
            // Cancel all pending
            while (!pendingQueue.isEmpty()) {
                TransferTask task = pendingQueue.poll();
                task.setStatus(TransferTask.TaskStatus.CANCELLED);
                if (onTaskCancel != null) {
                    onTaskCancel.accept(task);
                }
            }
            
            // Cancel running
            for (TransferTask task : runningTasks.values()) {
                task.setStatus(TransferTask.TaskStatus.CANCELLED);
                if (onTaskCancel != null) {
                    onTaskCancel.accept(task);
                }
            }
            runningTasks.clear();
            pendingQueue.clear();
        }
    }
}