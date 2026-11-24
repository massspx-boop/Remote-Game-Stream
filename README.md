# Remote Game Stream

Android LAN game screen streaming with authenticated, encrypted host/client sessions.

## Security design

- The host generates a fresh random 12-character pairing code for each screen-sharing session.
- The client must prove knowledge of the code before the host sends screen frames or accepts touch commands.
- Each connection uses a fresh random salt and PBKDF2-HMAC-SHA256 key derivation (210,000 iterations).
- Video frames and touch commands use AES-256-GCM authenticated encryption with separate directional nonce spaces.
- The host accepts private-LAN clients only, applies a timeout to unauthenticated handshakes, and slows repeated failed pairing attempts.
- Invalid authentication, malformed encrypted records, oversized commands, out-of-range coordinates and invalid gesture durations are rejected.
- Cleartext Android app traffic is disabled. The app does not request account, analytics, contacts, location or storage permissions.
- Android MediaProjection consent and explicit user enablement of AccessibilityService remain required for the requested screen-capture and remote-touch features.

## Important security notes

This is a private LAN application, not a formally audited security product. The pairing code is visible on the host screen; share it only with your own client device. Use a trusted Wi-Fi network and never expose TCP port 5000 to the public internet. Encryption does not make a compromised host or client safe. Play Protect may still warn about sideloaded apps that request sensitive Accessibility capabilities; source hardening and APK signing cannot guarantee Play Protect approval.

## Build APK

Every GitHub Actions run builds a debug APK for initial testing. A signed release APK is produced only after a stable, user-owned Android signing key is stored in the repository's **Settings → Secrets and variables → Actions** as these four repository secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded JKS/PKCS12 keystore file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: key alias
- `ANDROID_KEY_PASSWORD`: key password

Never commit the keystore or passwords to the repository, and never paste them into chat. Keep a secure offline backup of the keystore: losing it prevents future updates from being signed with the same identity. The debug APK is signed with a temporary CI debug key and is not the recommended long-term distribution build.

## Use

1. Install the same APK build on both Android phones and connect both to the same trusted Wi-Fi/hotspot.
2. On the host phone, choose **HOST MODE** and approve Android screen capture.
3. Enable **Remote Game Stream Input** in Android Accessibility settings only if remote touch control is needed.
4. The host displays its IP and a fresh 12-character pairing code.
5. On the client phone, choose **CLIENT MODE**, enter the host IP and pairing code, then connect.
6. Stop Host Mode when finished. Starting it again generates a new pairing code.

## Current technical limits

The app sends JPEG frames (maximum dimension 640 pixels, quality 55) over TCP. It is not a hardware H.264/WebRTC pipeline, so bandwidth, latency and host CPU usage may be significant. Continuous joystick movement and multi-touch are not implemented, and some games may block injected gestures. Android screen-capture consent is required each time the host starts sharing. Some hotspot configurations isolate connected clients.

A successful APK build does not prove compatibility with Carnage Wars. Real-device installation, connection, streaming and game-control tests are still required.
