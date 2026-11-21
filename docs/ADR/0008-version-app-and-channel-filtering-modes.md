# ADR-0008: Version app and channel filtering modes

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

Some apps place useful notifications and promotions in the same channel.
A user may want to keep only notifications matched by an explicit rule.
The current filtering selection and the behavior of unmatched notifications
are separate preferences.

## Decision

Evaluate explicit Keep and Hide rules first. For a readable, unprotected
notification with no matching rule, use its explicit channel mode, then
its app mode, then Keep. Apply this behavior only to apps selected for
filtering. Save app and channel modes in rule versions so that comparisons,
undo, snapshots, and archives can account for mode changes.

Version rule files independently of the database. A portable file carries
the explicitly selected app identifiers and the configured modes. Confirmed
imports can enable filtering for the listed apps in the same transaction as
the rule change. Keep the setting for apps that are not installed yet.

## Consequences

The listener can recheck a visible notification when the rules or filtering
settings change. Earlier rule files remain readable. Selecting app filtering
remains a local choice when the file does not contain that selection. System
permissions must be granted independently on each device.
