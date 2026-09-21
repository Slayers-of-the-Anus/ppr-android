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

## Phone-created ESP32 hotspot

The **START ESP32 HOTSPOT** button requests Android `LocalOnlyHotspot` and displays the generated SSID/password. Connect ESP32 by USB, then press **SEND WI-FI TO ESP32 VIA USB** to send and persist those credentials. Provisioning is intentionally explicit; starting a hotspot alone never changes the ESP32 configuration. Android 12 and older request precise-location permission because that is required by the platform Wi-Fi API; Android 13+ requests Nearby Wi-Fi Devices instead.

The hotspot reservation lives in `CameraStreamService`, not the Activity. It remains active while the foreground service lives. This is a local network without Internet sharing. On Android 12 the generated credentials are not guaranteed to remain identical after the hotspot is fully stopped/recreated, so USB provisioning may need to be repeated after a phone/app restart.

The service reconnects signaling automatically and keeps the camera/WebRTC lifecycle outside the Activity.

## Selectable ESP32 control link

`CameraStreamService` owns `ServoController`, which owns both `UsbSerialServoTransport` and `WifiServoTransport`. Select **USB CABLE** to keep direct OTG control, or **WI-FI HOTSPOT** to control the provisioned ESP32 without the cable. ESP32 advertises itself over UDP port 3334; Android then uses a direct TCP line-protocol connection on port 3333. Neither `RobotSocketClient` nor `WebRtcController` contains transport-specific code.

The Wi-Fi transport sends `STATUS` immediately after connecting and polls it every five seconds. If ESP32 does not answer the initial status request within four seconds, Android closes the stale TCP connection and discovers/reconnects again. The server enables servo commands only after `STATUS ready=1` is received.

## Shared Wi-Fi mode

In Setup choose **SHARED WI-FI NETWORK** for control. Under **ESP32 NETWORK SETUP**, press **USE PHONE'S CURRENT WI-FI NAME**, enter the router password, then choose **VIA USB** or **VIA SETUP WI-FI** and press **SEND MODE & NETWORK TO ESP32**. The latter path asks Android to temporarily join the board's password-protected `PhonePilot-Setup-XXXXXX` network; its password is printed in Arduino Serial Monitor when the board starts. Android can read the connected SSID with permission, but not the saved router password, and the app does not persist that password. Server URL should point to the server's address reachable on the shared LAN. Router AP/client isolation must be off for local discovery and control.

The transport discovers the first supported USB serial device, requests permission, opens it at 115200 8N1, sends `STATUS`, handles attach/detach, and reconnects. A movement is confirmed only after `OK ANGLE <n>`. The executor keeps one active command and one latest pending target. The camera and WebRTC continue normally when USB hardware is absent.

## Build-tool compatibility note

The uploaded project used Android Gradle Plugin `8.7.3` with a Gradle `9.3.0` wrapper. The wrapper is pinned here to Gradle `8.9`, which is the supported Gradle baseline for AGP 8.7.x. The Java target remains 17 and compileSdk remains 35.

The existing package/application ID `com.robroute.camera` is intentionally preserved to avoid turning this structural refactor into an Android package migration.
