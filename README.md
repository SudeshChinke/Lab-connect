<<<<<<< ours
# Lab-connect
GitGub version
=======
# LabConnect

LabConnect is a decentralized LAN discovery and transport layer for peer-to-peer communication. It is a desktop (Java/JavaFX) project that discovers other LabConnect instances on the same local network via UDP multicast, and exchanges framed messages over a non-blocking TCP transport. The application layer for chat and file transfer is incomplete; this repository is shared as an engineering snapshot.

## Status (truthful)

- Discovery and TCP transport: implemented and verified between two live processes. 85 automated tests pass (including a two-process frame-delivery test).
- Chat, file transfer, TLS/mTLS, and pairing: stubbed (methods exist but have empty bodies). The Connect/New Chat/Send File actions in the UI do not deliver real behaviour.
- Build: `mvn clean package` succeeds on Linux (and will build for Windows on Windows). The shaded JAR bundles platform natives for the OS it was built on, so do not copy a JAR between OSes — build on the target OS instead.

For a precise inventory of what works and what doesn't, see `STATUS_REPORT.txt`. For a detailed engineering log, see `REVIEW_SUMMARY.txt`. 

## Quick start

1. **Prerequisites**: JDK 21 or newer, Apache Maven 3.6+.
2. **Clone and build**:
   ```bash
   git clone https://github.com/SudeshChinke/LabConnect.git
   cd LabConnect
   mvn clean package
   ```
3. **Run**:
   ```bash
   java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar
   ```
   On first launch, if `config.yaml` is missing, the app copies `config.example.yaml` to `config.yaml` automatically. The window title shows the device name from config. The UI lists only other LabConnect instances (discovered via multicast), not arbitrary hosts.

## Multi-instance testing

**Single machine (two windows)**:
```bash
# Peer A
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar

# Peer B in a separate directory
mkdir -p /tmp/peerB
cp config.example.yaml /tmp/peerB/config.yaml
# edit /tmp/peerB/config.yaml to use name: peerB and tcpPort: 5001
(cd /tmp/peerB && java -jar /path/to/LabConnect/target/labconnect-core-1.0.0-SNAPSHOT.jar)
```

**Two machines on the same LAN**: build on each, ensure `config.yaml` has a unique `name`, run both. Allow discovery/transport through the firewall.

- Linux (ufw): `sudo ufw allow 5000/tcp && sudo ufw allow 50001/udp`
- Windows (PowerShell, admin): 
  ```powershell
  New-NetFirewallRule -DisplayName "LabConnect UDP" -Direction Inbound -Protocol UDP -LocalPort 50001 -Action Allow
  New-NetFirewallRule -DisplayName "LabConnect TCP" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow
  ```

Ports: UDP 50001 (multicast), TCP 5000 (peer connections). Networks that filter multicast (some guest Wi-Fi, VLANs) may prevent discovery.

## What works / doesn't

| Feature | State |
|---|---|
| UDP multicast discovery | Working, verified |
| TCP framing, heartbeats, partial-write handling | Working, verified |
| Config (YAML) | Working |
| JavaFX UI, device list (refresh to poll) | Working |
| Chat (send/receive) | Stub |
| File transfer (chunking/queueing plumbing incomplete) | Stub |
| TLS/mTLS (encryption) | Not implemented (plaintext today) |
| Pairing/TOFU | Stub |
| Mobile (Android) | Not built here |

## Development notes

- Tests: `mvn test` (85 tests). The `P2PFrameDeliveryTest` exercises real socket delivery in both directions and across partial writes.
- Config is per-machine and gitignored; `config.example.yaml` is tracked. Don't commit real `config.yaml` files.
- The shaded JAR is platform-locked to the build OS. Cross-OS jar reuse is not supported.
- No Git history rewrites were performed here; only the working tree was cleaned for personal/local paths.
- This is an engineering snapshot: discovery and transport are solid, application features are incomplete. See `STATUS_REPORT.txt` for a concrete implementation plan. 

## License

MIT License. See `LICENSE` for details.
>>>>>>> theirs
