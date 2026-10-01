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
workflows and puzzle reports reproducible. Its **Label Text Color** is configurable while the dark
badge background stays fixed. **Move Elements** includes a separate sample badge preview for
placement; moving that preview does not move the actual per-slot badges. **Dev → Debug** independently
enables the retained, category/module-separated GeilerAddons logs under `logs/geileraddons/`.

Features identified as **Unreleased** below are present in the current source tree but are not part
of the last published release.

## Installation and compatibility

GeilerAddons 1.6.0 targets the **client** on Minecraft **26.1.2**. The Fabric instance needs Java 25,
Fabric Loader 0.19.3 or newer, Fabric API for Minecraft 26.1.2, and the Fabric implementation of the
Hypixel Mod API (version 1.0 or newer). Install the GeilerAddons jar and those dependencies in the
client's `mods` folder. GeilerAddons is a client mod and is not intended for server installation.

## Modules

<details>
<summary><b>General</b> — the mod-wide switches: macro chat, island detection, updates</summary>

<br>

**General** in **Miscellaneous** holds the settings that belong to the mod as a whole rather than to
one overlay. Cheat-style options are controlled directly where they are used; there is no separate
master toggle that overrides their individual settings.

**Chat Triggers In Text Screens** (off by default) lets chat-triggered macro stacks run while a chat
box, editor or menu has the keyboard. Leave it off and chat can never make a macro type into what you
are writing.

**Show Availability Notices** controls the inactive-reason text shown on module cards and their
tooltips. Turn it off to keep the grid quieter; module names, descriptions, and settings remain
visible.

**Macros → Replay Last Blocked Macro** is an explicit settings button. It starts the chat-triggered
stack that was most recently refused — the usual case being a message that arrived while chat was
open — and explains itself when there is nothing to replay. Individual chat stacks can also have
their own replay hotkey.

**Check for Updates** lives here now instead of in the config file. The old top-level
`checkForUpdates` key still seeds it once on the first launch after the move, so nobody loses their
choice.

</details>

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
`/ga dstats [player-name]`; leaving the name out looks up your own account. While entering a target,
Tab suggests matching names from the current server's listed players. Names must contain 1–16
letters, digits, or underscores. The command uses the same display toggles.
Gear checks read Odin's wrapped compressed inventory payload; a missing or invalid inventory still
leaves only those gear fields marked unavailable. Profile requests are paced and a brief HTTP 429 gets
one retry. If the service keeps rate-limiting or returns incomplete data, Auto Kick leaves the name
unverified and issues no kick; use **Retry Stats** after the limit clears.

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
<summary><b>Experimentation Solver</b> — reads Experimentation Table boards</summary>

<br>

The Experimentation Solver recognizes **Chronomatron**, **Ultrasequencer**, and **Superpairs**. It
shows the sequence or discovered card information in a configurable overlay and can prevent clicks
that do not match the current sequence step. Preview depth, colours, dimensions, and the selected
sound, pitch, and volume are configurable. **Max Click Alert** (on by default) plays the selected
sound once when an experiment reaches its maximum-click milestone. **Complete Sounds** also enables
that same milestone sound. There is no chat alert. Its input assistance is separate from
**Auto Experiments**; Superpairs is manual-only.

</details>

<details>
<summary><b>Auto Experiments</b> — replays supported experiment sequences</summary>

<br>

Auto Experiments can replay **Chronomatron** and **Ultrasequencer**. Choose the games to automate and
set the first-click and between-click delays. **Superpairs is not automated.** Chronomatron continues
to use the shared experiment session. Experimentation Solver and Auto share one experiment-board
owner and session, so enabling both does not create duplicate board observations. Ultrasequencer Auto
consumes the shared solver's complete immutable sequence view and keeps a separate timed execution
cursor; it does not parse a second board sequence or treat vanilla click dispatch as server
acknowledgment. Each
click is guarded against the current screen, menu, session, and slot. Manual input, an incomplete or
changed solution, or a refused/uncertain dispatch pauses the executor without retrying a click. Its
diagnostics record solver progress, Auto cursor/delay state, ownership, pause reason, and dispatch
result; these do not establish live server acceptance. The shared solver logic/model and Chronomatron
path were not changed by this Auto integration; Chronomatron remains on its existing scheduler. While the
matching Ultrasequencer is open, Experimentation Solver also shows Auto's cursor for the exact active
screen, menu, session, and solution; this is display-only and does not affect manual click protection.
Experimentation Solver's **Max Click Alert** plays the selected sound once at the maximum-click
milestone. The alert defaults on, and **Complete Sounds** also enables the same milestone sound.

