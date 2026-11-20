# ADR-0001: Filter published Android notifications

Status: Proposed

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

An app can post useful and promotional notifications to the same Android channel. Users need to filter specific cards and inspect the results in a journal. An app can also have its notification permission turned off before Push Me Not receives any cards from it.

## Decision

Use the public Android notification listener API with explicit user consent and a list of selected sources. Include launcher apps in the source picker before they appear in history. Open Android notification settings for the package by intent and retain a separate setting for filtering the app in Push Me Not.

Evaluate card observations in order with the same engine used for preview. Keep system-sensitive or ambiguous cards. Store the rule decision before requesting dismissal. Track removal events as observations and keep them distinct from the dismissal request.

## Consequences

The listener receives cards after publication, so the system may show a banner or play a sound first. SMS can be handled through the card published by the messaging app. The message remains in the messaging app, and direct SMS handling requires a separate decision.
