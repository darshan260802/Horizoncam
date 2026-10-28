# Changelog

## 1.0.0
First production release. The interface was reviewed against Apple's Human Interface Guidelines
(principles, accessibility, color, type, motion, writing) and rebuilt with fluid, physical motion.
The full review and design system are in docs/DESIGN.md.

**Design**
- New signature: a live horizon glyph in the top bar. Its line shows the horizon your video will have: it tilts with your hand when leveling is off, and stays level with Steady or horizon lock. Each mode also has its own shape, so state never depends on color alone.
- One accent everywhere: amber means on, selected or locked, on the camera screen and in settings.
- Settings follow the system light or dark theme, with checked contrast in both.
- Scrims behind the top bar and the bottom controls keep labels readable over a bright sky (white text at least 7:1 at the mode labels).

**Motion**
- Every movement uses springs: interruptible, starting from what is on screen, and carrying the finger's velocity.
- Controls press in on touch-down.
- The mode carousel follows your finger and snaps to the nearest mode with a tick.
- The quick-controls sheet has a grabber. Drag or flick it down to close; it rubber-bands when pulled up.
- Menus grow out of their buttons. The zoom selection slides between lenses.
- Controls turn with the phone on a rotation spring.
- The capture blink is a gentle dim instead of a white flash.
- With Android's "Remove animations" on, everything switches to instant changes and fades.

**Accessibility**
- Text follows the system size: up to 200% in settings and menus, capped at 130% for labels over the viewfinder so the controls keep their layout.
- All touch targets are at least 48 dp.
- TalkBack:
  - announces switches and toggles with their on/off state, and modes, zoom buttons and menu items with their selection;
  - reads the shutter's action ("Take photo", "Start recording", "Stop recording") and the recording time;
  - reads every on-screen message aloud.
- Android 14+ contrast setting: heavier scrims.

**Behavior**
- The camera permission screen has a single Continue button. The microphone is asked for when you first record a video with sound, not at launch.
- The first-run dialog is replaced by a one-time tip at the horizon button that closes on tap.
- No vibration while recording, because it would reach the microphone and the stabilizer. The shutter still answers.
- Camera errors are plain sentences with a next step, such as "Another app is using the camera. Close it and try again."
- Quick controls: grid and level are combined into Guides. Changes update the panel in place, so the brightness slider doesn't stutter.
- The overlay only redraws while something on it changes, which saves battery during long recordings.

## 0.3.1
- Photos and videos are saved to DCIM/Camera with your other camera pictures by default. Settings › Storage › *Save to a separate folder* switches to DCIM/HorizonCam.
- The gallery button opens the capture directly in the phone's gallery app (no "open with" chooser), using the camera review action so galleries that support it can swipe through recent captures.
- The gallery button always shows the newest capture that still exists: deleting a photo or video in the gallery updates it when you come back.
- Shutter sound: new Sound toggle in the quick controls, the setting moved to the top of Settings › General, and camera sounds stay quiet when the phone is on silent or vibrate. Where the law requires the sound (reported by Android 13+), the setting says so.

## 0.3.0
Redesigned to look and work like the latest Galaxy camera, with horizon lock built in.

- New camera screen: stabilization button (Off / Steady / Steady + horizon lock) and a video size chip at the top; frame, zoom buttons and quick controls above the mode carousel; gallery, shutter and camera switch at the bottom.
- Quick controls panel: settings, torch, timer, ratio, video size or picture processing, brightness with white balance, grid and level.
- Video size menu with UHD / QHD / FHD / HD at 30 or 60 fps (60 only when the camera supports it) and 9:16, 3:4 and 1:1 ratios.
- Pause and resume while recording, and take photos from the video while recording (horizon-locked, full video resolution).
- Picture processing: uses the phone's own Auto, HDR or Night processing for photos when the phone offers it to apps (Android 12+).
- New settings screen in the One UI style (large title, grouped cards, switches and choice dialogs).
- The viewfinder now starts under the top bar and shows exactly what is recorded; swipe up or down to switch cameras; drag after tap-to-focus to change brightness.
- New shutter design: white ring with a white button for photos and a red button for video that turns into a stop square.

## 0.2.0

**Image quality**
- Sharper video and preview: the horizon rotation now uses multi-sample anti-aliasing (no more shimmer or jagged edges) plus a gentle sharpening pass (Settings › Image quality › Sharpness).
- The camera streams its highest native resolution (up to 12 MP) so the horizon-lock crop keeps much more real detail.
- Uses the camera's high-quality noise reduction, sharpening and colour processing for video, and falls back automatically if a phone can't keep the frame rate.
- 360° mode now uses the main camera by default. The ultra-wide is still one tap away (or turn it back on in Settings).
- Higher video bitrates (about 15 Mbps at 1080p30 and 60 Mbps at 4K30 with H.264) with proper BT.709 colour tags.
- Photos are straightened for their exact angle and cropped only as much as needed, often 2–4× more pixels than before. The photo preview frame shows the exact crop live.

**One-handed UI**
- All controls moved to the bottom: a quick bar for More, Lock, Frame, Light, Timer and Adjust.
- Tap Lock, Frame or Timer to pick from a pop-up row with every option visible.
- Drag sideways on the lens buttons to zoom; lens changes now glide smoothly.
- Swipe up or down anywhere on the preview to change brightness.
- New bottom-sheet settings with tap-to-choose options; drag down or tap outside to close.
- Smooth fades when switching camera or mode, haptic feedback, and a cleaner status line.

## 0.1.1

- Fixed the launch crash on Samsung Galaxy S23 and other devices (hidden framework API).
- Crash report screen with Copy and Share, and start-up breadcrumbs for driver crashes.
- APKs are now built by GitHub Actions with the official Android SDK.

## 0.1.0

- First version: 360° horizon lock, Auto Level, time-lapse, leveled photos.
