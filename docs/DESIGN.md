# HorizonCam design: review and 1.0 redesign

This is a design review of HorizonCam 0.3.1 and the plan behind 1.0. It uses two guides:

- Apple's Human Interface Guidelines, through the *apple-hig-review* skill. HorizonCam is an
  Android app, so only Apple's principles and foundations apply: accessibility, color, type,
  layout, motion, writing and privacy. Apple's iOS platform conventions do not. Android and Galaxy
  conventions (back gesture, edge-to-edge, the Galaxy camera layout people already know) stay in
  place.
- The *apple-fluid-motion* skill, for springs, interruptible motion, gestures and materials.

Citations name the HIG file and heading, for example `accessibility.md › Mobility`. Anything else
is marked as judgment. Contrast figures are WCAG ratios computed from the hex values in the code,
not estimated from screenshots.

---

## Design review: HorizonCam 0.3.1

### Summary

HorizonCam has one job: capture level, steady video and photos as quickly as the phone's own
camera. Its most characteristic idea is that **the horizon stays put while the phone moves**. 0.3.1
copies the Galaxy camera layout well, but that idea never appears in the interface itself. The
lock is a running-figure icon, the same one Samsung uses for its stabilization mode.

The main problems:

- Text drawn over a bright viewfinder drops to 1.2:1 contrast.
- Controls clip or truncate at large text sizes.
- Several touch targets are below the minimum size.
- Screen readers can't tell what is on.
- Motion uses fixed-duration tweens that can't be interrupted.
- A dialog appears on first launch.

**Overall rating: Critical issues.**

### Critical

