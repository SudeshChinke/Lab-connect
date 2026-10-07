# LabConnect

LabConnect is a local network peer-to-peer communication prototype. The desktop Java application can discover peers, connect over TCP, exchange direct text messages, and transfer files with chunk acknowledgments and SHA-256 verification.

## Current implementation status

- Desktop discovery, TCP framing, direct chat, file transfer, and desktop pairing have automated coverage. `mvn test` runs the complete Java test suite, including end-to-end desktop tests over local TCP sockets.
- The Android sources are an unfinished prototype. They do not yet implement the same complete messaging and transfer flows as desktop.
- Desktop TLS is not connected to the TCP transport. Do not use this version for private or sensitive traffic.
- Pairing UI exists, but pairing frames are not routed to the pairing manager. Pairing between running applications is not functional yet.
- Group creation exists in the desktop chat manager, but group membership exchange and cross-device group messaging are incomplete.

Passing automated tests demonstrate behavior on one machine; they do not replace testing discovery and firewall behavior between real devices.

## Desktop prerequisites

- JDK 21 or newer (the source targets Java 17)
- Apache Maven 3.6 or newer

## Build and run desktop

From this directory:

```bash
mvn clean package
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar
```

The application uses `config.yaml` in its working directory and creates it from `config.example.yaml` on first start. Devices need to be on a network that permits UDP multicast and TCP connections. The default ports are UDP 50001 for discovery and TCP 5000 for peer connections.

## Run desktop tests

```bash
mvn test
```

## Android

The Android project is in `android/`. It requires Android Studio/Android SDK and a compatible Gradle installation. Android discovery, chat, and file transfers have not been validated as a complete flow.

## Security

The desktop transport currently sends protocol frames over plaintext TCP. Pairing and TLS are not active features in the running desktop app. Use only on a trusted test network, and do not transfer sensitive data.

## License

MIT. See [LICENSE](LICENSE).
