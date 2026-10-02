package com.labconnect.core.error;

public enum ErrorCode {
    // Network errors (1000-1999)
    CONNECTION_FAILED(1000, "Unable to establish connection"),
    CONNECTION_TIMEOUT(1001, "Connection timed out"),
    CONNECTION_REFUSED(1002, "Connection refused by remote host"),
    CONNECTION_LOST(1003, "Connection lost unexpectedly"),
    HOST_UNREACHABLE(1004, "Host is unreachable"),
    NETWORK_UNREACHABLE(1005, "Network is unreachable"),
    PORT_UNREACHABLE(1006, "Port is unreachable"),
    DNS_RESOLUTION_FAILED(1007, "Failed to resolve hostname"),
    
    // Discovery errors (2000-2999)
    DISCOVERY_FAILED(2000, "Device discovery failed"),
    MULTICAST_FAILED(2001, "Multicast announcement failed"),
    BROADCAST_FAILED(2002, "Broadcast announcement failed"),
    DEVICE_NOT_FOUND(2003, "Device not found"),
    DUPLICATE_DEVICE_ID(2004, "Duplicate device ID detected"),
    
    // Protocol errors (3000-3999)
    PROTOCOL_VERSION_MISMATCH(3000, "Protocol version mismatch"),
    INVALID_FRAME_FORMAT(3001, "Invalid frame format"),
    INVALID_MESSAGE_TYPE(3002, "Unknown message type"),
    MESSAGE_TOO_LARGE(3003, "Message exceeds maximum size"),
    INVALID_MESSAGE_ID(3004, "Invalid or duplicate message ID"),
    MESSAGE_DECODE_FAILED(3005, "Failed to decode message"),
    MESSAGE_ENCODE_FAILED(3006, "Failed to encode message"),
    
    // Messaging errors (4000-4999)
    MESSAGE_DELIVERY_FAILED(4000, "Failed to deliver message"),
    MESSAGE_ACK_TIMEOUT(4001, "Message acknowledgement timeout"),
    MESSAGE_DUPLICATE(4002, "Duplicate message received"),
    CHAT_NOT_FOUND(4003, "Chat not found"),
    MESSAGE_RECALL_FAILED(4004, "Failed to recall message"),
    
    // File transfer errors (5000-5999)
    FILE_NOT_FOUND(5000, "File not found"),
    FILE_ACCESS_DENIED(5001, "Access denied to file"),
    FILE_TOO_LARGE(5002, "File size exceeds limit"),
    FILE_TRANSFER_FAILED(5003, "File transfer failed"),
    FILE_TRANSFER_CANCELLED(5004, "File transfer cancelled"),
    FILE_CHECKSUM_MISMATCH(5005, "File checksum mismatch - data corrupted"),
    FILE_RESUME_FAILED(5006, "Failed to resume transfer"),
    FILE_PARTIAL_CORRUPT(5007, "Partial file is corrupted"),
    INSUFFICIENT_DISK_SPACE(5008, "Insufficient disk space"),
    FILE_ALREADY_EXISTS(5009, "File already exists"),
    
    // Security errors (6000-6999)
    PAIRING_FAILED(6000, "Device pairing failed"),
    PAIRING_REJECTED(6001, "Pairing request rejected"),
    PAIRING_TIMEOUT(6002, "Pairing request timed out"),
    UNTRUSTED_DEVICE(6003, "Device is not trusted"),
    CERTIFICATE_INVALID(6004, "Certificate is invalid"),
    TLS_HANDSHAKE_FAILED(6004, "TLS handshake failed"),
    ENCRYPTION_FAILED(6005, "Encryption failed"),
    DECRYPTION_FAILED(6006, "Decryption failed"),
    KEY_GENERATION_FAILED(6007, "Key generation failed"),
    
    // Group errors (7000-7999)
    GROUP_NOT_FOUND(7000, "Group not found"),
    GROUP_FULL(7001, "Group has reached maximum members"),
    NOT_GROUP_MEMBER(7002, "Not a member of this group"),
    GROUP_CREATION_FAILED(7003, "Failed to create group"),
    
    // System errors (9000-9999)
    OUT_OF_MEMORY(9000, "Out of memory"),
    DISK_FULL(9001, "Disk full"),
    THREAD_POOL_EXHAUSTED(9002, "Thread pool exhausted"),
    CONFIGURATION_ERROR(9003, "Configuration error"),
    INTERNAL_ERROR(9999, "Internal error occurred");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() { return code; }
    public String getDefaultMessage() { return defaultMessage; }
    
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) return ec;
        }
        return INTERNAL_ERROR;
    }
}