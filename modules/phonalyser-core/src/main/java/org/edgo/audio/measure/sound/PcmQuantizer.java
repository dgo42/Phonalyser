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
 * Quantizes a {@link SignalGenerator}'s continuous-domain samples (double,
 * −1...+1) to interleaved-stereo signed little-endian PCM, with optional TPDF
 * dither applied immediately before the rounding step - the only place dither
 * is meaningful, since its ±1 LSB amplitude is defined by the <em>target</em>
 * bit depth.  Owned by the playback backends ({@link JavaSoundGenerator},
 * {@link WasapiGenerator}, {@link WdmksGenerator}), which all emit the same
 * encoding; the synthesis layer stays dither-free so exports and the
 * deconvolution reference X(t) remain the ideal waveform.
 *
 * <h2>Per-channel output</h2>
 * <p>This is the sole stereo-interleave seam for the live path, so it also
 * owns the per-lane full-scale scale ({@link #setChannelScale}) and the
 * Left/Right/Both output gate ({@link #setOutputChannels}).  A LINKED stereo
 * card whose two DAC full-scales differ needs the same tone emitted at a
 * different digital level per lane; the mono {@link SignalGenerator} cannot do
 * that, so the right lane is multiplied by {@code fsLeft/fsRight} here.  The
 * gate silences the un-selected lane(s).  With both scales at {@code 1.0} and
 * the gate at {@link OutputChannels#BOTH} the output is byte-identical to the
 * pre-per-channel encoder.
 *
 * <h2>Threading</h2>
 * <p>{@link #encode} is called from a single render thread at a time (the
 * backend's play thread or PortAudio's callback thread) and allocates
 * nothing.  {@link #setDitherBits}, {@link #setChannelScale} and
 * {@link #setOutputChannels} are the live-tunables: volatile.  The dither
 * bits are read once per sample; the scale/gate once per {@link #encode}
 * call, so a live change lands on the next audio block.
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
    /** Per-lane full-scale scale factors: left is normally {@code 1.0}, right is
     *  {@code fsLeft/fsRight} so a LINKED card with distinct DAC full-scales
     *  emits the same physical level on both lanes; both {@code 1.0} when the
     *  full-scales are equal or the card is mono.  Live-tunable. */
    private volatile double scaleL = 1.0;
    private volatile double scaleR = 1.0;
    /** Output-lane gate; the un-selected lane(s) are written as digital silence.
     *  {@link OutputChannels#BOTH} is the default / pre-feature behaviour. */
    @Setter
    private volatile OutputChannels outputChannels = OutputChannels.BOTH;
    /** {@link SplittableRandom}, not {@code Random}: render-thread-confined,
     *  and Random's CAS-looped nextDouble() costs ~2 CAS per call at up to
     *  1.5 M calls/s with dither on. */
    private final SplittableRandom rng = new SplittableRandom();

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

    /** Live-applies the per-lane full-scale scale factors (left, right). */
    public void setChannelScale(double left, double right) {
        this.scaleL = left;
        this.scaleR = right;
    }

    /**
     * Pulls {@code frames} samples from {@code gen} and encodes them as
     * stereo signed little-endian PCM into {@code buf}, honouring the per-lane
     * scale and the output gate.  No allocation - safe on the audio hot path.
     * With both scales {@code 1.0} and the gate {@link OutputChannels#BOTH}
     * (the default) both lanes get the identical quantised sample - byte-for-byte
     * the pre-per-channel encoding.  Silence for a gated-off lane is mid-code 0
     * (signed PCM).
     */
    public void encode(SignalGenerator gen, byte[] buf, int frames) {
        // Hoist the volatile scale/gate once per block - a live change lands on
        // the next call.  ditherBits stays a per-sample read inside tpdfNoise().
        OutputChannels gate  = outputChannels;
        double         sl    = scaleL;
        double         sr    = scaleR;
        boolean        wantL = gate != OutputChannels.RIGHT;
        boolean        wantR = gate != OutputChannels.LEFT;
        if (bitDepth == 8) {
            // Signed 8-bit PCM [−128, +127] - all three backends open their
            // lines/streams in signed formats.
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                int    offset = i * bytesPerFrame;
                buf[offset]     = wantL ? (byte) Math.round(clamp(sample * sl) * 127.0) : 0; // left
                buf[offset + 1] = wantR ? (byte) Math.round(clamp(sample * sr) * 127.0) : 0; // right
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcmL   = wantL ? (long) Math.round(clamp(sample * sl) * maxVal) : 0L;
                long   pcmR   = wantR ? (long) Math.round(clamp(sample * sr) * maxVal) : 0L;
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
