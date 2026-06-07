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

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.ScopePane;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Content of the Multifunctional tab — the original three-pane layout
 * (generator on the left, oscilloscope / FFT split on the right) extracted
 * from {@link MainTab} so MainTab can host multiple tab contents without
 * knowing the details of any one.
 *
 * <p>Owns the {@link SashForm SashForms}, the per-pane collapse helpers,
 * persistence of pane layout state, and the bus subscriptions for pane
 * title-bar clicks.  Lifecycle is shell-bound: subscriptions and resources
 * register their dispose listeners against the parent {@link Shell}.
 */
@Log4j2
public final class MultifunctionalTab {

    /** Approximate pixel size of a pane's title bar — used as the SashForm
     *  weight for the collapsed pane. */
    private static final int COLLAPSED_PANE_SIZE  = 28;

    private final Display display;
    private final Shell   shell;

    private SashForm hSplit;
    private SashForm vSplit;
    @Getter private GeneratorPane          genPane;
    @Getter private FftPane                fftPane;
    private ScopePane                oscPane;

    /** SashForm weights snapshot at collapse time so toggle helpers can
     *  restore them on expand. */
    private int[]   preCollapseVSplitWeights;

    /** Bus subscriptions for pane title-bar clicks.  Each subscriber
     *  receives the NEW collapsed state as the payload.  Stored as
     *  fields so the shell-dispose listener can unsubscribe the SAME
     *  instances (method references compare by identity). */
    private Consumer<Boolean> onGeneratorToggle;
    private Consumer<Boolean> onScopeToggle;
    private Consumer<Boolean> onFftToggle;

    public MultifunctionalTab(Composite parent, Shell shell) {
        this.shell   = shell;
        this.display = shell.getDisplay();
        buildPanes(parent);
    }

    /** Two-phase setup: applied after the host has clamped the shell's
     *  minimum size.  See {@link MainTab#applySavedLayoutState()} for the
     *  rationale around ordering. */
    public void applySavedLayoutState() {
        applySavedSashWeights();
        applySavedCollapseStates();
        registerStatePersistence();
    }

    /** Smallest size that fits the panes' natural layout — used by
     *  {@link MainWindow} to set the shell's minimum size. */
    public Point computeNaturalSize() {
        return hSplit.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    }

    /** Pauses live capture + generator playback for the lifetime of a
     *  modal dialog (e.g. Preferences).  Returns a {@link Runnable} that
     *  resumes both when the dialog closes. */
    public Runnable pauseForDialog() {
        boolean oscWasRunning = oscPane != null && oscPane.isCapturing();
        if (oscWasRunning) oscPane.stopCapture();
        // The FFT pane holds its own ref on SharedCapture when recording.
        // Without releasing it here, refCount stays > 0 across the dialog,
        // SharedCapture.acquire() short-circuits, and any sample-rate /
        // device / bit-depth change the user just made is silently
        // ignored (the existing buffer keeps running at the OLD rate).
        Runnable resumeFft = (fftPane != null) ? fftPane.pauseForDialog() : () -> {};
        Runnable resumeGen = (genPane != null) ? genPane.pauseAroundDialog() : () -> {};
        return () -> {
            if (oscWasRunning) oscPane.startCapture();
            resumeFft.run();
            resumeGen.run();
        };
    }

    /** Stops running playback / capture in preparation for a shell
     *  recreate (language switch, tab orientation change).  Discards any
     *  resume hook — the next instance restores state from
     *  {@link Preferences}. */
    public void stopForRecreate() {
        if (oscPane != null && oscPane.isCapturing()) oscPane.stopCapture();
        if (genPane != null) genPane.pauseAroundDialog();
    }

    // -------------------------------------------------------------------------
    // Pane construction
    // -------------------------------------------------------------------------

