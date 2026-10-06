# LabConnect Security Model

## Overview
LabConnect implements a security-first decentralized architecture with no central authority. All security decisions are made peer-to-peer.

## Current Implementation Status (read this first)

**All LAN traffic is plaintext today.** The TLS 1.3/mTLS layer, pairing, and
trust store described in this document are the *designed* model. The
corresponding code (`TlsContextManager`, `PairingManager`, `TrustStore`) is
stubbed and never invoked by the running application, so:

- Chat messages and file transfers travel unencrypted on the local network.
- Peers are not authenticated — any host can open a connection.
- `config.security.tlsVersion` and `config.security.requirePairing` are read
  but have no effect.

What *is* implemented: the framing layer, heartbeat liveness, and SHA-256 file
integrity verification (when file transfer lands). Treat this document as the
specification the remaining security work (blocking errors E6/E7 in
`STATUS_REPORT.txt`) must deliver.

## Threat Model

### In Scope
- Eavesdropping on LAN traffic
- Man-in-the-middle attacks
- Device impersonation
- File tampering during transfer
- Replay attacks

### Out of Scope
- Physical device access
- Malware on endpoint devices
- Denial of service (flooding)
- Side-channel attacks

---

## Identity & Authentication

### Device Identity
- **Ed25519 key pair** generated on first run
- **Device ID**: `DEVICE-` + first 8 chars of public key fingerprint
- Stored in `keystore.dat` (encrypted with device password optional)

### Pairing (Trust-on-First-Use)
1. Device A initiates pairing with Device B
2. Device B shows prompt: "Device A (ID: xxx) requests pairing. Accept?"
3. User verifies **fingerprint** on both devices (optional but recommended)
4. On accept: both store each other's public key in `truststore.dat`
5. Future connections: automatic mutual authentication

### Trust Store
- `truststore.dat`: Map of `deviceId → {publicKey, deviceName, pairedAt}`
- Only paired devices can connect (when `requirePairing=true`)
- Can be cleared to reset all trust relationships

---

## Transport Security

### TLS 1.3
- **Mutual TLS (mTLS)**: Both client and server authenticate
- **Cipher suites**: TLS_AES_256_GCM_SHA384, TLS_CHACHA20_POLY1305_SHA256
- **Certificate**: Self-signed, derived from Ed25519 identity key
- **Verification**: Peer certificate validated against truststore

### Connection Flow
*(Designed flow — steps 2, 3 and 5 are not implemented yet; today it is
plain TCP + HELLO. See the status note above.)*
```
1. TCP connect
2. TLS handshake (mTLS)
3. Verify peer cert against truststore
4. Send HELLO frame with deviceId, publicKey
5. Peer verifies HELLO matches TLS cert
6. Connection established
```

---

## Message Security

### Frame Format
```
[4 bytes length][1 byte type][1 byte flags][16 bytes messageId][payload]
```

### Message Types
Current wire values (from `MessageType.java`). Nothing is encrypted yet — see
the status note at the top of this document.

| Type | Value | Encrypted | Signed |
|------|-------|-----------|--------|
| HELLO | 0x10 | No | No |
| HEARTBEAT | 0x14 | No | No |
| TEXT_MESSAGE | 0x30 | No | No |
| MESSAGE_ACK | 0x31 | No | No |
| FILE_CHUNK | 0x43 | No | No |
| PAIR_REQUEST | 0x50 | No | No |
| PAIR_ACCEPT | 0x51 | No | No |

### Integrity
- **Designed**: all frames protected by TLS record layer (AEAD)
- **Implemented**: file chunks carry an additional SHA-256 checksum in
  metadata; full file SHA-256 is verified on completion

---

## File Transfer Security

### Metadata (sent before transfer)
```json
{
  "fileId": "uuid",
  "fileName": "document.pdf",
  "fileSize": 1048576,
  "sha256": "hex-encoded-hash",
  "chunkCount": 16,
  "chunkSize": 65536
}
```

### Chunk Verification
- Each chunk: sequence number + payload
- Receiver computes rolling SHA-256
- Final verification against metadata hash

