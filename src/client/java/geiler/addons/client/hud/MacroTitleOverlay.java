package geiler.addons.client.hud;

import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroTitleTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/** One transient, centered macro title. A later title replaces the one currently being shown. */
public final class MacroTitleOverlay {
	private static State current;

	private MacroTitleOverlay() { }

	public static void show(MacroStep.Title title) {
		if (title == null || title.text().isBlank()) return;
		long now = System.nanoTime();
		long total = (long) title.fadeInMillis() + title.holdMillis() + title.fadeOutMillis();
		current = new State(title.text(), title.font(), title.scale(), title.textColor(),
			title.showBackground(), title.backgroundColor(), title.backgroundOpacity(),
			title.fadeInMillis(), title.holdMillis(), title.fadeOutMillis(), now,
			now + Math.max(1, total) * 1_000_000L);
	}

	public static void render(GuiGraphicsExtractor graphics) {
		State state = current;
		if (state == null) return;
		long now = System.nanoTime();
		if (now >= state.expiresAt) {
			current = null;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options.hideGui || minecraft.font == null) return;
		float alpha = opacity(state, now);
		Identifier fontId = Identifier.tryParse(state.font);
		MutableComponent title = Component.literal(state.text);
		if (fontId != null) title = title.withStyle(style -> style.withFont(new FontDescription.Resource(fontId)));
		Font font = minecraft.font;
		float scale = state.scale;
		float textWidth = font.width(title) * scale;
		float textHeight = font.lineHeight * scale;
		float x = (minecraft.getWindow().getGuiScaledWidth() - textWidth) / 2.0f;
		float y = (minecraft.getWindow().getGuiScaledHeight() - textHeight) / 2.0f;
		float paddingX = 12.0f * scale;
		float paddingY = 6.0f * scale;
		if (state.showBackground && state.backgroundOpacity > 0) {
			int backgroundAlpha = Math.round(state.backgroundOpacity * alpha);
			int background = (backgroundAlpha << 24) | (state.backgroundColor & 0x00FFFFFF);
			graphics.fill((int) (x - paddingX), (int) (y - paddingY),
				(int) (x + textWidth + paddingX), (int) (y + textHeight + paddingY), background);
		}
		int text = withAlpha(state.textColor, alpha);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(font, title, 0, 0, text);
		graphics.pose().popMatrix();
	}

	private static float opacity(State state, long now) {
		long elapsed = Math.max(0, (now - state.startedAt) / 1_000_000L);
		return MacroTitleTiming.opacity(elapsed, state.fadeInMillis, state.holdMillis, state.fadeOutMillis);
	}

	private static int withAlpha(int color, float multiplier) {
		int alpha = Math.round((color >>> 24) * Math.max(0, Math.min(1, multiplier)));
		return (alpha << 24) | (color & 0x00FFFFFF);
	}

	private record State(String text, String font, float scale, int textColor,
		boolean showBackground, int backgroundColor, int backgroundOpacity,
		int fadeInMillis, int holdMillis, int fadeOutMillis, long startedAt, long expiresAt) { }
}