    private void buildPanes(Composite parent) {
        // SWT.SMOOTH on a SashForm makes the contained Sash widgets update
        // the content live while the user drags — without it you get the
        // rubber-band ghost outline and the panes only reflow on release.
        hSplit = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
        hSplit.setSashWidth(4);

        genPane = new GeneratorPane(hSplit);

        vSplit = new SashForm(hSplit, SWT.VERTICAL | SWT.SMOOTH);
        vSplit.setSashWidth(4);
        this.oscPane = new ScopePane(vSplit, true);

        fftPane = new FftPane(vSplit, true);

        // Title-bar collapse clicks are broadcast through the MessageBus
        // by each pane.  Store the handlers as fields so the matching
        // unsubscribe in the shell-dispose listener removes the exact
        // same instances (method references compare by identity).
        MessageBus bus = MessageBus.instance();
        onGeneratorToggle = this::toggleGenCollapse;
        onScopeToggle     = this::toggleOscCollapse;
        onFftToggle       = this::toggleFftCollapse;
        bus.subscribe(Events.paneTitleClick(Events.PANE_ID_GENERATOR), onGeneratorToggle);
        bus.subscribe(Events.paneTitleClick(Events.PANE_ID_SCOPE),     onScopeToggle);
        bus.subscribe(Events.paneTitleClick(Events.PANE_ID_FFT),       onFftToggle);
        shell.addDisposeListener(e -> {
            bus.unsubscribe(Events.paneTitleClick(Events.PANE_ID_GENERATOR), onGeneratorToggle);
            bus.unsubscribe(Events.paneTitleClick(Events.PANE_ID_SCOPE),     onScopeToggle);
            bus.unsubscribe(Events.paneTitleClick(Events.PANE_ID_FFT),       onFftToggle);
        });

        vSplit.setWeights(new int[]{1, 1});
        hSplit.setWeights(new int[]{1, 3});

        // Tint just the splitter bars #808080 with a #C8C8C8 hover state.
        // We do NOT call setBackground on the SashForm itself — that would
        // propagate the dark tint to every child Composite that hasn't set
        // its own background, producing dark panes on GTK.
        Color sashColor      = new Color(display, 0x80, 0x80, 0x80);
        Color sashHoverColor = new Color(display, 0xC8, 0xC8, 0xC8);
        display.asyncExec(() -> {
            if (hSplit == null || hSplit.isDisposed()) return;
            for (Control c : hSplit.getChildren()) {
                if (c instanceof Sash s) tintSash(s, sashColor, sashHoverColor);
            }
            if (vSplit == null || vSplit.isDisposed()) return;
            for (Control c : vSplit.getChildren()) {
                if (c instanceof Sash s) tintSash(s, sashColor, sashHoverColor);
            }
        });
        shell.addDisposeListener(e -> {
            sashColor.dispose();
            sashHoverColor.dispose();
        });

        // While either osc or fft is collapsed the SashForm only knows its
        // weights, not its pixel size — so a window resize would scale the
        // collapsed pane proportionally.  Re-pin the weights to
        // {COLLAPSED_PANE_SIZE, remaining-pixels} on every resize so the
        // collapsed pane stays at roughly the title-bar height.
        vSplit.addControlListener(ControlListener.controlResizedAdapter(e -> {
            boolean oscC = oscPane != null && oscPane.isCollapsed();
            boolean fftC = fftPane != null && fftPane.isCollapsed();
            if (oscC || fftC) {
                int total = Math.max(2, vSplit.getSize().y);
                int oscW  = oscC ? COLLAPSED_PANE_SIZE
                                 : Math.max(1, total - COLLAPSED_PANE_SIZE);
                int fftW  = fftC ? COLLAPSED_PANE_SIZE
                                 : Math.max(1, total - oscW);
                if (oscC && !fftC) fftW = Math.max(1, total - oscW);
                if (fftC && !oscC) oscW = Math.max(1, total - fftW);
                vSplit.setWeights(new int[]{ oscW, fftW });
            }
        }));

        // Generator pane = fixed pixel width.  Display.addFilter(SWT.Selection)
        // fires BEFORE the Sash widget's own listener chain — the only spot we
        // can mutate e.x before SashForm's internal handler reads it.
        Listener sashFilter = e -> {
            if (!(e.widget instanceof Sash sash)) return;
            if (sash.getParent() == hSplit) {
                if (genPane != null && genPane.isCollapsed()) { e.doit = false; return; }
                if (e.x < GeneratorPane.MIN_WIDTH_PX) e.x = GeneratorPane.MIN_WIDTH_PX;
                if (genPane != null) genPane.setPaneWidthPx(e.x);
            } else if (sash.getParent() == vSplit) {
                if ((oscPane != null && oscPane.isCollapsed())
                        || (fftPane != null && fftPane.isCollapsed())) {
                    e.doit = false;
                }
            }
        };
        display.addFilter(SWT.Selection, sashFilter);
        shell.addDisposeListener(e -> display.removeFilter(SWT.Selection, sashFilter));

        hSplit.addControlListener(ControlListener.controlResizedAdapter(e -> {
            int total = hSplit.getSize().x;
            if (total <= 0) return;
            int sashW = hSplit.getSashWidth();
            int avail = Math.max(2, total - sashW);
            boolean genC = genPane != null && genPane.isCollapsed();
            if (avail < GeneratorPane.MIN_WIDTH_PX * 2 && !genC) return;

            if (genPane != null && genPane.getPaneWidthPx() < 0) {
                int[] w = hSplit.getWeights();
                long sum = (long) w[0] + (long) w[1];
                int derived = sum > 0
                        ? (int) ((long) avail * w[0] / sum)
                        : GeneratorPane.MIN_WIDTH_PX;
                genPane.setPaneWidthPx(Math.max(GeneratorPane.MIN_WIDTH_PX, derived));
            }

            int gen;
            if (genC) {
                gen = Math.min(COLLAPSED_PANE_SIZE, Math.max(1, avail - 1));
            } else {
                int desired = (genPane != null) ? genPane.getPaneWidthPx() : GeneratorPane.MIN_WIDTH_PX;
                gen = Math.max(GeneratorPane.MIN_WIDTH_PX,
                               Math.min(desired, avail - GeneratorPane.MIN_WIDTH_PX));
            }
            int osc = Math.max(1, avail - gen);
            hSplit.setWeights(new int[]{ gen, osc });
        }));
    }

