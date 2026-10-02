package com.labconnect.android.network

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ProtocolTest {
    
    @Test
    fun testFrameCreation() {
        val frame = Frame.create(
            Protocol.TYPE_HELLO, 0, java.util.UUID.randomUUID(),
            "test".toByteArray()
        )
        
        assertEquals(Protocol.TYPE_HELLO, frame.type)
        assertEquals(0, frame.flags)
    }
    
    @Test
    fun testFrameEncodeDecode() {
        val messageId = java.util.UUID.randomUUID()
        val payload = "Hello, World!".toByteArray()
        
        val frame = Frame.create(Protocol.TYPE_TEXT_MESSAGE, 0, messageId, payload)
        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)
        
        assertNotNull(decoded)
        assertEquals(frame.type, decoded?.type)
        assertEquals(frame.flags, decoded?.flags)
        assertEquals(frame.messageId, decoded?.messageId)
        assertArrayEquals(frame.payload, decoded?.payload)
    }
    
    @Test
    fun testDeviceAnnounceSerialization() {
        val announce = DeviceAnnounce(
            deviceId = "TEST-123",
            deviceName = "Test Device",
            deviceType = "MOBILE",
            tcpPort = 50000,
            publicKey = "test-key"
        )
        
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.encodeToString(announce)
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(DeviceAnnounce.serializer(), json)
        
        assertEquals(announce.deviceId, parsed.deviceId)
        assertEquals(announce.deviceName, parsed.deviceName)
        assertEquals(announce.deviceType, parsed.deviceType)
    }
}