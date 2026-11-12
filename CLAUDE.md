# CLAUDE.md

Guidance for Claude Code (and other contributors) working in this repository.

## What this is

**App Downloader** — a single-module Android app (Kotlin, Jetpack Compose, Material 3) that saves
APK sources and installs them. Sources are direct URLs, aftv.news short codes, or GitHub
repositories (whose releases are monitored for updates). Runs on phones, tablets, Fire TV and
Android TV.

## Commands

```sh
./gradlew testDebugUnitTest      # JVM unit tests (fast, no device needed)
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug              # Android lint -> app/build/reports/lint-results-debug.html
./gradlew testDebugUnitTest --tests '*ParsingTest*'   # a single test class
```

Requires JDK 17+ and an Android SDK with platform 35 (`local.properties` → `sdk.dir=...`, git-ignored).
Run `testDebugUnitTest assembleDebug` before every commit; CI runs the same plus `assembleRelease`.

## Layout

All code lives in `app/src/main/java/io/github/edrock200/appdownloader/`:

| Path | Responsibility |
|---|---|
| `App.kt` | `Application`; builds the singletons (`repository`, `settings`, `controller`), tracks foreground (`isForeground`), re-arms the schedule (`refreshSchedule`), CoroutineExceptionHandler safety net. Use `context.app()` to reach them. |
| `AppController.kt` | Process-wide coordinator: download → inspect → install pipeline, update checks, batches, self-update, per-entry `OpState`, snackbar `messages`. **Main-thread confined**: workers switch to `Dispatchers.Main` before calling in. |
| `data/AppEntry.kt` | The persisted model (`@Serializable`). One class for all source types; GitHub-only fields are nullable. |
| `data/EntryRepository.kt` | JSON file in `filesDir`, exposed as `StateFlow`, writes serialized by a `Mutex`. Atomic writes (tmp + fsync + rename) with `entries.json.bak`; per-entry decoding; damaged files kept aside; failed saves keep memory state and set `problem`. |
| `data/Settings.kt` | SharedPreferences-backed settings as a `StateFlow`. |
| `data/SourceParser.kt` | Detects whether user input is an aftv code, a GitHub repo or a URL. |
| `data/VersionUtils.kt` | Version comparison and the `hasUpdate` rule. |
| `net/Http.kt` | Shared OkHttp clients (`client` for downloads, `metaClient` with a call timeout for lookups), coroutine `execute` that cancels the call; blocks https→http redirects; bundles Let's Encrypt roots below Android 7.1.1. |
| `net/Errors.kt`, `net/LinkNormalizer.kt` | Plain-language error messages (`Errors.friendly`, `isConnectivity`); Dropbox/Google Drive share links → direct downloads. |
| `net/AftvResolver.kt` | Resolves codes via `https://go.aftvnews.com/<code>` by parsing the redirect page HTML. |
| `net/GitHubClient.kt` | Releases API, repo URL parsing, `pickRelease` (pure). |
| `net/AssetSelector.kt` | Chooses the release asset for the device ABI / user filter (pure). |
| `net/RemoteProbe.kt` | "Has this file changed?" without a full download: `fingerprint(url)` (HEAD / 1-byte GET headers, follows a page's APK link, hashes the last 256 KB via a positional Range request — GitHub's storage rejects suffix ranges), pure `compare` and `decide`. |
| `install/ApkDownloader.kt` | Streaming download with progress; scans HTML pages for `.apk` links; validates zip magic. |
| `install/ApkInspector.kt` | Reads package name/version/label; picks splits from `.xapk`/`.apks`; `InstalledApps` lookup. |
| `install/ApkInstaller.kt` | `PackageInstaller` session install; result goes to `InstallResultReceiver`. |
| `work/` | WorkManager jobs: periodic GitHub check (`UpdateCheckWorker`), the user's scheduled "update all" run (`AutoUpdateWorker` + `AutoUpdateScheduler`), notifications. |
| `data/Schedule.kt` | Pure date/time math for scheduled runs (`nextRun`, `atTime`), DST-safe via `Calendar`. |
| `ui/ScheduleDialog.kt` | Date/time/repeat picker built from step buttons (works with a TV remote). |
| `ui/` | `MainActivity`, `MainViewModel`, Compose screens and dialogs, `SystemActions` (intents to the system). |
| `ui/Tv.kt` | `Modifier.focusHighlight()` (remote-friendly focus ring), `Device.isTv/isFireTv`, per-device install-permission help. |
| `ui/SelfUpdateDialog.kt` | Prompt shown when a newer App Downloader release exists. |
| `data/History.kt`, `ui/HistoryDialog.kt` | Per-app update history (`AppEntry.history`, newest first, max 50): pure `recordInstall` / `syncWithDevice` / `describe`, and the ⋮ › Update history dialog. |
| `data/Backup.kt` | Backup format (`BackupFile`/`BackupApp`/`BackupSettings`), JSON + QR (`ADBK1:` + base64url(gzip(json))) encoding, pure merge/replace (`restoreApps`) and `restoreSettings`. |
| `ui/BackupStorage.kt` | SAF read/write, Downloads fallback (MediaStore on 29+, file + storage permission on ≤28) for TVs without a file picker, `canScanQr`. |
| `ui/BackupDialogs.kt`, `ui/QrCode.kt` | Show-QR (ZXing 3.3.3 → Canvas), restore confirmation, restore-from-link, Downloads picker. Scanning uses Play services' `GmsBarcodeScanning` (no camera permission). |
| `.github/workflows/android.yml` | CI: tests, builds, signs, publishes tagged releases, announces on Discord. |
| `.github/scripts/` | `release-notes.sh` (CHANGELOG section → plain-language notes), `discord-notify.sh`. |

