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

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

import lombok.extern.log4j.Log4j2;

/**
 * The QA402/QA403 session — ONE always-duplex stream per open transport (see
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
 *       starts the stream: {@code reg8=0 → reg5 → reg6 → reg9 → 100 ms settle →
 *       reg8=5}, then primes the output past the 1024-frame threshold with steady
 *       16 KB chunks (2048 stereo int32 frames) and keeps
 *       {@value #IN_FLIGHT_TRANSFERS} reads in flight.  A completed read is the
 *       pacing clock — it submits the next read and, only while fewer than
 *       {@value #IN_FLIGHT_TRANSFERS} writes are already outstanding, the next
 *       write (§5).  The write side is bounded the same way as the read side
 *       (double-buffered): a burst of read completions — e.g. under OS scheduling
 *       jitter, dramatically worse when the app window is unfocused and the host
 *       throttles the transfer thread — can NEVER inflate the output queue, so
 *       playback stays sample-locked to capture instead of racing open-loop
 *       ahead.</li>
 *   <li>An unattached generator lane sends silence; an unattached capture lane's
 *       reads are discarded.  A LATER attach swaps the silence source for real
 *       samples <b>live</b> — no restart, no register write.</li>
 *   <li>The LAST detach stops: {@code cancelAll} strictly BEFORE {@code reg8=0}
 *       (§7 step 7); the transport never pipe-resets / clear-halts (§3).</li>
 *   <li>A range or sample-rate change while running does a full stop + start —
 *       reg 9 is a shared rate register, so the app's input/output rates must be
 *       constrained equal for this backend (§10).</li>
 * </ul>
 *
 * <h2>Wire format (§5)</h2>
 * Samples are interleaved stereo int32 <b>little-endian</b>.  The DAC output L/R
 * are <b>swapped</b> before sending (device out ch0 ← logical R).  The ADC input
 * is NOT swapped and the right input is NOT inverted — those are QA401-only
 * quirks (§9 item 6).
 */
@Log4j2
public final class Qa40xDuplexEngine implements Qa40xTransport.TransferListener {

    private static final int CHANNELS               = 2;
    private static final int BYTES_PER_SAMPLE       = 4;
    private static final int FRAME_BYTES            = CHANNELS * BYTES_PER_SAMPLE;
    /** Steady-state frames per transfer — 2048 stereo frames = 16 KB (§5). */
    private static final int STEADY_FRAMES          = 2048;
    private static final int STEADY_CHUNK_BYTES     = STEADY_FRAMES * FRAME_BYTES;
    /** Transfers kept in flight per direction — double-buffered (§5). */
    private static final int IN_FLIGHT_TRANSFERS    = 2;
    /** Output frames that must be queued before the QA403 starts streaming (§5). */
    private static final int START_THRESHOLD_FRAMES = 1024;
    /** Upper bound on banked write debt.  In a matched-clock duplex the ADC and DAC
     *  run at one rate, so the backlog oscillates near zero and never reaches this;
     *  the ceiling only guards a genuinely stuck output from unbounded latency, and
     *  it is generous (≈ 0.34 s at 192 kHz) so ordinary read-completion bursts are
     *  fully repaid rather than forfeited — a forfeited write is a silent DAC frame,
     *  which the loopback captures as a discontinuity (bench 2026-07-17). */
    private static final int MAX_WRITE_DEBT          = 32;
    /** Rate-write settle delay — the ABA hazard guard (§8). */
    private static final long SETTLE_MILLIS         = 100;

    /** Zero-fill source for an unattached generator lane. */
    private static final SampleSource SILENCE =
            (destination, frames) -> Arrays.fill(destination, 0, frames * CHANNELS, 0);

    private final Qa40xTransport transport;
    private final Sleeper sleeper;
    private final int[] scratch = new int[STEADY_FRAMES * CHANNELS];
    private final Deque<byte[]> freeReadBuffers  = new ArrayDeque<>();
    private final Deque<byte[]> freeWriteBuffers = new ArrayDeque<>();

    /** Guards the mutable stream state below.  Held ONLY for short, non-blocking
     *  sections — <b>never</b> across a {@link Qa40xTransport#registerWrite} or a
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
     *  {@code ioLock} → {@link #stateLock}, never the reverse. */
    private final Object ioLock = new Object();

