<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

Fully client-side. Nothing is automated, nothing is sent to the server.

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
<summary><b>Tree Tracker</b> — gift counting for Helix, Fig and Mangrove</summary>

<br>

Reads the gift message out of chat and keeps score, so you can tell whether a spot is actually worth farming.

**The panel** shows gifts per hour, the running count, and how long you've been at it — for whichever tree gifted last, so it follows you around without any switching.

**It knows when you stopped.** After a set idle time the clock freezes, and the next gift picks it back up. Time spent walking somewhere else doesn't get counted against your rate. The timeout is yours to set.

**Session and all-time.** A session runs until you reset it or restart the game; either way its gifts roll into the all-time totals rather than disappearing. Flip the panel between the two.

Drag the panel wherever you like with **Move Elements** in the menu.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. The **switch** turns one on; **clicking anywhere else** on the card opens its settings — either mouse button works.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Every colour has an alpha slider, every number is a draggable slider with a live readout.
- **Move Elements**, at the bottom of the category list, opens a screen where you drag HUD panels into place. Positions are kept as a share of the screen, so they survive a resolution or GUI-scale change.
- The panel scales to your screen and reopens wherever you left it.

</details>

## Settings and data

Your settings live in `.minecraft/config/geileraddons/config.json`. Delete it to reset everything.

**One network request:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. It never downloads or installs anything. To turn it off, set `"checkForUpdates": false` in that config file.

## License

[CC0 1.0](LICENSE) — public domain. Do whatever you like with it.
