package geiler.addons.client.gui;

import geiler.addons.client.module.Category;

/**
 * Offline checks for the Click GUI's scroll bounds.
 *
 * <p>Both defects these cover were a wheel that could move past the end of a list: the category
 * column had no scroll at all, so the rows it could not fit were drawn outside the panel yet still
 * accepted a click, and the block picker clamped its wheel against the row count while its renderer
 * clamped against the visible row count, so it overshot and snapped back a frame later.
 */
public final class ClickGuiScrollChecks {

	private ClickGuiScrollChecks() { }

	public static void run() {
		checkResponsiveClickGuiSizing();
		checkHeaderGeometry();
		checkVisibleGridRows();
		checkScrollbarGutter();
		checkCategoryScroll();
		checkSearchInset();
		checkBlockPickerScroll();
		checkChangelogScrollPrecedence();
	}

	private static void checkHeaderGeometry() {
		int panelX = 40;
		int panelY = 30;
		int panelWidth = ClickGuiScreen.panelWidthForScreen(2048);
		int panelHeight = ClickGuiScreen.panelHeightForScreen(981);
		int headerHeight = ClickGuiScreen.baseHeaderHeightForPanelHeight(panelHeight);
		ClickGuiScreen.Rect logo = ClickGuiScreen.profileHeaderRectForLayout(
			panelX, panelY, panelWidth, panelHeight);
		ClickGuiScreen.Rect version = ClickGuiScreen.versionBadgeRectForLayout(
			panelX, panelY, panelWidth, panelHeight);
		int brandRoom = ClickGuiScreen.categoryBrandRoom(panelWidth, panelHeight);
		int trailingInset = Math.max(12,
			Math.round(ClickGuiScreen.headerInsetXForPanelWidth(panelWidth) * 0.4f));
		check(panelWidth == 1844 && panelHeight == 750,
			"the reference panel grows just enough to keep the search field width");
		check(headerHeight == 143 && logo.w() == 117 && logo.h() == 117,
			"the 2048x981 reference header produces the requested responsive logo size");
		check(version.w() == 86 && version.h() == 36,
			"the reference version badge scales to its requested bounds");
		check(brandRoom == 256,
			"the brand and its inset fit in the reference sidebar");
		check(version.x() == logo.x() + logo.w()
				+ ClickGuiScreen.logoVersionGapForPanelWidth(panelWidth)
			&& version.y() + version.h() / 2 == logo.y() + logo.h() / 2,
			"the version badge follows and vertically centers on the logo");
		check(version.x() + version.w() + trailingInset <= panelX + brandRoom,
			"the category rail reserves the brand and its trailing inset");

		int controlHeight = ClickGuiScreen.controlHeaderHeightForPanelHeight(panelHeight);
		int buttonSize = ClickGuiScreen.headerButtonSizeForLayout(panelWidth, panelHeight);
		ClickGuiScreen.Rect search = ClickGuiScreen.searchRectForLayout(
			panelX, panelY, panelWidth, panelHeight, brandRoom);
		ClickGuiScreen.Rect github = ClickGuiScreen.topBarRectForLayout(
			panelX, panelY, panelWidth, panelHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		check(controlHeight == 115 && buttonSize == 63 && search.h() == 44,
			"the larger brand band preserves the reference search height and toolbar size");
		check(search.w() == 1208,
			"the wider responsive panel preserves the reference search width");
		check(search.x() >= panelX + brandRoom
			&& search.x() + search.w() <= github.x(),
			"the search field stays between the brand rail and toolbar without overlap");
		check(version.x() + version.w() <= github.x(),
			"the brand badge ends before the toolbar begins");
		int previousRight = -1;
		for (ClickGuiScreen.TopBarIcon icon : ClickGuiScreen.TopBarIcon.values()) {
			ClickGuiScreen.Rect button = ClickGuiScreen.topBarRectForLayout(
				panelX, panelY, panelWidth, panelHeight, icon);
			check(button.w() == buttonSize && button.h() == buttonSize,
				"toolbar hit rectangles retain their reference size");
			check(button.x() >= panelX && button.x() + button.w() <= panelX + panelWidth,
				"toolbar hit rectangles stay inside the reference panel");
			check(previousRight < 0 || previousRight <= button.x(),
				"toolbar buttons do not overlap one another");
			previousRight = button.x() + button.w();
		}

		int compactWidth = ClickGuiScreen.panelWidthForScreen(854);
		int compactHeight = ClickGuiScreen.panelHeightForScreen(480);
		int compactBrandRoom = ClickGuiScreen.categoryBrandRoom(compactWidth, compactHeight);
		ClickGuiScreen.Rect compactSearch = ClickGuiScreen.searchRectForLayout(
			panelX, panelY, compactWidth, compactHeight, compactBrandRoom);
		ClickGuiScreen.Rect compactGithub = ClickGuiScreen.topBarRectForLayout(
			panelX, panelY, compactWidth, compactHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		ClickGuiScreen.Rect compactMove = ClickGuiScreen.topBarRectForLayout(
			panelX, panelY, compactWidth, compactHeight, ClickGuiScreen.TopBarIcon.MOVE_ELEMENTS);
		ClickGuiScreen.Rect compactVersion = ClickGuiScreen.versionBadgeRectForLayout(
			panelX, panelY, compactWidth, compactHeight);
		check(ClickGuiScreen.headerButtonSizeForLayout(compactWidth, compactHeight) == 31
			&& compactSearch.h() == 21,
			"compact screens preserve the former toolbar and search heights");
		check(compactSearch.x() >= panelX + compactBrandRoom
			&& compactSearch.x() + compactSearch.w() <= compactGithub.x(),
			"the compact search field remains bounded between brand and toolbar");
		check(compactVersion.x() + compactVersion.w() <= compactGithub.x()
			&& compactGithub.x() >= panelX
			&& compactMove.x() + compactMove.w() <= panelX + compactWidth,
			"compact brand and toolbar bounds remain separate and inside the panel");

		int narrowWidth = ClickGuiScreen.panelWidthForScreen(240);
		int narrowHeight = ClickGuiScreen.panelHeightForScreen(160);
		int narrowBrandRoom = ClickGuiScreen.categoryBrandRoom(narrowWidth, narrowHeight);
		ClickGuiScreen.Rect narrowVersion = ClickGuiScreen.versionBadgeRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight);
		ClickGuiScreen.Rect narrowGithub = ClickGuiScreen.topBarRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight, ClickGuiScreen.TopBarIcon.GITHUB);
		ClickGuiScreen.Rect narrowSearch = ClickGuiScreen.searchRectForLayout(
			panelX, panelY, narrowWidth, narrowHeight, narrowBrandRoom);
		check(narrowVersion.x() + narrowVersion.w() <= narrowGithub.x()
			&& narrowGithub.x() >= panelX
			&& ClickGuiScreen.topBarRectForLayout(panelX, panelY, narrowWidth, narrowHeight,
				ClickGuiScreen.TopBarIcon.MOVE_ELEMENTS).x()
				+ ClickGuiScreen.headerButtonSizeForLayout(narrowWidth, narrowHeight) <= panelX + narrowWidth
			&& narrowSearch.w() == 0,
			"the search field is hidden when it cannot fit between the brand and toolbar");
	}

