# NOGA MT Showroom — Android TV

A kiosk shell for the exhibition TV. It opens the live Lovable web app at
`https://noga-exhibit-buddy.lovable.app/tv` full-screen, plays local MP4 files natively when
they exist, survives an unattended 12-hour exhibition day, and stays out of the way.

**Package:** `com.nogamt.showroom` · **Version:** 1.1.0 (versionCode 2) · **minSdk:** 26 (Android 8) ·
**compileSdk/targetSdk:** 34

---

## The division of labour

| Lovable web app | Android APK |
|---|---|
| Playlist, ordering, enabled/disabled videos | Full-screen kiosk window |
| Products, product pages, branding, UI | TV remote handling |
| Screensaver / attract mode | Local media index + native playback |
| QR / NFC sharing | Crash, network and renderer recovery |
| Everything a visitor sees | Boot behaviour, staff tooling |

Publish in Lovable → the TV picks the change up on the next load. **A new APK is only needed
for native changes.** There is no second playlist anywhere in this project, no hard-coded video
ids and no 28-video limit.

The ~620 MB video library is **not** in the APK. The APK is a shell of a few MB.

**New in 1.1 — the library maintains itself.** The TV reads a media manifest published by NOGA
MT, downloads whatever is missing or changed, verifies it and indexes it, all in the background
while the showroom keeps running. Adding or replacing a video no longer needs a USB stick, an
Android Studio session or a new APK.

| Change | Needs a new APK? |
|---|---|
| Text, design, playlist, screen order, products, case studies | No — Lovable publish |
| New video added to the library | No — media manifest |
| An existing MP4 replaced | No — media manifest |
| Native Android behaviour (kiosk, player, storage, bridge) | Yes |

---

## Project layout

```
noga-mt-showroom/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nogamt/showroom/
│       │   ├── MainActivity.kt          kiosk shell, remote, recovery, staff menu
│       │   ├── ShowroomApp.kt           app entry, restores the media index
│       │   ├── Constants.kt             every tunable value
│       │   ├── Prefs.kt                 settings storage
│       │   ├── bridge/
│       │   │   ├── NogaBridge.kt        the @JavascriptInterface surface
│       │   │   ├── BridgeScript.kt      the window.NogaAndroidTV facade (JS)
│       │   │   └── BridgeHost.kt        what JS may ask the Activity to do
│       │   ├── media/
│       │   │   ├── MediaIndex.kt        in-memory index (NOT a playlist)
│       │   │   ├── MediaScanner.kt      SAF directory walk
│       │   │   ├── MediaRepository.kt   permissions + background scanning
│       │   │   ├── VideoIdMatcher.kt    file name → video id rules
│       │   │   ├── LocalPlayerController.kt  Media3 / ExoPlayer lifecycle
│       │   │   └── LocalVideo.kt
│       │   ├── web/
│       │   │   ├── WebViewFactory.kt    WebView configuration
│       │   │   ├── ShowroomWebViewClient.kt   navigation policy + errors
│       │   │   └── ShowroomWebChromeClient.kt HTML5 fullscreen video
│       │   ├── net/NetworkMonitor.kt
│       │   ├── boot/BootReceiver.kt
│       │   └── staff/
│       │       ├── StaffSettingsActivity.kt
│       │       └── MediaManagerActivity.kt
│       └── res/                         TV banner, icons, dark/orange staff UI
├── docs/KIOSK.md                        optional device-owner provisioning
├── INTEGRATION.md                       the contract for the Lovable developer
├── build.sh
├── .github/workflows/android.yml        builds the APK in CI, no local SDK needed
└── gradlew, gradle/wrapper/             Gradle 8.7 wrapper (official)
```

---

## Building

### Android Studio (simplest)

Open the `noga-mt-showroom` folder, let it sync (it downloads AGP 8.5.2, Kotlin 1.9.24 and the
SDK components), then **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Command line

```bash
cd noga-mt-showroom
export ANDROID_HOME=/path/to/android/sdk      # or run Android Studio once
./gradlew assembleDebug
./gradlew assembleRelease
```

