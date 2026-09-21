# Android <-> ESP32 USB and Wi-Fi integration

Do not put ESP32 implementation details into `RobotSocketClient` or `WebRtcController`.

Recommended Android boundary:

```text
RobotSocketClient
    receives servo_command(angle)
            |
            v
ServoController / ServoTransport interface
            |
            v
UsbSerialServoTransport OR WifiServoTransport
            |
            v
ESP32 text protocol: S95\n
```

Implemented interface:

```kotlin
interface ServoTransport {
    fun start()
    fun stop()
    fun moveTo(angle: Int)
    fun requestStatus()
}
```

Both transport lifecycles belong to `CameraStreamService`, so they survive Activity stop/recreation. The selected control mode is persisted as `USB` or `WIFI`. `usb-serial-for-android` supplies CDC ACM, CP210x and CH340/CH341 drivers; no VID/PID is hardcoded.

`ServoLineParser` buffers partial USB packets and emits complete responses for `\n` or `\r\n`. `ServoController` waits up to 1.5 seconds for `OK ANGLE`/`ERR`. While busy it replaces its single pending target instead of growing a queue. On connect and after timeout/ESP32 error it requests `STATUS` to resynchronize.

Stable errors sent to the server include the USB errors, `WIFI_CONNECT_FAILED`, `WIFI_DISCONNECTED`, `WIFI_WRITE_FAILED`, `SERIAL_TIMEOUT`, `ESP32_ERROR`, and `SERVO_NOT_READY`. None tears down WebRTC.

## LocalOnlyHotspot provisioning

`LocalHotspotController` requests a generated local-only hotspot from Android. Credentials are forwarded through the existing USB transport only after the user presses the send button:

```text
WIFI_CONFIG <base64-ssid> <base64-password>\n
```

ESP32 replies with `OK WIFI_SAVED`, then `WIFI CONNECTING` and `WIFI CONNECTED ip=<address>`. Wi-Fi errors update the Android diagnostic status but do not mark the servo unavailable.

After joining the hotspot, ESP32 broadcasts `PHONEPILOT_ESP32 port=3333` to UDP port 3334 every 1.5 seconds and accepts the same ASCII command protocol over TCP port 3333. `WifiServoTransport` discovers, connects and reconnects automatically. The USB path remains active for provisioning and available as the alternative control mode.

## Shared-router setup without phone-to-board USB

The Setup screen can fill the shared network's SSID from the phone's current Wi-Fi connection after location permission. Android does not expose the saved Wi-Fi password to third-party apps, so the user enters it. The password is not stored in SharedPreferences or activity state. The board advertises a temporary WPA2 `PhonePilot-Setup-XXXXXX` AP at boot; its unique generated password is printed in the Arduino Serial Monitor after flashing and retained in ESP32 NVS. Android uses `WifiNetworkSpecifier` with a system approval dialog, then a socket bound to that temporary network to send `CONFIG SHARED <base64-ssid> <base64-password>` to `192.168.4.1:3333`. The board acknowledges before switching networks. Alternatively the same `CONFIG` command can be sent over USB. Once ESP32 joins the shared router, the existing UDP discovery/TCP control path is reused. The server must be reachable on that LAN; router client isolation must be disabled.

The firmware-side contract is in `../../esp32-firmware/docs/SERIAL_PROTOCOL.md`.
