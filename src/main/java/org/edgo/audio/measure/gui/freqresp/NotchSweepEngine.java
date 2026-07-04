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

package org.edgo.audio.measure.gui.freqresp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Self-contained CONTINUOUS play+capture for the Tune-notch wizard's live sweep
 * loop.  The playback + capture lines are opened ONCE in {@link #start} and held
 * open for the whole wizard session: a MAX_PRIORITY generator thread plays a
 * <em>looping</em> Farina log-sweep (Hann-faded at each cycle seam) while the
 * recorder streams both ADC channels into a circular ring buffer, never
 * stopping until {@link #close}.
 *
 * <p>This is the speed win over a per-sweep open/close engine: there is no
 * per-sweep ~0.59 s device open/close overhead and no ~0.86 s real-time
 * recording wait — the wizard simply grabs the most recent one-period window
 * from the ring whenever it wants a fresh result (≈10 updates/s).  It also
 * sidesteps the known "second sweep captures zeros" bug: that came from
 * stop→replay on a held-open line, but a line that streams continuously is
 * never restarted.
 *
 * <p>No alignment of the grabbed window to the sweep-cycle start is needed —
 * but ONLY because the wizard makes the loop period a power of two, so
 * {@code computeFromLogSweep}'s {@code nextPow2(period)} FFT is exactly one
 * period: a CIRCULAR transform.  A window grabbed at any loop phase is then
 * just a circular shift, which leaves the magnitude {@code |H(f)|} unchanged.
 * (With a non-power-of-two period the FFT would be zero-padded/linear, an
 * unaligned window would wrap the period across the buffer, and every bin
 * would corrupt into a random spike.)
 *
 * <p>Thread-safety: the capture-thread listener WRITES the ring under
 * {@link #ringLock}; the wizard's worker thread READS via
 * {@link #latestPeriod} under the same lock.  The copy is small and infrequent
 * (~10/s), so the lock is uncontended in practice.
 */
@Log4j2
public final class NotchSweepEngine implements AutoCloseable {

    /** Bound on the wait for the generator thread to report it has pre-filled
     *  the hardware buffer (mirrors {@code runStereo}). */
    private static final long GEN_READY_TIMEOUT_S = 10L;
    /** Bound on the join when stopping the generator thread. */
    private static final long GEN_JOIN_TIMEOUT_MS = 5000L;
    /** Ring capacity floor: a few sweep periods, never below one second, so the
     *  most-recent window is always present even if the wizard grabs late. */
    private static final int RING_PERIODS = 4;

    private final DeviceRef out;
    private final DeviceRef in;
    private final int       sampleRate;
    private final int       bitDepth;
    private final int       ditherBits;
    private final long      halfRange;

    // Session state, set up in start() and torn down in close().
    private SignalGenerator gen;
    private AudioPlayback   playback;
    private AudioCapture    capture;
    private Thread          genThread;
    private final AtomicBoolean genStop = new AtomicBoolean(false);

    // Circular ring buffer of normalised [-1, +1] L/R doubles.  Written by the
    // capture thread, read by the wizard worker thread, both under ringLock.
    private final Object ringLock = new Object();
    private double[]     ringL = new double[0];
    private double[]     ringR = new double[0];
    private int          ringCapacity;
    /** Total samples written since start() — monotone; the write cursor is
     *  {@code totalWritten % ringCapacity}. */
    private long         totalWritten;

    public NotchSweepEngine(DeviceRef out, DeviceRef in, int sampleRate, int bitDepth, int ditherBits) {
        this.out        = out;
        this.in         = in;
        this.sampleRate = sampleRate;
        this.bitDepth   = bitDepth;
        this.ditherBits = ditherBits;
        this.halfRange  = 1L << (bitDepth - 1);
    }

    /**
     * Deconvolution FFT bin spacing (Hz) for a single captured period of
     * {@code sweepSamples} — {@code sampleRate / nextPow2(sweepSamples)}.  The
     * wizard matches its output-grid density to this so the bin→grid
     * interpolation never oversamples (oversampling facets the trace into a
     * kink + comb).  Reflects the actual {@code yRec} length the wizard passes
     * to {@link FreqRespCalHelper#computeFromLogSweep} (one period, leadIn 0).
     */
    public double deconvBinHz(int sweepSamples) {
        return sampleRate / (double) MathUtil.nextPow2(sweepSamples);
    }

    /** The looping sweep's one-period reference X(t) — the buffer
     *  {@link FreqRespCalHelper#computeFromLogSweep} deconvolves against. */
    public double[] sweepRef() {
        return gen.getLogSweepBuffer();
    }

    // -------------------------------------------------------------------------
    // Session lifecycle: open once, stream forever, close once
    // -------------------------------------------------------------------------

    /**
     * Opens the playback + capture lines ONCE, builds a looping Farina
     * log-sweep, installs the ring-buffer capture listener, and starts a
     * MAX_PRIORITY generator thread that plays the loop until {@link #close}.
     * Returns as soon as the playback hardware buffer is pre-filled and
     * recording has started.
     */
    public void start(double f0, double f1, double ampVrms, double dacFsVrms,
                      int sweepSamples, int fadeSamples) throws Exception {
        gen = new SignalGenerator(f0, f1, sweepSamples, 0, sampleRate, ampVrms, dacFsVrms);
        // Loop ON with Hann fades at each cycle boundary: logSweepNext() replays
        // the buffer back-to-back and the per-side fades smooth the loop seam.
        // The SAME fadeSamples must be applied to the deconvolution reference.
        gen.setSweepParams(true, fadeSamples, fadeSamples);

        ringCapacity = Math.max(sampleRate, RING_PERIODS * sweepSamples);
        synchronized (ringLock) {
            ringL = new double[ringCapacity];
            ringR = new double[ringCapacity];
            totalWritten = 0L;
        }
        genStop.set(false);

        playback = AudioBackend.instance().openPlayback(out, sampleRate, bitDepth, ditherBits);
        playback.open();
        capture = AudioBackend.instance().openCapture(in, sampleRate, bitDepth);
        installListener(capture);
        capture.open();

        CountDownLatch genReady = new CountDownLatch(1);
        genThread = newGenThread(genReady);
        genThread.start();
        awaitGenReady(genReady);
        capture.startRecording();
    }

    /** Live band change WITHOUT restart: rebuilds the looping buffer in place
     *  (thread-safe, volatile inside the generator). */
    public void setBand(double f0, double f1) {
        SignalGenerator g = gen;
        if (g == null) return;
        g.setSweepFreqStart(f0);
        g.setSweepFreqEnd(f1);
    }

    /** Total samples captured so far — drives the settle / fill percentage. */
    public long availableSamples() {
        synchronized (ringLock) {
            return totalWritten;
        }
    }

    /**
     * Thread-safely copies the most recent {@code n} samples (L and R) from the
     * ring into a fresh {@link StereoSamples}, unwrapping the circular layout.
     * Returns {@code null} if fewer than {@code n} samples have been captured
     * yet (still settling).
     */
    public StereoSamples latestPeriod(int n) {
        double[] l = new double[n];
        double[] r = new double[n];
        synchronized (ringLock) {
            if (totalWritten < n) return null;
            // The window ends at the latest written sample; start n back.
            int start = (int) ((totalWritten - n) % ringCapacity);
            int first = Math.min(n, ringCapacity - start);
            System.arraycopy(ringL, start, l, 0, first);
            System.arraycopy(ringR, start, r, 0, first);
            if (first < n) {
                System.arraycopy(ringL, 0, l, first, n - first);
                System.arraycopy(ringR, 0, r, first, n - first);
            }
        }
        return new StereoSamples(l, r);
    }

    @Override
    public void close() {
        genStop.set(true);
        if (capture != null) {
            try {
                capture.stopRecording();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.warn("NotchSweepEngine: stopRecording failed", ex);
            }
        }
        joinGen();
        if (capture != null) {
            try {
                capture.close();
            } catch (Exception ex) {
                log.warn("NotchSweepEngine: capture close failed", ex);
            }
            capture = null;
        }
        // The gen thread closes the playback line in its finally block; clear
        // the reference so a stray setBand() after close is a no-op.
        playback = null;
        genThread = null;
        gen = null;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Plays {@code gen} on a fresh MAX_PRIORITY thread until {@link #genStop},
     *  closing the playback line when play() returns (this path owns the line). */
    private Thread newGenThread(CountDownLatch genReady) {
        AudioPlayback pb = playback;
        SignalGenerator g = gen;
        Thread t = new Thread(() -> {
            try {
                pb.play(g, genStop, genReady);
            } catch (Exception ex) {
                log.error("NotchSweepEngine: generator error", ex);
            } finally {
                pb.close();
            }
        }, "notch-gen-thread");
        t.setPriority(Thread.MAX_PRIORITY);
        // Daemon: if the backend's play()/close() ever stalls on teardown the
        // JVM must still exit (every other wizard thread is a daemon too) — a
        // non-daemon stall here held the whole app open for >1 min after close.
        t.setDaemon(true);
        return t;
    }

    private void awaitGenReady(CountDownLatch genReady) throws InterruptedException {
        if (!genReady.await(GEN_READY_TIMEOUT_S, TimeUnit.SECONDS)) {
            genStop.set(true);
            throw new IllegalStateException(
                    "Generator did not start streaming within " + GEN_READY_TIMEOUT_S + " s — capture aborted.");
        }
    }

    private void joinGen() {
        Thread t = genThread;
        if (t == null) return;
        try {
            t.join(GEN_JOIN_TIMEOUT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.warn("NotchSweepEngine: generator thread still running after {} ms — playback device may remain busy.",
                    GEN_JOIN_TIMEOUT_MS);
        }
    }

    /** Installs the stereo sample listener that converts each frame's ch0/ch1
     *  raw ADC codes into normalised [-1, +1] left/right doubles and appends
     *  them to the circular ring (same conversion as {@code runStereo}). */
    private void installListener(AudioCapture capture) {
        capture.setSampleListener(stereo -> {
            synchronized (ringLock) {
                int pos = (int) (totalWritten % ringCapacity);
                for (int i = 0; i < stereo.length; i++) {
                    double code0 = (double) (stereo[i].ch0 & 0xFFFFFFFFL);
                    double code1 = (double) (stereo[i].ch1 & 0xFFFFFFFFL);
                    ringL[pos] = (code0 - halfRange) / (double) halfRange;
                    ringR[pos] = (code1 - halfRange) / (double) halfRange;
                    pos++;
                    if (pos == ringCapacity) pos = 0;
                }
                totalWritten += stereo.length;
            }
        });
    }
}
