package geiler.addons.client.gui;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.module.impl.TikiHelperModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Scrollable list of the Tiki Helper's coordinates, with delete and add-from-crosshair. */
public class TikiCoordManagerScreen extends Screen {
	private static final int PANEL_WIDTH = 250;
	private static final int PANEL_HEIGHT = 220;
	private static final int HEADER_HEIGHT = 24;
	private static final int FOOTER_HEIGHT = 26;
	private static final int ROW_HEIGHT = 18;
	private static final int PADDING = 6;
	private static final int DELETE_SIZE = 14;
	private static final int SCROLLBAR_WIDTH = 3;

	private static final int DIALOG_WIDTH = 210;
	private static final int DIALOG_HEIGHT = 100;
	private static final int FIELD_WIDTH = 56;
	private static final int FIELD_HEIGHT = 16;
	private static final int FIELD_GAP = 8;

	/** Matches the vanilla reach the spec asks for when picking the block to pre-fill. */
	private static final double PICK_RANGE = 6.0;

	private final Screen parent;

	private int scroll;
	private boolean addMode;
	private String error;

	// Kept outside the widgets so a window resize (which re-runs init) doesn't wipe what was typed.
	private String pendingX = "0";
	private String pendingY = "0";
	private String pendingZ = "0";

	private EditBox xField;
	private EditBox yField;
	private EditBox zField;

	public TikiCoordManagerScreen(Screen parent) {
		super(Component.literal("Tiki Coordinates"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		if (!addMode) return;

		Rect dialog = dialogRect();
		int fieldWidth = fieldWidth();
		int fieldX = dialog.x + (dialog.w - (fieldWidth * 3 + FIELD_GAP * 2)) / 2;
		int fieldY = dialog.y + 34;
		xField = addField(fieldX, fieldY, pendingX, "X", value -> pendingX = value);
		yField = addField(fieldX + fieldWidth + FIELD_GAP, fieldY, pendingY, "Y", value -> pendingY = value);
		zField = addField(fieldX + 2 * (fieldWidth + FIELD_GAP), fieldY, pendingZ, "Z", value -> pendingZ = value);
		setInitialFocus(xField);
	}

	private EditBox addField(int x, int y, String value, String label, Consumer<String> responder) {
		EditBox box = new EditBox(this.font, x, y, fieldWidth(), FIELD_HEIGHT, Component.literal(label));
		box.setMaxLength(8);
		box.setValue(value);
		box.setResponder(responder);
		return addRenderableWidget(box);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		renderList(graphics, mouseX, mouseY);
		if (addMode) {
			renderAddDialog(graphics, mouseX, mouseY);
		}
		// Draws the EditBoxes last so they sit on top of the dialog panel.
		if (addMode) {
			Rect dialog = dialogRect();
			// EditBox extraction is separate from the dialog renderer, so it needs the same clip or
			// its widgets can escape on a very small framebuffer while the dialog is being resized.
			graphics.enableScissor(dialog.x, dialog.y, dialog.x + dialog.w, dialog.y + dialog.h);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.disableScissor();
		} else {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void renderList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int panelX = panelX();
		int panelY = panelY();
		Font font = this.font;
		// While the add dialog is up nothing behind it should react to the cursor.
		boolean hoverable = !addMode;

		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight);
		roundedRectBordered(graphics, panelX, panelY, panelWidth, panelHeight, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);

		List<BlockPos> coords = TikiCoords.all();
		graphics.text(font, "Tiki Coordinates", panelX + PADDING + 4, panelY + 9, TEXT_PRIMARY);
		String count = coords.size() + (coords.size() == 1 ? " entry" : " entries");
		graphics.text(font, count, panelX + panelWidth - PADDING - 4 - font.width(count), panelY + 9, TEXT_MUTED);
		graphics.fill(panelX + PADDING, panelY + HEADER_HEIGHT - 3, panelX + panelWidth - PADDING, panelY + HEADER_HEIGHT - 2, BORDER);

		Rect viewport = listViewport();
		clampScroll();

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		if (coords.isEmpty()) {
			graphics.text(font, "No coordinates yet.", viewport.x + 10, viewport.y + 6, TEXT_MUTED);
		}
		for (int i = 0; i < coords.size(); i++) {
			Rect row = rowRect(i);
			boolean rowHovered = hoverable && row.contains(mouseX, mouseY) && viewport.contains(mouseX, mouseY);
			if (rowHovered) {
				roundedRect(graphics, row.x, row.y, row.w, row.h - 2, 3, CATEGORY_HOVER, CATEGORY_HOVER);
			}
			BlockPos pos = coords.get(i);
			graphics.text(font, (i + 1) + ".", row.x + 6, row.y + 5, TEXT_MUTED);
			graphics.text(font, pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), row.x + 28, row.y + 5, TEXT_SECONDARY);

			Rect delete = deleteRect(row);
			boolean deleteHovered = hoverable && delete.contains(mouseX, mouseY) && viewport.contains(mouseX, mouseY);
			int deleteBackground = deleteHovered ? DANGER_HOVER : DANGER_BG;
			roundedRect(graphics, delete.x, delete.y, delete.w, delete.h, 3, deleteBackground, deleteBackground);
			graphics.centeredText(font, "X", delete.x + delete.w / 2, delete.y + 3, TEXT_PRIMARY);
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, coords.size() * ROW_HEIGHT);

		button(graphics, font, addButtonRect(), "Add Coordinate", mouseX, mouseY, hoverable, BUTTON_BG, BUTTON_HOVER);
		button(graphics, font, doneButtonRect(), "Done", mouseX, mouseY, hoverable, BUTTON_BG, BUTTON_HOVER);
		graphics.disableScissor();
	}

	private void renderAddDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = this.font;
		graphics.fill(0, 0, this.width, this.height, DIALOG_SHADE);

		Rect dialog = dialogRect();
		graphics.enableScissor(dialog.x, dialog.y, dialog.x + dialog.w, dialog.y + dialog.h);
		roundedRectBordered(graphics, dialog.x, dialog.y, dialog.w, dialog.h, RADIUS, MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);

		graphics.centeredText(font, "Add Coordinate", dialog.x + dialog.w / 2, dialog.y + 9, TEXT_PRIMARY);

		int fieldWidth = fieldWidth();
		int fieldX = dialog.x + (dialog.w - (fieldWidth * 3 + FIELD_GAP * 2)) / 2;
		String[] labels = {"X", "Y", "Z"};
		for (int i = 0; i < labels.length; i++) {
			graphics.centeredText(font, labels[i], fieldX + i * (fieldWidth + FIELD_GAP) + fieldWidth / 2, dialog.y + 24, TEXT_MUTED);
		}

		if (error != null) {
			graphics.centeredText(font, error, dialog.x + dialog.w / 2, dialog.y + 56, TEXT_ERROR);
		}

		button(graphics, font, confirmButtonRect(), "Confirm", mouseX, mouseY, true, BUTTON_BG, BUTTON_HOVER);
		button(graphics, font, cancelButtonRect(), "Cancel", mouseX, mouseY, true, DANGER_BG, DANGER_HOVER);
		graphics.disableScissor();
	}

