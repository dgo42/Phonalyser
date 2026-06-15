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

import java.util.function.IntConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
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
import org.edgo.audio.measure.enums.TabOrientation;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.freqresp.FreqRespPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.scope.ScopePane;
import org.edgo.audio.measure.preferences.Preferences;

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
 * <p>Orientation / icon-size changes apply LIVE: the content composites
 * (and the heavy panes inside them) are created once and re-parented
 * into a freshly built host chrome — no shell rebuild, running
 * capture / playback untouched.  The Preferences dialog just writes the
 * prefs; the rebuild is driven by the property bindings registered in
 * the constructor.
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
    //@SuppressWarnings("unused") // Tab class instances are created for their UI; we don't need to keep a method reference yet.
    private FrequencyResponseTab  frequencyResponse;
    /** Content composites — created ONCE; the host builders re-parent them
     *  into whichever chrome (top TabFolder / left sidebar) is active. */
    private Composite multiContent;
    private Composite frContent;
    /** Root of the current host chrome (TabFolder or sidebar composite) —
     *  disposed and rebuilt on an orientation / icon-size change. */
    private Composite hostChrome;
    /** Live selected-tab index, updated by both hosts' selection events;
     *  restored across chrome rebuilds and persisted once on shell dispose. */
    private int activeTabIndex;
    private boolean chromeRebuildPending;
    /** Selects a top-level tab in whatever chrome is active (top TabFolder or
     *  left sidebar); reassigned on every {@link #buildHost()} so it always
     *  drives the current chrome. */
    private IntConsumer topTabSelector;

    public MainTab(Shell shell, UIEngines engines) {
        this.shell   = shell;
        this.display = shell.getDisplay();
        Preferences prefs = Preferences.instance();
        activeTabIndex = clampSelection(prefs.getActiveTabIndex(), 2);
        // A language / font change builds a fresh MainTab; reset the component
        // registry so the panes below re-register their new controls instead
        // of leaving the disposed ones behind.
        UiRegistry.instance().clear();
        // Content first (parented to the shell), chrome around it after —
        // buildHost() re-parents the content into the active chrome.
        multiContent = new Composite(shell, SWT.NONE);
        multiContent.setLayout(new FillLayout());
        multifunctional = new MultifunctionalTab(multiContent, shell, engines);
        frContent = new Composite(shell, SWT.NONE);
        frequencyResponse = new FrequencyResponseTab(frContent);
        buildHost();
        // Register the Frequency Response top-level tab and its settings tabs
        // so the help-screenshot automation can select + capture them by path
        // (the multifunctional panes register themselves in MultifunctionalTab).
        UiRegistry.instance()
                .register("frequencyResponse", frequencyResponse.getPane().getGroup())
                .onActivate(() -> selectTopTab(1));
        frequencyResponse.getPane().registerTabs("frequencyResponse/tabs");
        // Look & Feel layout prefs apply LIVE: the dialog writes the pref,
        // the chrome rebuilds around the untouched content.  Apply can
        // change both prefs at once — coalesce to a single rebuild.
        Bindings.onChange(shell, prefs.tabOrientationProperty(),      v -> scheduleChromeRebuild());
        Bindings.onChange(shell, prefs.smallIconsInMainTabProperty(), v -> scheduleChromeRebuild());
        // Persist the selected tab once, on shell dispose (per-build
        // listeners would go stale across chrome rebuilds).
        shell.addDisposeListener(e -> {
            prefs.setActiveTabIndex(activeTabIndex);
            prefs.save();
        });
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

    public GeneratorPane getGenPane() {
        return (multifunctional != null) ? multifunctional.getGenPane() : null;
    }

    public FftPane getFftPane() {
        return (multifunctional != null) ? multifunctional.getFftPane() : null;
    }

    public ScopePane getOscPane() {
        return (multifunctional != null) ? multifunctional.getOscPane() : null;
    }

    public FreqRespPane getFreqRespPane() {
        return (frequencyResponse != null) ? frequencyResponse.getPane() : null;
    }

    /** Brings a top-level tab forward regardless of the active chrome (top
     *  tabs or left sidebar) — used by the component registry so automation
     *  can reveal the Frequency Response tab before screenshotting it. */
    private void selectTopTab(int index) {
        if (topTabSelector != null) {
            topTabSelector.accept(index);
            activeTabIndex = index;
        }
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

    /** Coalesces the orientation + icon-size property events (the dialog's
     *  Apply can fire both) into one chrome rebuild on the next UI tick. */
    private void scheduleChromeRebuild() {
        if (chromeRebuildPending) return;
        chromeRebuildPending = true;
        display.asyncExec(() -> {
            chromeRebuildPending = false;
            if (shell.isDisposed()) return;
            // Park the content composites on the shell so disposing the
            // old chrome can't take them (and the panes inside) with it.
            multiContent.setParent(shell);
            frContent.setParent(shell);
            hostChrome.dispose();
            buildHost();
            shell.layout(true, true);
        });
    }

    private void buildTopTabs() {
        Preferences prefs = Preferences.instance();
        int iconPx = prefs.isSmallIconsInMainTab()
                ? TOP_ICON_SMALL_PX
                : TOP_ICON_BIG_PX;
        TabFolder tabFolder = new TabFolder(shell, SWT.NONE);
        hostChrome = tabFolder;

        TabItem multiItem = new TabItem(tabFolder, SWT.NONE);
        multiItem.setText(I18n.t("tab.multifunctional"));
        multiItem.setImage(renderTabIcon(SvgPaths.SWISS_ARMY_KNIFE, iconPx));
        multiContent.setParent(tabFolder);
        multiItem.setControl(multiContent);

        TabItem frItem = new TabItem(tabFolder, SWT.NONE);
        frItem.setText(I18n.t("tab.frequencyResponse"));
        frItem.setImage(renderTabIcon(SvgPaths.RIAA_IEC_CURVE, iconPx));
        frContent.setParent(tabFolder);
        frItem.setControl(frContent);

        tabFolder.setSelection(activeTabIndex);
        tabFolder.addListener(SWT.Selection,
                e -> activeTabIndex = tabFolder.getSelectionIndex());
        topTabSelector = tabFolder::setSelection;
    }

    private void buildLeftSidebar() {
        Preferences prefs = Preferences.instance();
        int iconPx = iconSizePx();
        int barWidth = prefs.isSmallIconsInMainTab()
                ? LEFT_BAR_WIDTH_SMALL_PX
                : LEFT_BAR_WIDTH_BIG_PX;
        Composite root = new Composite(shell, SWT.NONE);
        hostChrome = root;
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

        multiContent.setParent(contentHost);
        frContent.setParent(contentHost);

        // Owned by this chrome generation — disposed with it, not with the
        // shell, so orientation round-trips don't accumulate colours.
        Color hoverBg    = new Color(display, 0xE6, 0xEE, 0xF8);
        Color selectedBg = new Color(display, 0xCD, 0xDD, 0xF5);
        root.addDisposeListener(e -> {
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

        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setSiblings(buttons);
            buttons[i].setStack(stack, contentHost);
            final int idx = i;
            buttons[i].addListener(SWT.MouseDown, e -> activeTabIndex = idx);
        }

        buttons[activeTabIndex].select();
        topTabSelector = i -> buttons[i].select();
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
            Preferences prefs = Preferences.instance();
            prefs.setWindowWidth (lastNormal[0]);
            prefs.setWindowHeight(lastNormal[1]);
            prefs.save();
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

            // Shared, centrally configured font — owned by Fonts, never
            // disposed here.
            this.labelFont = Fonts.instance().normal(parent.getDisplay());

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
