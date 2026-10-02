package com.labconnect.core.history;

import com.labconnect.core.history.HistoryManager.TransferRecord;
import com.labconnect.core.messaging.MessageStatus;
import com.labconnect.core.messaging.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HistoryManagerTest {

    private HistoryManager historyManager;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("labconnect-history-test");
        historyManager = new HistoryManager(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        historyManager.close();
        // Clean up temp directory
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
    }

    @Test
    @Timeout(10)
    void testAddAndGetMessages() {
        String chatId = "chat-1";
        TextMessage msg1 = TextMessage.create(chatId, "device-1", "Hello");
        TextMessage msg2 = TextMessage.create(chatId, "device-2", "Hi there");
        
        historyManager.addMessage(msg1);
        historyManager.addMessage(msg2);
        
        var messages = historyManager.getMessages(chatId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("Hello");
        assertThat(messages.get(1).content()).isEqualTo("Hi there");
    }

    @Test
    @Timeout(10)
    void testGetMessagesWithLimit() {
        String chatId = "chat-limit";
        for (int i = 0; i < 100; i++) {
            historyManager.addMessage(TextMessage.create(chatId, "device-1", "Message " + i));
        }
        
        var last10 = historyManager.getMessages(chatId, 10);
        assertThat(last10).hasSize(10);
        assertThat(last10.get(0).content()).isEqualTo("Message 90");
        assertThat(last10.get(9).content()).isEqualTo("Message 99");
    }

    @Test
    @Timeout(10)
    void testGetMessagesSince() {
        String chatId = "chat-since";
        Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
        
        // Add old message with old timestamp
        TextMessage oldMsg = new TextMessage(
                UUID.randomUUID(), chatId, "device-1", "Old message", 
                "text/plain", Instant.now().minusSeconds(600), null, MessageStatus.SENT
        );
        historyManager.addMessage(oldMsg);
        
        // Add recent message
        TextMessage recentMsg = new TextMessage(
                UUID.randomUUID(), chatId, "device-2", "Recent message", 
                "text/plain", Instant.now(), null, MessageStatus.SENT
        );
        historyManager.addMessage(recentMsg);
        
        var recentMessages = historyManager.getMessages(chatId, fiveMinutesAgo);
        assertThat(recentMessages).hasSize(1);
        assertThat(recentMessages.get(0).content()).isEqualTo("Recent message");
    }

    @Test
    @Timeout(10)
    void testMarkAsRead() {
        String chatId = "chat-read";
        TextMessage msg = TextMessage.create(chatId, "device-1", "Test");
        historyManager.addMessage(msg);
        
        historyManager.markAsRead(chatId, msg.messageId());
        
        var messages = historyManager.getMessages(chatId);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).status()).isEqualTo(MessageStatus.READ);
    }

    @Test
    @Timeout(10)
    void testDeleteMessage() {
        String chatId = "chat-delete";
        TextMessage msg1 = TextMessage.create(chatId, "device-1", "Keep");
        TextMessage msg2 = TextMessage.create(chatId, "device-2", "Delete");
        historyManager.addMessage(msg1);
        historyManager.addMessage(msg2);
        
        historyManager.deleteMessage(chatId, msg2.messageId());
        
        var messages = historyManager.getMessages(chatId);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Keep");
    }

    @Test
    @Timeout(10)
    void testClearChatHistory() {
        String chatId = "chat-clear";
        historyManager.addMessage(TextMessage.create(chatId, "device-1", "Test"));
        
        historyManager.clearChatHistory(chatId);
        
        var messages = historyManager.getMessages(chatId);
        assertThat(messages).isEmpty();
    }

    @Test
    @Timeout(10)
    void testTransferHistory() {
        TransferRecord record = new TransferRecord(
                "transfer-1", "test.txt", 1024, "COMPLETED", 1024,
                "device-1", "device-2", Instant.now(), Instant.now(), "sha256hash"
        );
        
        historyManager.addTransferRecord(record);
        
        var transfers = historyManager.getTransfersForDevice("device-1");
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).fileName()).isEqualTo("test.txt");
    }

    @Test
    @Timeout(10)
    void testTransferUpdate() {
        TransferRecord record = new TransferRecord(
                "transfer-update", "test.txt", 1024, "IN_PROGRESS", 512,
                "device-1", "device-2", Instant.now(), null, "sha256hash"
        );
        
        historyManager.addTransferRecord(record);
        
        // Update status
        TransferRecord updated = new TransferRecord(
                "transfer-update", "test.txt", 1024, "COMPLETED", 1024,
                "device-1", "device-2", Instant.now(), Instant.now(), "sha256hash"
        );
        historyManager.updateTransferRecord(updated);
        
        var transfers = historyManager.getTransfersForDevice("device-1");
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).status()).isEqualTo("COMPLETED");
        assertThat(transfers.get(0).bytesTransferred()).isEqualTo(1024);
    }

    @Test
    @Timeout(10)
    void testGetTransferById() {
        TransferRecord record = new TransferRecord(
                "transfer-get", "test.txt", 1024, "COMPLETED", 1024,
                "device-1", "device-2", Instant.now(), Instant.now(), "sha256hash"
        );
        
        historyManager.addTransferRecord(record);
        
        var found = historyManager.getTransfer("transfer-get");
        assertThat(found).isPresent();
        assertThat(found.get().fileName()).isEqualTo("test.txt");
    }

    @Test
    @Timeout(15)
    void testPersistenceAcrossRestart() throws IOException, InterruptedException {
        String chatId = "chat-persist";
        historyManager.addMessage(TextMessage.create(chatId, "device-1", "Persistent"));
        
        // Force flush to disk
        historyManager.flushAll();
        Thread.sleep(100);
        
        historyManager.close();
        
        // Reopen
        historyManager = new HistoryManager(tempDir);
        
        var messages = historyManager.getMessages(chatId);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Persistent");
    }
}