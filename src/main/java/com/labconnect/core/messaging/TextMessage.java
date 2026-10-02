package com.labconnect.core.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TextMessage(
        @JsonProperty("messageId") UUID messageId,
        @JsonProperty("chatId") String chatId,
        @JsonProperty("senderId") String senderId,
        @JsonProperty("content") String content,
        @JsonProperty("contentType") String contentType,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("replyTo") UUID replyTo,
        @JsonProperty("status") String status
) {
    public static TextMessage create(String chatId, String senderId, String content) {
        return new TextMessage(
                UUID.randomUUID(),
                chatId,
                senderId,
                content,
                "text/plain",
                Instant.now(),
                null,
                MessageStatus.SENT
        );
    }

    public static TextMessage createReply(String chatId, String senderId, String content, UUID replyTo) {
        return new TextMessage(
                UUID.randomUUID(),
                chatId,
                senderId,
                content,
                "text/plain",
                Instant.now(),
                replyTo,
                MessageStatus.SENT
        );
    }

    public TextMessage withStatus(String newStatus) {
        return new TextMessage(messageId, chatId, senderId, content, contentType, timestamp, replyTo, newStatus);
    }
}