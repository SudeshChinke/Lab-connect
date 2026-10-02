package com.labconnect.core.transfer;

public enum TransferPriority {
    LOW(0),
    NORMAL(5),
    HIGH(10),
    CRITICAL(20);
    
    private final int value;
    
    TransferPriority(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
}