# Monoposto VR Mirror

Live SBS (side-by-side) VR mirror of Monoposto on a single Samsung Galaxy M30s,
using MediaProjection + OpenGL ES external OES textures. No root, no PC, no
internet, no video recording.

## Why I could not hand you a compiled APK

I built and reasoned through this entire project, but the sandbox I write code
in has no Android SDK and no network access to Google's Maven repository
(`dl.google.com`), so `gradlew assembleDebug` cannot run here. Everything below
is a complete, real source tree — you build the APK yourself in Android
Studio, which takes about 5 minutes.

## Build & install

### Option A — you have a PC
1. Install Android Studio (Koala or newer).
2. `File > Open` this folder (`MonopostoVRMirror/`).
3. Let Gradle sync.
4. `Build > Build Bundle(s)/APK(s) > Build APK(s)`.
5. Copy `app-debug.apk` to the M30s and install it.

### Option B — phone only, no PC (uses GitHub Actions to compile in the cloud)
This project includes `.github/workflows/build.yml`, which builds the APK on
GitHub's servers the moment you push the code. Your phone never needs to run
Gradle or the Android SDK.

1. **Install Termux** on the M30s — get it from F-Droid
   (`https://f-droid.org/packages/com.termux/`) or the Termux GitHub releases
   page, **not** the Play Store version (that one is outdated and broken).
2. Create a free GitHub account at github.com if you don't have one, then
   create a new **empty** repository (no README, no .gitignore) — call it
   `monoposto-vr-mirror`.
