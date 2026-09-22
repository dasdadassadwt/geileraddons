# Bugs

Known defects in the current working tree, written down with what is observed, what actually causes
it, and what to do about it. This is the local list, not the public tracker: an entry stays here
until it is fixed, and a fix is noted in [CHANGELOG.md](CHANGELOG.md).

Severity is about the player, not the code. **Low** means nothing is lost and a workaround exists,
**medium** means a feature is hard to use or reads wrong, **high** means data loss, a crash, or
something the player cannot work around.

## Open

### BUG-004 — Auto Experiments does not activate on the Ultrasequencer

- **Area:** Enchanting → Auto Experiments with the Solver enabled and working. **Severity:** high
  (the automation does nothing at all).
- **Observed:** the Solver recognises the board and renders the solution, but Auto Experiments never
  dispatches a click — no chat notice, no click, nothing in the diagnostic log.
- **Expected:** Auto dispatches a click for the first remembered slot once the solve opens.
- **Status:** open, instrumented. Static analysis ruled out the click path itself (recognition,
  session/menu identity, and the view-derived expected slot all check out, and the offline suite
  reproduces a working `SOLVE` → `CLICK` → gate → vanilla chain). What remains are six silent exits
  that leave no trace: the module being off, `supports(ULTRASEQUENCER)` being off, the snapshot's
  early `!isAutoEligibleScreen` return, the model never reaching `SHOW` because the real status text
  does not match the expected wording, a `dispatchSequenceClick` early return, and the swallowed
  `RuntimeException` around the vanilla invoker.
- **Instrumentation:** **Dev Debug** plus **Auto Experiments → Diagnostics → Debug Automation** now
  writes one `Auto Experiments.log` line per changed decision (type, tier, enabled, supports,
  eligible, owner, session generation, engine phase, index/length, expected slot, decision, dispatch
  result, failure) and one `dispatch rejected: <gate>` line naming the first failed gate in
  `ExperimentController`. A stale build is also possible: the last recorded dev run loaded
  `geileraddons 1.5.1` while the tree is `1.5.2`, so the previous fix may never have been under test.
- **Also changed:** the Ultrasequencer phase transition now accepts the tolerant status matchers, so
  a slightly reworded or reformatted notice can no longer leave the model waiting for a timer.
- **Repro:** enable the Solver and Auto Experiments, run one Ultrasequencer round, and read
  `logs/geileraddons/<session>/Enchanting/Auto Experiments.log`.
- **Files:** `src/client/java/geiler/addons/client/module/impl/AutoExperimentsModule.java`,
  `src/client/java/geiler/addons/client/module/impl/ExperimentController.java`,
  `src/client/java/geiler/addons/client/enchanting/UltrasequencerModel.java`.

### BUG-001 — Expanding one macro's Islands section expands every macro's Islands section

- **Area:** Click GUI → Miscellaneous → Macros. **Severity:** low.
- **Observed:** opening or closing the folded **Islands** section inside one macro opens or closes it
  inside every macro in the list at the same time, including macros that were never touched.
- **Expected:** each macro's Islands section folds independently, the way every other repeated
  section in the panel behaves.
- **Cause:** `MacrosModule.macroGroup()` builds the section as `SettingGroup.folded("Islands", ...)`
  and never calls `.keyed(...)`, so `ClickGuiState.key()` falls back to
  `module.name() + "." + group.name()` — the literal string `Macros.Islands`, identical for every
  macro. One entry in `ClickGuiState.collapsedGroups` therefore drives all of them. Mob Highlight and
  Block ESP do not have this problem because they already key their repeated sections
  (`mob-highlight-islands:<id>`, `block-esp-islands:<id>`), which is why only the macro list is
  affected.
- **Fix direction:** give the folded section a stable per-macro identity —
  `.keyed("macro-islands:" + macro.id())` — matching the existing convention. Note the one-time
  effect: the old `Macros.Islands` key is left behind and ignored, so whatever was expanded or
  collapsed before the fix returns to its default state once.
- **Repro:** create two macros, open the first one's Islands section, then look at the second.
- **Files:** `src/client/java/geiler/addons/client/module/impl/MacrosModule.java`,
  `src/client/java/geiler/addons/client/config/ClickGuiState.java`.
