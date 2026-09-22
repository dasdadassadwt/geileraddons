# Changelog

## [1.5.2] - 2026-09-22

### Added

- **Block ESP** — configurable block highlights with connected shapes, labels, tracers, island filters, and incremental loaded-chunk scanning.
- **Inventory Buttons** — place macro buttons in the player inventory with custom icons, text, tooltips, and eligibility states.
- **Garden Plot Borders** — render garden plot boundaries and projected labels.
- **Automated Experiments** — add guarded automation support for supported experimentation-table games.
- **Macro editor and runtime features** — add scratch-style editing, nested folders, variables, scripts, titles, sounds, world regions, and additional input controls.

### Changed

- Expanded macro transfer format to version 2 while retaining version 1 import compatibility.
- Improved Click GUI motion, macro editing, HUD overlays, slot-id layout, and module keybind handling.
- Added bounded request handling and stale-result protection for dungeon and party data.
- Expanded offline coverage for macro execution, folders, rendering, Block ESP, Inventory Buttons, garden state, and party-finder reliability.

### Fixed

- Hardened party-finder statistics and lifecycle handling against repeated, stale, or incomplete results.
- Improved experiment solver milestones, click gating, and automation safety.
- Preserved safe client-side behavior when profile or eligibility data is unavailable.

## [1.5.1] - 2026-09-19

### Fixed

- Fixed Party Finder Stats breaking.
- Fixed the Experiment Table solver reporting the maximum click count too early.

### Added

- Added more nodes and possibilities to macros.

### Changed

- Reworked the macro editor's appearance.

## [1.5.0] - 2026-09-18

### Added

- **Experiment Solver** — Solves all Experimentation Table games.
- **Pest Highlighter** — Garden pest ESP with boxes, rings, tracers, and names.
- **Macros** — Editor with commands, waits, clicks, conditions, repeats, and island safety.
- **Developer tools** — Debug logs, Slot IDs overlay, and module hotkeys.

### Changed

- **Click GUI improvements**

### Fixed

- Fixed some performance issues.

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
