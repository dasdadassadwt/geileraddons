# Bugs

Known defects in the current working tree, written down with what is observed, what actually causes
it, and what to do about it. This is the local list, not the public tracker: an entry stays here
until it is fixed, and a fix is noted in [CHANGELOG.md](CHANGELOG.md).

Severity is about the player, not the code. **Low** means nothing is lost and a workaround exists,
**medium** means a feature is hard to use or reads wrong, **high** means data loss, a crash, or
something the player cannot work around.

## Open

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

Nothing yet.
