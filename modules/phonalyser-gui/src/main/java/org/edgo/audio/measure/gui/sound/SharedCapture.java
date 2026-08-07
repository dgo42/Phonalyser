/*
 * Phonalyser - precision audio measurement workbench.
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

package org.edgo.audio.measure.gui.sound;

import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.MarkedCapture;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Single owner of the application's input-capture device and the
 * {@link SignalBuffer} fed by it.  Both the oscilloscope and the FFT
 * pane consume from the same underlying stream; reference-counted
 * {@link #acquire()} / {@link #release()} keep the device open as long
 * as either pane is recording and close it when the last reference
 * goes away.
 *
 * <p>Previously these responsibilities lived inside
 * {@code OscilloscopeController} - a layering inversion, since the FFT
 * pane had to reach across through the oscilloscope to share the
 * device.  Lifted here so the controller is purely a scope concern and
 * any consumer can drive the shared capture directly.
 *
 * <h2>Threading</h2>
 * <p>{@link #acquire()} and {@link #release()} are synchronised on the
 * instance; the audio-callback thread writes into the shared
 * {@link SignalBuffer} directly (the buffer synchronises read / write
 * itself).
 */
@Log4j2
public final class SharedCapture {

    /** Ring-buffer length, in seconds.  Sized for at least two display
     *  windows at the slowest time/div (1 s/div × 10 = 10 s) plus margin
     *  so the trigger-position slider can sweep across the full window
     *  without the display falling off the buffer. */
    private static final double BUFFER_SECONDS = 22.0;

    private static volatile SharedCapture instance;

    public static SharedCapture instance() {
        SharedCapture local = instance;
        if (local != null) return local;
        synchronized (SharedCapture.class) {
            if (instance == null) instance = new SharedCapture();
            return instance;
        }
    }

    private AudioCapture capture;
    /** The currently-open shared buffer - owned by the active capture
     *  session.  Both the scope view and the FFT pane read from this
     *  same instance so they see consistent data.
     *
     *  <p>Volatile for the same reason as {@link #refCount}: it now carries the
     *  answer to "is this stream still live", which the display thread asks to
     *  paint a Record button and must not queue behind a remote device open. */
    private volatile SignalBuffer sharedBuffer;
    /** Reference count for the open device.  Incremented by every
     *  {@link #acquire()}, decremented by every {@link #release()};
     *  the device is open while this is &gt; 0.
     *
     *  <p>WRITTEN only under this instance's monitor - that is the invariant,
     *  and it has not moved.  Volatile so it can be READ without taking it: a
     *  remote {@code capture.open} runs inside {@link #acquire()} for as long as
     *  the bench takes to answer, and everything that merely asks "is it
     *  recording" - the scope's Record button on the display thread, the
     *  frequency-response worker's poll loop - used to queue behind that answer.
     *  A stale-by-microseconds count is what those callers actually want; a
     *  twenty-second wait to paint a button is not. */
    private volatile int refCount;
    /** Human-readable message from the last failed {@link #acquire()},
     *  or {@code null} on success / clean state.  Cleared on the next
     *  successful acquire.  Volatile for the same reason as {@link #refCount}:
     *  the pane reads it to explain a failure, and reading an explanation must
     *  not wait on the next device open. */
    @Getter
    private volatile String lastStartError;

    /** Consumers call {@link #acquire()} / {@link #release()} DIRECTLY - worker
     *  threads talk straight to the audio side, and the {@link MessageBus} is
     *  UI-only, so the capture must never be driven through it. */
    private SharedCapture() {
    }

    /** Returns {@code true} if the shared audio device is currently open
     *  (at least one consumer holds a reference AND its stream is still live).
     *  Deliberately NOT synchronised - see {@link #refCount}: this is asked from
     *  the display thread to paint a button and from a worker's poll loop, and
     *  neither may wait out a remote device open that is holding the monitor.
     *
     *  <p>A reference held over a FINISHED stream is not a capture.  Between a
     *  device dying and the last pane running its stop there is a window where the
     *  count is still above zero and nothing is arriving; answering "recording"
     *  there is how a Record button stays lit over hardware that is gone. */
    public boolean isCapturing() {
        SignalBuffer live = sharedBuffer;
        return refCount > 0 && live != null && !live.isFinished();
    }

