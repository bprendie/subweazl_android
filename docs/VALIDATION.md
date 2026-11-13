# Validation

Last validated: November 1, 2026, version 1.3.1 (version code 9).

## Automated checks

The following passed locally before the initial public GitHub import:

```sh
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 scripts/test-emulator.sh
```

The unit suite contains 29 tests covering the API contract, voice matching,
related queues, authentication/origin rules, and playback error reporting.
The isolated WeazlMusic fixture suite covers library synchronization, playlist
editing and duplicate occurrences, favorites, downloads and offline playback,
radio, recording timelines, Compose navigation, cold process restoration,
voice commands and Android Auto's legacy media-session requests. Gateway tests
verify that a radio fallback strips account/proxy headers and the relay lease,
that authentication failures do not trigger fallback, and that direct failures
remain actionable HTTP errors. Evidence is generated under ignored `build/evidence`.

A separate actual Navidrome 0.64.2 instance with synthetic music passed library,
playlist, favorite, artwork, streaming, seeking, offline and voice checks.
Run `scripts/test-navidrome.sh` with the isolated setup below. It refuses
physical-device serials. Do not run mutation tests against a personal server.

## Physical device evidence

The optimized development-signed 1.3.1 APK installed and launched on a Pixel.
A live MP3 station initially failed through the WeazlMusic relay with HTTP 502;
the HTTPS fallback then reached `isPlaying=true`, and the tester confirmed audio.
This verifies phone playback; it does not identify the relay's infrastructure
failure. A configured HTTPS source is tried once on 502/503/504, with no account
credentials or redirects. The backend was not changed.

An earlier standard Android Auto media build was confirmed working in a real car.
The latest single-song continuation behavior passed service/legacy controller
checks, including selecting the requested song first and skipping to the next
track, but awaits a new physical head-unit acceptance test. The custom Car App
Library UI is experimental and disabled by default. Emulator/DHU checks do not
establish audible hardware or real head-unit acceptance.

## Isolated test servers

For the WeazlMusic fixture, clone the public backend into a sibling folder:

```sh
git clone https://github.com/bprendie/weazlmusic.git ../web_weazltunes
cd ../web_weazltunes
go run ./cmd/weazlfixture
```

The fixture binds to loopback port 4003 and supplies disposable accounts and
synthetic audio. Start one emulator and run this repository's
`scripts/test-emulator.sh` with its `ANDROID_SERIAL`. No backend is needed for
unit tests, lint, or APK builds.

For direct Navidrome compatibility:

1. Download the official Navidrome 0.64.2 binary outside source control.
2. Create separate data/music directories under ignored `build/navidrome-fixture`.
3. Generate a 30-second MP3 titled `Fixture Song`, artist `Fixture Artist`, album
   `Fixture Album`, and add a generated `cover.jpg` to the music folder.
4. Start Navidrome with `ND_ADDRESS=127.0.0.1`, `ND_PORT=4537`, `ND_DATAFOLDER`
   and `ND_MUSICFOLDER` pointing to those isolated directories; wait for the scan.
5. Create the disposable admin `fixture` / `fixture-only-password` through its
   local setup page, then run `scripts/test-navidrome.sh`.

## Remaining acceptance and limitations

- Retest 40-track continuation in a real car, including voice, skip, reconnect,
  background playback and the head unit's browse/queue presentation.
- Exercise long screen-off playback, calls/audio focus, Bluetooth/headphones,
  Wi-Fi/cellular handover and battery restrictions on representative devices.
- Exercise long/recurring recordings, daylight-saving review, gaps, storage
  pressure, accessibility and minimum Android hardware/codecs.
- Direct Navidrome uses original audio and local history without server scrobbling.
  Its playlist conflict check is best-effort read-before-write, not atomic.
- Enhanced radio/SiriusXM, Flight Recorder and Mood require WeazlMusic.
- Google assistant app-name recognition/provider routing depends on Google;
  in-app speech and delivered media-session requests use the local resolver.

The GitHub preview uses a development certificate. Google Play uses a different
app-signing certificate, so it cannot replace this install in place. Changing
between those distributions may require uninstalling and removes local app data.
Public Google Play production availability is not established by these checks.

## Fire TV build

Validated November 1, 2026: `1.3.1-tv` (`io.prendie.subweazl.tv`, version code 9).
The phone and TV packages can coexist; their artwork provider authorities and
account/download storage are separate. The TV uses the same PlaybackService.

- TV debug and optimized release builds, the 29 unit tests and lint passed with
  `-PfireTv=true`. `scripts/package-firetv.sh` produced and verified a development-
  signed optimized APK, about 8 MB, under ignored `build/outputs`.
- `TvNavigationTest` passed on a landscape emulator with the isolated fixture:
  D-pad navigation between the rail and content, search, radio, Settings/Back,
  player Select-to-pause, and playback remaining ready after Home.
- After physical feedback about the credential keyboard, `TvCredentialFormTest`
  also passed: D-pad navigation skips blank fields without starting text input,
  optional settings expand explicitly, Save/Done returns focus to the edited row,
  and Cancel discards only the local draft. TV fields now open a single-field
  editor only on Select; switches are one full-width remote focus target.
- The ordinary phone debug/release builds, unit tests, lint and full
  `scripts/test-emulator.sh` suite passed after the TV changes. This includes
  integration/UI, cold-process restoration, voice and radio gateway checks.
- The optimized TV APK installed and launched on `AFTDCT31` (32-bit ARM),
  Fire OS 7.7.1.7 / Android 9 / API 28, at `10.0.0.51`. The original Subweazl
  icon and branded TV banner are included; the sign-in form renders and opens
  Fire TV's remote keyboard. OmarchyTV's dock configuration places Subweazl
  third after YouTube and Jellyfin.

