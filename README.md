# PhonePilot Android Camera

Independent native Android application that turns the phone into a camera/WebRTC endpoint for the robot.

The application does not import server source code and does not depend on the ESP32 firmware project at build time. Runtime communication with the backend is defined only by the WebSocket protocol documented in `docs/SERVER_PROTOCOL.md`.

## Stack

- Kotlin
- targetSdk 35, minSdk 26 (compileSdk 36 on the checked-in toolchain)
- OkHttp WebSocket client
- WebRTC SDK
- Foreground camera service
- `usb-serial-for-android` 3.10.0 (CDC ACM, CP210x, CH340/CH341 and other supported drivers)

## Build

Open this folder directly in Android Studio, or run:

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Linux/macOS:

```bash
./gradlew assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is intentionally not included. Android Studio will create it for the local SDK path.

## Run

1. Start the Go server separately.
2. Enter its base URL in the app, for example `http://192.168.0.128:8080`.
3. Set a unique camera ID such as `phone_01`.
4. Grant camera/notification permissions and connect.
5. Connect ESP32 through a data-capable USB OTG adapter/cable and grant USB permission.

## v2 server path and camera authentication

In Setup, enter the server's private `http://<LAN IPv4>:8081` address in **LAN server URL (fallback)**. The app also discovers `_phonepilot._tcp.` with Android NSD. Enter a separate public `https://` URL to allow remote access. The public URL must reach the same server through TLS and expose `/api/network/info` and `/ws/camera`. Enter the server's 32-byte Base64URL camera secret once; the app encrypts it with Android Keystore and never puts it in a URL. Leave the secret box blank on later connections to retain the saved value. Change the field to replace a rotated secret.

The app stores the server's UUID from `/api/network/info`. A private candidate is accepted only after a Wi-Fi-bound `/api/network/lan-probe` returns the same UUID and nonce. The signaling socket is bound to the selected Android `Network`. The status panel shows the server's `network_path`; `mobile` is only reported when the public socket uses cellular. If neither verified LAN nor the configured public server can be reached, the phone shows offline. A public endpoint without a saved camera secret is not used.

The server must implement the v2 challenge and path messages before remote signaling works. For video across NAT, configure working ICE/TURN on the server as well. The servo control link is still independent of the server signaling route.

## v2 BLE board setup

Grant Nearby Devices (Bluetooth scan/connect) on Android 12+, or location on older Android versions, then enable Bluetooth. The app scans for the PhonePilot service while its camera service is running. Pair an unpaired board using the one-time PIN shown in its Serial Monitor. After receiving a status with the complete 12-digit `deviceId`, the app saves that identity and reconnects to the same bonded board. BLE status and board Wi-Fi status appear separately from server and servo status. **EXIT APP** closes scanning, GATT, and the other service resources.

In **ESP32 NETWORK SETUP**, select `USB`, `SHARED`, or `HOTSPOT`, choose **VIA BLE**, and send the mode. For a known `SHARED` profile the app first requests the stored board profile without a password. For a new network, enter the SSID and password. Select **Remember shared Wi-Fi password securely** only if you want the app to encrypt it with Android Keystore for later reconnection; **DELETE SAVED WI-FI PROFILE** removes it. Android cannot read an existing system Wi-Fi password, and the SSID can be typed manually when Android does not expose it. For `HOTSPOT`, start PhonePilot's `LocalOnlyHotspot`; its current credentials are sent over bonded BLE whenever a new hotspot session starts. The USB and setup Wi-Fi paths remain available as recovery paths.

BLE commands require an active system bond and are sent only after the board's full identity is checked. The board firmware must enforce authenticated encrypted GATT writes. An accepted BLE ACK is only receipt of a command; the board's later `ready` status confirms the effective configuration. BLE does not carry servo commands.

## Phone-created ESP32 hotspot

