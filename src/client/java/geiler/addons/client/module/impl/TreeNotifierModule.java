package geiler.addons.client.module.impl;

import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.tree.ChatSuppressor;
import geiler.addons.client.tree.TreeFelled;
import geiler.addons.client.tree.TreeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Flashes a title when a tree gives a gift, and a separate one when a tree comes down.
 *
 * <p>Two titles rather than one because they are two events, not two names for the same one: a
 * felling can happen without a gift, can arrive before one, and can repeat for a single tree.
 * Giving each its own wording and sound is what makes them tellable apart without looking at chat.
 */
public final class TreeNotifierModule extends Module implements HudElement {
	public static final TreeNotifierModule INSTANCE = new TreeNotifierModule();

	private static final String HUD_ID = "tree_notifier";
	/** Replaced with the tree's name where one is known; left as a generic word where it isn't. */
	private static final String TREE_PLACEHOLDER = "{tree}";
	private static final String UNKNOWN_TREE = "Tree";
	private static final String GIFT_SOUND = "minecraft:entity.player.levelup";
	private static final String FELLED_SOUND = "minecraft:block.note_block.bass";
	/** Vanilla line height, before the size setting scales it. */
	private static final int LINE_HEIGHT = 9;
	/** Faded out over the last of its time on screen, so it leaves rather than blinks out. */
	private static final long FADE_MILLIS = 400;
	/**
	 * A gift block names its tree on more than one line, so the title would fire twice for one
	 * gift. Felled lines are never de-duplicated - see {@link TreeFelled}.
	 */
	private static final long GIFT_WINDOW_MILLIS = 1000;

	private final BooleanSetting giftEnabled;
	private final TextSetting giftText;
	private final TextSetting giftSound;
	private final NumberSetting giftPitch;
	private final BooleanSetting hideGiftChat;

	private final BooleanSetting felledEnabled;
	private final TextSetting felledText;
	private final TextSetting felledSound;
	private final NumberSetting felledPitch;
	private final BooleanSetting hideFelledChat;

	private final NumberSetting textSize;
	private final NumberSetting duration;
	private final BooleanSetting playSound;
	private final NumberSetting volume;

	private final ChatSuppressor suppressor = new ChatSuppressor();
	/** The separator being withheld while the next line decides whether it opened a gift block. */
	private Component heldLine;

	private String shownText = "";
	private long shownUntil;
	private long lastGiftTitle;
	/** Remembered so a felled line, which never names a tree, can still fill in {@value #TREE_PLACEHOLDER}. */
	private TreeType lastTree;

	private TreeNotifierModule() {
		this(new Settings());
	}