    private void tintSash(Sash sash, Color rest, Color hover) {
        sash.setBackground(rest);
        boolean[] hovered = { false };
        sash.addListener(SWT.Paint, e -> {
            Point sz = sash.getSize();
            e.gc.setBackground(hovered[0] ? hover : rest);
            e.gc.fillRectangle(0, 0, sz.x, sz.y);
        });
        sash.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (sash.isDisposed()) return;
                hovered[0] = true;
                sash.setBackground(hover);
                sash.redraw();
            }
            @Override
            public void mouseExit(MouseEvent e) {
                if (sash.isDisposed()) return;
                hovered[0] = false;
                sash.setBackground(rest);
                sash.redraw();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void applySavedSashWeights() {
        int[] vw = Preferences.instance().getMultiVSplitWeights();
        if (isValidWeights(vw, 2)) vSplit.setWeights(vw);
    }

    private void applySavedCollapseStates() {
        Preferences prefs = Preferences.instance();
        boolean wantOsc = prefs.isOscPaneCollapsed();
        boolean wantFft = prefs.isFftPaneCollapsed() && !wantOsc;
        if (prefs.isGenPaneCollapsed())    toggleGenCollapse(true);
        if (wantOsc)                       toggleOscCollapse(true);
        else if (wantFft)                  toggleFftCollapse(true);
    }

    private boolean isValidWeights(int[] arr, int expectedLen) {
        if (arr == null || arr.length != expectedLen) return false;
        int sum = 0;
        for (int v : arr) {
            if (v < 0) return false;
            sum += v;
        }
        return sum > 0;
    }

    private void registerStatePersistence() {
        shell.addDisposeListener(e -> {
            Preferences prefs = Preferences.instance();
            if (genPane != null && genPane.getPaneWidthPx() >= GeneratorPane.MIN_WIDTH_PX) {
                prefs.setGenPaneWidth(genPane.getPaneWidthPx());
            }
            boolean oscC = oscPane != null && oscPane.isCollapsed();
            boolean fftC = fftPane != null && fftPane.isCollapsed();
            boolean genC = genPane != null && genPane.isCollapsed();
            if (vSplit != null && !vSplit.isDisposed() && !oscC && !fftC) {
                prefs.setMultiVSplitWeights(vSplit.getWeights());
            }
            prefs.setGenPaneCollapsed(genC);
            prefs.setOscPaneCollapsed(oscC);
            prefs.setFftPaneCollapsed(fftC);
            prefs.save();
        });
    }

    // -------------------------------------------------------------------------
    // Collapse helpers
    // -------------------------------------------------------------------------

    private void toggleOscCollapse(Boolean newCollapsed) {
        if (vSplit == null || vSplit.isDisposed()) return;
        if (oscPane == null) return;
        boolean collapsing = newCollapsed != null && newCollapsed;
        if (collapsing && fftPane != null && fftPane.isCollapsed()) {
            toggleFftCollapse(false);
        }
        if (collapsing) preCollapseVSplitWeights = vSplit.getWeights();
        oscPane.setCollapsed(collapsing);
        if (collapsing) {
            int total = Math.max(2, vSplit.getSize().y);
            int oscW  = Math.max(1, COLLAPSED_PANE_SIZE);
            int fftW  = Math.max(1, total - oscW);
            vSplit.setWeights(new int[]{ oscW, fftW });
        } else {
            int[] restore = preCollapseVSplitWeights;
            if (restore != null && restore.length == 2 && restore[0] > 0 && restore[1] > 0) {
                vSplit.setWeights(restore);
            } else {
                vSplit.setWeights(new int[]{ 1, 1 });
            }
            preCollapseVSplitWeights = null;
        }
        vSplit.layout(true);
    }

    private void toggleFftCollapse(Boolean newCollapsed) {
        if (vSplit == null || vSplit.isDisposed()) return;
        if (fftPane == null) return;
        boolean collapsing = newCollapsed != null && newCollapsed;
        if (collapsing && oscPane != null && oscPane.isCollapsed()) {
            toggleOscCollapse(false);
        }
        fftPane.setCollapsed(collapsing);
        int total = Math.max(2, vSplit.getSize().y);
        if (collapsing) {
            int fftW = Math.max(1, COLLAPSED_PANE_SIZE);
            int oscW = Math.max(1, total - fftW);
            vSplit.setWeights(new int[]{ oscW, fftW });
        } else {
            vSplit.setWeights(new int[]{ 1, 1 });
        }
        vSplit.layout(true);
    }

    private void toggleGenCollapse(Boolean newCollapsed) {
        if (hSplit == null || hSplit.isDisposed()) return;
        if (genPane == null) return;
        boolean collapsing = newCollapsed != null && newCollapsed;
        genPane.setCollapsed(collapsing);
        int total = Math.max(2, hSplit.getSize().x);
        int sashW = hSplit.getSashWidth();
        int avail = Math.max(2, total - sashW);
        int gen;
        if (collapsing) {
            gen = Math.min(COLLAPSED_PANE_SIZE, Math.max(1, avail - 1));
        } else {
            int desired = genPane.getPaneWidthPx();
            gen = Math.max(GeneratorPane.MIN_WIDTH_PX,
                           Math.min(desired, avail - GeneratorPane.MIN_WIDTH_PX));
        }
        int osc = Math.max(1, avail - gen);
        hSplit.setWeights(new int[]{ gen, osc });
        hSplit.layout(true);
    }
}
