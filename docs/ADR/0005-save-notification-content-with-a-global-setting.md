# ADR-0005: Save notification content with a global setting

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

[ADR-0004](0004-record-all-notifications-in-bounded-journal.md) records
notifications from all apps and ties saved text to a per-app setting. The journal
is used to inspect notifications from arbitrary apps. Users need one control over
content retention, independent of which apps apply rules.

## Decision

Save delivered notification content by default. Keep live rule evaluation
separate from the archive. Save standard public text fields, available message
text and sender names, action labels, and bounded thumbnails of supported
BigPicture images. Omit Android-redacted data, executable actions, and external
resource references. Keep notification metadata with and without content.

Use one settings screen for journal settings, Android settings, and per-app
filter configuration. Turning off content saving clears the archived text,
search fields, and media in the same transaction as the change. A retention
revision carried by incoming events prevents a queued write from restoring old
content. Keep previous entries and the journal capacity on upgrade. This decision
supersedes only the content-retention part of ADR-0004.

## Consequences

Full available text can be searched locally, including text from apps that are
not filtered. Turning off the archive does not alter the text available to live
rules. The archive cannot reconstruct text the app did not previously store.
Media is kept separately with a per-image and aggregate storage limit. Journal
entry eviction also removes associated media.
