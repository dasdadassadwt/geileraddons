# Future Ideas

This is a planning list, not a promise of implementation or order. Status notes below reflect
source present in the current working tree; they do not mean a feature has been tested or released.

## Present in the current working tree

These ideas already have code and should leave the future backlog after review and release:

- **Inventory Buttons — present.** Place and configure macro buttons on the player inventory.
- **Collapsible Macro Categories — present.** Macros can be organized in nested, collapsible folders.
- **Automated Experiments — partial.** Automation covers Chronomatron and Ultrasequencer. Superpairs
  is not supported; decide whether it belongs in scope before closing this idea.
- **Macro input and control actions — present.** Key presses, hotbar selection, mouse clicks/holds,
  timed and start/stop input blocking, waits, and container slot/item clicks are available. Hotbar
  selection is guarded while an inventory is open, and held inputs are released on every run exit.
- **Multiple macro event stacks — present.** A macro can have independent key, world-region, and
  `On Call` stacks, with concurrent runs, per-stack re-entry protection, and visible run states.
- **Scratch-style macro editor — present.** The editor has categorized blocks, nested sockets,
  movable/zoomable stacks, a compact canvas-plus-dock layout, inline value previews, editable
  conditions, explicit target pickers, and block add/move/delete undo. Inline values are previews;
  detailed editing remains in Inspect.
- **Functions and macro calls — present.** Named Number/Text/Boolean function inputs use isolated
  call-local variables. Macro-call blocks wait for the target macro's `On Call` stack; imports
  include transitive dependencies and remap IDs. Runtime depth and cycle guards are in place.
- **World-placed macro regions — present.** Each macro can use a square, circle, or ring trigger
  with world binding, vertical tolerance, style controls, once-per-loaded-world behavior, and a
  repeat delay measured from the previous run's completion.
- **General module and the Cheats gate — present.** A **General** module holds the settings that
  belong to the mod as a whole. **Cheats** is a master gate, off by default: while it is off it forces
  the safe behaviour of the `Depth Check` off-state in Block ESP, Mob Highlight, Safari Floor Drops
  and Pest Highlighter, and it refuses any macro that is more than a single action. Gated rows stay
  visible, show the safe position, refuse clicks, and name Cheats in a tooltip. Stored values are
  never overwritten, so turning Cheats back on restores exactly what was set. **Check for Updates**
  moved here from the config file; the old top-level key seeds it once on the first load.
- **Chat-message macro triggers — present.** A macro event stack fires from a received chat line.
  Matching strips legacy formatting and is case-insensitive, contains by default with an optional
  whole-line mode, so rank and channel prefixes do not matter. Each stack has a minimum repeat delay
  and is suppressed while its own sent message is being echoed back, which is what stops a
  self-triggering loop. Chat stacks never run while a text/editor screen has the keyboard unless
  **General → Chat Triggers In Text Screens** is on; a refused start is recorded and written to the
  debug log, and **General → Replay Last Blocked Macro** (module hotkey or action) starts the last one.
  A chat stack also carries its own optional replay hotkey. Chat triggers are deliberately separate
  from a Chat condition, which is unchanged.
- **Conditional Macro Calls — present.** A **Call another macro** block can carry an optional
  condition. A false condition skips the call, logs it to the dev log, and continues with the next
  block; null means unconditional, so every macro written before this behaves exactly as it did.
- **Update checker download and changelog UI — present.** The same single `releases/latest` request
  now also reads the release body and `html_url`. When a newer release exists the Click GUI header
  grows one row with **Download** and **Info**; Download opens the release page through the existing
  confirmation screen, Info opens a scrollable panel inside the Click GUI showing the sanitized notes
  and the version the player has, closed by Escape or a click outside. The body is untrusted remote
  text, so it is stripped of Markdown and HTML, bounded in length, rendered as plain text, and never
  carries a click event. The URL is prefix-checked against this repository and falls back to the
  releases-page constant. Nothing is downloaded or installed, and no publish date is shown.
- **Garden plot borders — present.** Configurable borders distinguish client-confirmed infestation,
  clear evidence, unknown data, and stale observations; labels and exact visible counts are optional.
  The borders are now stationary 3D boxes drawn between a captured fixed world Y and a configurable
  wall height, instead of a flat line that followed the player's own height. Unknown plots stay
  dashed and infested plots keep their inset accent box.
- **Party Finder lookup reliability — present.** Roster changes request stats only for new party
  memberships, failures stay unavailable until an explicit retry, `/p list` is limited to bootstrap
  and recovery, and a confirmed departure clears per-membership state for a quick rejoin.
- **Title and Sound Steps — present.** Macros can show a styled title or play a registered sound.

## Planned features (specified, not implemented)

These two are larger than the items under "Recommended to implement next" and carry a spec because
the design decisions are already settled. The order below is a reading order, not a schedule.

### Dungeon secret waypoints

A **Dungeons** module that shows per-room secret waypoints, with the room list and every waypoint
individually configurable. **This is a re-implementation, not a copy.** Skyblocker is LGPL-3.0 and
its room data cannot be shipped inside a CC0 repository, so no Skyblocker source, JSON or coordinate
table is copied or redistributed: only the behaviour is ported, and the waypoint data comes from the
player. `REFERENCES.md` gets the source note when this is implemented, not before.

- **Room identity** is read from the SkyBlock dungeon map, parsed into a room grid, the way
  Skyblocker's dungeon manager and Odin's dungeon sources do it. Which room a waypoint belongs to is
  therefore worked out at runtime rather than pasted in from someone else's table.