3. Create a Personal Access Token: on github.com → your profile picture →
   Settings → Developer settings → Personal access tokens → Tokens (classic)
   → Generate new token → check the `repo` scope → Generate → copy the token
   somewhere safe (you'll paste it once, in step 6).
4. Unzip the project I gave you into your phone's storage. In Termux:
   ```
   termux-setup-storage
   pkg install git openssh
   cp -r /sdcard/Download/MonopostoVRMirror ~/MonopostoVRMirror
   cd ~/MonopostoVRMirror
   ```
   (adjust the `/sdcard/Download/...` path to wherever the unzipped folder
   actually landed)
5. Initialize and commit:
   ```
   git init
   git add .
   git -c user.email="you@example.com" -c user.name="You" commit -m "initial"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/monoposto-vr-mirror.git
   ```
6. Push (when asked for a password, paste the Personal Access Token, not your
   GitHub password):
   ```
   git push -u origin main
   ```
7. Open github.com in your phone's browser → your repo → **Actions** tab.
   You'll see a "Build APK" run start automatically. Tap it and wait
   (usually 3-6 minutes).
8. When it finishes (green check), scroll down to **Artifacts** → tap
   `MonopostoVRMirror-debug-apk` → it downloads a `.zip` containing
   `app-debug.apk`.
9. Unzip that on the phone (any file manager with unzip, or Termux:
   `unzip app-debug.apk.zip`), then open `app-debug.apk` to install — allow
   "install unknown apps" for whichever app you use to open it.

Either way, also install Monoposto (`com.gabama.monopostolite`) on the same
phone if it isn't already there.

## First run on the phone

1. Open **Monoposto VR Mirror**.
2. Tap **Grant Overlay Permission** → allow "display over other apps" in the
   system screen that opens → back out to the app.
3. Adjust sliders if you want (you can also change them later, before
   starting VR — see "Known limitation" below).
4. Tap **Start VR**. Android will show the standard "Start recording or
   casting?" system dialog for MediaProjection — accept it.
5. Monoposto launches automatically. Put the phone in the headset.
6. Pair/use the Vivo Y51 as a Bluetooth controller as you already do — that
   input path is untouched by this app.
7. To exit, take the phone out, pull down the notification shade, tap
   **STOP** on the "Monoposto VR Mirror running" notification (or reopen the
   app and tap Stop VR).

## How the recursion problem is actually solved

The overlay window that renders the SBS view is created with
`WindowManager.LayoutParams.FLAG_SECURE`. Android's compositor (SurfaceFlinger)
excludes `FLAG_SECURE` window content from any screen capture path — the same
mechanism Netflix/DRM apps rely on to blank themselves out of screenshots and
screen recordings. `MediaProjection` capture goes through that same path, so
the overlay should not appear inside its own captured frames.

**This is documented Android platform behavior, not a guess — but I have not
run it on your specific device/ROM, and OEM Android skins have occasionally
shipped bugs in secure-surface handling.** You must verify it, not assume it:

### Required on-device test (do this before trusting the app)
1. Start VR mode with Monoposto running.
2. Look carefully at both halves of the SBS image.
3. **Pass**: you see only Monoposto's normal gameplay UI, duplicated left and
   right.
4. **Fail**: you see the SBS overlay nested inside itself (a picture-in-picture
   recursion, usually degrading toward a checkerboard/strobing pattern within
   a second or two).
5. If it fails: `FLAG_SECURE` is not being honored for overlay windows on that
   Samsung One UI build. There is no reliable app-level fallback for true
   live same-device mirroring in that case — the only technically valid paths
   left are (a) a second physical display device, or (b) an accessibility-
   service-based UI mirroring approach that redraws Monoposto's layout instead
   of capturing pixels, which does not work for a game rendered via GL/Vulkan
   like Monoposto. I'm telling you this now so you don't waste time chasing a
   fix that doesn't exist on a device where the underlying platform behavior
   doesn't cooperate.

## Performance notes

- Capture path is 100% GPU: `MediaProjection → VirtualDisplay → SurfaceTexture
  (GL_TEXTURE_EXTERNAL_OES) → GLSurfaceView`. No `ImageReader`, no `Bitmap`, no
  per-frame CPU buffer copies.
- `GLSurfaceView.RENDERMODE_WHEN_DIRTY` + `SurfaceTexture.OnFrameAvailableListener`
  means we only redraw when Monoposto actually produces a new frame — no
  wasted redraws burning the M30s's battery/GPU.
- If you see frame drops, the two most likely causes are (a) driver overhead
  from `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` at full native 1080×2340 — try
  lowering the VirtualDisplay's width/height to something like 960×2080 in
  `CaptureService.addOverlayAndRenderer()` — or (b) Monoposto itself capping
  its own frame rate.

## Known limitation: settings only apply live, no restart-needed change

Sliders write straight to `SharedPreferences` and the renderer reads them
every frame via a provider lambda, so changes while VR is already running
take effect immediately with no extra plumbing needed — I mention this only
so you don't assume you have to stop/restart VR after nudging a slider.

## What was deliberately left out (per your spec)

No `INTERNET` permission, no video recording, no dependency on App2VR/VirtualApp/
Trinus/VRidge, no second phone in the render path, no root, no bitmap-copy
capture path. Minimum SDK 30 (Android 11) confirmed compatible for every API
used: `MediaProjection`, `VirtualDisplay`, `SurfaceTexture`,
`GL_TEXTURE_EXTERNAL_OES`, `TYPE_APPLICATION_OVERLAY`, and foreground services
have all existed since well before API 30. `FOREGROUND_SERVICE_MEDIA_PROJECTION`
and `foregroundServiceType="mediaProjection"` are declared for forward
compatibility with Android 14+, since `targetSdk` is set to 34.

## File map

- `MainActivity.kt` — permission flow, settings sliders, launches Monoposto.
- `CaptureService.kt` — foreground service; owns MediaProjection, the overlay
  window, and the VirtualDisplay.
- `VrRenderer.kt` — the actual OpenGL: one external OES texture, drawn twice
  (left/right quads) with zoom/offset/eye-separation/barrel-distortion applied
  per eye in the fragment/vertex shaders.
- `Constants.kt` — shared constants and a small `VrSettings` SharedPreferences
  wrapper used by both the Activity and the Service.
