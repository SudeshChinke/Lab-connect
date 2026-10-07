# LabConnect

LabConnect is a local network peer-to-peer communication prototype. The desktop Java application can discover peers, connect over TCP, exchange direct text messages, and transfer files with chunk acknowledgments and SHA-256 verification.

## Current implementation status

- Desktop discovery, TCP framing, direct chat, file transfer, and desktop pairing have automated coverage. `mvn test` runs the complete Java test suite, including end-to-end desktop tests over local TCP sockets.
- Android now has stable desktop-compatible identity, LAN discovery, TCP client/server, heartbeats, direct text messaging, and single-file transfers with an incoming-file approval prompt and SHA-256 verification. The Android app has been compiled, but the cross-device flow has not yet been exercised on physical devices.
- Desktop TLS is not connected to the TCP transport. Do not use this version for private or sensitive traffic.
- Desktop pairing and fingerprint confirmation are implemented, but pairing does not yet block untrusted traffic or encrypt connections.
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

Source runs use `config.yaml` in their working directory. Installed builds keep their settings, identity, trust list, and received files in the user's application data directory. Devices need to be on a network that permits UDP multicast and TCP connections. The default ports are UDP 50001 for discovery and TCP 5000 for peer connections.

Use the self-contained Windows installer, Linux Mint `.deb`, or Android APK from the workflow artifacts. The desktop installers include a Java runtime and JavaFX/application dependencies; users do not need Maven or a separate JDK. The Android APK bundles Android-side dependencies. Native desktop packages must be built on their target OS, so CI creates Windows and Linux packages separately.

## Run desktop tests

```bash
mvn test
```

## Android

The Android project is in `android/`. It requires Android Studio or Android SDK 34, JDK 17, and Gradle 8.6. Android supports discovery, connecting, direct text messages, and single-file transfers on the same Wi-Fi network. Received files are stored in the app's private `files/received` folder. Pairing enforcement and encrypted transport are not implemented. The GitHub Actions workflow builds a debug APK artifact.

## Security

The desktop transport currently sends protocol frames over plaintext TCP. Pairing and TLS are not active features in the running desktop app. Use only on a trusted test network, and do not transfer sensitive data.

## License

MIT. See [LICENSE](LICENSE).
