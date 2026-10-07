package com.labconnect.android.network

import android.util.Log
import com.labconnect.android.security.AndroidIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** TCP client/server and framing compatible with the desktop Java transport. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ConnectionManager(
    private val identity: AndroidIdentityStore.Identity,
    private val localDeviceName: String
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val frameHandlers = ConcurrentHashMap<Byte, (Connection, Frame) -> Unit>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private var server: ServerSocketChannel? = null
    @Volatile private var onMessageReceived: (TextMessage) -> Unit = {}
    @Volatile private var onConnectionChanged: (String, Boolean) -> Unit = { _, _ -> }

    data class Connection(
        @Volatile var remoteDeviceId: String,
        val socket: SocketChannel,
        val remoteAddress: InetSocketAddress,
        val sendChannel: Channel<Frame> = Channel(64)
    ) {
        @Volatile var connected: Boolean = false
    }

    init {
        registerHandler(Protocol.TYPE_HELLO, ::handleHello)
        registerHandler(Protocol.TYPE_TEXT_MESSAGE, ::handleTextMessage)
        registerHandler(Protocol.TYPE_MESSAGE_ACK) { _, _ -> }
        registerHandler(Protocol.TYPE_HEARTBEAT, ::handleHeartbeat)
        registerHandler(Protocol.TYPE_ACK) { _, _ -> }
        scope.launch {
            while (scope.coroutineContext.isActive) {
                delay(Protocol.HEARTBEAT_INTERVAL_MS.toLong())
                val payload = "{\"timestamp\":${System.currentTimeMillis()},\"sequence\":${System.nanoTime()}}"
                getAllConnections().forEach { connection ->
                    send(connection, Protocol.TYPE_HEARTBEAT, UUID.randomUUID(), payload)
                }
            }
        }
    }

    fun setOnMessageReceived(handler: ((TextMessage) -> Unit)?) {
        onMessageReceived = handler ?: {}
    }

    fun setOnConnectionChanged(handler: ((String, Boolean) -> Unit)?) {
        onConnectionChanged = handler ?: { _, _ -> }
    }

    fun startServer(port: Int = Protocol.TCP_PORT) {
        if (server != null) return
        scope.launch {
            try {
                val listener = ServerSocketChannel.open().apply {
                    configureBlocking(true)
                    bind(InetSocketAddress(port))
                }
                server = listener
                Log.i(TAG, "Listening for peers on TCP $port")
                while (scope.coroutineContext.isActive && listener.isOpen) {
                    val socket = listener.accept() ?: continue
                    socket.configureBlocking(true)
                    openConnection(socket)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (scope.coroutineContext.isActive) Log.e(TAG, "TCP listener failed on $port", e)
            }
        }
    }

    suspend fun connect(host: String, port: Int): Connection? = withContext(Dispatchers.IO) {
        try {
            val socket = SocketChannel.open().apply {
                configureBlocking(true)
                socket().connect(InetSocketAddress(host, port), Protocol.CONNECTION_TIMEOUT_MS)
            }
            openConnection(socket)
        } catch (e: Exception) {
            Log.e(TAG, "Connect to $host:$port failed", e)
            null
        }
    }

    private fun openConnection(socket: SocketChannel): Connection {
        val address = socket.remoteAddress as InetSocketAddress
        val connection = Connection("pending:${address.address.hostAddress}:${address.port}", socket, address)
        connections[connection.remoteDeviceId] = connection
        scope.launch { readLoop(connection) }
        scope.launch { writeLoop(connection) }
        sendHello(connection)
        return connection
    }

    fun getConnection(deviceId: String): Connection? =
        connections[deviceId]?.takeIf { it.connected && it.socket.isOpen }

    fun getAllConnections(): List<Connection> = connections.values.distinct().filter { it.connected }

    fun sendMessage(targetDeviceId: String, content: String): TextMessage? {
        val connection = getConnection(targetDeviceId) ?: return null
        val message = TextMessage(
            chatId = listOf(identity.deviceId, targetDeviceId).sorted().joinToString("-"),
            senderId = identity.deviceId,
            content = content,
            timestamp = Instant.now().toString()
        )
        send(connection, Protocol.TYPE_TEXT_MESSAGE, UUID.fromString(message.messageId), json.encodeToString(message))
        return message
    }

    fun sendFrame(connection: Connection, frame: Frame) {
        if (connection.socket.isOpen) connection.sendChannel.trySend(frame)
    }

    suspend fun sendFrameAwait(connection: Connection, frame: Frame) {
        if (connection.socket.isOpen) connection.sendChannel.send(frame)
    }

    fun registerHandler(type: Byte, handler: (Connection, Frame) -> Unit) {
        frameHandlers[type] = handler
    }

    fun closeConnection(deviceId: String) {
        connections[deviceId]?.let(::closeConnection)
    }

    private fun sendHello(connection: Connection) {
        val hello = HelloMessage(
            deviceId = identity.deviceId,
            deviceName = localDeviceName,
            deviceType = Protocol.DEVICE_TYPE_MOBILE,
            publicKey = identity.publicKeyBase64
        )
        send(connection, Protocol.TYPE_HELLO, UUID.randomUUID(), json.encodeToString(hello))
    }

    private fun send(connection: Connection, type: Byte, id: UUID, payload: String) {
        sendFrame(connection, Frame.create(type, 0, id, payload.toByteArray(Charsets.UTF_8)))
    }

    private suspend fun readLoop(connection: Connection) {
        var buffer = ByteBuffer.allocate(64 * 1024)
        try {
            while (connection.socket.isOpen) {
                if (!buffer.hasRemaining()) buffer = grow(buffer)
                val count = connection.socket.read(buffer)
                if (count < 0) break
                buffer.flip()
                while (buffer.remaining() >= FrameCodec.HEADER_SIZE) {
                    val start = buffer.position()
                    val length = buffer.getInt()
                    buffer.position(start)
                    require(length in (FrameCodec.HEADER_SIZE + FrameCodec.MESSAGE_ID_SIZE)..FrameCodec.MAX_FRAME_SIZE) {
                        "Invalid protocol frame length: $length"
                    }
                    if (buffer.remaining() < length) break
                    val encoded = ByteArray(length)
                    buffer.get(encoded)
                    FrameCodec.decode(encoded)?.let { frameHandlers[it.type]?.invoke(connection, it) }
                }
                buffer.compact()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Read failed from ${connection.remoteAddress}", e)
        } finally {
            closeConnection(connection)
        }
    }

    private fun grow(buffer: ByteBuffer): ByteBuffer {
        require(buffer.capacity() < FrameCodec.MAX_FRAME_SIZE) { "Protocol frame exceeds maximum size" }
        val grown = ByteBuffer.allocate((buffer.capacity() * 2).coerceAtMost(FrameCodec.MAX_FRAME_SIZE))
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private suspend fun writeLoop(connection: Connection) {
        try {
            for (frame in connection.sendChannel) {
                val buffer = ByteBuffer.wrap(FrameCodec.encode(frame))
                while (buffer.hasRemaining()) connection.socket.write(buffer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Write failed to ${connection.remoteAddress}", e)
        } finally {
            closeConnection(connection)
        }
    }

    private fun handleHello(connection: Connection, frame: Frame) {
        try {
            val hello = json.decodeFromString(HelloMessage.serializer(), String(frame.payload, Charsets.UTF_8))
            require(hello.deviceId == deriveDeviceId(hello.publicKey)) { "HELLO public key does not match device id" }
            val previous = connections.put(hello.deviceId, connection)
            if (previous != null && previous !== connection) closeConnection(previous)
            connection.remoteDeviceId = hello.deviceId
            connection.connected = true
            onConnectionChanged(hello.deviceId, true)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid HELLO from ${connection.remoteAddress}", e)
            closeConnection(connection)
        }
    }

    private fun handleTextMessage(connection: Connection, frame: Frame) {
        try {
            val message = json.decodeFromString(TextMessage.serializer(), String(frame.payload, Charsets.UTF_8))
            require(message.senderId == connection.remoteDeviceId) { "Message sender does not match peer" }
            onMessageReceived(message)
            val ack = MessageAcknowledgement(frame.messageId.toString(), "DELIVERED")
            send(connection, Protocol.TYPE_MESSAGE_ACK, UUID.randomUUID(), json.encodeToString(ack))
        } catch (e: Exception) {
            Log.w(TAG, "Invalid message from ${connection.remoteAddress}", e)
        }
    }

    private fun handleHeartbeat(connection: Connection, frame: Frame) {
        val ack = AckMessage(frame.messageId.toString(), "OK")
        send(connection, Protocol.TYPE_ACK, UUID.randomUUID(), json.encodeToString(ack))
    }

    private fun closeConnection(connection: Connection) {
        val peerId = connection.remoteDeviceId
        connections.entries.removeIf { it.value === connection }
        connection.connected = false
        connection.sendChannel.close()
        try { connection.socket.close() } catch (_: IOException) { }
        if (peerId.startsWith("DEVICE-")) onConnectionChanged(peerId, false)
    }

    fun shutdown() {
        try { server?.close() } catch (_: IOException) { }
        server = null
        connections.values.distinct().forEach(::closeConnection)
        scope.cancel()
    }

    private fun deriveDeviceId(publicKeyBase64: String): String {
        val encoded = android.util.Base64.decode(publicKeyBase64, android.util.Base64.NO_WRAP)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
        return "DEVICE-" + hash.take(8).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    companion object { private const val TAG = "AndroidConnection" }
}

@Serializable
data class MessageAcknowledgement(
    val originalMessageId: String,
    val status: String = "DELIVERED",
    val timestamp: Long = System.currentTimeMillis()
)
