package geiler.addons.client.module.impl;

/** One locally stored 18×18 button anchored to an inventory-screen grid cell. */
public final class InventoryButtonPlacement {
	public enum Appearance { ITEM, TEXT, PNG }

	private final int id;
	private int macroId = -1;
	private int gridX;
	private int gridY;
	private boolean slotAligned = true;
	private Appearance appearance = Appearance.ITEM;
	private String value = "minecraft:stone";
	private String hoverTooltip = "";

	public InventoryButtonPlacement(int id) {
		this.id = Math.max(0, id);
	}

	public InventoryButtonPlacement(InventoryButtonPlacement source) {
		this(source.id);
		macroId = source.macroId;
		gridX = source.gridX;
		gridY = source.gridY;
		slotAligned = source.slotAligned;
		appearance = source.appearance;
		value = source.value;
		hoverTooltip = source.hoverTooltip;
	}

	public int id() { return id; }
	public int macroId() { return macroId; }
	public int gridX() { return gridX; }
	public int gridY() { return gridY; }
	public boolean slotAligned() { return slotAligned; }
	public Appearance appearance() { return appearance; }
	public String value() { return value; }
	public String hoverTooltip() { return hoverTooltip; }

	public void setMacroId(int value) { macroId = Math.max(-1, value); }
	public void setGrid(int x, int y) {
		gridX = Math.max(-128, Math.min(128, x));
		gridY = Math.max(-128, Math.min(128, y));
		slotAligned = true;
	}
	public void setSlotAligned(boolean value) { slotAligned = value; }
	public void setAppearance(Appearance value, String content) {
		appearance = value == null ? Appearance.ITEM : value;
		String trimmed = content == null ? "" : content.trim();
		this.value = trimmed.substring(0, Math.min(256, trimmed.length()));
	}
	public void setHoverTooltip(String value) {
		String trimmed = value == null ? "" : value.trim();
		hoverTooltip = trimmed.substring(0, Math.min(256, trimmed.length()));
	}
}