    private int inputRangeDbv;
    private int outputRangeDbv;
    private int sampleRateHz;
    private SampleSource source = SILENCE;
    private CaptureConsumer consumer;
    private boolean generatorAttached;
    private boolean captureAttached;
    private boolean streaming;
    /** Outstanding playback transfers — capped at {@link #IN_FLIGHT_TRANSFERS} so a
     *  burst of read completions cannot pump the output queue open-loop (§5). */
    private int writesInFlight;
    /** Write debt: read completions whose paced write found both slots busy.  A
     *  2048-frame write drains through the device's 1024-frame queue in a full
     *  read period, so the skip-vs-submit race is routine — the debt is repaid the
     *  moment {@link #writeCompleted} frees a slot, keeping the long-run pacing
     *  exactly 1:1 (bench 2026-07-17: skipping without repayment lost ~⅓ of all
     *  writes — a periodic underrun "meander").  Bounded by {@link #MAX_WRITE_DEBT}
     *  — generous, so read-completion bursts are repaid rather than forfeited: a
     *  forfeited write is a silent DAC frame the loopback captures as a
     *  discontinuity. */
    private int writesOwed;

    public Qa40xDuplexEngine(Qa40xTransport transport, Sleeper sleeper,
                             int inputRangeDbv, int outputRangeDbv, int sampleRateHz) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
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
                stopStream();
                parkSafeRanges();
            }
        }
    }

    /**
     * Attaches the capture lane's consumer; starts the stream if idle.  A duplex
     * engine has exactly ONE capture consumer — the scope and the FFT share it
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
                    throw new IllegalStateException("QA40x capture lane already attached — "
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
                stopStream();
                parkSafeRanges();
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

    /** Caller holds {@link #ioLock} and must NOT hold {@link #stateLock} — every
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
     *  those into {@link #transferFailed} — which needs {@link #stateLock} — while holding
     *  libusb's event lock, the very lock the blocking {@code registerWrite} below waits
     *  for.  Clearing {@code streaming} first makes the completions bail out cheaply. */
    private void stopStream() {
        synchronized (stateLock) {
            streaming = false;
        }
        transport.cancelAll();                                                            // BEFORE reg8=0 (§7 step 7)
        transport.registerWrite(Qa40xProtocol.REG_RUN, Qa40xProtocol.RUN_STOP);
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

    /** Caller holds {@link #stateLock} — the submits touch the buffer pools and the
     *  write-pacing counters, and are non-blocking (an async submit, not a bulk transfer). */
    private void primeStream() {
        for (int i = 0; i < IN_FLIGHT_TRANSFERS; i++) {
            submitRead();
        }
        // Enough steady chunks to cross the start threshold, at least the in-flight
        // count — with 2048-frame chunks a single chunk already exceeds 1024 (§5).
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
    // holds its per-context event lock.  They take ONLY stateLock — never ioLock —
    // so a completion can never block behind a register write (see stateLock).

    @Override
    public void readCompleted(byte[] buffer, int transferred) {
        synchronized (stateLock) {
            if (!streaming) {                       // a late / cancelled completion after stop
                returnReadBuffer(buffer);
                return;
            }
            submitRead();                           // re-arm FIRST — the pipe never waits on the consumer (§5)
            CaptureConsumer sink = consumer;
            if (sink != null) {
                sink.onAudio(buffer, transferred);  // ADC bytes pass through — not swapped, not inverted (§9 item 6)
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

    /** Submits owed writes while an in-flight slot is free — writes stay read-clocked
     *  (only read completions create debt) and bounded (never more than
     *  {@link #IN_FLIGHT_TRANSFERS} outstanding), but a write skipped at the cap is
     *  repaid as soon as a slot frees instead of being lost. */
    private void drainOwedWrites() {
        while (writesOwed > 0 && writesInFlight < IN_FLIGHT_TRANSFERS) {
            writesOwed--;
            submitWrite();
        }
    }

    @Override
    public void transferFailed(boolean read, String detail) {
        // No auto-recovery (§5); a transfer cancelled during stop also lands here (benign).
        synchronized (stateLock) {
            if (!read && writesInFlight > 0) {
                writesInFlight--;               // a cancelled / failed write frees its in-flight slot
            }
        }
        if (log.isWarnEnabled()) {
            log.warn("QA40x {} transfer failed: {}", read ? "read" : "write", detail);
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
    }

    /** Sink for ADC samples on the capture lane. */
    @FunctionalInterface
    public interface CaptureConsumer {
        /**
         * Receives one ADC read: {@code length} bytes of {@code buffer},
         * interleaved little-endian int32 stereo (L,R) straight off the input
         * endpoint — NOT swapped, right NOT inverted (§9 item 6).  Consume
         * synchronously; the buffer is recycled after return.
         */
        void onAudio(byte[] buffer, int length);
    }

    /** Injected settle clock so the ABA rate-write delay (§8) is real in production yet instant in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }
}
