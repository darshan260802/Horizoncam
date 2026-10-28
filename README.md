# HorizonCam

Horizon-locked video for every Android phone. HorizonCam brings the Galaxy S26 "Horizontal Lock" idea to any device running Android 8.0 or newer: rotate the phone as much as you like and the recorded video stays level with the real horizon.

## Features

**Horizon lock**
- **Steady + horizon lock**: the horizon stays level through a full rotation. The camera frame is cropped to the circle that stays inside the sensor at every angle.
- **Steady**: corrects tilts up to ±15°, ±30° or ±45° and crops less, so you keep more detail.
- **Frame orientation**: Auto, Landscape or Portrait. You can record landscape video while holding the phone upright, or the other way round.
- The viewfinder shows exactly what gets saved. Settings › Viewfinder › *Gimbal view* instead shows the full camera view with the level recording frame rotating with the horizon.
- Every camera frame is matched to the gyro reading taken at that frame's exposure time (200 Hz ring buffer, exposure and rolling-shutter compensated). This keeps the lock tight, without lag or wobble.
- **Steady** mode adds the camera's own stabilization (preview EIS on Android 13+, OIS) with a smooth lock response. **Lock only** gives a rigid, perfectly locked horizon.
- **Ultra-wide for 360°** switches to the widest lens to make up for the crop.

**Capture**
- Video: HD / FHD / QHD / UHD, 30 or 60 fps, H.264 or HEVC, standard or high bitrate, stereo AAC audio.
- Pause and resume while recording; take photos from the video while recording.
- **Horizon-locked hyperlapse** from 2× to 60×.
- **Picture processing**: the phone's own Auto / HDR / Night photo processing where the phone offers it to apps (Camera2 extensions, Android 12+).
- **Leveled photos**: the JPEG is straightened using the phone's orientation at the exact moment of exposure, then cropped to 4:3, 16:9 or 1:1.
- Camera input resolution is chosen automatically so the crop keeps full output detail where the hardware allows.

**Interface** (see [docs/DESIGN.md](docs/DESIGN.md) for the review and design system)
- Familiar Galaxy-style layout:
  - top: horizon button and video size;
  - bottom: frame button, zoom buttons and quick controls, then the PHOTO / VIDEO / HYPERLAPSE carousel, then gallery, shutter and camera switch.
- Live horizon glyph: the line in the top-left button shows the horizon your video will have.
- Fluid, interruptible spring motion throughout:
  - the mode carousel follows your finger;
  - the quick-controls sheet can be dragged or flicked closed;
  - menus grow out of their buttons;
  - controls press in on touch-down.
- Accessible:
  - text scales with the system setting;
  - 48 dp touch targets;
  - TalkBack labels and on/off states;
  - legible over a bright viewfinder;
  - respects "Remove animations" and the system contrast setting.
- Settings follow the system light or dark theme.
- Swipe sideways to change mode, swipe up or down to switch cameras, drag after tap-to-focus to change brightness, drag sideways on the zoom buttons to zoom.

**Image quality**
- Highest native-resolution camera stream (up to 12 MP), downsampled with multi-sample anti-aliasing and optional sharpening.
- Camera high-quality processing (noise reduction, edges, colour) with an automatic frame-rate safeguard.
- Photos are straightened and cropped for their exact angle.

**Camera controls**
- Lens buttons (ultra-wide / 1× / 2× / tele where available), pinch to zoom.
- Tap to focus and meter. Long-press for AE/AF lock.
- Brightness (exposure compensation) and white balance presets.
- Torch, 3 s / 10 s self-timer, rule-of-thirds grid.
- Spirit level with haptic tick when the phone is level.
- Volume keys as shutter or zoom. Front camera with optional mirroring.
- Saves to `DCIM/Camera` next to your other camera pictures (or `DCIM/HorizonCam` if you prefer a separate folder). The gallery button opens the newest capture in the phone's gallery app.
- Shutter sound can be turned off (quick controls or settings) and is muted when the phone is on silent or vibrate, except where the law requires it.

**Testing helpers**
- Settings › About › *Camera diagnostics*: copyable device and camera report (including which photo processing extensions the phone offers), plus recent errors.
- If the app crashes, the next launch opens a crash report screen with Copy and Share buttons. This also works for crashes during start-up and for native or driver crashes: the last start-up step is recorded.

## Install

Grab `HorizonCam-debug.apk` from the latest GitHub Actions run (artifact **HorizonCam-apks**) or from a release, then install it on the phone. You may need to allow "Install unknown apps".

All builds (CI, local and offline) are signed with the shared test key in `keystore/`, so newer builds install over older ones.

## Build

### Android Studio / Gradle (normal way)
```
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # geometry tests + Robolectric start-up smoke test
```
Requires JDK 17+ and the Android SDK (compileSdk 36). Android Studio installs everything automatically.

`./gradlew testDebugUnitTest` also runs the Robolectric UI tests: start-up with and without permissions, every camera control, the carousel and sheet gestures, the gallery button and the settings screen.

### Offline build (no Android SDK)
`tools/offline-build.sh` builds a signed APK without the Android SDK. It uses Debian packages (`aapt`, `zipalign`, `apksigner`), the Kotlin compiler from GitHub, the Android framework jar from Maven Central and Google's official D8 dexer from the R8 release bucket. This is how APKs are produced in sandboxes without access to Google Maven.

## How it works

```
Camera2 ──► SurfaceTexture (OES) ──► GL thread ──► preview window (gimbal / output view)
                                         │
 game rotation vector (200 Hz) ──► up-vector at frame timestamp
                                         │
                                         └──► MediaCodec input surface ──► MediaMuxer (+ AAC)
```

- `core/FramePlanner.kt`: all geometry (rotation, crop, mirroring, preview mapping), pure Kotlin and unit tested.
- `gl/HorizonRenderer.kt`: GL thread, EGL surfaces, sensor-to-frame timing.
- `camera/CameraController.kt`: Camera2 sessions, zoom, focus, still capture.
- `media/VideoRecorder.kt`: encoder, audio and muxer. `media/PhotoProcessor.kt` handles photo leveling.

## Before publishing on Google Play
- Create a private upload key and replace the debug signing config for `release`.
- Add a privacy policy (camera and microphone permissions).
- Build an App Bundle: `./gradlew bundleRelease`.
