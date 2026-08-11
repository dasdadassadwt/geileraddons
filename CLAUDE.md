# GeilerAddons

Client-side Fabric mod for Hypixel SkyBlock (Minecraft 1.21.10, SkyBlock protocol 26.1.2, Java 25). Two modules today: **i4 Helper** (Catacombs F7/M7 4th-device puzzle) and **Tiki Helper** (Sneaky Tiki hunting).

## Build

```bash
JAVA_HOME=C:\Java\jdk-25.0.3+9 ./gradlew.bat build --offline --console=plain
```

- **JDK 25 is required.** The system default `JAVA_HOME` is JDK 21 and the build fails silently-ish under it — always set `JAVA_HOME` explicitly for this project.
- **Hand over the built jar, never launch `runClient`.** There's no way to drive a live Minecraft session from here to verify UI/gameplay changes — say so plainly rather than claiming something was tested in-game when it wasn't.
- MC 26 ships unobfuscated: no `remapJar` step, no refmap wiring to think about. The dev jar in `build/libs/` **is** the shippable jar.
- `build/libs/*.jar` is tracked in git (releases are cut from it, not from CI). Remove the previous version's jar when bumping — don't leave stale versions staged.

## Architecture

- `geiler.addons` (in `src/main`) — the `ModInitializer`, logging only. Everything real lives client-side.
- `geiler.addons.client` (in `src/client`) — entry point `GeilerAddonsClient`, which registers modules, loads config, and wires tick/render/HUD callbacks. This is the place to look first for "what runs when."
- **Modules** (`module/impl/`) extend `Module` (`module/Module.java`), which owns settings lists, enabled state, and now `isActive()`/`inactiveReason()` for modules that gate themselves on context (e.g. Tiki Helper only runs in Torrhus Canyon) without touching the user's actual on/off switch. Settings are declared as `ColorSetting`/`NumberSetting`/`BooleanSetting`/`ModuleAction`, all implementing `Setting`, and grouped for display via `group(SettingGroup...)` in the constructor — grouping is presentation-only, the flat setting lists are still what gets persisted, so reshuffling groups never touches a config key.
- **Config** (`config/ModConfig.java`) is Gson-backed, lives at `config/geileraddons/config.json`, and migrates a pre-1.3.1 flat `config/geileraddons.json` automatically on first load (copy + delete, not a rename — see the migration test pattern below). `save()`/`load()` failures are logged, never thrown — this runs from screen teardown and every setting toggle, so a bad disk must not take the UI down with it.
- **GUI** (`gui/`) — `ClickGuiScreen` is the `/ga` menu, `GuiTheme` holds the shared anti-aliased-corner drawing primitives (`roundedRect`, `roundedRectBordered`, `toggleSwitch`). Corners are anti-aliased by partial-alpha coverage per boundary pixel, not by a texture — see `GuiTheme.coverage()` if touching this.
- **Location detection** (`location/`) — `TorrhusPresence` decides whether the player is in Torrhus Canyon by fingerprinting the ground block at each saved Tiki waypoint (learned on first sighting, persisted, compared on every subsequent load) rather than by parsing the scoreboard. The scoreboard approach was tried twice and failed twice — Hypixel gives every area its own icon, so a marker-glyph allowlist can never be complete. Don't reintroduce scoreboard-text parsing for island detection without a strong reason; if a future module needs "which island am I on" as a *name* rather than "am I at this specific spot," that's a different problem from what `TorrhusPresence` solves.
- **Tiki solving** (`tiki/TikiSolver.java`, `tiki/TikiStacks.java`) — pure logic, no Minecraft dependency. `TikiSolver` is a from-scratch BFS solver table; the click rule it encodes was reverse-engineered from recorded gameplay (see the class doc), not from the wiki, which is measurably wrong about a third of the time. Don't second-guess it in favor of the wiki's description.

## Testing approach

There's no automated test suite, and no way to launch the game from this environment. What actually catches bugs here:

1. **Pure logic gets a standalone offline check before it's wired in**, using a throwaway harness in the scratchpad directory that copies the real source file(s) verbatim (not reimplemented) and exercises them against real or adversarial input — e.g. `TikiSolver.solveSequence` was checked against all 1,024 reachable puzzle states, the scoreboard-line parser (now retired) was checked against synthetic lines including a surrogate-pair emoji that a naive regex would mishandle, the config migration logic was checked against a real temp directory across four scenarios. This has caught real bugs before they shipped (a corner-rendering bug, a version mislabel) — keep doing it for anything with real branching logic, especially parsing and stateful migrations.
2. **`compileClientJava` after every substantive change**, not just at the end — catches API drift immediately (this MC version has already renamed/moved several APIs from what's commonly documented: `ResourceLocation` → `Identifier`, `displayClientMessage` → `ChatComponent.addClientSystemMessage`, etc.). When an API doesn't match expectations, check the actual jar with `javap` against the loom cache (`~/.gradle/caches/fabric-loom/...`) rather than guessing from memory or older MC versions' docs.
3. **Full `build` at the end** to confirm the jar assembles and to inspect its contents (`unzip -l`/`unzip -p`) when a resource or metadata change is involved.

## Git conventions

- Author identity is `dasdadassadwt <dasdadassadwt@users.noreply.github.com>` — the real email must never appear in history (it was scrubbed once already via `filter-branch` + force-push after being exposed on the now-public repo).
- **No `Co-Authored-By: Claude` trailer in commit messages.** The user asked for Claude removed from the contributor list; adding the trailer back would undo that.
- Prefer a new commit over amending anything already pushed.
- Only push or create a GitHub release when explicitly asked. The user has said they'll cut releases themselves — don't run `gh release create` unprompted.
- Version bumps must reflect what's actually in the release: a batch of fixes and UI rework is a patch bump, not a feature bump, even if it's a large diff. Ask if it's unclear which the user intends.

## Code style

- No doc comments beyond a one-line class/method summary; multi-paragraph Javadoc is reserved for genuinely non-obvious rationale (a workaround, an invariant, a "why not the obvious approach" note). Most methods have no comment at all.
- Comments explain *why*, never *what* — the code already says what. If removing a comment wouldn't confuse a future reader, don't write it.
- No speculative abstraction. `SettingGroup`, `Setting`, and `Module.isActive()` were all added because a concrete second use case existed at the time (Tiki Helper's sections, and its Torrhus gating) — not preemptively.
