# LabConnect Troubleshooting Guide

## Quick Diagnosis

Run diagnostics from the app: **Menu → Diagnostics → Generate Report**

There is no command-line diagnostics mode; check the log file instead:
```bash
grep -iE "discovery|connection|error|warn" logs/labconnect.log
```
The log is set to DEBUG for `com.labconnect`, so discovery announcements and
peer connections are all recorded there.

---

## Connection Issues

### Devices Not Discovered

| Cause | Check | Fix |
|-------|-------|-----|
| Different subnets | `ipconfig` / `ifconfig` | Connect to same Wi-Fi, disable guest network |
| Multicast blocked | Router settings | Enable IGMP Snooping / Multicast |
| Firewall blocking UDP 50001 | Windows Firewall / iptables | Allow UDP 50001 inbound/outbound |
| AP Isolation enabled | Router Wi-Fi settings | Disable AP/Client Isolation |
| Android location off | Phone settings | Enable Location (required for Wi-Fi scan) |

**Manual Connection Fallback:**
1. Get target IP: `ipconfig` (Windows) / `ip addr` (Linux) / Settings→About Phone (Android)
2. In LabConnect: **Menu → Connect Manually**
3. Enter IP:Port (e.g., `192.168.1.50:5000`)

### Connection Refused / Timeout

| Cause | Check | Fix |
|-------|-------|-----|
| Port 5000 blocked | `telnet <ip> 5000` | Allow TCP 5000 in firewall |
| Wrong port | Config mismatch | Verify both use port 5000 (or same custom port) |
| Not paired | `requirePairing=true` | Pair devices first, or set `requirePairing: false` |
| TLS handshake fail | Logs show "Handshake failed" | Delete `keystore.dat` and `truststore.dat`, re-pair |

### Connection Drops Frequently

| Cause | Check | Fix |
|-------|-------|-----|
| Heartbeat timeout | Logs: "stale (no heartbeat)" | Increase `heartbeatIntervalSec` in config |
| Wi-Fi power save | Phone battery settings | Disable battery optimization for LabConnect |
| Router DHCP lease short | Router settings | Increase DHCP lease time |
| IP change | Test 14 in TESTING.md | Use static IP or DHCP reservation |

---

## Pairing Issues

### Pairing Request Not Received

| Cause | Fix |
|-------|-----|
| Device not discovered | Fix discovery first (above) |
| Pairing UI not showing | Restart app, check notification permissions (Android) |
| Auto-reject | Check `requirePairing` config, ensure both apps running |

### Pairing Fails / "Verification Failed"

| Cause | Fix |
|-------|-----|
| Stale truststore | Delete `truststore.dat` on both devices, re-pair |
| Key mismatch | Delete `keystore.dat` on one device (regenerates key), re-pair |
| Fingerprint mismatch | Verify out-of-band, someone may be MITM |

### "Already Paired" But Can't Connect

1. Check both devices show each other in truststore
2. Verify device IDs match
3. Delete truststore on both, re-pair

---

## File Transfer Issues

### Transfer Starts But Stalls at 0%

| Cause | Fix |
|-------|-----|
| Receiver disk full | Free space on receiver |
| Permission denied | Check download directory writable |
| Chunk size too large | Reduce `chunkSize` in config (try 32768) |
| Network buffer full | Reduce `maxConcurrentTransfers` |

### Transfer Fails at ~50% / Random Point

| Cause | Fix |
|-------|-----|
| Wi-Fi interruption | Test 11/12 in TESTING.md - check signal strength |
| Receiver app crashed | Check logs, restart receiver |
| Sender app killed | Test 10 - resume should work |
| SHA-256 mismatch | File corrupted on sender, regenerate |

### "Hash Mismatch" on Completion

| Cause | Fix |
|-------|-----|
| File changed during transfer | Don't modify source file while sending |
| Disk error | Run `chkdsk` / `fsck` |
| Memory corruption | Restart both apps, try smaller file first |

### Resume Not Working

