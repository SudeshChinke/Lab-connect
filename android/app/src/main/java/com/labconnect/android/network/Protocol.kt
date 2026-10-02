package com.labconnect.android.network

import kotlinx.serialization.Serializable
import java.util.UUID

// Protocol constants
object Protocol {
    const val VERSION = "1.0"
    const val DISCOVERY_PORT = 50001
    const val TCP_PORT = 50000
    const val MULTICAST_GROUP = "239.255.255.250"
    const val BROADCAST_ADDRESS = "255.255.255.255"
    const val ANNOUNCE_INTERVAL_MS = 5000
    const val HEARTBEAT_INTERVAL_MS = 5000
    const val CONNECTION_TIMEOUT_MS = 10000
    const val CHUNK_SIZE = 65536
    
    // Frame header
    const val FRAME_HEADER_SIZE = 8
    const val MESSAGE_ID_SIZE = 16
    const val MAX_FRAME_SIZE = 16 * 1024 * 1024 // 16MB
    
    // Frame types
    const val FRAME_PING = 0x00.toByte()
    const val FRAME_PONG = 0x01.toByte()
    const val FRAME_FRAGMENT_START = 0xFE.toByte()
    const val FRAME_FRAGMENT_CONT = 0xFF.toByte()
    
    // Message types
    const val TYPE_HELLO = 0x10.toByte()
    const val TYPE_ACK = 0x11.toByte()
    const val TYPE_ERROR = 0x12.toByte()
    const val TYPE_GOODBYE = 0x13.toByte()
    const val TYPE_HEARTBEAT = 0x14.toByte()
    
    const val TYPE_DEVICE_ANNOUNCE = 0x20.toByte()
    const val TYPE_DEVICE_QUERY = 0x21.toByte()
    const val TYPE_DEVICE_RESPONSE = 0x22.toByte()
    
    const val TYPE_TEXT_MESSAGE = 0x30.toByte()
    const val TYPE_MESSAGE_ACK = 0x31.toByte()
    const val TYPE_MESSAGE_READ = 0x32.toByte()
    const val TYPE_GROUP_MESSAGE = 0x33.toByte()
    const val TYPE_BROADCAST_MESSAGE = 0x34.toByte()
    
    const val TYPE_FILE_REQUEST = 0x40.toByte()
    const val TYPE_FILE_ACCEPT = 0x41.toByte()
    const val TYPE_FILE_REJECT = 0x42.toByte()
    const val TYPE_FILE_CHUNK = 0x43.toByte()
    const val TYPE_FILE_CHUNK_ACK = 0x44.toByte()
    const val TYPE_FILE_COMPLETE = 0x45.toByte()
    const val TYPE_FILE_VERIFIED = 0x46.toByte()
    const val TYPE_FILE_CANCEL = 0x47.toByte()
    const val TYPE_FILE_RESUME = 0x48.toByte()
    const val TYPE_FILE_PAUSE = 0x49.toByte()
    
    const val TYPE_PAIR_REQUEST = 0x50.toByte()
    const val TYPE_PAIR_ACCEPT = 0x51.toByte()
    const val TYPE_PAIR_REJECT = 0x52.toByte()
    const val TYPE_KEY_ROTATE = 0x53.toByte()
    
    // Flags
    const val FLAG_COMPRESSED = 0x01
    const val FLAG_ENCRYPTED_APP = 0x02
    const val FLAG_FRAGMENTED = 0x04
    
    // Device types
    const val DEVICE_TYPE_DESKTOP = "DESKTOP"
    const val DEVICE_TYPE_MOBILE = "MOBILE"
    
    // Capabilities
    const val CAP_FILE_TRANSFER = "FILE_TRANSFER"
    const val CAP_GROUPS = "GROUPS"
    const val CAP_ENCRYPTION = "ENCRYPTION"
}

// Frame structure
data class Frame(
    val type: Byte,
    val flags: Byte,
    val messageId: UUID,
    val payload: ByteArray
) {
    companion object {
        fun create(type: Byte, flags: Byte, messageId: UUID, payload: ByteArray): Frame {
            return Frame(type, flags, messageId, payload)
        }
        
        fun ping(messageId: UUID) = Frame(Protocol.FRAME_PING, 0, messageId, ByteArray(0))
        fun pong(messageId: UUID) = Frame(Protocol.FRAME_PONG, 0, messageId, ByteArray(0))
    }
}

// Discovery announcement
@Serializable
data class DeviceAnnounce(
    val type: String = "DEVICE_ANNOUNCE",
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val protocolVersion: String = Protocol.VERSION,
    val tcpPort: Int = Protocol.TCP_PORT,
    val capabilities: List<String> = listOf(Protocol.CAP_FILE_TRANSFER, Protocol.CAP_GROUPS, Protocol.CAP_ENCRYPTION),
    val timestamp: Long = System.currentTimeMillis()
)

// Hello message
@Serializable
data class HelloMessage(
    val protocolVersion: String = Protocol.VERSION,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val publicKey: String,
    val capabilities: List<String> = listOf(Protocol.CAP_FILE_TRANSFER, Protocol.CAP_GROUPS, Protocol.CAP_ENCRYPTION),
    val supportedCompression: List<String> = listOf("zstd", "none")
)

// Ack message
@Serializable
data class AckMessage(
    val originalMessageId: String,
    val status: String = "OK",
    val timestamp: Long = System.currentTimeMillis()
)

// Text message
@Serializable
data class TextMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val senderId: String,
    val content: String,
    val contentType: String = "text/plain",
    val timestamp: Long = System.currentTimeMillis(),
    val replyTo: String? = null,
    val status: String = "SENT"
)

// File transfer messages
@Serializable
data class FileRequest(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val sha256: String,
    val chunkSize: Int = Protocol.CHUNK_SIZE,
    val totalChunks: Long,
    val senderId: String,
    val receiverId: String
)

@Serializable
data class FileAccept(
    val transferId: String,
    val accepted: Boolean = true,
    val resumeOffset: Long = 0,
    val receiverSha256: String? = null
)

@Serializable
data class FileChunk(
    val transferId: String,
    val chunkSequence: Int,
    val data: ByteArray
)

@Serializable
data class FileChunkAck(
    val transferId: String,
    val chunkSequence: Int,
    val status: String = "OK"
)

@Serializable
data class FileComplete(
    val transferId: String,
    val senderSha256: String
)

@Serializable
data class FileVerified(
    val transferId: String,
    val verified: Boolean,
    val receiverSha256: String
)

@Serializable
data class FileCancel(
    val transferId: String
)

@Serializable
data class FileResume(
    val transferId: String,
    val requestedOffset: Long,
    val knownSha256: String
)

// Heartbeat
@Serializable
data class Heartbeat(
    val timestamp: Long = System.currentTimeMillis(),
    val sequence: Long
)