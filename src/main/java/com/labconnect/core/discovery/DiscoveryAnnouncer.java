package com.labconnect.core.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.labconnect.core.config.AppConfig;
import com.labconnect.core.models.DeviceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscoveryAnnouncer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryAnnouncer.class);

    private final AppConfig.NetworkConfig networkConfig;
    private final DeviceInfo localDeviceInfo;
    private final DatagramChannel channel;
    private final InetAddress multicastGroup;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discovery-announcer");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public DiscoveryAnnouncer(AppConfig.NetworkConfig networkConfig, DeviceInfo localDeviceInfo) throws IOException {
        this.networkConfig = networkConfig;
        this.localDeviceInfo = localDeviceInfo;
        this.channel = DatagramChannel.open(StandardProtocolFamily.INET);
        this.channel.configureBlocking(false);
        this.channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.multicastGroup = InetAddress.getByName(networkConfig.getMulticastGroup());

        // Join multicast group
        NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
        if (ni != null) {
            channel.join(multicastGroup, ni);
        }
    }

    public void start() {
        Announcement announcement = new Announcement(
                "DEVICE_ANNOUNCE",
                localDeviceInfo.deviceId(),
                localDeviceInfo.deviceName(),
                localDeviceInfo.deviceType().name(),
                localDeviceInfo.protocolVersion(),
                localDeviceInfo.tcpPort(),
                localDeviceInfo.capabilities(),
                localDeviceInfo.publicKey(),
                Instant.now()
        );

        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendAnnouncement(announcement);
            } catch (IOException e) {
                log.warn("Failed to send announcement", e);
            }
        }, 0, networkConfig.getAnnounceInterval().toMillis(), TimeUnit.MILLISECONDS);

        log.info("Discovery announcer started on {}:{}", multicastGroup, networkConfig.getDiscoveryPort());
    }

    private void sendAnnouncement(Announcement announcement) throws IOException {
        byte[] json = mapper.writeValueAsBytes(announcement);

        // Send to multicast group
        InetSocketAddress multicastAddr = new InetSocketAddress(multicastGroup, networkConfig.getDiscoveryPort());
        channel.send(ByteBuffer.wrap(json), multicastAddr);

        // Also send broadcast as fallback
        try {
            InetSocketAddress broadcastAddr = new InetSocketAddress("255.255.255.255", networkConfig.getDiscoveryPort());
            channel.socket().setBroadcast(true);
            channel.send(ByteBuffer.wrap(json), broadcastAddr);
        } catch (IOException e) {
            log.debug("Broadcast send failed (may be expected): {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            channel.close();
        } catch (IOException ignored) {}
    }
}