# Changelog

## [1.4.5] - 2026-09-15

### Added

- Animations to the Click GUI.
- Three new Click GUI presets.
- Party Finder stats preview.

### Fixed

- SkyHanni incompatibility.

## [1.4.4] - 2026-09-14

### Added

- Added Party Finder Stats.
- Added party-list and dungeon-stats backends.
- Added per-floor Auto Kick settings for normal and master floors.
- Added duplicate-class checks and Ask Before Kick confirmation.
- Added Party Finder floor detection from Group Builder and selected listings.
- Added Party Finder Leave Party action and diagnostic messages.

### Changed

- Redesigned Party Finder chat output with responsive borders and compact mode.
- Changed Auto Kick numeric settings from sliders to text inputs.
- Added delayed party-chat explanations before automatic kicks.
- Added serialized profile fetching and Auto Kick actions to reduce chat and request spam.
- Improved profile, Magical Power, bank, inventory, UUID, and selected-profile parsing.

### Fixed

- Fixed F2 being detected as F7 from unrelated tab-list progression text.
- Fixed Auto Kick using the wrong floor policy.
- Fixed duplicate scan completion and repeated evaluations from cached results.
- Fixed the local party host being fetched during `/p list` scans.
- Fixed Auto Kick processing for players joining during an active scan.
- Fixed kick buttons appearing when the local player was not party leader.
- Fixed Party Finder borders being cut off at different chat widths and scales.

### Verification

- Full Gradle build passed with Java 25 and Minecraft 26.1.2.
