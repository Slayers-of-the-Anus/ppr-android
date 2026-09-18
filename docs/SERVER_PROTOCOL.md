# Android <-> server contract

The Android app connects to:

```text
ws://<server>/ws/camera?cameraId=<id>&name=<name>
wss://<server>/ws/camera?cameraId=<id>&name=<name>
```

Android -> server messages currently used:

- `camera_status`
- `camera_disconnect`
- `webrtc_offer`
- `webrtc_ice`
- `webrtc_state`
- `webrtc_error`
- `webrtc_stop`
- `servo_state {available,angle,min,max}`
- `servo_error {code,detail}`

Server -> Android messages currently handled:

- `camera_ready` / legacy alias `robot_ready`
- `viewer_list`
- `webrtc_start`
- `webrtc_answer`
- `webrtc_ice`
- `webrtc_stop`
- `servo_command {cameraId,action,angle,step,requestedBy}`

For servo commands Android uses the server-calculated absolute `angle`; it does not recalculate LEFT/RIGHT. `RobotSocketClient` invokes a callback and `ServoController` uses the selected USB or Wi-Fi transport. Android publishes `servo_state` after `STATUS ready=1` and only after ESP32 acknowledges movement with `OK ANGLE`. Loss of the selected link publishes `available:false`.

The Go rewrite keeps the camera/WebRTC contract compatible with the previous Node.js v0.7 server. Servo messages extend the existing contract without changing WebRTC message handling.
