# ADR-0002: Store notification history and rules on the device

Status: Proposed

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

Filtering requires potentially sensitive content. The journal needs durable event records, including updates, decisions and observed removals. Users need to move rules between devices.

## Decision

Store journal rows and ordered rules in a private Room database, with app and time indexes for paging. Retain text only with per-source consent. Apply age and count limits, clear derived search fields when text consent is revoked, and exclude data from backup and device transfer. Transfer rules through explicitly selected versioned JSON files.

## Consequences

Text search and text-based preview depend on retained content. The app marks coverage gaps and can leave the result of a dismissal unknown. Rule files may contain personal search terms and should be handled as user data.
