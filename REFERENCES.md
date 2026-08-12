# References

Sources consulted while building this mod, and what each one actually settled. Kept because MC
26.1 renamed enough that guessing from older docs is worse than useless, and because a dead end is
only worth walking into once.

## Minecraft / Fabric API surface

The authority is the jar, not the internet. This version is unobfuscated and sitting in the loom
cache, so read it directly:

```bash
JAVA_HOME=C:\Java\jdk-25.0.3+9 javap -cp ~/.gradle/caches/fabric-loom/26.1.2/minecraft-client-only.jar net.minecraft.client.gui.screens.Screen
```

Fabric API modules live under `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`.

What that has answered so far:

| Question | Answer |
| --- | --- |
| Resource identifiers | `Identifier`, not `ResourceLocation` |
| Sending a client-side chat message | `mc.gui.getChat().addClientSystemMessage(...)`; `LocalPlayer.displayClientMessage` is gone |
| Screen drawing | `GuiGraphicsExtractor`, not `GuiGraphics` — has `text`/`fill`/`fillGradient`/`enableScissor`/`guiWidth`/`guiHeight` |
| Key and character input | `GuiEventListener.keyPressed(KeyEvent)` and `charTyped(CharacterEvent)`; `CharacterEvent` is a record of a single `codepoint()`, `KeyEvent` of `key()`/`scancode()`/`modifiers()` |
| In-world text | Has to go through `SubmitNodeCollector.submitText`; `Font.drawInBatch` against the level buffer source silently drops the glyphs, which is why solver labels are HUD-projected instead |
| Clean shutdown hook | `ClientLifecycleEvents.CLIENT_STOPPING` |

## SkyHanni — scoreboard island detection

<https://github.com/hannibal002/SkyHanni> (`HypixelData.kt`, `ScoreboardData.kt`), pulled via
`gh api` rather than read second-hand.

Consulted while trying to detect Torrhus Canyon from the scoreboard sidebar. Their approach is a
structural regex — colour code, any single icon character, space, colour code, area name — instead
of an allowlist of known icons, because Hypixel gives each area its own icon.

**This did not work here and was removed.** See `location/TorrhusPresence` for what replaced it and
`CLAUDE.md` for the standing rule against reintroducing it. Recorded so the next attempt at
scoreboard parsing knows it's the third one, not the first.

## Hypixel SkyBlock wiki — tiki rotation rule

The wiki's description of how clicking a Sneaky Tiki rotates the stack disagrees with recorded
gameplay roughly a third of the time. `tiki/TikiSolver` encodes the rule reverse-engineered from
the debug logs instead, and keeps the wiki's version behind the "Hypixel Rule" toggle for
comparison. Don't "fix" the solver to match the wiki.