The **START ESP32 HOTSPOT** button requests Android `LocalOnlyHotspot` and displays the generated SSID/password. With v2 BLE firmware and a bonded board, current session credentials are sent over BLE when `HOTSPOT` is the desired board mode. USB provisioning remains available for recovery. Android 12 and older request precise-location permission because that is required by the platform Wi-Fi API; Android 13+ requests Nearby Wi-Fi Devices instead.

The hotspot reservation lives in `CameraStreamService`, not the Activity. It remains active while the foreground service lives. This is a local network without Internet sharing. Generated credentials may change after the hotspot is stopped and recreated; BLE uses the new session values.

The service reconnects signaling automatically and keeps the camera/WebRTC lifecycle outside the Activity.

## Exit app

**EXIT APP** is available in both Robot and Setup screens. After confirmation it disables automatic reconnection, closes the server socket and ESP32 links, releases the camera/WebRTC resources and wake lock, stops the PhonePilot local hotspot and foreground service, clears the app's internal/external temporary cache, and closes the Activity. Saved server, robot, and transport settings are preserved. Android-managed system caches and the app's saved data are not deleted.

## Selectable ESP32 control link

`CameraStreamService` owns `ServoController`, which owns both `UsbSerialServoTransport` and `WifiServoTransport`. Select **USB CABLE** to keep direct OTG control, or **WI-FI HOTSPOT** to control the provisioned ESP32 without the cable. ESP32 advertises itself over UDP port 3334; Android then uses a direct TCP line-protocol connection on port 3333. Neither `RobotSocketClient` nor `WebRtcController` contains transport-specific code.

The Wi-Fi transport sends `STATUS` immediately after connecting and polls it every five seconds. If ESP32 does not answer the initial status request within four seconds, Android closes the stale TCP connection and discovers/reconnects again. The server enables servo commands only after `STATUS ready=1` is received.

## Shared Wi-Fi mode

In Setup choose **SHARED WI-FI NETWORK** for control. Under **ESP32 NETWORK SETUP**, press **USE PHONE'S CURRENT WI-FI NAME**, then choose **VIA BLE** for the v2 board. Enter the router password for a new profile, or select a known board profile without repeating it. **VIA USB** and **VIA SETUP WI-FI** remain recovery paths; the latter asks Android to temporarily join the board's password-protected `PhonePilot-Setup-XXXXXX` network. Android can read the connected SSID with permission, but not the saved router password. The app saves a typed password only when the user checks the secure storage option. Router AP/client isolation must be off for local discovery and control.

The transport discovers the first supported USB serial device, requests permission, opens it at 115200 8N1, sends `STATUS`, handles attach/detach, and reconnects. A movement is confirmed only after `OK ANGLE <n>`. The executor keeps one active command and one latest pending target. The camera and WebRTC continue normally when USB hardware is absent.

## L298N robot movement

The web panel sends `drive_command` actions `forward`, `backward`, or `stop`. The service forwards them through the selected USB/Wi-Fi transport as `DRIVE FORWARD`, `DRIVE BACKWARD`, or `DRIVE STOP`. Drive capability/state comes from the board's separate `DRIVE_STATE` response, independently of servo readiness. Install the updated firmware manually to enable movement; servo-only firmware leaves the drive controls disabled.

Only fresh commands within their server-issued 400 ms lease reach the board. USB drops expired or pre-disconnect writes; Wi-Fi keeps only the latest drive write and clears it on disconnect. The phone never generates ongoing movement heartbeats itself. Server disconnect, route handover, transport change and service exit send STOP; the ESP32's own 700 ms watchdog handles abrupt link/process loss. See the firmware README for L298N GPIO wiring.

## Build-tool compatibility note

The uploaded project used Android Gradle Plugin `8.7.3` with a Gradle `9.3.0` wrapper. The wrapper is pinned here to Gradle `8.9`, which is the supported Gradle baseline for AGP 8.7.x. The Java target remains 17 and compileSdk remains 35.

The existing package/application ID `com.robroute.camera` is intentionally preserved to avoid turning this structural refactor into an Android package migration.
