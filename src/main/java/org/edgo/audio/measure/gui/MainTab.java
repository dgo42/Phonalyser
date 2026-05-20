package org.edgo.audio.measure.gui;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.OscilloscopeController;
import org.edgo.audio.measure.gui.scope.OscilloscopePane;
import org.edgo.audio.measure.gui.sound.SharedCapture;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Tab-folder content for the main window — owns the three measurement
 * panes ({@link GeneratorPane}, {@link OscilloscopePane}, {@link FftPane})
 * inside two nested {@link SashForm SashForms}, the per-pane collapse
 * helpers, persistence of pane layout state, and the pane-level callbacks
 * (screenshot dialogs, ADC-calibration dialog, shared-capture
 * acquire / release for the FFT pane).
 *
 * <p>Lives under the application's main {@link Shell}; uses the shell only
 * for the few dialogs that need a parent.  Everything else is layout +
 * widget wiring inside its own TabFolder.
 */
@Log4j2
public final class MainTab {

    /** Approximate pixel size of a pane's title bar — used as the SashForm
     *  weight for the collapsed pane. */
    private static final int COLLAPSED_PANE_SIZE  = 28;

    private final Display display;
    private final Shell   shell;

    private SashForm hSplit;     // generator | (osc / fft)
    private SashForm vSplit;     // osc / fft
    @Getter private OscilloscopeController oscController;
    @Getter private GeneratorPane          genPane;
    @Getter private FftPane                fftPane;
    private OscilloscopePane                oscPane;

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

    public MainTab(Shell shell) {
        this.shell   = shell;
        this.display = shell.getDisplay();

        buildPanes();
    }

    /** Two-phase setup: {@link MainWindow} calls this after it has read
     *  the natural shell size (and clamped the shell's minimum to it).
     *  Collapsing a pane changes the SashForm weights to {@code {weight,
     *  28}}, which makes {@link Shell#computeSize} balloon the natural
     *  size to whatever total honours that ratio — so the saved-collapse
     *  restore MUST happen after the minimum-size pass, otherwise a
     *  shutdown with a collapsed pane reopens the window many thousands
     *  of pixels tall / wide. */
    public void applySavedLayoutState() {
        applySavedSashWeights();
        applySavedCollapseStates();
        registerStatePersistence();
    }

    /** Smallest shell size that fits the panes' natural layout — used by
     *  {@link MainWindow} to set the shell's minimum size. */
    public Point computeNaturalShellSize() {
        return shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    }

    /** Pauses live capture + generator playback for the lifetime of a
     *  modal dialog (e.g. Preferences).  Returns a {@link Runnable} that
     *  resumes both when the dialog closes. */
    public Runnable pauseForDialog() {
        boolean oscWasRunning = oscController != null && oscController.isRunning();
        if (oscWasRunning) oscController.stop();
        Runnable resumeGen = (genPane != null) ? genPane.pauseAroundDialog() : () -> {};
        return () -> {
            if (oscWasRunning) oscController.start();
            resumeGen.run();
        };
    }

    /** Stops running playback / capture in preparation for a shell
     *  recreate (language switch).  Discards any resume hook — the next
     *  instance restores state from {@link Preferences}. */
    public void stopForRecreate() {
        if (oscController != null && oscController.isRunning()) oscController.stop();
        if (genPane != null) genPane.pauseAroundDialog();
    }

    // -------------------------------------------------------------------------
    // Pane construction
    // -------------------------------------------------------------------------

