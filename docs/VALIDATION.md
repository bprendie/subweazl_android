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

## References

- [MediaLibraryService](https://developer.android.com/media/media3/session/serve-content)
- [Android Auto media declaration](https://developer.android.com/training/cars/media/auto)
- [Car testing](https://developer.android.com/training/cars/testing)
- [Navidrome supported API](https://www.navidrome.org/docs/developers/subsonic-api/)
- [Subsonic API](https://www.subsonic.org/pages/api.jsp)
