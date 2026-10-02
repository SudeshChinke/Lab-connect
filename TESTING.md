# LabConnect Testing Guide

## Hardware Test Matrix (Phase 17)

### Test Environment
- **PC1**: Windows 10/11, LabConnect Desktop
- **PC2**: Windows 10/11, LabConnect Desktop  
- **Phone1**: Android 7+, LabConnect Mobile
- **Phone2**: Android 7+, LabConnect Mobile
- **Router**: TP-Link (Wi-Fi only, no Ethernet)

### Pre-Test Setup
1. Install LabConnect on all 4 devices
2. Connect all devices to same Wi-Fi network
3. Disable Windows Firewall or allow LabConnect (port 5000 TCP, 50001 UDP)
4. Note IP addresses of all devices
5. Ensure all devices can ping each other

---

### Test 1: PC1 ↔ PC2 (Basic Connectivity)
**Objective**: Verify desktop-to-desktop connection
**Steps**:
1. Launch LabConnect on PC1 and PC2
2. Wait for auto-discovery (5-10 seconds)
3. Verify both devices appear in each other's device list
4. Click "Connect" on PC1 to PC2
5. Verify connection status shows "Connected"
6. Send text message from PC1 to PC2
7. Verify message received on PC2
8. Send reply from PC2 to PC1
**Expected**: Bidirectional messaging works
**Pass Criteria**: Messages delivered in both directions within 2 seconds

---

### Test 2: PC1 ↔ Phone1 (Desktop-Mobile)
**Objective**: Verify cross-platform connection
**Steps**:
1. Launch LabConnect on PC1 and Phone1
2. Wait for auto-discovery
3. Verify devices appear in each other's list
4. Pair devices if prompted (accept on both)
5. Send text message from PC1 to Phone1
6. Verify notification/message on Phone1
7. Send reply from Phone1 to PC1
**Expected**: Cross-platform messaging works
**Pass Criteria**: Messages delivered in both directions

---

### Test 3: PC2 ↔ Phone2 (Desktop-Mobile)
**Objective**: Verify second cross-platform pair
**Steps**: Same as Test 2 with PC2 and Phone2
**Expected**: Cross-platform messaging works

---

### Test 4: Phone1 ↔ Phone2 (Mobile-Mobile)
**Objective**: Verify mobile-to-mobile connection
**Steps**:
1. Launch LabConnect on Phone1 and Phone2
2. Wait for auto-discovery
3. Pair devices if prompted
4. Send messages both directions
**Expected**: Mobile-to-mobile messaging works

---

### Test 5: PC1 → All (Broadcast)
**Objective**: Verify broadcast messaging to all devices
**Steps**:
1. Launch LabConnect on all 4 devices
3. Wait for all devices discovered
4. On PC1, send broadcast message ("Hello everyone")
5. Verify all 3 other devices receive message
**Expected**: Broadcast reaches all connected peers

---

### Test 6: Group Chat
**Objective**: Verify group chat functionality
**Steps**:
1. On PC1, create group "TestGroup"
2. Invite PC2, Phone1, Phone2
3. Accept invites on all devices
4. Send messages in group from each device
5. Verify all members receive all messages
**Expected**: Group chat works with 4 participants

---

### Test 7: Large File Transfer (100MB+)
**Objective**: Verify large file transfer with integrity
**Steps**:
1. Create 150MB test file on PC1: `dd if=/dev/urandom of=test150mb.bin bs=1M count=150` (or use random file generator)
2. Send file from PC1 to PC2
3. Monitor progress (should show speed, ETA, progress bar)
4. Wait for completion
5. Verify SHA-256 matches on both ends
6. Verify file opens correctly on PC2
**Expected**: Transfer completes, SHA-256 verified, file intact
**Pass Criteria**: SHA-256 match, transfer speed >1MB/s on LAN

---

### Test 8: Multiple Simultaneous Transfers
**Objective**: Verify concurrent transfer handling
**Steps**:
1. On PC1, send 3 different files (10MB, 20MB, 50MB) to PC2 simultaneously
2. Monitor all 3 transfers in progress
3. Verify all complete successfully
4. Check SHA-256 on all files
**Expected**: All 3 transfers complete, priority scheduling works

---

### Test 9: Interrupt Transfer
**Objective**: Verify transfer can be cancelled
**Steps**:
1. Start 100MB transfer PC1 → PC2
2. Wait for ~30% progress
3. Click "Cancel" on PC1
4. Verify transfer stops on both ends
5. Verify partial file cleaned up on receiver
**Expected**: Clean cancellation, no orphaned files

