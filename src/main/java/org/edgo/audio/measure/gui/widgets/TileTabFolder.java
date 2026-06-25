/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import org.edgo.audio.measure.gui.common.AbstractTabControl;

import lombok.Setter;
/**
 * A {@link CTabFolder} whose leading tabs carry a row of live "status
 * tiles" painted under the tab label.  Each tile is a small rounded
 * rectangle holding a short value (e.g. {@code "64k"}, {@code "ac"}) or a
 * coloured LED dot; together they give an at-a-glance summary of the
 * settings on that tab without expanding the tab body.  The FFT,
 * oscilloscope and frequency-response panes all share this exact look, so
 * the machinery lives here once instead of being triplicated.
 *
 * <h2>What the widget owns</h2>
 * <ul>
 *   <li>A custom {@link CTabFolderRenderer} that widens each custom tab to
 *       fit the wider of its label / tile row and draws the label
 *       top-aligned so the tiles sit cleanly underneath.</li>
 *   <li>A transparent spacer {@link Image} per custom tab — the lever
 *       {@code CTabFolder} actually uses to size a tab; resized on demand
 *       so a setting change visibly re-fits the tab.</li>
 *   <li>The paint listener that draws the tiles, plus per-tile hover
 *       tooltips (state-aware, resolved at paint time).</li>
 *   <li>Tab-body collapse on tab-strip double-click / Enter, exposed via
 *       {@link #setCollapsed(boolean)} for the screenshot path.</li>
 *   <li>The colour / font resources the tiles need, disposed with the
 *       folder.</li>
 * </ul>
 *
 * <h2>What the host pane supplies</h2>
 * <p>Everything pane-specific is funnelled through a single
 * {@link TileSource}: the {@link Tile} list for a tab, its fallback
 * tooltip, and any per-tab extra width.  The pane builds {@link Tile}s
 * with {@link Tile#text(String, String)} / {@link Tile#led(String)} from
 * its current preferences; the widget measures and paints them uniformly.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   TileTabFolder tabs = new TileTabFolder(parent, SWT.NONE);
 *   tabs.setCustomTabs(NUM_CUSTOM_TABS, source);   // count + tile source
 *   tabs.setLayoutData(gridData);                  // must be a GridData
 *   // ... build CTabItems (buildSettingsTab(tabs), ...) ...
 *   tabs.setSelection(0);
 *   tabs.init();                                    // capture labels, size, wire listeners
 * </pre>
 * Only the leading {@code customTabs} tabs get tiles; any trailing tabs
 * (Presets, Utility, Save, Load, …) render with the default behaviour.
 */
public class TileTabFolder extends CTabFolder {

    /** Inset of the tile row / label from the tab's left edge — narrower on
     *  the selected tab because its curve bulges further in. */
    private static final int SEL_INSET   = 11;
    private static final int UNSEL_INSET = 15;
    /** Y-offset of the top-aligned label inside the tab.  Sits the label +
     *  tile group 2 px higher than the original 3 px so the strip's top
     *  margin tightens; {@link #TILE_BOTTOM_GAP} is widened by the same 2 px
     *  so the label-to-tile distance is unchanged. */
    private static final int LABEL_TOP_Y = 1;
    /** Left-inset + right-margin reserved around a label when sizing a tab. */
    private static final int LABEL_PAD   = 14;
    /** Width added on top of {@code max(tileRow, label)} unless the
     *  {@link TileSource} overrides it per tab. */
    private static final int DEFAULT_EXTRA_PADDING = 28;
    /** Compact 18-px tile box: the text font ({@link #TILE_FONT_HEIGHT}) and
     *  the LED dot ({@link #LED_DIAMETER}) stay the same, so dropping the box
     *  height just trims the padding around them. */
    private static final int DEFAULT_TILE_HEIGHT   = 18;
    private static final int DEFAULT_STRIP_HEIGHT  = 46;
    /** Floor for a spacer image so a near-empty tab is still clickable. */
    private static final int MIN_TAB_WIDTH   = 50;
    /** Gap below the tile row.  4 px (was 2) raises the tile row by 2 px to
     *  match the label lift — see {@link #LABEL_TOP_Y}. */
    private static final int TILE_BOTTOM_GAP = 4;
    private static final int TILE_GAP        = 3;
    private static final int TILE_H_PADDING  = 4;
    private static final int TILE_CORNER     = 4;
    private static final int LED_DIAMETER    = 16;
    private static final int TILE_FONT_HEIGHT = 10;
    /** Upward nudge applied to chip text.  {@code textExtent} reports the
     *  full line box (the font's top-side internal leading included), so the
     *  naive {@code (h - te.y)/2} sits digits ~1 px low; lifting by 1 px
     *  optically centres them in the chip. */
    private static final int TILE_TEXT_LIFT  = 1;

