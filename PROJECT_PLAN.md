# LabConnect — Project Plan

## Overview
LabConnect is a decentralized LAN communication, messaging, and file transfer system designed for educational and practical use. It operates entirely on local networks without any dependency on internet, cloud services, or central servers.

## Project Goals
1. **Decentralized Architecture**: Every device is a peer; no single point of failure
2. **Cross-Platform**: Java/JavaFX desktop + Kotlin/Android mobile
3. **Real-World Testing**: Validated on actual hardware (2 PCs, 2 phones, 1 Wi-Fi router)
4. **Educational Value**: Clean, modular codebase demonstrating networking principles
5. **Security-First**: Pairing, authentication, and encrypted communication

## Development Phases

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Architecture & Planning | **IN PROGRESS** |
| 1 | Project Foundation (Java, Maven, JavaFX) | Pending |
| 2 | Basic TCP Connection | Pending |
| 3 | LAN Discovery (UDP) | Pending |
| 4 | Multi-Device System | Pending |
| 5 | Text Chat (1-to-1) | Pending |
| 6 | Group/Broadcast Chat | Pending |
| 7 | File Transfer Engine | Pending |
| 8 | File Transfer UI | Pending |
| 9 | Multiple Transfers | Pending |
| 10 | Resumable Transfers | Pending |
| 11 | Android Client | Pending |
| 12 | Security (Pairing, Encryption) | Pending |
| 13 | History | Pending |
| 14 | Diagnostics | Pending |
| 15 | Error Handling | Pending |
| 16 | UI Polish | Pending |
| 17 | Complete Hardware Test | Pending |
| 18 | Final Documentation | Pending |

## Hardware Test Matrix

| Test | Description | Devices |
|------|-------------|---------|
| 1 | PC1 ↔ PC2 | 2 |
| 2 | PC1 ↔ Phone1 | 2 |
| 3 | PC2 ↔ Phone2 | 2 |
| 4 | Phone1 ↔ Phone2 | 2 |
| 5 | PC1 → All (broadcast) | 4 |
| 6 | Group Chat | 4 |
| 7 | Large File Transfer | 2+ |
| 8 | Multiple Simultaneous Transfers | 3+ |
| 9 | Interrupt Transfer | 2 |
| 10 | Resume Transfer | 2 |
| 11 | PC1 Offline | 3 |
| 12 | PC2 Offline | 3 |
| 13 | Router Restart | 4 |
| 14 | IP Reassignment | 2+ |
| 15 | Firewall Block | 2 |

## Technology Stack

### Desktop
- **Language**: Java 17+ LTS
- **UI Framework**: JavaFX
- **Build Tool**: Maven
- **Networking**: Java NIO (non-blocking), TCP sockets, UDP multicast
- **Serialization**: JSON (Jackson/Gson)
- **Security**: TLS 1.3, SHA-256
- **Logging**: SLF4J + Logback
- **Testing**: JUnit 5, Testcontainers (if needed)

### Android
- **Language**: Kotlin
- **IDE**: Android Studio
- **Min SDK**: API 24 (Android 7.0)
- **Networking**: Java NIO / Kotlin Coroutines + Channels
- **Storage**: Scoped Storage (MediaStore, Storage Access Framework)
- **Background**: Foreground Service for discovery/connection maintenance
- **UI**: Jetpack Compose or XML + ViewBinding

### Shared
- **Protocol**: Custom binary framing over TCP + JSON control messages
- **Discovery**: UDP multicast (239.255.255.250:50001) + broadcast fallback
- **Transport**: TLS over TCP (port 5000 default)
- **File Integrity**: SHA-256 streaming verification

## Risk Assessment & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Multicast blocked by router | High | High | Broadcast fallback + manual IP entry |
| Windows Firewall blocks ports | High | High | Clear diagnostics + setup guide |
| Android background restrictions | High | Medium | Foreground service + wake locks |
| Different subnets (TP-Link as router) | Medium | High | Subnet detection + warnings |
| TCP message framing errors | Medium | High | Rigorous unit tests + integration tests |
| Large file memory issues | Low | High | Streaming/chunked design from start |
| Clock skew between devices | Low | Medium | Logical timestamps + NTP optional |

## Deliverables
- `PROJECT_PLAN.md` — This document
- `ARCHITECTURE.md` — System architecture
- `PROTOCOL.md` — Wire protocol specification
- Source code in `src/` (core + desktop packages) and `android/`
- `SETUP.md` — Build & run instructions
- `TESTING.md` — Test procedures
- `SECURITY.md` — Security model
- `TROUBLESHOOTING.md` — Common issues
- `PROJECT_REPORT.md` — Final report (Phase 18)

## Success Criteria
1. All 15 hardware tests pass
2. Zero central server dependency
3. File transfer >100MB verified with SHA-256
4. Interrupted transfer resumes correctly
4. Android ↔ Desktop interoperability
5. Clean diagnostics identifying network issues
6. All phases tested before proceeding