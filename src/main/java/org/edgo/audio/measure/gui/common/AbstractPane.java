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

package org.edgo.audio.measure.gui.common;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.widgets.IconButton;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;

/**
 * Common base for the measurement panes — Generator, Oscilloscope, FFT and
 * Frequency Response.  Owns the structure every pane shares:
 *
 * <ul>
 *   <li>the bordered {@link #group} root the pane is built inside (a
 *       {@code SWT.BORDER} composite replaces the legacy {@code Group}, whose
 *       GtkFrame label consumed title-bar clicks on GTK and broke the
 *       collapse-on-title UX; the border preserves the visual frame);</li>
 *   <li>the {@link #title} bar and the collapse machinery
 *       ({@link #setCollapsed}/{@link #isCollapsed}) — hide every child but
 *       the title, snapshotting each one's {@code visible} / {@code exclude}
 *       so per-child state survives the round-trip;</li>
 *   <li>uniform sizing + creation of the pane's primary action buttons
 *       (Record / Play / wizard) so every toolbar's actions line up.</li>
 * </ul>
 *
 * <p>Subclasses set the {@link #group}'s layout, assign {@link #title}, and
 * populate the pane; pane-specific collapse side effects hook in via
 * {@link #onCollapsing()} / {@link #onExpanding()}.
 */
public abstract class AbstractPane {

    /** Icon / LED pixel height to render an action button's glyph at. */
    protected static final int ACTION_ICON_SIZE = 33;
    /** Square action-button box (px) — Record / Play / wizard all share it. */
    protected static final int ACTION_BOX_SIZE  = 48;
    /** Default screenshot comment-caption top (px) — sits under the pane title. */
    private static final int   DEFAULT_COMMENT_TOP_PX = 40;

    /** The pane's root composite — created here, laid out and populated by
     *  the subclass. */
    @Getter
    protected final Composite group;

    /** The clickable title bar; assigned by the subclass once built.  The
     *  collapse logic keeps it visible while hiding every other child. */
    protected PaneTitle title;

    /** True while the pane is collapsed to its title bar. */
    @Getter
    protected boolean collapsed;
    /** Per-child {@code visible} / {@code GridData.exclude} snapshot taken on
     *  collapse and restored on expand. */
    private boolean[] preCollapseChildVisible;
    private boolean[] preCollapseChildExclude;
    /** Primary action buttons (Record / Play / wizard) created via
     *  {@link #createActionButton} — hidden in the screenshot clone so the
     *  snapshot shows only the measurement, not the interactive controls. */
    private final List<Control> actionButtons = new ArrayList<>();

    protected AbstractPane(Composite parent) {
        this.group = new Composite(parent, SWT.BORDER);
    }

    /**
     * Hides every child except {@link #title} so the pane collapses to its
     * title bar (or restores).  Each child's pre-collapse {@code visible} and
     * {@code GridData.exclude} are snapshotted so mode-specific visibility
     * survives the round-trip.
     */
    public void setCollapsed(boolean wantCollapsed) {
        if (collapsed == wantCollapsed) return;
        if (group == null || group.isDisposed()) return;
        collapsed = wantCollapsed;
        Control[] children = group.getChildren();
        if (collapsed) {
            onCollapsing();
            preCollapseChildVisible = new boolean[children.length];
            preCollapseChildExclude = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                if (children[i] == title) continue;
                preCollapseChildVisible[i] = children[i].getVisible();
                if (children[i].getLayoutData() instanceof GridData gd) {
                    preCollapseChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                children[i].setVisible(false);
            }
            if (title != null) title.setCollapsed(true);
        } else {
            onExpanding();
            if (preCollapseChildVisible != null
                    && preCollapseChildVisible.length == children.length) {
                for (int i = 0; i < children.length; i++) {
                    if (children[i] == title) continue;
                    children[i].setVisible(preCollapseChildVisible[i]);
                    if (children[i].getLayoutData() instanceof GridData gd) {
                        gd.exclude = preCollapseChildExclude[i];
                    }
                }
                preCollapseChildVisible = null;
                preCollapseChildExclude = null;
            }
            if (title != null) title.setCollapsed(false);
        }
        group.layout(true);
    }

    /** Pane-specific side effect at the start of a collapse (e.g. the
     *  generator pane snapshots its pixel width).  Default no-op. */
    protected void onCollapsing() {
        // no-op
    }

