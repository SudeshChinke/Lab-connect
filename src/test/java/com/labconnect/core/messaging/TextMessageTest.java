package com.labconnect.core.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TextMessageTest {

    @Test
    void testCreateMessage() {
        TextMessage msg = TextMessage.create("chat-1", "device-1", "Hello World");
        
        assertThat(msg.messageId()).isNotNull();
        assertThat(msg.chatId()).isEqualTo("chat-1");
        assertThat(msg.senderId()).isEqualTo("device-1");
        assertThat(msg.content()).isEqualTo("Hello World");
        assertThat(msg.contentType()).isEqualTo("text/plain");
        assertThat(msg.timestamp()).isNotNull();
        assertThat(msg.replyTo()).isNull();
        assertThat(msg.status()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void testCreateReply() {
        UUID replyTo = UUID.randomUUID();
        TextMessage msg = TextMessage.createReply("chat-1", "device-2", "Reply", replyTo);
        
        assertThat(msg.replyTo()).isEqualTo(replyTo);
        assertThat(msg.content()).isEqualTo("Reply");
    }

    @Test
    void testWithStatus() {
        TextMessage msg = TextMessage.create("chat-1", "device-1", "Hello");
        TextMessage delivered = msg.withStatus(MessageStatus.DELIVERED);
        
        assertThat(delivered.messageId()).isEqualTo(msg.messageId());
        assertThat(delivered.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(msg.status()).isEqualTo(MessageStatus.SENT); // Original unchanged
    }

    @Test
    void testMessageEqualityById() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        
        TextMessage msg1 = new TextMessage(id, "chat-1", "device-1", "Hello", "text/plain", now, null, MessageStatus.SENT);
        TextMessage msg2 = new TextMessage(id, "chat-1", "device-1", "Hello", "text/plain", now, null, MessageStatus.DELIVERED);
        
        // Records compare all fields, so different status = not equal
        // But they have the same messageId for deduplication purposes
        assertThat(msg1.messageId()).isEqualTo(msg2.messageId());
        assertThat(msg1).isNotEqualTo(msg2); // Different status = not equal
    }

    @Test
    void testMessageEqualitySameFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        
        TextMessage msg1 = new TextMessage(id, "chat-1", "device-1", "Hello", "text/plain", now, null, MessageStatus.SENT);
        TextMessage msg2 = new TextMessage(id, "chat-1", "device-1", "Hello", "text/plain", now, null, MessageStatus.SENT);
        
        assertThat(msg1).isEqualTo(msg2);
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());
    }
}