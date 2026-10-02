package com.labconnect.core.error;

import java.io.Serial;

public class LabConnectException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String userMessage;
    private final String technicalDetails;
    private final boolean recoverable;
    private final String recoveryHint;

    public LabConnectException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null, true, null);
    }

    public LabConnectException(ErrorCode errorCode, String userMessage) {
        this(errorCode, userMessage, null, true, null);
    }

    public LabConnectException(ErrorCode errorCode, String userMessage, Throwable cause) {
        this(errorCode, userMessage, cause, true, null);
    }

    public LabConnectException(ErrorCode errorCode, String userMessage, String recoveryHint) {
        this(errorCode, userMessage, null, true, recoveryHint);
    }

    public LabConnectException(ErrorCode errorCode, String userMessage, Throwable cause, boolean recoverable, String recoveryHint) {
        super(userMessage != null ? userMessage : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage != null ? userMessage : errorCode.getDefaultMessage();
        this.technicalDetails = cause != null ? cause.toString() : null;
        this.recoverable = recoverable;
        this.recoveryHint = recoveryHint;
    }

    public LabConnectException(ErrorCode errorCode, String userMessage, String recoveryHint, boolean recoverable) {
        this(errorCode, userMessage, null, recoverable, recoveryHint);
    }

    public ErrorCode getErrorCode() { return errorCode; }
    public String getUserMessage() { return userMessage; }
    public String getTechnicalDetails() { return technicalDetails; }
    public boolean isRecoverable() { return recoverable; }
    public String getRecoveryHint() { return recoveryHint; }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorCode.getCode()).append("] ").append(userMessage);
        if (recoveryHint != null) {
            sb.append(" | Hint: ").append(recoveryHint);
        }
        if (technicalDetails != null) {
            sb.append(" | Details: ").append(technicalDetails);
        }
        return sb.toString();
    }

    // Factory methods for common errors
    public static LabConnectException connectionFailed(String host, int port, Throwable cause) {
        return new LabConnectException(ErrorCode.CONNECTION_FAILED,
                "Could not connect to " + host + ":" + port,
                cause);
    }

    public static LabConnectException connectionTimeout(String host, int port) {
        return new LabConnectException(ErrorCode.CONNECTION_TIMEOUT,
                "Connection to " + host + ":" + port + " timed out",
                "Check if the device is online and the port is accessible");
    }

    public static LabConnectException connectionRefused(String host, int port) {
        return new LabConnectException(ErrorCode.CONNECTION_REFUSED,
                "Connection refused by " + host + ":" + port,
                "Verify the device is running LabConnect and the port is open");
    }

    public static LabConnectException fileNotFound(String fileName) {
        return new LabConnectException(ErrorCode.FILE_NOT_FOUND,
                "File not found: " + fileName,
                "Check if the file exists and the path is correct");
    }

    public static LabConnectException fileChecksumMismatch(String fileName) {
        return new LabConnectException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                "File integrity check failed for " + fileName,
                "The file may be corrupted. Try transferring again");
    }

    public static LabConnectException fileTooLarge(long size, long maxSize) {
        return new LabConnectException(ErrorCode.FILE_TOO_LARGE,
                "File size (" + formatBytes(size) + ") exceeds limit (" + formatBytes(maxSize) + ")",
                "Split the file or increase the size limit in settings");
    }

    public static LabConnectException diskFull() {
        return new LabConnectException(ErrorCode.DISK_FULL,
                "Not enough disk space to complete the operation",
                "Free up space and try again");
    }

    public static LabConnectException pairingRejected(String deviceName) {
        return new LabConnectException(ErrorCode.PAIRING_REJECTED,
                deviceName + " rejected the pairing request",
                "Ask the device owner to accept the pairing request");
    }

    public static LabConnectException untrustedDevice(String deviceId) {
        return new LabConnectException(ErrorCode.UNTRUSTED_DEVICE,
                "Device " + deviceId + " is not trusted",
                "Accept the pairing request on the remote device");
    }

    public static LabConnectException outOfMemory() {
        return new LabConnectException(ErrorCode.OUT_OF_MEMORY,
                "Not enough memory to complete the operation",
                "Close other applications or increase heap size",
                false);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }
}