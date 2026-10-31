# Starlane Share

A fast, encrypted, offline peer-to-peer file and app sharing app for Android. It has its own name, look, icons and protocol and does not reuse any other app's branding, layouts or assets.

- **Platform:** native Android, Kotlin + Jetpack Compose, minSdk 21 (Android 5.0) to targetSdk 34
- **Languages:** English and Persian (فارسی), with full right-to-left layout
- **Network:** everything stays on the local network or a direct phone-to-phone link. Nothing goes through a cloud server and no internet is needed.

## Download the APK

1. Open **Actions → Android CI** in this repository: https://github.com/killpv47-png/starlane-share/actions
2. Open the latest green run.
3. Under **Artifacts**, download one of:
   - `starlane-share-optimized-apk`: shrunk with R8 and signed with the debug key. This is the small one to install for testing.
   - `starlane-share-debug-apk`: the standard debug build.

Both builds are signed with the debug key, so they are for testing, not store distribution. A signed release build is produced only when a keystore is supplied through these GitHub secrets: `STARLANE_KEYSTORE_PATH`, `STARLANE_KEYSTORE_PASSWORD`, `STARLANE_KEY_ALIAS`, `STARLANE_KEY_PASSWORD`. No release key is included in this repository.

## Features

### Transfer engine (`core/`, plain Kotlin with no Android dependency)
- **Streaming:** files are read and written in chunks and are never loaded whole into memory. Each file is split into segments, and each segment is checked with SHA-256 as it arrives. A whole-file hash is checked before the file is saved under its final name.
- **Encryption:**
  - Each connection uses a fresh ECDH P-256 key exchange, keys are derived with HKDF-SHA256, and both devices prove their identity with ECDSA signatures.
  - Data is encrypted with AES-128-GCM or ChaCha20-Poly1305. Both phones measure which is faster on their hardware and the faster one is chosen.
  - Counter-based nonces block replayed or reordered packets.
- **Parallel lanes:** large transfers can use several TCP connections at once on Wi-Fi, with a limit on unconfirmed data so memory use stays bounded.
- **Resume:** verified segments are saved, so an interrupted transfer continues from the last verified segment. If the link drops, the sender reconnects automatically.
- **Controls:** pause/resume, cancel (partial files are cleaned up), retry, and per-file results.
- **Storage safety:**
  - Free space is checked before accepting.
  - File names and paths sent by the other phone are cleaned so they cannot escape the save folder.
  - Existing files are never silently overwritten: you choose rename, overwrite, or skip identical files.
- **Turbo Mode:** picks the number of lanes, chunk size, socket buffers and cipher from what *both* phones can handle. The app shows whether Turbo is Active, Limited or Unavailable, and why. It never artificially caps speed.

### Ways to connect
| Method | Discovery | Notes |
|---|---|---|
| Same Wi-Fi network / hotspot | mDNS/DNS-SD (NSD) | Fastest setup, no pairing |
| Wi-Fi Direct | Wi-Fi Direct service discovery carrying username and model | Works without a router |
| Bluetooth (RFCOMM) | Bluetooth scan | Fallback; the UI says it is slower |
| USB cable | Android Open Accessory (host ↔ accessory) | Detects the USB roles and reports unsupported setups instead of pretending to connect |

The app tries LAN first, then Wi-Fi Direct, then Bluetooth, using the first that works. Wi-Fi Aware hardware is detected and shown, but transfers use Wi-Fi Direct or LAN because far more phones support them.

### User interface
- **Space-style discovery screen:** a slow starfield and orbit rings, with each real nearby device shown as a circle with its username, model and nickname. Drag a device onto your own to select it, or tap it. Then choose **SEND** or **RECEIVE**. There is no QR code and no camera permission.
- **Incoming transfers** show the sender's verified username, model and device ID, the file list, total size and free space. Nothing is received until you tap **Accept**.
- **Home screen status panel** always shows: connection, method, link quality, current and average speed, amount transferred, total, percentage and time remaining.
  - Speed is calculated from real bytes over real time, with smoothing.
  - Signal strength is shown only when the Wi-Fi driver actually reports it; otherwise the app says it is not reported.
