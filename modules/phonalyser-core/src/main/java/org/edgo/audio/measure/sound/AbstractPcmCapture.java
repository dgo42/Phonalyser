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
 * {@link #dispatch} — off the realtime thread — by duplicating the single
 * channel into both interleaved slots, so the rest of the pipeline (which reads
 * ch1) is unchanged.
 */
@Log4j2
public abstract class AbstractPcmCapture implements AudioCapture {

    protected final int sampleRate;
    protected final int bitDepth;
    protected final int sampleBytes;
    /** Stereo output frame size — the pipeline is always 2 channels downstream. */
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

    private StereoSample[] sampleBuf = new StereoSample[0];
    /** Consume-thread-only scratch for the mono→stereo upmix; null when stereo. */
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
        this.sampleBytes     = bitDepth / 8;
        this.frameSize       = sampleBytes * 2;
        this.captureChannels = captureChannels;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, 2,
                frameSize, sampleRate, false);
    }

    /**
     * Listener fan-out — call from the capture / consume thread only.  Buffers
     * may be recycled after this returns, so listeners must consume
     * synchronously.  {@code bytes} is the count of valid bytes in
     * {@code buffer}, which may itself be larger.
     */
    protected void dispatch(byte[] buffer, int bytes) {
        byte[] out = buffer;
        int outBytes = bytes;
        if (captureChannels == 1) {
            // Mono device → stereo: duplicate the single channel into both
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
            // Prefer the PCM-bytes listener — it skips the StereoSample[]
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

    /** Offset-binary decoder — unsigned 0..2^bits-1, midpoint-shifted. */
    @Override
    public int readSample(byte[] pcm, int offset) {
        switch (sampleBytes) {
            case 1:
                return (pcm[offset] + (byte) 0x80) & 0xFF;
            case 2:
                return ((short) ((pcm[offset + 1] & 0xFF) << 8 | (pcm[offset] & 0xFF)) + (short) 0x8000) & 0xFFFF;
            case 3:
                return ((((pcm[offset + 2]) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x800000) & 0xFFFFFF;
            case 4:
                return (((pcm[offset + 3] << 24)
                     | ((pcm[offset + 2] & 0xFF) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x80000000) & 0xFFFFFFFF;
            default:
                throw new IllegalStateException("Unsupported sampleBytes: " + sampleBytes);
        }
    }

    @Override
    public boolean isRecording() {
        return recording.get();
    }
}
