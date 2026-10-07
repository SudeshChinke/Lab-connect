# Install LabConnect

LabConnect has separate builds for Windows, Linux Mint, and Android. Download
the artifact for your device from the latest successful **Build LabConnect** run
on the [repository's Actions page](https://github.com/SudeshChinke/Lab-connect/actions).
Download the artifact ZIP and extract the installer or APK inside it. GitHub
may ask you to sign in before downloading workflow artifacts.

## Linux Mint

The Linux package is for 64-bit Intel/AMD PCs (`amd64`).

1. Download and extract `labconnect-desktop-ubuntu-latest` from GitHub Actions.
2. In a terminal, go to the folder containing `labconnect_1.0.0_amd64.deb`.
3. Install it:

   ```bash
   sudo apt install ./labconnect_1.0.0_amd64.deb
   ```

   `apt` installs any required Linux system libraries. LabConnect includes its
   Java runtime.
4. Open **LabConnect** from the applications menu.

To remove it later: `sudo apt remove labconnect`.

## Windows

The Windows package is an x64 per-user MSI installer.

1. Download and extract `labconnect-desktop-windows-latest` from GitHub Actions.
2. Double-click `LabConnect-1.0.0.msi` and follow the installer prompts.
3. Start **LabConnect** from the Start menu. The installer includes its Java
   runtime; a separate Java installation is not needed.

If Windows Firewall blocks discovery or file transfers, run PowerShell as
Administrator and add private-network rules:

```powershell
New-NetFirewallRule -DisplayName "LabConnect TCP" -Direction Inbound -Profile Private -LocalPort 5000 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "LabConnect Discovery" -Direction Inbound -Profile Private -LocalPort 50001 -Protocol UDP -Action Allow
```

## Android

LabConnect supports Android 7.0 (API 24) and newer.

1. Download and extract `labconnect-android` from GitHub Actions.
2. Copy `app-debug.apk` to the phone, then open it with the Files app.
3. If Android asks, allow the Files app to install unknown apps, return to the
   installer, and tap **Install**. This permission can be turned off again
   afterward in Settings.
4. Open LabConnect. Allow **Nearby devices** on Android 13 or later; on Android
   7–12, allow **Location** when requested so Wi-Fi discovery can run.
5. Keep Wi-Fi enabled and connect to the same local network as the other
   LabConnect devices. Keep LabConnect open in the foreground while discovering
   peers or transferring files.

The APK is a debug build intended for direct installation and evaluation.

## Connect devices

1. Install and open LabConnect on each device.
2. Connect all devices to the same Wi-Fi or local network. Guest Wi-Fi may block
   device-to-device traffic.
3. Allow incoming TCP port **5000** and UDP port **50001** in the computer's
   firewall. On Linux Mint with UFW:

   ```bash
   sudo ufw allow 5000/tcp
   sudo ufw allow 50001/udp
   ```

   On Windows, allow LabConnect when Windows Firewall prompts. If discovery
   still fails, create inbound rules for TCP 5000 and UDP 50001.
4. Wait for the peer list to populate, tap/click **Connect**, then send a chat
   message or file. Accept incoming file requests on the receiving device.

The router must allow local peer traffic and multicast; turn off AP/client
isolation for the Wi-Fi network if devices cannot see each other. The current
transport is unencrypted TCP. Use only on a trusted network and do not send
sensitive information. Physical device-to-device behavior may vary by network
and has not been fully validated.

## Build from source

### Windows or Linux desktop

Install JDK 21 and Maven 3.8 or newer, clone the repository, and run:

```bash
mvn clean package
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar
```

To create a native installer, build on the target operating system:

- Linux Mint: `bash scripts/package-linux.sh`
- Windows PowerShell: `./scripts/package-windows.ps1` (requires WiX Toolset)

The desktop package contains the Java runtime. Linux may still fetch standard
OS libraries through `apt` during installation.

### Android

Install Android Studio with Android SDK Platform 34 and JDK 17. Open the
repository's `android` folder in Android Studio and allow Gradle to sync; the
checked-in wrapper downloads Gradle 8.6 and the project dependencies. To build
from a terminal:

```bash
cd android
./gradlew assembleDebug
```

On Windows, run `gradlew.bat assembleDebug` from the `android` folder instead.

The APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`. Android Studio can install
it on a connected device, or use `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Data locations

Installed desktop settings, identity keys, logs, and received files are stored
in the user's LabConnect application-data folder. Source runs use files in the
working directory. Android stores its identity and received files in
app-private storage. Keep `keystore.dat` private if you need to preserve a
source-run desktop identity.
