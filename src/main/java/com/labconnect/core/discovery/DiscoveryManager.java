package com.labconnect.core.discovery;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class DiscoveryManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryManager.class);

    private final AppConfig config;
    private final DeviceInfo localDeviceInfo;
    private final DeviceRegistry registry;
    private final DiscoveryAnnouncer announcer;
    private final DiscoveryListener listener;
    private final Map<String, Consumer<DeviceInfo>> callbacks = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public DiscoveryManager(AppConfig config, DeviceInfo localDeviceInfo) throws IOException {
        this.config = config;
        this.localDeviceInfo = localDeviceInfo;

        this.registry = new DeviceRegistry(
                config.getNetwork().getAnnounceInterval().multipliedBy(3),
                this::onDeviceAdded,
                this::onDeviceRemoved,
                this::onDeviceUpdated
        );

        this.announcer = new DiscoveryAnnouncer(config.getNetwork(), localDeviceInfo);
        this.listener = new DiscoveryListener(config.getNetwork(), localDeviceInfo.deviceId(), this::onDiscovered);
    }

    public void start() {
        if (started) return;
        started = true;
        announcer.start();
        listener.start();
        log.info("Discovery manager started for device: {}", localDeviceInfo.deviceId());
    }

    private void onDiscovered(DiscoveredDevice discovered) {
        registry.addOrUpdate(discovered);
    }

    private void onDeviceAdded(DeviceInfo info) {
        callbacks.values().forEach(cb -> {
            try { cb.accept(info); } catch (Exception e) { log.warn("Callback error", e); }
        });
    }

    private void onDeviceRemoved(DeviceInfo info) {
        callbacks.values().forEach(cb -> {
            try { cb.accept(info); } catch (Exception e) { log.warn("Callback error", e); }
        });
    }

    private void onDeviceUpdated(DeviceInfo info) {
        callbacks.values().forEach(cb -> {
            try { cb.accept(info); } catch (Exception e) { log.warn("Callback error", e); }
        });
    }

    public void addCallback(String id, Consumer<DeviceInfo> callback) {
        callbacks.put(id, callback);
    }

    public void removeCallback(String id) {
        callbacks.remove(id);
    }

    public Optional<DeviceInfo> getDevice(String deviceId) {
        return registry.get(deviceId);
    }

    public Collection<DeviceInfo> getAllDevices() {
        return registry.getAll();
    }

    public int getDeviceCount() {
        return registry.size();
    }

    DeviceRegistry getRegistry() {
        return registry;
    }

    public void manualConnect(String ipAddress, int port) {
        // Create a pseudo-discovered device for manual connection
        DiscoveredDevice manual = new DiscoveredDevice(
                "MANUAL-" + ipAddress + ":" + port,
                "Manual-" + ipAddress,
                DeviceInfo.DeviceType.DESKTOP,
                "1.0",
                ipAddress,
                port,
                Set.of("FILE_TRANSFER", "GROUPS", "ENCRYPTION"),
                "",
                java.time.Instant.now()
        );
        registry.addOrUpdate(manual);
    }

    /**
     * Adds or updates a peer in the registry without waiting for a UDP
     * announcement. Used by integration tests and available for manual
     * entry of known peers.
     */
    public void registerDevice(DeviceInfo info) {
        registry.addOrUpdate(DiscoveredDevice.fromDeviceInfo(info, java.time.Instant.now()));
    }

    /** Removes a peer from the registry, firing the device-removed callbacks. */
    public void removeDevice(String deviceId) {
        registry.remove(deviceId);
    }

    @Override
    public void close() {
        started = false;
        announcer.close();
        listener.close();
        registry.close();
        log.info("Discovery manager stopped");
    }
}