# UVC Monitor — Turn an Android Tablet into an HDMI Secondary Monitor

A **dedicated viewer app** that displays a PC's HDMI output on an Android tablet through a USB capture card, plus a **setup script** that turns the tablet into a dedicated monitor.

```
[PC] --HDMI--> [USB capture card] --USB (OTG)--> [Android tablet + UVC Monitor]
```

Tested setup: Galaxy Book4 Pro → Hagibis MS2130 capture card → Galaxy Tab S6 Lite (SM-P610, Android 13)  
Result: 1920×1080 @ 50 fps, subjectively low latency; touch input is not sent to the PC.

## Contents

| Path | Description |
|---|---|
| `app/` | Viewer source code (Kotlin, no external libraries) |
| `scripts/build.sh` | Build the APK |
| `scripts/install.sh` | Install and launch the viewer without changing tablet settings |
| `dist/uvc-monitor.apk` | Generated APK (not included in Git) |
| `tablet/setup.sh` | Automatic tablet setup (installation, debloating, optimization, and kiosk mode) |
| `tablet/restore.sh` | Restore the tablet's previous state |
| `tablet/debloat-list.txt` | Level 1 disable list (Bixby, sharing/DeX, supplementary apps, etc.) |
| `tablet/debloat-list-extreme.txt` | Level 2 list (Google Play services and Store, Samsung account and updates, etc.) |
| `tablet/uvc_descriptors.py` | Capture card compatibility check |
| `tablet/logs/<model>_<serial-number>/` | Original device state and operation logs (used for restoration) |

## Requirements and Compatibility

- **Capture card**: UVC compliant, with **MJPEG** and a **bulk transfer** endpoint.
  MacroSilicon MS2130-based cards (including Hagibis) have been tested. Most inexpensive MS2109-based cards have the same structure.
  When the capture card is connected, `setup.sh` automatically reports compatibility (`Supported : YES/NO`).
  Cards that support only isochronous transfers are not currently supported.
- **Tablet**: Android 7 (API 24) or later and USB OTG (host) support. Wireless debugging requires Android 11 or later.
  The debloat lists target Samsung One UI; packages absent from other manufacturers' devices are skipped automatically.
- **PC**: adb (Windows platform-tools recommended; mDNS discovery does not work in WSL).

## Build and Install

On Linux, macOS, or WSL, install JDK 17, Gradle 8.7, Android SDK platform 34, build-tools 34, and Android platform-tools. Set `ANDROID_HOME` to the SDK directory (or configure `sdk.dir` in `local.properties`):

```bash
export ANDROID_HOME="$HOME/android-sdk"
./scripts/build.sh
```

Enable developer options and USB debugging on the tablet, connect it to the PC, and approve the debugging prompt. Then run:

```bash
adb devices                             # Find the device serial
./scripts/install.sh                    # When exactly one device is connected
./scripts/install.sh <serial-or-ip:port> # Or select a device explicitly
```

Set `ADB=/path/to/adb` if adb is not on `PATH`. Connect the capture card and accept the tablet's USB permission prompt. Wireless debugging on Android 11+ also works after pairing and connecting with adb. The installation script only installs and launches the app; the optional setup below changes tablet settings.

## Setting Up a Dedicated Monitor

1. **Enable developer options on the tablet** — Settings > About tablet > Software information > tap Build number seven times.
2. **Connect adb** using one of the following methods:
   - Wireless: Developer options > turn on Wireless debugging > “Pair device with pairing code”.
     ```
     adb mdns services                       # Check ports
     adb pair <ip>:<pairing-port> <6-digit-code>
     adb connect <ip>:<connection-port>
     ```
   - USB: Connect using USB debugging before plugging in the capture card, then switch to wireless with `adb tcpip 5555` after setup.
3. **Build the APK** using `./scripts/build.sh` from the project root.
4. **Connect the capture card to the tablet** (PC HDMI → capture card → tablet).
5. **Run the script**:
   ```bash
   cd tablet
   ./setup.sh -s <ip:port> -n            # Preview with a dry run first
   ./setup.sh -s <ip:port>               # Apply (default: extreme + kiosk)
   ./setup.sh -s <ip:port> -l basic -K   # Lighter setup: keep Google services, skip kiosk
   ```
6. **On the tablet**, confirm the USB permission prompt.
7. **On the PC**, press `Win + P` → **Extend**, then set the capture card's display to 1920×1080 and arrange its position in display settings.
8. On a Samsung device, if the screen looks yellow, toggle Settings > Display > **Eye comfort shield** on and then off. (Changing the adb setting alone may not disable the actual filter.)

## What setup.sh Does

| Step | Description |
|---|---|
| Device check | Prints the model and SDK version, backs up the original list of enabled packages, and checks capture card compatibility |
| Viewer installation | Installs the APK, grants camera permission (required for UVC access), and exempts the app from battery optimization |
| Debloating | Uses `pm disable-user` to **disable packages only** (they are not deleted, and their data is preserved) |
| Optimization | Disables animations and **power saving**, turns off Wi-Fi/BLE scanning and location, keeps the screen on while charging, and disables the eye comfort filter |
| Kiosk mode | Sets the viewer as the default home app and disables the lock screen if no lock-screen password is set |

At the `extreme` level, **the Play Store, Google and Samsung account features, and system updates stop working**. Apps can only be installed through adb.

## Using the Viewer

- The viewer starts automatically when the capture card is connected, runs fullscreen in landscape, and keeps the screen on.
- **Long-press the screen → menu**: preserve aspect ratio or fill the screen, show status (fps and decode time), restart the stream, open settings, or open the Samsung home screen (to exit kiosk mode).
- The Back button is ignored while the viewer acts as the home app.

## Restoring the Tablet

Keep the device-specific `tablet/logs/` directory: it is excluded from Git and is required to identify packages disabled by setup. The restore script applies fixed defaults for some settings and chooses an available home app; it does not restore every original setting. Review your tablet settings afterward.

```bash
./tablet/restore.sh <ip:port>              # Re-enable only packages disabled on this device and apply default settings and choose a home app
./tablet/restore.sh <ip:port> --uninstall  # Also uninstall the viewer
```

## Build Signing

The APK is signed with a debug key. Rebuilding on another PC produces a different signature, so you must uninstall the existing app (`adb uninstall ai.waion.uvcmonitor`) before installing the new build.

## Architecture (Technical Notes)

- `UvcCamera.kt` — A minimal UVC driver implemented using only the Android USB Host API. It parses MJPEG modes from descriptors, negotiates VS_PROBE/COMMIT, receives payloads through bulk transfers, and assembles frames using UVC headers (FID/EOF). No native code (libusb/libuvc) is used.
- `MainActivity.kt` — Keeps only the latest frame to minimize latency, decodes in parallel on two threads (`BitmapFactory` = libjpeg-turbo NEON), and uses `SurfaceView.lockHardwareCanvas()` for GPU scaling and compositing.
- Many mobile SoCs, including the Exynos 9611, **lack a hardware MJPEG decoder**, so JPEG decoding runs on the CPU (NEON), while scaling and output are handled by the GPU. Decoding a 1080p frame takes approximately 17–22 ms and uses roughly 1.2 CPU cores.
