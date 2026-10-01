# Air Control – Hand Gesture Controller (Android, Java)

Control other Android apps (Gallery/Photos, PDF viewers, browsers, video players …) with hand
gestures seen by the front camera. Press **START AIR CONTROL**, press Home, open Gallery, swipe your
hand → the photo changes. Everything runs on the phone; nothing is uploaded (the app has **no
INTERNET permission**).

## Build & install

Requirements: Android Studio (Narwhal or newer), JDK 11+ (Android Studio's bundled JDK is fine),
internet on the **first** build.

1. Unzip, then **File ▸ Open** the `AirGest` folder in Android Studio and let Gradle sync.
2. On the first build Gradle downloads the MediaPipe model `hand_landmarker.task` (~7.5 MB) into
   `app/src/main/assets/` (task `downloadHandModel`, runs automatically before every build).
   *Offline?* Download  
   `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`  
   and save it as `app/src/main/assets/hand_landmarker.task`.
3. Enable USB debugging on your phone, connect it, press ▶ **Run**  
   — or build an APK: **Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)** →
   `app/build/outputs/apk/debug/app-debug.apk`, copy it to the phone and install it.
4. If Gradle sync complains about the MediaPipe version, change `mediapipe` in
   `gradle/libs.versions.toml` (known good: `0.10.14`; newer 0.10.x releases use the same API).

Package: `com.example.airgest` · minSdk 26 · target/compile SDK 36 · Java 11.

## First-run flow

1. Open **Air Control** → tap **START AIR CONTROL**.
2. Allow **Camera** (a dialog explains why) and, on Android 13+, **Notifications**.
3. Tap **ENABLE ACCESSIBILITY** → Android Settings ▸ Accessibility ▸ *Air Control* → switch on.
   (Apps cannot do this silently; it is always your decision.)
4. Back in Air Control tap **START AIR CONTROL**. Notification **"Air Control Active"** appears
   (actions: *Stop Control*, *Open Air Control*). Press Home, open Gallery, open a photo.
5. Raise your hand ~30–50 cm from the front camera, **rest it for a moment** (this "arms" the swipe),
   then swipe quickly left→right = **next image**, right→left = **previous image**.

Stop any time with **STOP AIR CONTROL** (app) or **Stop Control** (notification).

## Gestures

| Gesture | How | Default action |
|---|---|---|
| Swipe right | hand moves quickly left → right (mirror view) | Next image (screen swipe right→left) |
| Swipe left | hand moves quickly right → left | Previous image |
| Pinch | thumb + index touch, other fingers open | Click at the Air Cursor |
| Double pinch | two quick pinches (≤ 450 ms apart) | Double click |
| Open palm | hold still ~0.35 s = SCROLL MODE, then hand up / down from the start point (keep it there to keep scrolling) | Scroll up / down |
| Fist | hold ~0.35 s, move hand, open hand to release | Press-hold-drag at the cursor |
| Two-finger zoom | only thumb + index out ("L"), hold, move them apart / together | Pinch-zoom in / out |

Per-app mappings: **App Profiles** (Gallery, PDF Reader, Browser, Video Player; unknown apps use
Gallery). Profile is chosen from the foreground app's package name. Video Player uses media keys.
All thresholds: **Calibration** (sensitivity, pinch threshold, swipe distance, swipe speed, cursor
smoothing, cooldown, **RESET TO DEFAULT**). **Debug** shows live hand / gesture / FPS / service status.

## Architecture

```
MainActivity (setup + start/stop only)
   └─ AirControlForegroundService  (type=camera, persistent notification)
        ├─ HandTrackingManager      CameraX front camera -> upright+mirrored frame -> MediaPipe HandLandmarker (21 landmarks)
        ├─ GestureDetector          pure-Java state machine, no Android deps (unit-tested)
        │     IDLE → HAND_DETECTED → TRACKING → GESTURE_CANDIDATE → GESTURE_CONFIRMED
        │          → ACTION_EXECUTED → COOLDOWN → IDLE
        └─ ActionManager            gesture -> action (per AppProfile) -> AccessibilityService
              └─ AirControlAccessibilityService   dispatchGesture() / performGlobalAction()
                    └─ OverlayManager             Air Cursor + gesture HUD (TYPE_ACCESSIBILITY_OVERLAY)
```

Why it does not fire repeatedly: a swipe needs 2 consecutive qualifying frames, fires once, then
COOLDOWN (default 800 ms), and afterwards the hand must **come to rest** (~150 ms) before another
swipe is accepted — this also swallows the return stroke of your hand. Holding the hand at the end
of a swipe for 2 s changes exactly one image. Verified by the unit tests.

Key files: `GestureDetector.java`, `HandTrackingManager.java`, `AirControlForegroundService.java`,
`AirControlAccessibilityService.java`, `ActionManager.java`, `OverlayManager.java`,
`SettingsManager.java`, `AppProfile.java`, `res/xml/accessibility_service_config.xml`.

## Tests

`./gradlew testDebugUnitTest` runs `GestureDetectorTest` (synthetic hand landmarks): swipe fires
once and not on slow/diagonal/entering-hand movement, return stroke ignored, pinch / double pinch,
scroll, drag + release (incl. hand lost), zoom, state-machine order.

## Honest limitations (please read)

* **Android decides what is possible.** The camera may only run while Air Control shows its
  foreground-service notification, and Android shows the green camera indicator while it does.
  This cannot and should not be hidden. If another app (e.g. a video call) takes the front camera,
  Android may cut Air Control's camera. The camera is released while the screen is off.
* **Restart:** Android does not allow starting a camera service from a boot broadcast. The
  "start after restart" setting therefore posts a notification after boot; tap it to start.
  Accessibility is never enabled automatically.
* **Accessibility gestures** do not work on the lock screen, on secure screens / password fields
  that block injected input, or in apps that ignore them. Drag and pinch-zoom are **best effort**:
  they work only in apps that respond to ordinary touch drags / two-finger pinch (most photo
  viewers, maps and browsers do; not every app). Zoom is not universal.
* "Next image" is performed as a finger swipe right→left, which is what Google Photos and most
  galleries expect. If an app navigates the other way, swap the two mappings in *App Profiles*.
* "Scroll up" means the view moves toward its top; flip with *Invert scroll direction*.
* **Tuning:** pose thresholds (finger extended, pinch distance …) are geometry-based and were
  verified with synthetic hands on the JVM, but not on a physical phone in my environment. Use
  *Debug* + *Calibration* to tune for your phone/lighting. Good light and a plain background help a lot.
* This project was type-checked against the Android framework API and unit-tested, but I could not
  run a full Gradle/Android build or test on a device in my environment, so the first build on your
  machine is the real integration test.

## Privacy

All tracking is local. No camera frames, hand images or landmarks are stored or sent anywhere; the app
has no internet permission. The accessibility service does **not** request screen-content access;
it only injects the gestures you make and reads which app is in front (to pick a profile).