</details>

<details>
<summary><b>Dungeon Guide</b> — run player-authored Catacombs route guides</summary>

<br>

**Unreleased.** Each floor has one named route containing every built-in and saved custom phase.
Legacy routes migrate into that route in phase order, with their original route and step order
preserved within each phase; the original files are archived for recovery. The editor groups steps by
phase and keeps the selected step's label, position, phase, conditions, and shape in one inspector.
Appearance and searchable macro controls are collapsed until needed. Add a step at your feet or at
the block under your crosshair. Box, ring, beacon, and text-only shapes are available. World labels
are plain text without a backplate.

Choose **ALL** or **ANY** for manual advance, radius, delay, exact full chat line, and detected phase
conditions. Only the active step is marked and traced; after its conditions pass, the next step appears.
Turn on **Always Display All Route Steps** to keep every step in the active phase visible after progress
advances. The module's normal keybind opens the editor. In the editor, **Simulate** cycles through the
floor's phases and filters the route list to the selected phase without changing a live run. Inspector
fields have explanatory tooltips, and outline, fill, and label colors use the Click GUI color picker.
An optional linked enabled macro runs its **On Call** stack when a step advances. Each Ring step has
its own ring count, height, radius, speed, line width, and optional filled interior in the advanced
appearance section; these controls appear only while that step uses the Ring shape. Its **Marker
size** scales the ring height and radius. Filled interiors start off. Older routes with shared ring
settings migrate those values to each existing Ring step; imported older routes receive the same
migration. Guide status remains an ordinary HUD panel.

Dungeon presence and floor are read from the displayed scoreboard independently of Party Finder
Stats and Auto Kick. When the dungeon is detected but its floor is unknown, Dungeon Guide stays
inactive instead of assuming F1. Use its **Choose Dungeon Floor** action to open the floor picker.
The manual choice remains until leaving the dungeon,
then automatic detection resumes. The **Edit Guide Stages** action opens the per-floor stage manager:
choose a stage manually or return to automatic detection, and add, reorder, or remove custom stages
with exact-chat, location-radius, or timer triggers. The built-in ordered stage model starts with
**Entry**, **Clear**, **Blood Open**,
**Blood Clear**, and **Boss Entry**, then adds floor-specific boss steps: **Bonzo** on Entrance;
**Bonzo's Sike/Bonzo** on F1; **Scarf's Minions/Scarf** on F2; **Guardians/Professor/Transformed
Professor** on F3; **Thorn** on F4; **Livid** on F5; **Terracottas/Sadan's Giants/Sadan** on F6;
and **Maxor/Storm/Terminals/Goldor/Necron**, plus **Wither King** on M7. **Complete** ends each
stage list. These are available route groups and selectable stages; they do not all have automatic
chat evidence. Chat recognition covers run start, blood opening/clearing, boss entry/defeat, and
specific dialogue or terminal milestones on supported floors. Saved custom phases and older broad
Blood/Boss phase IDs are retained during migration. The route editor provides built-in and saved custom phases;
route markers are depth-tested by default, and their **Draw Through Walls** option is a direct choice. Scoreboard
formats, stage chat, and floor transitions still need live verification.

</details>

<details>
<summary><b>Box Doors</b> — highlight dungeon doors</summary>

<br>

