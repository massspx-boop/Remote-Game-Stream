# Remote Game Stream

Android LAN screen-streaming prototype with two modes:

- **Host**: requests Android screen-capture permission, runs a foreground service, captures a 640x360 screen stream and serves JPEG frames on TCP port 5000.
- **Client**: connects to the host IP on the same Wi-Fi network, displays the incoming stream and sends touch gestures back.
- **Remote input**: uses Android AccessibilityService gesture dispatch. The user must explicitly enable this service on the host phone.

## Build APK

Push to `main` or run **Actions → Android APK Build → Run workflow**. Download the generated `Remote-Game-Stream-debug-apk` artifact from the completed workflow.

## Use

1. Install the APK on both Android phones and connect both to the same Wi-Fi/hotspot.
2. On the host phone, open the app and choose **HOST MODE**. Approve screen capture.
3. Enable **Remote Game Stream Input** under Android Accessibility settings on the host phone.
4. Read the host IP shown by the app.
5. On the client phone, choose **CLIENT MODE**, enter the host IP and connect.
6. The host phone can then switch to the game. Keep the host service notification visible.

## Current technical limits

This first implementation sends JPEG frames (640x360, quality 55) over a TCP connection. It is not a hardware H.264/WebRTC pipeline, so bandwidth, latency and host CPU usage may be significant. Touch gestures are relayed through AccessibilityService; some games may block or ignore injected gestures. Android screen-capture consent is required each time the host starts sharing. Both phones must be on the same reachable LAN; some hotspot configurations isolate clients.

A successful APK build does **not** prove compatibility with Carnage Wars. Real-device testing is required before claiming that game control works reliably.
