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

import java.util.SplittableRandom;

import lombok.Getter;
import lombok.Setter;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Quantizes a {@link SignalGenerator}'s continuous-domain frames (double,
 * −1...+1 per lane) to interleaved-stereo signed little-endian PCM, with
 * optional TPDF dither applied immediately before the rounding step - the only
 * place dither is meaningful, since its ±1 LSB amplitude is defined by the
 * <em>target</em> bit depth.  Owned by the playback backends
 * ({@link JavaSoundGenerator}, {@link WasapiGenerator}, {@link WdmksGenerator}),
 * which all emit the same encoding; the synthesis layer stays dither-free so
 * exports and the deconvolution reference X(t) remain the ideal waveform.
 *
 * <h2>Per-channel output</h2>
 * <p>Each lane arrives at its OWN level from the generator
 * ({@link SignalGenerator#nextFrame}): the generator scales the one waveform by
 * each lane's amplitude over that lane's DAC full scale, so nothing here relates
 * one lane to the other.  This is the stereo-interleave seam for the live path,
 * and it owns the Left/Right/Both output gate ({@link #setOutputChannels}),
 * which silences the un-selected lane(s).  Dither is drawn per lane, so the two
 * lanes' dither is uncorrelated.
 *
 * <h2>Threading</h2>
 * <p>{@link #encode} is called from a single render thread at a time (the
 * backend's play thread or PortAudio's callback thread) and allocates
 * nothing.  {@link #setDitherBits} and {@link #setOutputChannels} are the
 * live-tunables: volatile.  The dither bits are read once per sample; the
 * gate once per {@link #encode} call, so a live change lands on the next
 * audio block.
 */
public final class PcmQuantizer {

    private static final int CHANNELS = 2;

    @Getter
    private final int bitDepth;
    private final int bytesPerSample;
    private final int bytesPerFrame;
    /** TPDF dither depth in bits (may be fractional); 0 = off.  Live-tunable
     *  from the UI. */
    @Getter
    private volatile double ditherBits;
    /** Output-lane gate; the un-selected lane(s) are written as digital silence.
     *  {@link OutputChannels#BOTH} is the default / pre-feature behaviour. */
    @Setter
    private volatile OutputChannels outputChannels = OutputChannels.BOTH;
    /** {@link SplittableRandom}, not {@code Random}: render-thread-confined,
     *  and Random's CAS-looped nextDouble() costs ~2 CAS per call at up to
     *  1.5 M calls/s with dither on. */
    private final SplittableRandom rng = new SplittableRandom();
    /** The frame the generator fills - render-thread-confined, reused so the
     *  hot path allocates nothing. */
    private final double[] frame = new double[CHANNELS];

    public PcmQuantizer(int bitDepth, double ditherBits) {
        this.bitDepth       = bitDepth;
        this.ditherBits     = Math.max(0.0, ditherBits);
        // Rounded UP: a depth that is not a whole number of bytes rides
        // right-aligned in the next larger container (20 bits in 3 bytes,
        // S20_3LE-style).  Exact division for 16 / 24 / 32.
        this.bytesPerSample = (bitDepth + 7) / 8;
        this.bytesPerFrame  = bytesPerSample * CHANNELS;
    }

    /** Live-applies the dither bit count (may be fractional; clamped to ≥ 0). */
    public void setDitherBits(double bits) {
        this.ditherBits = Math.max(0.0, bits);
    }

    /**
     * Pulls {@code frames} frames from {@code gen} and encodes them as stereo
     * signed little-endian PCM into {@code buf}, honouring the output gate.  No
     * allocation - safe on the audio hot path.  Silence for a gated-off lane is
     * mid-code 0 (signed PCM).
     */
    public void encode(SignalGenerator gen, byte[] buf, int frames) {
        // Hoist the volatile gate once per block - a live change lands on the
        // next call.  ditherBits stays a per-sample read inside tpdfNoise().
        OutputChannels gate  = outputChannels;
        boolean        wantL = gate != OutputChannels.RIGHT;
        boolean        wantR = gate != OutputChannels.LEFT;
        double[]       lanes = frame;
        if (bitDepth == 8) {
            // Signed 8-bit PCM [−128, +127] - all three backends open their
            // lines/streams in signed formats.
            for (int i = 0; i < frames; i++) {
                gen.nextFrame(lanes);
                double left   = clamp(lanes[0] + tpdfNoise());
                double right  = clamp(lanes[1] + tpdfNoise());
                int    offset = i * bytesPerFrame;
                buf[offset]     = wantL ? (byte) Math.round(left * 127.0) : 0;  // left
                buf[offset + 1] = wantR ? (byte) Math.round(right * 127.0) : 0; // right
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                gen.nextFrame(lanes);
                double left   = clamp(lanes[0] + tpdfNoise());
                double right  = clamp(lanes[1] + tpdfNoise());
                long   pcmL   = wantL ? (long) Math.round(left * maxVal) : 0L;
                long   pcmR   = wantR ? (long) Math.round(right * maxVal) : 0L;
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    buf[offset + b]                  = (byte) (pcmL >> (8 * b)); // left
                    buf[offset + bytesPerSample + b] = (byte) (pcmR >> (8 * b)); // right
                }
            }
        }
    }

    private double tpdfNoise() {
        double bits = ditherBits;   // single read - a live change can't shift by (0 − 1)
        if (bits <= 0.0) return 0.0;
        // Math.pow(2, bits−1) equals the old 1L<<(bits−1) for whole bits, and
        // interpolates the ±1 LSB amplitude continuously for a fractional depth.
        return (rng.nextDouble() - rng.nextDouble()) / Math.pow(2.0, bits - 1);
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
