package com.labconnect.core.protocol;

public enum MessageType {
    // Control (0x10-0x1F)
    HELLO(0x10),
    ACK(0x11),
    ERROR(0x12),
    GOODBYE(0x13),
    HEARTBEAT(0x14),

    // Discovery (0x20-0x2F) - UDP only
    DEVICE_ANNOUNCE(0x20),
    DEVICE_QUERY(0x21),
    DEVICE_RESPONSE(0x22),

    // Messaging (0x30-0x3F)
    TEXT_MESSAGE(0x30),
    MESSAGE_ACK(0x31),
    MESSAGE_READ(0x32),
    GROUP_MESSAGE(0x33),
    BROADCAST_MESSAGE(0x34),
    MESSAGE_RECALL(0x35),

    // File Transfer (0x40-0x4F)
    FILE_REQUEST(0x40),
    FILE_ACCEPT(0x41),
    FILE_REJECT(0x42),
    FILE_CHUNK(0x43),
    FILE_CHUNK_ACK(0x44),
    FILE_COMPLETE(0x45),
    FILE_VERIFIED(0x46),
    FILE_CANCEL(0x47),
    FILE_RESUME(0x48),
    FILE_PAUSE(0x49),

    // Security (0x50-0x5F)
    PAIR_REQUEST(0x50),
    PAIR_ACCEPT(0x51),
    PAIR_REJECT(0x52),
    KEY_ROTATE(0x53);

    private final byte value;

    MessageType(int value) {
        this.value = (byte) value;
    }

    public byte value() { return value; }

    public static MessageType fromValue(byte value) {
        for (MessageType type : values()) {
            if (type.value == value) return type;
        }
        return null;
    }

    public boolean isControl() { return value >= 0x10 && value <= 0x1F; }
    public boolean isDiscovery() { return value >= 0x20 && value <= 0x2F; }
    public boolean isMessaging() { return value >= 0x30 && value <= 0x3F; }
    public boolean isFileTransfer() { return value >= 0x40 && value <= 0x4F; }
    public boolean isSecurity() { return value >= 0x50 && value <= 0x5F; }
}