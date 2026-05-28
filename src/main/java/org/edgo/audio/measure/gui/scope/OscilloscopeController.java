package org.edgo.audio.measure.gui.scope;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

import lombok.extern.log4j.Log4j2;

/**
 * Owns the oscilloscope's UI-side lifecycle: the {@link #isRunning()}
 * Record-button flag, the periodic view redraws, and the
 * stop-with-frozen-snapshot behaviour.  The audio device itself is
 * owned by {@link SharedCapture} — the controller just acquires /
 * releases a reference for the duration of the scope's Record state.
 *
 * <p>The scope and the FFT pane share the same underlying capture; the
 * {@link SharedCapture} reference count makes the device close only when
 * the last consumer is done with it.
 *
 * <p>All buffer reads happen on the SWT UI thread inside the views'
 * paint handlers; writes happen on the audio capture thread.  The
 * {@link SignalBuffer} synchronises both sides.
 */
@Log4j2
public final class OscilloscopeController {

    /**
     * Period between scope redraw ticks, in milliseconds.  20 ms (≈ 50 Hz)
     * is fast enough to read as smooth motion and leaves plenty of idle
     * time in the OS message queue between paints, so the FFT worker's
     * {@code display.asyncExec} handoff still gets dispatched even
     * while the scope is recording.  The previous 1 ms value flooded
     * the message pump with WM_TIMER + WM_PAINT pairs and starved every
     * other async work item in the app.
     */
    private static final int REDRAW_PERIOD_MS = 20;

    private final OscilloscopeView mainView;
    private final CondensedView    condensedView;
    private final Display          display;

    /** True while the scope's own Record button is on — drives the redraw
     *  timer and the {@link #isRunning()} accessor.  Independent of the
     *  shared-capture lifecycle (the FFT pane can keep the device open
     *  via {@link SharedCapture} without flipping this flag). */
    private volatile boolean scopeLive;
    /** Live capture buffer held while {@link #scopeLive} is true.
     *  Returned by {@link Events#CAPTURE_ACQUIRE} on {@link #start()},
     *  used by {@link #stop()} to snapshot the last frame before
     *  releasing.  {@code null} when the scope isn't recording. */
    private SignalBuffer currentBuffer;

    public OscilloscopeController(OscilloscopeView mainView, CondensedView condensedView) {
        this.mainView      = mainView;
        this.condensedView = condensedView;
        this.display       = mainView.getDisplay();
    }

    /** Returns {@code true} if the scope's own Record button is on.  Does
     *  NOT reflect the shared capture device — the FFT pane can hold the
     *  device open via {@link SharedCapture} while this stays {@code
     *  false}. */
    public boolean isRunning() {
        return scopeLive;
    }

    /**
     * Returns a human-readable description of the last {@link #start()}
     * failure (or {@code null} if the last start succeeded / wasn't
     * attempted).  Forwarded straight from {@link SharedCapture}.
     */
    public String getLastStartError() {
        return SharedCapture.instance().getLastStartError();
    }

    /**
     * Requests the shared capture via the bus, attaches both views to
     * the live buffer, starts the measurement worker and the redraw
     * timer.  Bails out (without flipping {@link #scopeLive}) if the
     * device fails to open — the reason is available via
     * {@link #getLastStartError()}.
     */
    public synchronized void start() {
        if (scopeLive) return;
        SignalBuffer buf = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
        if (buf == null) return;
        scopeLive = true;
        currentBuffer = buf;
        // Attach the views to the live buffer only when the scope's own
        // Record button is on.  If the FFT pane held the device open
        // first, the views stay detached until the user explicitly
        // starts the scope — otherwise paint events would draw what the
        // audio thread writes while scopeLive=false.
        mainView.setBuffer(buf);
        condensedView.setBuffer(buf);
        // Measurement compute runs on its own daemon thread so the SWT
        // paint thread doesn't block on the Goertzel scan at high sample
        // rates.  Started after the buffer is wired so the worker sees it.
        mainView.startMeasurementThread();
        scheduleRedraw();
    }

    /**
     * Stops the capture but keeps a frozen snapshot of the last
     * captured frame attached to both views.  A subsequent
     * {@link #start()} replaces the snapshot with a fresh live buffer.
     */
    public synchronized void stop() {
        if (!scopeLive) return;
        scopeLive = false;
        // Stop the measurement worker first so it doesn't try to read
        // from the buffer while capture is being torn down.
        if (!mainView.isDisposed()) mainView.stopMeasurementThread();
        // Take a snapshot of the live buffer so the scope keeps showing
        // the LAST captured frame after stop.  If we kept the views
        // attached to the shared buffer, any continued writes (because
        // the FFT pane is still recording) would visually resume the
        // trace whenever a paint event fires.
        if (currentBuffer != null) {
            SignalBuffer frozen = snapshotBuffer(currentBuffer);
            if (!mainView.isDisposed())      mainView.setBuffer(frozen);
            if (!condensedView.isDisposed()) condensedView.setBuffer(frozen);
        }
        currentBuffer = null;
        MessageBus.instance().publish(Events.CAPTURE_RELEASE);
        log.info("Oscilloscope stopped.");
    }

    /** Copies the current contents of {@code live} into a new
     *  standalone {@link SignalBuffer} that no audio thread writes
     *  to — used by {@link #stop()} so the scope view freezes on
     *  the last frame instead of continuing to pick up writes the
     *  FFT pane's still-running capture pushes into the shared one. */
    private SignalBuffer snapshotBuffer(SignalBuffer live) {
        int sr  = live.getSampleRate();
        int cap = live.getCapacity();
        SignalBuffer s = new SignalBuffer(sr, (double) cap / sr);
        float[] l = new float[cap];
        float[] r = new float[cap];
        int n = live.readLatest(cap, l, r);
        s.appendBatch(l, r, n);
        return s;
    }

    /**
     * Number of main-view redraws between condensed-view redraws.  The
     * condensed strip walks ~1 s of audio (lots of samples per pixel) and
     * its per-paint work would otherwise halve the main view's cap/s.
     * Updating it at ~5 Hz is plenty for the human eye and keeps the main
     * trace at full speed.
     */
    private static final int CONDENSED_DECIMATION = 10;
    private int redrawCounter = 0;

    /**
     * Schedules the next paint pass via {@link Display#timerExec}.
     *
     * <p>The callback paints the main scope view <em>synchronously</em>
     * ({@code redraw() + update()}) and only then schedules the next
     * timer.  This guarantees the {@value #REDRAW_PERIOD_MS} ms period
     * is measured from <strong>end of paint</strong>, not from when the
     * paint was queued — so the message pump truly idles for that long
     * between scope paints.  During that idle window the FFT view's
     * pending {@code WM_PAINT} (and the FFT worker's
     * {@code display.asyncExec} handoff) get a chance to dispatch.
     * Without the synchronous {@code update()} call, a paint that takes
     * longer than the period leaves the next timer permanently armed
     * and back-to-back scope paints starve every other UI work item.
     */
    private void scheduleRedraw() {
        if (!scopeLive) return;
        display.timerExec(REDRAW_PERIOD_MS, () -> {
            if (!scopeLive) return;
            if (!mainView.isDisposed()) {
                mainView.redraw();
                mainView.update();
            }
            if (redrawCounter++ >= CONDENSED_DECIMATION) {
                redrawCounter = 0;
                if (!condensedView.isDisposed()) condensedView.redraw();
            }
            scheduleRedraw();
        });
    }
}