Unit tests are in `app/src/test/...` and cover the pure logic only.

## How the main flows work

- **Install:** `AppController.downloadAndInstall(id)` claims the entry (`OpState.Resolving`) before
  suspending, resolves the URL (aftv / GitHub check; private repos via the asset API URL + token),
  downloads to `cacheDir/apks/<entryId>.<ext>` (unique `.part` per attempt, verified against GitHub's
  size/sha256, free-space check), inspects it, refuses an older versionCode (records `notNewer` /
  `skippedTag`) or a different package (`OpState.Failed.differentApp` → "Install anyway"), then commits
  a session. `install()` records the session id in `sessions[opId]`; results of other/abandoned sessions
  are ignored (successes are always recorded). `InstallResultReceiver` (goAsync) hands
  `STATUS_PENDING_USER_ACTION` to `onNeedsConfirmation`: in the foreground the activity starts the
  confirm intent via `confirmRequest`; in the background the session is abandoned and the file kept as
  a waiting `readyFile`. `reconcileSessions()` (onResume) frees ops whose session vanished. packageName,
  label, history and the fingerprint baseline are committed in `onInstallSucceeded` (EXTRA_PACKAGE/LABEL).
  Jobs are owner-checked (`owns`) so a cancelled job never cleans up after its replacement.
- **Update detection:** `VersionUtils.hasUpdate`. If the installed version code equals the one we
  installed, compare release tags; otherwise compare the tag's numbers with the installed
  `versionName`. Not installed → "Install", never "Update".
- **Launch check:** `MainActivity.onStart` → `controller.checkAllOnLaunch()` runs a quiet `checkAllNow`
  (no snackbar) unless one is running or finished within 5 minutes (`UpdateCheckPolicy`, protects the
  GitHub rate limit). Cards with `hasUpdate || waiting` show `UpdateStar` and an "Update" button; the
  list's first item is `UpdateAllBar`, which calls `controller.updateEntries(ids)` for exactly the
  starred apps (regardless of the Auto-update tick box), installing one at a time via `runInstall` +
  `awaitInstallResult`. Settings' "Update all now" is different: ticked apps only (`updateSelected`).
- **Update history:** `install()` passes the version on the device to the session (`EXTRA_PREV_VERSION_*`);
  `onInstallSucceeded` adds an `InstallRecord` (`automatic` for background runs) via `History.recordInstall`.
  `checkAllOnLaunch` first runs `syncHistoryWithDevice`, which records version changes made outside the
  app (dated by `PackageInfo.lastUpdateTime`, `byApp = false`) or seeds a FOUND record; a seen-on-device
  record within 10 minutes of the app's own record of the same version is replaced by it.
  History is device-specific and not part of backups.
- **Background checks:** `UpdateCheckWorker` → `checkAll()` → notify for GitHub entries whose
  `notifiedTag != latestTag` and links whose `notifiedChangeKey != remoteFingerprint.key`.
- **Batches:** one at a time (`batchMutex`). UI batches (`updateAllNow`, `updateEntries`,
  `installWaitingUpdates`) run each item as a registered child job (`runItem`) so card Cancel works;
  `stopUpdateAll()` cancels the batch. Scheduled runs call `runScheduledUpdate()` (null when busy → the
  worker retries without advancing the schedule).