or just `./build.sh`.

### GitHub Actions (no local SDK at all)

Push this folder to a repository. `.github/workflows/android.yml` builds both APKs on every
push and attaches them as a downloadable artifact called `noga-mt-showroom-apks`.

### APK output paths

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Use the **debug** APK for the exhibition unless you have a reason not to — it installs directly
and is already signed with the local debug key.

### Signing a release build

```bash
keytool -genkey -v -keystore noga-mt.jks -keyalg RSA -keysize 2048 -validity 10000 -alias nogamt
```

Then add to `app/build.gradle.kts` inside `android { }`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../noga-mt.jks")
        storePassword = System.getenv("NOGA_STORE_PASSWORD")
        keyAlias = "nogamt"
        keyPassword = System.getenv("NOGA_KEY_PASSWORD")
    }
}
```

and `signingConfig = signingConfigs.getByName("release")` inside `buildTypes { release { … } }`.

---

## Installing on the TV

### Over ADB (recommended)

```bash
# TV: Settings → Device Preferences → About → click "Build" 7 times → Developer options
#     → USB debugging ON, and note the TV's IP address.
adb connect 192.168.1.42:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.nogamt.showroom -c android.intent.category.LEANBACK_LAUNCHER 1
```

Reinstall over an existing copy with `-r`. Logs: `adb logcat -s NogaMT`.

### From a USB stick

1. Copy `app-debug.apk` to the root of a FAT32/exFAT USB stick.
2. On the TV install a file manager + sideload permission — *X-plore File Manager* or
   *File Commander* from the Play Store both work.
3. Plug the stick in, open the file manager, select the APK, allow "install unknown apps" for
   the file manager when prompted, install.
4. **NOGA MT Showroom** now appears in the TV Apps row with its banner.

Some Google TV models hide sideloaded apps: the app is on the device, it just needs
*Apps → See all apps* once, or add it to favourites.

---

## Local video library

### Folder layout on the drive

```
NOGA-MT/
└── videos/
    ├── NOGA MT – Advanced CNC Deburring… [N-mTagRbXuM].mp4
    ├── … more .mp4 / .webm / .m4v