**Unreleased.** Normal passable doorways stay boxed; closed Wither and Blood doors are boxed until
opened. Closed door boxes are 3×4×3 and centered on the door plane. Normal, Wither, and Blood doors
each have separate fill and outline switches, colors with opacity, and through-wall options. Wither
and Blood doors default to red without the matching key and green after that door's key is carried or
the server reports its pickup. Wither and Blood key state is tracked separately.
Normal doors are shown only at the room you occupy. The Wither-door and Blood-door tracers each have
an independent enable switch and share color, width, and through-wall controls. Each tracer starts
only from a validated room whose full map footprint borders its selected closed door. After leaving
that room, its last eligible target remains for at most ten seconds, then hides; a changed or opened
door, dungeon exit, floor change, or world change clears it. The Wither trace follows the observed
door route toward Blood when available and falls back to the nearest loaded Wither door while the
route graph is incomplete. The Blood tracer is suppressed while an eligible Wither target is being
traced, including its short retained grace, so both lines are not shown at once. An unrelated closed
Wither that is not the active target does not block a directly eligible Blood tracer.
The Blood trace ignores the door-box render-range cap; the visible Wither-door cap does not limit
either tracer. The scanner works in bounded slices of already-loaded chunks and does not request new
chunks.
Enable **Diagnostics → Debug Door Tracing** to log the transition and door counts when the tracer
needs investigation; this module's diagnostic toggle works independently from **Dev → Debug**. Door
appearance and tracer accuracy need verification during a live dungeon run.

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
<summary><b>Dungeon Mob ESP</b> — highlight starred mobs and minibosses</summary>

<br>

One module keeps independently switchable **Starred Mobs**, **Minibosses**, and **Fels** settings
groups; their group-header switches work without opening the settings page. The existing text-based
**Mob Highlight** remains unchanged. All groups share one **Depth Check** control, which starts on.
All three dungeon groups are active only during the pre-Blood **Clear** phase. Fel highlights also
stop when the current room is cleared. **Fels Only Current Room** checks the confirmed room both when
collecting and drawing Fels. A Fel is tracked as an Enderman when it appears; if starred, its highlight
starts with that first spawned body. **Only Starred Minibosses** excludes regular minis, including
packet-recovered Shadow Assassins without star evidence.
Starred mobs are searched across rooms within their range, which starts at 128 blocks; their default
style is **Chams** with a five-block outline width. Each target group has its own **Only Current Room**
filter, off by default. The Starred and Miniboss groups keep their style, outline/fill colors, and ring
controls under **Appearance**; ring controls appear only when **Ring** is selected. Starred mob name
stands are associated to bodies using server entity ordering when available, then a uniquely closer
nearby body; an ambiguous label is skipped instead of highlighting a neighboring mob. Shadow Assassin
player-info and spawn packets are correlated by exact profile UUID/name, with body resolution retried
until the entity is loaded and tracking cleared on despawn, world change, or disconnect. Turn on
**Debug Target Matching** in the module's **Diagnostics** group to log candidate, match,
room, and range counts when diagnosing a target; this toggle works independently from **Dev → Debug**.
Minibosses include Shadow Assassins, Lost and Frozen Adventurers, Diamond Guy, Angry Archaeologists,
and King Midas. A Shadow Assassin with the exact NPC profile name is also recognized without its
nameplate and uses a full 0.8×2-block box. The miniboss tracer remains available.

With **Highlight Stationary Fels** enabled, every resolved stationary Fel with a nearby **Fels** name
source gets an independent highlight; no starred-mob sighting is required. Choose its own **Fel Style**,
outline/fill colors, outline width, ring controls, and tracer color/width in the **Fels** group. The
marker follows the reference's nominal approximately 0.6-block waypoint bounds, rather than the Fel
body's full hitbox. A moving Fel uses the existing Starred style only when its nameplate has a star.
Fel range follows **Starred Range**.
Current/max health labels (for example `16k/25k❤`) and supported dungeon level,
attribute, star, and private-use glyph prefixes are normalized before matching the exact miniboss/Fels
roster, including labels such as `Healthy Fels`.

Fel detection still depends on a `Fels` name source being present and associable with its living body;
there is no independent texture classifier for a nameless dormant floor skull. Whether Hypixel exposes
such an object's name before it moves remains unverified in-game. If one is missed, enable the module
and **Debug Target Matching** under **Dungeon Mob ESP → Diagnostics**; the session log is written to
`logs/geileraddons/<session timestamp>/Dungeons/Dungeon_Mob_ESP.log` and records nearby named candidates,
body matches, and room/range rejections (not raw skull texture/equipment data).