    private void buildPanes() {
        // Eager init of the SharedCapture singleton so its MessageBus
        // responder is registered BEFORE the first pane fires a
        // CAPTURE_ACQUIRE request (otherwise the request returns null
        // and the user thinks the device failed to open).
        SharedCapture.instance();

        // Top-level TabFolder hosts the panes — currently a single
        // "Multifunctional" tab carrying the original three-pane layout
        // (generator on the left, oscilloscope / FFT split on the right).
        // Wrapping everything in a tab keeps room for dedicated single-
        // function tabs (FRF, distortion, etc.) without rewiring the
        // SashForm geometry.
        TabFolder tabFolder = new TabFolder(shell, SWT.NONE);
        TabItem multiTab = new TabItem(tabFolder, SWT.NONE);
        multiTab.setText(I18n.t("tab.multifunctional"));
        Composite multiContent = new Composite(tabFolder, SWT.NONE);
        multiContent.setLayout(new FillLayout());
        multiTab.setControl(multiContent);

        // SWT.SMOOTH on a SashForm makes the contained Sash widgets update
        // the content live while the user drags — without it you get the
        // rubber-band ghost outline and the panes only reflow on release.
        hSplit = new SashForm(multiContent, SWT.HORIZONTAL | SWT.SMOOTH);
        hSplit.setSashWidth(4);

        genPane = new GeneratorPane(hSplit);

        vSplit = new SashForm(hSplit, SWT.VERTICAL | SWT.SMOOTH);
        vSplit.setSashWidth(4);
        this.oscPane = new OscilloscopePane(vSplit, true);
        this.oscController = oscPane.getController();

        fftPane = new FftPane(vSplit, true);

        // Title-bar collapse clicks are now broadcast through the
        // MessageBus by each pane.  Store the handlers as fields so the
        // matching unsubscribe in the shell-dispose listener (below)
        // removes the exact same instances (method references compare
        // by identity, not value).
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
        // We do NOT call setBackground on the SashForm itself — that
        // would propagate the dark tint to every child Composite that
        // hasn't set its own background, producing dark panes on GTK.
        //
        // The wiring is deferred to the first event-loop tick because
        // SashForm creates its Sash children lazily during the first
        // layout pass (which happens after shell.open()).
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
        // fires BEFORE the Sash widget's own listener chain — the only spot
        // we can mutate e.x before SashForm's internal handler reads it.
        // hSplit.controlResized then recomputes weights to preserve the
        // tracked pixel width on window resize.
        Listener sashFilter = e -> {
            if (!(e.widget instanceof Sash sash)) return;
            if (sash.getParent() == hSplit) {
                // Collapsed generator: disallow drag so the user can't
                // accidentally grow it via the splitter — they must click
                // the title Label to expand.
                if (genPane != null && genPane.isCollapsed()) { e.doit = false; return; }
                if (e.x < GeneratorPane.MIN_WIDTH_PX) e.x = GeneratorPane.MIN_WIDTH_PX;
                if (genPane != null) genPane.setPaneWidthPx(e.x);
            } else if (sash.getParent() == vSplit) {
                // Collapsed osc or fft locks the divider until the user
                // expands the pane again.
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

            // First real resize after construction with no persisted width:
            // derive an initial pixel width from the SashForm's current
            // weights so the proportion isn't lost on the first paint.
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

    /** Paints a SashForm splitter bar with the resting tint and swaps to
     *  the hover tint while the mouse is over it. */
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

    /** Applies the Multifunctional tab's vertical split weights from prefs. */
    private void applySavedSashWeights() {
        int[] vw = Preferences.instance().getMultiVSplitWeights();
        if (isValidWeights(vw, 2)) vSplit.setWeights(vw);
    }

    /** Restores the persisted collapse state of each pane.  Called after
     *  panes are built and saved sash weights are applied so the toggle
     *  helpers see a clean baseline. */
    private void applySavedCollapseStates() {
        Preferences prefs = Preferences.instance();
        // Defensive: both osc and fft collapsed isn't a valid state — prefer osc.
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

    /** Writes the final pane geometry (window size, sash weights, collapse
     *  flags, generator pane pixel width) to {@link Preferences} when the
     *  shell is disposed.  Window size is tracked on every resize so we
     *  always know the latest non-maximised dimensions; sash weights are
     *  read straight from the SashForms at dispose time. */
    private void registerStatePersistence() {
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
            if (genPane != null && genPane.getPaneWidthPx() >= GeneratorPane.MIN_WIDTH_PX) {
                prefs.setGenPaneWidth(genPane.getPaneWidthPx());
            }
            boolean oscC = oscPane != null && oscPane.isCollapsed();
            boolean fftC = fftPane != null && fftPane.isCollapsed();
            boolean genC = genPane != null && genPane.isCollapsed();
            if (vSplit != null && !vSplit.isDisposed() && !oscC && !fftC) {
                // Only save the vertical split weights when neither pane is
                // collapsed — saving the collapsed weights ({28, N-28}) would
                // overwrite the user's actual desired split.
                prefs.setMultiVSplitWeights(vSplit.getWeights());
            }
            prefs.setGenPaneCollapsed(genC);
            prefs.setOscPaneCollapsed(oscC);
            prefs.setFftPaneCollapsed(fftC);
            prefs.save();
        });
    }

    // -------------------------------------------------------------------------
    // Collapse helpers — own only the SashForm-level work (vSplit / hSplit
    // weights, mutual exclusion).  Each pane owns its own child-hide /
    // restore + title-text swap via setCollapsed(boolean).
    // -------------------------------------------------------------------------

    /** Applies the requested collapsed state to the Oscilloscope pane
     *  and rebalances the vertical {@link SashForm}.  Saves the current
     *  {@code vSplit} weights on the way down so the user's pre-
     *  collapse proportion can be restored on expand.  Called from the
     *  bus subscription (click) and from
     *  {@link #applySavedCollapseStates()}. */
    private void toggleOscCollapse(Boolean newCollapsed) {
        if (vSplit == null || vSplit.isDisposed()) return;
        if (oscPane == null) return;
        boolean collapsing = newCollapsed != null && newCollapsed;
        // Only one of osc / fft can be collapsed at a time.
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

    /** Applies the requested collapsed state to the FFT pane and
     *  rebalances the vertical {@link SashForm}. */
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

    /** Applies the requested collapsed state to the Generator pane and
     *  rebalances the horizontal {@link SashForm}.  Shrinks the pane's
     *  WIDTH (not height) since it lives in the horizontal split.  The
     *  pane itself owns its pre-collapse / restored width — this method
     *  only reads the resulting {@link GeneratorPane#getPaneWidthPx()}
     *  to apply the matching SashForm weights. */
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

    // -------------------------------------------------------------------------
    // Capture sharing — FFT pane consumes from the scope's SignalBuffer
    // -------------------------------------------------------------------------


}