    /** Pane-specific side effect at the start of an expand (e.g. the
     *  generator pane restores its pixel width).  Default no-op. */
    protected void onExpanding() {
        // no-op
    }

    /**
     * Creates one of the pane's primary action buttons, sized to the common
     * {@link #ACTION_BOX_SIZE} square and anchored to the top-right of its
     * cell (the standard toolbar action-button slot).  The caller sets the
     * icon (rendered at {@link #ACTION_ICON_SIZE}) and tooltip.
     *
     * @param style {@code SWT.PUSH} for a momentary action, {@code SWT.TOGGLE}
     *              for a latching one (e.g. Record)
     */
    protected Button createActionButton(Composite parent, int style) {
        Button b = new Button(parent, style);
        GridData gd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        gd.widthHint  = ACTION_BOX_SIZE;
        gd.heightHint = ACTION_BOX_SIZE;
        b.setLayoutData(gd);
        actionButtons.add(b);
        return b;
    }

    /**
     * Like {@link #createActionButton} but owner-drawn (an {@link IconButton}
     * Canvas) so macOS can't clip the icon: native macOS buttons cap their
     * content height below our icon size and cut the icon on top, whereas a
     * Canvas has no bezel.  Use this for icon-only buttons (Record / Play /
     * wizard).
     */
    protected IconButton createActionIconButton(Composite parent, int style) {
        IconButton b = new IconButton(parent, style);
        GridData gd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        gd.widthHint  = ACTION_BOX_SIZE;
        gd.heightHint = ACTION_BOX_SIZE;
        b.setLayoutData(gd);
        actionButtons.add(b);
        return b;
    }

    /** Hides every action button (Record / Play / wizard) in the screenshot
     *  clone so the snapshot shows only the measurement, not the interactive
     *  controls.  The button's layout cell is KEPT (not excluded): it pads the
     *  toolbar row to the same height the live pane has, so the collapsed tab
     *  strip gets its full height and isn't clipped.  Called by
     *  {@link #renderOffscreen} on the clone. */
    private void hideActionButtons() {
        for (Control b : actionButtons) {
            if (b != null && !b.isDisposed()) b.setVisible(false);
        }
    }

    // -------------------------------------------------------------------------
    // Offscreen screenshot render (shared by every screenshot-capable pane)
    // -------------------------------------------------------------------------

    /**
     * Renders the whole pane offscreen at the requested size: builds a fresh
     * clone in a hidden {@link Shell} laid out to that size — so SWT lays the
     * chrome out crisply at native pixels instead of bitmap-scaling it — copies
     * this pane's live snapshot into it, drains the event queue so it paints,
     * then prints it into a new {@link Image}.  Subclasses supply the clone via
     * {@link #createSnapshotClone}.  {@code Control.print} captures the tab
     * strip's tile paint listener too, so no manual overlay is needed.  UI
     * thread only.
     */
    public final Image renderOffscreen(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        // Hidden Shell sized to the target.  setSize BEFORE setLocation — on
        // GTK, locating before the shell has a real size lets some WMs ignore
        // the negative offset and place it at (0,0).
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        AbstractPane clone = createSnapshotClone(offscreen);
        clone.hideActionButtons();   // screenshots omit the Record / Play / wizard controls
        Image output = new Image(d, targetW, targetH);
        try {
            offscreen.setSize(targetW, targetH);
            offscreen.setLocation(-10000, -10000);
            offscreen.open();
            // Lay the clone out EXPANDED first so the tab folder settles its
            // real strip height — only THEN collapse it.  A folder collapsed
            // before its first expanded layout reports a stale, too-short strip
            // height (its bottom tiles / tab outlines then print clipped); the
            // live pane never hits this because it is always laid out expanded
            // before the user collapses it.
            while (d.readAndDispatch()) { /* drain the expanded layout */ }
            clone.setTabsCollapsed(true);
            clone.group.layout(true, true);
            while (d.readAndDispatch()) { /* drain the collapse */ }
            clone.group.redraw();
            while (d.readAndDispatch()) { /* drain the redraw */ }
            clone.group.update();

            GC outGc = new GC(output);
            try {
                clone.group.print(outGc);
            } finally {
                outGc.dispose();
            }
            offscreen.setVisible(false);
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    /**
     * Builds a fresh clone of this pane inside {@code parent}, sized by the
     * caller, with this pane's live snapshot copied in.  Screenshot-capable
     * panes override this; the default refuses (a pane with no screenshot
     * support).  The clone is built expanded — {@link #renderOffscreen}
     * collapses the strip after the first layout.
     */
    protected AbstractPane createSnapshotClone(Composite parent) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " has no screenshot renderer");
    }