    /** Supplies the pane-specific tile content; everything else is generic. */
    public interface TileSource {
        /** Tiles for a custom tab, left to right; empty for none. */
        List<Tile> tilesFor(int tabIndex);

        /** Fallback hover tooltip (already-resolved text) shown when the
         *  cursor is over the tab but not over a tile; {@code null} for none. */
        default String tabTooltip(int tabIndex) {
            return null;
        }

        /** Extra width added on top of {@code max(tileRow, label)} for the
         *  given tab.  Defaults to {@link #DEFAULT_EXTRA_PADDING}. */
        default int extraPadding(int tabIndex) {
            return DEFAULT_EXTRA_PADDING;
        }
    }

    /** One status tile: a short text chip or a coloured LED dot, each with
     *  its own hover tooltip. */
    public static final class Tile {

        /** Tile shape — a text chip or a filled LED dot. */
        public enum Kind {
            TEXT, LED;

            private Kind() {
            }
        }

        private final Kind   kind;
        private final String text;
        private final String tooltip;

        private Tile(Kind kind, String text, String tooltip) {
            this.kind    = kind;
            this.text    = text;
            this.tooltip = tooltip;
        }

        /** A rounded text chip showing {@code text}. */
        public static Tile text(String text, String tooltip) {
            return new Tile(Kind.TEXT, text, tooltip);
        }

        /** A filled LED dot — a square chip with a coloured circle, used to
         *  flag an "on" state (e.g. a live oscilloscope channel). */
        public static Tile led(String tooltip) {
            return new Tile(Kind.LED, null, tooltip);
        }
    }

    /** A painted sub-region of the tab strip with its hover tooltip. */
    private static final class Region {
        final Rectangle bounds;
        final String    tooltip;

        Region(Rectangle bounds, String tooltip) {
            this.bounds  = bounds;
            this.tooltip = tooltip;
        }
    }

    /** Pixel height of each tile.  22 px suits the FFT / scope tabs; the
     *  frequency-response pane uses a shorter strip. */
    @Setter private int tileHeight     = DEFAULT_TILE_HEIGHT;
    /** Forced height of the tab strip (label row + tile row). */
    @Setter private int tabStripHeight = DEFAULT_STRIP_HEIGHT;

    private int        customTabs;
    private TileSource tileSource;
    private String[]   tabLabels    = new String[0];
    private Image[]    spacerImages = new Image[0];
    private final List<Region> regions = new ArrayList<>();
    private boolean    collapsed;

    private Color tileBg;
    private Color tileFg;
    private Color tileLed;
    private Font  tileFont;

    public TileTabFolder(Composite parent, int style) {
        super(parent, style);
        setSimple(false);
        setRenderer(new TileRenderer(this));
    }

    /** Declares how many leading tabs carry tiles and where their content
     *  comes from.  Call before {@link #init()}. */
    public void setCustomTabs(int count, TileSource source) {
        this.customTabs   = count;
        this.tileSource   = source;
        this.tabLabels    = new String[count];
        this.spacerImages = new Image[count];
    }

    /** Captures each custom tab's label (then clears the {@link CTabItem}
     *  text so the renderer's top-aligned label is the only one drawn),
     *  sizes the strip and spacer images, and wires the paint / hover /
     *  collapse listeners.  Call after the tabs are built. */
    public void init() {
        if (isDisposed()) {
            return;
        }
        for (int i = 0; i < customTabs && i < getItemCount(); i++) {
            tabLabels[i] = getItem(i).getText();
            getItem(i).setText("");
        }
        setTabHeight(tabStripHeight);
        for (int i = 0; i < customTabs && i < getItemCount(); i++) {
            updateSpacerImage(i);
        }
        wireCollapse();
        wirePaintAndTooltips();
        addDisposeListener(e -> disposeResources());
    }