	private static void checkResponsiveClickGuiSizing() {
		check(ClickGuiScreen.panelWidthForScreen(1582) == 1424,
			"the reference width uses the mockup panel proportion");
		check(ClickGuiScreen.panelHeightForScreen(1024) == 783,
			"the reference height uses the mockup panel proportion");
		check(ClickGuiScreen.panelWidthForScreen(854) == 769
			&& ClickGuiScreen.panelHeightForScreen(480) == 367,
			"the compact acceptance size retains a centered, responsive panel");
		check(ClickGuiScreen.cardColumnsForModuleWidth(500) == 2
			&& ClickGuiScreen.cardColumnsForModuleWidth(720) == 3
			&& ClickGuiScreen.cardColumnsForModuleWidth(840) == 3,
			"module cards use three columns when the content width supports readable cards");
		check(ClickGuiScreen.cardHeightForPanelHeight(367) == 63
			&& ClickGuiScreen.cardHeightForPanelHeight(783) == 135,
			"card height scales with the panel instead of staying pixel-fixed");
		check(ClickGuiScreen.cardGapForPanelWidth(733) == 11
			&& ClickGuiScreen.cardGapForPanelWidth(1357) == 20,
			"card spacing scales with the panel width");
		checkCompactModuleCardActions();
		check(ClickGuiScreen.gridInsetForPanelWidth(733) == 14
			&& ClickGuiScreen.gridInsetForPanelWidth(1357) == 26,
			"card margins scale to the approved reference proportions");
		check(ClickGuiScreen.categoryRowHeightForPanelHeight(367) == 36
			&& ClickGuiScreen.categoryRowHeightForPanelHeight(783) == 77,
			"category controls scale with panel height");
	}

