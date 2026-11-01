# iOS-to-Android implementation map

Reference: the current `../subweazl_app/Subweazl` source, inspected October 11,
2026, including its latest Home, playlist, radio, Mood and Flight Recorder work.

| Original behavior | Android implementation |
| --- | --- |
| RootView, Theme, OnboardingView | Compose root, five-tab strip, square controls, original theme/assets, Connect form |
| Account profiles, KeychainVault, WeazlTunesClient | Repository, AES-GCM Android Keystore vault, Api; installation/account/library scoped storage |
| Full library cache, album/artist/playlist details, typed search | Catalog snapshot, local indexed-by-model browsing/filtering, cancellable background search |
| Favorites and mutation review | Persisted idempotent journal, optimistic favorites and uncertain-outcome review |
| Playlist duplicate entries/edit ownership/ETag checks | Ordered track lists, reorder/remove/add, version-aware mutation requests |
| Home discovery and local history | Recent/random albums, random/recent/frequent tracks, favorites; qualified play counts |
| Apple PlaybackEngine and PlaybackCoordinator | ExoPlayer in a foreground MediaLibraryService; shared MediaController for UI |
| Queue restore, shuffle/repeat, held library while on radio | Durable occurrence-preserving queue/index/position, restore without Activity, held queue |
| Music downloads/cache and quality controls | WorkManager, verified atomic file promotion, Range resume, per-account preferences and budgets |
| Radio presets/directories/relay/metadata | Station CRUD, ordered presets, server media leases and authenticated SSE |
| Server Flight Recorder and offline manifests | Versioned schedules/sessions, recoverable create, local segment validation and downloads |
| Recorded radio shared clock and gaps | Single-window concatenated media sources with clipped segments and silence for explicit gaps |
| CarPlay and Siri equivalents | Android Auto media tree, MediaSession search/voice resolution, ordered radio presets |
| Lock screen, background playback, headset controls | Media3 session/notification, audio focus, noisy-route handling, cached artwork |
| Mood & curator, devices, privacy/help | Compose settings flows backed by the same endpoints; Android-specific privacy text |

Android Auto supplies its own driver-safe presentation. It does not render the
phone's five-tab interface. Android's audio focus, codecs, routes, background work
and battery restrictions replace Apple platform behavior; physical tests are
required to establish equivalent behavior on the user's equipment.

The original production views do not implement the older wishlist's lyrics,
equalizer, widgets, folders, ratings, sharing or Music Haptics. They were not
invented for this port. The original's Apple entitlements and App Store release
work are not Android features and were not copied.

The Android app is implemented and builds, but exact end-to-end parity on the
user's real library/phone/car is **not yet certified**. Current checks and the
specific remaining acceptance work are in VALIDATION.md.
