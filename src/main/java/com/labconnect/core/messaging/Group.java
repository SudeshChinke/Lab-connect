package com.labconnect.core.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

public record Group(
        @JsonProperty("groupId") String groupId,
        @JsonProperty("name") String name,
        @JsonProperty("creatorId") String creatorId,
        @JsonProperty("members") Set<String> members,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("description") String description
) {
    public static Group create(String groupId, String name, String creatorId, String description) {
        return new Group(
                groupId,
                name,
                creatorId,
                new LinkedHashSet<>(Set.of(creatorId)),
                Instant.now(),
                description
        );
    }

    public Group addMember(String deviceId) {
        Set<String> newMembers = new LinkedHashSet<>(members);
        newMembers.add(deviceId);
        return new Group(groupId, name, creatorId, newMembers, createdAt, description);
    }

    public Group removeMember(String deviceId) {
        Set<String> newMembers = new LinkedHashSet<>(members);
        newMembers.remove(deviceId);
        return new Group(groupId, name, creatorId, newMembers, createdAt, description);
    }

    public boolean isMember(String deviceId) {
        return members.contains(deviceId);
    }

    public int memberCount() {
        return members.size();
    }
}