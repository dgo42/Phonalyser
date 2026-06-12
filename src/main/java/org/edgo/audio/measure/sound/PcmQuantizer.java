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

import java.util.SplittableRandom;

import lombok.Getter;
import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Quantizes a {@link SignalGenerator}'s continuous-domain samples (double,
 * −1…+1) to interleaved-stereo signed little-endian PCM, with optional TPDF
 * dither applied immediately before the rounding step — the only place dither
 * is meaningful, since its ±1 LSB amplitude is defined by the <em>target</em>
 * bit depth.  Owned by the playback backends ({@link JavaSoundGenerator},
 * {@link WasapiGenerator}, {@link WdmksGenerator}), which all emit the same
 * encoding; the synthesis layer stays dither-free so exports and the
 * deconvolution reference X(t) remain the ideal waveform.
 *
 * <h2>Threading</h2>
 * <p>{@link #encode} is called from a single render thread at a time (the
 * backend's play thread or PortAudio's callback thread) and allocates
 * nothing.  {@link #setDitherBits} is the one live-tunable: volatile, read
 * once per sample.
 */
public final class PcmQuantizer {

    private static final int CHANNELS = 2;

    @Getter
    private final int bitDepth;
    private final int bytesPerSample;
    private final int bytesPerFrame;
    /** TPDF dither depth in bits; 0 = off.  Live-tunable from the UI. */
    @Getter
    private volatile int ditherBits;
    /** {@link SplittableRandom}, not {@code Random}: render-thread-confined,
     *  and Random's CAS-looped nextDouble() costs ~2 CAS per call at up to
     *  1.5 M calls/s with dither on. */
    private final SplittableRandom rng = new SplittableRandom();

    public PcmQuantizer(int bitDepth, int ditherBits) {
        this.bitDepth       = bitDepth;
        this.ditherBits     = Math.max(0, ditherBits);
        this.bytesPerSample = bitDepth / 8;
        this.bytesPerFrame  = bytesPerSample * CHANNELS;
    }

    /** Live-applies the dither bit count (clamped to ≥ 0). */
    public void setDitherBits(int bits) {
        this.ditherBits = Math.max(0, bits);
    }

    /**
     * Pulls {@code frames} samples from {@code gen} and encodes them as
     * stereo (same signal both channels) signed little-endian PCM into
     * {@code buf}.  No allocation — safe on the audio hot path.
     */
    public void encode(SignalGenerator gen, byte[] buf, int frames) {
        if (bitDepth == 8) {
            // Signed 8-bit PCM [−128, +127] — all three backends open their
            // lines/streams in signed formats.
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                byte   val    = (byte) Math.round(sample * 127.0);
                int    offset = i * bytesPerFrame;
                buf[offset]     = val; // left
                buf[offset + 1] = val; // right
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte byteVal = (byte) (pcm >> (8 * b));
                    buf[offset + b]                  = byteVal; // left
                    buf[offset + bytesPerSample + b] = byteVal; // right
                }
            }
        }
    }

    private double tpdfNoise() {
        int bits = ditherBits;   // single read — a live change can't shift by (0 − 1)
        if (bits == 0) return 0.0;
        return (rng.nextDouble() - rng.nextDouble()) / (1L << (bits - 1));
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