```

Sub-folders are scanned too (up to 6 levels), so grouping by UBACK / UBURR / UFIBER / GENERAL
is fine. The bear/mascot video is treated like any other file — nothing is filtered by product
family.

### Naming

The id is read from the file name: bracketed `[N-mTagRbXuM]` first, then a trailing
`-N-mTagRbXuM`, then a name that is itself an id, then rule 4 — a slug-like name with no spaces
becomes its own id, so `bear-mascot.mp4` → `bear-mascot`. The default
yt-dlp naming already used by the current library works as-is. Full rules and examples are in
**INTEGRATION.md §8**.

### First run — PREPARE OFFLINE MEDIA

A few seconds after the first launch the TV offers three choices. This is the only setup step.

| Choice | What it does |
|---|---|
| **INTERNAL STORAGE** | Keeps the library in the app's own folder. Nothing to plug in, no permission prompts. Removed if the app is uninstalled. |
| **USB / EXTERNAL STORAGE** | Opens the Android folder picker so staff can select `NOGA-MT/videos` on a USB drive or SD card. |
| **SKIP FOR NOW** | The showroom runs online only. Offline media can be set up later from the staff menu. |

Skipping never blocks anything, and BACK on that screen behaves like skip.

### Internal storage setup

Choose **INTERNAL STORAGE**. The app creates `NOGA-MT/videos` inside its own external-files
directory and starts downloading the library from the manifest in the background. No permission
dialog appears, because an app never needs one for its own directory.

### USB setup and choosing NOGA-MT/videos

1. Prepare the drive with a `NOGA-MT/videos` folder (any existing MP4s can already be in it).
2. Insert it into the TV **before** starting the setup.
3. First run → **USB / EXTERNAL STORAGE**, or later: hold **BACK** 3 s → **Local media
   manager** → **CHANGE STORAGE** → **USB / external folder…**.
4. In the Android picker open the drive, open `NOGA-MT/videos`, press **USE THIS FOLDER** →
   **ALLOW**. Selecting the parent `NOGA-MT` also works, sub-folders are scanned.
5. The scan runs immediately and the counters fill in.

The permission is persisted, so it survives reboots and power cuts — **staff never repeat this
step**. Local Media shows `SAF PERMISSION · VALID` when it is still held.

### Changing storage later

**Local media manager → CHANGE STORAGE**, pick internal or a folder, then choose:

- **USE NEW LOCATION** — start using the new place; old files stay where they are.
- **COPY EXISTING MEDIA** — copies the current library across first (progress is shown).
- **START FRESH** — clears the app's index only. **No media file is ever deleted.**

### How automatic synchronization works

The app reads the NOGA MT media manifest from

```
https://noga-exhibit-buddy.lovable.app/api/public/android-media-manifest
```

which is **built into the app** — staff never enter a URL, and a freshly installed TV starts
syncing on its own. It compares that manifest against its local index and downloads only the
difference.
It checks on: app start, app resume, network return, USB return, every 30 minutes while running,
and immediately on **SYNC NOW**. A pass never blocks the showroom and never interrupts playback.

Each video ends up in exactly one state, visible in Local Media:

| State | Meaning |
|---|---|
| `LOCAL_READY` | Verified local copy, played natively |
| `MISSING` | In the library, not downloaded yet |
| `DOWNLOADING` | Transfer in progress |
| `UPDATE_AVAILABLE` | The MP4 changed remotely; replacement queued |
| `FAILED` | Three attempts failed — see VIEW FAILED DOWNLOADS |
| `ONLINE_ONLY` | No direct download URL (YouTube/Vimeo entries land here) |
| `UNUSED` | On the drive but no longer in the library — kept, never auto-deleted |

Settings (staff settings screen): **Auto-sync new videos** (default ON), **Sync on Wi-Fi /
Ethernet only** (default OFF), **Verify downloaded files** (default ON).

### Adding a video later

1. Add it in the Lovable library as usual.
2. Upload the MP4 to NOGA-controlled storage (Supabase, R2, NOGA CDN — never YouTube).
3. Add its entry to the manifest served by
   `/api/public/android-media-manifest` and bump `libraryVersion`. Publish.

The TV downloads it on its next check, verifies it, indexes it, and `hasLocalVideo(id)` starts
returning true. To see it happen now, press **SYNC NOW**. No APK, no USB, no restart.

Hand-copying an MP4 into `NOGA-MT/videos` and pressing **RESCAN VIDEOS** still works and is
never undone: a file whose size matches the manifest is adopted, not re-downloaded.

### Replacing an existing video

Upload the new MP4, bump that entry's `version` (and `fileSize`/`sha256`), bump
`libraryVersion`, publish. The TV downloads the replacement to a `.part` file, checks its size
and SHA-256, and only then swaps it in atomically. **The old file stays playable the whole
time**, and a file currently being played is never replaced underneath the player — the swap
waits for the next pass.

### Running SYNC NOW

Hold **BACK** 3 s → **Local media manager** → **SYNC NOW**. While it runs the screen shows
`SYNCING MEDIA · video 3 / 5 · 240 MB / 410 MB`. Visitors see nothing.

### TEST LOCAL VIDEO

**Local media manager → TEST LOCAL VIDEO** plays the first indexed file through Media3 only —
no WebView, no Lovable, no YouTube, no browser storage. If it plays, then storage, SAF
permission, the index, the player and the display are all proven good and any remaining problem
is on the web side.

### TEST VIDEO BY ID

**Local media manager → TEST VIDEO BY ID** → type an id such as `N-mTagRbXuM`. It reports
FOUND/NOT FOUND with the file name, size, match type, state, availability and storage source,
and offers **PLAY TEST**.

### Diagnosing missing media

`MISSING > 0` means the library lists something this TV has not downloaded yet. Press **SYNC
NOW**, then **VIEW MISSING VIDEOS** for the ids and their states. If they stay missing, check
**VIEW FAILED DOWNLOADS** for the host, HTTP status and error.

### CLEAN UNUSED MEDIA

When a video is removed from the library its file is kept and marked `UNUSED` — nothing is ever
deleted behind staff's back. **Local media manager → CLEAN UNUSED MEDIA** shows
`FILES TO DELETE` and `SPACE TO RECOVER` and requires confirmation. A playing file, an active
download and a pending replacement are never deleted.

### Low storage

Before downloading, the app adds up what it needs and compares it with the free space. If there
is not enough it **stops and warns staff** — it never deletes working media to make room. The
warning appears only in the staff area, never over the showroom.

### USB removal

Pulling the drive does not crash anything. The source is marked unavailable, `hasLocalVideo()`
starts returning false and the web app falls back to online playback. Re-insert it: the app
revalidates the persisted permission, rescans, resumes pending downloads and restores local
playback — no TV reboot, no folder re-selection.

---

## Operating the TV

### Staff menu — hold BACK for 3 seconds

- **Return to showroom**
- **Reload web app** — safe reload, deferred if a local video is playing
- **Settings** — the staff settings screen, including sync settings and TV media health
- **Local media manager** — counters, SYNC NOW, storage, tests, cleanup
- **Exit application** — asks for confirmation first

A short BACK press never exits: it closes HTML5 fullscreen, stops a local video, or navigates
web history. Visitors cannot reach the Android launcher by pressing BACK.

### Staff settings

Read-out: **TV MEDIA HEALTH** (bridge, storage, sync, local videos, overall verdict), start URL,
network state, Android System WebView version, app version, device model, selected media folder,
SAF permission, storage used/free, last scan, manifest URL, library version and last sync.

Toggles: **Keep screen awake** · **Auto launch on TV boot** · **Automatic recovery** ·
**Auto-sync new videos** · **Sync on Wi-Fi / Ethernet only** · **Verify downloaded files**.

Actions: Sync Now · Reload Web App · Force Web Refresh · Change Storage · Rescan Media ·
Local Media · Clear Web Cache · Change Start URL · Change Manifest URL · Open Android Network
Settings · Return To Showroom.

Both the start URL and the manifest URL only accept HTTPS on the allow-listed hosts.

### Auto-start after a TV power cycle

Staff settings → **Auto launch on TV boot** → ON. See the manufacturer caveats below; for a
guaranteed start on dedicated exhibition hardware, use `docs/KIOSK.md`.

### Recovery, unattended

Renderer crashes rebuild the WebView. Failed loads show the branded
**NOGA MT / SHOWROOM TEMPORARILY OFFLINE / Reconnecting…** screen and retry with exponential
back-off (3s → 60s), and retry immediately when the network returns. A single failed request
never replaces a working UI — the reconnect screen appears only when the app genuinely has no
usable page. Reloads never interrupt a playing local video; they wait for it to finish.

---

## Manufacturer limitations, honestly

- **Auto-start on boot is best-effort.** `RECEIVE_BOOT_COMPLETED` works on most Android TV
  boxes, Nvidia Shield, Xiaomi/Mi Box, TCL and generic Chinese signage firmware. Google TV
  (Chromecast HD/4K), several Sony and Philips models, and Amazon Fire TV commonly suppress an
  app opening itself at boot. Nothing crashes when it is blocked; it is logged. Guaranteed
  start needs device-owner provisioning (`docs/KIOSK.md`).
- **Sideloaded apps can be hidden** in the Google TV launcher (see the install section).
- **USB behaviour varies.** Most TVs expose USB drives through the Storage Access Framework
  picker; a few budget models expose no document provider at all, in which case copy the videos
  to internal storage and select that folder instead. The app never assumes a fixed USB path.
- **Android System WebView version matters.** The app needs the WebView that ships with the TV;
  very old ones (Android 8 devices never updated) may lack newer web APIs the Lovable app uses.
  The installed version is shown in staff settings — update it from the Play Store if the web
  app misbehaves.
- **Immersive mode is a request, not a guarantee.** Some OEM launchers redraw a top bar
  briefly; the app re-asserts immersive mode whenever it regains focus.
- **4K TVs** render the WebView at the platform's TV density. Layouts are TV-safe at both
  1920×1080 and 3840×2160, with a 5% overscan margin on the native screens.

---

## Expected diagnostics on a healthy TV

Staff menu → **Local media manager**, on a correctly configured exhibition TV:

```
MEDIA SOURCE           USB DRIVE  (or Internal storage · NOGA-MT/videos)
SAF PERMISSION         VALID      (N/A on internal storage)
SOURCE AVAILABLE       YES

