# Subweazl Android

The user requires a functionally identical Android port of `../subweazl_app`,
including Android Auto. Use the current iOS implementation and its v1 contract
as the reference; distinguish implemented functionality from its old wish lists.
Do not replace real server failures with sample data. Do not modify production
accounts while testing. The sibling backend's `cmd/weazlfixture` is available.

Keep playback owned by PlaybackService, independent of Compose/Activity lifetime.
Phone, Android Auto and system controls must use the same MediaSession and queue.
Preserve duplicate playlist/queue occurrences and the Flight Recorder shared clock.
Never forward account tokens or proxy headers to an unverified origin or redirect.
Keep secrets, downloaded audio, personal library data, signing keys and build output
out of Git. Keep the source iOS app unchanged unless specifically requested.

Build: `./gradlew :app:assembleDebug :app:assembleRelease`.
Checks: `./gradlew :app:testDebugUnitTest :app:lintDebug` and
`scripts/test-emulator.sh` with the isolated backend running.
Read `docs/VALIDATION.md` for evidence and remaining physical-device checks.
Do not claim head-unit or audible hardware acceptance based only on emulator tests.