Remembered mobs remain highlighted at their updated position while the entity is alive and stays in
the detected connected room, even if its name match or room snapshot briefly drops. The footprint is
retained while the player remains inside it; a confirmed room exit, death/removal, room clear, or
expiry clears the target. When the
entity's anchor chunk unloads, its last bounds are retained for up to ten seconds.

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
<summary><b>Infested plot</b> — fixed 3D boxes around Garden plots</summary>

<br>

Draws a box around every plot, coloured by what the client actually knows: confirmed infested,
confirmed clear, or unknown because the evidence is missing or too old.

**Where the plot data comes from.** The main source is the **Pests** tab-list widget, which names every
infested plot in the Garden whether or not it is near you — so a plot two fields away still shows its
border. That widget has to be enabled in game (`/widget` → Pests). If it is off, the module keeps
whatever it already knew and never treats a silent widget as "the Garden is clean". A plot listed as
infested without a count shows as infested rather than as zero pests, and the nearby-pest scan stays
as a fallback for the plot you are standing in.

**The boxes stay put.** Each border is a 3D box drawn between a fixed world height and a chosen wall
height, so it is a landmark you can navigate by — it does not ride your eye level while you walk or
jump. The floor is taken from you automatically once, the first time you are standing on something in
the Garden; **Capture Current Y** sets it yourself at any time, and **Wall Height** controls how tall
the boxes are.

Unknown plots are dashed rather than solid, so expired evidence cannot read as confirmed clear.
Infested plots get a second, inset box of the same height. Labels sit on top of the box and are
optional, as are clear and unknown plots themselves. Plots far from the camera are not drawn and only
the nearest few are labelled at once, so a full grid does not cost a frame every tick.

**Island detection has to be on.** Like every island-gated feature, this one needs the official
Hypixel Mod API, which is **Miscellaneous → General → Island Detection API**. The mod never guesses
your island from the scoreboard or the terrain, so if that switch is off the module says so in chat
rather than doing nothing in silence.

</details>

<details>
<summary><b>Macros</b> — build clear, randomized input workflows</summary>

<br>

Create a macro in the **Miscellaneous** category and open its workflow editor. The editor is titled
**Macro Editor** and groups nodes into **Actions**, **Control**, **Player Input**, **Inventory**,
**Display**, **Data**, **Reusable**, and **World**. The Inspector is a separate panel on the right of
the canvas. Scroll over the canvas to zoom around the pointer; the palette and Inspector keep their
own list scrolling. Long node text expands the node to show the full value, and the canvas can pan to
reach that width. Zoom ranges from 1% to 100%; saved values migrate proportionally from the former
range and clamp at 100%. Drag previews show the whole node at its proposed drop location. If/Else trees show
their **Then** branch and an optional **Else** branch; Repeat selects
count, forever, or until mode in one node. Select a branch or body before adding steps there.
Detached square comment notes can be placed anywhere without joining the macro chain.

Actions include one **Send Message** node for chat and commands (a leading `/` runs a command),
captured keyboard/mouse/hotbar input in one **Press or Hold Key** node, slot-id clicks, item-name
clicks, Escape, **Title**, and **Sound**. Scroll was removed from the new-node palette; old saved
Scroll steps still run. A Title step displays centered text with a chosen game font and
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
world state, variables, and Hypixel state, combined with AND, OR, and NOT. Wait Until resumes when
its condition becomes true.
**Call another macro** waits for that macro's **On Call** stack and can carry its own condition:
with one set, the call is skipped when the condition is false and the workflow continues with the
next block, so a gated call never has to be wrapped in an If/Else.

Each macro node category has its own editable color in **Macros → Macro Node Colors**. With
**Theme → Theme Macro + Inventory Colors** enabled, those category colors are tinted toward the
selected theme while staying distinct; turning it off restores the editable colors.

**Hypixel state conditions (unreleased).** These compare locally observed island, confirmed
Catacombs floor, party membership/size/leader/member, and Garden pest status or count for the current
or a selected plot. They use existing client snapshots and make no extra network request. Missing
or stale data stays unknown. If the complete condition cannot be resolved, the workflow stops with
an unavailable-data message instead of treating the value as false or zero.

