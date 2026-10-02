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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DiscoveryListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryListener.class);

    private final AppConfig.NetworkConfig networkConfig;
    private final String localDeviceId;
    private final DatagramChannel channel;
    private final InetAddress multicastGroup;
    private final Consumer<DiscoveredDevice> onDeviceDiscovered;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discovery-listener");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private volatile boolean running = false;

    public DiscoveryListener(AppConfig.NetworkConfig networkConfig,
                             String localDeviceId,
                             Consumer<DiscoveredDevice> onDeviceDiscovered) throws IOException {
        this.networkConfig = networkConfig;
        this.localDeviceId = localDeviceId;
        this.onDeviceDiscovered = onDeviceDiscovered;
        this.channel = DatagramChannel.open(StandardProtocolFamily.INET);
        this.channel.configureBlocking(false);
        // SO_REUSEADDR must be set before bind, and is required for multicast
        // listeners anyway. Without it a second LabConnect instance on the same
        // host cannot share the discovery port, so local multi-instance testing
        // (and multiple instances behind one NAT address) would fail to bind.
        this.channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.multicastGroup = InetAddress.getByName(networkConfig.getMulticastGroup());

        // Bind to discovery port
        channel.bind(new InetSocketAddress(networkConfig.getDiscoveryPort()));

        // Join multicast group
        NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
        if (ni != null) {
            channel.join(multicastGroup, ni);
        }
    }

    public void start() {
        if (running) return;
        running = true;

        scheduler.submit(this::listenLoop);
        log.info("Discovery listener started on port {}", networkConfig.getDiscoveryPort());
    }

    private void listenLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);

        while (running) {
            try {
                buffer.clear();
                SocketAddress sender = channel.receive(buffer);
                if (sender != null) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    processPacket(data, sender);
                }
            } catch (IOException e) {
                if (running) {
                    log.debug("Receive error", e);
                }
            }
        }
    }

    private void processPacket(byte[] data, SocketAddress sender) {
        try {
            Announcement announcement = mapper.readValue(data, Announcement.class);

            // Ignore our own announcements
            if (announcement.deviceId().equals(localDeviceId)) {
                return;
            }

            InetSocketAddress senderAddr = (InetSocketAddress) sender;
            String senderIp = senderAddr.getAddress().getHostAddress();

            DiscoveredDevice discovered = new DiscoveredDevice(
                    announcement.deviceId(),
                    announcement.deviceName(),
                    DeviceInfo.DeviceType.valueOf(announcement.deviceType()),
                    announcement.protocolVersion(),
                    senderIp,
                    announcement.tcpPort(),
                    announcement.capabilities(),
                    announcement.publicKey(),
                    announcement.timestamp()
            );

            log.debug("Discovered device: {} ({}) at {}:{}",
                    discovered.deviceName(), discovered.deviceId(), senderIp, announcement.tcpPort());

            if (onDeviceDiscovered != null) {
                onDeviceDiscovered.accept(discovered);
            }
        } catch (Exception e) {
            log.debug("Failed to parse announcement: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        try {
            channel.close();
        } catch (IOException ignored) {}
    }
}