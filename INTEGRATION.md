# INTEGRATION.md — Lovable ↔ Android TV

Everything the Lovable developer needs. The web app stays in charge of the playlist, the
ordering, the UI and the business logic. The Android shell only answers one question —
*"do you have a local copy of this video?"* — and plays it natively when the answer is yes.

**Nothing in this document is mandatory.** If the web app ignores the bridge entirely, the TV
still works: it just streams everything online exactly as it does in a desktop browser.

---

## 1. Detecting the Android TV shell

The shell installs `window.NogaAndroidTV` **before any page script runs** on WebViews that
support document-start injection, and again at `onPageStarted` / `onPageFinished` as a
fallback. On a slow first paint the object may therefore appear a few milliseconds late, so
either check it lazily (at the moment you need it) or wait for the ready event.

```ts
// Lazy check — the safest pattern, and the one used in every example below.
const isAndroidTV = (): boolean =>
  typeof window !== "undefined" && !!window.NogaAndroidTV?.isAndroidTV();
```

```ts
// Or wait for the bridge if you need it during app bootstrap.
window.addEventListener("nogamt-bridge-ready", (e) => {
  const { version, platform } = (e as CustomEvent).detail;
  console.log("NOGA MT Android shell", version, platform);
});
```

The WebView user agent also carries `NogaMTShowroom/1.0.0 (AndroidTV)` if you prefer to branch
on that for analytics.

---

## 2. TypeScript declarations

Drop this in `src/types/noga-android.d.ts` (or any `.d.ts` in the project).

```ts
export interface NogaLocalVideo {
  /** The video id the playlist uses, e.g. "N-mTagRbXuM". */
  id: string;
  /** File name on the TV/USB drive. Diagnostics only — never a path. */
  fileName: string;
  sizeBytes: number;
  lastModified: number;
  matchType: "BRACKET_ID" | "SUFFIX_ID" | "FILENAME_ID" | "UNMATCHED";
  /** False when the drive was pulled — treat as "not available", fall back online. */
  available?: boolean;
}

export interface NogaMediaDiagnostics {
  sourceLabel: string;
  sourceAvailable: boolean;
  scanning: boolean;
  lastScanAt: number;
  filesDiscovered: number;
  matched: number;
  unmatched: number;
  duplicates: number;
  storageUsedBytes: number;
  missing: string[];
  unmatchedFiles: { fileName: string; sizeBytes: number }[];
  duplicateIds: { id: string; kept: string; ignored: string }[];
  appVersion: string;
}

export interface NogaAndroidTVBridge {
  readonly platform: "android-tv";
  isAndroidTV(): boolean;
  getAppVersion(): string | null;
  hasLocalVideo(videoId: string): boolean;
  getLocalVideoInfo(videoId: string): NogaLocalVideo | null;
  listLocalVideos(): NogaLocalVideo[];
  /** Returns false if the id is unknown or the media source is offline. */
  playLocalVideo(videoId: string): boolean;
  stopLocalVideo(): void;
  isLocalVideoPlaying(): boolean;
  getMediaDiagnostics(): NogaMediaDiagnostics | null;
  /** Opens the native staff Local Media screen. */
  openMediaSettings(): void;
  refreshLocalMediaIndex(): void;
  /** Optional: publish playlist ids so the staff screen can list what's missing. */
  reportPlaylist(ids: string[]): void;
  log(message: string): void;
}

declare global {
  interface Window {
    NogaAndroidTV?: NogaAndroidTVBridge;
  }

  interface WindowEventMap {
    "nogamt-bridge-ready": CustomEvent<{ version: string | null; platform: string }>;
    "nogamt-local-video-started": CustomEvent<{ id: string }>;
    "nogamt-local-video-ended": CustomEvent<{ id: string }>;
    "nogamt-local-video-stopped": CustomEvent<{ id: string }>;
    "nogamt-local-video-error": CustomEvent<{ id: string; error: string }>;
  }
}

export {};
```

---

## 3. The core decision — local first, online fallback

This is the whole integration in five lines:

