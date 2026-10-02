package com.labconnect.core.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {}

    /**
     * Execute a supplier with error handling.
     */
    public static <T> T handle(Supplier<T> operation, Consumer<LabConnectException> errorHandler) {
        try {
            return operation.get();
        } catch (LabConnectException e) {
            log.error("Handled LabConnectException: {}", e.getMessage(), e);
            if (errorHandler != null) errorHandler.accept(e);
            return null;
        } catch (Exception e) {
            LabConnectException le = new LabConnectException(ErrorCode.INTERNAL_ERROR, "Unexpected error", e);
            log.error("Unexpected error", le);
            if (errorHandler != null) errorHandler.accept(le);
            return null;
        }
    }

    /**
     * Execute a runnable with error handling.
     */
    public static void handle(Runnable operation, Consumer<LabConnectException> errorHandler) {
        try {
            operation.run();
        } catch (LabConnectException e) {
            log.error("Handled LabConnectException: {}", e.getMessage(), e);
            if (errorHandler != null) errorHandler.accept(e);
        } catch (Exception e) {
            LabConnectException le = new LabConnectException(ErrorCode.INTERNAL_ERROR, "Unexpected error", e);
            log.error("Unexpected error", le);
            if (errorHandler != null) errorHandler.accept(le);
        }
    }

    /**
     * Execute with retry logic.
     */
    public static <T> T withRetry(Supplier<T> operation, int maxRetries, long delayMs) throws LabConnectException {
        LabConnectException lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.get();
            } catch (LabConnectException e) {
                lastException = e;
                if (!e.isRecoverable() || attempt == maxRetries) {
                    throw e;
                }
                log.warn("Attempt {}/{} failed: {}. Retrying in {}ms...", 
                        attempt + 1, maxRetries + 1, e.getUserMessage(), delayMs);
                sleep(delayMs);
            } catch (Exception e) {
                LabConnectException le = new LabConnectException(ErrorCode.INTERNAL_ERROR, "Unexpected error", e);
                if (attempt == maxRetries) throw le;
                log.warn("Attempt {}/{} failed: {}. Retrying in {}ms...", 
                        attempt + 1, maxRetries + 1, e.getMessage(), delayMs);
                sleep(delayMs);
            }
        }
        throw lastException;
    }

    /**
     * Execute with exponential backoff retry.
     */
    public static <T> T withExponentialBackoff(Supplier<T> operation, int maxRetries, long initialDelayMs) throws LabConnectException {
        long delay = initialDelayMs;
        LabConnectException lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.get();
            } catch (LabConnectException e) {
                lastException = e;
                if (!e.isRecoverable() || attempt == maxRetries) {
                    throw e;
                }
                log.warn("Attempt {}/{} failed: {}. Retrying in {}ms...", 
                        attempt + 1, maxRetries + 1, e.getUserMessage(), delay);
                sleep(delay);
                delay = Math.min(delay * 2, 30000); // Cap at 30 seconds
            } catch (Exception e) {
                LabConnectException le = new LabConnectException(ErrorCode.INTERNAL_ERROR, "Unexpected error", e);
                if (attempt == maxRetries) throw le;
                log.warn("Attempt {}/{} failed: {}. Retrying in {}ms...", 
                        attempt + 1, maxRetries + 1, e.getMessage(), delay);
                sleep(delay);
                delay = Math.min(delay * 2, 30000);
            }
        }
        throw lastException;
    }

    private static void sleep(long ms) throws LabConnectException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabConnectException(ErrorCode.INTERNAL_ERROR, "Interrupted during retry");
        }
    }

    /**
     * Wrap a checked exception in LabConnectException.
     */
    public static LabConnectException wrap(Exception e, ErrorCode defaultCode) {
        if (e instanceof LabConnectException) return (LabConnectException) e;
        return new LabConnectException(defaultCode, e.getMessage(), e);
    }

    /**
     * Execute and wrap checked exceptions.
     */
    public static <T> T unwrapChecked(Supplier<T> operation, ErrorCode errorCode) throws LabConnectException {
        try {
            return operation.get();
        } catch (LabConnectException e) {
            throw e;
        } catch (Exception e) {
            throw new LabConnectException(errorCode, "Operation failed", e);
        }
    }

    /**
     * Safe execution that never throws - returns error result.
     */
    public static <T> Result<T> safe(Supplier<T> operation) {
        try {
            return Result.success(operation.get());
        } catch (LabConnectException e) {
            return Result.failure(e);
        } catch (Exception e) {
            return Result.failure(new LabConnectException(ErrorCode.INTERNAL_ERROR, "Unexpected error", e));
        }
    }

    /**
     * Result wrapper for safe operations.
     */
    public static class Result<T> {
        private final T value;
        private final LabConnectException error;
        private final boolean success;

        private Result(T value, LabConnectException error) {
            this.value = value;
            this.error = error;
            this.success = error == null;
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(value, null);
        }

        public static <T> Result<T> failure(LabConnectException error) {
            return new Result<>(null, error);
        }

        public boolean isSuccess() { return success; }
        public boolean isFailure() { return !success; }
        public T getValue() { return value; }
        public LabConnectException getError() { return error; }
        public T orElse(T defaultValue) { return success ? value : defaultValue; }
        public T orElseThrow() { 
            if (success) return value;
            throw error;
        }
        public T orElseGet(java.util.function.Supplier<T> supplier) { return success ? value : supplier.get(); }
    }
}