	private static void checkCompactModuleCardActions() {
		ClickGuiScreen.Rect compact = new ClickGuiScreen.Rect(12, 18, 160, 63);
		ClickGuiScreen.Rect toggle = ClickGuiScreen.cardSwitchRect(compact);
		ClickGuiScreen.Rect favorite = ClickGuiScreen.moduleFavoriteHitRect(compact, true, toggle.y());
		boolean overlaps = favorite.x() < toggle.x() + toggle.w() && favorite.x() + favorite.w() > toggle.x()
			&& favorite.y() < toggle.y() + toggle.h() && favorite.y() + favorite.h() > toggle.y();
		check(!overlaps, "the compact module favorite hit target never intercepts the enable switch");
		check(favorite.contains(favorite.x() + favorite.w() / 2.0, favorite.y() + favorite.h() / 2.0)
			&& toggle.contains(toggle.x() + toggle.w() / 2.0, toggle.y() + toggle.h() / 2.0),
			"heart and switch retain separate clickable centers on compact module cards");
		check(favorite.x() + favorite.w() / 2 == toggle.x() + toggle.w() / 2,
			"the favorite heart and enable switch share one horizontal alignment axis");
	}

	private static void checkScrollbarGutter() {
		int viewportWidth = 200;
		int columns = 3;
		int gap = 10;
		int contentWidth = ClickGuiScreen.gridCardContentWidth(viewportWidth);
		int cardWidth = ClickGuiScreen.cardWidthForGridViewport(viewportWidth, columns, gap);
		check(contentWidth == 191, "module cards reserve the scrollbar and its side padding");
		check(columns * cardWidth + (columns - 1) * gap <= contentWidth,
			"the final card stays inside the content area, clear of the scrollbar hit strip");
	}

	private static void checkVisibleGridRows() {
		checkVisibleGridRows(0, 100, 180, 110, 6, "top of the grid");
		checkVisibleGridRows(40, 100, 180, 110, 6, "a card ending at the viewport bottom");
		checkVisibleGridRows(100, 100, 180, 110, 6, "a card ending at the viewport top");
		checkVisibleGridRows(105, 100, 180, 110, 6, "partial cards at both viewport edges");
		checkVisibleGridRows(470, 100, 180, 110, 6, "the bottom of the grid");
		checkVisibleGridRows(0, 100, 800, 110, 2, "content shorter than the viewport");
	}

	private static void checkVisibleGridRows(int scroll, int cardHeight, int viewportHeight,
		int rowStep, int rowCount, String scenario) {
		int first = Math.max(0,
			ClickGuiScreen.firstGridRowIntersectingViewport(scroll, cardHeight, rowStep));
		int last = Math.min(rowCount - 1,
			ClickGuiScreen.lastGridRowIntersectingViewport(scroll, viewportHeight, rowStep));
		for (int row = 0; row < rowCount; row++) {
			int top = row * rowStep - scroll;
			boolean intersects = top + cardHeight > 0 && top < viewportHeight;
			boolean selected = row >= first && row <= last;
			check(intersects == selected,
				"visible-row culling matches full geometry for " + scenario + " at row " + row);
		}
	}

