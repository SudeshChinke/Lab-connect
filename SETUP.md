# LabConnect Setup Guide

## Prerequisites

### Desktop (Windows/Linux/macOS)
- **Java 17+** (Eclipse Temurin/OpenJDK recommended; builds fine on JDK 21)
- **Maven 3.8+** (for building from source)
- **JavaFX 21** (included via Maven, platform-specific natives resolved automatically)

#### Linux install
```bash
# Debian/Ubuntu/Mint
sudo apt update
sudo apt install openjdk-17-jdk maven

# verify
java -version
mvn -version
```

### Android
- **Android Studio** (latest stable)
- **Min SDK**: API 24 (Android 7.0)
- **Target SDK**: API 34 (Android 14)

The Android tree is an unfinished prototype. Discovery UI is present, but there
is no complete Android chat or file-transfer implementation. No Gradle wrapper
or Android SDK is included in this repository; Android build/device validation
has not been performed for the current repair copy.

---

## Building from Source

### Desktop Application
```bash
# Clone repository
git clone <repo-url>
cd LabConnect

# Build with Maven
mvn clean package

# Run
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar

# Or run with Maven (development)
mvn javafx:run
```

### Android Application
```bash
# Open in Android Studio
# File → Open → LabConnect/android

# Build
./gradlew assembleDebug

# Install on device
./gradlew installDebug
```

---

## Network Configuration

### Required Ports
| Protocol | Port | Direction | Purpose |
|----------|------|-----------|---------|
| TCP | 5000 | Inbound/Outbound | Main data connection |
| UDP | 50001 | Inbound/Outbound | Discovery multicast |
| UDP | 50001 | Outbound | Discovery broadcast fallback |

### Windows Firewall
```powershell
# Allow LabConnect through firewall
New-NetFirewallRule -DisplayName "LabConnect TCP" -Direction Inbound -LocalPort 5000 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "LabConnect UDP Discovery" -Direction Inbound -LocalPort 50001 -Protocol UDP -Action Allow
```

### Linux Firewall (ufw)
```bash
sudo ufw allow 5000/tcp
sudo ufw allow 50001/udp
```

### Router Settings (TP-Link)
1. Enable **Multicast** / **IGMP Snooping** in router settings
2. Disable **AP Isolation** / **Client Isolation**
3. Ensure devices on same subnet (192.168.x.x)

---

## Configuration

### Desktop Config (`config.yaml`)
Auto-generated on first run at `./config.yaml`:

```yaml
device:
  name: "LabConnect-User"
  type: "DESKTOP"
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
logging:
  level: "INFO"
  fileEnabled: true
  consoleEnabled: true
```

### Android Config
Stored in `SharedPreferences`, editable in Settings screen.

---

## Running the Application

### Desktop
```bash
# Development
mvn javafx:run

# Production (after `mvn clean package`)
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar
```

The fat jar bundles all dependencies, so `java -jar` works standalone.

**Note on configuration:** the launcher takes no CLI flags. `config.yaml` is
read from the current working directory, so run the app from the project root,
or copy `config.yaml` next to wherever you launch it. There is no `--config`,
`--headless` or `--diagnostics` flag, and `-Dlabconnect.*` system properties
are **not** read - edit `config.yaml` to change ports or the device name.

### Android
1. Install APK on device
2. Grant permissions when prompted:
   - **Location** (required for Wi-Fi scanning)
   - **Nearby Devices** (Android 12+)
   - **Files and Media** (for file transfers)
3. Launch app, set device name
4. App runs discovery in foreground service

---

## First Run Checklist

- [ ] Java 17+ installed and in PATH
- [ ] Maven 3.8+ installed (for building)
- [ ] Port 5000 TCP allowed in firewall
- [ ] Port 50001 UDP allowed in firewall
- [ ] All devices on same Wi-Fi network
- [ ] Router multicast enabled
- [ ] AP Isolation disabled

---

## Verification

### Quick Test (Single Machine)

Two instances can share one discovery port (the listener sets `SO_REUSEADDR`),
but each needs its own `config.yaml`, so run the second peer from a separate
directory with a different `network.tcpPort`:

```bash
mvn clean package

# Peer A - uses ./config.yaml (tcpPort 5000)
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar

# Peer B - separate directory, own config.yaml with tcpPort 5001
mkdir -p /tmp/peerB
sed 's/^  tcpPort: 5000/  tcpPort: 5001/' config.yaml > /tmp/peerB/config.yaml
(cd /tmp/peerB && java -jar ~/path/to/LabConnect/target/labconnect-core-1.0.0-SNAPSHOT.jar)
```

Both instances should discover each other, and each should list the other in
the device panel after clicking **Refresh Devices**.

### Multi-Machine Test
1. Run on PC1: `java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar`
2. Run on PC2: `java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar`
3. Both should appear in each other's device list within 10 seconds

Note: discovery uses UDP multicast on 239.255.255.250:50001. Networks that
filter multicast (some guest Wi-Fi, some VLANs) will block discovery.

---

## Logs & Diagnostics

### Log Files
- Desktop: `./logs/labconnect.log`
- Android: `logcat | grep LabConnect`

### Diagnostic Report
There is no command-line diagnostics mode. Use the UI:
**Diagnostics → Generate Report**, then copy the text out of the dialog.
A machine-readable version of the same information is in the log file:
```bash
grep -i "discovery\|connection\|error\|warn" logs/labconnect.log
```

---

## Common Issues

| Problem | Solution |
|---------|----------|
| "JavaFX not found" | Use `mvn javafx:run` or ensure modular JDK |
| Devices not found | Check firewall, router multicast, same subnet |
| Pairing fails | Delete `truststore.dat`, restart both apps |
| Transfer fails | Check disk space, file permissions, chunk size |
| Android crashes | Check logcat, grant all permissions |

---

## Building Distributables

### Desktop (jpackage)
```bash
# Windows
jpackage --input target --main-jar labconnect-core-1.0.0-SNAPSHOT.jar --name LabConnect --type exe

# Linux
jpackage --input target --main-jar labconnect-core-1.0.0-SNAPSHOT.jar --name LabConnect --type deb

# macOS
jpackage --input target --main-jar labconnect-core-1.0.0-SNAPSHOT.jar --name LabConnect --type dmg
```

### Android
```bash
./gradlew bundleRelease  # For Play Store
./gradlew assembleRelease  # For direct distribution
```

---

## Updating
```bash
git pull
mvn clean package
# Replace jar/APK
```