    /** Recomputes a custom tab's width and repaints so a related preference
     *  change visibly re-fits the tab on the spot. */
    public void refreshTab(int tabIndex) {
        if (isDisposed() || tabIndex < 0 || tabIndex >= customTabs) {
            return;
        }
        updateSpacerImage(tabIndex);
        layout(true, true);
        redraw();
    }

    /** True when the tab body is collapsed to just the strip. */
    public boolean isCollapsed() {
        return collapsed;
    }

    /** Collapses or expands the tab body.  Collapsed leaves only the tab
     *  strip visible — clicking a tab still switches it, but the controls
     *  underneath are hidden and the freed vertical space is returned to
     *  whatever sits above.  {@code CTabFolder.computeSize(hHint)} treats
     *  {@code hHint} as the client-area height and adds the strip trim on
     *  top, so {@code heightHint = 0} yields exactly "strip only". */
    public void setCollapsed(boolean wantCollapsed) {
        if (isDisposed() || !(getLayoutData() instanceof GridData gd)) {
            return;
        }
        collapsed = wantCollapsed;
        if (collapsed) {
            gd.heightHint              = 0;
            gd.verticalAlignment       = SWT.BEGINNING;
            gd.grabExcessVerticalSpace = false;
        } else {
            gd.heightHint              = SWT.DEFAULT;
            gd.verticalAlignment       = SWT.FILL;
            gd.grabExcessVerticalSpace = true;
        }
        AbstractTabControl parent = (AbstractTabControl) getParent();
        if (parent != null && !parent.isDisposed()) {
            parent.onTabCollapsed();
        }
    }

    // =========================================================================
    // Tile measuring + drawing
    // =========================================================================

    /** Minimum tab width = max(label, tile row) + the tab's extra padding. */
    private int requiredTabWidth(GC gc, int tabIndex) {
        if (tabIndex < 0 || tabIndex >= customTabs) {
            return 0;
        }
        Font prev = gc.getFont();
        gc.setFont(tileFont);
        int tilesW = tileRowWidth(gc, tabIndex);
        gc.setFont(getFont());
        String label = tabLabels[tabIndex];
        int labelW = (label != null && !label.isEmpty()) ? gc.textExtent(label).x + LABEL_PAD : 0;
        gc.setFont(prev);
        int extra = (tileSource != null) ? tileSource.extraPadding(tabIndex) : DEFAULT_EXTRA_PADDING;
        return Math.max(tilesW, labelW) + extra;
    }

    /** Total pixel width of a tab's tile row.  The GC must already have
     *  {@link #tileFont} selected so the widths match {@link #drawTiles}. */
    private int tileRowWidth(GC gc, int tabIndex) {
        List<Tile> tiles = tilesFor(tabIndex);
        if (tiles.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Tile t : tiles) {
            total += tileWidth(gc, t) + TILE_GAP;
        }
        return Math.max(0, total - TILE_GAP);
    }

    private int tileWidth(GC gc, Tile t) {
        if (t.kind == Tile.Kind.LED) {
            return tileHeight;
        }
        return gc.textExtent(t.text).x + 2 * TILE_H_PADDING;
    }

