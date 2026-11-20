# ADR-0004: Record all delivered notifications in a bounded journal

Status: Accepted. Content retention superseded by [ADR-0005](0005-save-notification-content-with-a-global-setting.md).

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

The history must cover notifications from apps that do not use filtering rules.
Collecting every notification delivered to the listener can fill the journal
faster than collecting selected apps. The user needs control over its capacity.

## Decision

Record new notifications that Android delivers to the listener for the current
profile. Apply shared and individual rules only to apps selected for filtering.
Keep recording notifications after a matching Hide rule and record the observed
removal when Android reports it. Persist notification text only with explicit
per-app consent, independently of filtering.

Keep the journal in local storage as a bounded sequence of entries. The default
capacity is 10,000 entries. Insert and prune in the same database transaction.
The oldest entries by insertion order are evicted first. Lowering the capacity
evicts excess entries in the same transaction as saving the setting. Remove the
automatic age cutoff. Keep user-selected date filters in the journal.

This updates the retention decision in [ADR-0002](0002-store-history-on-device.md)
and the history scope in [ADR-0003](0003-scope-rules-to-selected-apps.md).

## Consequences

All delivered apps can appear in the same journal. Rules and frequency tracking
still operate on selected apps. A capacity change applies to notifications and
coverage markers together. History can span more than seven days when notification
volume fits within the selected capacity.
