# Changelog

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
