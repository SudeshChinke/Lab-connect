package com.labconnect.android.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager(
    private val localDeviceId: String
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val frameHandlers = mutableMapOf<Byte, (Connection, Frame) -> Unit>()
    private val json = Json { ignoreUnknownKeys = true }
    
    data class Connection(
        var remoteDeviceId: String,
        val socket: SocketChannel,
        val remoteAddress: InetSocketAddress,
        val sendChannel: Channel<Frame> = Channel(100),
        val receiveBuffer: ByteArray = ByteArray(65536)
    ) {
        var connected = false
        var lastHeartbeat = System.currentTimeMillis()
    }
    
    init {
        // Register default handlers
        registerHandler(Protocol.TYPE_HELLO) { conn, frame -> handleHello(conn, frame) }
        registerHandler(Protocol.TYPE_ACK) { conn, frame -> handleAck(conn, frame) }
        registerHandler(Protocol.TYPE_HEARTBEAT) { conn, frame -> handleHeartbeat(conn, frame) }
        registerHandler(Protocol.TYPE_TEXT_MESSAGE) { conn, frame -> handleTextMessage(conn, frame) }
        registerHandler(Protocol.TYPE_MESSAGE_ACK) { conn, frame -> handleMessageAck(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_REQUEST) { conn, frame -> handleFileRequest(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_ACCEPT) { conn, frame -> handleFileAccept(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_CHUNK) { conn, frame -> handleFileChunk(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_CHUNK_ACK) { conn, frame -> handleFileChunkAck(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_COMPLETE) { conn, frame -> handleFileComplete(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_VERIFIED) { conn, frame -> handleFileVerified(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_CANCEL) { conn, frame -> handleFileCancel(conn, frame) }
        registerHandler(Protocol.TYPE_FILE_RESUME) { conn, frame -> handleFileResume(conn, frame) }
    }
    
    fun connect(host: String, port: Int): Connection? {
        return try {
            val socket = SocketChannel.open()
            socket.configureBlocking(false)
            socket.connect(InetSocketAddress(host, port))
            
            // Wait for connection
            while (!socket.finishConnect()) {
                Thread.sleep(10)
            }
            socket.configureBlocking(true)
            
            val remoteAddress = InetSocketAddress(host, port)
            val connection = Connection("pending", socket, remoteAddress)
            connections["$host:$port"] = connection
            
            // Start read loop
            scope.launch { readLoop(connection) }
            scope.launch { writeLoop(connection) }
            
            connection
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Connect failed", e)
            null
        }
    }
    
    fun acceptConnection(socket: SocketChannel, remoteDeviceId: String): Connection {
        val remoteAddress = socket.remoteAddress as InetSocketAddress
        val connection = Connection(remoteDeviceId, socket, remoteAddress)
        connections["${remoteAddress.address.hostAddress}:${remoteAddress.port}"] = connection
        
        scope.launch { readLoop(connection) }
        scope.launch { writeLoop(connection) }
        
        return connection
    }
    
    fun sendFrame(connection: Connection, frame: Frame) {
        connection.sendChannel.trySend(frame)
    }
    
    fun registerHandler(type: Byte, handler: (Connection, Frame) -> Unit) {
        frameHandlers[type] = handler
    }
    
    fun getConnection(deviceId: String): Connection? {
        return connections.values.firstOrNull { it.remoteDeviceId == deviceId }
    }
    
    fun getAllConnections(): List<Connection> {
        return connections.values.toList()
    }
    
    fun closeConnection(deviceId: String) {
        connections.values.firstOrNull { it.remoteDeviceId == deviceId }?.let { conn ->
            conn.socket.close()
            connections.values.remove(conn)
        }
    }
    
    private suspend fun readLoop(connection: Connection) {
        val buffer = ByteBuffer.allocate(65536)
        buffer.order = ByteOrder.BIG_ENDIAN
        
        while (connection.socket.isOpen) {
            try {
                val bytesRead = connection.socket.read(buffer)
                if (bytesRead == -1) break // EOF
                
                buffer.flip()
                processBuffer(connection, buffer)
                buffer.compact()
            } catch (e: IOException) {
                Log.e("ConnectionManager", "Read error", e)
                break
            }
        }
        closeConnection(connection)
    }
    
    private fun processBuffer(connection: Connection, buffer: ByteBuffer) {
        while (buffer.remaining() >= FrameCodec.HEADER_SIZE) {
            val frameStart = buffer.position()
            val frameLength = buffer.getInt()
            buffer.position(frameStart)
            if (frameLength < FrameCodec.HEADER_SIZE + FrameCodec.MESSAGE_ID_SIZE ||
                frameLength > FrameCodec.MAX_FRAME_SIZE) {
                throw IllegalArgumentException("Invalid frame length: $frameLength")
            }
            if (buffer.remaining() < frameLength) return
            val encodedFrame = ByteArray(frameLength)
            buffer.get(encodedFrame)
            val frame = FrameCodec.decode(encodedFrame) ?: return
            frameHandlers[frame.type]?.invoke(connection, frame)
        }
    }
    
    private suspend fun writeLoop(connection: Connection) {
        for (frame in connection.sendChannel) {
            try {
                val data = FrameCodec.encode(frame)
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) connection.socket.write(buffer)
            } catch (e: IOException) {
                Log.e("ConnectionManager", "Write error", e)
                break
            }
        }
    }
    
    private fun closeConnection(connection: Connection) {
        try {
            connection.socket.close()
        } catch (e: IOException) {
            Log.e("ConnectionManager", "Close error", e)
        }
        connections.values.remove(connection)
    }
    
    // Frame handlers
    private fun handleHello(connection: Connection, frame: Frame) {
        // Parse hello and send ACK
        val hello = json.decodeFromString(HelloMessage.serializer(), String(frame.payload, Charsets.UTF_8))
        connection.remoteDeviceId = hello.deviceId
        connections[hello.deviceId] = connection
        connection.connected = true
        
        // Send ACK
        val ack = AckMessage(
            originalMessageId = frame.messageId.toString(),
            status = "OK"
        )
        val ackFrame = Frame.create(
            Protocol.TYPE_ACK, 0, UUID.randomUUID(),
            json.encodeToString(AckMessage.serializer(), ack).toByteArray(Charsets.UTF_8)
        )
        sendFrame(connection, ackFrame)
    }
    
    private fun handleAck(connection: Connection, frame: Frame) {
        // Handle ACK
    }
    
    private fun handleHeartbeat(connection: Connection, frame: Frame) {
        connection.lastHeartbeat = System.currentTimeMillis()
        // Send PONG
        val pong = Frame.pong(frame.messageId)
        sendFrame(connection, pong)
    }
    
    private fun handleTextMessage(connection: Connection, frame: Frame) {
        // Forward to message handler
    }
    
    private fun handleMessageAck(connection: Connection, frame: Frame) {
        // Handle message ACK
    }
    
    private fun handleFileRequest(connection: Connection, frame: Frame) {
        // Handle file request
    }
    
    private fun handleFileAccept(connection: Connection, frame: Frame) {
        // Handle file accept
    }
    
    private fun handleFileChunk(connection: Connection, frame: Frame) {
        // Handle file chunk
    }
    
    private fun handleFileChunkAck(connection: Connection, frame: Frame) {
        // Handle chunk ACK
    }
    
    private fun handleFileComplete(connection: Connection, frame: Frame) {
        // Handle file complete
    }
    
    private fun handleFileVerified(connection: Connection, frame: Frame) {
        // Handle file verified
    }
    
    private fun handleFileCancel(connection: Connection, frame: Frame) {
        // Handle file cancel
    }
    
    private fun handleFileResume(connection: Connection, frame: Frame) {
        // Handle file resume
    }
    
    fun shutdown() {
        scope.cancel()
        connections.values.forEach { it.socket.close() }
        connections.clear()
    }
}