- **File picker:** Photos, Videos, Audio, Documents, Apps, Downloads, Other files and Folders. It supports multi-select, select all, search, sorting, sizes and a selection summary. It uses the system file picker (Storage Access Framework) where Android requires it.
- **Other screens and integrations:**
  - Transfer queue, history, and recent/favorite devices.
  - USB screen, and Settings (profile, Turbo, conflict rule, save folder, language, theme, reduce animations, device capabilities).
  - Android share menu integration and notifications with a Cancel button.
  - A foreground service that runs only while there is active work.

### App transfer and its data
- Sends the base APK **plus every split APK**, along with app metadata.
- With **DATA** turned on, it also sends `Android/obb` and `Android/media` files that Android lets the app read, plus any folders you add yourself through the system picker.
- **Before sending**, a summary lists what will be transferred and what cannot be, with the reason (blocked by Android storage protection, private app data, or progress stored on the app's servers).
- **After receiving**, the app checks the package (base present, same package name, minimum Android version) and installs it with Android's PackageInstaller. You must always confirm in the system dialog; silent installation is not attempted.

## Project structure
```
core/                  transport-independent engine (protocol, crypto, sessions, storage, tuning) + JVM tests
app/src/main/java/com/starlane/share/
  net/                 LAN/NSD, Wi-Fi Direct, Bluetooth, USB (AOA) transports
  engine/              ShareManager (coordination), foreground service, notifications
  files/               receive storage (SAF / MediaStore / legacy), sources, selection, media queries
  apps/                app listing, split-APK plans, installer, received-app reports
  data/                identity key, history, devices
  ui/                  theme, components, screens (discovery, home, picker, transfers, settings, ...)
app/src/main/res/      values/ (English), values-fa/ (Persian)
.github/workflows/     CI
```

## Testing (all run on GitHub Actions for every push)
- **Engine tests:** run on a JVM over real TCP connections. They cover:
  - Handshake and identity verification, refusal of a wrong identity, and tamper/replay detection.
  - Multi-file and large-file transfers that must arrive bit-exact.
  - Multi-lane Turbo, resume after a dropped connection, cancellation cleanup, rejection, and insufficient storage.
  - Duplicate rename/skip, path-traversal protection, and app-bundle metadata.
  - A benchmark comparing the configurations.
- **App build:** debug and optimized APKs, app unit tests, and Android lint. Lint errors fail the build, which keeps API-level compatibility down to minSdk 21.
- **Emulator tests (Android 8.0 and 11):**
  - The real encrypted engine running on Android's own runtime and crypto provider.
  - The onboarding flow, the home screen, history, and Persian right-to-left layout.
  - Cold start time, memory use and idle CPU, published in each run's summary.

Measured results are in each run's job summary and artifacts. All numbers come from CI runners and emulators, not from phones.

## Status

**Implemented and tested in CI:** the transfer engine, including encryption, verification, resume, lanes, cancel, reject and storage checks; the APK build; lint; the app's discovery data format; the on-device engine; and the onboarding, home, history and Persian/RTL screens.

**Implemented but not yet tested on real phones:** these need two physical devices, which were not available.
- Wi-Fi Direct and NSD discovery and connection between two phones.
- The Bluetooth fallback and pairing.
- USB phone-to-phone transfer.
- Real-world throughput and Turbo gains on phones.
- Installing a received split-APK app.
- OBB/media transfer.
- Automatic reconnection after a real Wi-Fi drop.
- Notification actions.
- Battery behaviour.

**Partially supported:**
- *USB:* works only when one phone can act as USB host (OTG) and the other supports accessory mode. Many cable and phone combinations cannot do this, and the app reports that instead of faking a connection.
- *OBB files:* they are received into `Download/Starlane`. Android does not let one app place files in another app's folder, so you move them with a file manager if a game needs them.
- *Emulator test stability:* the Android 8.0 emulator job occasionally fails because the CI emulator goes offline (`adb: device offline`), not because of an app error. The same tests pass on Android 11 and passed on Android 8.0 in earlier runs.

**Not possible under Android's rules:**
- Reading another app's private data (`/data/data`), or `Android/data` and `Android/obb` of other apps on Android 11 and newer.
- Silent app installation on ordinary phones.
- Moving progress that an online game keeps on its own servers.

The app shows each of these limits to the user instead of claiming a full migration.

## Security notes
- No credentials are stored in the source, workflow files or APK. CI uses only the built-in `GITHUB_TOKEN` with read permission.
- The device identity key is kept in no-backup private storage, and file contents are never written to logs.
