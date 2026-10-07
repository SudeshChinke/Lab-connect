# LabConnect

LabConnect is a local-network peer-to-peer app for Windows, Linux Mint, and
Android. It discovers nearby peers and supports direct text messages and file
transfers with SHA-256 integrity checks. Devices need to be on a network that
allows them to communicate directly; there is no central server.

## Get the app

Download the platform artifact from the latest successful **Build LabConnect**
run in GitHub Actions, extract its ZIP, and follow the [device setup guide](SETUP.md).

| Device | GitHub Actions artifact | Package |
| --- | --- | --- |
| Windows x64 | `labconnect-desktop-windows-latest` | MSI installer |
| Linux Mint x64 | `labconnect-desktop-ubuntu-latest` | `.deb` package |
| Android 7+ | `labconnect-android` | Debug APK |

The desktop installers bundle the Java runtime. Android dependencies are
included in the APK. Linux's package manager installs any native system
libraries needed by the desktop UI.

## Current limitations

- Desktop and Android support discovery, direct chat, and single-file transfer.
- Group chat is not complete across devices.
- Desktop and Android TCP traffic is unencrypted; pairing does not enforce
  access control. Use only on a trusted test network and do not send sensitive
  data. See [SECURITY.md](SECURITY.md).
- Keep the Android app open in the foreground during discovery and transfers.
- Real device-to-device behavior depends on router and firewall settings and
  has not been fully validated on physical devices.

## Build

- Desktop: JDK 21 and Maven; run `mvn clean package`.
- Android: Android Studio, Android SDK Platform 34, and JDK 17; open `android`
  or run `cd android && ./gradlew assembleDebug`.

Build and per-device install instructions are in [SETUP.md](SETUP.md). Desktop
unit and integration tests run with `mvn test`.

## License

MIT. See [LICENSE](LICENSE).