- **Waypoint data** is recorded by the player in the same in-world editor the Dungeon helper uses, or
  imported from a package someone shares. One editor, not two.
- **Customisability.** Position, colour, shape, label and category per waypoint. Proposed categories
  to cover: entrance, superboom, chest, item, bat, wither, redstone key, lever, fairy soul, stonk,
  aotv, pearl, prince, and a default bucket for anything else.
- **Rooms.** A searchable room list, each room toggled on or off, with only enabled rooms rendering.
  Search matches the room name and type, and nothing renders in a room that is switched off.
- **Cheats gate.** Drawing through walls is the cheat: with Cheats off the waypoints are depth-tested
  and the option reads as forced safe.
- Open questions: whether a waypoint disappears once its secret is taken (which needs chest, lever,
  item and bat detection) or v1 keeps every waypoint visible; whether an external import format is
  supported; which map source drives room identity and what Master Mode and non-Catacombs dungeons
  do.

### Dungeon helper

A **Dungeons** module that walks a player through a dungeon one phase at a time, driven by
player-authored guides that can be shared.

**At runtime.** While in dungeons, the guide selected for the current floor activates on its own. The
mod works out the current phase from client-side observations only (see the phase table below), and
the guide for that phase decides what is shown: nodes drawn as their placed shapes and colours,
titles and subtitles fired by phase entry, time in the phase or an event in it, tracers or an arrow
towards the next node, and client-side chat hints. Nothing is sent to the server. A step-list HUD
panel and letting a node run one of the player's macros are both deliberately out of scope.

**Making a guide.** Create one in the module. A guide has a category per floor, only floors with
content appear, and each category carries a toggle for whether it takes part at all. Edit mode is
switched on from the guide and says so on screen. In edit mode a floating square tracks the block
being looked at, with an unlimited-range toggle that extends the client-side target raycast only — it
never changes vanilla reach and never sends anything. The bound key (default **Shift + right-click
with an empty hand**, rebindable) opens a small menu with **Delete** (only on a block that already
has a node), **Create**, **Undo** and **Redo**; **Create** opens the node screen with **Shape**
(Cube, Sphere, Text, Ring), **Position** with size and rotation, **Colour**, **Order**, **Phase** and
**Floor**. The editor is a separate screen with the world visible behind a translucent panel,
following the macro editor's conventions.

**Storage and sharing.** Guides are versioned JSON, bounded and validated on decode exactly like
macro packages, shared through the existing clipboard transfer route and kept as files in the config
directory. An import never overwrites a guide the player already has.

**Phases.** Split ids are stable and shared across guides, with an editable display name so a guide
can call a phase whatever it likes. Proposed per-floor ids, with the boss phases still to confirm
against live runs and the split implementations named below:

| Floor | Splits | Boss and sub-phases to confirm |
| --- | --- | --- |
| F1 | entry, clear, blood open, blood cleared | Bonzo: balloons, clones |
| F2 | same | Scarf: warrior summons, Scarf |
| F3 | same | Professor: guardians, Professor, chaos |
| F4 | same | Thorn: spirit animals, spirit bear, bats |
| F5 | same | Livid: finding the real Livid, clones |
| F6 | same | Sadan: giants, Sadan |
| F7 | same, plus terminals, devices, pillars, levers, arrow align, crystals | Maxor, Storm, Goldor, Necron |
| M1–M7 | as their normal counterpart | Master variants of the same boss phases |

**Open questions to settle before implementing:** the confirmed phase ids and boss phase names per
floor; what "Order" means exactly (working assumption: with Order on, only the current group is shown
and the number of groups still to come is displayed, groups are assigned in the node screen and
edited in the Click GUI); which events may fire a title; the edit bind needs a mouse-button bind
representation, because `ModuleKeybind` is keyboard-only today; the cap for unlimited-range editing;
whether a single guide may cover more than one floor; and whether individual nodes carry their own
enable toggle.

### Sources for the three dungeon entries

Skyblocker (<https://github.com/SkyblockerMod/Skyblocker>, LGPL-3.0), Odin
(<https://github.com/odtheking/Odin>, BSD-3-Clause), SkyHanni
(<https://github.com/hannibal002/SkyHanni>) and Devonian are references for behaviour — map and room
detection, split and phase detection, and the secret-waypoint category model. Their licences are
checked before any porting starts, no source is copied verbatim, and no third-party data file is
redistributed: the local implementation is independent Java and the dungeon data belongs to the
player. Each port gets its own `REFERENCES.md` section when it is implemented.

## Recommended to implement next

1. **Hypixel data conditions.** Add this after defining a small set of supported values and their
   source. Prefer a typed snapshot of data already visible to the client; report missing or stale
   values as unavailable rather than treating them as zero. Pest-trap tab widgets in
   [SkyHanni's feature list](https://github.com/hannibal002/SkyHanni/blob/beta/docs/FEATURES.md)
   are a useful example of the player-facing information, not a dependency to copy into GeilerAddons.
   The Chat condition's shaping is the precedent for how a new condition type is added: a sealed
   `MacroCondition` variant, a bounded codec entry, and an editor picker entry.

## Defer until there is a concrete use case

- **SkyHanni Pass Integration.** Clarify which pass and cooldown/progress state are meant, then
  identify a stable data source. The linked feature list describes a Crystal Hollows pass-purchase
  helper, but does not define a reusable data contract. Keep GeilerAddons usable without SkyHanni
  and avoid coupling to its internal classes.
