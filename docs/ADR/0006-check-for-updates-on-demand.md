# ADR-0006: Check for updates on demand

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

The app is installed from GitHub Releases. Users need to discover whether a
new stable APK is available. The notification journal and rule processor handle
sensitive device data and run locally.

## Decision

Make one unauthenticated HTTPS GET to the project's public GitHub Releases API
when the user taps Check for updates in Settings. Use a fixed host and path,
bounded response size and connection and read timeouts. Compare the published
stable tag to the app's installed version as numeric version components. When
a newer release is found, offer its GitHub release page in the browser.

Keep the update request independent of notification access, journal storage and
rule processing. With the fixed request the app sends no notification content,
rules, installed app version or app-controlled identifiers. Android's Internet
permission enables the user-initiated check. Keep the result in memory and leave
downloading and installing the APK to the user.

## Consequences

Filtering and the journal work without a network connection. GitHub sees
ordinary connection information, including the source IP address. The user
can retry if the API is temporarily unavailable or limits requests. A private
repository or a repository without a stable public release has no available
release through this request.
