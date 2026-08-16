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

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import lombok.extern.log4j.Log4j2;

/**
 * The QA402/QA403 session - ONE always-duplex stream per open transport (see
 * {@code doc/QA40X-PROTOCOL.md} §10 "Session model").  Input and output are a
 * single hardware streaming session: on the QA403 reads only complete once the
 * output priming crosses the 1024-frame start threshold, so the two directions
 * can never be started independently.  Rather than restart the running direction
 * when the other side joins, this engine keeps one primed duplex stream and lets
 * clients attach / detach lanes.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>The FIRST attach ({@link #attachGenerator} or {@link #attachCapture})
 *       starts the stream: {@code reg8=0 -> reg5 -> reg6 -> reg9 -> 100 ms settle ->
 *       reg8=5}, then primes the output past the 1024-frame threshold with steady
 *       16 KB chunks (2048 stereo int32 frames) and keeps
 *       {@value #IN_FLIGHT_TRANSFERS} reads in flight.  A completed read is the
 *       pacing clock - it submits the next read and, only while fewer than
 *       {@value #IN_FLIGHT_TRANSFERS} writes are already outstanding, the next
 *       write (§5).  The write side is bounded the same way as the read side
 *       (double-buffered): a burst of read completions - e.g. under OS scheduling
 *       jitter, dramatically worse when the app window is unfocused and the host
 *       throttles the transfer thread - can NEVER inflate the output queue, so
 *       playback stays sample-locked to capture instead of racing open-loop
 *       ahead.</li>
 *   <li>An unattached generator lane sends silence; an unattached capture lane's
 *       reads are discarded.  A LATER attach swaps the silence source for real
 *       samples <b>live</b> - no restart, no register write.</li>
 *   <li>The LAST detach stops: {@code cancelAll} strictly BEFORE {@code reg8=0}
 *       (§7 step 7); the transport never pipe-resets / clear-halts (§3).</li>
 *   <li>A range or sample-rate change while running does a full stop + start -
 *       reg 9 is a shared rate register, so the app's input/output rates must be
 *       constrained equal for this backend (§10).</li>
 * </ul>
 *
 * <h2>Wire format (§5)</h2>
 * Samples are interleaved stereo int32 <b>little-endian</b>.  The DAC output L/R
 * are <b>swapped</b> before sending (device out ch0 <- logical R).  The ADC input
 * is NOT swapped and the right input is NOT inverted - those are QA401-only
 * quirks (§9 item 6).
 */
@Log4j2
public final class Qa40xDuplexEngine implements Qa40xTransport.TransferListener {

    private static final int CHANNELS               = 2;
    private static final int BYTES_PER_SAMPLE       = 4;
    private static final int FRAME_BYTES            = CHANNELS * BYTES_PER_SAMPLE;
    /** Steady-state frames per transfer - 2048 stereo frames = 16 KB (§5). */
    private static final int STEADY_FRAMES          = 2048;
    private static final int STEADY_CHUNK_BYTES     = STEADY_FRAMES * FRAME_BYTES;
    /** Transfers kept in flight per direction - double-buffered (§5). */
    private static final int IN_FLIGHT_TRANSFERS    = 2;
    /** Output frames that must be queued before the QA403 starts streaming (§5). */
    private static final int START_THRESHOLD_FRAMES = 1024;
    /** Upper bound on banked write debt.  In a matched-clock duplex the ADC and DAC
     *  run at one rate, so the backlog oscillates near zero and never reaches this;
     *  the ceiling only guards a genuinely stuck output from unbounded latency, and
     *  it is generous (≈ 0.34 s at 192 kHz) so ordinary read-completion bursts are
     *  fully repaid rather than forfeited - a forfeited write is a silent DAC frame,
     *  which the loopback captures as a discontinuity. */
    private static final int MAX_WRITE_DEBT          = 32;
    /** Rate-write settle delay - the ABA hazard guard (§8). */
    private static final long SETTLE_MILLIS         = 100;

    /** Zero-fill source for an unattached generator lane. */
    private static final SampleSource SILENCE =
            (destination, frames) -> Arrays.fill(destination, 0, frames * CHANNELS, 0);

    private final Qa40xTransport transport;
    private final Sleeper sleeper;
    /** The front-panel I2S setting, consulted at each session boundary. */
    private final BooleanSupplier i2sEnabled;
    /** The session's frame width in bits, written to reg 0x0B when the port is on. */
    private final IntSupplier i2sBits;
    private final int[] scratch = new int[STEADY_FRAMES * CHANNELS];
    private final Deque<byte[]> freeReadBuffers  = new ArrayDeque<>();
    private final Deque<byte[]> freeWriteBuffers = new ArrayDeque<>();