REMOTE LIBRARY         31
FILES DISCOVERED       31
MATCHED                31
LOCAL READY            31

MISSING                 0
UPDATE AVAILABLE        0
DOWNLOADING             0
FAILED                  0
ONLINE ONLY             0
UNUSED                  0
UNMATCHED               0
DUPLICATES              0

STORAGE USED           690 MB
STORAGE FREE           18.6 GB

LAST SCAN              2026-09-05 20:41
LAST SYNC              2026-09-05 20:42
SYNC STATUS            UP TO DATE

HEALTH: GOOD
```

And in staff settings:

```
TV MEDIA HEALTH
BRIDGE          READY
STORAGE         READY
SYNC            UP TO DATE
LOCAL VIDEOS    31 / 31
HEALTH          GOOD
```

`ONLINE ONLY` is legitimately non-zero if some library entries are deliberately stream-only.

---

## Troubleshooting

| Reading | Likely cause | First action |
|---|---|---|
| **MATCHED = 0** | Wrong folder selected (a parent, or the drive root); file names carry no video id; the scan has not finished; permission lost | **RESCAN VIDEOS**. Still 0 → **CHANGE STORAGE** and reselect `NOGA-MT/videos`, then **VIEW UNMATCHED FILES** to see whether the names carry ids |
| **SAF PERMISSION = INVALID** | The drive was reformatted/replaced, or Android dropped the grant | **CHANGE STORAGE → USB / external folder…** and pick the folder again. No data is lost |
| **SOURCE AVAILABLE = NO** | USB unplugged or unmounted, storage not readable | Re-seat the drive, then **RESCAN VIDEOS**. Playback keeps working online meanwhile |
| **ANDROID BRIDGE = NOT READY** | The page has not finished loading, or the web app loaded before the bridge was injected | Staff settings → **Reload Web App**. If it persists, check `window.NogaAndroidTV` in DevTools and INTEGRATION.md §1 |
| **MISSING > 0** | The library lists media this TV has not downloaded yet | **SYNC NOW**, then **VIEW MISSING VIDEOS** |
| **FAILED > 0** | Network, storage, HTTP or checksum failure | **VIEW FAILED DOWNLOADS** — it shows host, HTTP status, bytes, retries and error → **RETRY FAILED DOWNLOADS** |
| **DUPLICATES > 0** | Two files on the drive resolve to the same video id | **VIEW DUPLICATES** — it names the file kept and the one ignored. Remove or rename the loser |
| **UNMATCHED > 0** | A file name maps to no stable video id (usually spaces in the name) | **VIEW UNMATCHED FILES** and rename to `<something>-<id>.mp4`, `[id].mp4` or a spaceless slug |
| **UPDATE AVAILABLE stuck > 0** | The replacement is waiting for the currently playing file, or downloads keep failing | Wait one playback cycle, then **SYNC NOW**; check VIEW FAILED |
| **LOW STORAGE warning** | Not enough free space for the queued downloads | Free space or move to a larger drive; the app will not delete media itself |
| Anything at all | — | `adb logcat -s NogaMT` logs every scan, sync, download, bridge call and recovery event |

Remote debugging: `adb connect <tv-ip>:5555`, then `chrome://inspect` on a desktop.

