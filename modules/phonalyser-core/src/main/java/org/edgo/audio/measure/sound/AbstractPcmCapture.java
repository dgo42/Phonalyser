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

package org.edgo.audio.measure.sound;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.StereoSample;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Shared base for the stereo PCM capture backends ({@link WdmksRecorder},
 * {@link WasapiRecorder}, {@link JavaSoundRecorder}, {@link CoreAudioRecorder}).
 *
 * <p>Owns the device-agnostic parts every recorder used to duplicate: the
 * captured {@link AudioFormat}, the {@code recording} flag, the offset-binary
 * {@link #readSample} decoder, and the listener fan-out in {@link #dispatch}.
 * Subclasses supply the device-specific stream lifecycle (open / start / stop /
 * close) and feed captured bytes to {@link #dispatch} from their own capture or
 * consume thread.
 *
 * <p>A mono device ({@code captureChannels == 1}) is upmixed to stereo inside
 * {@link #dispatch} - off the realtime thread - by duplicating the single
 * channel into both interleaved slots, so the rest of the pipeline (which reads
 * ch1) is unchanged.
 */
@Log4j2
public abstract class AbstractPcmCapture implements AudioCapture {

    protected final int sampleRate;
    protected final int bitDepth;
    protected final int sampleBytes;
    /** Stereo output frame size - the pipeline is always 2 channels downstream. */
    protected final int frameSize;
    /** Channels actually captured from the device: 1 (mono, upmixed in
     *  {@link #dispatch}) or 2 (stereo).  Set by the subclass constructor;
     *  backends that only learn the count when the stream opens (JavaSound)
     *  may update it in {@code open()}, before {@code startRecording()} starts
     *  the consume thread (so the write is safely published to it). */
    protected int captureChannels;
    @Getter
    private final AudioFormat format;
    protected final AtomicBoolean recording = new AtomicBoolean(false);

    @Setter
    private Consumer<StereoSample[]> sampleListener;
    @Setter
    private PcmBatchListener pcmBatchListener;
    @Setter
    private Consumer<byte[]> rawBytesListener;
    /** Whether {@link #endCapture} has already spoken - the end is said once per
     *  open, however many times a dying device makes the backend notice. */
    private final AtomicBoolean ended = new AtomicBoolean();

    /** How long a capture that is SUPPOSED to be delivering may deliver nothing
     *  before it is declared lost.  Two seconds: the longest buffer any of these
     *  backends opens is the csjsound provider's 500 ms default, PortAudio's
     *  WDM-KS periods are 10-200 ms and WASAPI exclusive is ~50 ms, so this is at
     *  least four times the worst LEGITIMATE gap between two blocks - and it is
     *  far below the time an operator would spend reading a trace that had
     *  quietly stopped being true. */
    private static final long DELIVERY_DEADLINE_NANOS = 2_000_000_000L;

    /** Whether this capture is watching its own delivery - see
     *  {@link #armDeliveryDeadline()}.  Opt-in: a backend whose device tells it
     *  the lane died (the QA40x engine, the net bridge's session) has a better
     *  answer than a clock and is left exactly as it was. */
    private volatile boolean deliveryWatched;
    /** When the last block reached {@link #dispatch}, or when the deadline was
     *  armed if none has yet.  Written by the consume thread, read by whichever
     *  thread runs the check. */
    private volatile long lastDeliveryNanos;

    private StereoSample[] sampleBuf = new StereoSample[0];
    /** Consume-thread-only scratch for the mono->stereo upmix; null when stereo. */
    private byte[] monoUpmix;
    /** Logged once when a consumer listener faults, so a per-block failure logs
     *  a single stack trace instead of flooding the log. */
    private final AtomicBoolean consumerFaultLogged = new AtomicBoolean();

    protected AbstractPcmCapture(int sampleRate, int bitDepth) {
        this(sampleRate, bitDepth, 2);
    }

    protected AbstractPcmCapture(int sampleRate, int bitDepth, int captureChannels) {
        this.sampleRate      = sampleRate;
        this.bitDepth        = bitDepth;
        // Rounded UP: a depth that is not a whole number of bytes arrives
        // right-aligned in the next larger container (20 bits in 3 bytes).
        // Exact division for 16 / 24 / 32.
        this.sampleBytes     = (bitDepth + 7) / 8;
        this.frameSize       = sampleBytes * 2;
        this.captureChannels = captureChannels;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, 2,
                frameSize, sampleRate, false);
    }

    /**
     * Ends this capture for good - the device was lost or its stream failed
     * unrecoverably.  A backend that can tell MUST call this instead of simply
     * falling silent: a listener that is never called again is indistinguishable
     * from a quiet input, and everything above would go on believing it is
     * measuring.
     *
     * <p>The end travels the seam the data travelled -
     * {@link PcmBatchListener#captureEnded} on the same listener that received
     * every block - so it climbs the layers the way the samples did (capture ->
     * ring buffer -> reader -> worker -> UI) and never sideways.  Said once per
     * open however many times a dying device makes the backend notice, and it
     * never throws back at the caller, which is usually a driver callback
     * thread.
     *
     * @param reason    what the layers above are handed - machine-readable,
     *                  localized by the GUI, never operator prose from here
     * @param logDetail the backend's own words for the LOG only (an HRESULT,
     *                  a millisecond count, a driver message); may be null
     */
    protected void endCapture(CaptureEndReason reason, String logDetail) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        recording.set(false);
        if (log.isErrorEnabled()) {
            log.error("Capture is over: {} ({})", reason.logText(),
                    logDetail == null ? reason.name() : logDetail);
        }
        PcmBatchListener listener = pcmBatchListener;
        if (listener == null) {
            return;
        }
        try {
            listener.captureEnded(reason);
        } catch (Throwable t) {
            // Thread boundary - this is a driver/consume thread, and a consumer
            // that faults must not take the backend down with it.
            log.error("captureEnded consumer failed for {}", reason, t);
        }
    }

    /**
     * Starts watching this capture's own delivery, for the backends whose device
     * does not tell them anything when it goes.
     *
     * <p>Call from {@code startRecording()} BEFORE the reading / consume thread
     * is started, so the write is safely published to it.
     *
     * <p><b>Why a clock is a legitimate answer here.</b>  This is not a general
     * "has anything happened lately" watchdog over the application; it is asked
     * only of a stream that has been STARTED and is therefore contracted to
     * deliver a block every audio period.  A capture line does not go quiet when
     * the input goes quiet - silence is blocks of zeroes - so "nothing at all,
     * for two seconds" is not a quiet input, it is a stream that has stopped.
     * That distinction is the whole reason the check exists: the backends that
     * need it have no other signal at all.  WDM-KS leaves the stream nominally
     * active after the device is pulled, and a capture line whose USB device has
     * gone answers 0 for ever without ever throwing - observed on the bench as
     * a generator and scope that simply keep running after the unplug.
     */
    protected final void armDeliveryDeadline() {
        lastDeliveryNanos = System.nanoTime();
        deliveryWatched   = true;
    }

    /**
     * Whether this capture has stopped delivering, and telling everything above
     * if it has.  Ask from the loop that would otherwise wait for the next block
     * - the empty-ring park, the read that returned nothing.
     *
     * <p><b>Wrong only in the safe direction.</b>  Three things must hold: the
     * deadline was armed (a backend that never opts in is never suspected), a
     * block has been due for longer than the deadline, and {@code recording} is
     * STILL set - every {@code stopRecording()} clears that flag before it
     * touches the device, so a stop of OUR own can never be read as a loss.
     *
     * @return true when the loss has been reported and the caller's loop must end
     */
    protected final boolean deliveryStalled() {
        return deliveryStalled(deliveryDeadlineNanos());
    }

    /** The deadline {@link #deliveryStalled()} judges by.  Overridable because
     *  ONE legitimate gap can be longer than any running stream's: the first
     *  block after a start, on a device that takes seconds to assemble its IO
     *  before it speaks at all - macOS aggregate devices are such.  Every
     *  backend that does not say otherwise keeps the standard deadline. */
    protected long deliveryDeadlineNanos() {
        return DELIVERY_DEADLINE_NANOS;
    }

    /** {@link #deliveryStalled()} against an explicit deadline - the seam the
     *  test drives, so the contract can be pinned without spending the real two
     *  seconds of wall clock. */
    boolean deliveryStalled(long deadlineNanos) {
        if (!deliveryWatched || !recording.get()) {
            return false;
        }
        long silentNanos = System.nanoTime() - lastDeliveryNanos;
        if (silentNanos < deadlineNanos) {
            return false;
        }
        // The millisecond count is for the LOG alone - the operator learns WHAT
        // happened (localized from the enum), not our bookkeeping numbers.
        endCapture(CaptureEndReason.DELIVERY_STALLED,
                "no delivery for " + (silentNanos / 1_000_000L) + " ms");
        return true;
    }

    /**
     * Listener fan-out - call from the capture / consume thread only.  Buffers
     * may be recycled after this returns, so listeners must consume
     * synchronously.  {@code bytes} is the count of valid bytes in
     * {@code buffer}, which may itself be larger.
     */
    protected void dispatch(byte[] buffer, int bytes) {
        // The one place a delivered block passes through, so the one place the
        // delivery deadline can be re-armed from.
        lastDeliveryNanos = System.nanoTime();
        byte[] out = buffer;
        int outBytes = bytes;
        if (captureChannels == 1) {
            // Mono device -> stereo: duplicate the single channel into both
            // interleaved slots, off the realtime thread.
            int frames = bytes / sampleBytes;
            int stereoBytes = frames * frameSize;
            if (monoUpmix == null || monoUpmix.length != stereoBytes) {
                monoUpmix = new byte[stereoBytes];
            }
            for (int f = 0; f < frames; f++) {
                int src = f * sampleBytes;
                int dst = f * frameSize;
                System.arraycopy(buffer, src, monoUpmix, dst, sampleBytes);
                System.arraycopy(buffer, src, monoUpmix, dst + sampleBytes, sampleBytes);
            }
            out = monoUpmix;
            outBytes = stereoBytes;
        }
        try {
            if (rawBytesListener != null) {
                rawBytesListener.accept(outBytes == out.length ? out : Arrays.copyOf(out, outBytes));
            }
            // Prefer the PCM-bytes listener - it skips the StereoSample[]
            // alloc/decode entirely (the consumer decodes into reusable float
            // buffers).  Only one of the two is set in any real configuration;
            // if both are, the PCM listener wins (the GUI scope view).
            if (pcmBatchListener != null) {
                pcmBatchListener.accept(out, outBytes);
            } else if (sampleListener != null) {
                int nFrames = outBytes / frameSize;
                if (sampleBuf.length != nFrames) {
                    sampleBuf = new StereoSample[nFrames];
                    for (int i = 0; i < nFrames; i++) sampleBuf[i] = new StereoSample();
                }
                for (int f = 0; f < nFrames; f++) {
                    int offset = f * frameSize;
                    sampleBuf[f].ch0 = readSample(out, offset);
                    sampleBuf[f].ch1 = readSample(out, offset + sampleBytes);
                }
                sampleListener.accept(sampleBuf);
            }
        } catch (Throwable th) {
            // A listener throwing must not kill the capture/consume thread,
            // which would freeze every capture-driven view.  Log once, continue.
            if (consumerFaultLogged.compareAndSet(false, true)) {
                log.error("Capture consumer listener failed (continuing): {}", th.toString(), th);
            }
        }
    }

    /** Offset-binary decoder - unsigned 0..2^bits-1, midpoint-shifted.  The
     *  midpoint and mask come from {@link #bitDepth}, NOT from the container
     *  size: a depth narrower than its container (20 valid bits in 3 bytes)
     *  would otherwise be shifted by the container's midpoint and read as
     *  full-scale DC downstream, where the consumers derive both from the bit
     *  depth.  For 8 / 16 / 24 / 32, where depth == 8*container, these
     *  expressions are the constants they replace. */
    @Override
    public int readSample(byte[] pcm, int offset) {
        long midpoint = 1L << (bitDepth - 1);
        long mask     = (1L << bitDepth) - 1;
        long raw;
        switch (sampleBytes) {
            case 1:
                raw = pcm[offset];
                break;
            case 2:
                raw = (short) ((pcm[offset + 1] & 0xFF) << 8 | (pcm[offset] & 0xFF));
                break;
            case 3:
                raw = (((pcm[offset + 2]) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF));
                break;
            case 4:
                raw = ((pcm[offset + 3] << 24)
                     | ((pcm[offset + 2] & 0xFF) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF));
                break;
            default:
                throw new IllegalStateException("Unsupported sampleBytes: " + sampleBytes);
        }
        return (int) ((raw + midpoint) & mask);
    }

    @Override
    public boolean isRecording() {
        return recording.get();
    }
}
