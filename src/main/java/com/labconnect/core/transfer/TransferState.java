package com.labconnect.core.transfer;

public enum TransferState {
    PENDING,        // Request sent, waiting for acceptance
    ACCEPTED,       // Receiver accepted, starting transfer
    IN_PROGRESS,    // Chunks being sent
    PAUSED,         // Paused by user
    COMPLETED,      // All chunks sent and verified
    FAILED,         // Error occurred
    CANCELLED,      // Cancelled by user
    VERIFYING       // Checksum verification in progress
}