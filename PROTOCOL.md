# LabConnect — Wire Protocol Specification

## Version
**Protocol Version: 1.0**

## 1. Transport Layer

### TCP (Primary)
- **Port**: 5000 (configurable)
- **Security**: TLS 1.3 mandatory
- **Usage**: All application messages, file transfer
- **Framing**: Length-prefixed binary frames

### UDP (Discovery)
- **Port**: 50001 (configurable)
- **Multicast Group**: 239.255.255.250
- **Broadcast Fallback**: 255.255.255.255
- **Usage**: Device announcements only
- **Format**: JSON (UTF-8)

## 2. TCP Frame Format

```
┌─────────────────────────────────────────────────────────────┐
│                    FRAME HEADER (8 bytes)                    │
├──────────────┬──────────────┬────────────────────────────────┤
│  Length (4B) │  Type (1B)   │   Flags (1B)   │ Reserved (2B) │
│  Big Endian  │  (MsgType)   │  (bitfield)    │   (zero)      │
└──────────────┴──────────────┴────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│                    FRAME PAYLOAD (Length bytes)              │
│                                                              │
│  ┌──────────────┬────────────────────────────────────────┐  │
│  │ Message ID   │         Message Payload                │  │
│  │ (16 bytes)   │         (Length - 16 bytes)            │  │
│  │ UUID v4      │                                        │  │
│  └──────────────┴────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Header Fields
| Field | Size | Description |
|-------|------|-------------|
| Length | 4 bytes | Total frame size (header + payload), big-endian, max 16MB |
| Type | 1 byte | Message type (see Section 4) |
| Flags | 1 byte | Bit 0: COMPRESSED, Bit 1: ENCRYPTED_APP, Bit 2: FRAGMENTED |
| Reserved | 2 bytes | Must be zero |

### Payload Structure
| Field | Size | Description |
|-------|------|-------------|
| Message ID | 16 bytes | UUID v4 (RFC 4122), unique per message |
| Message Body | Variable | Type-specific payload (JSON or binary) |

### Special Frames
| Type | Name | Purpose |
|------|------|---------|
| 0x00 | PING | Keep-alive, no payload |
| 0x01 | PONG | Response to PING |
| 0xFE | FRAGMENT_START | First fragment of large message |
| 0xFF | FRAGMENT_CONT | Continuation fragment |

## 3. Message ID Generation
- **Algorithm**: UUID v4 (random)
- **Format**: 16-byte binary (not string)
- **Deduplication**: Receivers track last 10,000 IDs (LRU)
- **Collision**: Statistically negligible; if detected, treat as duplicate

## 4. Message Types

### Control Messages (0x10–0x1F)

| Value | Name | Direction | Description |
|-------|------|-----------|-------------|
| 0x10 | HELLO | Both | Initial handshake after TLS |
| 0x11 | ACK | Both | Generic acknowledgement |
| 0x12 | ERROR | Both | Protocol error |
| 0x13 | GOODBYE | Both | Graceful disconnect |
| 0x14 | HEARTBEAT | Both | Keep-alive with timestamp |

### Discovery Messages (UDP only, 0x20–0x2F)

| Value | Name | Description |
|-------|------|-------------|
| 0x20 | DEVICE_ANNOUNCE | Periodic broadcast |
| 0x21 | DEVICE_QUERY | Request announcement |
| 0x22 | DEVICE_RESPONSE | Direct response to query |

### Messaging (0x30–0x3F)

| Value | Name | Description |
|-------|------|-------------|
| 0x30 | TEXT_MESSAGE | 1-to-1 chat |
| 0x31 | MESSAGE_ACK | Delivery confirmation |
| 0x32 | MESSAGE_READ | Read receipt |
| 0x33 | GROUP_MESSAGE | Group chat |
| 0x34 | BROADCAST_MESSAGE | All peers |
| 0x35 | MESSAGE_RECALL | Delete for everyone |

### File Transfer (0x40–0x4F)

| Value | Name | Description |
|-------|------|-------------|
| 0x40 | FILE_REQUEST | Initiate transfer |
| 0x41 | FILE_ACCEPT | Accept + resume offset |
| 0x42 | FILE_REJECT | Decline transfer |
| 0x43 | FILE_CHUNK | Data chunk (binary) |
| 0x44 | FILE_CHUNK_ACK | Chunk received |
| 0x45 | FILE_COMPLETE | All chunks sent |
| 0x46 | FILE_VERIFIED | Checksum match |
| 0x47 | FILE_CANCEL | Abort transfer |
| 0x48 | FILE_RESUME | Request resume |
| 0x49 | FILE_PAUSE | Pause transfer |

### Security (0x50–0x5F)

| Value | Name | Description |
|-------|------|-------------|
| 0x50 | PAIR_REQUEST | Initiate pairing |
| 0x51 | PAIR_ACCEPT | Accept pairing |
| 0x52 | PAIR_REJECT | Reject pairing |
| 0x53 | KEY_ROTATE | Rotate session keys |

## 5. Message Payloads (JSON unless noted)

### HELLO (0x10)
```json
{
  "protocolVersion": "1.0",
  "deviceId": "DEVICE-7F82A1B4",
  "deviceName": "PC-01",
  "deviceType": "DESKTOP",
  "publicKey": "base64(Ed25519_pub)",
  "capabilities": ["FILE_TRANSFER", "GROUPS", "ENCRYPTION"],
  "supportedCompression": ["zstd", "none"]
}
```

### ACK (0x11)
```json
{
  "originalMessageId": "uuid-of-original",
  "status": "OK",
  "timestamp": 1700000000000
}
```

### ERROR (0x12)
```json
{
  "code": "CONNECTION_REFUSED",
  "message": "Device not paired",
  "recoverable": false
}
```

### HEARTBEAT (0x14)
```json
{
  "timestamp": 1700000000000,
  "sequence": 42
}
```

### DEVICE_ANNOUNCE (0x20) — UDP JSON
```json
{
  "type": "DEVICE_ANNOUNCE",
  "deviceId": "DEVICE-7F82A1B4",
  "deviceName": "PC-01",
  "deviceType": "DESKTOP",
  "protocolVersion": "1.0",
  "tcpPort": 5000,
  "capabilities": ["FILE_TRANSFER", "GROUPS", "ENCRYPTION"],
  "timestamp": 1700000000000
}
```

### TEXT_MESSAGE (0x30)
```json
{
  "chatId": "CHAT-DEVICE-7F82...-DEVICE-93A1...",
  "content": "Hello, world!",
  "contentType": "text/plain",
  "replyTo": null
}
```

### MESSAGE_ACK (0x31)
```json
{
  "messageId": "uuid-of-message",
  "status": "DELIVERED",
  "timestamp": 1700000000000
}
```

### GROUP_MESSAGE (0x33)
```json
{
  "groupId": "GROUP-ABC123",
  "content": "Team meeting at 3pm",
  "contentType": "text/plain"
}
```

### FILE_REQUEST (0x40)
```json
{
  "transferId": "XFER-ABC123DEF",
  "fileName": "video.mp4",
  "fileSize": 1073741824,
  "mimeType": "video/mp4",
  "sha256": "hex-sha256-of-entire-file",
  "chunkSize": 65536,
  "totalChunks": 16384,
  "senderId": "DEVICE-7F82A1B4",
  "receiverId": "DEVICE-93A1C2D3"
}
```

### FILE_ACCEPT (0x41)
```json
{
  "transferId": "XFER-ABC123DEF",
  "accepted": true,
  "resumeOffset": 0,
  "receiverSha256": "hex-sha256-of-partial-file"
}
```

### FILE_CHUNK (0x43) — Binary Payload
```
┌─────────────────────────────────────────────────────────────┐
│ Message ID (16 bytes)                                       │
├─────────────────────────────────────────────────────────────┤
│ Transfer ID (16 bytes, UUID)                                │
├─────────────────────────────────────────────────────────────┤
│ Chunk Sequence (4 bytes, big-endian, 0-based)               │
├─────────────────────────────────────────────────────────────┤
│ Chunk Data (Length - 36 bytes, max 65536)                   │
└─────────────────────────────────────────────────────────────┘
```

### FILE_CHUNK_ACK (0x44)
```json
{
  "transferId": "XFER-ABC123DEF",
  "chunkSequence": 1234,
  "status": "OK"
}
```

### FILE_COMPLETE (0x45)
```json
{
  "transferId": "XFER-ABC123DEF",
  "senderSha256": "hex-sha256-computed-by-sender"
}
```

### FILE_VERIFIED (0x46)
```json
{
  "transferId": "XFER-ABC123DEF",
  "verified": true,
  "receiverSha256": "hex-sha256-computed-by-receiver"
}
```

### FILE_RESUME (0x48)
```json
{
  "transferId": "XFER-ABC123DEF",
  "requestedOffset": 67108864,
  "knownSha256": "hex-sha256-of-first-64MB"
}
```

### PAIR_REQUEST (0x50)
```json
{
  "deviceId": "DEVICE-7F82A1B4",
  "deviceName": "PC-01",
  "publicKey": "base64(Ed25519_pub)",
  "fingerprint": "SHA-256:AB:CD:EF:..."
}
```

## 6. Protocol Flow Examples

### Connection Handshake
```
Client                          Server
  │                               │
  ├─ TLS ClientHello ───────────►│
  │◄─── TLS ServerHello ────────┤
  │   ... TLS 1.3 handshake ...  │
  │                               │
  ├─ HELLO ─────────────────────►│
  │◄─── ACK (HELLO_OK) ─────────┤
  │                               │
  ▼                               ▼
