# ADR-0003: Scope rules to selected applications

Status: Accepted

## Contents

- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)

## Context

A useful rule may apply to more than one application. Users also need to inspect a particular application's history and the rules that apply to it without losing their main history filters.

## Decision

Keep the History, Apps, and Rules tabs. History merges the chosen applications in one chronological list. Apps opens with the selected sources and links each source to its history and rules. Rules separates shared rules from rules for individual applications.

Apply shared rules to notifications from any source selected for filtering. Apply individual rules to one package. Rank matching rules by action, Keep before Hide. Within each action rank the individual scope before the shared scope, then use the stored order. Partition frequency state by profile and package.

Read individual rules from version 1 documents. Write version 1 when all rules have an individual scope and version 2 with explicit scopes when any rule is shared. Keep source selection separate from rule transfer.

## Consequences

An individual exception can protect a useful notification from a shared hide rule. Enabling another source makes it eligible for the existing shared rules. A version 1 client rejects documents that contain shared rules. The history stores the scope of new decisions.
