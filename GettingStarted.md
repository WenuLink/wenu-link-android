# WenuLink - Getting Started

WenuLink allows you to connect a DJI drone to an Android device, send MAVLink commands and stream the camera feed via WebRTC.
This guide will help you set up your environment and run the Android app for local testing.

## > Environment Configuration

Clone the project:
```
git clone https://github.com/angel-ayala/wenu-link-android
cd WenuLink
```

### DJI SDK API Key

Obtain a valid [DJI Developer API Key](https://developer.dji.com/) and add it to:

`app/src/main/res/values/keys.xml`
```
<resources>
    <string name="dji_key" translatable="false">"Y0urDJ1k3yH3r3"</string>
</resources>
```

If the file does not exist, create it manually.


### WebRTC and GCS Network Setup

Update your **local network IP address** in the following source files.

#### WebRTC signaling server:

Path: `app/src/main/java/org/WenuLink/webrtc/WebRTCService.kt`

Line: `private val signalingServer = "ws://<YOUR_LOCAL_IP>:8090"`


#### MAVLink endpoint:

Path: `app/src/main/java/org/WenuLink/mavlink/MAVLinkService.kt`

Line: `private const val endpointAddress = "<YOUR_LOCAL_IP>"`

## > Service Execution Flow

1. Connect the **DJI remote controller** to the Android device via USB.
2. Launch the **WenuLink** application.
3. Wait until you see:

SDK status: Registered

4. Power on the DJI drone.
5. Once detected, the **Start Drone Service** button will be enabled.
6. Tap it to initialize WebRTC and MAVLink services.


## > Accessing the Drone Camera Feed (WebRTC)

To receive video in a web browser:

1. Clone the WebRTC signaling/streaming server repository:
```
git clone https://github.com/angel-ayala/webrtc-client-server
cd webrtc-client-server
npm install
npm start
```

2. Open your browser at:

```http://localhost:3000```


3. In the WenuLink app, copy the **Socket ID**.
4. Paste it into the browser → click **Get Streaming**.
5. If everything is correct, you will receive real-time video from the drone’s main camera.

---

##  Troubleshooting

| Problem | Possible Cause | Recommended Solution |
|--------|----------------|--------------------|
| SDK fails to register | Missing/invalid API key or poor internet connection | Verify DJI key and internet connection |