    /** The pane's settings tab strip, or {@code null} for panes without one.
     *  Exposed so the base can own the collapse delegation instead of each
     *  pane repeating it; subclasses with a strip override it. */
    protected AbstractTabControl tabStrip() {
        return null;
    }

    /** Re-flows this pane's own layout after its settings tab strip collapses or
     *  expands, reclaiming (or yielding) the freed vertical space.  The strip
     *  calls this through {@link AbstractTabControl#onTabCollapsed()} — no
     *  callback indirection.  A pane without a strip implements it empty. */
    protected abstract void onTabCollapse();

    /** Collapses / expands the settings tab strip (the screenshot path shows
     *  only the headers).  {@link #renderOffscreen} collapses the clone only
     *  AFTER an expanded layout: the folder's collapsed {@code computeSize} is
     *  unreliable until it has been laid out once expanded (the live pane is
     *  always laid out expanded first, which is why it never clips). */
    public void setTabsCollapsed(boolean collapsed) {
        AbstractTabControl t = tabStrip();
        if (t != null && !t.isDisposed()) t.setTabsCollapsed(collapsed);
    }

    /**
     * Opens the shared {@link ScreenshotDialog} for this pane: seeds it with the
     * persisted size / folder / comment-font, and wires a renderer that produces
     * the offscreen image via {@link #renderOffscreen} and stamps the brand
     * watermark + comment via {@link ScreenshotOverlay} at this pane's
     * {@link #screenshotCommentTopPx() header offset}.  Subclasses override the
     * small hooks below where they diverge (comment offset, which prefs hold the
     * size / folder).
     */
    public final void openScreenshotDialog() {
        if (group == null || group.isDisposed()) return;
        Rectangle b = group.getBounds();
        int commentTop = screenshotCommentTopPx();
        new ScreenshotDialog(
                group.getShell(),
                screenshotInitialWidth(), screenshotInitialHeight(),
                b.width, b.height,
                screenshotFolder(),
                Preferences.instance().getScreenshotCommentFont(),
                (d, w, h, comment, font) -> {
                    Image img = renderOffscreen(d, w, h);
                    new ScreenshotOverlay(d).stamp(img, commentTop, comment, font);
                    return img;
                },
                screenshotSizeCommit(),
                screenshotFolderCommit(),
                fontData -> {
                    Preferences p = Preferences.instance();
                    p.setScreenshotCommentFont(fontData);
                    p.save();
                }).open();
    }

    /** Top y (px) of the screenshot comment caption — under this pane's header.
     *  Default sits under the title bar; panes with extra top chrome (the FFT
     *  averages/unit overlay) override it. */
    protected int screenshotCommentTopPx() {
        return DEFAULT_COMMENT_TOP_PX;
    }

    /** Initial screenshot width seeded into the dialog (0 ⇒ use the pane's
     *  native size).  Default = the shared screenshot-size preference. */
    protected int screenshotInitialWidth() {
        return Preferences.instance().getScreenshotWidth();
    }

    /** Initial screenshot height; see {@link #screenshotInitialWidth()}. */
    protected int screenshotInitialHeight() {
        return Preferences.instance().getScreenshotHeight();
    }

    /** Folder seeded into the Save-as dialog.  Default = the shared screenshot folder. */
    protected String screenshotFolder() {
        return Preferences.instance().getScreenshotFolder();
    }

    /** Persists the chosen pixel size; {@code null} = don't persist (panes
     *  with no size preference). */
    protected ScreenshotDialog.SizeCommit screenshotSizeCommit() {
        return (w, h) -> {
            Preferences p = Preferences.instance();
            p.setScreenshotWidth(w);
            p.setScreenshotHeight(h);
            p.save();
        };
    }

    /** Persists the chosen save folder. */
    protected ScreenshotDialog.FolderCommit screenshotFolderCommit() {
        return folder -> {
            Preferences p = Preferences.instance();
            p.setScreenshotFolder(folder);
            p.save();
        };
    }
}