    /**
     * Opens the input device if no reference is held yet, otherwise
     * increments the reference count.  Returns a fresh
     * {@link SignalBufferReader} cursor over the live shared buffer the
     * device is writing into (each consumer gets its own read position
     * over the one shared stream), or {@code null} on failure (with a
     * human-readable reason stored in {@link #getLastStartError()}).
     */
    public synchronized SignalBufferReader acquire() {
        SignalBuffer live = sharedBuffer;
        if (refCount > 0 && live != null && !live.isFinished()) {
            refCount++;
            return new SignalBufferReader(live);
        }
        // A lane that has ENDED is not a lane to join.  The count can still be
        // above zero here - a holder whose stop has not run yet - and taking the
        // fast path on it would hand out a cursor over a ring nothing writes to
        // any more: a flat trace and a spectrum of silence, presented as a
        // measurement.  Whether the stream ended is the BUFFER's answer, because
        // the side that writes into it is the side holding the card; nothing here
        // keeps a second copy of it.
        discardFinishedLane();
        lastStartError = null;

        // Resolution and the stream format live with the backend
        // (AudioBackend.getActiveInputDevice / openCapture(device)); what stays
        // here is only the user-facing wording of the two failure modes, because
        // I18n does not exist below the GUI.
        AudioBackend audio = AudioBackend.instance();
        DeviceRef device = audio.getActiveInputDevice();
        if (device == null) {
            String deviceName = Preferences.instance().current().getInputDeviceName();
            lastStartError = (deviceName == null)
                    ? I18n.t("capture.error.noInputDevice")
                    : I18n.t("capture.error.inputDeviceGone", deviceName);
            log.warn("Capture: {}", lastStartError);
            return null;
        }
        // The caller's step after resolution: the per-card ADC full scale lands
        // on the runtime scalars via the UI thread (prefs bindings are plain
        // UI-only listeners).
        GuiUtil.marshal(() -> Preferences.instance().applyDeviceProfile(device, true));
        BackendPrefs bp = Preferences.instance().current();
        final int sampleRate = bp.getInputSampleRate();
        final int bitDepth   = bp.getInputBitDepth();

        // Hoisted out of the try so the catch can ask the SAME backend what its
        // own failure meant - the reading of a native error belongs to the
        // module that produced it, exactly as on the playback side.
        AudioDeviceManager input = audio.manager(device.carrier());
        try {
            AudioCapture cap = audio.openCapture(device);
            cap.open();

            // The ring carries the rate the stream REALLY runs at - a backend
            // (a remote bench above all) may grant another rate than the
            // preferences asked, and a buffer labelled with the asked one
            // would mislabel every sample for every consumer.  Consumers that
            // need a specific rate (the sweep engines) guard on the reader.
            final int grantedRate = (int) Math.round(cap.getFormat().getSampleRate());
            if (grantedRate != sampleRate && log.isWarnEnabled()) {
                log.warn("Capture granted {} Hz where the preferences ask {} Hz - "
                        + "the ring runs at the granted rate", grantedRate, sampleRate);
            }
            SignalBuffer buf = new SignalBuffer(grantedRate, BUFFER_SECONDS);

            final int sampleBytes = bitDepth / 8;
            final int frameSize   = sampleBytes * 2;   // stereo
            // Per sample we call cap.readSample (offset-binary unsigned),
            // then go through the (uL − midpoint) / midpoint conversion
            // in double precision and KEEP it double end-to-end - the whole
            // time-domain path (this staging, the ring buffer, the FFT read
            // buffers) is double, so no single-precision narrowing happens
            // between the ADC word and the transform.  Reusable per-chunk
            // staging buffers - fed by the capture thread, then pushed in one
            // synchronised appendBatch() call so the UI thread isn't
            // fighting the per-sample monitor entries.
            final long unsignedMask = (bitDepth >= 32) ? 0xFFFFFFFFL : ((1L << bitDepth) - 1);
            final double midpoint   = 1L << (bitDepth - 1);
            final double[][] convBuf = { new double[1], new double[1] };
            // Per SESSION, not per instance: the flag belongs to the lane this
            // acquire opened, so a device that failed cannot silence the report
            // for the next one the operator opens.  The BUFFER handed to
            // laneFailed is this lane's for the same reason - a callback that
            // outlives its lane must end its own stream, never the current one.
            final AtomicBoolean laneReported = new AtomicBoolean();
            cap.setPcmBatchListener(new AudioCapture.PcmBatchListener() {
                @Override
                public void accept(byte[] pcm, int validBytes) {
                    try {
                        int frames = validBytes / frameSize;
                        if (convBuf[0].length < frames) {
                            convBuf[0] = new double[frames];
                            convBuf[1] = new double[frames];
                        }
                        double[] l = convBuf[0];
                        double[] r = convBuf[1];
                        for (int f = 0, o = 0; f < frames; f++, o += frameSize) {
                            long uL = ((long) cap.readSample(pcm, o)) & unsignedMask;
                            long uR = ((long) cap.readSample(pcm, o + sampleBytes)) & unsignedMask;
                            l[f] = (uL - midpoint) / midpoint;
                            r[f] = (uR - midpoint) / midpoint;
                        }
                        buf.appendBatch(l, r, frames);
                    } catch (Throwable ex) {
                        // The audio callback thread's boundary.  THROWABLE because
                        // the native backends come through JNA, whose "Invalid
                        // memory access" is an Error: it used to leave this thread
                        // dead with the scope and the FFT still drawing whatever
                        // was last in the ring buffer - a measurement of nothing,
                        // presented as a measurement.
                        laneFailed(CaptureEndReason.DEVICE_LOST, ex, laneReported, buf);
                    }
                }

                @Override
                public void captureEnded(CaptureEndReason reason) {
                    // The stream's end arrives on the SAME seam the data did -
                    // the backend's common parent says it once, and from here it
                    // climbs the layers as state, not as an event: the buffer
                    // finishes with the ENUM, its readers answer terminally,
                    // the workers stop, and the pane localizes.
                    laneFailed(reason, null, laneReported, buf);
                }
            });
            // A stream that carries in-band marks (a bench rendering the sweep
            // itself): translate the sweep-start mark into a position ON THE
            // RING.  The mark is dispatched between two batches on the same
            // thread that appends them, so the buffer's write position at this
            // callback IS the mark's frame position - the remote sweep consumer
            // seeks its cursor there (kind 1 = generator sweep sample 0).
            if (cap instanceof MarkedCapture marked) {
                marked.setMarkerListener((kind, pcmBytes) -> {
                    if (kind == 1) buf.markSweepStart();
                });
            }

            this.capture      = cap;
            this.sharedBuffer = buf;
            cap.startRecording();
            // ++ and not = 1: the count is a running balance of acquires against
            // releases, and a lane discarded above may still owe releases from
            // holders that have not stopped yet.  Each of those decrements the
            // reference it really took, so nobody is stranded and it cannot go
            // negative - only the lane the count refers to has changed.
            refCount++;
            log.info("Audio capture started: device={}, sampleRate={} Hz, bitDepth={} bits",
                    device.displayName(), sampleRate, bitDepth);
            return new SignalBufferReader(buf);
        } catch (Throwable ex) {
            // THROWABLE: this is the boundary between the caller's thread and the
            // native driver, and a JNA Error escaping it used to travel out
            // through the CAPTURE_ACQUIRE responder into the SWT event loop.  A
            // failed open is the domain failure the caller already handles - a
            // null reader plus the reason - whatever kind of throwable caused it.
            DeviceFailureReason reason = input.classifyFailure(ex);
            lastStartError = openFailureText(reason, device, sampleRate, bitDepth);
            log.error("Capture: failed to start ({}) - {}", reason, ex.getMessage(), ex);
            cleanupAfterFailure();
            return null;
        }
    }