**Chat event stacks.** Besides a hotkey and a world area, a macro can have a stack that fires from a
received chat line: give it the text to look for and it starts whenever a line contains it (or
matches the whole line, if you switch that off). Matching ignores Minecraft formatting codes and
case, so a rank or channel prefix in front of the message does not matter, and action-bar lines count
too. Each chat stack has a **minimum repeat delay** so a repeated line cannot restart it constantly,
and it will not react to its own message being echoed back by the server. Chat stacks do not run while
you are typing: **General → Chat Triggers In Text Screens** overrides that, and
**Macros → Replay Last Blocked Macro** starts whichever stack was most recently refused. A chat
stack can also be given a **replay hotkey** of its own, which starts it by hand at any time.

World Switch destinations are selected from a dropdown of named islands. Island names cannot be
typed manually. A switch to a different or unknown island stops the workflow safely. A macro can
also be restricted to selected islands and can run in the world, containers, or any non-text screen.
Each node has a labeled minimum and maximum pre-node delay in milliseconds; there is no hidden
macro-wide default delay.

Event stacks run independently: a macro's key, world-area, chat and `On Call` stacks each have their
own run state, concurrent runs are supported, and re-entry is prevented per stack rather than per
macro. Starting another stack does not replace one already running; pressing a stack's own hotkey
while it runs is ignored. Duplicate hotkeys are rejected with a client-side warning. A missing
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
packages as new macros without overwriting existing ones. The current source writes the unreleased
transfer format v6, which preserves unconnected editor blocks; v1–v5 packages remain importable,
with fields they do not contain using their defaults. **Manage Folders** provides a separate nested
macro tree.
Folder names may repeat because UI state is keyed by stable folder ID; deleting a non-empty folder
promotes its entries and child folders to its parent instead of deleting them.

</details>

<details>
<summary><b>Inventory Buttons</b> — run macros or send text from the player inventory</summary>

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
Choose **Text action** when a button should not start a macro. Enter `/gfs ender_pearl 16` to send
that command, or enter text without `/` to send an ordinary chat message. Text actions save with the
button placement and remain independent from the macro catalog.
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

**Icons → Icon color** is a saved RGB tint for approved monochrome module-card icons. It starts at
the Theme Text color; selecting a preset updates it to that preset's Text color, and you can edit it
afterward. All module-card icons use this tint; the General and Theme toolbar buttons keep their
dedicated toolbar artwork and colors.

Nine presets to start from: **Tracker** (the flat translucent look, and the default), **Amethyst** (the original purple), **Midnight**, **Forest**, **Aurora**, **Ember**, **Orchid**, **Ice Glass**, and **Rose Glass**.

In-world colours — waypoint states, solver directions, device highlights — stay separate. Those are signals, not decoration, and a theme has no business repainting them.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. Click the card to open its settings; the **switch** and keybind control keep their own actions. Each module has a bundled icon; **Theme → Icons → Icon color** tints approved monochrome card icons.
- Smooth heart icons on categories and module cards mark favorites. Favorite categories and modules
  sort to the top, with multiple modules ordered alphabetically. The fixed GitHub, General, Theme, and
  Move HUD Elements toolbar buttons are not favoritable; their icons stay centered in their buttons.
- On compact cards, the heart and enable switch have separate non-overlapping click targets, and the heart, switch, and keybind align on one control column. Descriptions are larger while card dimensions stay the same.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Colours open a proper picker: drag the square for shade, the strip under it for hue, the one below that for transparency, or type a hex code straight in.
- Small numeric ranges (maximum 50 or below) are draggable sliders with a live readout. Larger
  ranges use a direct text input so values such as delays and scan intervals can be entered
  precisely; press Enter to apply them.
- In **Dev**, **Debug** controls diagnostic logs and **Slot IDs** independently adds the runtime
	container slot index to each slot as a small badge with configurable label text color.
- **Move Elements**, at the bottom of the category list, opens a screen where you drag HUD panels and the Slot IDs sample preview into place. Positions are kept as a share of the screen, so they survive a resolution or GUI-scale change; moving the preview does not move the actual per-slot badges.
- The panel scales to your screen and reopens wherever you left it.