| Cause | Fix |
|-------|-----|
| Checkpoint missing | Check `~/.labconnect/transfers/` exists |
| File modified on sender | Resume requires identical source file |
| Config `resumeEnabled: false` | Enable in config.yaml |
| Different file ID | Only works for same fileId (same transfer session) |

---

## Android-Specific Issues

### App Crashes on Launch

| Cause | Fix |
|-------|-----|
| Missing permissions | Grant all requested permissions |
| Keystore corrupt | Clear app data (Settings → Apps → LabConnect → Storage → Clear Data) |
| Android version < 7.0 | Requires API 24+ |

### Discovery Not Working on Android

| Cause | Fix |
|-------|-----|
| Location off | Enable Location (Settings → Location) |
| Nearby devices permission | Settings → Apps → LabConnect → Permissions → Nearby Devices |
| Battery optimization | Settings → Battery → App optimization → LabConnect → Don't optimize |
| Foreground service killed | Keep app in recent apps, don't swipe away |

### Files Not Visible After Receive

| Cause | Fix |
|-------|-----|
| Scoped Storage | Files in `Documents/LabConnect` or `Download/LabConnect` |
| MediaScanner not run | Open Files app, pull to refresh |
| Wrong path | Check notification for actual save location |

---

## Performance Issues

### Slow Transfer Speed (< 1 MB/s on LAN)

| Cause | Fix |
|-------|-----|
| Wi-Fi congestion | Use 5GHz band, reduce interference |
| Chunk size too small | Increase `chunkSize` to 131072 |
| CPU bottleneck | Check CPU usage, close other apps |
| TLS overhead | Normal for small files, negligible for large |

### High Memory Usage

| Cause | Fix |
|-------|-----|
| Large file in memory | Increase heap: `-Xmx2g` |
| Too many concurrent transfers | Reduce `maxConcurrentTransfers` |
| History growing | Clear old history in Settings |

### UI Freezes

| Cause | Fix |
|-------|-----|
| FX Application Thread blocked | Check logs for long-running tasks |
| Large message history | Limit history in Settings |
| TableView rendering | Reduce transfer table refresh rate |

---

## Log Analysis

### Enable Debug Logging
```yaml
# config.yaml
logging:
  level: "DEBUG"
  fileEnabled: true
  consoleEnabled: true
```

### Key Log Patterns

| Pattern | Meaning |
|---------|---------|
| `Accepted connection from` | Inbound connection successful |
| `Connection established to` | Outbound connection successful |
| `Handshake failed` | TLS error - cert/key mismatch |
| `stale (no heartbeat)` | Connection timeout |
| `Device discovered` | UDP discovery working |
| `Pairing accepted` | Trust established |
| `Hash verification: match=true` | File integrity OK |
| `Checkpoint saved at offset` | Resume point saved |

### Export Logs for Support
```bash
# Desktop
zip logs.zip logs/

# Android
adb logcat -d | grep LabConnect > android_logs.txt
```

---

## Reset / Clean Start

### Desktop
```bash
# Stop app, then:
rm -rf ~/.labconnect/keystore.dat ~/.labconnect/truststore.dat ~/.labconnect/history/
# Or on Windows:
del %USERPROFILE%\.labconnect\keystore.dat
del %USERPROFILE%\.labconnect\truststore.dat
rmdir /s %USERPROFILE%\.labconnect\history
```

### Android
Settings → Apps → LabConnect → Storage → Clear Data

### Router
Power cycle router (unplug 30 seconds) to clear multicast state.

---

## Known Limitations

| Limitation | Workaround |
|------------|------------|
| No internet relay | All devices must be on same LAN |
| No NAT traversal | Use same subnet, no double NAT |
| IPv6 not fully tested | Use IPv4 |
| Max 255 devices (theoretical) | Tested with 4 |
| Android background kill | Keep app in foreground/recents |

---

## Getting Help

1. Check **Diagnostics → Generate Report**
2. Search logs for `ERROR` or `WARN`
3. Try **Reset / Clean Start** above
4. Test with 2 devices first (simplest case)
5. Report issue with: OS, Java version, log excerpt, steps to reproduce