### Resumable Transfer Security
- Checkpoint file: `~/.labconnect/transfers/<fileId>.checkpoint`
- Contains: `offset, verifiedChunks[]`
- On resume: re-verify last completed chunk before continuing

---

## Network Defense

### Discovery
- **Multicast**: 239.255.255.250:50001 (signed announcements)
- **Broadcast fallback**: 255.255.255.250:50001
- **Announcement**: `{deviceId, deviceName, ip, port, publicKeyFingerprint, timestamp}`
- **Rate limited**: 1 announcement per 5 seconds

### Anti-Spoofing
- Device ID bound to public key
- Announcements include public key fingerprint
- Recipients verify fingerprint matches known identity (if paired)

### Heartbeat & Liveness
- **Interval**: 5 seconds (configurable)
- **Timeout**: 3 missed heartbeats = 15 seconds
- **Payload**: `{timestamp, sequence}`
- Prevents connection hijacking

---

## Data at Rest

### Keystore (`keystore.dat`)
- Ed25519 private key encrypted with AES-256-GCM
- Key derived from user password (PBKDF2, 100k iterations)
- Optional: can run without password (less secure)

### Truststore (`truststore.dat`)
- Plaintext JSON (public keys only)
- No sensitive material
- Can be backed up / synced between devices

### History (`history/`)
- Messages: Encrypted with per-chat key (derived from session)
- Transfers: Metadata only (file names, hashes, timestamps)
- No file content stored

---

## Android-Specific

### Permissions
| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Wi-Fi scanning for discovery |
| `NEARBY_WIFI_DEVICES` | Android 13+ Wi-Fi peer discovery |
| `FOREGROUND_SERVICE` | Background discovery/connection |
| `READ_MEDIA_*` | File picker for sending |
| `WRITE_EXTERNAL_STORAGE` | Receiving files (legacy) |

### Keystore
- Uses **Android Keystore** for Ed25519 key generation
- Hardware-backed when available (StrongBox/TEE)
- Biometric unlock optional for app access

### Network Security Config
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="user"/>  <!-- User-installed CAs only -->
        </trust-anchors>
    </domain-config>
</network-security-config>
```

---

## Security Configuration

### `config.yaml` Security Section
```yaml
security:
  requirePairing: true      # Reject connections from unpaired devices
  tlsVersion: "TLSv1.3"     # Minimum TLS version
  keyRotationDays: 90       # Suggested key rotation period
  allowUnpairedDiscovery: true  # Allow seeing unpaired devices in list
```

### Hardening Checklist
- [ ] `requirePairing: true` (default)
- [ ] Set keystore password
- [ ] Verify fingerprints during pairing
- [ ] Keep `truststore.dat` backed up
- [ ] Update Java/Android for TLS patches
- [ ] Monitor logs for connection anomalies

---

## Incident Response

### Compromised Device
1. Revoke: Delete device from truststore on all peers
2. Rotate: Generate new keypair on compromised device
3. Re-pair: Establish new trust relationships

### Suspected MITM
1. Check TLS certificate fingerprints match
2. Verify pairing fingerprints out-of-band
3. Check for unknown devices in truststore

### Log Analysis
```bash
# Failed connections
grep "Connection closed\|Handshake failed" logs/labconnect.log

# Pairing events
grep "Pairing" logs/labconnect.log

# Transfer verification failures
grep "Hash mismatch\|Checksum" logs/labconnect.log
```

---

## Cryptographic Libraries

| Component | Library | Algorithm |
|-----------|---------|-----------|
| TLS | JDK 17 built-in | TLS 1.3 |
| Ed25519 | BouncyCastle | Ed25519 |
| SHA-256 | JDK built-in | SHA-256 |
| AES-GCM | JDK built-in | AES-256-GCM |
| PBKDF2 | JDK built-in | PBKDF2-HMAC-SHA256 |

---

## Compliance
- No telemetry / no data collection
- No external network connections
- GDPR: No personal data stored (device names optional)
- Suitable for air-gapped networks