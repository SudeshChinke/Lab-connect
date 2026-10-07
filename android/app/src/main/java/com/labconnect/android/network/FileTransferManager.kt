package com.labconnect.android.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Implements the desktop FILE_* JSON frames over the shared TCP connection. */
class FileTransferManager(
    private val context: Context,
    private val identity: com.labconnect.android.security.AndroidIdentityStore.Identity,
    private val connections: ConnectionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()
    @Volatile var onIncomingRequest: (FileRequest) -> Unit = {}
    @Volatile var onTransferUpdate: (String) -> Unit = {}

    private data class Outgoing(val peerId: String, val uri: Uri, val metadata: FileRequest, val acked: MutableSet<Long> = ConcurrentHashMap.newKeySet())
    private data class Incoming(val peerId: String, val metadata: FileRequest, val file: File, val handle: RandomAccessFile, val received: MutableSet<Long> = ConcurrentHashMap.newKeySet())

    init {
        connections.registerHandler(Protocol.TYPE_FILE_REQUEST, ::handleRequest)
        connections.registerHandler(Protocol.TYPE_FILE_ACCEPT, ::handleAccept)
        connections.registerHandler(Protocol.TYPE_FILE_REJECT, ::handleReject)
        connections.registerHandler(Protocol.TYPE_FILE_CHUNK, ::handleChunk)
        connections.registerHandler(Protocol.TYPE_FILE_CHUNK_ACK, ::handleChunkAck)
        connections.registerHandler(Protocol.TYPE_FILE_COMPLETE, ::handleComplete)
        connections.registerHandler(Protocol.TYPE_FILE_VERIFIED, ::handleVerified)
        connections.registerHandler(Protocol.TYPE_FILE_CANCEL, ::handleCancel)
    }

    suspend fun sendFile(peerId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val peer = connections.getConnection(peerId) ?: return@withContext null
        try {
            val name = displayName(uri) ?: "shared-file"
            val descriptorSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val size = if (descriptorSize >= 0) descriptorSize else context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
            require(size >= 0) { "File size is unavailable" }
            val sha256 = context.contentResolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val block = ByteArray(64 * 1024)
                while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
                digest.digest().toHex()
            } ?: return@withContext null
            val transferId = UUID.randomUUID().toString()
            val request = FileRequest(
                transferId, name, size,
                context.contentResolver.getType(uri) ?: "application/octet-stream",
                sha256, Protocol.CHUNK_SIZE, (size + Protocol.CHUNK_SIZE - 1) / Protocol.CHUNK_SIZE,
                identity.deviceId, peerId
            )
            outgoing[transferId] = Outgoing(peerId, uri, request)
            send(peer, Protocol.TYPE_FILE_REQUEST, request, FileRequest.serializer())
            onTransferUpdate("Sending request for $name")
            transferId
        } catch (e: Exception) {
            Log.e(TAG, "Could not prepare file", e)
            null
        }
    }

    fun acceptIncoming(transferId: String) {
        scope.launch {
            val request = pendingRequests.remove(transferId) ?: return@launch
            try {
                val folder = File(context.filesDir, "received").apply { mkdirs() }
                val safeName = File(request.fileName).name.ifBlank { "received-file" }
                var destination = File(folder, safeName)
                if (destination.exists()) destination = File(folder, "${UUID.randomUUID()}-$safeName")
                val raf = RandomAccessFile(destination, "rw")
                val state = Incoming(request.senderId, request, destination, raf)
                incoming[transferId] = state
                sendTo(request.senderId, Protocol.TYPE_FILE_ACCEPT, FileAccept(transferId, resumeOffset = 0), FileAccept.serializer())
                onTransferUpdate("Receiving ${request.fileName}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not accept incoming file", e)
                sendTo(request.senderId, Protocol.TYPE_FILE_REJECT, RejectedFile(transferId, "Cannot create destination file"), RejectedFile.serializer())
            }
        }
    }

    fun rejectIncoming(transferId: String) {
        pendingRequests.remove(transferId)?.let { request ->
            sendTo(request.senderId, Protocol.TYPE_FILE_REJECT, RejectedFile(transferId, "Declined by user"), RejectedFile.serializer())
        }
    }

    fun shutdown() {
        incoming.values.forEach { try { it.handle.close() } catch (_: Exception) { } }
        incoming.clear()
        outgoing.clear()
        scope.cancel()
    }

    private val pendingRequests = ConcurrentHashMap<String, FileRequest>()

    private fun handleRequest(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val request = json.decodeFromString(FileRequest.serializer(), String(frame.payload, Charsets.UTF_8))
            require(request.senderId == connection.remoteDeviceId && request.receiverId == identity.deviceId)
            require(request.fileSize >= 0 && request.totalChunks == (request.fileSize + request.chunkSize - 1) / request.chunkSize)
            pendingRequests[request.transferId] = request
            onIncomingRequest(request)
        }.onFailure { Log.w(TAG, "Invalid FILE_REQUEST", it) }
    }

    private fun handleAccept(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val accepted = json.decodeFromString(FileAccept.serializer(), String(frame.payload, Charsets.UTF_8))
            val transfer = outgoing[accepted.transferId] ?: return
            require(transfer.peerId == connection.remoteDeviceId && accepted.resumeOffset == 0L)
            scope.launch { sendChunks(transfer) }
        }.onFailure { Log.w(TAG, "Invalid FILE_ACCEPT", it) }
    }

    private suspend fun sendChunks(transfer: Outgoing) {
        try {
            context.contentResolver.openInputStream(transfer.uri)?.use { input ->
                if (transfer.metadata.totalChunks == 0L) {
                    sendTo(transfer.peerId, Protocol.TYPE_FILE_COMPLETE, FileComplete(transfer.metadata.transferId, transfer.metadata.sha256), FileComplete.serializer())
                    return@use
                }
                var index = 0L
                val buffer = ByteArray(transfer.metadata.chunkSize)
                while (index < transfer.metadata.totalChunks) {
                    var length = 0
                    while (length < buffer.size) {
                        val count = input.read(buffer, length, buffer.size - length)
                        if (count < 0) break
                        length += count
                    }
                    require(length > 0) { "File ended before all chunks were read" }
                    val payload = FileChunk(transfer.metadata.transferId, index, Base64.encodeToString(buffer.copyOf(length), Base64.NO_WRAP))
                    sendToAwait(transfer.peerId, Protocol.TYPE_FILE_CHUNK, payload, FileChunk.serializer())
                    index++
                }
            } ?: error("Unable to reopen selected file")
            onTransferUpdate("Sent ${transfer.metadata.fileName} data; waiting for verification")
        } catch (e: Exception) {
            Log.e(TAG, "File send failed", e)
            cancel(transfer.metadata.transferId, transfer.peerId)
        }
    }

    private fun handleChunk(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val chunk = json.decodeFromString(FileChunk.serializer(), String(frame.payload, Charsets.UTF_8))
            val state = incoming[chunk.transferId] ?: return
            require(state.peerId == connection.remoteDeviceId)
            val bytes = Base64.decode(chunk.data, Base64.NO_WRAP)
            val offset = chunk.chunkIndex * state.metadata.chunkSize
            require(chunk.chunkIndex in 0 until state.metadata.totalChunks && bytes.size.toLong() <= state.metadata.fileSize - offset)
            synchronized(state.handle) { state.handle.seek(offset); state.handle.write(bytes) }
            state.received.add(chunk.chunkIndex)
            sendTo(state.peerId, Protocol.TYPE_FILE_CHUNK_ACK, FileChunkAck(chunk.transferId, chunk.chunkIndex), FileChunkAck.serializer())
        }.onFailure { Log.w(TAG, "Invalid FILE_CHUNK", it) }
    }

    private fun handleChunkAck(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val ack = json.decodeFromString(FileChunkAck.serializer(), String(frame.payload, Charsets.UTF_8))
            val transfer = outgoing[ack.transferId] ?: return
            require(transfer.peerId == connection.remoteDeviceId)
            transfer.acked.add(ack.chunkIndex)
            if (transfer.acked.size.toLong() == transfer.metadata.totalChunks) {
                sendTo(transfer.peerId, Protocol.TYPE_FILE_COMPLETE, FileComplete(ack.transferId, transfer.metadata.sha256), FileComplete.serializer())
            }
        }.onFailure { Log.w(TAG, "Invalid FILE_CHUNK_ACK", it) }
    }

    private fun handleComplete(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val complete = json.decodeFromString(FileComplete.serializer(), String(frame.payload, Charsets.UTF_8))
            val state = incoming[complete.transferId] ?: return
            require(state.peerId == connection.remoteDeviceId)
            if (state.received.size.toLong() != state.metadata.totalChunks) error("Some file chunks are missing")
            val digest = MessageDigest.getInstance("SHA-256")
            state.file.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
            }
            require(digest.digest().toHex().equals(state.metadata.sha256, ignoreCase = true)) { "File hash mismatch" }
            state.handle.close()
            incoming.remove(complete.transferId)
            sendTo(state.peerId, Protocol.TYPE_FILE_VERIFIED, FileVerified(complete.transferId), FileVerified.serializer())
            onTransferUpdate("Received ${state.metadata.fileName} to ${state.file.absolutePath}")
        }.onFailure { Log.w(TAG, "Incoming file verification failed", it) }
    }

    private fun handleVerified(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val verified = json.decodeFromString(FileVerified.serializer(), String(frame.payload, Charsets.UTF_8))
            outgoing[verified.transferId]?.let { transfer ->
                require(transfer.peerId == connection.remoteDeviceId)
                outgoing.remove(verified.transferId)
                onTransferUpdate("${transfer.metadata.fileName} received and verified")
            }
        }.onFailure { Log.w(TAG, "Invalid FILE_VERIFIED", it) }
    }

    private fun handleReject(connection: ConnectionManager.Connection, frame: Frame) {
        val node = runCatching { json.parseToJsonElement(String(frame.payload, Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        val transferId = node["transferId"]?.toString()?.trim('"') ?: return
        outgoing[transferId]?.let { transfer ->
            if (transfer.peerId == connection.remoteDeviceId) {
                outgoing.remove(transferId)
                onTransferUpdate("${transfer.metadata.fileName} was rejected")
            }
        }
    }

    private fun handleCancel(connection: ConnectionManager.Connection, frame: Frame) {
        runCatching {
            val cancelled = json.decodeFromString(FileCancel.serializer(), String(frame.payload, Charsets.UTF_8))
            incoming[cancelled.transferId]?.let { state ->
                if (state.peerId == connection.remoteDeviceId) incoming.remove(cancelled.transferId)?.handle?.close()
            }
            outgoing[cancelled.transferId]?.let { state ->
                if (state.peerId == connection.remoteDeviceId) outgoing.remove(cancelled.transferId)
            }
        }
    }

    private fun <T> sendTo(peerId: String, type: Byte, payload: T, serializer: KSerializer<T>) {
        val connection = connections.getConnection(peerId) ?: return
        send(connection, type, payload, serializer)
    }

    private fun <T> send(connection: ConnectionManager.Connection, type: Byte, payload: T, serializer: KSerializer<T>) {
        val text = json.encodeToString(serializer, payload)
        connections.sendFrame(connection, Frame.create(type, 0, UUID.randomUUID(), text.toByteArray(Charsets.UTF_8)))
    }

    private suspend fun <T> sendToAwait(peerId: String, type: Byte, payload: T, serializer: KSerializer<T>) {
        val connection = connections.getConnection(peerId) ?: error("Peer disconnected")
        val text = json.encodeToString(serializer, payload)
        connections.sendFrameAwait(connection, Frame.create(type, 0, UUID.randomUUID(), text.toByteArray(Charsets.UTF_8)))
    }

    private fun cancel(transferId: String, peerId: String) {
        sendTo(peerId, Protocol.TYPE_FILE_CANCEL, FileCancel(transferId), FileCancel.serializer())
        outgoing.remove(transferId)
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 } ?: return@use null) else null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @kotlinx.serialization.Serializable private data class RejectedFile(val transferId: String, val reason: String)
    @kotlinx.serialization.Serializable private data class FileCancel(val transferId: String)

    companion object { private const val TAG = "AndroidFileTransfer" }
}
