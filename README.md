# GeilerAddons

A client-side [Fabric](https://fabricmc.net/) mod for Minecraft **1.21.10 (SkyBlock protocol 26.1.2)**, built for [Hypixel SkyBlock](https://hypixel.net/). It renders overlays and does client-side math — it never sends packets, automates clicks, or reads server-restricted data. Everything runs entirely on your own screen.

Open the mod's menu in-game with:

```
/ga
```

## Modules

### i4 Helper (category: F7)

Highlights the Catacombs F7/M7 4th-device panel puzzle: boxes the completed and currently-active blocks, and marks the exact spot to aim for the next shot. Also plays a short confirmation sound sequence when your team finishes a device.

### Tiki Helper (category: Hunting)

Everything for hunting **Sneaky Tikis** in Torrhus Canyon / Torrhus Heights — three-headed totems that must be rotated into alignment by left- and right-clicking. Three independently toggleable parts:

- **Waypoints** — boxes each saved coordinate green or red depending on whether a tiki currently stands there, and keeps showing the last-known result even after the coordinate leaves render distance. Comes with a draggable-slider tracer line to the nearest solvable tiki.
- **Solver** — floats a click count directly over the head that needs it: `+3` means left-click it three times, `-2` means right-click it twice. The plan updates itself the instant a click registers. Handles the case where one of the three heads is hidden behind another block (the count shows `+?`/`-?` since it can't be read, but the direction is still exact).
- **Debug Logging** — writes a detailed, timestamped log of every rotation, click, and relevant sound/chat event to `logs/tiki-debug-*.log`. This is how the solver's rule was derived and verified in the first place, and it's there for anyone who wants to check that rule against a live server.

The coordinate list is managed from its own screen (**Manage Tiki Coords**, right-click the module in `/ga`): add the block you're looking at, or delete entries you no longer need. It's saved to your config automatically.

#### How the puzzle works

A Sneaky Tiki is three stacked heads, each on one of 16 rotation steps. Left-click adds 2 steps, right-click subtracts 2, and a click also turns the head *above* the one you clicked — unless that head already matches another head, in which case it's locked and doesn't turn (even if it's the one you clicked). The tiki wakes up once all three match.

This rule was reverse-engineered from recorded gameplay, not documentation — the commonly cited wiki description ("if the top two heads match, they freeze") is a simplification that's wrong roughly a third of the time. The version in this mod was checked against several hundred recorded clicks and reaches every solvable state in five clicks or fewer.

### Click GUI

- Sizes itself as a fixed 2:1 box relative to your screen instead of a hardcoded pixel size.
- Remembers which category and module panel you last had open, and reopens there.
- Every color setting includes an alpha slider; every numeric setting is a draggable slider with a live value readout.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.10.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) for the same version.
3. Download the latest `geileraddons-*.jar` from the [Releases](../../releases) page (or `build/libs/` in this repo) and drop it into your `.minecraft/mods` folder.
4. Launch the game and run `/ga` to open the menu.

Requires Java 25 or newer, matching the target Minecraft version.

## Building from source

```bash
git clone https://github.com/dasdadassadwt/geileraddons.git
cd geileraddons
./gradlew build
```

The output jar is written to `build/libs/geileraddons-<version>.jar`. Building targets Java 25 specifically — if your default JDK is older, point `JAVA_HOME` at a JDK 25 install for the build:

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

## Configuration

Settings are stored in `.minecraft/config/geileraddons.json`, written automatically whenever you change something in `/ga`. Deleting it resets everything to defaults, including the seeded tiki coordinate list.

## License

[CC0 1.0](LICENSE) — public domain. Do whatever you like with it.