    /** Guards the mutable stream state below.  Held ONLY for short, non-blocking
     *  sections - <b>never</b> across a {@link Qa40xTransport#registerWrite} or a
     *  {@link Sleeper#sleep}.  A register write is a SYNCHRONOUS bulk transfer that
     *  needs libusb's per-context event lock, and the USB event thread holds that
     *  same lock while it dispatches completion callbacks into this class.  Holding
     *  this monitor across a register write therefore deadlocks the two threads:
     *  the stopping thread waits for the event lock, the event thread waits for the
     *  monitor, and libusb cannot even time the transfer out because it enforces
     *  timeouts from inside the very event loop that is stuck.  Reproduced on the
     *  bench by stopping the generator, or by changing a range mid-capture; a mock
     *  transport can NOT reproduce it, because the second edge of the cycle is a
     *  native lock. */
    private final Object stateLock = new Object();

    /** Serialises the start / stop / re-range register sequences so two threads can
     *  never interleave register traffic.  The completion callbacks NEVER take it,
     *  so they can never block behind device I/O.  Lock order is always
     *  {@code ioLock} -> {@link #stateLock}, never the reverse. */
    private final Object ioLock = new Object();

    private int inputRangeDbv;
    private int outputRangeDbv;
    private int sampleRateHz;
    private SampleSource source = SILENCE;
    private CaptureConsumer consumer;
    private boolean generatorAttached;
    private boolean captureAttached;
    private boolean streaming;
    /** Outstanding playback transfers - capped at {@link #IN_FLIGHT_TRANSFERS} so a
     *  burst of read completions cannot pump the output queue open-loop (§5). */
    private int writesInFlight;
    /** Write debt: read completions whose paced write found both slots busy.  A
     *  2048-frame write drains through the device's 1024-frame queue in a full
     *  read period, so the skip-vs-submit race is routine - the debt is repaid the
     *  moment {@link #writeCompleted} frees a slot, keeping the long-run pacing
     *  exactly 1:1 (skipping without repayment lost ~⅓ of all
     *  writes - a periodic underrun "meander").  Bounded by {@link #MAX_WRITE_DEBT}
     *  - generous, so read-completion bursts are repaid rather than forfeited: a
     *  forfeited write is a silent DAC frame the loopback captures as a
     *  discontinuity. */
    private int writesOwed;

    /** Without an I2S source the front-panel generator simply stays off - the
     *  state every session then starts and ends in. */
    public Qa40xDuplexEngine(Qa40xTransport transport, Sleeper sleeper,
                             int inputRangeDbv, int outputRangeDbv, int sampleRateHz) {
        this(transport, sleeper, inputRangeDbv, outputRangeDbv, sampleRateHz,
                () -> false, () -> Qa40xProtocol.I2S_BITS_32);
    }

