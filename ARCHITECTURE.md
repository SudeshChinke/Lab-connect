# LabConnect — Architecture

> Design reference only. Some sections describe intended capabilities, including
> TLS and group messaging, that are not active in the current app. For current
> behavior and security limits, see [README.md](README.md) and [SECURITY.md](SECURITY.md).

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        LabConnect Peer                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │   Desktop   │  │  Android    │  │   Shared    │             │
│  │  (JavaFX)   │  │  (Kotlin)   │  │   Core      │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         │                │                │                     │
│         └────────────────┼────────────────┘                     │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Core Modules                          │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐  │   │
│  │  │Protocol │ │Networking│ │Discovery│ │  Messaging   │  │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └──────────────┘  │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐  │   │
│  │  │ Transfer│ │ Security│ │  Models │ │  Diagnostics │  │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └──────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  Transport Layer                         │   │
│  │  ┌─────────────────┐         ┌─────────────────┐        │   │
│  │  │   TCP (TLS)     │         │     UDP         │        │   │
│  │  │   Port 5000     │         │   Port 50001    │        │   │
│  │  │   Messaging,    │         │   Discovery,    │        │   │
│  │  │   File Transfer │         │   Announcements │        │   │
│  │  └─────────────────┘         └─────────────────┘        │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## 2. Decentralized Peer-to-Peer Model

### Why No Central Server?
- **Resilience**: Any device can fail without affecting others
- **Simplicity**: No server deployment, configuration, or maintenance
- **Privacy**: No central point of data collection
- **Offline-First**: Works in air-gapped environments
- **Scalability**: Natural mesh scales with participants

### Peer Capabilities
Every device implements:
- **Discovery**: Announce presence, discover peers
- **Connection Management**: Initiate/accept TLS connections
- **Messaging**: Send/receive text, groups, broadcasts
- **File Transfer**: Stream files with checksums
- **Identity**: Persistent device ID, key pair
- **State**: Local message/transfer history

## 3. Network Topology

```
Wi-Fi Router (Layer 2 switch + AP)
    │
    ├── PC 1 (192.168.1.10:5000) ◄──┐
    ├── PC 2 (192.168.1.11:5000) ◄──┤  Full Mesh
    ├── Phone 1 (192.168.1.12:5000) ◄┤  (each peer connects
    └── Phone 2 (192.168.1.13:5000) ◄┘   to every other)
```

**No star topology**. Every peer connects directly to every other peer.
- Discovery: UDP multicast → learns peer IPs/ports
- Connections: TCP/TLS mesh (n² connections worst case)
- For 4 devices: 6 TCP connections

## 4. Module Breakdown

The desktop client is a **single Maven module** (`com.labconnect.core`,
declared in `src/main/java/module-info.java`). The sections below are Java
*packages* under `src/main/java/com/labconnect/`, not separate directories or
build modules. The `core.*` packages are UI-free and independently testable;
the `desktop.*` packages hold the JavaFX client.

### `com.labconnect.core.protocol`
- **Message Framing**: Length-prefixed binary frames
- **Message Types**: Enum of all protocol messages
- **Serialization**: JSON for control, binary for file chunks
- **Versioning**: Protocol version negotiation

### `com.labconnect.core.networking`
- **ConnectionManager**: Lifecycle of all TCP connections (implemented, verified)
- **Frame Codec**: Encode/decode framed messages (implemented, verified)
- **Event Loop**: Non-blocking I/O via `Selector` (implemented, verified)
- **TLS Engine**: **NOT IMPLEMENTED.** `TlsContextManager` has empty methods
  and the transport uses a plain `SocketChannel`, so traffic is unencrypted
  plaintext. Treat every diagram in this document showing a TLS layer as the
  intended design, not the current build.

### `com.labconnect.core.discovery`
- **Announcer**: Periodic UDP multicast/broadcast
- **Listener**: Receives announcements, updates registry
- **Registry**: Thread-safe peer cache (deviceId → PeerInfo)
- **Manual Connect**: Direct IP:port connection fallback

### `com.labconnect.core.messaging`
- **MessageRouter**: Dispatch by message type
- **ChatManager**: 1-to-1, group, broadcast logic — **STUB.** `sendMessage`,
  `sendGroupMessage` and `handleIncomingMessage` have empty bodies.
- **AckTracker**: Message ID → delivery state
- **Deduplicator**: Recent message ID cache (LRU)

### `com.labconnect.core.transfer`
- **TransferManager**: Queue, concurrency, priorities — **STUB.** The
  scheduling side (`TransferQueue`, `TransferPriority`, `TransferTask`
  comparison) is implemented and unit-tested, but every wire method
  (`sendFileRequest`, `sendFileAccept`, `sendFileChunk`, `sendFileComplete`) has
  an empty body. `sendFile()` hashes the file, builds metadata and queues a task,
  then sends nothing.
- **TransferTask**: Single file transfer state machine
- **ChunkStream**: Read/write file in 64KB chunks
- **ChecksumEngine**: Streaming SHA-256 (sender + receiver) — **implemented**
- **ResumeStore**: Partial file metadata for resume

Note: the transport now correctly handles 64KB chunks and payloads far larger
than the socket buffer (`P2PFrameDeliveryTest` proves 512KB round-trips), so
chunked transfer would work at the transport layer once `TransferManager`
actually emits the frames.

### `com.labconnect.core.security`
- **IdentityManager**: Generate/load device ID + key pair — device ID derivation
  is a comment, not code
- **PairingManager**: Trust-on-first-use + user confirmation — **STUB**
- **TrustStore**: Persisted trusted peer certificates
- **Crypto**: TLS 1.3, AES-GCM for application payload — **NOT IMPLEMENTED**