```ts
if (window.NogaAndroidTV && window.NogaAndroidTV.hasLocalVideo(videoId)) {
  window.NogaAndroidTV.playLocalVideo(videoId);
} else {
  playOnlineVideo();
}
```

`hasLocalVideo()` returns **false** when the id is unknown *or* when the USB drive has been
removed, so a single check covers both cases. It is synchronous and cheap (a hash-map lookup),
so it is safe to call inside a render path.

---

## 4. A complete player hook

```ts
import { useCallback, useEffect, useRef } from "react";

type PlayResult = "local" | "online";

/**
 * Plays videoId natively when the TV has a local copy, otherwise falls back to the
 * existing online player. Resolves when the item finishes so the playlist can advance.
 */
export function useShowroomPlayer(playOnline: (id: string) => Promise<void>) {
  const pending = useRef<{ id: string; resolve: (r: PlayResult) => void } | null>(null);

  useEffect(() => {
    const settle = (id: string) => {
      const p = pending.current;
      if (p && p.id === id) {
        pending.current = null;
        p.resolve("local");
      }
    };

    const onEnded = (e: CustomEvent<{ id: string }>) => settle(e.detail.id);
    const onStopped = (e: CustomEvent<{ id: string }>) => settle(e.detail.id);

    const onError = (e: CustomEvent<{ id: string; error: string }>) => {
      const p = pending.current;
      console.warn("Local playback failed", e.detail);
      if (p && p.id === e.detail.id) {
        pending.current = null;
        // Local file is broken or the drive vanished — fall back online.
        playOnline(e.detail.id).then(() => p.resolve("online"));
      }
    };

    window.addEventListener("nogamt-local-video-ended", onEnded as EventListener);
    window.addEventListener("nogamt-local-video-stopped", onStopped as EventListener);
    window.addEventListener("nogamt-local-video-error", onError as EventListener);
    return () => {
      window.removeEventListener("nogamt-local-video-ended", onEnded as EventListener);
      window.removeEventListener("nogamt-local-video-stopped", onStopped as EventListener);
      window.removeEventListener("nogamt-local-video-error", onError as EventListener);
    };
  }, [playOnline]);

  return useCallback(
    (videoId: string): Promise<PlayResult> => {
      const tv = window.NogaAndroidTV;

      if (tv?.hasLocalVideo(videoId) && tv.playLocalVideo(videoId)) {
        return new Promise<PlayResult>((resolve) => {
          pending.current = { id: videoId, resolve };
        });
      }

      return playOnline(videoId).then(() => "online" as const);
    },
    [playOnline],
  );
}
```

**The playlist still belongs to Lovable.** Android never picks the next item — it only tells
you the current one finished. Advance exactly the way you do today.

---

## 5. Events

| Event | Fires when | detail |
|---|---|---|
| `nogamt-bridge-ready` | The facade is installed | `{ version, platform }` |
| `nogamt-local-video-started` | The first frame is ready | `{ id }` |
| `nogamt-local-video-ended` | The file played to its end | `{ id }` |
| `nogamt-local-video-stopped` | Stopped by staff, remote BACK, or `stopLocalVideo()` | `{ id }` |
| `nogamt-local-video-error` | File missing, codec failure, drive removed | `{ id, error }` |

Exactly one of `ended` / `stopped` / `error` fires per playback, so the same handler can
advance the playlist in all three cases.

```ts
window.addEventListener("nogamt-local-video-ended", (e) => {
  console.log("finished", e.detail.id);
  advanceToNextItem();
});
```

---

## 6. Skipping cleanly when offline

Requirement: when the TV has no internet *and* no local copy, skip the item rather than
showing a YouTube error or login page.

```ts
function canPlay(videoId: string): boolean {
  const tv = window.NogaAndroidTV;
  if (tv?.hasLocalVideo(videoId)) return true;   // local copy, internet irrelevant
  return navigator.onLine;                        // otherwise we need the network
}

const queue = playlist.filter((item) => canPlay(item.videoId));
```

`navigator.onLine` is accurate inside the shell — the WebView follows the Android
connectivity state.

---

## 7. Optional: publish the playlist for staff diagnostics

If you call this once whenever the playlist changes, the staff "VIEW MISSING VIDEOS" screen
can list precisely which playlist items have no local copy on this TV.

