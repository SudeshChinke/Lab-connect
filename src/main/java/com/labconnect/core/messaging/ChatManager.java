package com.labconnect.core.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.networking.Connection;
import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatManager {
    private static final Logger log = LoggerFactory.getLogger(ChatManager.class);

    private final ConnectionManager connectionManager;
    private final String localDeviceId;
    private final Map<String, Chat> chats = new ConcurrentHashMap<>();
    private final Map<UUID, PendingAck> pendingAcks = new ConcurrentHashMap<>();
    private final MessageDeduplicator deduplicator = new MessageDeduplicator(10_000);
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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

    /**
     * Sends a direct text message to a connected peer.
     *
     * @return true when the frame was queued on the wire, false when there is
     *         no live connection to the target or the message could not be
     *         serialized.
     */
    public boolean sendMessage(String targetDeviceId, String content) {
        Connection conn = connectionManager.getConnection(targetDeviceId).orElse(null);
        if (conn == null || !conn.isConnected()) {
            log.warn("Cannot send message to {}: not connected", targetDeviceId);
            return false;
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
        
        // Register the PendingAck before the frame goes out: a fast peer can
        // ack before sendTextMessage() returns.
        pendingAcks.put(message.messageId(), new PendingAck(message));
        if (!sendTextMessage(conn, message)) {
            pendingAcks.remove(message.messageId());
            return false;
        }
        
        if (onMessageSent != null) {
            onMessageSent.accept(message);
        }
        return true;
    }

    /**
     * Broadcasts a message to every connected member of the group.
     *
     * @return true when the frame was queued for at least one member.
     */
    public boolean sendGroupMessage(String groupId, String content) {
        Chat group = chats.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return false;
        }

        TextMessage message = TextMessage.create(groupId, localDeviceId, content);
        group.addMessage(message);

        boolean sent = false;
        for (Connection conn : connectionManager.getAllConnections()) {
            String member = conn.getRemoteDeviceId();
            if (conn.isConnected() && member != null && group.getParticipants().contains(member)) {
                sent |= sendGroupMessage(conn, message);
            }
        }
        if (sent && onMessageSent != null) {
            onMessageSent.accept(message);
        }
        return sent;
    }

    /**
     * Entry point for every inbound messaging frame; routed here by
     * DesktopService from the ConnectionManager's onFrameReceived callback.
     */
    public void handleFrame(FrameCodec.Frame frame) {
        MessageType type = MessageType.fromValue(frame.type());
        if (type == null) {
            log.warn("ChatManager received unknown frame type 0x{}",
                    Integer.toHexString(frame.type() & 0xFF));
            return;
        }
        switch (type) {
            case TEXT_MESSAGE -> handleInboundMessage(frame, Chat.ChatType.DIRECT);
            case GROUP_MESSAGE -> handleInboundMessage(frame, Chat.ChatType.GROUP);
            case MESSAGE_ACK -> handleMessageAck(frame);
            case MESSAGE_READ -> handleMessageRead(frame);
            default -> log.debug("ChatManager ignoring frame type {}", type);
        }
    }

    private void handleInboundMessage(FrameCodec.Frame frame, Chat.ChatType chatType) {
        TextMessage message = readMessage(frame);
        if (message == null) {
            return;
        }

        if (deduplicator.isDuplicate(message.messageId())) {
            // Already delivered to the app; fall through so the sender still
            // gets an ack (its previous ack may have been lost).
            log.debug("Duplicate message {}, re-acknowledging", message.messageId());
        } else {
            Chat chat = chats.computeIfAbsent(message.chatId(), id ->
                    new Chat(id, chatType, Set.of(localDeviceId, message.senderId())));
            chat.addMessage(message);
            if (onMessageReceived != null) {
                onMessageReceived.accept(message);
            }
        }
        acknowledge(message.senderId(), frame.getMessageIdUUID());
    }

    private void handleMessageAck(FrameCodec.Frame frame) {
        try {
            JsonNode node = mapper.readTree(frame.payload());
            JsonNode original = node.get("originalMessageId");
            if (original == null || !original.isTextual()) {
                log.warn("MESSAGE_ACK without originalMessageId");
                return;
            }
            UUID originalId = UUID.fromString(original.asText());
            PendingAck pending = pendingAcks.remove(originalId);
            if (pending == null) {
                log.debug("No pending ack for message {}", originalId);
                return;
            }
            boolean success = !node.has("status")
                    || !MessageStatus.FAILED.equals(node.get("status").asText());
            pending.complete(success);
            if (success && onMessageDelivered != null) {
                onMessageDelivered.accept(pending.message().withStatus(MessageStatus.DELIVERED));
            }
        } catch (Exception e) {
            log.warn("Malformed MESSAGE_ACK: {}", e.getMessage());
        }
    }

    private void handleMessageRead(FrameCodec.Frame frame) {
        try {
            JsonNode node = mapper.readTree(frame.payload());
            JsonNode original = node.get("originalMessageId");
            if (original == null || !original.isTextual()) {
                return;
            }
            PendingAck pending = pendingAcks.get(UUID.fromString(original.asText()));
            if (pending != null && onMessageRead != null) {
                onMessageRead.accept(pending.message().withStatus(MessageStatus.READ));
            }
        } catch (Exception e) {
            log.warn("Malformed MESSAGE_READ: {}", e.getMessage());
        }
    }

    /** Sends a MESSAGE_ACK for a message back to its sender, when reachable. */
    private void acknowledge(String senderId, UUID originalMessageId) {
        Connection conn = connectionManager.getConnection(senderId).orElse(null);
        if (conn == null || !conn.isConnected()) {
            log.debug("No connection to {} to acknowledge {}", senderId, originalMessageId);
            return;
        }
        String payload = "{\"originalMessageId\":\"" + originalMessageId
                + "\",\"status\":\"" + MessageStatus.DELIVERED
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}";
        connectionManager.sendFrame(conn, FrameCodec.Frame.create(
                MessageType.MESSAGE_ACK.value(), (byte) 0, UUID.randomUUID(),
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    private TextMessage readMessage(FrameCodec.Frame frame) {
        if (frame.payload().length == 0) {
            log.warn("Empty payload for frame type 0x{}", Integer.toHexString(frame.type() & 0xFF));
            return null;
        }
        try {
            return mapper.readValue(frame.payload(), TextMessage.class);
        } catch (IOException e) {
            log.warn("Malformed message payload: {}", e.getMessage());
            return null;
        }
    }

    private String createChatId(String deviceId1, String deviceId2) {
        return deviceId1.compareTo(deviceId2) < 0 
            ? deviceId1 + "-" + deviceId2 
            : deviceId2 + "-" + deviceId1;
    }

    private boolean sendTextMessage(Connection conn, TextMessage message) {
        return sendMessageFrame(conn, MessageType.TEXT_MESSAGE, message);
    }

    private boolean sendGroupMessage(Connection conn, TextMessage message) {
        return sendMessageFrame(conn, MessageType.GROUP_MESSAGE, message);
    }

    /** Serializes a TextMessage to JSON and queues it on the connection. */
    private boolean sendMessageFrame(Connection conn, MessageType type, TextMessage message) {
        try {
            byte[] payload = mapper.writeValueAsBytes(message);
            // The frame id doubles as the ack correlation id: the receiver
            // echoes it back in MESSAGE_ACK so the PendingAck can settle.
            connectionManager.sendFrame(conn, FrameCodec.Frame.create(
                    type.value(), (byte) 0, message.messageId(), payload));
            return true;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message {}", message.messageId(), e);
            return false;
        }
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
        private volatile boolean success;

        PendingAck(TextMessage message) {
            this.message = message;
        }

        TextMessage message() { return message; }
        void complete(boolean success) { this.success = success; }
        boolean isSuccessful() { return success; }
    }

    public Optional<Chat> getChat(String chatId) {
        return Optional.ofNullable(chats.get(chatId));
    }

    public Collection<Chat> getAllChats() {
        return Collections.unmodifiableCollection(chats.values());
    }
}