### `com.labconnect.core.models`
- **DeviceInfo**: id, name, type, IP, port, capabilities
- **Message**: id, type, timestamp, payload, ack status
- **Transfer**: id, file meta, chunks, progress, state
- **PeerState**: online/offline, last seen, connection status

### `com.labconnect.core.diagnostics`
- **NetworkInspector**: Interfaces, IPs, routes
- **ConnectionMonitor**: Active connections, health
- **DiscoveryStatus**: Announces sent/received, peers
- **ErrorReporter**: Categorized error messages

## 5. Threading Model

### Desktop (JavaFX)
```
JavaFX Application Thread  ──► UI only
     │
     ├── Platform.runLater() for UI updates
     │
     ▼
Worker Threads (ExecutorService)
     ├── Discovery: UDP recv loop (1 thread)
     ├── ConnectionManager: Selector loop (1 thread)
     ├── TransferManager: Transfer workers (fixed pool, 4)
     └── Security: Key ops (single thread)
```

### Android
```
Main Thread (UI)  ──► Jetpack Compose / Views only
     │
     ├── Coroutines (Dispatchers.Main) for UI
     │
     ▼
Background (Dispatchers.IO)
     ├── Discovery: DatagramChannel recv
     ├── ConnectionManager: SocketChannel selector
     ├── TransferManager: File I/O + network
     └── Foreground Service: Keep-alive
```

**Rule**: Zero blocking on UI threads. All I/O in background.

## 6. Data Flow Examples

### Device Discovery
```
Peer A                          Peer B
  │                               │
  ├─ UDP Announce (DEVICE_ANNOUNCE) ──►│
  │       {deviceId, name, port}       │
  │◄─── UDP Announce (DEVICE_ANNOUNCE) ┤
  │       {deviceId, name, port}       │
  │                               │
  ▼                               ▼
Registry.add(B)             Registry.add(A)
```

### TCP Connection Establishment
```
Peer A                          Peer B
  │                               │
  ├─ TCP Connect (TLS ClientHello) ─►│
  │◄─── TLS ServerHello ────────────┤
  │       ... TLS handshake ...     │
  │                               │
  ├─ HELLO {deviceId, pubKey} ─────►│
  │◄─── ACK {deviceId, pubKey} ────┤
  │                               │
  ▼                               ▼
Connection ESTABLISHED      Connection ESTABLISHED
```

### File Transfer
```
Sender                          Receiver
  │                               │
  ├─ FILE_REQUEST {id, name, size,  │
  │   sha256, chunks} ────────────►│
  │◄─── FILE_ACCEPT {id, offset=0} ┤
  │                               │
  ├─ FILE_CHUNK {id, seq, data} ──►│  (streaming, 64KB)
  │    ... more chunks ...        │
  │◄─── FILE_CHUNK_ACK {id, seq} ┤   (sliding window)
  │                               │
  ├─ FILE_COMPLETE {id, sha256} ──►│
  │◄─── FILE_VERIFIED {id, ok} ───┤
  │                               │
  ▼                               ▼
```

## 7. Failure Handling

| Failure | Detection | Recovery |
|---------|-----------|----------|
| Peer offline | Missed 3 heartbeats (15s) | Mark offline, keep in registry |
| TCP connection lost | Read timeout / IOException | Reconnect with backoff |
| UDP discovery blocked | No peers after 30s | Show manual connect UI |
| File transfer interrupted | Connection lost mid-transfer | Resume from last ACKed chunk |
| Checksum mismatch | Final SHA-256 ≠ declared | Reject file, notify user |
| Duplicate device ID | Registry collision | Reject new, log warning |

## 8. Security Architecture

```
┌────────────────────────────────────────┐
│         Device Identity                │
│  Ed25519 key pair (persistent)         │
│  Device ID = SHA-256(pubKey)[:16]      │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│         Pairing (TOFU)                 │
│  1. Connection request                 │
│  2. Show peer fingerprint to user      │
│  3. User accepts → store cert          │
│  4. Future: auto-accept trusted        │
└──────────────┬─────────────────────────┘
               │
               ▼
┌────────────────────────────────────────┐
│         Encrypted Channel              │
│  TLS 1.3 with mutual auth              │
│  Peer cert pinned after pairing        │
└────────────────────────────────────────┘
```

## 9. Android-Specific Considerations

| Challenge | Solution |
|-----------|----------|
| Background execution limits | Foreground Service with notification |
| Wi-Fi sleep | WifiManager.WifiLock + WakeLock |
| Scoped storage | MediaStore for downloads, SAF for picks |
| Permission model | Runtime permissions (LOCAL_NETWORK, NEARBY_WIFI_DEVICES) |
| Lifecycle | Service survives Activity recreation |
| Discovery | NsdManager for mDNS + raw UDP multicast |

## 10. Configuration

```yaml
# config.yaml (per device)
device:
  name: "PC-01"
  type: "DESKTOP"  # DESKTOP | MOBILE
network:
  tcpPort: 5000
  discoveryPort: 50001
  multicastGroup: "239.255.255.250"
  announceIntervalSec: 5
  heartbeatIntervalSec: 5
  connectionTimeoutSec: 10
transfer:
  chunkSize: 65536
  maxConcurrentTransfers: 4
  resumeEnabled: true
security:
  requirePairing: true
  tlsVersion: "TLSv1.3"
```

## 11. Testing Strategy

- Desktop unit and integration tests are in `src/test`; run them with `mvn test`.
- Android protocol unit tests are in `android/app/src/test`.
- Real cross-device tests across Windows, Linux Mint, and Android remain to be
  completed. TLS and group messaging are not verified app features.