    /**
     * @param i2sEnabled the front-panel I2S setting, READ at each session
     *                   boundary rather than copied - so a Preferences OK
     *                   between sessions is picked up without any push, and a
     *                   stale copy cannot leave the port running.
     */
    public Qa40xDuplexEngine(Qa40xTransport transport, Sleeper sleeper,
                             int inputRangeDbv, int outputRangeDbv, int sampleRateHz,
                             BooleanSupplier i2sEnabled, IntSupplier i2sBits) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.i2sEnabled = Objects.requireNonNull(i2sEnabled, "i2sEnabled");
        this.i2sBits = Objects.requireNonNull(i2sBits, "i2sBits");
        // Fail fast on an invalid range / rate via the protocol code maps.
        Qa40xProtocol.inputRangeCode(inputRangeDbv);
        Qa40xProtocol.outputRangeCode(outputRangeDbv);
        Qa40xProtocol.sampleRateCode(sampleRateHz);
        this.inputRangeDbv = inputRangeDbv;
        this.outputRangeDbv = outputRangeDbv;
        this.sampleRateHz = sampleRateHz;
        transport.setListener(this);
    }

    /** Attaches / live-swaps the generator lane's sample source; starts the stream if idle. */
    public void attachGenerator(SampleSource generatorSource) {
        Objects.requireNonNull(generatorSource, "generatorSource");
        synchronized (ioLock) {
            boolean start;
            synchronized (stateLock) {
                this.source = generatorSource;
                generatorAttached = true;
                start = !streaming;
            }
            if (start) {
                startStream();
            }
        }
    }

    /** Detaches the generator lane (reverts to silence); stops the stream if it was the last client. */
    public void detachGenerator() {
        synchronized (ioLock) {
            boolean stop;
            synchronized (stateLock) {
                this.source = SILENCE;
                generatorAttached = false;
                stop = streaming && !captureAttached;
            }
            if (stop) {
                endSession();
            }
        }
    }

    /**
     * Attaches the capture lane's consumer; starts the stream if idle.  A duplex
     * engine has exactly ONE capture consumer - the scope and the FFT share it
     * through {@code SharedCapture} (doc §10).  A second attach with a lane already
     * live is a wiring bug (two independent capture owners on one stream); it is
     * refused loudly rather than silently overwriting the first consumer, which
     * would starve one view and split the single capture lane.
     */
    public void attachCapture(CaptureConsumer captureConsumer) {
        Objects.requireNonNull(captureConsumer, "captureConsumer");
        synchronized (ioLock) {
            boolean start;
            synchronized (stateLock) {
                if (captureAttached) {
                    throw new IllegalStateException("QA40x capture lane already attached - "
                            + "one duplex engine has a single capture consumer (scope and FFT share it "
                            + "through SharedCapture); refusing to split the stream (doc §10)");
                }
                this.consumer = captureConsumer;
                captureAttached = true;
                start = !streaming;
            }
            if (start) {
                startStream();
            }
        }
    }

    /** Detaches the capture lane (reads discarded); stops the stream if it was the last client. */
    public void detachCapture() {
        synchronized (ioLock) {
            boolean stop;
            synchronized (stateLock) {
                this.consumer = null;
                captureAttached = false;
                stop = streaming && !generatorAttached;
            }
            if (stop) {
                endSession();
            }
        }
    }

    /**
     * True while either lane is still attached.
     *
     * <p>A read, deliberately: the transport and this session belong to the
     * manager above, and it asks after each detach whether the analyzer is still
     * in use before releasing it.  A stream that stopped is not the same fact as
     * a device nobody wants any more - this engine knows only the first, and
     * telling the manager what to do with the second would turn a one-way
     * ownership into a cycle.
     */
    public boolean anyLaneAttached() {
        synchronized (stateLock) {
            return generatorAttached || captureAttached;
        }
    }

    /**
     * The LAST detach: the stream stops, the analyzer is PARKED at the protected
     * ranges, and the front-panel I2S port is switched off - every one of them,
     * whatever the one before it did.
     *
     * <p>Written as a bare sequence this was the safety bug.  Pull the analyzer's
     * USB cable mid-generation and the stop's register write raises an Error out
     * of the binding (JNA turns a failed native invocation into one); the park
     * and the port shutdown were simply never reached, and the device that came
     * back was left sitting at whatever sensitive input range the measurement had
     * used.  A transport that is dead will refuse the park too - nothing can be
     * written to a device that is gone - but a transport that failed only the run
     * register still hears it, and that is the case the attenuator has to survive.
     *
     * <p>Caller holds {@link #ioLock} and must NOT hold {@link #stateLock}, like
     * each of the three steps.
     */
    private void endSession() {
        try {
            stopStream();
        } finally {
            try {
                parkSafeRanges();
            } finally {
                stopI2s();
            }
        }
    }

    /** Changes the input full-scale range; a full stop + start if streaming. */
    public void changeInputRange(int dbv) {
        Qa40xProtocol.inputRangeCode(dbv);          // fail fast before touching state
        synchronized (ioLock) {
            synchronized (stateLock) {
                this.inputRangeDbv = dbv;
            }
            restartIfStreaming();
        }
    }

    /** Changes the output full-scale range; a full stop + start if streaming. */
    public void changeOutputRange(int dbv) {
        Qa40xProtocol.outputRangeCode(dbv);
        synchronized (ioLock) {
            synchronized (stateLock) {
                this.outputRangeDbv = dbv;
            }
            restartIfStreaming();
        }
    }

    /** Changes the sample rate; a full stop + start if streaming (§8/§10). */
    public void changeSampleRate(int hz) {
        Qa40xProtocol.sampleRateCode(hz);
        synchronized (ioLock) {
            synchronized (stateLock) {
                this.sampleRateHz = hz;
            }
            restartIfStreaming();
        }
    }

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock}. */
    private void restartIfStreaming() {
        boolean running;
        synchronized (stateLock) {
            running = streaming;
        }
        if (running) {
            stopStream();
            startStream();
        }
    }

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock} - every
     *  {@code registerWrite} below is a blocking bulk transfer, and the settle is a
     *  100 ms sleep (see {@link #stateLock} for why that combination deadlocks). */
    private void startStream() {
        int inputCode;
        int outputCode;
        int rateCode;
        synchronized (stateLock) {
            inputCode  = Qa40xProtocol.inputRangeCode(inputRangeDbv);
            outputCode = Qa40xProtocol.outputRangeCode(outputRangeDbv);
            rateCode   = Qa40xProtocol.sampleRateCode(sampleRateHz);
        }
        transport.registerWrite(Qa40xProtocol.REG_RUN, Qa40xProtocol.RUN_STOP);           // recover / idle
        transport.registerWrite(Qa40xProtocol.REG_INPUT_FS, inputCode);
        transport.registerWrite(Qa40xProtocol.REG_OUTPUT_FS, outputCode);
        transport.registerWrite(Qa40xProtocol.REG_SAMPLE_RATE, rateCode);
        // Front-panel I2S generator (§4 reg 0x0A) - driven to the CURRENT setting
        // rather than only switched on, so the port state always matches the
        // preference.  Written unconditionally, which also makes a restart (range
        // or rate change) idempotent instead of interrupting the tone.
        // Front-panel I2S, both registers still before the engine starts: the
        // frame width (reg 0x0B, 16- or 32-bit taken from the output bit depth)
        // is set FIRST, so the port is already on the right width at the moment
        // the control register (reg 0x0A) starts it.  Both read 0 when off.
        boolean i2sOn = i2sEnabled.getAsBoolean();
        transport.registerWrite(Qa40xProtocol.REG_I2S_WIDTH,
                i2sOn ? Qa40xProtocol.i2sWidthCode(i2sBits.getAsInt())
                      : Qa40xProtocol.I2S_WIDTH_OFF);
        transport.registerWrite(Qa40xProtocol.REG_I2S,
                i2sOn ? Qa40xProtocol.I2S_START : Qa40xProtocol.I2S_STOP);
        sleeper.sleep(SETTLE_MILLIS);                                                      // ABA settle (§8)
        transport.registerWrite(Qa40xProtocol.REG_RUN, Qa40xProtocol.RUN_START);          // start
        synchronized (stateLock) {
            streaming = true;
            writesInFlight = 0;
            writesOwed = 0;
            primeStream();
        }
    }

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock}: {@code cancelAll}
     *  arms a completion for every in-flight transfer, and the USB event thread delivers
     *  those into {@link #transferFailed} - which needs {@link #stateLock} - while holding
     *  libusb's event lock, the very lock the blocking {@code registerWrite} below waits
     *  for.  Clearing {@code streaming} first makes the completions bail out cheaply. */
    private void stopStream() {
        synchronized (stateLock) {
            streaming = false;
        }
        try {
            transport.cancelAll();                                                        // BEFORE reg8=0 (§7 step 7)
        } finally {
            // Even a cancel that blew up leaves the engine running on the device
            // until reg8 says otherwise, so the stop register is written either
            // way - and the caller still sees the original fault.
            transport.registerWrite(Qa40xProtocol.REG_RUN, Qa40xProtocol.RUN_STOP);
        }
    }

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock} (blocking
     *  bulk writes).  Parks the idle analyzer at the protected ranges (doc §7
     *  step 8) so a sensitive range never sits live between measurements; the
     *  next {@link #startStream} re-applies the session ranges.  Deliberately
     *  NOT part of {@link #stopStream()}: the restart path (a range / rate
     *  change) would otherwise clack the attenuator relay to +42 dBV and back
     *  on every change. */
    private void parkSafeRanges() {
        transport.registerWrite(Qa40xProtocol.REG_INPUT_FS,
                Qa40xProtocol.inputRangeCode(Qa40xProtocol.SAFE_INPUT_DBV));
        transport.registerWrite(Qa40xProtocol.REG_OUTPUT_FS,
                Qa40xProtocol.outputRangeCode(Qa40xProtocol.SAFE_OUTPUT_DBV));
    }

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock}.  Stops
     *  the front-panel I2S generator when the SESSION ends, leaving the port in
     *  the same state a fresh connect finds it (§6 safe init).  Sits beside
     *  {@link #parkSafeRanges()} and NOT in {@link #stopStream()} for the same
     *  reason: a restart (range / rate change) would otherwise interrupt the I2S
     *  tone on every change. */
    private void stopI2s() {
        transport.registerWrite(Qa40xProtocol.REG_I2S, Qa40xProtocol.I2S_STOP);
        transport.registerWrite(Qa40xProtocol.REG_I2S_WIDTH, Qa40xProtocol.I2S_WIDTH_OFF);
    }

    /** Caller holds {@link #stateLock} - the submits touch the buffer pools and the
     *  write-pacing counters, and are non-blocking (an async submit, not a bulk transfer). */
    private void primeStream() {
        for (int i = 0; i < IN_FLIGHT_TRANSFERS; i++) {
            submitRead();
        }
        // Enough steady chunks to cross the start threshold, at least the in-flight
        // count - with 2048-frame chunks a single chunk already exceeds 1024 (§5).
        int primeWrites = Math.max(IN_FLIGHT_TRANSFERS,
                (START_THRESHOLD_FRAMES + STEADY_FRAMES - 1) / STEADY_FRAMES);
        for (int i = 0; i < primeWrites; i++) {
            submitWrite();
        }
    }

    private void submitRead() {
        transport.submitAudioRead(borrowReadBuffer());
    }

    private void submitWrite() {
        byte[] chunk = borrowWriteBuffer();
        fillWriteChunk(chunk);
        transport.submitAudioWrite(chunk, STEADY_CHUNK_BYTES);
        writesInFlight++;
    }

    private void fillWriteChunk(byte[] chunk) {
        source.nextFrames(scratch, STEADY_FRAMES);
        for (int f = 0; f < STEADY_FRAMES; f++) {
            int left  = scratch[CHANNELS * f];
            int right = scratch[CHANNELS * f + 1];
            int offset = f * FRAME_BYTES;
            // DAC L/R swapped before sending (§5): device slot 0 = R, slot 1 = L.
            putLittleEndianInt(chunk, offset, right);
            putLittleEndianInt(chunk, offset + BYTES_PER_SAMPLE, left);
        }
    }

    // The three completion callbacks below run on the USB event thread while libusb
    // holds its per-context event lock.  They take ONLY stateLock - never ioLock -
    // so a completion can never block behind a register write (see stateLock).

    @Override
    public void readCompleted(byte[] buffer, int transferred) {
        synchronized (stateLock) {
            if (!streaming) {                       // a late / cancelled completion after stop
                returnReadBuffer(buffer);
                return;
            }
            submitRead();                           // re-arm FIRST - the pipe never waits on the consumer (§5)
            CaptureConsumer sink = consumer;
            if (sink != null) {
                sink.onAudio(buffer, transferred);  // ADC bytes pass through - not swapped, not inverted (§9 item 6)
            }
            returnReadBuffer(buffer);
            writesOwed = Math.min(writesOwed + 1, MAX_WRITE_DEBT);
            drainOwedWrites();                      // read-clocked, bounded, AND debt-repaying (1:1 long-run)
        }
    }

    @Override
    public void writeCompleted(byte[] buffer, int transferred) {
        synchronized (stateLock) {
            if (writesInFlight > 0) {
                writesInFlight--;                   // a drained buffer frees an in-flight slot for the next read-clocked write
            }
            returnWriteBuffer(buffer);
            if (streaming) {
                drainOwedWrites();                  // repay a write skipped while both slots were busy
            }
        }
    }

    /** Submits owed writes while an in-flight slot is free - writes stay read-clocked
     *  (only read completions create debt) and bounded (never more than
     *  {@link #IN_FLIGHT_TRANSFERS} outstanding), but a write skipped at the cap is
     *  repaid as soon as a slot frees instead of being lost. */
    private void drainOwedWrites() {
        while (writesOwed > 0 && writesInFlight < IN_FLIGHT_TRANSFERS) {
            writesOwed--;
            submitWrite();
        }
    }

    /**
     * A transfer did not complete.  Two very different things arrive here and
     * telling them apart is the whole job: a transfer CANCELLED by our own stop
     * (benign - {@link #stopStream()} clears {@link #streaming} before it
     * cancels, precisely so these bail out cheaply), and a transfer that failed
     * because the device is gone or the stream broke MID-RUN.
     *
     * <p>The second case used to be treated as the first: counted, logged at
     * WARN and dropped.  But the read completion IS the pacing clock - {@link
     * #readCompleted} is what re-arms the next read - so a read that fails and
     * is not re-armed silently ends the whole duplex stream while
     * {@code streaming} still says true.  Nothing threw, nothing polled, and
     * both lanes went on believing they were running: an unplugged analyzer left
     * the generator "playing" and the scope drawing a frozen trace.
     *
     * <p>So a mid-run failure ends the session's streaming state here, and TELLS
     * BOTH LANES.  Both, because the duplex stream is one clock - half a stream
     * is not a state anything downstream can use - and because the two lanes are
     * owned by different objects, each of which has its own way of confessing to
     * the application above it.
     */
    @Override
    public void transferFailed(boolean read, String detail) {
        // No auto-recovery (§5).
        boolean lost;
        SampleSource failedSource;
        CaptureConsumer failedConsumer;
        synchronized (stateLock) {
            if (!read && writesInFlight > 0) {
                writesInFlight--;               // a cancelled / failed write frees its in-flight slot
            }
            lost = streaming;                   // false => our own stop cancelled it
            streaming = !lost && streaming;
            failedSource = source;
            failedConsumer = consumer;
        }
        if (!lost) {
            if (log.isDebugEnabled()) {
                log.debug("QA40x {} transfer cancelled by the stop: {}",
                        read ? "read" : "write", detail);
            }
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("QA40x {} transfer failed mid-stream - the session is over: {}",
                    read ? "read" : "write", detail);
        }
        // Each lane's own confession, and neither may stop the other being told.
        // THROWABLE: this runs on the USB event thread, whose death would strand
        // every transfer still in flight.
        try {
            failedConsumer.laneFailed(detail);
        } catch (Throwable t) {
            log.warn("QA40x capture lane failed to report the loss: {}", t.toString());
        }
        try {
            failedSource.laneFailed(detail);
        } catch (Throwable t) {
            log.warn("QA40x generator lane failed to report the loss: {}", t.toString());
        }
    }

    private byte[] borrowReadBuffer() {
        byte[] buffer = freeReadBuffers.poll();
        return buffer != null ? buffer : new byte[STEADY_CHUNK_BYTES];
    }

    private void returnReadBuffer(byte[] buffer) {
        freeReadBuffers.offer(buffer);
    }

    private byte[] borrowWriteBuffer() {
        byte[] buffer = freeWriteBuffers.poll();
        return buffer != null ? buffer : new byte[STEADY_CHUNK_BYTES];
    }

    private void returnWriteBuffer(byte[] buffer) {
        freeWriteBuffers.offer(buffer);
    }

    private void putLittleEndianInt(byte[] buffer, int offset, int value) {
        buffer[offset]     = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
        buffer[offset + 2] = (byte) (value >> 16);
        buffer[offset + 3] = (byte) (value >> 24);
    }

    /** Source of DAC samples for the generator lane. */
    @FunctionalInterface
    public interface SampleSource {
        /**
         * Fills {@code destination} with {@code frames} interleaved LOGICAL L,R
         * int32 samples ({@code destination[2i]} = left, {@code destination[2i+1]}
         * = right).  The engine swaps L/R and packs little-endian before sending
         * (§5).  {@code destination.length} is at least {@code 2 * frames}.
         */
        void nextFrames(int[] destination, int frames);

        /** The stream this lane was feeding has ENDED and will not resume - the
         *  device is gone or its transfers failed mid-run.  Default no-op: a
         *  source that has nobody to tell (the silence filler) needs nothing,
         *  while the real generator lane uses it to fail its play call, which is
         *  what the application above is already watching for. */
        default void laneFailed(String detail) { }
    }

    /** Sink for ADC samples on the capture lane. */
    @FunctionalInterface
    public interface CaptureConsumer {
        /**
         * Receives one ADC read: {@code length} bytes of {@code buffer},
         * interleaved little-endian int32 stereo (L,R) straight off the input
         * endpoint - NOT swapped, right NOT inverted (§9 item 6).  Consume
         * synchronously; the buffer is recycled after return.
         */
        void onAudio(byte[] buffer, int length);

        /** The stream feeding this lane has ENDED and will not resume.  Default
         *  no-op; the recorder overrides it to tell whoever opened the capture,
         *  because a consumer that simply stops being called cannot tell a dead
         *  device from silence. */
        default void laneFailed(String detail) { }
    }

    /** Injected settle clock so the ABA rate-write delay (§8) is real in production yet instant in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }
}