- **Status:** open.

### BUG-002 — A selected macro node's outline is invisible on some themes

- **Area:** macro editor (`ScratchMacroEditorScreen`) and the theme. **Severity:** low–medium.
- **Observed:** the highlight border marking the currently selected node is barely visible, and on
  some node and theme combinations it cannot be seen at all, so which block is selected is not
  obvious.
- **Expected:** the selected node is unmistakable whichever theme is active and whatever kind of node
  it is.
- **Cause:** two colours that are the same colour. `drawStepNode` draws the selected outline with
  `CARD_BORDER_ENABLED`, which `GuiTheme` derives as the opaque theme **accent**; `categoryColor()`
  returns that same accent for every macro block whenever **Theme Macro Colors** is on, and
  `VisualModule` defaults that setting to `true`. The node body and its selection ring therefore
  share one colour, so the ring disappears into the block. With theme macro colours off the accent
  can still land on a fixed category colour of similar brightness, and the ring is a single pixel
  wide (`GuiTheme.roundedRectBordered` insets the fill by exactly one pixel). The legacy
  `MacroEditorScreen` uses the same constant for its selected rows, but also fills the row with
  `CARD_BG_ENABLED`, so it reads better there.
- **Fix direction:** stop deriving the selection outline from the accent. Use a value guaranteed to
  contrast with both the panel and the accent — the existing `SWITCH_BORDER = withAlpha(text, 150)`
  is that precedent — or combine a two-pixel outline with a light or dark halo. Check it across every
  theme preset against every node category, with Theme Macro Colors both on and off.
- **Repro:** open a macro's workflow editor, select a node, switch through the theme presets, and
  toggle Theme Macro Colors.
- **Files:** `src/client/java/geiler/addons/client/gui/ScratchMacroEditorScreen.java`,
  `src/client/java/geiler/addons/client/gui/GuiTheme.java`,
  `src/client/java/geiler/addons/client/module/impl/VisualModule.java`.
- **Status:** open.

## Fixed

### BUG-003 — Auto Experiments clicks only the first item of every Ultrasequencer round

- **Area:** Enchanting → Auto Experiments on the Ultrasequencer board. **Severity:** high (the run
  fails and the player can only work around it by not using the module).
- **Observed:** Auto dispatched the first remembered item of a round and then stopped clicking for
  the rest of that round, while the Solver preview kept showing the correct remaining solution.
- **Expected:** Auto replays every remembered click of every round.
- **Cause:** `UltrasequencerModel.markDirty()` turned any new non-black pane colour into `State.END`,
  which maps to `ExperimentPhase.ROUND_COMPLETE`. The board repaints those panes inside a single
  round for its own buttons, so a round was closed after the first click while its remembered
  sequence and cursor were still intact. `ExperimentClickGate.dispatchSequenceClick` accepts clicks
  only during `ExperimentPhase.SOLVE`, so every later Auto request was rejected before vanilla saw
  it. `nextSlot` also deliberately keeps pointing at the last button after the final click, so the
  model could not tell "still owed clicks" apart from "waiting for the pane edge".
- **Fix direction:** count the accepted clicks of the round in the model and let a pane colour change
  close a round only once that round's clicks are complete; while the solve still owes clicks the
  repaint is the button animation. The round is then counted at that boundary, exactly once, so the
  round counter no longer depends on the next round's status arriving. A status tick that carries no
  numbers also keeps the capture window open, which otherwise left the model waiting for its own
  timer with an empty solution.
- **Repro:** start Auto Experiments on an Ultrasequencer run of two or more rounds; only the first
  item of each round is clicked. The offline traces in `OfflineChecks.checkAutoExperiments` and
  `SequenceSolverChecks.checkUltraRounds` reproduce it without Minecraft or the network.
- **Files:** `src/client/java/geiler/addons/client/enchanting/UltrasequencerModel.java`,
  `src/test/java/geiler/addons/client/module/impl/OfflineChecks.java`,
  `src/test/java/geiler/addons/client/module/impl/SequenceSolverChecks.java`.
- **Status:** fixed in the working tree; live Hypixel confirmation still outstanding.
