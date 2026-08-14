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
| Source encoding | `native.encoding` is `Cp1252` here, so javac's default would mangle the UTF-8 sources; Loom sets UTF-8 already and `build.gradle` now states it outright |
| Entities in a box | `Level.getEntities(EntityTypeTest.forClass(T), AABB, Predicate)`; `getEntitiesOfClass` does not exist on this version |
| Custom payloads | `PayloadTypeRegistry.clientboundPlay()/serverboundPlay().register(Type, StreamCodec)` plus `ClientPlayNetworking.registerGlobalReceiver`/`send`. There is no `ClientPayloadEvents` |
| A player's skin texture | `AbstractClientPlayer.getSkin()` → `PlayerSkin.body()` → `net.minecraft.core.ClientAsset$Texture` (not `client.resources`) |
| Titles | `Gui.setTimes(int, int, int)` + `Gui.setTitle`/`setSubtitle` |
| Depth-tested overlays | A second `RenderPipeline` pair with `DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)`; `CompareOp.LESS_THAN_OR_EQUAL`, not `LEQUAL` |

### Skin identifiers are not texture hashes

`PlayerSkin.body().texturePath()` looks like it should carry the Mojang texture hash, and does not.
`SkinManager$TextureCache` names a downloaded skin from `Hashing.sha1().hashUnencodedChars(url)`, so
the identifier is `minecraft:skins/<sha1-of-the-URL>` — comparing its path against a texture hash
matches nothing, silently and forever. The hash is only recoverable from
`ClientAsset$DownloadedTexture.url()`, which is what `HideyhoFinderModule` matches against.

## Hypixel Mod API

Protocol read from <https://github.com/AzureAaron/hm-api> (`HypixelCustomPayloadCodecs`,
`HypixelNetworkingImpl`, `PacketCodecUtils`, and the packet records), pulled via `gh api`. The
library itself is not a dependency — GeilerAddons speaks the three packets it needs directly.

It is a **binary** protocol built on Minecraft's own `StreamCodec`, not JSON, and the channel names
are not what they look like:

| Direction | Channel | Payload |
| --- | --- | --- |
| S2C | `hypixel:hello` | `bool success`, then the server environment. Sent on login; it is the cue to register |
| C2S | `hypixel:register` | `VarInt version`, then a map of `Identifier` → wanted `VarInt` version |
| S2C | `hyevent:location` | `bool success`, `VarInt version`, `String serverName`, then optional `serverType`, `lobbyName`, `mode`, `map` |

Three things that are easy to get wrong and cost nothing to get right:

- The location event is **`hyevent:location`**, not `hypixel:location_update`, and `hypixel:player_info`
  carries ranks rather than a location — it is no use for island detection.
- Every S2C packet leads with a success flag and a version, ahead of any real field.
- The buffer must be **drained** after decoding. Hypixel has shipped trailing zero bytes, and a
  payload decoder that leaves bytes unread is a disconnect rather than a warning
  (`PacketCodecUtils.readAllBytes` exists in hm-api for exactly this reason).

### Island ids

From SkyHanni's `IslandType.kt` (<https://github.com/hannibal002/SkyHanni>), read via `gh api`. The
`mode` string is not derivable from the island's name:

| Island | `mode` |
| --- | --- |
| Critter Safari | `safari` |
| Torrhus Canyon | `foraging_3` |
| Moonglade Marsh (Galatea) | `foraging_2` |
| Private island | `dynamic` |

`location/Island.java` now carries all **24** mode-addressable islands from that file. Three things
that table settles, none of them guessable:

- There is no `combat_2`. Combat runs `combat_1` (Spider's Den) then `combat_3` (The End); the
  Blazing Fortress that sat between them is gone, so a generated sequence would invent an island.
- The Catacombs is `dungeon`, singular, while its hub is `dungeon_hub`.
- **Guest variants have no mode at all.** Private Island Guest and Garden Guest are `null` in
  SkyHanni and are deliberately absent here: nothing distinguishes them from the island itself, so
  listing them would offer a filter that can never match.

## Skyblocker — Safari floor drops

<https://github.com/SkyblockerMod/Skyblocker> (`skyblock/hunting/FloorDrops.java`).

Floor drops are found from the happy-villager particle the server sends at them, confirmed by three
`Display.ItemDisplay` entities holding `minecraft:string` in the same block — there is no coordinate
list involved. A marker is refreshed while those three are still there, dropped 5 s after the last
confirmation, and dropped immediately when the player attacks or uses that block. Re-confirmation is
skipped within 2 s of the last one. GeilerAddons ports the same behaviour and timings.

### Safari biomes

<https://github.com/SkyblockerMod/Skyblocker> (`skyblock/hunting/safari/SafariUtils.java`,
`utils/SkyBlockBiomes.java`, and `Utils.updateBiome`/`isInBiome`).