- **Text over the viewfinder loses contrast.**
  - **What:** Mode labels, zoom labels, the resolution chip and the recording timer sit directly on
    the live preview, with no scrim. White text on a bright sky (#E6E6E6) measures 1.25:1.
    Unselected mode labels (#B3FFFFFF) on #BFBFBF measure 1.56:1. The amber zoom label measures
    1.24:1. All are below the 4.5:1 minimum.
  - **Why:** `accessibility.md › Vision`: "Strive to meet color contrast minimum standards… Up to
    17 pts: 4.5:1". `materials.md › Liquid Glass`: "determine whether to add a dimming layer behind
    components".
  - **Fix:**
    - Add gradient scrims behind the top bar and the bottom controls. They act like a scroll edge
      effect: they appear only where chrome floats over the picture. The bottom scrim is 65% black
      at the mode carousel, so even over a white sky white text is 7.0:1, unselected labels at 80%
      white are 5.2:1, and amber is 4.5:1.
    - Add a 1 dp text shadow to every label drawn on the preview.

- **Controls break at large text sizes.**
  - **What:** At 200% font scale:
    - "HYPERLAPSE" wraps to "HYPERLAP / SE".
    - The video-size chips clip their second line ("UHD 60" shows "UHD").
    - Quick-control labels truncate to "Settin", "Bright" and "Video".
  - **Why:** `typography.md › Supporting Dynamic Type`: "Make sure your app's layout adapts to all
    font sizes" and "Keep text truncation to a minimum". `typography.md › Conveying hierarchy`:
    "Prioritize important content when responding to text-size changes… people don't expect the
    tab titles to increase in size."
  - **Fix:**
    - Camera chrome (labels over the viewfinder) scales with the system setting but is capped at
      1.3×, like tab titles. Chips and labels use wrap-content widths with minimum sizes instead of
      fixed widths.
    - The settings screen and menus scale fully and grow vertically.

- **Touch targets are too small.**
  - **What:** Several controls are below 44 dp (Android's own minimum is 48 dp):
    - the resolution chip: 30 dp tall
    - the zoom buttons: 42 dp
    - the mode labels: about 36 dp tall
    - the menu chips: 40 dp tall
    - the frame and quick-controls buttons: 46 dp
  - **Why:** `accessibility.md › Mobility`: "Offer sufficiently sized controls… iOS 44x44 pt"
    and "Consider spacing between controls as important as size." `buttons.md › Best practices`: "a
    button needs a hit region of at least 44x44 pt".
  - **Fix:** Every control gets a hit area of at least 48 dp; the visible shape can stay smaller.
    The chip keeps a 32 dp outline inside a 48 dp hit area. Zoom buttons, mode labels and menu chips
    become 48 dp tall.

- **Screen readers can't tell what is on.**
  - **What:** Settings switch rows are announced only as their title, with no role and no on/off
    state. Quick-control toggles, mode labels and zoom buttons don't report their selected state.
  - **Why:** `accessibility.md › Vision`: "Describe your app's interface and content for
    VoiceOver". `toggles.md › Best practices`: "Make sure the visual differences in a toggle's state
    are obvious… Avoid relying solely on different colors".
  - **Fix:**
    - Switch rows report the Switch role and their checked state through an accessibility delegate.
    - Toggles, modes and zoom buttons set `isSelected` and a state description.
    - The shutter's label follows its state: "Take photo", "Start recording" or "Stop recording".
    - Toasts are also announced to TalkBack.

### Improvements

- **High: animations can't be interrupted and use fixed durations.**
  - **What:** Menus, the quick panel and UI rotation use 120–300 ms tweens. Re-tapping mid-animation
    restarts them from the target value, so they jump. Nothing tracks the finger.
  - **Why:** `motion.md › Best practices`: "Let people cancel motion… don't make people wait for an
    animation to complete." Fluid motion §3: "Always animate from the presentation value";
    §4: "use springs".
  - **Fix:** Add a small spring engine (response plus damping ratio, analytic, Choreographer-driven)
    used for every movement:
    - Springs start from the current on-screen value and velocity.
    - Default: damping 1.0, response 0.35 s.
    - Flicked or thrown surfaces: damping 0.85.
    - UI rotation when the phone turns: Apple's rotation preset, damping 0.8, response 0.4 s.

- **High: no press state on most controls.**
  - **What:** Icon buttons, chips and zoom buttons only show a ripple after the touch is accepted.
    The shutter's press scale jumps instantly.
  - **Why:** `buttons.md › Best practices`: "Always include a press state for a custom button."
    Fluid motion §1: "Respond on pointer-down, not on release."
  - **Fix:** Every control springs to 92% scale on touch-down and springs back on release or cancel.

- **High: a dialog on first launch, and permissions asked too early.**
  - **What:** First launch shows a "Horizon Lock" AlertDialog. The microphone permission is requested
    at launch together with the camera.
  - **Why:**
    - `onboarding.md › Best practices`: "Teach through interactivity."
    - `feedback.md › Best practices`: alerts are for critical information.
    - `privacy.md › Requesting permission`: "Request permission only when your app clearly needs
      access… wait to request permission until people actually use an app feature that requires
      access."
    - `privacy.md › Pre-alert screens`: "Include only one button and make it clear that it opens the
      system alert… Use a term like 'Continue'."
  - **Fix:**
    - Replace the dialog with a one-time tip anchored to the horizon button. It closes on tap and
      has no timer.
    - Ask for the camera at launch, with a single "Continue" button on the pre-permission screen.
    - Ask for the microphone when the person first starts a video.

- **High: haptics fire while recording.**
  - **What:** Tap ticks, zoom detents and the "level reached" tick play during recording.
  - **Why:** `playing-haptics.md › Best practices`: "Ensure that haptic vibrations don't disrupt
    experiences involving device features like the camera, gyroscope, or microphone." The vibration
    is recorded by the microphone and nudges OIS and the gyro that drives the horizon lock.
  - **Fix:** No haptics while recording, except for the stop action.

- **Medium: the quick panel is a sheet you can't swipe away.**
  - **What:** It has no grabber, doesn't follow the finger, and closes only with a tap elsewhere or
    Back.
  - **Why:** `sheets.md › Mobile`: "Support swiping to dismiss a sheet" and "Include a grabber".
    Fluid motion §2 (1:1 tracking), §5 (velocity handoff), §6 (momentum projection) and
    §9 (rubber-banding).
  - **Fix:**
    - Add a grabber, which also works as a "Close" button for TalkBack.
    - Track drags 1:1 from the grab offset. Upward drags rubber-band.
    - On release, project momentum with Apple's `v/1000·d/(1−d)`, d = 0.998. Dismiss if the
      projected point is past half the panel; otherwise spring back, handing over the release
      velocity.
    - Settings changes update cells in place instead of rebuilding the grid, so dragging the
      brightness slider doesn't stutter.

- **Medium: the mode row jumps instead of sliding.**
  - **What:** The selected mode's background moves instantly. Swiping the preview changes modes, but
    the labels don't follow the finger.
  - **Why:** `gestures.md › Best practices`: "Handle gestures as responsively as possible…
    provide feedback that helps them predict its results."
  - **Fix:** Turn the row into a carousel that keeps the selected mode centered above the shutter.
    Dragging it follows the finger; release projects and snaps to the nearest mode with a tick.

- **Medium: menus don't come from their buttons.**
  - **What:** The stabilization and video-size menus fade in in place.
  - **Why:** Fluid motion §7: "Anchor interactions to their source", "Enter and exit along the
    same path".
  - **Fix:** Menus scale out of their trigger (pivot at the button), and close back into it.

- **Medium: the capture flash is a full-screen white blink.**
  - **What:** 78% white for 180 ms.
  - **Why:** `accessibility.md › Cognitive`: "Be cautious with fast-moving and blinking
    animations". Fluid motion §14: avoid "abrupt brightness jumps".
  - **Fix:** A brief dim of the viewfinder (40% black), which reads as a shutter without a bright
    flash.

- **Medium: the overlay redraws every frame.**
  - **What:** It calls `postInvalidateOnAnimation()` unconditionally, even when nothing changes.
  - **Why:** Judgment: it wastes GPU time and battery and competes with the camera preview.
  - **Fix:** Invalidate only while something is animating or the level is shown.

- **Medium: camera errors show internals.**
  - **What:** Errors reach people as "Camera error 4" or "Camera 0 unavailable: …".
  - **Why:** `writing.md › Best practices`: "Write clear error messages… be clear about what someone
    can do to fix it."
  - **Fix:** Map errors to plain sentences ("Another app is using the camera. Close it and try
    again."). The technical detail goes to the diagnostics log.

- **Medium: the camera and settings use different accents.**
  - **What:** "On" is amber on the camera screen and blue in settings, and the blue also marks
    plain values.
  - **Why:** `color.md › Best practices`: "Avoid using the same color to mean different things."
  - **Fix:** One accent, Level amber, means *on, selected or locked* everywhere. Setting values use
    secondary text color.

- **Medium: settings ignore the system's light appearance.**
  - **What:** Settings are always black.
  - **Why:** `color.md › Best practices`: "Make sure all your app's colors work well in light,
    dark, and increased contrast contexts."
  - **Fix:** Settings follow the system light or dark theme with tokenized colors, all checked for
    contrast (below). The viewfinder stays dark in both themes: chrome around a live picture should
    not compete with it (judgment).

- **Low: the settings title uses a light font weight.**
  - **What:** The big title uses a light weight.
  - **Why:** `typography.md › Ensuring legibility`: "In general, avoid light font weights."
  - **Fix:** Use the regular weight with slightly negative tracking.

- **Low: reduced motion isn't honored.**
  - **What:** Animations play the same with Android's "Remove animations" turned on.
  - **Why:** `accessibility.md › Cognitive`: reduce animations when Reduce Motion is on.
  - **Fix:** Springs snap to their end state, and transitions become cross-fades when Android
    reports animations are off (`ValueAnimator.areAnimatorsEnabled()`). Spring response follows
    the system animation scale.

### Craft notes

- **High: no point of view.** The interface is a faithful copy of Samsung's camera with an amber
  accent; nothing in it says "horizon". The signature element should be the product's idea itself:
  a live horizon glyph in the top bar.
  - With Steady or horizon lock on, it shows a level amber line.
  - With leveling off, a white line tilts with your hand, showing the tilt your video will have.
  - The three modes also differ in shape (dashed circle, solid circle, circle with rotation
    arrows), so color isn't the only cue.
  - The same glyph appears in the stabilization menu.
- **Remove one thing:** the launch dialog (see above). Everything else on the camera screen earns
  its place.

### What works

- One-handed layout: everything used while shooting is in the bottom third.
- The shutter is clear: white for photos, red for video, morphing to a stop square.
- The mode-dependent quick control (video size, picture processing or hyperlapse speed) puts
  task-specific options where they're used (`settings.md › Task-specific options`).
- Settings are grouped into few cards, with switches for binary options and choice dialogs for
  lists.

### Platform notes

- Android conventions are kept: system back (including predictive back) closes pop-ups,
  edge-to-edge with cutout handling, sentence-case labels, and uppercase mode names as on the
  Galaxy camera.
- Android has no Reduce Transparency setting. The chrome material is opaque enough (86%) that it
  doesn't need one. On Android 14+, the system contrast setting (`UiModeManager.contrast`) makes
  the scrims and material stronger.

---

## Redesign plan (1.0)

### Subject

A camera for people filming while moving (walking, cycling, from a car or boat), shooting
one-handed and outdoors in bright light. The material world is the horizon: a level line,
sunlight, a spirit-level bubble.

### Color tokens

The viewfinder chrome is always dark. Settings follow the system theme.

| Token | Dark | Light (settings) | Role | Contrast |
| --- | --- | --- | --- | --- |
| `chrome` | #161618 at 86% | – | Panels and menus over the viewfinder | – |
| `content` | #FFFFFF | #000000 | Primary text | 18.1:1 on chrome |
| `secondary` | #A1A1A8 (chrome), #98989F (settings) | #6C6C70 | Secondary text, setting values | 7.0:1 chrome, 5.9:1 dark card, 5.2:1 light card |
| `level` (accent) | #FFC83D | #995700 | On, selected, locked: the one accent | 11.7:1 chrome, 11.0:1 dark card, 5.65:1 light card |
| `record` | #F5372B | #F5372B | Recording only | 5.5:1 on black |
| `scrim` | black, 50% top / 65% bottom gradient | – | Legibility over the viewfinder | white text ≥ 7.0:1 at the mode row on a white sky |

Off-state switch tracks are #76767C (dark, 3.8:1 against the card) and #8E8E93 (light, 3.3:1),
which meets the 3:1 non-text minimum.

### Type

System sans (Roboto, or One UI's font on Galaxy phones); no custom face. Hierarchy comes from
weight and size; tracking depends on size: tighter on large text, slightly looser on small
uppercase labels.

| Style | Size / weight | Tracking | Use |
| --- | --- | --- | --- |
| Large title | 32 sp regular | −0.01 em | Settings title |
| Title | 17 sp regular | 0 | Setting rows, menu items (16 sp medium) |
| Body | 14 sp regular | 0 | Summaries, descriptions |
| Label | 14 sp medium, uppercase | +0.06 em | Mode carousel |
| Control | 13 sp medium | +0.01 em | Chips, zoom buttons |
| Caption | 12 sp medium / 11 sp | +0.01 em | Quick-control labels and values (11 sp is the minimum) |

Camera chrome is capped at 1.3× the system font scale. Settings and menus scale fully.

### Layout

```
 ┌───────────────────────────────┐  ← top scrim (status area + bar)
 │ (◎—)          ·status·  FHD 30│  horizon glyph · status · video size chip (48 dp hit areas)
 │ ┌───────────────────────────┐ │
 │ │                           │ │
 │ │      viewfinder (shows    │ │  starts under the top bar;
 │ │      exactly what's       │ │  3:4 sits right below it,
 │ │      recorded)            │ │  9:16 runs under the controls
 │ │                           │ │
 │ └───────────────────────────┘ │  ← bottom scrim begins
 │  [▭]     .6  (1×)  2  5    [⋮⋮]│  frame · zoom (sliding selection) · quick controls
 │      PHOTO  [VIDEO]  HYPERLAPSE│  carousel: selected mode centered over the shutter
 │   (▣)        ( ◉ )        (⟲) │  gallery · shutter · switch  (pause/snapshot while recording)
 └───────────────────────────────┘
```

The quick-controls sheet rises from the quick-controls button, with a grabber; drag it down to
dismiss.

### Signature

The live horizon glyph described under Craft notes. It belongs to this product because it shows
the product's promise as it happens: your hand tilts, the line doesn't.

### Motion

The only orchestrated moment is the horizon glyph settling level when you turn the lock on. All
other motion is functional feedback:

| Interaction | Spring (damping / response) |
| --- | --- |
| Press feedback | 1.0 / 0.18 s down, 1.0 / 0.30 s up |
| Menus, sheet open and close (tap) | 1.0 / 0.35 s, from the trigger |
| Sheet or carousel released with momentum | 0.85 / 0.35 s, with the release velocity |
| UI rotation when the phone turns | 0.8 / 0.4 s |
| Zoom selection, shutter morph | 1.0 / 0.3 s |
| Horizon glyph line | 0.8 / 0.4 s |

With animations off, springs jump to the end state and enters and exits become short cross-fades.

### Critique of the plan

Would this plan fit any camera app? The token table nearly would, since dark chrome with one
accent is normal for cameras, and that is fine for a utility. What makes it HorizonCam's:

- amber means *level / locked* rather than generic "on";
- the signature is the horizon glyph, which only makes sense for this product.

An earlier version also restyled the shutter. That was dropped: the Galaxy shutter is familiar,
and familiarity wins for the most-used control (`design-principles.md` › Familiarity).

Quality floor checked in this release:

- 200% font scale renders without clipping.
- All touch targets are at least 48 dp.
- Reduced motion is respected.
- Everything works with TalkBack labels and states.
