package com.labconnect.core.networking;

import com.labconnect.core.protocol.FrameCodec;
import com.labconnect.core.protocol.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ConnectionManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);
    /** Parses HELLO payloads to bind incoming connections to a peer's deviceId. */
    private static final com.fasterxml.jackson.databind.ObjectMapper HELLO_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final int port;
    private final ExecutorService workerPool;
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<SelectionKey, Connection> keyToConnection = new ConcurrentHashMap<>();
    private final Consumer<Connection> onConnectionEstablished;
    private final Consumer<Connection> onConnectionClosed;
    private final Consumer<FrameCodec.Frame> onFrameReceived;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-sender");
        t.setDaemon(true);
        return t;
    });
    private final Duration heartbeatInterval;
    /**
     * Connections that have data queued and need OP_WRITE registered.
     * <p>{@code SelectionKey.interestOps()} may only be changed by the selector
     * thread, so worker threads record their request here and the selector loop
     * applies it. Waking the selector is not sufficient - the interest update
     * itself must happen on the selector thread or the write is lost.
     */
    private final Set<Connection> writeInterestPending = ConcurrentHashMap.newKeySet();
    private volatile boolean running = false;
    private Thread selectorThread;

    public ConnectionManager(int port,
                             Duration heartbeatInterval,
                             Consumer<Connection> onConnectionEstablished,
                             Consumer<Connection> onConnectionClosed,
                             Consumer<FrameCodec.Frame> onFrameReceived) throws IOException {
        this.port = port;
        this.heartbeatInterval = heartbeatInterval;
        this.onConnectionEstablished = onConnectionEstablished;
        this.onConnectionClosed = onConnectionClosed;
        this.onFrameReceived = onFrameReceived;
        this.workerPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "connection-worker-%d".formatted(Thread.activeCount()));
            t.setDaemon(true);
            return t;
        });
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        log.info("ConnectionManager listening on port {}", port);
    }

    public void start() {
        if (running) return;
        running = true;
        selectorThread = new Thread(this::selectorLoop, "connection-selector");
        selectorThread.setDaemon(true);
        selectorThread.start();

        // Start heartbeat sender
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);

        // Start stale connection checker (check every heartbeat interval)
        heartbeatScheduler.scheduleAtFixedRate(this::checkStaleConnections, 
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkStaleConnections() {
        if (!running) return;
        Duration timeout = heartbeatInterval.multipliedBy(3); // 3 missed heartbeats
        connections.values().forEach(conn -> {
            if (conn.isConnected() && conn.isHeartbeatStale(timeout)) {
                log.warn("Connection to {} stale (no heartbeat for {}), closing", 
                        conn.getRemoteAddress(), timeout);
                closeConnection(conn);
            }
        });
    }

    public Connection connect(String host, int port) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(host, port));

        Connection connection = new Connection(channel, this::handleFrame, this::handleClose, null);
        setWriteInterestCallback(connection, () -> requestWriteInterest(connection));
        String key = host + ":" + port;
        connections.put(key, connection);
        registerChannel(channel, connection, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
        log.info("Initiated connection to {}:{}", host, port);
        return connection;
    }

    public Optional<Connection> getConnection(String deviceId) {
        return connections.values().stream()
                .filter(c -> deviceId.equals(c.getRemoteDeviceId()))
                .findFirst();
    }

    /**
     * Connection registered for an outbound {@link #connect(String, int)} to
     * the given address, if any. Lets callers avoid opening a second TCP
     * connection to a peer that is already being (or has been) connected to.
     */
    public Optional<Connection> getConnectionByAddress(String host, int port) {
        return Optional.ofNullable(connections.get(host + ":" + port));
    }

    /** The actual bound TCP port; resolves a port-0 bind to the OS-assigned port. */
    public int getLocalPort() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return port;
        }
    }

    public Collection<Connection> getAllConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public void sendFrame(Connection connection, FrameCodec.Frame frame) {
        workerPool.submit(() -> {
            try {
                ByteBuffer encoded = FrameCodec.encode(frame);
                log.debug("ConnectionManager.sendFrame: queueing frame type={}, size={} to {}", frame.type(), encoded.remaining(), connection.getRemoteAddress());
                connection.write(encoded);
                requestWriteInterest(connection);
                log.debug("ConnectionManager.sendFrame: registered OP_WRITE for {}", connection.getRemoteAddress());
            } catch (IOException e) {
                log.error("Failed to send frame to {}", connection.getRemoteAddress(), e);
                closeConnection(connection);
            }
        });
    }

    public void sendHello(Connection connection, String deviceId, String deviceName,
                          String publicKey, Set<String> capabilities) {
        FrameCodec.Frame frame = FrameCodec.Frame.create(
                MessageType.HELLO.value(), (byte) 0, UUID.randomUUID(),
                buildHelloPayload(deviceId, deviceName, publicKey, capabilities)
        );
        sendFrame(connection, frame);
    }

    private void sendHeartbeats() {
        if (!running) return;
        FrameCodec.Frame heartbeat = FrameCodec.Frame.create(
                MessageType.HEARTBEAT.value(), (byte) 0, UUID.randomUUID(),
                ("{\"timestamp\":" + System.currentTimeMillis() + ",\"sequence\":" + heartbeatSequence.getAndIncrement() + "}").getBytes()
        );
        connections.values().forEach(conn -> {
            if (conn.isConnected()) {
                sendFrame(conn, heartbeat);
            }
        });
    }

    private final AtomicLong heartbeatSequence = new AtomicLong(0);

    private byte[] buildHelloPayload(String deviceId, String deviceName, String publicKey, Set<String> capabilities) {
        // Capabilities must be quoted, otherwise the payload is not valid JSON
        // and the peer cannot parse the deviceId out of it.
        String caps = capabilities.stream()
                .map(c -> "\"" + escape(c) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return ("{"
                + "\"protocolVersion\":\"1.0\","
                + "\"deviceId\":\"" + escape(deviceId) + "\","
                + "\"deviceName\":\"" + escape(deviceName) + "\","
                + "\"deviceType\":\"DESKTOP\","
                + "\"publicKey\":\"" + escape(publicKey) + "\","
                + "\"capabilities\":[" + caps + "],"
                + "\"supportedCompression\":[\"zstd\",\"none\"]"
                + "}").getBytes();
    }

    private String escape(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void selectorLoop() {
        while (running) {
            try {
                // Apply requests that arrived while the previous cycle was being
                // processed, then wait. Applying again after select() returns
                // keeps latency low for requests that land mid-wait.
                applyPendingWriteInterests();
                selector.select(1000);
                applyPendingWriteInterests();

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (!key.isValid()) continue;

                        // Independent checks, not else-if: a socket is frequently
                        // readable and writable in the same cycle (incoming data
                        // plus queued outgoing data). An else-if chain skipped
                        // handleWrite, leaving frames stuck in the write queue
                        // until the peer happened to stop sending.
                        //
                        // Validity is re-checked between each step because
                        // close()/closeConnection() cancel keys from other
                        // threads; querying readiness on a cancelled key throws
                        // CancelledKeyException.
                        if (key.isValid() && key.isAcceptable()) {
                            handleAccept(key);
                        }
                        if (key.isValid() && key.isConnectable()) {
                            handleConnect(key);
                        }
                        if (key.isValid() && key.isReadable()) {
                            handleRead(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key);
                        }
                    }
            } catch (IOException e) {
                if (running) log.error("Selector error", e);
            } catch (CancelledKeyException e) {
                // Key cancelled concurrently by another thread; nothing to do.
                if (running) log.debug("Selector key cancelled: {}", e.getMessage());
            } catch (ClosedSelectorException e) {
                if (running) log.error("Selector closed unexpectedly", e);
            } catch (RuntimeException e) {
                // The selector thread is the heart of this manager: if it dies,
                // every connection silently stops working. Never let an
                // unexpected error kill it.
                if (running) log.error("Unexpected error in selector loop", e);
            }
        }
    }

    /**
     * Called from worker threads when data is queued. Records the need for
     * OP_WRITE and wakes the selector; the interest op itself is applied by the
     * selector thread in {@link #applyPendingWriteInterests()}.
     */
    private void requestWriteInterest(Connection connection) {
        writeInterestPending.add(connection);
        selector.wakeup();
    }

    /** Selector-thread only: applies queued write-interest requests. */
    private void applyPendingWriteInterests() {
        if (writeInterestPending.isEmpty()) {
            return;
        }
        for (Connection connection : writeInterestPending) {
            if (!writeInterestPending.remove(connection)) {
                continue;
            }
            SelectionKey key = findKey(connection);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    private void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();
            if (client != null) {
                client.configureBlocking(false);
                Connection connection = new Connection(client, this::handleFrame, this::handleClose, null);
                setWriteInterestCallback(connection, () -> requestWriteInterest(connection));
                registerChannel(client, connection, SelectionKey.OP_READ);
                String connKey = client.getRemoteAddress().toString();
                connections.put(connKey, connection);
                connection.setConnected(true);
                onConnectionEstablished.accept(connection);
                log.info("Accepted connection from {}", client.getRemoteAddress());
            }
        } catch (IOException e) {
            log.error("Accept error", e);
        }
    }

    private void handleConnect(SelectionKey key) {
        try {
            SocketChannel channel = (SocketChannel) key.channel();
            if (channel.finishConnect()) {
                Connection connection = keyToConnection.get(key);
                if (connection != null) {
                    // Preserve OP_WRITE: data may already be queued (a caller
                    // can send before the non-blocking connect completes).
                    // Resetting to OP_READ alone would strand that data.
                    int ops = SelectionKey.OP_READ;
                    if (connection.hasPendingWrites()) {
                        ops |= SelectionKey.OP_WRITE;
                    }
                    key.interestOps(ops);
                    connection.setConnected(true);
                    onConnectionEstablished.accept(connection);
                    log.info("Connection established to {}", connection.getRemoteAddress());
                }
            } else {
                SocketChannel sc = (SocketChannel) key.channel();
                log.warn("Connection failed: {}", sc.getRemoteAddress());
                closeConnection(keyToConnection.get(key));
            }
        } catch (IOException e) {
            log.error("Connect error", e);
            closeConnection(keyToConnection.get(key));
        }
    }

    private void handleRead(SelectionKey key) {
        Connection connection = keyToConnection.get(key);
        if (connection == null) return;

        try {
            log.debug("handleRead: reading from {}", connection.getRemoteAddress());
            if (connection.read()) {
                // Data read, frame handler will be called
            } else {
                log.debug("handleRead: EOF from {}", connection.getRemoteAddress());
                closeConnection(connection);
            }
        } catch (IOException e) {
            log.error("Read error from {}", connection.getRemoteAddress(), e);
            closeConnection(connection);
        }
    }

    private void handleWrite(SelectionKey key) {
        Connection connection = keyToConnection.get(key);
        if (connection != null) {
            try {
                log.debug("handleWrite: flushing for {}", connection.getRemoteAddress());
                connection.flush();
                if (!connection.hasPendingWrites()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    log.debug("handleWrite: cleared OP_WRITE for {}", connection.getRemoteAddress());
                }
            } catch (IOException e) {
                log.error("Write error to {}", connection.getRemoteAddress(), e);
                closeConnection(connection);
            }
        }
    }

    private void registerChannel(SocketChannel channel, Connection connection, int ops) throws ClosedChannelException {
        SelectionKey key = channel.register(selector, ops);
        keyToConnection.put(key, connection);
    }

    private void handleFrame(Connection connection, FrameCodec.Frame frame) {
        if (frame.type() == MessageType.HEARTBEAT.value()) {
            // Update last heartbeat time
            connection.updateLastHeartbeat();
            // Send ACK
            connection.sendAck(frame.getMessageIdUUID(), "OK");
        } else if (frame.type() == MessageType.HELLO.value()) {
            bindRemoteDeviceId(connection, frame);
            onFrameReceived.accept(frame);
        } else {
            onFrameReceived.accept(frame);
        }
    }

    /**
     * Maps this connection to the peer's advertised deviceId so that
     * {@link #getConnection(String)} can resolve it. Without this binding the
     * connection is only reachable by socket address and every outbound send
     * that looks up a connection by deviceId fails.
     */
    private void bindRemoteDeviceId(Connection connection, FrameCodec.Frame frame) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = HELLO_MAPPER.readTree(frame.payload());
            com.fasterxml.jackson.databind.JsonNode deviceId = node.get("deviceId");
            if (deviceId != null && deviceId.isTextual() && !deviceId.asText().isBlank()) {
                connection.setRemoteDeviceId(deviceId.asText());
                log.debug("Bound connection {} to device {}", connection.getRemoteAddress(), deviceId.asText());
            } else {
                log.warn("HELLO from {} carries no deviceId", connection.getRemoteAddress());
            }
        } catch (Exception e) {
            log.warn("Malformed HELLO from {}: {}", connection.getRemoteAddress(), e.getMessage());
        }
    }

    private void handleClose(Connection connection) {
        closeConnection(connection);
    }

    private void closeConnection(Connection connection) {
        if (connection == null) return;
        String remoteAddr = connection.getRemoteAddress();
        try {
            connection.close();
        } catch (IOException ignored) {}
        connections.values().removeIf(c -> c == connection);
        keyToConnection.values().removeIf(c -> c == connection);
        onConnectionClosed.accept(connection);
        log.info("Connection closed: {}", remoteAddr);
    }

    private SelectionKey findKey(Connection connection) {
        return keyToConnection.entrySet().stream()
                .filter(e -> e.getValue() == connection)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void setWriteInterestCallback(Connection connection, Runnable callback) {
        try {
            java.lang.reflect.Field field = Connection.class.getDeclaredField("onWriteInterestNeeded");
            field.setAccessible(true);
            field.set(connection, callback);
        } catch (Exception e) {
            log.error("Failed to set write interest callback", e);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            heartbeatScheduler.shutdownNow();
            selector.wakeup();
            if (selectorThread != null) selectorThread.join(2000);
            serverChannel.close();
            selector.close();
            workerPool.shutdownNow();
            connections.values().forEach(c -> {
                try { c.close(); } catch (IOException ignored) {}
            });
            connections.clear();
            log.info("ConnectionManager stopped");
        } catch (IOException | InterruptedException e) {
            log.error("Error stopping ConnectionManager", e);
        }
    }
}