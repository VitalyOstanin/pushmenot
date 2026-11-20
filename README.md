# Push Me Not

Push Me Not is an Android app for filtering notifications with local rules and keeping a searchable history.

## Contents

- [Install](#install)
- [Getting started](#getting-started)
- [Notification history](#notification-history)
- [Development](#development)
- [Release builds](#release-builds)

## Install

1. On Android 14 or later, open [GitHub Releases](https://github.com/VitalyOstanin/pushmenot/releases/latest).
2. Download the signed `push-me-not-<version>.apk` from the release assets.
3. Open the APK and follow Android's installation prompt. If prompted, allow the
   browser or file manager to install apps from that source.
4. Open Push Me Not and grant notification access from Settings.

The release assets include `SHA256SUMS.txt` so you can verify the APK you
downloaded. Install subsequent releases over the previous release to keep your
rules and history. Debug builds use a different signing certificate. Export
your rules and save a rules backup before switching from a debug build to the
release. Android requires removing the debug build first.

In **Settings → Updates**, tap **Check for updates**. When a newer stable
release is available, select **Install update** to download, verify and install
the signed APK from within the app. Android may ask you to authorize Push Me Not
as an installation source or confirm the update. You can also open the GitHub
release page and install the APK manually.

## Getting started

Open the journal from Push Me Not or from its shortcut in the notification shade. Under **In the notification shade**, review a suggested rule for a visible notification. The draft uses an exact title or text match and is scoped to the app and channel when available. If the app is not yet filtered, you can enable filtering and save the rule together. You can also create a rule from a saved journal entry. Rules can distinguish a promotion from an order or payment update posted to the same channel.

Android delivers notifications to the listener after posting them. A banner, sound, or vibration may occur before the app dismisses a matching notification. SMS notification cards can be filtered when the messaging app provides the relevant fields. Messages remain in the messaging app.

## Notification history

Each card starts with the sending app’s name and icon when they are available.

Use Settings to manage history size, content saving, notification access, background
settings, app language, and which apps are filtered. The journal records notifications
that Android delivers, including notifications from apps without filtering and those
that match a Hide rule. Content saving is on by default. Turning it off removes saved
content after confirmation. Text rules still evaluate incoming notifications for
selected apps. Rules can be imported or exported from the Rules tab. Open a .pushmenot rules file
from another Android app to review the import. The import can add new rules or
replace the current ruleset after confirmation.

The Rules tab also offers Version history. Every saved change creates a local
snapshot. You can compare a snapshot with the active rules and restore it. A
backup file contains the active rules and all snapshots. The backup excludes
notification history and system permissions.

Saving a ruleset also checks notifications that are still visible. Matching
notifications can be dismissed without counting them as new arrivals. Frequency
limits continue to apply as new notifications arrive.

The journal shows how many received notifications were hidden today and this
week. These counts start when the app starts tracking and survive a journal
clear. A quiet notification can open the journal directly when Android permits
the app to show notifications.

The app saves public text fields, individual messages, sender names, and action
labels supplied by Android. It can store a thumbnail from a delivered BigPicture
bitmap or resource. Thumbnails are at most 512 pixels on the longest side and use
up to 32 KiB each. The newest 2,048 thumbnails are kept, up to 64 MiB in total.
Thumbnail data is stored locally. The journal currently displays text and metadata.
Android can redact content or provide it only through an app-specific layout.
Action intents and external image URIs are omitted.

## Development

The app supports English and Russian. Project documentation uses English. Architectural decisions will be recorded as numbered ADRs.

From a workstation with Podman and an x86-64 Linux host, run:

- `tools/build.sh` to build the debug APK.
- `tools/test.sh` to run JVM tests.
- `tools/e2e.sh` to build the app, install it on an isolated Android 14 emulator, and run instrumentation tests. This requires access to `/dev/kvm`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The emulator report is written to `app/build/reports/androidTests/emulator.txt`.

See the [ADR index](docs/ADR/README.md) for proposed architectural decisions.

## Release builds

CI uses a pinned checkout and Java setup, Gradle dependency verification, and
builds with the Gradle task cache disabled. A version tag matching the app
version builds and signs a release APK, verifies the signing certificate, and
uploads it with its checksum to a draft GitHub Release. Publish the draft after
testing the signed APK. Continue using the same signing key for updates.