Ready for application messages
```

### File Transfer (Sender → Receiver)
```
Sender                          Receiver
  │                               │
  ├─ FILE_REQUEST ──────────────►│
  │◄─── FILE_ACCEPT (offset=0) ──┤
  │                               │
  ├─ FILE_CHUNK (seq=0) ────────►│
  │◄─── FILE_CHUNK_ACK (seq=0) ──┤
  ├─ FILE_CHUNK (seq=1) ────────►│
  │◄─── FILE_CHUNK_ACK (seq=1) ──┤
  │         ...                   │
  ├─ FILE_CHUNK (seq=N-1) ──────►│
  │◄─── FILE_CHUNK_ACK (seq=N-1) ┤
  │                               │
  ├─ FILE_COMPLETE ─────────────►│
  │◄─── FILE_VERIFIED ──────────┤
  │                               │
  ▼                               ▼
```

### Resume After Interruption
```
Sender                          Receiver
  │                               │
  ├─ FILE_RESUME ───────────────►│
  │   (offset=64MB, knownSha256) │
  │◄─── FILE_ACCEPT ────────────┤
  │   (resumeOffset=64MB,        │
  │    receiverSha256=matches)   │
  │                               │
  ├─ FILE_CHUNK (seq=1024) ────►│  (continues from chunk 1024)
  │         ...                   │
  ▼                               ▼
