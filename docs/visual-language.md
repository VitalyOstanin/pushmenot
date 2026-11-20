# Visual language

## Contents

- [Purpose](#purpose)
- [Color roles](#color-roles)
- [Layout and typography](#layout-and-typography)
- [States and accessibility](#states-and-accessibility)
- [Localization](#localization)
- [Interface copy](#interface-copy)

## Purpose

Use a light, quiet interface that makes filtering decisions easy to read. Keep the app's filtering switch and the action that opens Android notification settings visibly distinct. Use fictional notification content in examples and screenshots.

## Color roles

| No. | Role                 | Color                                      |
| --- | -------------------- | ------------------------------------------ |
| 1   | Primary text         | `#192824`                                  |
| 2   | Secondary text       | `#435B51`                                  |
| 3   | Background           | `#E9EFEC`                                  |
| 4   | Screen surface       | `#FAFCFB`                                  |
| 5   | Card surface         | `#FFFFFF`                                  |
| 6   | Primary action       | `#225D45` background, white text           |
| 7   | Secondary action     | `#527766` outline, `#194E39` text          |
| 8   | Warning              | `#FFF3DF` background, `#192824` text       |
| 9   | Keyboard focus       | `#F0B429` outline                           |

Use a filled button for the primary action and an outlined button for system navigation and secondary actions. Pair status colors with readable labels. Use the warning surface for missing access or incomplete history.

## Layout and typography

Use the Android system typeface. Use 22 sp for screen titles, 16 sp for body text, 14 sp for supporting text, and 13 sp for navigation labels. Respect font scaling and allow labels to wrap.

Use a 4 dp spacing grid, 16–20 dp side padding, and 12 dp between cards. Use 14 dp card corners and 12 dp button corners. The mock phone frame uses 24 px corners. Button and switch touch targets are at least 48 dp.

Keep one clear screen title. Place a relevant warning before the content it qualifies. Put decision details next to the relevant history entry.

## States and accessibility

Use distinct labels for permission, filtering, text retention, rule decisions, and the removal observed by Android. A return from system settings does not confirm a permission change. Show a gap in history explicitly and indicate when rule preview is incomplete.

Give buttons and switches accessible names. Expose switch state to assistive technology and provide a visible keyboard focus indicator. Verify screen-reader navigation, system font scaling, and contrast in the Android implementation.

Present Import rules and Export rules as distinct actions on the rules screen. Show the proposed changes before applying an import. Explain what the exported file includes.

## Localization

Use the terminology in the [English and Russian glossary](../GLOSSARY.md). Keep user-facing strings in Android resources. Review layouts and action labels in both languages at narrow widths and increased font sizes.

## Interface copy

Read the title, message, and action together against the visible state. Describe the current state, the action the user can take, or both. When describing a future result, name the action or condition that enables it. Update the explanation when a switch changes. Distinguish proposed results in previews from outcomes that have already occurred. Review the same state and action in both languages.