Evidence is in ignored `build/evidence/tv`, with build/test logs under `build`.
Physical-device checks establish installation, rendering and keyboard access;
server sign-in, audible playback and extended remote use still require user
acceptance. No personal server credentials or libraries were embedded or copied.

## References

- [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content)
- [Android Auto media declaration](https://developer.android.com/training/cars/media/auto)
- [Car testing](https://developer.android.com/training/cars/testing)
- [Navidrome supported API](https://www.navidrome.org/docs/developers/subsonic-api/)
- [Subsonic API](https://www.subsonic.org/pages/api.jsp)

### TV listening and navigation pass — 2026-11-01

- Home replaces the Flight Recorder card with Drop the Needle on TV. It replaces
  the shared MediaController queue with up to 40 distinct shuffled library tracks,
  disables secondary shuffle ordering, starts playback, and opens Now Playing.
- TV navigation omits Downloads; library, album/playlist, track menus, Radio,
  and Settings omit download/recording entry points. Phone features remain.
- Focus uses a 3 dp amber outline and tinted surface, separate from the cyan
  selected navigation item. Explicit rail Up/Down ordering prevents jumps into
  content. Track artwork/title share one TV playback focus target.
- Updated TvNavigationTest checks hidden TV controls and uses Drop the Needle
  to start playback, verifies the distinct queue count (including the fixture's
  smaller library), Now Playing, pause, and background service playback.
- Final checks: TV instrumented suite passed (2 tests); TV unit tests and lint
  passed. Phone fixture integration/UI, cold-start, voice, and radio checks all
  passed; phone unit tests, lint, and release build passed.
- Signed optimized APK installed in place on AFTDCT31 (Fire OS 7 / API 28).
  On-device screenshots confirm the revised rail, distinct focus/selection
  treatments, and larger content targets. Existing signed-in account retained.
  Playback behavior was asserted on the fixture; no audible hardware claim.

### Phone Home: Drop the Needle — 2026-11-01

- Home now offers Drop the Needle for WeazlMusic and direct Navidrome libraries.
  Like the web random-listening action, it replaces the queue and starts music;
  Android selects up to 40 distinct tracks from the synced catalog. Smaller
  libraries yield shorter queues. Offline mode uses only saved tracks.
- Track selection runs off the main thread; MediaController commands return to
  the main thread. Account/offline-mode changes during selection cancel the
  action. Playback continues to use the existing PlaybackService and session.
- Flight Recorder's phone Home card is removed; its existing Radio entry remains.
  Phone Now Playing returns to Home with Back. The TV Home action is retained.
- Debug/release builds, unit tests, lint and the full isolated phone emulator
  suite passed. The UI test now taps the button and verifies playing state,
  distinct queue size, shuffle ordering, Back, and Radio → Flight Recorder.
- The optimized development-signed preview installed in place on the connected
  Pixel and launched successfully. User account data was retained; no production
  playback or new physical head-unit acceptance was performed by these tests.
- The isolated TV credential/navigation suite also passed (2 tests), including
  Drop the Needle playback and its shared queue. TV unit tests and lint passed.

### Android Auto playlists and Drop the Needle — 2026-11-13

- Playlist browse rows encode the playlist and entry occurrence in their media
  IDs. A legacy play-from-ID request now loads the whole playlist, starts at the
  tapped occurrence, and preserves order and duplicates. Standalone search/song
  requests retain the existing related-track continuation. Actual playback uses
  canonical track IDs in the same PlaybackService queue.
- Playlist folders expose cover artwork (explicit playlist cover or a contained
  track's cover), with a local playlist icon if unavailable. The Playlists entry
  and Drop the Needle tile also have local icons served through ArtworkProvider.
- Android Auto Home includes a playable Drop the Needle tile: up to 40 distinct
  random library tracks, or saved tracks in offline mode, with shuffle ordering
  disabled after choosing the mix.
- Synthwave troubleshooting was initially deferred. A direct GET from the Mac
  returned HTTP 200/audio-mpeg and received live audio bytes. The user subsequently
  confirmed Synthwave works in the app, closing the reported playback issue.
  The underlying cause of the earlier failure was not established.
- Debug/release builds, 29 unit tests and lint passed. AutoBrowseIntegrationTest
  passed through MediaBrowser and legacy MediaControllerCompat: every playlist
  position, duplicate occurrences, skip/previous, stale-entry rejection,
  decodable artwork/fallback icons, Drop the Needle playback, 40-track cap and
  offline-empty handling. The integration/UI, both cold-start checks, voice,
  and radio gateway tests also passed.
- Test environment limitations: a shared Gradle daemon was stopped externally;
  the build succeeded with a single-use daemon. The shared emulator initially
  timed out during UI setup; a clean Subweazl launch passed. A separate headless
  API 37 emulator crashed during boot. On the existing emulator the final broad
  run passed integration/UI, cold-start and voice, then hit ENETUNREACH during
  Auto test login. Auto plus both radio gateway tests passed on retry (3 tests).
  No application/server failure was replaced with sample data.
- Evidence: ignored build/auto-nov13-*.log, build/evidence/auto-browse.txt and
  build/evidence/auto-nov13/auto-radio-recheck.txt. An optimized development-signed
  APK installed in place on the connected Pixel on November 13; its package
  still exposes PlaybackService with android.media.browse.MediaBrowserService.
  Accounts and downloaded data were retained. No DHU was available; playlist
  artwork/layout and controls still need acceptance on the physical head unit.