```

## 7. Sliding Window for File Transfer

- **Window Size**: 32 chunks (configurable, 2MB in-flight)
- **Sender**: Sends up to window size without waiting
- **Receiver**: ACKs each chunk, processes in order
- **Backpressure**: Sender pauses on window full
- **Retransmit**: Unacked chunks after 5s timeout

## 8. Chunk Size
- **Default**: 65536 bytes (64 KB)
- **Minimum**: 4096 bytes
- **Maximum**: 1048576 bytes (1 MB)
- **Negotiation**: In FILE_REQUEST/FILE_ACCEPT

## 9. Checksum Verification

### Sender
1. Compute SHA-256 streaming while reading file
2. Include in FILE_REQUEST
3. Re-compute for FILE_COMPLETE (must match)

### Receiver
1. Compute SHA-256 streaming while writing chunks
2. On FILE_COMPLETE: compare with sender's hash
3. Send FILE_VERIFIED with result

### Resume Verification
- Receiver sends `receiverSha256` of existing partial file in FILE_ACCEPT
- Sender verifies matches its `knownSha256` for that offset
- Mismatch → restart from zero

## 10. Heartbeat Protocol

- **Interval**: 5 seconds (configurable)
- **Missed Threshold**: 3 (15 seconds)
- **Message**: HEARTBEAT (0x14) with timestamp + sequence
- **Response**: ACK with same sequence
- **Action on Miss**: Mark peer offline, close TCP connection

## 11. Error Codes

| Code | Description |
|------|-------------|
| PROTOCOL_VERSION_MISMATCH | Incompatible versions |
| DEVICE_NOT_PAIRED | Connection from unknown device |
| INVALID_MESSAGE_ID | Duplicate or malformed ID |
| TRANSFER_NOT_FOUND | Unknown transferId |
| CHUNK_OUT_OF_ORDER | Sequence gap detected |
| CHECKSUM_MISMATCH | File integrity failure |
| INSUFFICIENT_STORAGE | Receiver disk full |
| PERMISSION_DENIED | User rejected |
| INTERNAL_ERROR | Unexpected exception |

## 12. Compatibility Rules

1. **Version Negotiation**: HELLO includes protocolVersion; reject if major differs
2. **Forward Compatibility**: Unknown message types → ignore + ACK
3. **Backward Compatibility**: New optional fields must have defaults
4. **Feature Flags**: Capabilities in HELLO/ANNOUNCE control behavior

## 13. Security Considerations

- All TCP traffic encrypted via TLS 1.3
- Application-layer encryption optional (Flags bit 1)
- Message IDs prevent replay attacks
- Pairing uses Trust-On-First-Use with user verification
- Device ID = SHA-256(publicKey)[:16] — cryptographic binding
- No plaintext secrets on wire

## 14. UDP Discovery Format

Sent to 239.255.255.250:50001 (multicast) and 255.255.255.255:50001 (broadcast)
every 5 seconds.

```json
{
  "type": "DEVICE_ANNOUNCE",
  "deviceId": "DEVICE-7F82A1B4",
  "deviceName": "PC-01",
  "deviceType": "DESKTOP",
  "protocolVersion": "1.0",
  "tcpPort": 5000,
  "capabilities": ["FILE_TRANSFER", "GROUPS", "ENCRYPTION"],
  "timestamp": 1700000000000
}
```

**Note**: UDP payload is plain JSON (no framing). Max size ~1400 bytes.

## 15. Manual Connection Fallback

When discovery fails, user enters:
- Target IP address
- Target TCP port (default 5000)

Client initiates TLS connection directly to IP:port.
Protocol proceeds identically from HELLO onward.

## 16. Implementation Notes

### Java (Desktop)
- Use `Selector` for non-blocking I/O
- `ByteBuffer` for frame encoding/decoding
- `SSLEngine` for TLS (not SSLSocket)
- Jackson for JSON serialization

### Kotlin (Android)
- `SocketChannel` + `Selector` or Okio/NIO2
- Kotlin Coroutines + Channels for async flow
- `SSLEngine` for TLS
- kotlinx.serialization for JSON

### Frame Codec (Pseudocode)
```java
// Encode
ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
buf.putInt(payload.length + 16);  // length includes msgId
buf.put((byte) msgType);
buf.put(flags);
buf.putShort(0);  // reserved
buf.put(msgIdBytes);  // 16 bytes
buf.put(payload);
buf.flip();
channel.write(buf);

// Decode
ByteBuffer header = ByteBuffer.allocate(8);
readFully(header);
int length = header.getInt();
byte type = header.get();
byte flags = header.get();
header.getShort();  // reserved
byte[] msgId = new byte[16];
readFully(msgId);
byte[] payload = new byte[length - 16];
readFully(payload);
```

## 17. Testing Vectors

### Valid HELLO Frame
```
Length: 0x00000090 (144 bytes)
Type: 0x10
Flags: 0x00
Reserved: 0x0000
Message ID: 550e8400-e29b-41d4-a716-446655440000
Payload: {"protocolVersion":"1.0","deviceId":"DEVICE-7F82A1B4",...}
```

### Valid FILE_CHUNK Frame (seq=0, 64KB data)
```
Length: 0x00010024 (65572 bytes = 8 header + 16 msgId + 16 xferId + 4 seq + 65536 data)
Type: 0x43
Flags: 0x00
Reserved: 0x0000
Message ID: <uuid>
Payload: <16B xferId> <4B seq=0> <65536B data>
```

---
*End of Protocol Specification v1.0*