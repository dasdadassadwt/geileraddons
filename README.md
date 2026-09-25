<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

Fully client-side. Requests are limited to optional island detection, the update check, sparkling
party announcements, and optional dungeon profile lookups for Party Finder tools. Dungeon profile
lookups are cached and the mod never sends a kick unless Auto Kick is enabled and you are party
leader.

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-brightgreen)
![Loader](https://img.shields.io/badge/loader-Fabric-blue)
![License](https://img.shields.io/badge/license-CC0--1.0-lightgrey)

</div>

---

Open the menu in-game with **`/ga`**.

The **Dev → Slot IDs** module contains the optional slot overlay. It labels each inventory slot
with the menu id used by vanilla (not the backing-inventory index), which makes slot-based
workflows and puzzle reports reproducible. **Dev → Debug** independently enables the retained,
category/module-separated GeilerAddons logs under `logs/geileraddons/`.

## Modules

<details>
<summary><b>Party Finder Stats</b> — shows dungeon stats for joining players</summary>

<br>

When the first Party Finder player joins, the module sends `/p list` and fetches the current party
members except you. Already-checked players are not fetched again by later `/party list` output.
The queued floor is captured from the selected values in Hypixel's Group Builder when you click
`Confirm Group`, then accepted only when Hypixel confirms the queue. It displays a bold cyan
bordered card fitted to the current chat width with configurable Catacombs level, joined class
level, class average, magical power, secret average, floor PB, gear, and actions. The Click GUI
shows an inline sample card, and its ten stat toggles update both the preview and future cards
immediately. Hover over SA for total secrets and a class for all class levels. **Normal PBs** and
**Master PBs** have separate floor lists and separate hovers; the Party Finder card also shows the
queued-floor PB. `Kick` appears only for
the party leader. Joining someone else's listing adds one final **Leave Party** action after all
profiles finish.

If a profile cannot be fetched, the line says so and provides a green **PV** button that runs
`/pv <player>`. **Compact** collapses the card to one line. When profile lookup is unavailable, the
card says so instead of showing a row of unknown values. If the Hypixel Mod API switch is off, the
card adds an **Island detection API is off** status line. You can also fetch one player's card with
`/ga dstats "name"` (quotes are optional for a single player name); it uses the same display toggles.
Gear checks read Odin's wrapped compressed inventory payload; a missing or invalid inventory still
leaves only those gear fields marked unavailable.

</details>

<details>
<summary><b>Auto Kick</b> — checks Party Finder requirements per floor</summary>

<br>

Configure F1–F7 and M1–M7 separately. Each folded floor heading carries its Auto Kick switch, each
floor has an **Ask Before** toggle, and numeric requirements are entered as text. Requirements
include Cata, selected-class level, class average, secrets, secret average, MP, PB, bank,
Term/Hype/GDrag, and duplicate-class checks.

The PB limit accepts `m:ss` (for example, `6:42`) or legacy seconds; a player's PB must be that
time or faster. Enter `0` to disable the PB check.

Auto Kick only enforces requirements while you are party leader. Automatic kicks first wait a
random 1–2 seconds, send the reasons to party chat, wait another random 1–2 seconds, and then kick
if leadership and membership are still valid. Multiple actions are serialized. **Ask Before**
instead shows the reasons client-side with a clickable **Kick** button and schedules no command.
Missing API data never causes an automatic kick.
The profile retry window and retry interval are configurable under **Safety**; after the window
expires the mod stops retrying and offers a manual **Kick** action, with a reminder to ban or
ignore the player yourself if that is what you want.

</details>

<details>
<summary><b>i4 Helper</b> — Catacombs F7/M7 4th device</summary>

<br>

Takes the guesswork out of the 4th device puzzle.

- **Boxes the panel** — completed blocks in red, the live target in green.
- **Marks where to aim** — a bright dot on the exact spot for the current shot, so you're not eyeballing block edges mid-fight.
- **Shows the next shots too** — every still-open block gets a dimmer marker, so you can pre-aim instead of reacting.
- **Plays a jingle** when your team finishes a device, and hides the notification spam that buries it.
- **Recovers instantly when you land back on the plate.** Arriving part-way through a device, or stepping back on after the device itself went inactive, re-reads the live target instead of guessing a blank board.

Colours and marker sizes are all adjustable.

</details>

<details>
<summary><b>Tiki Helper</b> — Sneaky Tiki hunting in Torrhus</summary>

<br>

Sneaky Tikis are three stacked heads that have to be rotated into alignment by clicking them. This module handles finding them and telling you exactly what to click.

**Waypoints** — boxes every saved tiki spot, green if one is standing there and red if not. Markers stay put after the spot leaves render distance, so you keep your bearings while running a route. An optional tracer line points at the nearest solvable tiki.

**Solver** — floats a number over the head that needs clicking. `+3` means left-click it three times, `-2` means right-click it twice. It updates the instant a click registers, and handles the case where one head is hidden behind another block — you still get the direction, marked `+?`, because the count genuinely can't be known. The rest of the solve shows too, greyed out ahead of time, so you can see every remaining click instead of just the next one.

**Debug Logging** — off by default. Writes every rotation, click and sound to a file. This is how the solver's rule was worked out, and it's there for anyone who wants to check that rule against a live server.

The module switches itself on only in Torrhus, and sits idle everywhere else in SkyBlock — so it costs you nothing while you're doing something else. The card in the menu tells you where you currently are when it's idle.

</details>

<details>
<summary><b>Safari Floor Drops</b> — highlights drops in the Critter Safari</summary>

<br>

Boxes floor drops as they appear, so you spot them without sweeping the floor with your eyes.

There's no list of spots behind this. A drop is caught **when it spawns**, from the particle the server sends at it, and confirmed by the model it's made of — so it works on spots nobody has mapped, and a drop someone else already took stops being marked instead of leading you to nothing. Boxes clear themselves a few seconds after the drop goes, and immediately once you act on the block.

**Depth Check** decides whether walls hide the box. On means it behaves like anything else in the world; off means you see it through terrain.

The module only runs on the Safari and sits idle everywhere else, so it costs you nothing while you're doing something else.

</details>

<details>
<summary><b>Hideyho Finder</b> — boxes the Hideyho wherever it is</summary>

<br>

Sweeps the Haunted biome once a second for the Hideyho itself and boxes it through walls until it's gone.

**It only runs in Haunted**, that being the only part of the Safari a Hideyho spawns in, so it costs you nothing while you're working the other three. The card tells you when it's idle for that reason. Which biome you're in comes from the biome the server reports rather than from a map of coordinates, so there's nothing to go stale when Hypixel moves a wall.

**It looks for the critter, not for the places it hides.** A list of hiding spots goes stale the moment Hypixel adds one, can only ever check spots whose chunks happen to be loaded, and misses a Hideyho standing a few blocks off a listed spot — so there isn't one. Nothing needs triggering and no chat line needs catching: if it's in range, it's boxed.

It's recognised by its skin, with its name as a backup so a reskin doesn't quietly switch the module off.

**Debug Mode** boxes every fake player in range in a faded colour — that's how you tell "it wasn't there" apart from "it was there and wasn't recognised".

Scan rate and colour are both yours to set.

</details>

<details>
<summary><b>Sparkling Critter</b> — finds the rare variants, and can call them out</summary>

<br>

Sweeps the Safari for sparkling critters and boxes them through walls, with the species written above each one.

**It reads name tags.** Hypixel names the label above a rare one `Sparkling Rockmite` rather than `Rockmite`, and a name tag renders through terrain in vanilla anyway — so this points out something that was already on your screen, it just stops you having to notice it. A label is matched whole, so a dropped item called a "Sparkling Tepid Shard" is not mistaken for a Tepid.

Boxes follow the critter rather than its name tag: Hypixel floats that on a separate marker with no hitbox at all, and the mod resolves it back to the mob underneath — never to a player standing nearby.

**Telling your party.** Off by default. When it's on, the first time a sparkling comes within your **Range to Send** it posts `Sparkling Rockmite found at x:131 y:54 z:12` to party chat. Each critter is called out once, and one that was too far away when you first saw it still gets called out when you get closer.

**Debug** prints that line to your own chat instead of sending it, and never sends anything while it's on — so you can watch exactly what it would say without messaging anybody.

Colour, text size, an optional tracer line and the sweep rate are all yours to set.

</details>

<details>
<summary><b>Mob Highlight</b> — box any mob you name</summary>

<br>

Make as many highlights as you want, each with its own colours and its own text to match.

- **Match by name or by type.** Name matches what the mob calls itself, so `crypt ghoul` finds it whatever level tag Hypixel hangs off it. Type matches what kind of thing it is, so `zombie` catches every zombie regardless of name.
- **Colour codes and levels don't get in the way.** Matching runs on the readable text, ignoring case, so you type what you see rather than what the server sent.
- **Two colours per highlight** — outline and fill, set separately — plus its own depth check and its own scan rate.
- **The name you give it is drawn on the box**, in the real game font, so several highlights running at once stay tellable apart.
- **Its own switch, on its own heading.** Turn a highlight off without deleting it or opening it up.
- **Pick the islands it runs on.** Each highlight has its own **Islands** list, folded up inside it, covering every island the mod can recognise. **They all start off**, so you say where a highlight belongs rather than switching off the two dozen places you didn't mean. Where the island can't be named at all — single player, another server, or the moment before the handshake lands — the highlight stays idle until the official API supplies a recognised island. It never guesses from terrain or scoreboard text. Highlights made before this existed keep their old all-island selections until you narrow them.

Highlights are built in the settings panel itself: **Create Mob Highlight** adds an empty one, and each becomes its own foldable section you fill in. No separate screen to keep in step.

Use **Manage Folders** to organize highlights into nested groups. Folders are independent from the
Macros and Block ESP trees; renaming or moving one never changes another feature's folders.

</details>

<details>
<summary><b>Block ESP</b> — highlight registered block types</summary>

<br>

Create up to 32 entries, each selecting exactly one registered block. **Choose Block…** filters
both its readable name and technical registry ID, so you do not have to remember unusual block
names. Each entry has independent **Box**, **Fill**, and **Outline** switches, depth check, label, tracer, island list, and
**Connect Touching Blocks** option. Connected blocks of the same type share one exposed voxel
shape, label, and tracer; face-adjacent blocks connect, while edge and corner contact does not.

New entries start with every island off. Scans are centered on you and use the full effective render
distance by default. Turn on **Use Custom Range** to give one entry its own radius (32 blocks by
default); it is still limited by render distance. There is no match-count cap. Scanning advances in
bounded, distance-ordered batches over loaded chunks only, skipping sections whose block palette
cannot contain the selected type—no chunks are loaded and no network requests are made. The last
complete highlight stays visible while a refresh runs, avoiding the clear-and-pop flicker of a full
rescan. **Manage Folders** organizes entries in its own nested tree.

</details>

<details>
<summary><b>Pest Highlighter</b> — highlights every Garden pest</summary>

<br>

Recognises Garden pests from the textured player heads Hypixel puts on their ArmorStands, so it
does not depend on a name tag or a hard-coded list of positions. It covers every known pest
texture, including the Earthworm and Firefly variants.

The module only runs in the Garden. Box, animated ring mode, tracer and the `Pest: <type>` name
can be switched independently. Ring mode supports several phase-shifted rings and the tracer
selects the camera-facing point on the nearest moving ring, so it remains attached while the
animation runs. The box has separate outline/fill colours, alpha and outline width; rings have
their own colour, alpha, width, radius, count and speed. Depth Check applies to all world markers,
while the projected name remains a normal HUD label. HP is intentionally not shown because the
head marker does not provide reliable health data.

</details>

<details>
<summary><b>Macros</b> — build clear, randomized input workflows</summary>

<br>

Create a macro in the **Miscellaneous** category and open its workflow editor. The editor groups
nodes into **Actions**, **Flow**, **Timing & Conditions**, and **World**. On wide screens it shows
the node palette, a nested workflow tree, and a properties pane together; narrower screens switch
between those panes. If/Else trees show their **Then** and **Else** branches, and Repeat nodes show
their body. Select a branch or body before adding steps there.

Actions include commands/chat, captured key presses or holds, slot-id clicks, item-name clicks,
Escape, **Title**, and **Sound**. A Title step displays centered text with a chosen game font and
size, text color, optional background, and independent fade-in/hold/fade-out times. It does not
pause the workflow, and a later Title step replaces the title already on screen. A Sound step
chooses a registered game sound and plays it at its default volume and pitch. Key nodes use a
capture button and readable key name rather than an ID. Click Item supports
comma-separated names as alternatives, exact or partial matching, search scope, match number, mouse
button, and shift-click. A Hold key samples a randomized duration between its minimum and maximum,
separate from the node's pre-delay. Shift, Control, Alt, and Super can be captured as the key itself.
Wait uses an independent randomized range. **Repeat** can run a body a set number of times or forever;
**Repeat Until** checks its stop condition before each iteration and runs its body while the
condition is false. For example, Repeat Until with an **Item → Missing** condition and a comma-
separated partial-name list can click any matching item until none remain, with a delay on the Click
Item node. Conditions can use item present/missing, exact/partial matching, screen, slot, chat, and
world state, combined with AND, OR, and NOT. Wait Until resumes when its condition becomes true.

World Switch destinations are selected from a dropdown of named islands. Island names cannot be
typed manually. A switch to a different or unknown island stops the workflow safely. A macro can
also be restricted to selected islands and can run in the world, containers, or any non-text screen.
Each node has a labeled minimum and maximum pre-node delay in milliseconds; there is no hidden
macro-wide default delay.

Only one macro runs at a time. Starting another replaces the previous run; pressing the active
macro's hotkey cancels it. Duplicate hotkeys are rejected with a client-side warning. A missing
slot/item or unmet wait condition is retried for five seconds, then the run stops with a chat
message. Click **Set hotkey** and release a key to save it; **Escape** clears it, and the editor
shows a visible listening banner while capturing. The **Help** button gives step-by-step recipes for
loop behavior, condition composition, modifier-key capture, randomized holds, and the dropdown-only
island rule. The `/ga` command is registered locally, so Brigadier can suggest it and its
`dstats` subcommand while typing. Per-launch debug logs are available under `logs/geileraddons/`
when **Dev → Debug** is enabled; log sessions are retained rather than deleted automatically.

The **Enable Macro System** toggle is the master switch for all macro hotkeys and running
workflows; each macro also has its own **Macro Enabled** toggle. **Share / Paste Macros** opens a
multi-select manager that copies selected macros as a portable clipboard package and appends
packages as new macros without overwriting existing ones. New packages use transfer format v2;
format v1 packages remain importable. **Manage Folders** provides a separate nested macro tree.
Folder names may repeat because UI state is keyed by stable folder ID; deleting a non-empty folder
promotes its entries and child folders to its parent instead of deleting them.

</details>

<details>
<summary><b>Inventory Buttons</b> — run macros from the player inventory</summary>

<br>

Open **Visual → Inventory Buttons → Edit Layout** while in a world. The faint 18×18 grid follows
the vanilla inventory slot lattice and only exposes fully visible cells outside the inventory and
any open recipe book. Click an empty cell to choose an existing macro or create one; after editing a
new macro, its button returns to that original cell. Select a button to edit its appearance, custom
hover tooltip, and macro. Drag it or use **Move** and click a destination; **Delete** or
Delete/Backspace removes it. Saved placements are not silently rearranged if a GUI size changes:
invalid cells are marked in red, and **Reflow** explicitly moves them to the nearest free exterior
cells while reporting any that could not fit.

Buttons can use a registered item/block icon, short text, or a PNG selected from
`.minecraft/config/geileraddons/inventory-button-icons/`. The editor shows a live icon preview and
the hover tooltip; at runtime the tooltip also shows the macro name and any eligibility reason.
Only the standard player inventory is supported. When not editing, an unassigned placement is
hidden. If its macro is deleted, the placement remains so it can be rebound.

Buttons store the macro's stable numeric ID and use the macro's enable switch, trigger context,
island filter, and the Macro System master switch. Ineligible buttons stay visible but disabled,
show the reason on hover, and consume clicks so an inventory slot underneath cannot be activated.
Their layout is local and is not included in shared macro packages.

</details>

<details>
<summary><b>Tree Tracker</b> — gift counting for Helix, Fig and Mangrove</summary>

<br>

Reads the gift message out of chat and keeps score, so you can tell whether a spot is actually worth farming.

**The panel** shows gifts per hour, the running count, and how long you've been at it — for whichever tree gifted last, so it follows you around without any switching. It appears when a gift lands and takes itself off screen once you stop, so it isn't sitting there while you're doing something else.

**It knows when you stopped.** After a set idle time the clock freezes and the panel hides. The next gift picks up where it left off — same session, nothing lost. Time spent walking somewhere else doesn't count against your rate. The timeout is yours to set.

**Session and all-time.** A session runs until you reset it or restart the game; either way its gifts roll into the all-time totals rather than disappearing. Flip the panel between the two.

Drag the panel wherever you like with **Move Elements** in the menu.

</details>

<details>
<summary><b>Tree Broken Notifier</b> — a title when a tree comes down</summary>

<br>

Flashes a title across the screen when you fell a tree or one gives you a gift — so you can keep your eyes on the trees instead of on chat.

**Two separate titles.** Gifts get one, **TIMBER!** and **PETALFALL!** get another, each with its own wording, sound and pitch, so you can tell them apart without reading. They really are separate events — a felling can happen without a gift and can fire more than once for one tree — and each has its own switch if you only want one of them. A gift block that names its tree on several lines still only shows one title.

Size, on-screen time and volume are shared. Write `{tree}` anywhere in either text and it becomes Helix, Fig or Mangrove.

**Hide the chat spam.** Each side can suppress its own message: the whole gift block — bars, header, rewards line and all — or the TIMBER!/PETALFALL! line. Hidden gifts are still counted; hiding only affects what you see. Off by default.

</details>

<details>
<summary><b>Theme</b> — one look for the whole mod</summary>

<br>

Everything the mod draws — the menu, the tracker panel, the notifier — reads from a single theme, so there's one place to change how it all looks.

Five colours define it: background, border, accent, text and muted text. Every hover tint, card fill and slider is worked out from those, so you can't end up with half of the GUI on the old scheme. Text sitting on the accent flips between black and white on its own, so a pale accent doesn't leave unreadable labels.

Seven presets to start from: **Tracker** (the flat translucent look, and the default), **Amethyst** (the original purple), **Midnight**, **Forest**, **Aurora**, **Ember**, and **Orchid**.

In-world colours — waypoint states, solver directions, device highlights — stay separate. Those are signals, not decoration, and a theme has no business repainting them.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. The **switch** turns one on; **clicking anywhere else** on the card opens its settings — either mouse button works.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Colours open a proper picker: drag the square for shade, the strip under it for hue, the one below that for transparency, or type a hex code straight in.
- Small numeric ranges (maximum 50 or below) are draggable sliders with a live readout. Larger
  ranges use a direct text input so values such as delays and scan intervals can be entered
  precisely; press Enter to apply them.
- In **Dev**, **Debug** controls diagnostic logs and **Slot IDs** independently shows the runtime
  container slot indices used by macro slot-click steps.
- **Move Elements**, at the bottom of the category list, opens a screen where you drag HUD panels into place. Positions are kept as a share of the screen, so they survive a resolution or GUI-scale change.
- The panel scales to your screen and reopens wherever you left it.

Macros, Mob Highlight, and Block ESP each have an independent nested folder tree. New and legacy
entries belong to the root until moved. Deleting a populated folder promotes its entries and child
folders to its parent; folder names can repeat because UI state uses stable IDs. Inventory Buttons
are a spatial layout, not a multi-entry folder list.

</details>

## Settings and data

Your settings live in `.minecraft/config/geileraddons/config.json`. Delete it to reset everything.

**Update check:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. It never downloads or installs anything. To turn it off, set `"checkForUpdates": false` in that config file.

**Island detection:** modules that only apply on one island need to know which island you're on, so the mod subscribes to the official Hypixel Mod API. The shared API handles the greeting and registration, then reports the island whenever you change server. One config-file key controls it:

- `"hypixelModApi": false` turns it off. Island-gated modules then stay idle and say so; no terrain or scoreboard guess is allowed to activate them.

## License

[CC0 1.0](LICENSE) — public domain. Do whatever you like with it.
