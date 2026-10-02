package com.labconnect.core.messaging;

import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.networking.Connection;
import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatManager {
    private static final Logger log = LoggerFactory.getLogger(ChatManager.class);

    private final ConnectionManager connectionManager;
    private final String localDeviceId;
    private final Map<String, Chat> chats = new ConcurrentHashMap<>();
    private final Map<UUID, PendingAck> pendingAcks = new ConcurrentHashMap<>();
    private final Consumer<TextMessage> onMessageReceived;
    private final Consumer<TextMessage> onMessageSent;
    private final Consumer<TextMessage> onMessageDelivered;
    private final Consumer<TextMessage> onMessageRead;

    public ChatManager(ConnectionManager connectionManager,
                       String localDeviceId,
                       Consumer<TextMessage> onMessageReceived,
                       Consumer<TextMessage> onMessageSent,
                       Consumer<TextMessage> onMessageDelivered,
                       Consumer<TextMessage> onMessageRead) {
        this.connectionManager = connectionManager;
        this.localDeviceId = localDeviceId;
        this.onMessageReceived = onMessageReceived;
        this.onMessageSent = onMessageSent;
        this.onMessageDelivered = onMessageDelivered;
        this.onMessageRead = onMessageRead;
    }

    public void createGroup(String groupName) {
        String groupId = "GROUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Chat group = new Chat(groupId, Chat.ChatType.GROUP, Set.of(localDeviceId));
        chats.put(groupId, group);
        log.info("Created group: {} ({})", groupName, groupId);
    }

    public void sendMessage(String targetDeviceId, String content) {
        Connection conn = connectionManager.getConnection(targetDeviceId).orElse(null);
        if (conn == null || !conn.isConnected()) {
            log.warn("Cannot send message to {}: not connected", targetDeviceId);
            return;
        }

        TextMessage message = TextMessage.create(
            createChatId(localDeviceId, targetDeviceId), 
            localDeviceId, 
            content
        );
        
        Chat chat = chats.computeIfAbsent(
            createChatId(localDeviceId, targetDeviceId), 
            k -> new Chat(k, Chat.ChatType.DIRECT, Set.of(localDeviceId, targetDeviceId))
        );
        chat.addMessage(message);
        
        sendTextMessage(conn, message);
        
        PendingAck pending = new PendingAck(message);
        pendingAcks.put(message.messageId(), pending);
        
        if (onMessageSent != null) {
            onMessageSent.accept(message);
        }
    }

    public void sendGroupMessage(String groupId, String content) {
        Chat group = chats.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return;
        }

        TextMessage message = TextMessage.create(groupId, localDeviceId, content);
        group.addMessage(message);

        connectionManager.getAllConnections().forEach(conn -> {
            if (conn.isConnected() && group.getParticipants().contains(conn.getRemoteDeviceId())) {
                sendGroupMessage(conn, message);
            }
        });
    }

    public void handleFrame(FrameCodec.Frame frame) {
        // Handle incoming messages
    }

    private String createChatId(String deviceId1, String deviceId2) {
        return deviceId1.compareTo(deviceId2) < 0 
            ? deviceId1 + "-" + deviceId2 
            : deviceId2 + "-" + deviceId1;
    }

    private void sendTextMessage(Connection conn, TextMessage message) {
        // Send text message frame
    }

    private void sendGroupMessage(Connection conn, TextMessage message) {
        // Send group message frame
    }

    public static class Chat {
        public enum ChatType { DIRECT, GROUP, BROADCAST }

        private final String chatId;
        private final ChatType type;
        private final Set<String> participants;
        private final List<TextMessage> messages = new ArrayList<>();

        public Chat(String chatId, ChatType type, Set<String> participants) {
            this.chatId = chatId;
            this.type = type;
            this.participants = participants;
        }

        public void addMessage(TextMessage message) {
            synchronized (messages) {
                messages.add(message);
            }
        }

        public List<TextMessage> getMessages() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        public String getChatId() { return chatId; }
        public ChatType getType() { return type; }
        public Set<String> getParticipants() { return participants; }
    }

    private static class PendingAck {
        private final TextMessage message;

        PendingAck(TextMessage message) {
            this.message = message;
        }

        TextMessage message() { return message; }
        void complete(boolean success) { /* ... */ }
    }

    public Optional<Chat> getChat(String chatId) {
        return Optional.ofNullable(chats.get(chatId));
    }

    public Collection<Chat> getAllChats() {
        return Collections.unmodifiableCollection(chats.values());
    }
}