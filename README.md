<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

Fully client-side. Nothing is automated, nothing is sent to the server.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.10-brightgreen)
![Loader](https://img.shields.io/badge/loader-Fabric-blue)
![License](https://img.shields.io/badge/license-CC0--1.0-lightgrey)

</div>

---

GeilerAddons draws things on your screen and does the maths you'd otherwise do in your head. It reads what your client already knows and renders on top of it — it never clicks for you, never sends a packet, and never reads anything the server hasn't already told your client.

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
- **Survives a jump.** Hopping on the platform, or arriving part-way through a device, doesn't blank the overlay.

Colours and marker sizes are all adjustable.

</details>

<details>
<summary><b>Tiki Helper</b> — Sneaky Tiki hunting in Torrhus</summary>

<br>

Sneaky Tikis are three stacked heads that have to be rotated into alignment by clicking them. This module handles finding them and telling you exactly what to click.

**Waypoints** — boxes every saved tiki spot, green if one is standing there and red if not. Markers stay put after the spot leaves render distance, so you keep your bearings while running a route. An optional tracer line points at the nearest solvable tiki.

**Solver** — floats a number over the head that needs clicking. `+3` means left-click it three times, `-2` means right-click it twice. It updates the instant a click registers, and handles the case where one head is hidden behind another block — you still get the direction, marked `+?`, because the count genuinely can't be known.

**Debug Logging** — off by default. Writes every rotation, click and sound to a file. This is how the solver's rule was worked out, and it's there for anyone who wants to check that rule against a live server.

The module switches itself on only in Torrhus, and sits idle everywhere else in SkyBlock — so it costs you nothing while you're doing something else. The card in the menu tells you where you currently are when it's idle.

</details>

<details>
<summary>How the tiki puzzle actually works</summary>

<br>

Each tiki is three heads, each on one of 16 rotation steps. Left-click adds two steps, right-click subtracts two, and a click also turns the head *above* the one you clicked. The catch: a head that already matches another head is locked, and a locked head turns for nobody — **including when it's the one you clicked**. The tiki wakes once all three match.

That last part is the bit that matters, and it's why a naive solver gives you impossible advice. The commonly cited description ("if the top two heads match, they freeze") is a simplification that's wrong about a third of the time.

This rule wasn't taken from documentation — it was reverse-engineered from several hundred recorded clicks of real gameplay, then checked back against them. It reaches every solvable state in five clicks or fewer.

</details>

<details>
<summary>The menu</summary>

<br>

- Modules are cards. The **switch** turns one on; **clicking anywhere else** on the card opens its settings — either mouse button works.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Every colour has an alpha slider, every number is a draggable slider with a live readout.
- The panel scales to your screen and reopens wherever you left it.

</details>

## What it doesn't do

This matters more than the feature list, so it's spelled out plainly:

- **No automation.** It never clicks, moves, or acts for you.
- **No packets sent.** The mod is render-only; the server sees a completely vanilla client.
- **No hidden information.** Everything it draws comes from data your client already received.

## Settings and data

Your settings live in `.minecraft/config/geileraddons.json`. Delete it to reset everything.

**One network request:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. It never downloads or installs anything. To turn it off, set `"checkForUpdates": false` in that config file.

## License

[CC0 1.0](LICENSE) — public domain. Do whatever you like with it.
