package com.labconnect.core.history;

import com.labconnect.core.messaging.MessageStatus;
import com.labconnect.core.messaging.TextMessage;
import com.labconnect.core.transfer.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public final class HistoryManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HistoryManager.class);

    private final Path historyDir;
    private final Path messagesDir;
    private final Path transfersDir;
    
    private final Map<String, List<TextMessage>> messageCache = new ConcurrentHashMap<>();
    private final Map<String, List<TransferRecord>> transferCache = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService flushExecutor = Executors.newSingleThreadScheduledExecutor();
    private final int maxMessagesPerChat = 10000;
    private final int maxTransfersPerDevice = 1000;

    public HistoryManager(Path historyDir) throws IOException {
        this.historyDir = historyDir;
        this.messagesDir = historyDir.resolve("messages");
        this.transfersDir = historyDir.resolve("transfers");
        
        Files.createDirectories(messagesDir);
        Files.createDirectories(transfersDir);
        
        loadCache();
        
        // Periodic flush to disk
        flushExecutor.scheduleAtFixedRate(this::flushAll, 30, 30, TimeUnit.SECONDS);
        
        log.info("HistoryManager initialized at {}", historyDir);
    }

    // === Message History ===

    public void addMessage(TextMessage message) {
        String chatId = message.chatId();
        messageCache.computeIfAbsent(chatId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
        
        // Trim if needed
        List<TextMessage> messages = messageCache.get(chatId);
        if (messages.size() > maxMessagesPerChat) {
            int toRemove = messages.size() - maxMessagesPerChat;
            messages.subList(0, toRemove).clear();
        }
    }

    public List<TextMessage> getMessages(String chatId) {
        return messageCache.getOrDefault(chatId, Collections.emptyList())
                .stream()
                .sorted(Comparator.comparing(TextMessage::timestamp))
                .collect(Collectors.toList());
    }

    public List<TextMessage> getMessages(String chatId, Instant since) {
        return getMessages(chatId).stream()
                .filter(m -> m.timestamp().isAfter(since))
                .collect(Collectors.toList());
    }

    public List<TextMessage> getMessages(String chatId, int limit) {
        List<TextMessage> messages = getMessages(chatId);
        if (messages.size() <= limit) return messages;
        return messages.subList(messages.size() - limit, messages.size());
    }

    public void markAsRead(String chatId, UUID messageId) {
        List<TextMessage> messages = messageCache.get(chatId);
        if (messages != null) {
            messages.stream()
                    .filter(m -> m.messageId().equals(messageId))
                    .forEach(m -> {
                        // Create new message with updated status
                        int idx = messages.indexOf(m);
                        if (idx >= 0) {
                            messages.set(idx, m.withStatus(MessageStatus.READ));
                        }
                    });
        }
    }

    public void deleteMessage(String chatId, UUID messageId) {
        List<TextMessage> messages = messageCache.get(chatId);
        if (messages != null) {
            messages.removeIf(m -> m.messageId().equals(messageId));
        }
    }

    public void clearChatHistory(String chatId) {
        messageCache.remove(chatId);
        try {
            Files.deleteIfExists(messagesDir.resolve(chatId + ".json"));
        } catch (IOException e) {
            log.warn("Failed to delete chat history file", e);
        }
    }

    // === Transfer History ===

    public void addTransferRecord(TransferRecord record) {
        String key = record.transferId();
        transferCache.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
    }

    public void updateTransferRecord(TransferRecord record) {
        String key = record.transferId();
        List<TransferRecord> records = transferCache.get(key);
        if (records != null) {
            records.removeIf(r -> r.transferId().equals(record.transferId()));
            records.add(record);
        }
    }

    public List<TransferRecord> getTransfersForDevice(String deviceId) {
        return transferCache.values().stream()
                .flatMap(List::stream)
                .filter(r -> r.senderId().equals(deviceId) || r.receiverId().equals(deviceId))
                .sorted(Comparator.comparing(TransferRecord::createdAt).reversed())
                .collect(Collectors.toList());
    }

    public List<TransferRecord> getAllTransfers() {
        return transferCache.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(TransferRecord::createdAt).reversed())
                .collect(Collectors.toList());
    }

    public Optional<TransferRecord> getTransfer(String transferId) {
        List<TransferRecord> records = transferCache.get(transferId);
        if (records == null) return Optional.empty();
        return records.stream()
                .filter(r -> r.transferId().equals(transferId))
                .findFirst();
    }

    // === Persistence ===

    private void loadCache() {
        loadMessages();
        loadTransfers();
    }

    private void loadMessages() {
        try (var stream = Files.list(messagesDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String json = Files.readString(path);
                            String chatId = path.getFileName().toString().replace(".json", "");
                            parseMessagesJson(chatId, json);
                            log.debug("Loaded message history for chat: {}", chatId);
                        } catch (IOException e) {
                            log.warn("Failed to load message history from {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list message history files", e);
        }
    }

    private void parseMessagesJson(String chatId, String json) {
        // Simple JSON parsing for message array
        // Format: [{"messageId":"...","chatId":"...","senderId":"...","content":"...","contentType":"...","timestamp":"...","replyTo":"...","status":"..."},...]
        try {
            String content = json.trim();
            if (!content.startsWith("[") || !content.endsWith("]")) return;
            
            String inner = content.substring(1, content.length() - 1).trim();
            if (inner.isEmpty()) return;
            
            // Split by "}," but be careful with nested structures
            // Simple approach: split by "},{" 
            String[] messageJsons = inner.split("\\}\\s*,\\s*\\{");
            for (int i = 0; i < messageJsons.length; i++) {
                String msgJson = messageJsons[i];
                if (i == 0 && !msgJson.startsWith("{")) msgJson = "{" + msgJson;
                if (i == messageJsons.length - 1 && !msgJson.endsWith("}")) msgJson = msgJson + "}";
                else msgJson = "{" + msgJson + "}";
                
                TextMessage msg = parseMessageJson(msgJson);
                if (msg != null && msg.chatId().equals(chatId)) {
                    messageCache.computeIfAbsent(chatId, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(msg);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse messages JSON for chat {}", chatId, e);
        }
    }

    private TextMessage parseMessageJson(String json) {
        try {
            String messageId = extractJsonField(json, "messageId");
            String chatId = extractJsonField(json, "chatId");
            String senderId = extractJsonField(json, "senderId");
            String content = extractJsonField(json, "content");
            String contentType = extractJsonField(json, "contentType");
            String timestampStr = extractJsonField(json, "timestamp");
            String replyTo = extractJsonField(json, "replyTo");
            String status = extractJsonField(json, "status");
            
            if (messageId == null || chatId == null || senderId == null || content == null) {
                return null;
            }
            
            Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : Instant.now();
            UUID replyToUuid = (replyTo != null && !replyTo.equals("null")) ? UUID.fromString(replyTo) : null;
            String msgStatus = (status != null) ? status : MessageStatus.SENT;
            
            return new TextMessage(
                    UUID.fromString(messageId), chatId, senderId, content, 
                    contentType != null ? contentType : "text/plain", 
                    timestamp, replyToUuid, 
                    msgStatus
            );
        } catch (Exception e) {
            log.debug("Failed to parse message JSON", e);
            return null;
        }
    }

    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        Matcher matcher = Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Handle non-string values (numbers, booleans, null)
        String pattern2 = "\"" + field + "\"\\s*:\\s*([^,\\}]+)";
        Matcher matcher2 = Pattern.compile(pattern2).matcher(json);
        if (matcher2.find()) {
            return matcher2.group(1).trim();
        }
        return null;
    }

    private void loadTransfers() {
        try (var stream = Files.list(transfersDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String json = Files.readString(path);
                            // Parse and add to cache
                        } catch (IOException e) {
                            log.warn("Failed to load transfer history from {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list transfer history files", e);
        }
    }

    public void flushAll() {
        flushMessages();
        flushTransfers();
    }

    private void doFlushAll() {
        flushMessages();
        flushTransfers();
    }

    private void flushMessages() {
        messageCache.forEach((chatId, messages) -> {
            Path path = messagesDir.resolve(chatId + ".json");
            try {
                // Write messages as JSON array
                // Simplified - in production use Jackson
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < messages.size(); i++) {
                    TextMessage m = messages.get(i);
                    sb.append("  {\"messageId\":\"").append(m.messageId()).append("\",")
                            .append("\"chatId\":\"").append(m.chatId()).append("\",")
                            .append("\"senderId\":\"").append(m.senderId()).append("\",")
                            .append("\"content\":\"").append(escapeJson(m.content())).append("\",")
                            .append("\"contentType\":\"").append(m.contentType()).append("\",")
                            .append("\"timestamp\":\"").append(m.timestamp()).append("\",")
                            .append("\"replyTo\":").append(m.replyTo() == null ? "null" : "\"" + m.replyTo() + "\"").append(",")
                            .append("\"status\":\"").append(m.status()).append("\"")
                            .append("}");
                    if (i < messages.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                Files.writeString(Path.of(path.toString()), sb.toString());
            } catch (IOException e) {
                log.warn("Failed to flush messages for chat {}", chatId, e);
            }
        });
    }

    private void flushTransfers() {
        transferCache.forEach((transferId, records) -> {
            Path path = transfersDir.resolve(transferId + ".json");
            try {
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < records.size(); i++) {
                    TransferRecord r = records.get(i);
                    sb.append("  {\"transferId\":\"").append(r.transferId()).append("\",")
                            .append("\"fileName\":\"").append(r.fileName()).append("\",")
                            .append("\"fileSize\":").append(r.fileSize()).append(",")
                            .append("\"senderId\":\"").append(r.senderId()).append("\",")
                            .append("\"receiverId\":\"").append(r.receiverId()).append("\",")
                            .append("\"status\":\"").append(r.status()).append("\",")
                            .append("\"bytesTransferred\":").append(r.bytesTransferred()).append(",")
                            .append("\"createdAt\":\"").append(r.createdAt()).append("\",")
                            .append("\"completedAt\":").append(r.completedAt() == null ? "null" : "\"" + r.completedAt() + "\"").append(",")
                            .append("\"sha256\":\"").append(r.sha256()).append("\"")
                            .append("}");
                    if (i < records.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                Files.writeString(Path.of(path.toString()), sb.toString());
            } catch (IOException e) {
                log.warn("Failed to flush transfer {}", transferId, e);
            }
        });
    }

    private String escapeJson(String s) {
        return s.replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\\", "\\\\");
    }

    @Override
    public void close() {
        doFlushAll();
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record TransferRecord(
            String transferId,
            String fileName,
            long fileSize,
            String status,
            long bytesTransferred,
            String senderId,
            String receiverId,
            Instant createdAt,
            Instant completedAt,
            String sha256
    ) {}
}