	private void button(GuiGraphicsExtractor graphics, Font font, Rect rect, String label, int mouseX, int mouseY, boolean hoverable, int background, int hoverBackground) {
		int color = hoverable && rect.contains(mouseX, mouseY) ? hoverBackground : background;
		roundedRect(graphics, rect.x, rect.y, rect.w, rect.h, 4, color, color);
		graphics.centeredText(font, label, rect.x + rect.w / 2, rect.y + (rect.h - 8) / 2, TEXT_PRIMARY);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, Rect viewport, int contentHeight) {
		if (contentHeight <= viewport.h) return;
		int trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH;
		int thumbHeight = Math.max(16, viewport.h * viewport.h / contentHeight);
		int maxScroll = contentHeight - viewport.h;
		int thumbY = viewport.y + (viewport.h - thumbHeight) * Math.max(0, Math.min(maxScroll, scroll)) / maxScroll;
		graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, SCROLLBAR);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (addMode) {
			// Let the text fields claim focus first, then fall through to the dialog buttons.
			if (super.mouseClicked(event, doubleClick)) return true;
			if (confirmButtonRect().contains(event.x(), event.y())) {
				confirmAdd();
			} else if (cancelButtonRect().contains(event.x(), event.y())) {
				exitAddMode();
			}
			return true;
		}

		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}

		if (addButtonRect().contains(event.x(), event.y())) {
			enterAddMode();
			return true;
		}
		if (doneButtonRect().contains(event.x(), event.y())) {
			onClose();
			return true;
		}

		Rect viewport = listViewport();
		if (viewport.contains(event.x(), event.y())) {
			for (int i = 0; i < TikiCoords.size(); i++) {
				Rect row = rowRect(i);
				if (!row.contains(event.x(), event.y())) continue;
				if (deleteRect(row).contains(event.x(), event.y())) {
					TikiCoords.remove(i);
					ModConfig.save();
					TikiHelperModule.INSTANCE.requestScan();
				}
				return true;
			}
			return true;
		}

		if (panelRect().contains(event.x(), event.y())) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!addMode && listViewport().contains(mouseX, mouseY)) {
			scroll -= (int) Math.round(scrollY * ROW_HEIGHT);
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (addMode) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_ESCAPE -> {
					exitAddMode();
					return true;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					confirmAdd();
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	private void enterAddMode() {
		BlockPos looking = lookedAtBlock();
		pendingX = String.valueOf(looking.getX());
		pendingY = String.valueOf(looking.getY());
		pendingZ = String.valueOf(looking.getZ());
		error = null;
		addMode = true;
		rebuildWidgets();
	}

	private void exitAddMode() {
		addMode = false;
		error = null;
		xField = null;
		yField = null;
		zField = null;
		rebuildWidgets();
	}

	private void confirmAdd() {
		Integer x = parse(xField);
		Integer y = parse(yField);
		Integer z = parse(zField);
		if (x == null || y == null || z == null) {
			error = "X, Y and Z must be whole numbers";
			return;
		}
		if (!TikiCoords.add(new BlockPos(x, y, z))) {
			error = "That coordinate is already in the list";
			return;
		}
		ModConfig.save();
		TikiHelperModule.INSTANCE.requestScan();
		// Show the row that was just added.
		scroll = Integer.MAX_VALUE;
		exitAddMode();
	}

	private static Integer parse(EditBox field) {
		if (field == null) return null;
		try {
			return Integer.valueOf(field.getValue().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The block under the crosshair within {@value #PICK_RANGE} blocks, else where the player stands. */
	private BlockPos lookedAtBlock() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return BlockPos.ZERO;
		HitResult hit = player.pick(PICK_RANGE, 0.0f, false);
		if (hit.getType() == HitResult.Type.BLOCK) {
			return ((BlockHitResult) hit).getBlockPos();
		}
		return player.blockPosition();
	}

	private void clampScroll() {
		int maxScroll = Math.max(0, TikiCoords.size() * ROW_HEIGHT - listViewport().h);
		scroll = Math.max(0, Math.min(maxScroll, scroll));
	}

	private int panelX() {
		return (this.width - panelWidth()) / 2;
	}

	private int panelY() {
		return (this.height - panelHeight()) / 2;
	}

	private int panelWidth() {
		return Math.max(1, Math.min(PANEL_WIDTH, this.width - 12));
	}

	private int panelHeight() {
		return Math.max(1, Math.min(PANEL_HEIGHT, this.height - 12));
	}

	private Rect panelRect() {
		return new Rect(panelX(), panelY(), panelWidth(), panelHeight());
	}

	private Rect listViewport() {
		int top = panelY() + HEADER_HEIGHT;
		int height = panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT;
		return new Rect(panelX() + PADDING, top, Math.max(1, panelWidth() - 2 * PADDING),
			Math.max(1, height));
	}

	private Rect rowRect(int index) {
		Rect viewport = listViewport();
		return new Rect(viewport.x, viewport.y + index * ROW_HEIGHT - scroll, viewport.w, ROW_HEIGHT);
	}

	private Rect deleteRect(Rect row) {
		return new Rect(row.x + row.w - DELETE_SIZE - 6, row.y + (ROW_HEIGHT - DELETE_SIZE) / 2, DELETE_SIZE, DELETE_SIZE);
	}

	private Rect addButtonRect() {
		int y = panelY() + panelHeight() - FOOTER_HEIGHT + 4;
		int footerWidth = Math.max(1, panelWidth() - 2 * PADDING);
		int doneWidth = Math.min(56, Math.max(1, (footerWidth - 4) / 3));
		int addWidth = Math.max(1, footerWidth - doneWidth - 4);
		return new Rect(panelX() + PADDING, y, addWidth, 18);
	}

	private Rect doneButtonRect() {
		int y = panelY() + panelHeight() - FOOTER_HEIGHT + 4;
		int footerWidth = Math.max(1, panelWidth() - 2 * PADDING);
		int doneWidth = Math.min(56, Math.max(1, (footerWidth - 4) / 3));
		return new Rect(panelX() + panelWidth() - PADDING - doneWidth, y, doneWidth, 18);
	}

	private Rect dialogRect() {
		int width = dialogWidth();
		int height = dialogHeight();
		return new Rect((this.width - width) / 2, (this.height - height) / 2, width, height);
	}

	private Rect confirmButtonRect() {
		Rect dialog = dialogRect();
		int width = Math.max(1, (dialog.w - 34) / 2);
		return new Rect(dialog.x + 12, dialog.y + dialog.h - 28, width, 18);
	}

	private Rect cancelButtonRect() {
		Rect dialog = dialogRect();
		int width = Math.max(1, (dialog.w - 34) / 2);
		return new Rect(dialog.x + dialog.w - 12 - width, dialog.y + dialog.h - 28, width, 18);
	}

	private int dialogWidth() {
		return Math.max(1, Math.min(DIALOG_WIDTH, Math.min(this.width - 12, panelWidth() - 12)));
	}

	private int dialogHeight() {
		return Math.max(1, Math.min(DIALOG_HEIGHT, Math.min(this.height - 12, panelHeight() - 12)));
	}

	private int fieldWidth() {
		return Math.max(1, Math.min(FIELD_WIDTH, (dialogWidth() - 24 - FIELD_GAP * 2) / 3));
	}

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}
}
