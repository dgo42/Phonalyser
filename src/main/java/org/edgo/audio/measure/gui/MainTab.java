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

package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.enums.TabOrientation;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.OscilloscopeController;
import org.edgo.audio.measure.gui.sound.SharedCapture;

import lombok.extern.log4j.Log4j2;

/**
 * Top-level tab host for the main window.  Owns one
 * {@link MultifunctionalTab} (the three-pane SashForm layout) and one
 * {@link FrequencyResponseTab} (placeholder for the FR measurement UI),
 * and chooses between two presentations depending on
 * {@link Preferences#getTabOrientation()}:
 *
 * <ul>
 *   <li>{@code "TOP"} — conventional SWT {@link TabFolder} with text
 *       labels and a small icon per tab.</li>
 *   <li>{@code "LEFT"} — custom vertical sidebar of large 48×48 icon
 *       buttons with the label rendered underneath.  The selected tab's
 *       content is shown in a {@link StackLayout} to the right.  SWT
 *       offers no built-in left-tab widget, so this is composed by hand.</li>
 * </ul>
 *
 * <p>Switching orientation re-creates the shell (see
 * {@link MainWindow#requestRecreate()}) so the layout takes effect
 * immediately.  All shell-bound resources allocated here register their
 * dispose listeners against the host shell.
 */
@Log4j2
public final class MainTab {

    /** Sidebar tab icon size in pixels — 2 normally, 24 when the user
     *  ticks "Small icons in main tab" in Look &amp; Feel preferences. */
    private static final int ICON_BIG_PX   = 42;
    private static final int ICON_SMALL_PX = 24;
    /** Top-tab icon sizes are smaller — the SWT TabFolder enforces a
     *  height around the system font metric, and asking for a 48 px icon
     *  would force the runtime to stretch it (visibly distorting the
     *  aspect since the tab is taller-than-wide on most platforms). */
    private static final int TOP_ICON_BIG_PX   = 24;
    private static final int TOP_ICON_SMALL_PX = 16;
    /** Width of the left sidebar — sized for an icon centred on top with
     *  a vertically-rotated label underneath (label width = its line
     *  height once rotated, not its run length). */
    private static final int LEFT_BAR_WIDTH_BIG_PX   = 56;
    private static final int LEFT_BAR_WIDTH_SMALL_PX = 42;

    private final Display display;
    private final Shell   shell;

    private MultifunctionalTab    multifunctional;
    @SuppressWarnings("unused") // Tab class instances are created for their UI; we don't need to keep a method reference yet.
    private FrequencyResponseTab  frequencyResponse;

    public MainTab(Shell shell) {
        this.shell   = shell;
        this.display = shell.getDisplay();
        // Eager init of the SharedCapture singleton so its MessageBus
        // responder is registered BEFORE any pane fires a CAPTURE_ACQUIRE
        // request — otherwise the request returns null and the user
        // thinks the device failed to open.
        SharedCapture.instance();
        buildHost();
    }

    /** Two-phase setup: {@link MainWindow} calls this after it has read
     *  the natural shell size (and clamped the shell's minimum to it).
     *  See {@link MultifunctionalTab#applySavedLayoutState()} for the
     *  rationale around ordering. */
    public void applySavedLayoutState() {
        if (multifunctional != null) multifunctional.applySavedLayoutState();
        registerWindowSizePersistence();
    }

    /** Smallest shell size that fits the contained tabs' natural layout
     *  — used by {@link MainWindow} to set the shell's minimum size. */
    public Point computeNaturalShellSize() {
        return shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    }

    public Runnable pauseForDialog() {
        return (multifunctional != null) ? multifunctional.pauseForDialog() : () -> {};
    }

    public void stopForRecreate() {
        if (multifunctional != null) multifunctional.stopForRecreate();
    }

    public OscilloscopeController getOscController() {
        return (multifunctional != null) ? multifunctional.getOscController() : null;
    }

    public GeneratorPane getGenPane() {
        return (multifunctional != null) ? multifunctional.getGenPane() : null;
    }

    public FftPane getFftPane() {
        return (multifunctional != null) ? multifunctional.getFftPane() : null;
    }

    // -------------------------------------------------------------------------
    // Host construction — top tabs vs. left sidebar
    // -------------------------------------------------------------------------

    private void buildHost() {
        TabOrientation orientation = Preferences.instance().getTabOrientation();
        if (orientation == TabOrientation.LEFT) {
            buildLeftSidebar();
        } else {
            buildTopTabs();
        }
    }

