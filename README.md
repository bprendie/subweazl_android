# Subweazl for Android

Native Android music client built in Kotlin and Jetpack Compose, with Android
Auto, WeazlMusic integration and optional direct Navidrome support. Ported from
the implemented Subweazl iOS app using the same backend contract and brand assets.
No server, account, or sample library is embedded in the shipping app.

**Server compatibility:** version 1.3.0 adds optional direct Navidrome connections.
WeazlMusic remains the default and existing accounts keep their server type.
Choose **Navidrome** under Settings → Add server for library browsing, search,
playlists, favorites, artwork, streaming, downloads, in-app voice and Android Auto
through the existing player. Enhanced radio/SiriusXM, Flight Recorder and Mood
require [WeazlMusic](https://github.com/bprendie/weazlmusic).
Direct Navidrome currently uses original audio quality and local listening history;
server scrobbling is not enabled. Each direct account uses one explicitly configured
server URL. See [validation evidence](docs/VALIDATION.md) for tested coverage.

Single-song selections from phone, voice and Android Auto start a queue of up to
40 tracks: selected song, album continuation, more by that artist, then library
variety. Explicit collection queues and enqueue operations preserve their entries.
Offline continuation uses saved tracks only. If the WeazlMusic radio relay returns
502/503/504, the app tries the station's configured HTTPS stream once, without
account tokens or proxy headers and without following redirects.

## Open and build

Open this folder in Android Studio and run the `app` configuration. The project
uses the checked-in Gradle wrapper, Android Gradle Plugin 9.1.0, Java 17 bytecode,
and Android SDK 36. Minimum Android version is 9 (API 28).

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Use JDK 25 for the Gradle daemon (the app targets Java 17 bytecode). Set
`ANDROID_HOME` to your SDK directory, or let Android Studio create the local SDK
configuration. Neither the iOS source nor a running backend is required to build.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Unsigned optimized release: `app/build/outputs/apk/release/app-release-unsigned.apk`.
[GitHub releases](https://github.com/bprendie/subweazl_android/releases) provide
installable previews when available. Preview builds use a development certificate,
which differs from Google Play's certificate; switching distributions may require
uninstalling and removes local app data. You supply your own server and account.
The Google Play production listing is not yet publicly available.

Maintainer packaging scripts use keys outside the repository; contributors do
not need those keys. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup and pull requests.

## Features

- Home, Library, Playlists, Search, Radio, settings, and full/mini players, using
  the original dark palette, neon accents, square controls, wordmark and artwork.
- Multiple accounts; verified home/remote server identity; optional proxy
  headers; Android Keystore encrypted tokens; serialized token refresh.
- Complete paged local library, offline browse/search, album/artist/playlist
  details, favorite tracks/albums/artists and a durable mutation journal.
- Editable playlists with repeated entries, ownership checks, version conflicts,
  queue saving, whole-collection playback and downloads.
- Background Media3/ExoPlayer audio, media notifications, lock-screen controls,
  audio focus, headset disconnect handling, artwork, shuffle/repeat, queue editing,
  saved position, and a held library queue while listening to radio or recordings.
- Wi-Fi/cellular/saved quality preferences based on server capabilities,
  persistent WorkManager downloads, safe Range resume, progress, pause/retry,
  removal, storage limits, and promotion of complete playback cache to offline audio.
- Radio presets with ordering, station editing, directories, server-relayed audio,
  and live server metadata.
- Flight Recorder: immediate and recurring server recordings, exact-time review,
  edit/cancel/stop/delete, recoverable create requests, SHA-256 validated offline
  assets, explicit gaps, and one shared timeline across recorded presets.
- Mood generation, curator settings/model discovery, seed search, progress,
  cancellation, account devices, sign-out, and local account removal.
- Device-local recent/frequent listening shelves and qualified server scrobbles.

The parity reference is the **implemented iOS app**, not features that appear only
in its older aspirational Nautiline workbooks. See `docs/PARITY.md` and
`docs/VALIDATION.md` for evidence and outstanding device acceptance.

## Android Auto

The default directly installed build uses Android Auto's standard media UI:
Home (Recently Played, Newest Albums, Random Albums, with artwork), Library,
Radio and Saved. Playlists are under Library. Android Auto renders the launcher,
browse screens, search, queue and Now Playing using the shared `PlaybackService`.

The custom Car App Library UI is preserved but disabled in the default build.
Build it explicitly with `-PtemplatedCarUi=true` for Desktop Head Unit development
or Play-based testing. Do not assume sideloading this build enables it in a real
car: Google's Unknown Sources exception does not apply to Car App Library apps.
The 1.1.0 custom build passed DHU tests but the user confirmed it did not appear
in the real car's launcher. Do not describe that build as vehicle-validated.

Sign in on the phone and enable **Unknown sources in Android Auto developer
settings** for the default sideloaded media build. Check **Customize launcher**
for Subweazl. See [Google's real-vehicle testing rules](https://developer.android.com/training/cars/testing#unknown-sources).
The actual vehicle must be retested; a Desktop Head Unit check is insufficient.

## Isolated integration tests

The existing sibling backend includes a synthetic music/radio fixture server:

```sh
cd ../web_weazltunes
go run ./cmd/weazlfixture
```

With an Android emulator running, return here and run:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.prendie.subweazl.IntegrationTest,io.prendie.subweazl.UiTest
```

Tests use only `http://10.0.2.2:4003` and disposable fixture users. They create and
remove fixture playlists and create short fixture recordings. They must not be
pointed at a production account. The source app and backend source are unchanged.

`ColdStartTest` requires separate prepare/verify invocations with an intervening
force-stop; `scripts/test-emulator.sh` runs the complete sequence and preserves
the APKs on the emulator for inspection.


Voice playback uses the existing Media3 session for Android Auto and the phone
assistant's MEDIA_PLAY_FROM_SEARCH intent. Try “Play What's the Story Morning
Glory by Oasis on Subweazl,” “Play Wonderwall by Oasis,” or “Play playlist Night
Drive.” Albums play in disc/track order; playlist repeats are preserved.
Structured artist/album/title fields are honored, and ambiguous or missing
matches leave the current queue intact.

Pronounce the app name “sub weasel.” The resolver tolerates trailing “on Sub
Weasel,” “on Subweazl,” and “on Subway” only after a request reaches this app.
This does not configure Google's speech recognition or guarantee that Google
selects Subweazl. If Google chooses Subway before delivery, app-side parsing
cannot fix that routing decision. Logs tagged SubweazlVoice report resolution
outcomes without recording the spoken query.


On the phone, tap the microphone beside Settings and say the music request without
the app name. This uses Android's installed speech recognition activity and the
same playback resolver, bypassing Gemini's music-provider selection. Cancelling
recognition leaves playback unchanged. No microphone permission or audio storage
is added to Subweazl. Recognition speed/network use depends on the installed
speech provider.

Voice commands also accept “Shuffle The Midnight on Subweazl,” “Shuffle album
What's the Story Morning Glory by Oasis,” and “Shuffle playlist Night Drive.”
The named collection is queued with a random starting item and shuffle enabled;
a subsequent ordinary play request restores ordered playback.

## License

[MIT](LICENSE), copyright 2026 Bob Prendergast.
