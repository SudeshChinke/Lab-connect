package com.labconnect.core.messaging;

import com.labconnect.core.networking.ConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.UUID;

public final class GroupManager {
    private static final Logger log = LoggerFactory.getLogger(GroupManager.class);

    private final ConnectionManager connectionManager;
    private final String localDeviceId;
    private final ChatManager chatManager;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pendingInvites = new ConcurrentHashMap<>();
    private final Consumer<Group> onGroupCreated;
    private final Consumer<Group> onGroupUpdated;
    private final Consumer<String> onGroupDeleted;
    private final Consumer<GroupInvite> onInviteReceived;
    private final Consumer<GroupInvite> onInviteResponded;

    public GroupManager(ConnectionManager connectionManager,
                        String localDeviceId,
                        ChatManager chatManager,
                        Consumer<Group> onGroupCreated,
                        Consumer<Group> onGroupUpdated,
                        Consumer<String> onGroupDeleted,
                        Consumer<GroupInvite> onInviteReceived,
                        Consumer<GroupInvite> onInviteResponded) {
        this.connectionManager = connectionManager;
        this.localDeviceId = localDeviceId;
        this.chatManager = chatManager;
        this.onGroupCreated = onGroupCreated;
        this.onGroupUpdated = onGroupUpdated;
        this.onGroupDeleted = onGroupDeleted;
        this.onInviteReceived = onInviteReceived;
        this.onInviteResponded = onInviteResponded;
    }

    public Group createGroup(String name, String description) {
        String groupId = "GROUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Group group = Group.create(groupId, name, localDeviceId, description);
        groups.put(groupId, group);
        
        // Create corresponding chat
        ChatManager.Chat chat = chatManager.getChat(groupId).orElseGet(() -> {
            ChatManager.Chat newChat = new ChatManager.Chat(groupId, ChatManager.Chat.ChatType.GROUP, Set.of(localDeviceId));
            // Note: ChatManager needs a method to add this chat
            return newChat;
        });
        
        log.info("Created group: {} ({})", name, groupId);
        if (onGroupCreated != null) onGroupCreated.accept(group);
        return group;
    }

    public void inviteToGroup(String groupId, String targetDeviceId) {
        Group group = groups.get(groupId);
        if (group == null) {
            log.warn("Cannot invite to non-existent group: {}", groupId);
            return;
        }
        if (!group.isMember(localDeviceId)) {
            log.warn("Cannot invite to group {}: not a member", groupId);
            return;
        }

        GroupInvite invite = new GroupInvite(
                UUID.randomUUID(),
                groupId,
                group.name(),
                localDeviceId,
                targetDeviceId,
                Instant.now(),
                InviteStatus.PENDING
        );
        
        pendingInvites.computeIfAbsent(targetDeviceId, k -> ConcurrentHashMap.newKeySet()).add(groupId);
        
        // Send invite via connection manager
        sendInviteTo(targetDeviceId, invite);
        
        log.info("Invited {} to group {} ({})", targetDeviceId, group.name(), groupId);
    }

    public void acceptInvite(String groupId) {
        GroupInvite invite = findInvite(groupId);
        if (invite == null || invite.status() != InviteStatus.PENDING) {
            log.warn("No pending invite for group: {}", groupId);
            return;
        }
        
        Group group = groups.get(groupId);
        if (group == null) {
            // Create group locally from invite info
            group = new Group(groupId, invite.groupName(), invite.inviterId(), 
                    Set.of(localDeviceId), Instant.now(), "");
            groups.put(groupId, group);
        } else {
            group = group.addMember(localDeviceId);
            groups.put(groupId, group);
        }
        
        // Remove invite
        pendingInvites.getOrDefault(localDeviceId, Collections.emptySet()).remove(groupId);
        
        // Send acceptance
        GroupInvite accepted = invite.withStatus(InviteStatus.ACCEPTED);
        sendInviteResponse(invite.inviterId(), accepted);
        
        if (onInviteResponded != null) onInviteResponded.accept(accepted);
        log.info("Accepted invite to group: {} ({})", invite.groupName(), groupId);
    }

    public void rejectInvite(String groupId) {
        GroupInvite invite = findInvite(groupId);
        if (invite == null) return;
        
        GroupInvite rejected = invite.withStatus(InviteStatus.REJECTED);
        pendingInvites.getOrDefault(localDeviceId, Collections.emptySet()).remove(groupId);
        
        sendInviteResponse(invite.inviterId(), rejected);
        
        if (onInviteResponded != null) onInviteResponded.accept(rejected);
        log.info("Rejected invite to group: {} ({})", invite.groupName(), groupId);
    }

    public void leaveGroup(String groupId) {
        Group group = groups.get(groupId);
        if (group == null || !group.isMember(localDeviceId)) {
            log.warn("Cannot leave group {}: not a member", groupId);
            return;
        }
        
        group = group.removeMember(localDeviceId);
        if (group.memberCount() == 0) {
            groups.remove(groupId);
            if (onGroupDeleted != null) onGroupDeleted.accept(groupId);
            log.info("Group {} deleted (last member left)", groupId);
        } else {
            groups.put(groupId, group);
            if (onGroupUpdated != null) onGroupUpdated.accept(group);
            log.info("Left group: {} ({})", group.name(), groupId);
        }
    }

    public void sendGroupMessage(String groupId, String content) {
        Group group = groups.get(groupId);
        if (group == null || !group.isMember(localDeviceId)) {
            log.warn("Cannot send to group {}: not a member", groupId);
            return;
        }
        chatManager.sendGroupMessage(groupId, content);
    }

    public Optional<Group> getGroup(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public Collection<Group> getAllGroups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public void handleInvite(GroupInvite invite) {
        pendingInvites.computeIfAbsent(localDeviceId, k -> ConcurrentHashMap.newKeySet()).add(invite.groupId());
        if (onInviteReceived != null) onInviteReceived.accept(invite);
    }

    public void handleInviteResponse(GroupInvite invite) {
        if (invite.status() == InviteStatus.ACCEPTED) {
            Group group = groups.get(invite.groupId());
            if (group != null) {
                group = group.addMember(invite.inviteeId());
                groups.put(invite.groupId(), group);
                if (onGroupUpdated != null) onGroupUpdated.accept(group);
            }
        }
        if (onInviteResponded != null) onInviteResponded.accept(invite);
    }

    private GroupInvite findInvite(String groupId) {
        Set<String> groupIds = pendingInvites.get(localDeviceId);
        if (groupIds == null || !groupIds.contains(groupId)) return null;
        // In real implementation, store full invite objects
        return null;
    }

    private void sendInviteTo(String targetDeviceId, GroupInvite invite) {
        // Connection manager handles sending
        log.debug("Sending invite to {} for group {}", targetDeviceId, invite.groupId());
    }

    private void sendInviteResponse(String targetDeviceId, GroupInvite invite) {
        log.debug("Sending invite response to {} for group {}", targetDeviceId, invite.groupId());
    }

    public record GroupInvite(
            UUID inviteId,
            String groupId,
            String groupName,
            String inviterId,
            String inviteeId,
            Instant timestamp,
            InviteStatus status
    ) {
        public GroupInvite withStatus(InviteStatus newStatus) {
            return new GroupInvite(inviteId, groupId, groupName, inviterId, inviteeId, timestamp, newStatus);
        }
    }

    public enum InviteStatus {
        PENDING, ACCEPTED, REJECTED
    }
}