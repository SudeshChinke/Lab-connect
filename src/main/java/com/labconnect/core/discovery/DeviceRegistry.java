package com.labconnect.core.discovery;

import com.labconnect.core.models.DeviceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DeviceRegistry {
    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final Map<String, DeviceEntry> devices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-registry-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final Duration ttl;
    private final Consumer<DeviceInfo> onDeviceAdded;
    private final Consumer<DeviceInfo> onDeviceRemoved;
    private final Consumer<DeviceInfo> onDeviceUpdated;

    public DeviceRegistry(Duration ttl,
                          Consumer<DeviceInfo> onDeviceAdded,
                          Consumer<DeviceInfo> onDeviceRemoved,
                          Consumer<DeviceInfo> onDeviceUpdated) {
        this.ttl = ttl;
        this.onDeviceAdded = onDeviceAdded;
        this.onDeviceRemoved = onDeviceRemoved;
        this.onDeviceUpdated = onDeviceUpdated;

        cleanupExecutor.scheduleAtFixedRate(this::cleanupStale, ttl.toMillis(), ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void addOrUpdate(DiscoveredDevice discovered) {
        String deviceId = discovered.deviceId();
        DeviceEntry existing = devices.get(deviceId);
        DeviceInfo info = discovered.toDeviceInfo();

        if (existing == null) {
            DeviceEntry entry = new DeviceEntry(info, Instant.now());
            devices.put(deviceId, entry);
            log.info("Device discovered: {} ({})", info.deviceName(), info.deviceId());
            if (onDeviceAdded != null) onDeviceAdded.accept(info);
        } else {
            boolean changed = !existing.info.equals(info);
            existing.info = info;
            existing.lastSeen = Instant.now();
            if (changed) {
                log.debug("Device updated: {}", info.deviceId());
                if (onDeviceUpdated != null) onDeviceUpdated.accept(info);
            }
        }
    }

    public void markSeen(String deviceId) {
        DeviceEntry entry = devices.get(deviceId);
        if (entry != null) {
            entry.lastSeen = Instant.now();
        }
    }

    public Optional<DeviceInfo> get(String deviceId) {
        DeviceEntry entry = devices.get(deviceId);
        return entry != null ? Optional.of(entry.info) : Optional.empty();
    }

    public Collection<DeviceInfo> getAll() {
        return devices.values().stream()
                .map(e -> e.info)
                .toList();
    }

    public void remove(String deviceId) {
        DeviceEntry removed = devices.remove(deviceId);
        if (removed != null) {
            log.info("Device removed: {} ({})", removed.info.deviceName(), deviceId);
            if (onDeviceRemoved != null) onDeviceRemoved.accept(removed.info);
        }
    }

    public int size() {
        return devices.size();
    }

    private void cleanupStale() {
        Instant now = Instant.now();
        devices.entrySet().removeIf(entry -> {
            if (Duration.between(entry.getValue().lastSeen, now).compareTo(ttl) > 0) {
                log.info("Device stale, removing: {} ({})", entry.getValue().info.deviceName(), entry.getKey());
                if (onDeviceRemoved != null) onDeviceRemoved.accept(entry.getValue().info);
                return true;
            }
            return false;
        });
    }

    public void close() {
        cleanupExecutor.shutdownNow();
        devices.clear();
    }

    private static class DeviceEntry {
        DeviceInfo info;
        Instant lastSeen;

        DeviceEntry(DeviceInfo info, Instant lastSeen) {
            this.info = info;
            this.lastSeen = lastSeen;
        }
    }
}