- **Scheduled "update all":** the schedule (`scheduleAt`, `scheduleRepeat`: once, daily, weekly, or
  hourly `EVERY_1H…EVERY_12H` — hourly steps are fixed real-time intervals, daily/weekly keep wall-clock
  time across DST and follow time-zone changes via `scheduleMinuteOfDay`/`scheduleWeekday`,
  `Schedule.nextLocal`, `ClockChangeReceiver`) lives in `Settings` and is
  saved immediately by `MainViewModel.setSchedule`, which (re)enqueues a one-time WorkManager job with
  `AutoUpdateScheduler.constraints` (Wi-Fi only option, battery/storage not low). `App.onCreate`
  re-arms it with `KEEP`. `AutoUpdateWorker` books the next run first, runs the update, stores
  `lastRunAt/lastRunSummary`, and returns `Result.retry()` when every failure was the network.
  Background installs: `canInstallSilently` (Android 12+, App Downloader is installer of record, not
  `readyNeedsUser`; Android 12-13 only when the screen is off; Android 14+ checks/commits with
  `InstallConstraints.GENTLE_UPDATE`). Background failures persist `failedVersionCode`/`failedTag`/
  `lastUpdateError` so they aren't retried every run; insecure (http) sources need
  `allowInsecureAutoUpdates`.
  `updateSelected` handles installed entries with `autoUpdate`: `prepareUpdate` checks GitHub (or, for
  links, downloads and compares versionCode), and stores a newer APK as `readyFile`/`readyVersion*`/`readyTag`.
  Background runs commit sessions with `EXTRA_BACKGROUND`; on Android 12+ they can complete silently,
  otherwise `InstallResultReceiver` abandons the session on `STATUS_PENDING_USER_ACTION` and the update
  stays "waiting" (notification + prompt on next launch). Below Android 12 nothing is committed in
  the background. Interactive runs ("Update all now", "Install now") install one app at a time,
  waiting for each result (`awaitInstallResult`). `runInstall` always prefers a waiting `readyFile`;
  stale ones (older than installed, or not the latest GitHub tag) are discarded.
- **Link change detection:** each download records a `RemoteFingerprint` (ETag, Last-Modified, size,
  host+path without query, `tailHash` = SHA-256 of the last 256 KB) as `pendingFingerprint`;
  `onInstallSucceeded` promotes it to `fingerprint`. `checkLink` probes the server and calls
  `RemoteProbe.decide`, in order: (1) CONTENT — compare the remote tail hash/length with the installed
  APK (`InstalledApps.localApk`, single-APK installs only; needs no saved fingerprint), (2) SAVED_DETAILS —
  `compare(fingerprint, remote)`, (3) DATE — remote Last-Modified vs `PackageInfo.lastUpdateTime`
  (±2 min), else UNKNOWN with NO_DETAILS / NO_BASELINE. It stores `remoteChange`/`remoteReason`, and a
  `newBaseline` as `fingerprint`. `compare` trusts the tail hash, then ETag/date; size/address alone
  can only prove a change. With
  the `updateOnlyIfChanged` setting, `prepareUpdate` skips links only when the result is UNCHANGED;
  UNKNOWN still downloads and compares versionCode (and then records the download as the new baseline).
  `checkAll` covers GitHub entries and installed links; `VersionUtils.updateAvailable` drives the card.
- **Backup & restore:** `MainViewModel` builds `Backup.create(...)` (only user-set fields; token only when
  asked, never in QR), writes via SAF or `BackupStorage.saveToDownloads`, and reads from SAF, Downloads,
  a scanned QR or a link/aftv code into `pendingRestore`, which `RestoreDialog` confirms. `restore`
  merges or replaces via `Backup.restoreApps` (keeping ids of matching entries), `repository.replaceAll`,
  optionally `saveSettings(Backup.restoreSettings(...))` (re-applies both schedulers), then `checkAllNow`.
  Bump `BackupFile.version` (and teach `Backup.parse`) for incompatible format changes.
- **Self-update:** `MainActivity.onStart` (every time the app comes to the foreground — TVs resume
  the singleTask activity instead of recreating it) → `controller.checkSelfUpdateOnLaunch()`
  (every launch while the *checkSelfUpdates* tick box is on; skipped only while an update is already
  offered/installing or a check is running — see `SelfUpdatePolicy`; failures retry 3× at 10 s) looks up `BuildConfig.UPDATE_REPO`'s releases
  for an `AppDownloader*.apk` asset and compares the tag with `BuildConfig.VERSION_NAME`. A newer one
  sets `controller.selfUpdate`, shown by `SelfUpdateDialog`; installing it reuses the download/install
  pipeline under the op id `AppController.SELF_UPDATE_ID` and refuses APKs with another package name.
