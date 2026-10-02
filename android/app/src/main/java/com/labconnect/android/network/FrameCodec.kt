package com.labconnect.android.network

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object FrameCodec {
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        const val HEADER_SIZE = 8
        const val MESSAGE_ID_SIZE = 16
        const val MAX_FRAME_SIZE = 16 * 1024 * 1024 // 16MB
        
        fun encode(frame: Frame): ByteArray {
            val payload = frame.payload
            val totalLength = HEADER_SIZE + MESSAGE_ID_SIZE + payload.size
            
            if (totalLength > MAX_FRAME_SIZE) {
                throw IllegalArgumentException("Frame too large: $totalLength")
            }
            
            val buffer = ByteBuffer.allocate(totalLength)
            buffer.order = ByteOrder.BIG_ENDIAN
            buffer.putInt(totalLength)
            buffer.put(frame.type)
            buffer.put(frame.flags)
            buffer.putShort(0) // reserved
            
            // Write UUID as 16 bytes
            val msb = frame.messageId.mostSignificantBits
            val lsb = frame.messageId.leastSignificantBits
            buffer.putLong(msb)
            buffer.putLong(lsb)
            
            buffer.put(payload)
            return buffer.array()
        }
        
        fun decode(buffer: ByteArray): Frame? {
            if (buffer.size < HEADER_SIZE) return null
            
            val bb = ByteBuffer.wrap(buffer)
            bb.order = ByteOrder.BIG_ENDIAN
            
            val length = bb.getInt()
            val type = bb.get()
            val flags = bb.get()
            bb.getShort() // reserved
            
            if (length < HEADER_SIZE + MESSAGE_ID_SIZE || length > MAX_FRAME_SIZE) {
                throw IllegalArgumentException("Invalid frame length: $length")
            }
            
            if (buffer.size < length) return null
            
            val msb = bb.getLong()
            val lsb = bb.getLong()
            val messageId = UUID(msb, lsb)
            
            val payloadLength = length - HEADER_SIZE - MESSAGE_ID_SIZE
            val payload = ByteArray(payloadLength)
            bb.get(payload)
            
            return Frame(type, flags, messageId, payload)
        }
        
        fun hasCompleteFrame(buffer: ByteArray): Boolean {
            if (buffer.size < HEADER_SIZE) return false
            val bb = ByteBuffer.wrap(buffer)
            bb.order = ByteOrder.BIG_ENDIAN
            val length = bb.getInt()
            return buffer.size >= length
        }
        
        fun parseJsonPayload(frame: Frame, clazz: KClass<*>): Any? {
            return try {
                json.decodeFromString(clazz, String(frame.payload, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }
}