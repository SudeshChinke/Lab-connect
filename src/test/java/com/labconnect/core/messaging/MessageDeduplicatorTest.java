package com.labconnect.core.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MessageDeduplicatorTest {

    @Test
    @Timeout(5)
    void testDuplicateDetection() {
        MessageDeduplicator dedup = new MessageDeduplicator(100);
        UUID id = UUID.randomUUID();

        // First arrival - not duplicate
        assertThat(dedup.isDuplicate(id)).isFalse();
        // Second arrival - duplicate
        assertThat(dedup.isDuplicate(id)).isTrue();
        // Third arrival - duplicate
        assertThat(dedup.isDuplicate(id)).isTrue();
    }

    @Test
    @Timeout(5)
    void testDifferentMessagesNotDuplicates() {
        MessageDeduplicator dedup = new MessageDeduplicator(100);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        assertThat(dedup.isDuplicate(id1)).isFalse();
        assertThat(dedup.isDuplicate(id2)).isFalse();
        assertThat(dedup.isDuplicate(id1)).isTrue();
    }

    @Test
    @Timeout(5)
    void testEviction() {
        MessageDeduplicator dedup = new MessageDeduplicator(3);
        
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        // Simulate message arrivals in order
        dedup.isDuplicate(id1); // msg 1 arrives
        dedup.isDuplicate(id2); // msg 2 arrives
        dedup.isDuplicate(id3); // msg 3 arrives
        assertThat(dedup.size()).isEqualTo(3);

        // Fourth message arrives - should evict oldest (id1)
        dedup.isDuplicate(id4); // msg 4 arrives
        assertThat(dedup.size()).isEqualTo(3);

        // Now simulate subsequent arrivals:
        // id2 arrives again (should be duplicate - still in cache)
        assertThat(dedup.isDuplicate(id2)).isTrue();
        
        // id3 arrives again (should be duplicate - still in cache)
        assertThat(dedup.isDuplicate(id3)).isTrue();
        
        // id4 arrives again (should be duplicate - still in cache)
        assertThat(dedup.isDuplicate(id4)).isTrue();
        
        // New message with id1 arrives (original was evicted, so treated as new)
        assertThat(dedup.isDuplicate(id1)).isFalse();
    }

    @Test
    @Timeout(5)
    void testClear() {
        MessageDeduplicator dedup = new MessageDeduplicator(100);
        UUID id = UUID.randomUUID();

        dedup.isDuplicate(id);
        assertThat(dedup.size()).isEqualTo(1);

        dedup.clear();
        assertThat(dedup.size()).isEqualTo(0);
        assertThat(dedup.isDuplicate(id)).isFalse();
    }
}