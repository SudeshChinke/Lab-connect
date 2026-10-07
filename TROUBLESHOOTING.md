# Troubleshooting

## Devices do not appear

- Confirm every device is on the same Wi-Fi or local network. Guest Wi-Fi often
  blocks traffic between clients.
- Keep Wi-Fi enabled on Android. Grant Nearby devices on Android 13+, or
  Location on Android 7–12, when LabConnect asks.
- Allow inbound TCP 5000 and UDP 50001 on desktop firewalls.
- Disable AP/client isolation in the router. Discovery uses UDP multicast, which
  some routers and managed networks filter.

## A device appears but Connect fails

- Verify that the peer is still running LabConnect and both devices are on the
  same local network.
- Allow TCP 5000 through the receiving device's firewall.
- Check that the network does not isolate clients or block local TCP traffic.

## File transfer does not start

- The receiver must accept the incoming file request.
- Check free storage on the receiving device and try a smaller file.
- Android saves received files in app-private storage; other apps may not show
  them in the general Downloads folder.

## Desktop logs

Packaged desktop logs are stored in the LabConnect application-data folder.
Source-run logs are under `logs/` in the working directory. Android logs can
be inspected with Android Studio's Logcat or `adb logcat`.

If a problem persists, include the device OS, network type (home/guest/hotspot),
and relevant log messages when reporting it. Do not share `keystore.dat` or
other identity files.