    /** Paints the tile row under a tab's label and rebuilds its hover
     *  regions.  Drawn by the paint listener AFTER the default tab
     *  rendering so the tiles overlay the lower part of the tab. */
    private void drawTiles(GC gc, Rectangle bounds, int tabIndex) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return;
        }
        if (tabIndex < 0 || tabIndex >= customTabs) {
            return;
        }
        List<Tile> tiles = tilesFor(tabIndex);
        if (tiles.isEmpty()) {
            return;
        }
        ensureResources();
        Font prev = gc.getFont();
        gc.setFont(tileFont);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        boolean selected = (getSelectionIndex() == tabIndex);
        int leftInset = selected ? SEL_INSET : UNSEL_INSET;
        int y = bounds.y + bounds.height - tileHeight - TILE_BOTTOM_GAP;
        int x = bounds.x + leftInset;
        for (Tile t : tiles) {
            int w = drawTile(gc, x, y, t);
            addRegion(x, y, w, tileHeight, t.tooltip);
            x += w + TILE_GAP;
        }
        gc.setFont(prev);
    }

    /** Draws one tile at {@code (x, y)} and returns the width it occupied. */
    private int drawTile(GC gc, int x, int y, Tile t) {
        if (t.kind == Tile.Kind.LED) {
            gc.setBackground(tileBg);
            gc.fillRoundRectangle(x, y, tileHeight, tileHeight, TILE_CORNER, TILE_CORNER);
            gc.setBackground(ledColor());
            int ledInset = (tileHeight - LED_DIAMETER) / 2;
            gc.fillOval(x + ledInset, y + ledInset, LED_DIAMETER, LED_DIAMETER);
            return tileHeight;
        }
        Point te = gc.textExtent(t.text);
        int w = te.x + 2 * TILE_H_PADDING;
        gc.setBackground(tileBg);
        gc.fillRoundRectangle(x, y, w, tileHeight, TILE_CORNER, TILE_CORNER);
        gc.setForeground(tileFg);
        gc.drawText(t.text, x + TILE_H_PADDING, y + (tileHeight - te.y) / 2 - TILE_TEXT_LIFT, true);
        return w;
    }

    private void addRegion(int x, int y, int w, int h, String tooltip) {
        if (tooltip == null || w <= 0 || h <= 0) {
            return;
        }
        regions.add(new Region(new Rectangle(x, y, w, h), tooltip));
    }

    private List<Tile> tilesFor(int tabIndex) {
        if (tileSource == null) {
            return List.of();
        }
        List<Tile> tiles = tileSource.tilesFor(tabIndex);
        return (tiles != null) ? tiles : List.of();
    }

    /** Resizes / recreates the transparent spacer image attached to a custom
     *  {@link CTabItem}.  The image width is the lever {@code CTabFolder}
     *  uses to size the tab (the item's own text is empty), so it must hold
     *  the wider of the current tile row / label. */
    private void updateSpacerImage(int tabIndex) {
        if (isDisposed() || tabIndex < 0 || tabIndex >= customTabs) {
            return;
        }
        if (getItemCount() <= tabIndex) {
            return;
        }
        ensureResources();
        int width;
        GC gc = new GC(this);
        try {
            width = Math.max(MIN_TAB_WIDTH, requiredTabWidth(gc, tabIndex));
        } finally {
            gc.dispose();
        }
        Image existing = spacerImages[tabIndex];
        if (existing != null && !existing.isDisposed()) {
            Rectangle b = existing.getBounds();
            if (b.width == width && b.height == 1) {
                return;
            }
            existing.dispose();
        }
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData id = new ImageData(width, 1, 24, palette);
        id.alphaData = new byte[width];      // all zeros → fully transparent
        Image img = new Image(getDisplay(), id);
        spacerImages[tabIndex] = img;
        getItem(tabIndex).setImage(img);
    }

    // =========================================================================
    // Listener wiring
    // =========================================================================

    private void wireCollapse() {
        // Double-click on the tab strip (above the client area) toggles the
        // body; body widgets keep their own double-click semantics.
        addListener(SWT.MouseDoubleClick, e -> {
            Rectangle ca = getClientArea();
            if (e.y < ca.y) {
                setCollapsed(!collapsed);
            }
        });
        // Enter when the folder has focus toggles too.  Left / right arrow
        // navigation is left to CTabFolder's built-in traversal.
        addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                setCollapsed(!collapsed);
                e.doit = false;
            }
        });
    }

    private void wirePaintAndTooltips() {
        // Paint AFTER the default renderer: overlay the tile row + rebuild
        // the hover-region map for the MouseMove handler below.
        addPaintListener(e -> {
            regions.clear();
            for (int i = 0; i < customTabs && i < getItemCount(); i++) {
                if (!getItem(i).isDisposed()) {
                    drawTiles(e.gc, getItem(i).getBounds(), i);
                }
            }
        });
        // Dynamic per-region hover tooltip — tile tips are resolved at paint
        // time so they reflect current state.  Set the tooltip on the hovered
        // CTabItem (its built-in tab tooltip takes priority over the
        // widget-level one).  Only update when the resolved region actually
        // changes so the OS hover delay isn't reset on every mouse-move pixel.
        final String[] currentTip = { null };
        final int[]    currentIdx = { -1 };
        addMouseMoveListener(e -> {
            String tip = null;
            for (Region r : regions) {
                if (r.bounds.contains(e.x, e.y)) {
                    tip = r.tooltip;
                    break;
                }
            }
            int hoverIdx = -1;
            for (int i = 0; i < getItemCount(); i++) {
                if (!getItem(i).isDisposed() && getItem(i).getBounds().contains(e.x, e.y)) {
                    hoverIdx = i;
                    break;
                }
            }
            if (tip == null && hoverIdx >= 0 && tileSource != null) {
                tip = tileSource.tabTooltip(hoverIdx);
            }
            if (hoverIdx != currentIdx[0] || !Objects.equals(currentTip[0], tip)) {
                currentTip[0] = tip;
                currentIdx[0] = hoverIdx;
                if (hoverIdx >= 0) {
                    getItem(hoverIdx).setToolTipText(tip);
                }
            }
        });
        addListener(SWT.MouseExit, e -> {
            if (currentIdx[0] >= 0 && currentIdx[0] < getItemCount()
                    && !getItem(currentIdx[0]).isDisposed()) {
                getItem(currentIdx[0]).setToolTipText(null);
            }
            currentTip[0] = null;
            currentIdx[0] = -1;
        });
    }

    // =========================================================================
    // Resources
    // =========================================================================

    private void ensureResources() {
        if (isDisposed()) {
            return;
        }
        Display d = getDisplay();
        if (tileBg == null) {
            tileBg = new Color(d, 0xE0, 0xE0, 0xE0);
        }
        if (tileFg == null) {
            tileFg = new Color(d, 0x20, 0x20, 0x20);
        }
        if (tileFont == null) {
            FontData[] fd = getFont().getFontData();
            for (FontData f : fd) {
                f.setHeight(TILE_FONT_HEIGHT);
            }
            tileFont = new Font(d, fd);
        }
    }

    private Color ledColor() {
        if (tileLed == null) {
            tileLed = new Color(getDisplay(), 0xFF, 0x20, 0x20);
        }
        return tileLed;
    }

    private void disposeResources() {
        for (Image img : spacerImages) {
            if (img != null && !img.isDisposed()) {
                img.dispose();
            }
        }
        if (tileBg   != null) tileBg.dispose();
        if (tileFg   != null) tileFg.dispose();
        if (tileLed  != null) tileLed.dispose();
        if (tileFont != null) tileFont.dispose();
    }

    // =========================================================================
    // Renderer
    // =========================================================================

    /** Widens each custom tab to fit its label / tile row and draws the
     *  label top-aligned (or centred when the tab has no tiles) so the tile
     *  row can sit underneath.  Trailing tabs render with the default look. */
    private final class TileRenderer extends CTabFolderRenderer {

        protected TileRenderer(CTabFolder parent) {
            super(parent);
        }

        @Override
        protected Point computeSize(int part, int state, GC gc, int wHint, int hHint) {
            Point p = super.computeSize(part, state, gc, wHint, hHint);
            if (part >= 0 && part < customTabs) {
                ensureResources();
                p.x = Math.max(p.x, requiredTabWidth(gc, part));
            }
            return p;
        }

        @Override
        protected void draw(int part, int state, Rectangle bounds, GC gc) {
            super.draw(part, state, bounds, gc);
            if (part < 0 || part >= customTabs) {
                return;
            }
            String label = tabLabels[part];
            if (label == null || label.isEmpty()) {
                return;
            }
            boolean selected = (state & SWT.SELECTED) != 0;
            int leftInset = selected ? SEL_INSET : UNSEL_INSET;
            Color fg = selected ? getSelectionForeground() : getForeground();
            if (fg == null) {
                fg = getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            }
            gc.setForeground(fg);
            Font prev = gc.getFont();
            gc.setFont(getFont());
            // Top-align when tiles sit underneath; otherwise centre the label
            // vertically so it doesn't float alone at the top of a header
            // sized for tile-bearing siblings (CTabFolder enforces a uniform
            // row height across all tabs).
            Point te = gc.textExtent(label);
            int textY = tilesFor(part).isEmpty()
                    ? bounds.y + Math.max(LABEL_TOP_Y, (bounds.height - te.y) / 2)
                    : bounds.y + LABEL_TOP_Y;
            gc.drawText(label, bounds.x + leftInset, textY, true);
            gc.setFont(prev);
        }
    }
}
