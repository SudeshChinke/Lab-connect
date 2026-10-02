package com.labconnect.core.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageDeduplicator {
    private static final Logger log = LoggerFactory.getLogger(MessageDeduplicator.class);

    private final int maxSize;
    private final Map<UUID, Long> seenMessages = new ConcurrentHashMap<>();
    private final Deque<UUID> insertionOrder = new LinkedList<>();
    private final Object orderLock = new Object();

    public MessageDeduplicator(int maxSize) {
        this.maxSize = maxSize;
    }

    public boolean isDuplicate(UUID messageId) {
        synchronized (orderLock) {
            log.debug("isDuplicate called for: {}, map size: {}", messageId, seenMessages.size());
            if (seenMessages.containsKey(messageId)) {
                log.debug("Duplicate message detected: {}", messageId);
                return true;
            }
            seenMessages.put(messageId, System.currentTimeMillis());
            insertionOrder.addLast(messageId);
            log.debug("Added to map, new size: {}", seenMessages.size());
            evictIfNeeded();
            log.debug("After eviction, map size: {}", seenMessages.size());
            return false;
        }
    }

    private void evictIfNeeded() {
        synchronized (orderLock) {
            log.debug("evictIfNeeded: map size={}, maxSize={}", seenMessages.size(), maxSize);
            while (seenMessages.size() > maxSize) {
                UUID oldest = insertionOrder.pollFirst();
                if (oldest != null) {
                    log.debug("Evicting oldest: {}", oldest);
                    seenMessages.remove(oldest);
                }
            }
            log.debug("After eviction: map size={}", seenMessages.size());
        }
    }

    public void clear() {
        synchronized (orderLock) {
            seenMessages.clear();
            insertionOrder.clear();
        }
    }

    public int size() {
        return seenMessages.size();
    }
}