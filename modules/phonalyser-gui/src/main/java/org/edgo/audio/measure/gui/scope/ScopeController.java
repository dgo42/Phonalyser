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

package org.edgo.audio.measure.gui.scope;

import java.io.File;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.scope.gl.GlScopeSurface;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.widgets.ToolWindow;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Controller of the oscilloscope pane: owns the scope's share of the
 * capture-device lifecycle — the Record state, the live buffer reference
 * and the {@link Events#CAPTURE_ACQUIRE} / {@link Events#CAPTURE_RELEASE}
 * handshake with {@code SharedCapture} (the scope and the FFT pane share
 * the same device via its refcount).
 *
 * <p>The pane orchestrates the VIEW side around these calls — attaching
 * the returned live buffer to its canvases, starting the measurement
 * thread and redraw timer on acquire, re-attaching the frozen snapshot on
 * release.  Auto-setup, the open-signal load lifecycle
 * ({@link #openSignalFile}), view-state recomputes ({@link #applyViewState},
 * which fires {@link #onViewStateChanged} for the pane's nav slider),
 * repaints and persistence wipes are control operations and live here; the
 * save feature stays with its widget ({@code ScopeFileSaver} /
 * {@code StereoPcmIo} are separate engines whose orchestration reads
 * displayed view state).
 *
 * <p>The amplitude-histogram tool window lives here for the same reason: a
 * window's lifecycle — created / disposed off a preference, switching the
 * measurement worker's accumulator on and off with it, owning its reset — is
 * orchestration.  {@link ScopeView} keeps only the header toggle that writes the
 * preference and {@link ScopeView#paintHistogram}, which renders the plot.
 */
@Log4j2
public final class ScopeController {

    /** Number of main-view redraws between condensed-view redraws.  The
     *  condensed strip walks ~1 s of audio (lots of samples per pixel); updating
     *  it at ~5 Hz keeps the main trace at full cap/s. */
    private static final int CONDENSED_DECIMATION = 10;

    /** Horizontal scroll step of one wheel tick, in grid divisions (½ div). */
    private static final double HALF_DIV = 0.5;

    /** Initial content size of the amplitude-histogram window, and where it is
     *  parked.  Bottom-right deliberately: the extracted measurement window owns
     *  the top-right corner and sizes itself from the font, so any offset
     *  measured down from the top would sooner or later land on top of it. */
    private static final int HIST_WIN_W          = 420;
    private static final int HIST_WIN_H          = 320;
    private static final int HIST_WIN_RIGHT_GAP  = 24;
    private static final int HIST_WIN_BOTTOM_GAP = 48;
    /** Registry path the histogram plot is published under, for the screenshot harness. */
    private static final String HISTOGRAM_PATH = "multifunctional/scope/histogram";
    /** True while the scope's own Record state is on.  Does NOT reflect
     *  the shared capture device — the FFT pane can hold it open via
     *  {@code SharedCapture} while this stays {@code false}. */
    @Getter
    private volatile boolean capturing;
    /** Live capture buffer held while {@link #capturing}; snapshotted by
     *  {@link #releaseCapture()} before the release. */
    private SignalBufferReader currentBuffer;
    /** The scope's main view + condensed (zoomed) strip, attached by the pane
     *  after it builds them ({@link #attachViews}); the controller drives their
     *  realtime repaint ({@link #renderRealtimeFrame}). */
    private ScopeView  view;
    private ZoomedView condensed;
    /** Set when the scope renders on the GPU (the {@code phonalyser.scope.gpu}
     *  path): the realtime frame routes through this surface instead of the SWT
     *  view's GC paint.  Null on the normal CPU path. */
    private GlScopeSurface glSurface;
    /** Main-view redraws elapsed since the last condensed-view redraw. */
    private int        redrawCounter;
    /** TEMP: render-tick profiling counter (see {@link #renderRealtimeFrame}). */
    private int        tickProfile;
    /** Absolute (fractional) frame under the canvas centre in file / scrolled-back
     *  navigation; {@code -1} = follow the live tip.  Owned here because deriving
     *  the view window from it coordinates the main view + condensed strip (and the
     *  pane's nav slider reads it) — a multi-entity operation, not pane-local. */
    @Getter @Setter
    private double     viewCenterFrames = -1.0;
    /** Synchronous open-signal file loader, attached with the views; {@code null}
     *  on the screenshot-only pane (no live capture, no file loading). */
    private ScopeOpenSignal loader;
    /** The amplitude-histogram tool window — non-null only while it is open.  Window
     *  LIFECYCLE is orchestration (which window exists, and when the accumulator
     *  runs), so it lives here.  What the window CONTAINS is a {@link HistogramView},
     *  which owns its own palette, buttons, channel pick and reset; the scope view
     *  contributes only the header toggle that writes the preference. */
    private ToolWindow histogramWindow;
    /** Fired after every {@link #applyViewState()} recompute — the pane registers
     *  its nav-scrollbar sync here ({@code viewCenterFrames} is transient controller
     *  state, not a preference, so the widget can't observe it any other way). */
    @Setter
    private Runnable   onViewStateChanged;

    /** Requests the shared capture via the bus and holds the returned live
     *  buffer.  Returns {@code null} when the device fails to open — the
     *  reason is then available via {@link #getLastStartError()}.  Already
     *  capturing: returns the held buffer (idempotent). */
    public synchronized SignalBufferReader acquireCapture() {
        if (capturing) return currentBuffer;
        SignalBufferReader buf = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
        if (buf == null) return null;
        capturing = true;
        currentBuffer = buf;
        return buf;
    }

    /** Drops the scope's capture reference and returns a frozen snapshot
     *  of the last captured frame for the views to keep showing —
     *  {@code null} when not capturing or no buffer was held.  Publishes
     *  {@link Events#CAPTURE_RELEASE} so {@code SharedCapture} can close
     *  the device once every holder is gone. */
    public synchronized SignalBufferReader releaseCapture() {
        if (!capturing) return null;
        capturing = false;
        SignalBufferReader frozen =
                (currentBuffer != null) ? currentBuffer.frozenSnapshot() : null;
        currentBuffer = null;
        MessageBus.instance().publish(Events.CAPTURE_RELEASE);
        log.info("Oscilloscope stopped.");
        return frozen;
    }

    /** The live capture buffer held while {@link #isCapturing()} —
     *  {@code null} otherwise.  Lets a rebuilt pane re-attach its views to
     *  a capture that survived an in-place content rebuild. */
    public synchronized SignalBufferReader liveBuffer() {
        return currentBuffer;
    }

    /** Starts live capture and attaches the views to the live buffer: acquires the
     *  shared device, wires both canvases to the returned buffer, then starts the
     *  measurement worker (after the buffer is wired so it sees it).  No-op if the
     *  device fails to open ({@link #getLastStartError()} carries the reason).  The
     *  pane updates its Record button around this call. */
    public synchronized void startCapture() {
        SignalBufferReader buf = acquireCapture();
        if (buf == null) return;
        if (view != null)      view.setBuffer(buf);
        if (condensed != null) condensed.setBuffer(buf);
        if (view != null)      view.startMeasurementThread();
    }

    /** Stops live capture but keeps a frozen snapshot of the last frame attached to
     *  both views.  Stops the measurement worker first (so it isn't reading a buffer
     *  being torn down), then releases the device and freezes the snapshot — keeping
     *  the last measurements + DC means so a post-stop repaint still shows them. */
    public synchronized void stopCapture() {
        if (!capturing) return;
        if (view != null && !view.isDisposed()) view.stopMeasurementThread();
        SignalBufferReader frozen = releaseCapture();
        if (frozen != null) {
            if (view != null && !view.isDisposed())           view.freezeBuffer(frozen);
            if (condensed != null && !condensed.isDisposed()) condensed.setBuffer(frozen);
        }
    }

    /** Re-attaches the views to a capture that survived an in-place pane rebuild
     *  ({@link #liveBuffer()} still held); returns whether one was re-attached so the
     *  pane can re-light its Record button. */
    public synchronized boolean reattachLiveCapture() {
        SignalBufferReader buf = liveBuffer();
        if (buf == null) return false;
        if (view != null)      view.setBuffer(buf);
        if (condensed != null) condensed.setBuffer(buf);
        if (view != null)      view.startMeasurementThread();
        return true;
    }

    /** Human-readable description of the last {@link #acquireCapture()}
     *  failure (or {@code null} if it succeeded / wasn't attempted).
     *  Forwarded from {@code SharedCapture}. */
    public String getLastStartError() {
        return SharedCapture.instance().getLastStartError();
    }

    /** Attaches (or re-attaches, after a pane rebuild) the views this controller
     *  drives, plus the open-signal loader bound to them ({@code null} on the
     *  screenshot-only pane).  The pane builds all three, so they arrive here
     *  rather than via the constructor. */
    public void attachViews(ScopeView view, ZoomedView condensed, ScopeOpenSignal loader) {
        // An open histogram window is bound to the OUTGOING view's palette and
        // measurement worker, so drop it before re-binding to the new one.
        disposeHistogramWindow();
        this.view      = view;
        this.condensed = condensed;
        this.loader    = loader;
        // Histogram orchestration only for the LIVE pane: the screenshot-only pane
        // (no loader) renders a passive copy of the scope and must never pop a tool
        // window of its own over the real one.
        if (loader != null) {
            Preferences prefs = Preferences.instance();
            // Subscriptions owned by the view's lifetime: a pane rebuild disposes it
            // and takes them with it, then re-registers here against the replacement.
            Bindings.onChange(view, prefs.oscShowHistogramProperty(),       show -> syncHistogramWindow());
            // Re-open a window the preference says should be up — persisted from the
            // last run, or carried across a pane rebuild.  Deferred: at attach time the
            // main shell isn't laid out yet, and the window is parked off its bounds.
            view.getDisplay().asyncExec(() -> {
                if (this.view == view && !view.isDisposed()) syncHistogramWindow();
            });
        }
    }

    /** Brings the histogram window into sync with its preference: creates + opens it
     *  when on, disposes it otherwise.  Accumulation follows the window — the worker
     *  bins only while it is open, and the counts go with it. */
    private void syncHistogramWindow() {
        boolean shouldBeOpen = Preferences.instance().isOscShowHistogram();
        if (shouldBeOpen && histogramWindow == null) {
            createHistogramWindow();
        } else if (!shouldBeOpen) {
            disposeHistogramWindow();
        }
        view.setHistogramEnabled(shouldBeOpen);
    }

    /** Builds the amplitude-histogram window.  Everything INSIDE it — the plot, the
     *  L/R pick, its own reset, its palette — belongs to {@link HistogramView}; what
     *  the controller owns is the window's lifetime and where it sits.  Resizable,
     *  unlike the measurement window: the plot scales into whatever room it is given
     *  instead of laying out fixed-pixel columns. */
    private void createHistogramWindow() {
        ToolWindow w = view.newToolWindow(true, parent -> {
            HistogramView plot = new HistogramView(parent, view.getMeasurementWorker());
            // Make the plot addressable by path.  The screenshot harness has no other
            // way in: a tool window exposes nothing of its insides, and printing a
            // top-level shell comes out blank on Windows.
            UiRegistry.instance().register(HISTOGRAM_PATH, plot);
            return plot;
        });
        w.setTitle(I18n.t("scope.histogram.window.title"));
        w.addCloseListener(e -> Preferences.instance().setOscShowHistogram(false));
        histogramWindow = w;
        w.setSize(HIST_WIN_W, HIST_WIN_H);
        Point ws     = w.getSize();
        Rectangle pb = view.getShell().getBounds();
        w.setLocation(pb.x + pb.width  - ws.x - HIST_WIN_RIGHT_GAP,
                      pb.y + pb.height - ws.y - HIST_WIN_BOTTOM_GAP);
        w.open();
    }

    /** Repaints the histogram window if it is open.  Driven from the view's repaint
     *  paths — both the CPU {@code redraw()} and the GPU overlay phase, which never
     *  calls it — so the plot tracks a running trace instead of looking frozen. */
    void redrawHistogram() {
        if (histogramWindow != null) histogramWindow.redraw();
    }

    private void disposeHistogramWindow() {
        if (histogramWindow == null) return;
        histogramWindow.dispose();
        histogramWindow = null;
    }

    /** Attaches the GPU surface the pane builds when the GPU scope is enabled; the
     *  realtime frame then renders through it instead of the view's GC paint. */
    public void attachGlSurface(GlScopeSurface glSurface) {
        this.glSurface = glSurface;
    }

    /**
     * Re-derives the file/scroll view window — the main view + condensed strip read
     * back-offsets — from {@link #viewCenterFrames}, repaints both, and fires
     * {@link #onViewStateChanged} so the pane can re-sync its nav-scrollbar widget.
     * The ONE view-state recompute in the system; the positioning maths live in
     * {@link ScopeNav#fileViewWindow}.
     */
    public void applyViewState() {
        if (view == null) return;
        SignalBufferReader reader = view.getReader();
        if (reader == null) {
            view.setViewBackOffsetFrames(0);
            if (condensed != null) condensed.setViewBackOffsetFrames(0);
            redrawViews();
            fireViewStateChanged();
            return;
        }
        int displaySamples = ScopeFormat.displaySamplesFor(
                Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
        ScopeNav.ViewWindow vw = view.getNav().fileViewWindow(
                viewCenterFrames, displaySamples, reader.getWritePos(), reader.getCapacity(), reader.getSampleRate());
        view.setViewBackOffsetFrames(vw.mainBackOffset());
        if (condensed != null) condensed.setViewBackOffsetFrames(vw.condensedBackOffset());
        redrawViews();
        fireViewStateChanged();
    }

    private void fireViewStateChanged() {
        Runnable listener = onViewStateChanged;
        if (listener != null) listener.run();
    }

    /** Repaints both scope canvases (main + condensed).  The settings toolbar calls
     *  this after a preference change — required for stopped / file-mode sessions
     *  where the realtime render loop is idle; a cheap no-op while recording. */
    public void redrawViews() {
        if (view != null && !view.isDisposed())           view.redraw();
        if (condensed != null && !condensed.isDisposed()) condensed.redraw();
    }

    /** Wipes the persistence afterglow (GPU phosphor; no-op on the CPU path,
     *  which has no persistence) and repaints so the wipe shows immediately. */
    public void clearPersistence() {
        if (glSurface != null) glSurface.clearPersistence();
        redrawViews();
    }

    /**
     * Loads a signal file into the scope: stops a running live capture first (the
     * loaded file swaps the shared buffer out from under it; the pane pops its
     * Record toggle via {@link Events#SCOPE_RECORDING_STOPPED}), decodes the file
     * through the attached {@link ScopeOpenSignal}, then centres the view on the
     * file's start and recomputes the view state.  Returns whether the load
     * succeeded — on {@code false} the caller surfaces
     * {@link #getLastOpenSignalError()}.
     */
    public boolean openSignalFile(File file) {
        if (loader == null) return false;
        if (isCapturing()) {
            stopCapture();
            MessageBus.instance().publish(Events.SCOPE_RECORDING_STOPPED);
        }
        if (!loader.loadFile(file)) return false;
        SignalBufferReader reader = (view != null) ? view.getReader() : null;
        if (reader != null) {
            // Centre on the start of the loaded signal so the first frames show.
            int displaySamples = ScopeFormat.displaySamplesFor(
                    Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
            viewCenterFrames = displaySamples / 2.0;
        }
        applyViewState();
        return true;
    }

    /** Human-readable description of the last {@link #openSignalFile} failure
     *  ({@code null} when it succeeded or no loader is attached). */
    public String getLastOpenSignalError() {
        return (loader == null) ? null : loader.getLastError();
    }

    /**
     * Moves the FILE / scrolled-back view centre by one wheel tick (½ division),
     * clamped so the window stays inside the buffer.  Owns the {@link #viewCenterFrames}
     * math the pane used to inline; the pane just forwards the wheel direction and then
     * repaints via {@code applyViewState}.  Returns whether the centre actually moved.
     */
    public boolean scrollFileByWheel(int dir) {
        return scrollFileByDivisions(-dir * HALF_DIV);
    }

    /**
     * Moves the FILE / scrolled-back view centre by {@code divisions} grid divisions
     * (signed; negative = toward older samples), clamped inside the buffer — ½ div per
     * wheel tick, ⅕ div per scrollbar arrow, 5 div per scrollbar page click.  The step
     * is computed in EXACT double samples (timePerDiv × sampleRate), never rounded to
     * whole samples or scrollbar units, so repeated fractional steps accumulate without
     * drift.  Returns whether the centre actually moved.
     */
    public boolean scrollFileByDivisions(double divisions) {
        if (view == null) return false;
        SignalBufferReader reader = view.getReader();
        if (reader == null) return false;
        double timePerDiv = Preferences.instance().getOscTimePerDiv();
        int    sr         = reader.getSampleRate();
        int displaySamples = ScopeFormat.displaySamplesFor(timePerDiv, sr);
        long writePos = reader.getWritePos();
        long oldest   = Math.max(0L, writePos - reader.getCapacity());
        double cur = viewCenterFrames;
        if (cur < 0) cur = writePos - displaySamples / 2.0;
        double next = view.getNav().moveFileCentre(cur, divisions, timePerDiv * sr,
                displaySamples, oldest, writePos);
        if (next == viewCenterFrames) return false;
        viewCenterFrames = next;
        return true;
    }

    /**
     * Sets the FILE view centre from the nav-scrollbar thumb fraction {@code frac}
     * (0 = oldest resident sample, 1 = latest), falling back to the buffer midpoint when
     * the window is wider than the resident data.  Owns the {@link #viewCenterFrames}
     * mapping the pane used to inline; the pane just passes its thumb fraction and repaints.
     */
    public void scrollFileToSliderFraction(double frac) {
        if (view == null) return;
        SignalBufferReader reader = view.getReader();
        if (reader == null) return;
        int displaySamples = ScopeFormat.displaySamplesFor(
                Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
        long writePos  = reader.getWritePos();
        long oldest    = Math.max(0L, writePos - reader.getCapacity());
        long minCenter = oldest   + displaySamples / 2;
        long maxCenter = writePos - displaySamples / 2;
        if (maxCenter < minCenter) {
            viewCenterFrames = (writePos + oldest) / 2.0;   // no scroll room
        } else {
            viewCenterFrames = minCenter + frac * (maxCenter - minCenter);
        }
    }

    /**
     * Zooms the FILE / scrolled-back view's t/div around the sample under the mouse
     * ({@code mouseFrac} across the width): the sample under the pointer stays put as the
     * window resizes {@code tDivOld → tDivNew}, then the centre is clamped into the file.
     * Owns the {@link #viewCenterFrames} math the pane used to inline; the pane forwards
     * the gesture from the t/div wheel-zoom and repaints.
     */
    public void zoomFileAroundMouse(double mouseFrac, double tDivOld, double tDivNew) {
        if (view == null) return;
        SignalBufferReader reader = view.getReader();
        if (reader == null) return;
        int  sr       = reader.getSampleRate();
        int  dispOld  = ScopeFormat.displaySamplesFor(tDivOld, sr);
        int  dispNew  = ScopeFormat.displaySamplesFor(tDivNew, sr);
        long writePos = reader.getWritePos();
        long oldest   = Math.max(0L, writePos - reader.getCapacity());
        ScopeNav nav  = view.getNav();
        double cur = viewCenterFrames;
        if (cur < 0) cur = writePos - dispOld / 2.0;
        double next = nav.zoomFileCentre(cur, mouseFrac, dispOld, dispNew);
        viewCenterFrames = nav.clampFileCentre(next, dispNew, oldest, writePos);
    }

    /** Loop-driven realtime repaint of the scope, called once per frame by the
     *  main event loop's render tick (forwarded through the pane from
     *  {@code MultifunctionalTab}).  Repaints while recording OR showing a loaded
     *  signal (file mode) — both are live / interactive; a plain stopped scope
     *  keeps its frozen frame via ordinary paint events.  The condensed strip
     *  repaints decimated.  Returns {@code true} while it should keep the realtime
     *  cadence going. */
    public boolean renderRealtimeFrame() {
        if (view == null || view.isDisposed()
                || (!capturing && !view.isFileMode())) {
            return false;
        }
        if (glSurface != null) {
            Control c = glSurface.control();
            if (c.isDisposed() || !c.isVisible()) return false;
            glSurface.render();                 // GPU: NanoVG render of view.paintCanvas
            if (redrawCounter++ >= CONDENSED_DECIMATION) {
                redrawCounter = 0;
                if (condensed != null && !condensed.isDisposed()) condensed.redraw();
            }
            return true;
        }
        if (!view.isVisible()) return false;
        long _t0 = System.nanoTime();
        view.redraw();
        view.update();
        long _t1 = System.nanoTime();
        if (redrawCounter++ >= CONDENSED_DECIMATION) {
            redrawCounter = 0;
            if (condensed != null && !condensed.isDisposed()) condensed.redraw();
        }
        long _t2 = System.nanoTime();
        // TEMP: view.update() forces the whole SWT main-view paint cycle (paintCanvas
        // + GC setup + double-buffer present); compare it to PAINT-PROFILE's TOTAL to
        // see the SWT present overhead, and condensed.redraw to see the strip's cost.
        if (++tickProfile >= 30 && log.isWarnEnabled()) {
            tickProfile = 0;
            log.warn(String.format("RENDER-TICK ms: view.update=%.1f condensed.redraw=%.1f",
                    (_t1 - _t0) / 1e6, (_t2 - _t1) / 1e6));
        }
        return true;
    }

    /**
     * Auto-setup: picks a t/div that fits ~1.5 periods on screen and a V/div
     * that makes the signal span ~0.75 of the vertical range, centres each
     * channel (a DC-coupled channel on its DC mean so a DC-biased signal lands
     * mid-screen, an AC-coupled one at 0&nbsp;V) and resets the trigger to
     * centre.  The same V/div is applied to both channels (driven by the
     * measurement channel's Vpp).  Wired to the Auto-Setup button in
     * {@link ScopeView}'s header via {@link Events#SCOPE_AUTO_SETUP}.
     *
     * <p>No-op unless the scope is actively recording: without a live capture
     * there is no fresh frequency / Vpp to fit, and reading the shared buffer
     * (which the FFT pane may be driving) would paint a signal the user never
     * asked the scope to capture.  The pane's redraw timer repaints with the
     * new scale on its next tick, so no explicit redraw is issued here.
     *
     * @param view       live scope view — source of the measured freq / Vpp and
     *                   the per-channel DC-centring offset fraction
     * @param tabControl settings control that owns the V/T scale selectors
     */
    public void performAutoSetup(ScopeView view, ScopeTabControl tabControl) {
        // Also runs on a loaded signal (file mode) and on a STOPPED/frozen scope:
        // no live capture, but a valid frame + held measurements to scale from.
        boolean frozen = view != null && !view.isDisposed() && view.isFrozen();
        if (view == null || view.isDisposed() || (!capturing && !view.isFileMode() && !frozen)) return;
        Preferences prefs = Preferences.instance();
        // Horizontal scale + trigger reset only when live / file — a stopped scope
        // keeps the user's current time base and trigger; auto-setup then fixes ONLY
        // the vertical (V/div + offset) off the frozen frame.
        if (!frozen) {
            double freq = view.getLastFrequencyHz();
            // In dual-tone mode the carrier crosses 0 many times per beat envelope
            // cycle.  Pick the LOWER of the carrier and |F1-F2| so the time scale
            // covers at least one full beat envelope — the carrier alone would
            // render a packed wall of cycles with no visible envelope.
            double scaleHz = freq;
            if (prefs.getGenSignalForm().isDualTone()) {
                double beatHz = Math.abs(prefs.getGenDualToneFreq2Hz()
                                       - prefs.getGenDualToneFreq1Hz());
                if (beatHz > 0 && (!Double.isFinite(scaleHz) || beatHz < scaleHz)) {
                    scaleHz = beatHz;
                }
            }
            if (Double.isFinite(scaleHz) && scaleHz > 0) {
                double period = 1.0 / scaleHz;
                double targetTDiv = period * 1.5 / ScopeView.DIVISIONS_X;
                double newTDiv    = ScopeFormat.ceilToStep(targetTDiv, OscParse.timePerDivTargets());
                tabControl.setTimePerDiv(newTDiv);
            }
        }
        // Per-channel V/div: scale each side off its own residual Vpp when its
        // residual is on, else off the captured Vpp.  When both residuals are off
        // autoSetupVpp returns getLastVpp() for both, so both get the SAME V/div —
        // today's behaviour preserved.
        double vppL = view.autoSetupVpp(true);
        double vppR = view.autoSetupVpp(false);
        if (Double.isFinite(vppL) && vppL > 0) {
            double targetVDiv = vppL / (ScopeView.DIVISIONS_Y * 0.75);
            tabControl.setLeftVoltsPerDiv(ScopeFormat.ceilToStep(targetVDiv, OscParse.voltsPerDivTargets()));
        }
        if (Double.isFinite(vppR) && vppR > 0) {
            double targetVDiv = vppR / (ScopeView.DIVISIONS_Y * 0.75);
            tabControl.setRightVoltsPerDiv(ScopeFormat.ceilToStep(targetVDiv, OscParse.voltsPerDivTargets()));
        }
        // Centre each channel: a DC-coupled channel on its DC mean (so a
        // DC-biased signal lands mid-screen instead of clipped off the top/
        // bottom edge); an AC-coupled channel — DC already removed from the
        // trace — at 0 V.  Trigger position + level back to centre.
        prefs.setOscLeftOffsetFrac (view.autoSetupOffsetFrac(true,  prefs.getOscLeftVoltsPerDiv()));
        prefs.setOscRightOffsetFrac(view.autoSetupOffsetFrac(false, prefs.getOscRightVoltsPerDiv()));
        if (!frozen) {
            prefs.setOscTriggerPositionFrac(0.5);
            prefs.setOscTriggerLevelFrac   (0.5);
        } else {
            // Frozen: keep the user's time base + trigger, BUT recover a trigger offset
            // that a zoom carried OFF-screen (virtual) so the signal returns to view —
            // an on-screen offset (in [0,1]) is left exactly as set (don't disturb a good view).
            double pos = prefs.getOscTriggerPositionFrac();
            if (pos < 0.0 || pos > 1.0) prefs.setOscTriggerPositionFrac(0.5);
        }
        prefs.save();
    }

    /** Releases a still-held capture and closes the tool window it owns — called by
     *  {@code UIEngines} at application exit. */
    public void shutdown() {
        releaseCapture();
        disposeHistogramWindow();
    }
}