Folded sections and macro/function list states are remembered while the game is open, then start
collapsed after a restart. The category rail, module grid, and settings list each scroll inside their
own clipped area; the wheel scrolls the area under the pointer. Category, per-category grid, and
settings-list positions persist.

The header shows the enlarged mod logo with the current version to its right; player name and skin
displays are removed. Search remains the same width with a thinner field. Category labels have no
icons. The GitHub, General Settings, Theme, and Move HUD Elements controls use official Primer
Octicons. The
module grid fits three smaller cards per row when wide and two when narrow; descriptions use a more
readable scale. The
Theme module is opened from its toolbar button and has no Visual-category card. The rounded outer
outline follows the panel edge, while its category and header dividers remain straight and inset.
The settings screen retains the category rail, follows the same navy-and-cyan styling, and has a
visible Back button in its header; press Escape to return as well. **General → Show Availability Notices** hides
inactive-reason text from cards and tooltips without hiding module names or descriptions.

Macros, Mob Highlight, and Block ESP each have an independent nested folder tree. New and legacy
entries belong to the root until moved. Deleting a populated folder promotes its entries and child
folders to its parent; folder names can repeat because UI state uses stable IDs. Inventory Buttons
are a spatial layout, not a multi-entry folder list.

</details>

## Settings and data

Module settings and Click GUI state live in `.minecraft/config/geileraddons/config.json`. Macros and
reusable macro functions are saved as individual JSON files under `macros/` and `functions/`, with
`macro-index.json` as their manifest. On the first save after upgrading, inline macros/functions are
migrated and the old config is preserved once as `config.before-split.json`.

Dungeon Guide steps are stored by floor and phase under
`.minecraft/config/geileraddons/dungeon-guides/<floor>/<phase>/`; the route name is shared across every
phase on that floor. Upgraded phase-specific route files are archived under
`dungeon-guides/archive/legacy-v1/`.

`/ga reset` provides a guided factory reset instead of requiring manual file deletion. Every reset
area starts checked; uncheck any areas to preserve, review the selection, then confirm. The reset
flow creates a recovery export before applying changes.

Use `/ga config export [name]` to create a full `.gacfg.zip` profile. `/ga config import` lists
available profiles, validates and previews the selected archive, asks for a separate confirmation,
and creates a recovery export before importing. `/ga config save` flushes the main config and split
collections to disk; `/ga config folder` opens the GeilerAddons config directory. Exported profiles
and recovery copies are stored in `.minecraft/config/geileraddons/exports/`; the commands provide a
chat link to that folder.

For manual recovery, module settings and Click GUI state are in `config.json`, while macros/functions,
guide routes, and other feature collections use the separate files and folders described above.
Deleting `config.json` alone only resets module settings and GUI state.

**Update check:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. When one does, the Click GUI header grows a row with **Download** and **Info**: Download opens the release page through the usual link confirmation, and Info opens a scrollable panel with the release notes and the version you have. Both stay inside the mod — nothing is ever downloaded or installed. Turn the check off with **Miscellaneous → General → Check for Updates** (an existing `"checkForUpdates"` key in the config file seeds that setting once, then the setting is authoritative).

**Island detection:** modules that only apply on one island need to know which island you're on, so the mod subscribes to the official Hypixel Mod API. The shared API handles the greeting and registration, then reports the island whenever you change server. It is a normal row — **Miscellaneous → General → Island Detection API** — and an existing `"hypixelModApi"` key in the config file seeds that setting once, then the setting is authoritative.

Turning it off leaves island-gated modules idle, and they say so: the config file is not the only
place that knows why. No terrain or scoreboard guess is allowed to activate them, so an off switch
means an idle feature rather than a wrong one.

**Keybinds:** binding a key that another module, macro or script already uses reports the collision in
chat. It is reported rather than resolved — a macro hotkey is checked before module keybinds, so on a
collision both would otherwise fire from one press.

## License

Original GeilerAddons code is [CC0 1.0](LICENSE). The Primer Octicons artwork license notice is
included at `src/main/resources/META-INF/licenses/OCTICONS-MIT.txt`.