---

### Test 10: Resume Transfer
**Objective**: Verify resumable transfer after interruption
**Steps**:
1. Start 100MB transfer PC1 → PC2
2. Wait for ~50% progress
3. Force-close LabConnect on PC1 (Task Manager / kill -9)
4. Restart LabConnect on PC1
5. Reconnect to PC2
6. Resume transfer (should continue from ~50%)
7. Verify completion and SHA-256 match
**Expected**: Transfer resumes from checkpoint, completes successfully

---

### Test 11: PC1 Offline (Graceful Disconnect)
**Objective**: Verify handling of device going offline
**Steps**:
1. Establish PC1 ↔ PC2 connection with active chat
2. Close LabConnect on PC1 normally (File → Exit)
3. Verify PC2 detects disconnect within 15 seconds (3 heartbeats)
4. Verify PC2 shows PC1 as "Offline"
5. Restart PC1, verify auto-reconnect
**Expected**: Clean disconnect detection, auto-reconnect works

---

### Test 12: PC2 Offline (Network Failure)
**Objective**: Verify handling of sudden network loss
**Steps**:
1. Establish PC1 ↔ PC2 connection
2. Disconnect PC2 from Wi-Fi (or enable airplane mode)
3. Verify PC1 detects stale connection within 15 seconds
4. Verify PC1 shows PC2 as "Offline"
5. Reconnect PC2 to Wi-Fi
6. Verify auto-reconnect
**Expected**: Stale connection detection, auto-reconnect works

---

### Test 13: Router Restart
**Objective**: Verify recovery after network infrastructure restart
**Steps**:
1. All 4 devices connected, active chats/transfers
2. Restart TP-Link router (power cycle)
3. Wait for router to fully boot (2-3 minutes)
4. Verify all devices reconnect automatically
5. Verify chats/transfers recover or show appropriate status
**Expected**: All devices reconnect after router reboot

---

### Test 14: IP Reassignment (DHCP Lease Renewal)
**Objective**: Verify handling of IP address changes
**Steps**:
1. Note PC1's IP address
2. Disconnect PC1 from Wi-Fi
3. On router, release/renew DHCP lease for PC1 (or wait for lease expiry)
4. Reconnect PC1 (should get new IP)
5. Verify other devices rediscover PC1 at new IP
6. Verify connections re-establish
**Expected**: Device rediscovered at new IP, connections work

---

### Test 15: Firewall Block
**Objective**: Verify diagnostics identify firewall issues
**Steps**:
1. Enable Windows Firewall on PC1, block LabConnect (port 5000)
2. Try to connect from PC2 to PC1
3. Run Diagnostics on PC2
4. Verify diagnostics report "Connection refused" / "Firewall blocking"
5. Unblock port, verify connection works
**Expected**: Diagnostics clearly identify firewall as cause

---

## Running Automated Tests

### Unit Tests
```bash
mvn test
```

### Integration Tests (requires 2+ machines)

The app has no `--test-server` / `--test-client` modes. To exercise peer
discovery, just launch the normal app on each machine - they find each other
over multicast discovery. Set a distinct device name in `config.yaml` on each
so they are easy to tell apart in the UI.

```bash
# Build first on each machine
mvn clean package

# Machine 1
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar

# Machine 2
java -jar target/labconnect-core-1.0.0-SNAPSHOT.jar

# To vary the port on a single machine, edit config.yaml -> network.tcpPort.
# Note: the launcher does not read -Dlabconnect.* system properties, so the
# config file is the supported way to change ports.
```

---

## Test Reporting

Create test report: `TEST_REPORT_<date>.md`

| Test | Status | Notes |
|------|--------|-------|
| 1 |  |  |
| 2 |  |  |
| ... |  |  |

---

## Troubleshooting Common Test Failures

| Issue | Cause | Solution |
|-------|-------|----------|
| Devices not discovered | Multicast blocked | Use manual IP entry, check router multicast settings |
| Connection refused | Firewall | Allow port 5000 TCP, 50001 UDP |
| Pairing fails | Key mismatch | Delete truststore.dat, re-pair |
| Transfer stalls | Large file memory | Check chunked streaming, increase heap |
| Android not discovering | Background restrictions | Enable foreground service, disable battery optimization |