**Hypixel ships the Safari's areas as real biomes**, registered in its own namespace, so which part
of the Safari a player is standing in is a question the client can simply ask rather than something
to be worked out from coordinates:

| Area | biome id |
| --- | --- |
| Forest | `hypixel:forest` |
| Cavern | `hypixel:cavern` |
| Icy | `hypixel:icy`, `hypixel:icy_caves` |
| Haunted | `hypixel:haunted` |

Icy really does have two ids. The lookup is `level.getBiome(player.blockPosition())` guarded by
`isInsideBuildHeight`, compared with `Holder.is(Identifier)` — all three exist on 26.1.2, though
`getBiome` is inherited from `LevelReader` and `isInsideBuildHeight` from `LevelHeightAccessor`
rather than being declared on `Level` itself, so `javap` on `Level` alone finds neither.

`location/SafariBiome` resolves this once a tick and Hideyho Finder gates on `HAUNTED`, that being
the only biome a Hideyho spawns in. Note this is a **better** answer than the one critterMod uses
for the same question — its `SafariAreaMap.biomeAt(x, y, z)` is a coordinate table, which carries
exactly the staleness problem that got the Hideyho spot list deleted.

## SkyHanni — Hideyho

<https://github.com/hannibal002/SkyHanni> (`features/hunting/safari/HideyhoFinder.kt`), and
`hannibal002/SkyHanni-REPO` for `constants/Skulls.json` — the `HIDEYHO` skin, texture
`3504f1f2…d2db533`, which is still what identifies it.

### Hiding-spot coordinates were tried and removed

SkyHanni waits 2 s after "No peeking!" (the Hideyho teleports first), then pathfinds between the
`hideyho`-tagged nodes in `constants/island_graphs/SAFARI.json`. Ported without a pathfinder, that
became "sweep the 19 listed spots that are inside render distance", and it missed too often to
keep, for three separate reasons:

- the list goes stale the moment Hypixel adds a spot, and the wiki's is already incomplete;
- only spots in loaded chunks can be read, so most of the list is unreadable on arrival;
- a Hideyho standing a few blocks off a listed spot is invisible to it.

It now sweeps for the entity itself once a second and holds no coordinates at all. Don't
reintroduce a spot list without a reason that survives all three of those.

## critterMod — sparkling critters

`C:\Users\chens\Desktop\Mods\critterMod-26.1.2` (`data/Critters.java`, `client/CritterEntities.java`,
`client/SparklingWatch.java`, `client/SafariLocation.java`). Read from the working copy rather than
summarised second-hand, because a plan's transcription of it had already drifted in one detail.

What it settled:

- **The roster is 37 species**, and `hunting/CritterSpecies` carries the same names. Hypixel labels a
  critter with an entity whose custom name is *exactly* the species name.
- **A rare one is named for it** — `Sparkling Rockmite`. The prefix test is
  `equalsIgnoreCase` over the first nine characters plus a `length > 9` guard, **not**
  `startsWith`: a label reading exactly `Sparkling` must not resolve to a rare nothing.
- **Only that one prefix is tolerated**, rather than searching a label for any species name it
  contains, so an armour stand advertising a `Tepid Shard` cannot read as a Tepid.
- **Labels are stripped of `\p{Cf}` and `\p{Co}`** as well as §-codes. Hypixel pads name tags with
  formatting characters and draws with private-use glyphs, either of which defeats an equality test
  while being invisible in a log. `ChatText.stripForMatch` does this; `ChatText.plain` deliberately
  does not, since chat-line recognition depends on the padding it keeps.
- **A label's mob is found by proximity, excluding scaffolding** — armour stands, interactions, the
  three display types, items, *and players*. The player exclusion matters: without it a sparkling
  standing beside someone boxes the person. A Hideyho arrives as a fake player and names itself, so
  a player may still be a *label*, just never borrowed as somebody else's body.
- **Announcements are keyed on the label's UUID**, not the mob's, because the label is what named it
  and it survives the pairing underneath being ambiguous.

### A shared sweep was considered and not built

critterMod runs one `CritterEntities` sweep read by four features, and copying that shape was
proposed here. It was not adopted, because GeilerAddons has only one consumer for it: Mob Highlight
matches arbitrary user text against any entity on its own per-highlight interval, and Hideyho Finder
matches a skin hash — neither can read a roster-of-species sweep. A shared component with a single
consumer is the speculative abstraction this repo's own rules forbid, so the sweep lives in
`SparklingCritterModule`. If Hideyho Finder is ever moved onto species labels there would be a
second consumer, and that is the point to revisit it.

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
