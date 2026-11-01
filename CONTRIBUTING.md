# Contributing

Fork the repository, create a branch, and open a pull request against `main`.
For larger behavior changes, open an issue first to discuss the approach.
Describe the problem, resulting behavior, and validation in your pull request.

## Build locally

Install Android Studio, Android SDK Platform 36, Build Tools 36.0.0, and JDK 25
(the Gradle daemon is pinned to 25; app bytecode targets Java 17). Open the
repository in Android Studio and select the `app` run configuration. The iOS
source and a running backend are not required to build this project.

```sh
git clone https://github.com/bprendie/subweazl_android.git
cd subweazl_android
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Set `ANDROID_HOME` to your Android SDK directory, or configure the SDK location
through Android Studio (which creates ignored `local.properties`). Gradle uses
the installed JDK 25 or can provision the daemon JDK via its checked-in criteria.
The Gradle wrapper and dependency declarations are included; use the wrapper.
No Play credentials or signing keys are needed for development builds.

Use your own WeazlMusic or Navidrome server for manual use. For automated
integration tests, use only the isolated fixtures described in
[VALIDATION.md](docs/VALIDATION.md). Their disposable credentials are public test
data. Never point mutation tests at production accounts. Set `ANDROID_SERIAL`
to the intended emulator when multiple devices are connected.

## Architecture and review rules

- `PlaybackService.kt` owns the only player, MediaSession, queue and playback
  lifecycle. Phone, voice, Auto and system controls all use it.
- `Api.kt` implements WeazlMusic v1; `Navidrome.kt` adapts direct Subsonic access
  into the same repository/catalog models. Capability-gate unsupported features.
- Preserve duplicate playlist/queue occurrences, stable account storage identity,
  download integrity checks and Flight Recorder's shared timeline.
- Never forward account tokens or proxy headers to an unverified origin/redirect.
- Do not hide server errors with sample content or introduce another player.
- Keep credentials, personal libraries/media, device dumps, keys, and build
  artifacts out of commits. Use synthetic data in tests and issue reports.
- Report emulator tests separately from real phone/audio/head-unit observations.

GitHub Actions runs builds, unit tests and lint on pushes and pull requests.
Integration tests require separately running fixtures and an emulator. Include
relevant results when changing playback, protocol, downloads, or Auto behavior.

## License

Contributions are provided under this project's [MIT license](LICENSE).
