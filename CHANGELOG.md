# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Choose Follow system, Light, or Dark under Appearance in Settings. Changes apply immediately,
  and your choice is saved and included in backups.

## [1.6.0] - 2026-11-05

### Added
- Opening App Downloader now checks every app in your list for a new version.
- Apps with an update get a gold star, and their button changes to "Update".
- An "Update all" button appears at the top of the list when updates are found. It updates
  every starred app, one after another.

## [1.5.0] - 2026-11-05

### Added
- Back up & restore in Settings: save your list of apps and your settings to a file, and restore
  them later or on another device. Choose whether to add to your current list or replace it.
- Copy everything to another device with a QR code: "Show QR code" on one device, then
  "Scan QR code" on a phone. Your GitHub token is never put in a QR code.
- "Restore from link or aftv.news code" loads a backup file from the internet, which is handy on
  TVs: type the code with the remote.
- On TVs without a file picker, backups are saved to and restored from the Downloads folder.

## [1.4.3] - 2026-11-05

### Changed
- "Check for App Downloader updates each time it's opened" is now a tick box in Settings (on by
  default). When ticked, every time you open App Downloader it checks for a new version and asks
  whether you want to install it.

## [1.4.2] - 2026-11-05

### Fixed
- App Downloader now checks for its own updates every time you open it, including when it was
  still running in the background (the usual case on TVs). Before, it only checked after a restart.
  It also retries if the network isn't ready yet, for example right after the TV wakes up.

## [1.4.1] - 2026-11-05

### Changed
- The schedule now asks for a day of the week ("Today", "Tomorrow", "Saturday"…) instead of a
  calendar date, and only for one-off and weekly schedules. Daily and hourly schedules just need a time.

## [1.4.0] - 2026-11-05

### Added
- Scheduled updates can now also repeat throughout the day: every 1, 2, 4, 8 or 12 hours,
  starting from the date and time you pick.

## [1.3.0] - 2026-11-05

### Added
- Plain links and aftv.news codes now show whether the file on the website has changed since
  you installed it ("Changed on the website" / "No change"), checked without downloading it.
  The ⟳ button and "Check for update" now cover these apps too, and you get notified about changes.
- New setting "Only update links whose file has changed" (on by default) for scheduled updates and
  "Update all now". If a website doesn't say whether its file changed, it is still downloaded and
  compared, so no update is missed.

## [1.2.0] - 2026-11-05

### Added
- Scheduled updates: pick a date and time in Settings (once, every day or every week) and
  App Downloader updates all your apps at that time.
- Each app now has an "Auto-update" tick box, so you choose which apps are included.
- "Update all now" in Settings updates every ticked app straight away.
- If Android needs you to confirm an install, the update is downloaded in advance and waits
  for you. Next time you open App Downloader it offers to install it with one click.

## [1.1.0] - 2026-11-03

### Added
- App Downloader now checks for its own updates when you open it and offers to install them.
  You can turn this off in Settings, or check right away with "Check for app update now".
- Settings shows which version of App Downloader you have.

### Changed
- Much easier to use with a TV remote: the selected button is outlined and slightly enlarged,
  and the app you're on is outlined too.
- Extra space around the edges on TVs, so nothing gets cut off.
- Clearer help for allowing installs on Android TV and Google TV, not just Fire TV.
- Options that need a web browser are hidden on TVs that don't have one.

## [1.0.0] - 2026-11-03

### Added
- Save and install APKs from direct URLs, aftv.news codes and GitHub repositories.
- Web-page links are scanned for an `.apk` link when they don't point at an APK directly.
- GitHub release monitoring with ABI-aware asset selection, optional asset filter and pre-release
  opt-in per repository.
- Background update checks (3/6/12/24 h or manual) with notifications.
- Installation of plain APKs and split bundles (`.xapk`, `.apks`) via PackageInstaller.
- Open, uninstall, re-download, edit and remove actions per app; share-to-add from other apps.
- Settings for GitHub token, check interval and deleting APKs after install.
- Fire TV / Android TV support (leanback launcher, D-pad navigation).
- CI workflow that tests, builds and publishes APKs on version tags.