	private TreeNotifierModule(Settings s) {
		super("Tree Broken Notifier", "Shows a title when a tree is felled or gives a gift.",
			Category.FORAGING,
			s.giftEnabled, s.giftText, s.giftSound, s.giftPitch, s.hideGiftChat,
			s.felledEnabled, s.felledText, s.felledSound, s.felledPitch, s.hideFelledChat,
			s.textSize, s.duration, s.playSound, s.volume);
		this.giftEnabled = s.giftEnabled;
		this.giftText = s.giftText;
		this.giftSound = s.giftSound;
		this.giftPitch = s.giftPitch;
		this.hideGiftChat = s.hideGiftChat;
		this.felledEnabled = s.felledEnabled;
		this.felledText = s.felledText;
		this.felledSound = s.felledSound;
		this.felledPitch = s.felledPitch;
		this.hideFelledChat = s.hideFelledChat;
		this.textSize = s.textSize;
		this.duration = s.duration;
		this.playSound = s.playSound;
		this.volume = s.volume;
		// Each trigger's section leads with the toggle that switches it on, the same way Tiki
		// Helper's do, so the first row decides whether the rest of the section matters.
		group(
			new SettingGroup("Tree Gift", s.giftEnabled, s.giftText, s.giftSound, s.giftPitch, s.hideGiftChat),
			new SettingGroup("Timber & Petalfall", s.felledEnabled, s.felledText, s.felledSound,
				s.felledPitch, s.hideFelledChat),
			new SettingGroup("Shared", s.textSize, s.duration, s.playSound, s.volume)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final BooleanSetting giftEnabled = new BooleanSetting("On Tree Gift", true);
		final TextSetting giftText = new TextSetting("Gift Text", "TREE GIFT", 48);
		final TextSetting giftSound = new TextSetting("Gift Sound", GIFT_SOUND, 64);
		final NumberSetting giftPitch = new NumberSetting("Gift Pitch", 0.5f, 2.0f, 1.0f);
		final BooleanSetting hideGiftChat = new BooleanSetting("Hide Gift Message", false);

		final BooleanSetting felledEnabled = new BooleanSetting("On Timber / Petalfall", true);
		final TextSetting felledText = new TextSetting("Felled Text", "TREE BROKEN", 48);
		final TextSetting felledSound = new TextSetting("Felled Sound", FELLED_SOUND, 64);
		final NumberSetting felledPitch = new NumberSetting("Felled Pitch", 0.5f, 2.0f, 0.8f);
		final BooleanSetting hideFelledChat = new BooleanSetting("Hide Felled Message", false);

		final NumberSetting textSize = new NumberSetting("Text Size", 0.5f, 6.0f, 3.0f);
		final NumberSetting duration = new NumberSetting("Duration (s)", 0.5f, 10.0f, 2.0f);
		final BooleanSetting playSound = new BooleanSetting("Play Sound", true);
		final NumberSetting volume = new NumberSetting("Volume", 0.0f, 1.0f, 1.0f);
	}

	// ---- triggering ---------------------------------------------------------------------

	/**
	 * Handles one chat line: fires the titles, and decides whether the line reaches chat.
	 *
	 * @return true if the caller should cancel the message, either because it is being hidden or
	 *         because it is being held back for a moment
	 */
	public boolean onChatMessage(String message, Component content) {
		if (!isEnabled()) return false;
		long now = System.currentTimeMillis();
		notifyFor(message, now);

		ChatSuppressor.Decision decision =
			suppressor.accept(message, hideGiftChat.value(), hideFelledChat.value());
		if (decision.emitHeld()) {
			emitHeld();
		} else {
			// Not emitted means deliberately discarded - it was the block's opening bar. Dropping
			// the reference matters: left set, the next end of tick would print it on its own.
			heldLine = null;
		}
		return switch (decision.action()) {
			case SHOW -> false;
			case HIDE -> true;
			case HOLD -> {
				heldLine = content;
				yield true;
			}
		};
	}

	private void notifyFor(String message, long now) {
		if (TreeFelled.matches(message)) {
			if (felledEnabled.value()) {
				show(felledText, felledSound, felledPitch, lastTree, now);
			}
			return;
		}

		TreeType type = TreeType.fromMessage(message);
		if (type == null) return;
		// Recorded even with the gift title switched off, so a later felling can still name the tree.
		lastTree = type;
		if (!giftEnabled.value()) return;
		if (now - lastGiftTitle < GIFT_WINDOW_MILLIS) return;
		lastGiftTitle = now;
		show(giftText, giftSound, giftPitch, type, now);
	}

	/** Call once per client tick: releases anything still held and closes an unterminated block. */
	public void tick() {
		if (suppressor.endOfTick()) {
			emitHeld();
		}
	}

	/**
	 * Puts a withheld line back into chat.
	 *
	 * <p>Re-added rather than un-cancelled because the decision to keep it can only be made after
	 * the packet has already been handled; this is the same call the mod uses for its own
	 * messages, so the line lands looking exactly as the server sent it.
	 */
	private void emitHeld() {
		if (heldLine == null) return;
		Component line = heldLine;
		heldLine = null;
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(line);
	}

	@Override
	protected void onDisable() {
		// Switching off mid-block must not strand a line that was never shown.
		suppressor.endOfTick();
		emitHeld();
	}

	private void show(TextSetting text, TextSetting sound, NumberSetting pitch, TreeType type, long now) {
		shownText = substitute(text.value(), type);
		shownUntil = now + Math.round(duration.value() * 1000);
		playSound(sound, pitch);
	}

	private void playSound(TextSetting sound, NumberSetting pitch) {
		if (!playSound.value()) return;
		Identifier id = Identifier.tryParse(sound.value().trim());
		if (id == null) return;
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
		// A name that isn't a real sound just plays nothing; the title still shows.
		if (event == null) return;
		Minecraft.getInstance().getSoundManager()
			.play(SimpleSoundInstance.forUI(event, pitch.value(), volume.value()));
	}

	private String substitute(String text, TreeType type) {
		TreeType named = type == null ? lastTree : type;
		return text.replace(TREE_PLACEHOLDER, named == null ? UNKNOWN_TREE : named.displayName());
	}

	// ---- hud ----------------------------------------------------------------------------

	@Override
	public String id() {
		return HUD_ID;
	}

	@Override
	public String displayName() {
		return "Tree Broken Notifier";
	}

	@Override
	public boolean visible() {
		return isEnabled() && System.currentTimeMillis() < shownUntil;
	}

	@Override
	public int width(Font font) {
		return Math.round(font.width(previewText()) * textSize.value());
	}

	@Override
	public int height(Font font) {
		return Math.round(LINE_HEIGHT * textSize.value());
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) {
		long remaining = shownUntil - System.currentTimeMillis();
		if (remaining <= 0) return;
		int alpha = remaining >= FADE_MILLIS ? 255 : (int) (255 * remaining / FADE_MILLIS);
		int color = (alpha << 24) | (GuiTheme.TEXT_PRIMARY & 0x00FFFFFF);

		float scale = textSize.value();
		graphics.pose().pushMatrix();
		// Scaling about the panel's own corner keeps it where it was dragged, whatever the size.
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(font, shownText, 0, 0, color);
		graphics.pose().popMatrix();
	}

	/**
	 * What the box measures against when nothing is showing, so Move Elements has a real size.
	 *
	 * <p>The longer of the two titles, since either can appear in the spot being placed and the
	 * box has to hold whichever turns up.
	 */
	private String previewText() {
		if (!shownText.isEmpty()) return shownText;
		String gift = substitute(giftText.value(), null);
		String felled = substitute(felledText.value(), null);
		return gift.length() >= felled.length() ? gift : felled;
	}
}
