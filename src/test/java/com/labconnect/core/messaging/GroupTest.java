package com.labconnect.core.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class GroupTest {

    @Test
    void testCreateGroup() {
        Group group = Group.create("GROUP-ABC123", "Test Group", "DEVICE-1", "A test group");
        
        assertThat(group.groupId()).isEqualTo("GROUP-ABC123");
        assertThat(group.name()).isEqualTo("Test Group");
        assertThat(group.creatorId()).isEqualTo("DEVICE-1");
        assertThat(group.members()).containsExactly("DEVICE-1");
        assertThat(group.description()).isEqualTo("A test group");
        assertThat(group.createdAt()).isNotNull();
        assertThat(group.memberCount()).isEqualTo(1);
    }

    @Test
    void testAddMember() {
        Group group = Group.create("GROUP-ABC123", "Test Group", "DEVICE-1", "");
        
        Group updated = group.addMember("DEVICE-2");
        
        assertThat(updated.memberCount()).isEqualTo(2);
        assertThat(updated.members()).contains("DEVICE-1", "DEVICE-2");
        assertThat(updated.groupId()).isEqualTo(group.groupId());
    }

    @Test
    void testRemoveMember() {
        Group group = Group.create("GROUP-ABC123", "Test Group", "DEVICE-1", "");
        Group withTwo = group.addMember("DEVICE-2");
        
        Group removed = withTwo.removeMember("DEVICE-2");
        
        assertThat(removed.memberCount()).isEqualTo(1);
        assertThat(removed.members()).containsExactly("DEVICE-1");
    }

    @Test
    void testIsMember() {
        Group group = Group.create("GROUP-ABC123", "Test Group", "DEVICE-1", "");
        Group withTwo = group.addMember("DEVICE-2");
        
        assertThat(withTwo.isMember("DEVICE-1")).isTrue();
        assertThat(withTwo.isMember("DEVICE-2")).isTrue();
        assertThat(withTwo.isMember("DEVICE-3")).isFalse();
    }

    @Test
    void testImmutability() {
        Group group = Group.create("GROUP-ABC123", "Test Group", "DEVICE-1", "");
        Group withTwo = group.addMember("DEVICE-2");
        
        // Original unchanged
        assertThat(group.memberCount()).isEqualTo(1);
        assertThat(group.members()).containsExactly("DEVICE-1");
    }
}