# ADR-0007: Install signed updates through Android

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

[ADR-0006](0006-check-for-updates-on-demand.md) makes stable updates discoverable.
Users also need to install a release APK from the app while retaining the
browser workflow.

## Decision

After an explicit install action, use the expected uploaded APK asset from the
public release. Download it over HTTPS into private storage with a size limit,
then verify its SHA-256, package ID, version name, increasing version code and
installed signing certificate. Use Android's PackageInstaller to replace the
installed version. Request permission from Android only when the user chooses
the in-app installation path. Request a self-update without a second prompt
and handle Android's confirmation screen if required.

Keep the browser action available for manual updates. When release metadata
lacks a verifiable APK, offer the browser action. Preserve the on-demand check
and local processing of notifications and rules. This decision supersedes the
installation portion of ADR-0006.

## Consequences

In-app updates require permission to install apps from this source. Android
can require additional confirmation. The updater requires APKs signed with the
current certificate. A future certificate rotation needs an explicit policy.