    private void buildTopTabs() {
        int iconPx = Preferences.instance().isSmallIconsInMainTab()
                ? TOP_ICON_SMALL_PX
                : TOP_ICON_BIG_PX;
        TabFolder tabFolder = new TabFolder(shell, SWT.NONE);

        TabItem multiItem = new TabItem(tabFolder, SWT.NONE);
        multiItem.setText(I18n.t("tab.multifunctional"));
        multiItem.setImage(renderTabIcon(SvgPaths.SWISS_ARMY_KNIFE, iconPx));
        Composite multiContent = new Composite(tabFolder, SWT.NONE);
        multiContent.setLayout(new FillLayout());
        multiItem.setControl(multiContent);
        multifunctional = new MultifunctionalTab(multiContent, shell);

        TabItem frItem = new TabItem(tabFolder, SWT.NONE);
        frItem.setText(I18n.t("tab.frequencyResponse"));
        frItem.setImage(renderTabIcon(SvgPaths.RIAA_IEC_CURVE, iconPx));
        Composite frContent = new Composite(tabFolder, SWT.NONE);
        frItem.setControl(frContent);
        frequencyResponse = new FrequencyResponseTab(frContent);

        // Restore the last-selected tab; default to Multifunctional.
        int sel = clampSelection(Preferences.instance().getActiveTabIndex(), tabFolder.getItemCount());
        tabFolder.setSelection(sel);
        // Persist selection on dispose so the next launch reopens the
        // same tab regardless of orientation.
        shell.addDisposeListener(e -> {
            if (!tabFolder.isDisposed()) {
                Preferences.instance().setActiveTabIndex(tabFolder.getSelectionIndex());
                Preferences.instance().save();
            }
        });
    }

