# Remote Game Stream

Android LAN game screen streaming with authenticated, encrypted host/client sessions.

## Security design

- The host generates a fresh, random 12-character Base32 pairing code each time screen sharing starts.
- The client must enter that code. The host proves knowledge of the same code before any screen frames or touch commands are exchanged.
- A fresh random salt and PBKDF2-HMAC-SHA256 (210,000 iterations) derive connection key material for each session.
- Video frames and remote-input commands are protected with AES-256-GCM authenticated encryption. Separate directional nonce spaces prevent nonce reuse between host and client directions.
- Invalid pairing proofs, malformed encrypted records, oversized input commands, out-of-range coordinates and invalid gesture durations are rejected.
- Cleartext Android app traffic is disabled. No account, analytics, contacts, location or storage permission is requested.
- Android's own MediaProjection consent dialog and the user's explicit Accessibility Service enablement remain required.

## Important security notes

This is a private LAN application, not a formally audited security product. Pairing codes are short-lived session secrets, but anyone who can see the host screen can read the code. Share it only with your own client device. Use a trusted Wi-Fi network; do not expose TCP port 5000 to the public internet. A successful build or encryption implementation is not a substitute for independent security review and real-device testing.

## Build

Push to `main` or run **Actions → Android APK Build → Run workflow**. The workflow builds a debug APK for initial functional testing. A stable, user-owned release signing key must be configured in GitHub Actions secrets before producing a distributable signed release APK; do not commit a keystore or passwords to this repository.

## Use

1. Install the same build on both Android phones and connect both to the same trusted Wi-Fi/hotspot.
2. On the host phone, choose **HOST MODE** and approve Android screen capture.
3. Enable **Remote Game Stream Input** under Android Accessibility settings only if remote touch control is needed.
4. The host displays its IP and a new 12-character pairing code.
5. On the client phone, choose **CLIENT MODE**, enter the host IP and pairing code, then connect.
6. Stop Host Mode when finished. The next Host session generates a different pairing code.

## Current technical limits

This implementation sends JPEG frames (640-pixel maximum dimension, quality 55) over TCP. It is not a hardware H.264/WebRTC pipeline, so bandwidth, latency and host CPU usage may be significant. Touch gestures are relayed through AccessibilityService; continuous joystick movement and multi-touch are not implemented, and some games may block injected gestures. Android screen-capture consent is required each time the host starts sharing. Both phones must be on the same reachable LAN; some hotspot configurations isolate clients.

A successful APK build does not prove compatibility with Carnage Wars. Real-device testing is required before claiming that game control works reliably.