    /**
     * A live capture lane died on its own.  The STREAM is ended - and that is
     * the whole report: every consumer (the scope's measurement worker, the
     * FFT worker) consults its reader's terminal state, stops itself, and the
     * first to claim the reason shows it to the operator.  No event.
     *
     * <p>Once per lane ({@code reported}): a callback that has started failing
     * usually fails on every batch, and the death must be logged once, not a
     * hundred times.  The device itself is closed by the panes' own stop,
     * which releases the last reference - the same teardown a Record toggle
     * runs.
     *
     * <p>This runs on the thread that feeds the ring, which is the only place
     * in the application that can know nothing more is coming - from any
     * reader's side a dead lane and a silent one are both "no new samples".
     * Marking it here means every reader over that buffer can answer
     * truthfully, and {@link #acquire()} can refuse to hand out a fresh cursor
     * over it, without anybody keeping a second copy of the fact.
     * {@code stream} is THIS lane's buffer, never the current one, so a
     * callback that outlives its lane cannot end a stream that replaced it.
     *
     * <p>Deliberately takes no monitor: it is called from the audio callback,
     * and {@code acquire} can hold the monitor for the length of a remote
     * device open.  {@code finish} synchronises on the buffer itself, which no
     * long operation ever holds.
     */
    private void laneFailed(CaptureEndReason reason, Throwable logDetail,
            AtomicBoolean reported, SignalBuffer stream) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        if (logDetail != null) {
            log.error("Capture: the input lane failed - {}", reason.logText(), logDetail);
        } else {
            log.error("Capture: the input lane failed - {}", reason.logText());
        }
        stream.finish(reason);
    }

    /** Releases one reference.  Closes the device only when the last
     *  reference goes away.  Safe to call when no reference is held. */
    public synchronized void release() {
        if (refCount <= 0) return;
        refCount--;
        if (refCount > 0) return;
        closeLine();
        sharedBuffer = null;
        log.info("Audio capture stopped (last reference released).");
    }

    /** Gives the device back.  Null-safe and idempotent - the line may already
     *  have gone with a lane that was discarded. */
    private void closeLine() {
        if (capture != null) {
            Closeables.tryQuietly("capture.stopRecording", capture::stopRecording);
            Closeables.closeQuietly(capture);
            capture = null;
        }
    }

    /**
     * Lets go of a lane whose stream has ENDED, so a fresh open starts clean.
     *
     * <p>The reference count is deliberately left alone: it counts acquires
     * against releases, and holders that have not yet run their stop still owe one
     * each.  Zeroing it here would strand them - their release would find nothing
     * to decrement and the NEXT lane would then be closed one reference early.
     * Letting each of them decrement the count the new lane inherits keeps the
     * number of outstanding references correct throughout, which is the only
     * property the count has ever had.
     *
     * <p>The device goes back before the new one is opened: a line held across
     * captures is what makes the next one deliver silence.
     */
    private void discardFinishedLane() {
        SignalBuffer dead = sharedBuffer;
        if (dead == null || !dead.isFinished()) return;
        if (log.isWarnEnabled()) {
            log.warn("Capture: re-opening after the input stream ended - {}",
                    dead.getFinishedReason());
        }
        closeLine();
        sharedBuffer = null;
    }

    /**
     * The sentence a failed capture open leaves on screen: WHERE it failed -
     * device, rate, depth - and WHY, both in the operator's language.
     *
     * <p>The why is not decided here any more.  This method used to sniff the
     * driver's English for "busy" / "not found" / "exclusive", which is a
     * classifier living as far from the native error as it could possibly be,
     * and whose last branch printed the raw driver text at the operator.  The
     * backend that OWNS the error now answers with a
     * {@link DeviceFailureReason} ({@code AudioDeviceManager.classifyFailure}),
     * the same vocabulary a remote bench sends over the wire and the same one
     * the generator renders - one classification, both directions.  The raw
     * text keeps going to the log, where it belongs.
     */
    private String openFailureText(DeviceFailureReason reason, DeviceRef device,
                                   int sampleRate, int bitDepth) {
        return I18n.t("capture.error.openHeader", device.displayName(),
                String.valueOf(sampleRate), String.valueOf(bitDepth))
                + "\n\n" + I18n.t(reason.i18nKey());
    }

    /** Undoes a half-built lane.  The reference count is NOT touched: this open
     *  never incremented it, and it may already carry holders of a lane discarded
     *  just before - zeroing it would strand their releases. */
    private void cleanupAfterFailure() {
        Closeables.closeQuietly(capture);
        capture = null;
        sharedBuffer = null;
    }

}