---

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`. That is all. Media access goes
through the Storage Access Framework, so there is no `READ_EXTERNAL_STORAGE` and no
`MANAGE_EXTERNAL_STORAGE`.

---

## Acceptance tests

| # | Test | Where it is handled |
|---|---|---|
| 1 | Appears in TV Apps | `LEANBACK_LAUNCHER` intent filter + `@drawable/banner` |
| 2 | Opens `/tv` immediately | `MainActivity.loadStartUrl` |
| 3 | No browser chrome | `WebView` in a no-action-bar immersive Activity |
| 4 | Remote works | `dispatchKeyEvent`, focusable WebView, DPAD-navigable staff UI |
| 5 | Video plays | Media3 locally, WebView `WebChromeClient` fullscreen online |
| 6 | Keep awake | `FLAG_KEEP_SCREEN_ON`, toggleable |
| 7 | Long BACK → staff menu | `Constants.LONG_BACK_MS` = 3000 |
| 8 | Select USB folder | SAF `OpenDocumentTree` + persisted permission |
| 9 | Index discovers MP4s | `MediaScanner` |
| 10 | First-run offline media setup | `FirstRunSetupActivity` |
| 11 | New video downloads by itself | `SyncEngine` + `MediaStateResolver` |
| 12 | Replacement never destroys the working copy | `.part` → verify → atomic promote |
| 13 | No YouTube/Vimeo downloads | `RemoteVideo.downloadable` |
| 14 | One terminal playback event | `LocalPlayerController.terminalEmitted` |
| 15 | USB removal degrades safely | `verifySourceAvailability` + `MediaIndex.isPlayable` |
| 16 | Native playback provable on its own | `MediaTestActivity` |
| 10 | `[N-mTagRbXuM]` → `N-mTagRbXuM` | `VideoIdMatcher` — **verified by an executed test** |
| 11 | Local playback on request | `NogaBridge.playLocalVideo` → `LocalPlayerController` |
| 12 | Ended event reaches the web app | `nogamt-local-video-ended` |
| 13 | Online fallback | `hasLocalVideo()` false → web app's normal path |
| 14 | Offline: no crash, local still plays | Requirement 20 handling in `onMainFrameFailure` |
| 15 | Reconnect recovers | `NetworkMonitor` + back-off retry |
| 16 | New Lovable version, no APK | Live URL, no bundled copy |
| 17 | Auto-start | `BootReceiver` (+ caveats above) |
| 18 | USB hot-plug | `MediaRepository.verifySourceAvailability` |
| 19 | No videos in the APK | Nothing under `app/src/main/assets` or `res/raw` |
| 20 | Clean compile | Run one of the build paths above |

Tests 1–9 and 11–19 are behavioural and need the APK on a TV. See "What was verified" below
for what was actually executed while the project was built.

---

## What was verified when this project was generated

The build environment had a JDK but **no Android SDK**, and `dl.google.com`, `maven.google.com`,
`repo1.maven.org` and `services.gradle.org` were blocked by network policy, so
`./gradlew assembleDebug` could not run there. **No APK was produced in that environment** — the
first real compile happens on your machine or in GitHub Actions. What *was* executed:

- **Kotlin 1.9.24 over all source files** — zero syntax or structural errors. Full semantic
  checking needs `android.jar`, so treat CI as the first true compile.
- **92 behavioural tests run on the JVM against the real source files** (`MediaScanner`,
  `MediaStateResolver`, `MediaIndex`, `RemoteManifest`, `VideoIdMatcher`, `Prefs`,
  `SyncStatus`), compiled against minimal Android stubs. They cover: all four id-matching rules,
  duplicate resolution, unmatched reporting, manifest parsing, the YouTube/Vimeo download ban,
  download file-name safety, all seven media states, the USB-removed path, hand-copied files not
  being re-downloaded, index persistence and restore, and the guarantee that diagnostics and
  failure records leak no paths, URLs or signed tokens.
- **The injected bridge JavaScript** — `node --check` plus a functional test of the facade,
  the ready event, all 13 methods, double-injection safety and exception containment.
- **All 14 XML resources parse**, and every `@string/@color/@style/@drawable/@id` reference from
  both XML and Kotlin resolves.
- **The GitHub Actions workflow** still builds `assembleDebug` + `assembleRelease` and uploads
  the `noga-mt-showroom-apks` artifact.
