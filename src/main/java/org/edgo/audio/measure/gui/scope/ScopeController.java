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

import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.scope.gl.GlScopeSurface;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
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
 * release.  Auto-setup is a control operation and lives here (see
     * {@link #performAutoSetup}); the save / open-signal features stay with their
 * widgets: their engines ({@code ScopeFileSaver}, {@code ScopeOpenSignal},
 * {@code StereoPcmIo}, {@code ScopeMeasurementWorker}) are already
 * separate classes, and their orchestration reads displayed view state.
 */
@Log4j2
public final class ScopeController {

    /** Number of main-view redraws between condensed-view redraws.  The
     *  condensed strip walks ~1 s of audio (lots of samples per pixel); updating
     *  it at ~5 Hz keeps the main trace at full cap/s. */
    private static final int CONDENSED_DECIMATION = 10;

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
     *  drives.  The pane builds the views, so they arrive here rather than via
     *  the constructor. */
    public void attachViews(ScopeView view, ZoomedView condensed) {
        this.view      = view;
        this.condensed = condensed;
    }

    /** Attaches the GPU surface the pane builds when the GPU scope is enabled; the
     *  realtime frame then renders through it instead of the view's GC paint. */
    public void attachGlSurface(GlScopeSurface glSurface) {
        this.glSurface = glSurface;
    }

    /**
     * Re-derives the file/scroll view window — the main view + condensed strip read
     * back-offsets — from {@link #viewCenterFrames}, and repaints both.  The
     * positioning maths live in {@link ScopeNav#fileViewWindow}; this just applies
     * the result to the two views it owns.  The pane syncs its own nav-slider widget
     * (its concern) after calling this.
     */
    public void applyViewState() {
        if (view == null) return;
        SignalBufferReader reader = view.getReader();
        if (reader == null) {
            view.setViewBackOffsetFrames(0);
            if (condensed != null) condensed.setViewBackOffsetFrames(0);
            redrawViews();
            return;
        }
        int displaySamples = ScopeFormat.displaySamplesFor(
                Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
        ScopeNav.ViewWindow vw = view.getNav().fileViewWindow(
                viewCenterFrames, displaySamples, reader.getWritePos(), reader.getCapacity(), reader.getSampleRate());
        view.setViewBackOffsetFrames(vw.mainBackOffset());
        if (condensed != null) condensed.setViewBackOffsetFrames(vw.condensedBackOffset());
        redrawViews();
    }

    private void redrawViews() {
        if (view != null && !view.isDisposed())           view.redraw();
        if (condensed != null && !condensed.isDisposed()) condensed.redraw();
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
        double vpp  = view.getLastVpp();
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
        if (Double.isFinite(vpp) && vpp > 0) {
            double targetVDiv = vpp / (ScopeView.DIVISIONS_Y * 0.75);
            double newVDiv    = ScopeFormat.ceilToStep(targetVDiv, OscParse.voltsPerDivTargets());
            tabControl.setLeftVoltsPerDiv(newVDiv);
            tabControl.setRightVoltsPerDiv(newVDiv);
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

    /** Releases a still-held capture — called by {@code UIEngines} at
     *  application exit. */
    public void shutdown() {
        releaseCapture();
    }
}