    private void buildLeftSidebar() {
        int iconPx = iconSizePx();
        int barWidth = Preferences.instance().isSmallIconsInMainTab()
                ? LEFT_BAR_WIDTH_SMALL_PX
                : LEFT_BAR_WIDTH_BIG_PX;
        Composite root = new Composite(shell, SWT.NONE);
        GridLayout rootLayout = new GridLayout(2, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        rootLayout.horizontalSpacing = 0;
        rootLayout.verticalSpacing = 0;
        root.setLayout(rootLayout);

        Composite sidebar = new Composite(root, SWT.NONE);
        GridData sidebarGd = new GridData(SWT.LEFT, SWT.FILL, false, true);
        sidebarGd.widthHint = barWidth;
        sidebar.setLayoutData(sidebarGd);
        GridLayout sbLayout = new GridLayout(1, false);
        sbLayout.marginWidth  = 4;
        sbLayout.marginHeight = 4;
        sbLayout.verticalSpacing = 4;
        sidebar.setLayout(sbLayout);

        Composite contentHost = new Composite(root, SWT.NONE);
        contentHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        StackLayout stack = new StackLayout();
        contentHost.setLayout(stack);

        Composite multiContent = new Composite(contentHost, SWT.NONE);
        multiContent.setLayout(new FillLayout());
        multifunctional = new MultifunctionalTab(multiContent, shell);

        Composite frContent = new Composite(contentHost, SWT.NONE);
        frequencyResponse = new FrequencyResponseTab(frContent);

        Color hoverBg    = new Color(display, 0xE6, 0xEE, 0xF8);
        Color selectedBg = new Color(display, 0xCD, 0xDD, 0xF5);
        shell.addDisposeListener(e -> {
            hoverBg.dispose();
            selectedBg.dispose();
        });

        SidebarButton[] buttons = new SidebarButton[2];
        buttons[0] = new SidebarButton(sidebar,
                renderTabIcon(SvgPaths.SWISS_ARMY_KNIFE, iconPx),
                I18n.t("tab.multifunctional"),
                multiContent, hoverBg, selectedBg);
        buttons[1] = new SidebarButton(sidebar,
                renderTabIcon(SvgPaths.RIAA_IEC_CURVE, iconPx),
                I18n.t("tab.frequencyResponse"),
                frContent, hoverBg, selectedBg);

        for (SidebarButton b : buttons) {
            b.setSiblings(buttons);
            b.setStack(stack, contentHost);
        }

        // Restore the last-selected tab; default to Multifunctional.
        int sel = clampSelection(Preferences.instance().getActiveTabIndex(), buttons.length);
        buttons[sel].select();

        // Persist active-tab selection on dispose.
        shell.addDisposeListener(e -> {
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i].isSelected()) {
                    Preferences.instance().setActiveTabIndex(i);
                    Preferences.instance().save();
                    return;
                }
            }
        });
    }

    private static int clampSelection(int v, int n) {
        if (v < 0) return 0;
        if (v >= n) return n - 1;
        return v;
    }

    /** Left-sidebar icon size driven by the "Small icons in main tab" preference. */
    private static int iconSizePx() {
        return Preferences.instance().isSmallIconsInMainTab() ? ICON_SMALL_PX : ICON_BIG_PX;
    }

    /** Lazily renders an SVG icon to an {@link Image} at the requested
     *  pixel height through {@link IconUtils}.  Returns {@code null} if the
     *  icon is missing so the caller can fall back to a label-only tab. */
    private Image renderTabIcon(String svgPath, int heightPx) {
        try {
            return IconUtils.instance().renderAtHeightColored(display, svgPath, heightPx);
        } catch (RuntimeException ex) {
            log.warn("Tab icon {} failed to render: {}", svgPath, ex.getMessage());
            return null;
        }
    }

    private void registerWindowSizePersistence() {
        Point initial = shell.getSize();
        int[] lastNormal = { initial.x, initial.y };

        shell.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (!shell.getMaximized() && !shell.getMinimized()) {
                Point s = shell.getSize();
                lastNormal[0] = s.x;
                lastNormal[1] = s.y;
            }
        }));

        shell.addDisposeListener(e -> {
            Preferences.instance().setWindowWidth (lastNormal[0]);
            Preferences.instance().setWindowHeight(lastNormal[1]);
            Preferences.instance().save();
        });
    }

    /** One row in the left sidebar — a {@link Canvas} that paints the
     *  icon centered horizontally with the label wrapped underneath.
     *  Using a Canvas (instead of nested Labels with a paint listener)
     *  means our background fill is the only paint that runs for this
     *  widget, so the icon and text are guaranteed to land on top
     *  regardless of platform-specific Label paint order. */
    private static final class SidebarButton extends Canvas {
        private static final int PAD_Y = 2;
        private static final int GAP_Y = 2;

        private final Image icon;
        private final String label;
        private final Color hoverBg;
        private final Color selectedBg;
        private final Composite content;
        private final Font labelFont;
        private SidebarButton[] siblings;
        private StackLayout stack;
        private Composite contentHost;
        private boolean selected;
        private boolean hovered;

        SidebarButton(Composite parent, Image icon, String label,
                      Composite content, Color hoverBg, Color selectedBg) {
            super(parent, SWT.DOUBLE_BUFFERED);
            this.icon       = icon;
            this.label      = label;
            this.hoverBg    = hoverBg;
            this.selectedBg = selectedBg;
            this.content    = content;

            FontData fd = getFont().getFontData()[0];
            this.labelFont = new Font(parent.getDisplay(), fd.getName(),
                                      Math.max(8, fd.getHeight() - 1), SWT.NORMAL);
            addDisposeListener(e -> { if (!labelFont.isDisposed()) labelFont.dispose(); });

            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
            gd.heightHint = computePreferredHeight();
            setLayoutData(gd);

            addPaintListener(this::onPaint);
            addListener(SWT.MouseDown,  e -> select());
            addListener(SWT.MouseEnter, e -> setHovered(true));
            addListener(SWT.MouseExit,  e -> setHovered(false));
        }

        void setSiblings(SidebarButton[] siblings) {
            this.siblings = siblings;
        }

        void setStack(StackLayout stack, Composite host) {
            this.stack = stack;
            this.contentHost = host;
        }

        boolean isSelected() {
            return selected;
        }

        void select() {
            if (siblings != null) {
                for (SidebarButton b : siblings) {
                    if (b != this && b.selected) {
                        b.selected = false;
                        b.redraw();
                    }
                }
            }
            selected = true;
            redraw();
            if (stack != null && contentHost != null && !contentHost.isDisposed()) {
                stack.topControl = content;
                contentHost.layout();
            }
        }

        private void setHovered(boolean h) {
            if (hovered == h) return;
            hovered = h;
            redraw();
        }

        private int computePreferredHeight() {
            int iconH = (icon != null) ? icon.getBounds().height : 0;
            // Rotated label occupies its full text run vertically; estimate
            // generously so the longest expected tab name (e.g.
            // "Frequency response") fits without clipping.
            int labelLen = 4 + label.length() * 7;
            return PAD_Y + iconH + GAP_Y + labelLen + PAD_Y;
        }

        private void onPaint(PaintEvent e) {
            Point size = getSize();
            // Antialias + high-quality bitmap scaling so the icon stays
            // crisp even when the host slightly resizes the tile.
            e.gc.setAntialias(SWT.ON);
            e.gc.setInterpolation(SWT.HIGH);
            e.gc.setTextAntialias(SWT.ON);

            if (selected) {
                e.gc.setBackground(selectedBg);
            } else if (hovered) {
                e.gc.setBackground(hoverBg);
            } else {
                e.gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            }
            e.gc.fillRectangle(0, 0, size.x, size.y);

            int iconH = 0;
            if (icon != null && !icon.isDisposed()) {
                Rectangle ib = icon.getBounds();
                int ix = Math.max(0, (size.x - ib.width) / 2);
                int iy = PAD_Y;
                e.gc.drawImage(icon, ix, iy);
                iconH = ib.height;
            }

            // Label — painted vertically (rotated 90° counter-clockwise)
            // so it reads bottom-to-top below the icon.  Mirrors the
            // collapsed PaneTitle treatment in narrow strips so the
            // sidebar feels consistent with the rest of the app.
            e.gc.setFont(labelFont);
            e.gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            Point textExt = e.gc.textExtent(label);
            int areaTop    = PAD_Y + iconH + GAP_Y;
            int areaHeight = size.y - areaTop - PAD_Y;
            float cx = size.x / 2f;
            float cy = areaTop + areaHeight / 2f;
            Transform tr = new Transform(getDisplay());
            try {
                tr.translate(cx, cy);
                tr.rotate(-90);
                tr.translate(-textExt.x / 2f, -textExt.y / 2f);
                e.gc.setTransform(tr);
                e.gc.drawText(label, 0, 0, true);
                e.gc.setTransform(null);
            } finally {
                tr.dispose();
            }
        }
    }
}