- **Releases:** the workflow has a secret-free `build` job (tests, lint, `-dev+sha` test builds, release
  notes + Discord dry-run, shellcheck) and a `release` job (only for a `vX.Y.Z` tag or a manual run
  with `version`; refuses lower/duplicate versions and missing changelog sections, verifies the signing
  certificate digest, creates a draft then publishes, `announce_only` re-posts). Actions are pinned to
  commit SHAs. Running the workflow manually with input `version: X.Y.Z` (or pushing tag `vX.Y.Z`)
  sets `APP_VERSION_NAME` (versionCode =
  X*10000 + Y*100 + Z, computed in `app/build.gradle.kts`), signs with the `SIGNING_*` secrets,
  publishes the release with the fixed asset name `AppDownloader.apk`, and posts to the
  `DISCORD_WEBHOOK` secret (aftv.news code `9697744`, overridable with the `AFTV_CODE` repository variable). Stable download link:
  `https://github.com/edrock200/androidappdownloader/releases/latest/download/AppDownloader.apk`.

## Conventions and gotchas

- **minSdk is 22** (old Fire TV devices). Guard newer APIs with `Build.VERSION.SDK_INT` checks
  (e.g. `canRequestPackageInstalls` is 26+, `FLAG_MUTABLE` / `setRequireUserAction` are 31+).
  Lint flags misses — keep lint free of errors.
- **Keep logic testable on the JVM.** Unit tests run against stubbed `android.jar`, so parsing,
  selection and version logic must not touch Android APIs. Put such logic in pure functions
  (see `GitHubClient.pickRelease`, `ApkInspector.selectSplits` with an explicit `supportedAbis`).
- **Unit tests must not hit the network.** Use recorded fixtures (see `GitHubReleaseTest`).
- **TV / D-pad:** every action must be reachable without touch — use standard focusable Material
  components (buttons, menus), no gesture-only or long-press-only actions. Give every clickable
  control `Modifier.focusHighlight(...)` (pass `FocusShapes.Row, scaleUp = false` for rows and menu
  items). Make label+switch/checkbox rows a single `toggleable` row. Hide browser-only actions when
  `SystemActions.hasBrowser()` is false, and use `Device.installPermissionHelp()` for permission text.
- **Adding a field to `AppEntry`:** give it a default value so existing `entries.json` files still
  load (`ignoreUnknownKeys` covers removed fields, defaults cover new ones).
- **Persistence changes go through `EntryRepository.update/add/delete`**, never by mutating the flow.
- UI state comes from `MainViewModel.items` (`EntryUi`); call `refreshInstalled()` when installed
  apps may have changed (done in `onResume`).
- User-facing errors are short sentences shown on the entry card (`OpState.Failed`) or as a
  snackbar via `controller.message(...)`; keep them actionable ("Add a token in Settings").
- Dependencies are declared in `gradle/libs.versions.toml`; avoid adding libraries for things the
  platform/OkHttp/kotlinx.serialization already cover. Avoid `material-icons-extended` (APK size).
- Release builds use R8 conservatively (`app/proguard-rules.pro`: `-dontobfuscate`, the app's own
  classes and reflection-heavy libraries kept). Loosen it only after testing a release on a phone and
  a Fire TV — a broken release also breaks self-update.
- Network tests use `MockWebServer` (local); `GitHubClient.apiBase` and `AftvResolver.resolveBase` are
  overridable for that. Don't add tests that reach the real internet.
- Don't commit keystores or `local.properties`. Release signing is configured via `SIGNING_*`
  environment variables (see README). The only committed APKs are the signed release builds in
  `releases/`; never put a debug-signed APK there, because a different key breaks in-place updates.
- Record user-visible changes in `CHANGELOG.md` under *Unreleased*, written in plain language for
  non-technical users — the section becomes the release notes and the Discord announcement. When
  releasing, rename it to `## [X.Y.Z]` and tag `vX.Y.Z`. Don't edit versionCode/versionName by hand
  (they come from the tag); only update the local-build default in `app/build.gradle.kts`.
- Release notes and the Discord announcement are for everyone, not just TV users: the app runs on
  Android phones and tablets as well as Fire TV / Android TV, so install instructions must cover both
  (see `.github/scripts/discord-notify.sh` and the "Write release notes" workflow step). Only publish
  a release (which posts to Discord) when the user asks for one.
- Never rename the release asset `AppDownloader.apk`: the "latest" download link, the aftv.news code
  and in-app self-update all depend on it.

## Not covered by tests

No instrumentation/UI tests exist; the install flow, notifications and Compose UI need manual
testing on a device or emulator (ideally one phone and one Fire TV / Android TV).