	private static void checkCategoryScroll() {
		check(ClickGuiScreen.categoryContentHeight() == Category.values().length * 22,
			"the compatibility helper still reports its baseline row height");
		int compactContent = ClickGuiScreen.categoryContentHeightForPanelHeight(367);
		check(compactContent == 300, "the compact rail reserves its scaled header gap and eight rows");
		check(ClickGuiScreen.categoryMaxScroll(367, 298) == 2,
			"the compact rail can scroll the final clipped pixels into view");
		int referenceContent = ClickGuiScreen.categoryContentHeightForPanelHeight(783);
		check(referenceContent == 643, "the reference rail has responsive row spacing");
		check(ClickGuiScreen.categoryMaxScroll(783, 637) == 6,
			"the full-height reference rail clamps to the small update-banner overflow");
		check(ClickGuiScreen.categoryMaxScroll(referenceContent) == 0,
			"a column that fits every row does not scroll");
		check(ClickGuiScreen.categoryMaxScroll(referenceContent * 2) == 0,
			"a column taller than its rows does not scroll");
		check(ClickGuiScreen.categoryMaxScroll(1) == ClickGuiScreen.categoryContentHeight() - 1,
			"a one-pixel baseline column can still reach its last row");
		check(ClickGuiScreen.categoryMaxScroll(0) == ClickGuiScreen.categoryContentHeight() - 1,
			"an unmeasurable column falls back to the smallest usable height");
	}

	private static void checkSearchInset() {
		int fieldHeight = 24;
		int iconLeft = 8;
		int iconSize = 18;
		check(ClickGuiScreen.searchTextInsetForHeight(fieldHeight) == iconLeft + iconSize + 6,
			"search text starts after the rendered icon and a six-pixel gap");
		check(ClickGuiScreen.searchTextViewportWidth(180, fieldHeight) == 140,
			"search text hit testing reserves the same icon inset and right padding as rendering");
		check(ClickGuiScreen.searchTextFontWidth(180, fieldHeight) == 184,
			"scaled search rendering and mouse character mapping share the font-space width");
		check(Math.abs(ClickGuiScreen.textOffsetForMouse(138, 100, 0.76f) - 50.0) < 0.001,
			"mouse hit testing converts scaled screen pixels to the same text-space coordinates");
		check(ClickGuiScreen.searchTextViewportWidth(20, fieldHeight) == 1
			&& ClickGuiScreen.searchTextFontWidth(20, fieldHeight) >= 1,
			"narrow search fields keep a bounded, usable text viewport");
	}

	private static void checkBlockPickerScroll() {
		// Ten options in a 96-pixel viewport show four rows, so six may be scrolled past.
		check(BlockPickerScreen.maxScroll(10, 96) == 6,
			"the wheel clamp matches the render clamp, got " + BlockPickerScreen.maxScroll(10, 96));
		check(BlockPickerScreen.maxScroll(4, 96) == 0, "a list that fits does not scroll");
		check(BlockPickerScreen.maxScroll(3, 96) == 0, "a shorter list does not scroll");
		check(BlockPickerScreen.maxScroll(0, 96) == 0, "an empty list does not scroll");
		check(BlockPickerScreen.maxScroll(10, 0) == 9,
			"a viewport too short for one row still lets every option be reached");
		check(BlockPickerScreen.maxScroll(10, 24) == 9, "exactly one visible row behaves as one row");
	}

	private static void checkChangelogScrollPrecedence() {
		check(ClickGuiScreen.scrollTarget(true, true, true, true, true) == ClickGuiScreen.ScrollTarget.CHANGELOG,
			"an open changelog captures wheel input before overlapping category, settings, and module regions");
		check(ClickGuiScreen.scrollTarget(false, true, true, true, true) == ClickGuiScreen.ScrollTarget.CATEGORY,
			"without the modal, the category rail keeps its existing precedence over the main panel");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
