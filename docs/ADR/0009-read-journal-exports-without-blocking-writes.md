# ADR-0009: Read journal exports without blocking notification writes

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

A journal export with thousands of image previews can take several seconds.
The export reads a consistent snapshot so writes, removals, and pruning
cannot make the exported file internally inconsistent. The original export
held the database write transaction for the entire operation.

## Decision

Use the AndroidX bundled SQLite driver with write-ahead logging. Stream each
export from a read transaction on its own connection. Read previews for one
page in a batched query when the user includes images. Use the same Room
database and existing SQLite file schema for upgrades.

## Consequences

The listener can save new notifications during export. The export includes
the records and previews visible in its snapshot. SQLite retains the WAL
pages needed by the snapshot until the export finishes. The APK includes
the native SQLite library for each supported ABI.
