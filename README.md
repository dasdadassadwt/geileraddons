<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

Fully client-side. Two things ever leave your client, and the second is off until you switch it on: a single request to be told which island you're on, and — if you enable it — a party message when a sparkling critter turns up.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.10-brightgreen)
![Loader](https://img.shields.io/badge/loader-Fabric-blue)
![License](https://img.shields.io/badge/license-CC0--1.0-lightgrey)

</div>

---

Open the menu in-game with **`/ga`**.

## Modules

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
- **Pick the islands it runs on.** Each highlight has its own **Islands** list, folded up inside it, covering every island the mod can recognise. **They all start off**, so you say where a highlight belongs rather than switching off the two dozen places you didn't mean. Where the island can't be named at all — single player, another server, or the moment before the handshake lands — there's nothing to match against, so the highlight simply runs. Highlights made before this existed keep running everywhere until you narrow them.

Highlights are built in the settings panel itself: **Create Mob Highlight** adds an empty one, and each becomes its own foldable section you fill in. No separate screen to keep in step.

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

Four presets to start from: **Tracker** (the flat translucent look, and the default), **Amethyst** (the original purple), **Midnight** and **Forest**.

In-world colours — waypoint states, solver directions, device highlights — stay separate. Those are signals, not decoration, and a theme has no business repainting them.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. The **switch** turns one on; **clicking anywhere else** on the card opens its settings — either mouse button works.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Colours open a proper picker: drag the square for shade, the strip under it for hue, the one below that for transparency, or type a hex code straight in.
- Every number is a draggable slider with a live readout.
- **Move Elements**, at the bottom of the category list, opens a screen where you drag HUD panels into place. Positions are kept as a share of the screen, so they survive a resolution or GUI-scale change.
- The panel scales to your screen and reopens wherever you left it.

</details>

## Settings and data

Your settings live in `.minecraft/config/geileraddons/config.json`. Delete it to reset everything.

**Update check:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. It never downloads or installs anything. To turn it off, set `"checkForUpdates": false` in that config file.

**Island detection:** modules that only apply on one island need to know which island you're on, so the mod uses Hypixel's own Mod API to ask. Hypixel greets any client that supports it; the mod answers that greeting once to subscribe, and is then told the island whenever you change server. Nothing else is sent, and nothing is sent at all on a server that never greets it. Two config-file keys control it:

- `"hypixelModApi": false` turns it off. The Safari modules then stay idle and say so; Tiki Helper falls back to recognising Torrhus Canyon by its terrain, as it did before.
- `"islandCheckIntervalSeconds"` (default `30`) is how long to wait before asking again if the island never arrived. It's a retry, not a poll — nothing is sent while the answer is already known.

## License

[CC0 1.0](LICENSE) — public domain. Do whatever you like with it.
