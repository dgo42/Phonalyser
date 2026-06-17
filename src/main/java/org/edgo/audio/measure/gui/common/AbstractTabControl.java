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

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;

/**
 * Shared base for a pane's toolbar tab control (FFT / oscilloscope / frequency
 * response).  Each pane builds a {@link TileTabFolder} of settings tabs and
 * assigns it to {@link #toolbarTabs}; this base provides the collapse / paint /
 * automation-registry plumbing every pane delegates to it identically, so each
 * subclass keeps only its own tab content (and its own {@code registerTabs}
 * slug-to-index list, which calls {@link #registerTab}).
 *
 * <p>{@link #toolbarTabs} is assigned by the subclass <em>after</em> it has run
 * its layout, so every method here null-guards it.
 */
public abstract class AbstractTabControl extends Composite {

    /** Pixel height of the camera / crosshair utility-bar icons, shared so
     *  every pane's screenshot / calibrate buttons render at the same size. */
    protected static final int UTILITY_ICON_HEIGHT = 26;

    /** Screenshot ("camera") and calibrate ("crosshair") icons, rendered once
     *  at {@link #UTILITY_ICON_HEIGHT} and shared by each pane's utility
     *  buttons.  Owned by {@link IconUtils}'s cache — NOT disposed here. */
    protected final Image cameraIcon;
    protected final Image crosshairIcon;

    /** The settings tab folder.  Subclass builds it and assigns it; the
     *  delegations below null-guard against the pre-assignment window. */
    protected TileTabFolder toolbarTabs;

    protected AbstractTabControl(Composite parent, int style) {
        super(parent, style);
        IconUtils icons = IconUtils.instance();
        Display d = getDisplay();
        this.cameraIcon    = icons.render(d, SvgPaths.CAMERA,
                (int) Math.round(UTILITY_ICON_HEIGHT * 1.27), UTILITY_ICON_HEIGHT);
        this.crosshairIcon = icons.render(d, SvgPaths.CROSSHAIR,
                UTILITY_ICON_HEIGHT, UTILITY_ICON_HEIGHT);
    }

    /** Runs {@code r} after each tab-body collapse / expand so the host pane
     *  can re-flow its own layout into (or out of) the freed space. */
    public void setCollapseRelayout(Runnable r) {
        if (toolbarTabs != null) toolbarTabs.setCollapseRelayout(r);
    }

    /** True when the tab body is collapsed to just the strip. */
    public boolean isTabsCollapsed() {
        return toolbarTabs != null && toolbarTabs.isCollapsed();
    }

    /** Collapses / expands the tab body to just the strip (used by the
     *  screenshot path so the snapshot shows only the strip).  No-op when
     *  already in the requested state. */
    public void setTabsCollapsed(boolean collapsed) {
        if (toolbarTabs != null && toolbarTabs.isCollapsed() != collapsed) {
            toolbarTabs.setCollapsed(collapsed);
        }
    }

    /** Builds a tab in {@code folder} with the given title and returns its
     *  content {@link Composite} (the {@link CTabItem#setControl} target) for
     *  the caller to lay out and populate.
     *
     *  <p>Creation order is load-bearing: the content composite is created and
     *  assigned to the item via {@code setControl} <em>before</em> any content
     *  goes in.  Creating the {@link CTabItem} first and leaving the body a
     *  stray folder child while the content is built corrupts the folder's
     *  collapsed tab-height computation (the strip then prints clipped at the
     *  bottom).  Every tile-bearing tab must come through here. */
    protected Composite groupCell(CTabFolder folder, String title) {
        Composite c = new Composite(folder, SWT.NONE);
        CTabItem item = new CTabItem(folder, SWT.NONE);
        item.setText(title);
        item.setControl(c);
        return c;
    }

    /** Registers one settings tab in the {@link UiRegistry} under
     *  {@code prefix + "/" + slug} so automation can select it by path —
     *  activation expands the tab body and selects {@code index}.  Tabs not
     *  built (e.g. Save / Load without live capture) are skipped.  Each
     *  subclass's {@code registerTabs(prefix)} supplies its own slug list. */
    protected void registerTab(String prefix, String slug, int index) {
        if (toolbarTabs == null || index >= toolbarTabs.getItemCount()) return;
        UiRegistry.instance().register(prefix + "/" + slug, this)
                .onActivate(() -> {
                    setTabsCollapsed(false);
                    toolbarTabs.setSelection(index);
                });
    }
}
