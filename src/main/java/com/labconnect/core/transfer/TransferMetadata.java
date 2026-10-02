package com.labconnect.core.transfer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TransferMetadata(
        @JsonProperty("transferId") UUID transferId,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("fileSize") long fileSize,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("chunkSize") int chunkSize,
        @JsonProperty("totalChunks") long totalChunks,
        @JsonProperty("senderId") String senderId,
        @JsonProperty("receiverId") String receiverId,
        @JsonProperty("createdAt") Instant createdAt
) {
    public static TransferMetadata create(UUID transferId, String fileName, long fileSize,
                                          String mimeType, String sha256, int chunkSize,
                                          String senderId, String receiverId) {
        long totalChunks = (fileSize + chunkSize - 1) / chunkSize;
        return new TransferMetadata(
                transferId, fileName, fileSize, mimeType, sha256,
                chunkSize, totalChunks, senderId, receiverId, Instant.now()
        );
    }
}