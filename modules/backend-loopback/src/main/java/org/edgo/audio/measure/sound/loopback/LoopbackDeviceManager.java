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

package org.edgo.audio.measure.sound.loopback;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * Manager for the digital loopback backend: one device, listed for both
 * directions, and the crossing the two lanes meet on.
 *
 * <p>It owns that crossing because both lanes are created here - a playback and
 * a capture opened from this manager are the two ends of the same loop.  The
 * formats are the standard rate ladder at the four depths the encoder supports;
 * nothing is probed, since there is no hardware to ask.
 */
public final class LoopbackDeviceManager implements AudioDeviceManager {

    /** The standard sample-rate ladder, 8 k to 768 k.  Both directions offer
     *  the same list - the loop has ONE clock, so an input and an output rate
     *  that differ cannot exist here. */
    private static final int[] SAMPLE_RATES = {
        8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000,
        176400, 192000, 352800, 384000, 705600, 768000
    };
    /** 20 bits is a real card format (right-aligned in a 3-byte container),
     *  not a rounding of 24. */
    private static final int[] BIT_DEPTHS = { 16, 20, 24, 32 };
    private static final int CHANNELS = 2;

    private final LoopbackDeviceRef device = new LoopbackDeviceRef();
    private final LoopbackCrossing crossing = new LoopbackCrossing();

    @Override
    public List<DeviceRef> listInputDevices() {
        return List.of(device);
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return List.of(device);
    }

    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        if (index != 0) {
            throw new IllegalArgumentException("No loopback device at index " + index);
        }
        return device;
    }

    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef ref, boolean output) {
        if (!(ref instanceof LoopbackDeviceRef)) {
            return List.of();
        }
        List<AudioFormat> formats = new ArrayList<>(SAMPLE_RATES.length * BIT_DEPTHS.length);
        for (int rate : SAMPLE_RATES) {
            for (int depth : BIT_DEPTHS) {
                int frameSize = ((depth + 7) / 8) * CHANNELS;
                formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        rate, depth, CHANNELS, frameSize, rate, false));
            }
        }
        return formats;
    }

    @Override
    public AudioCapture openCapture(DeviceRef ref, int sampleRate, int bitDepth) {
        return new LoopbackCapture(sampleRate, bitDepth, crossing);
    }

    /** {@code ditherBits} is accepted to satisfy the contract and deliberately
     *  not passed on - see {@link LoopbackPlayback} for why this backend always
     *  dithers at its own selected depth. */
    @Override
    public AudioPlayback openPlayback(DeviceRef ref, int sampleRate, int bitDepth, double ditherBits) {
        return new LoopbackPlayback(sampleRate, bitDepth, crossing);
    }
}