```ts
window.NogaAndroidTV?.reportPlaylist(playlist.map((i) => i.videoId));
```

Ids are validated and stored for diagnostics only. Without this call the screen still lists
every id the web app *asked* for and did not get, which covers most of the same ground.

---

## 8. File naming — what the TV can match

The video id is taken from the file name on the drive, in this priority order:

| Rule | Example file name | Matched id |
|---|---|---|
| 1. Bracketed id | `NOGA MT – Advanced CNC Deburring & Precision Finishing Solutions. [N-mTagRbXuM].mp4` | `N-mTagRbXuM` |
| 2. Id at the end | `NOGA MT Deburring-N-mTagRbXuM.mp4` | `N-mTagRbXuM` |
| 3. The name *is* the id | `N-mTagRbXuM.mp4` | `N-mTagRbXuM` |
| 4. Exact file-name match | `bear-mascot.mp4` | `bear-mascot` |

Rule 1 wins over the others, so the yt-dlp default naming that the current library uses works
untouched. Files with no recognisable id are still reachable through rule 4, so if your
playlist keys something by file name it resolves too.

If the same id appears twice, the first file wins and the second is reported as a duplicate on
the staff screen — never two entries for one id.

---

## 9. Adding videos later

There is no fixed list of 28 and no hard-coded ids anywhere in the APK. Add the video in the
Lovable library, copy the matching MP4 into `NOGA-MT/videos/` on the TV or USB drive, then
either restart the app or press **RESCAN VIDEOS** in the staff Local Media screen. That is the
whole procedure — no new APK.

---

## 10. Developing without an Android TV

Stub the bridge in your dev build so both paths are exercisable in a browser:

```ts
if (import.meta.env.DEV && !window.NogaAndroidTV && localStorage.getItem("fakeTV")) {
  const local = new Set(["N-mTagRbXuM"]);
  // @ts-expect-error dev-only stub
  window.NogaAndroidTV = {
    platform: "android-tv",
    isAndroidTV: () => true,
    getAppVersion: () => "dev",
    hasLocalVideo: (id: string) => local.has(id),
    getLocalVideoInfo: () => null,
    listLocalVideos: () => [],
    playLocalVideo: (id: string) => {
      setTimeout(
        () => window.dispatchEvent(
          new CustomEvent("nogamt-local-video-ended", { detail: { id } }),
        ),
        3000,
      );
      return true;
    },
    stopLocalVideo: () => {},
    isLocalVideoPlaying: () => false,
    getMediaDiagnostics: () => null,
    openMediaSettings: () => {},
    refreshLocalMediaIndex: () => {},
    reportPlaylist: () => {},
    log: console.log,
  };
}
```

On a real TV, `chrome://inspect` from a desktop Chrome on the same network gives you full
DevTools against the showroom WebView (`adb connect <tv-ip>:5555` first).

---

## 11. Security boundary — what the bridge will not do

- No method takes or returns a filesystem path, a `content://` URI or a `file://` URL.
- Ids are validated against `^[A-Za-z0-9 ._-]{1,160}$`; traversal attempts such as
  `../../etc/passwd` are rejected before any lookup.
- Only files discovered by scanning the staff-selected folder are reachable — nothing else on
  the device is addressable, whatever the web app sends.
- The bridge cannot run shell commands, install anything or read arbitrary storage.
- Navigation is restricted to `noga-exhibit-buddy.lovable.app`, `lovable.app`, `noga.com` and
  `noga-mt.com`; cleartext HTTP and other URL schemes are blocked, and certificates are always
  validated.

---

## 12. Checklist for the Lovable side

Minimum, if you want local playback:

- [ ] Add the `.d.ts` from section 2.
- [ ] Wrap the TV player's "play this item" call in the section 3 check.
- [ ] Advance the playlist on `nogamt-local-video-ended` / `-stopped` / `-error`.

Recommended:

- [ ] Filter unplayable items when offline (section 6).
- [ ] Call `reportPlaylist()` when the playlist changes (section 7).
- [ ] Add a discreet staff link to `openMediaSettings()` somewhere in the TV admin UI.
