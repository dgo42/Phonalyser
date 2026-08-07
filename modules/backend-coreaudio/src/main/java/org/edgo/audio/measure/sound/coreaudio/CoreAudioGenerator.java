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

package org.edgo.audio.measure.sound.coreaudio;

import org.edgo.audio.measure.sound.AbstractPortAudioPlayback;
import org.edgo.audio.measure.sound.PortAudio;

import com.sun.jna.Pointer;

import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM playback via PortAudio's CoreAudio host API (macOS, callback
 * mode).  All behaviour lives in {@link AbstractPortAudioPlayback}; this
 * subclass adapts the {@link CoreAudioDeviceManager.CoreAudioDeviceRef} device
 * and pins the device's own volume where a measurement chain needs it.
 */
@Log4j2
public class CoreAudioGenerator extends AbstractPortAudioPlayback {

    /** By the name the HAL publishes it under - which is what a volume control
     *  is reached through, the PortAudio index being PortAudio's own. */
    private final String deviceName;
    /** The HAL, injected by the manager that owns it: the volume pin below is
     *  the one thing this lane changes about the machine. */
    private final CoreAudioHal hal;

    public CoreAudioGenerator(CoreAudioDeviceManager.CoreAudioDeviceRef device, int sampleRate,
            int bitDepth, double ditherBits, CoreAudioHal hal) {
        super(device.paDeviceIndex(), device.name(), "CoreAudio", sampleRate, bitDepth, ditherBits);
        this.deviceName = device.name();
        this.hal = hal;
    }

    /**
     * Opens the line and pins the device's output volume to its 0 dB - a mixer
     * control anywhere else scales every volt this generator was calibrated to
     * emit, and nothing downstream can see that it did (see
     * {@link CoreAudioHal#pinVolumeToUnity}).
     *
     * <p>AFTER the open, never before: an open that failed has claimed nothing
     * and must leave the machine exactly as it found it.
     */
    @Override
    public void open() {
        super.open();
        hal.pinVolumeToUnity(deviceName, true);
    }

    @Override
    public void close() {
        // Halted BEFORE the close: a Pa_CloseStream on a still-running (or
        // vanished-device) CoreAudio stream can block for ever, and a close
        // that is abandoned leaves the stream open and counted - vetoing every
        // device-list rebuild until the process dies.
        haltStream();
        super.close();
        // The line is no longer ours: put the volume back as the open found it.
        hal.restoreVolume(deviceName, true);
    }

    /** {@inheritDoc}  {@code Pa_AbortStream}, not {@code Pa_StopStream}: on
     *  CoreAudio the stop blocks on a semaphore the host API does not reliably
     *  signal - and on a device that left the machine it never signals at all.
     *  Abort halts the IO proc immediately; a tone has nothing to drain.  The
     *  same discipline the capture side has always used, for the same reason. */
    @Override
    protected void haltStream() {
        Pointer open = stream();
        if (open == null) {
            return;
        }
        try {
            PortAudio.lib().Pa_AbortStream(open);
        } catch (Throwable t) {
            log.warn("Pa_AbortStream: {}", t.getMessage());
        }
    }

    /** {@inheritDoc}  Asked of the live HAL: macOS keeps the PortAudio stream
     *  nominally active after the device is pulled - aggregate devices
     *  included - so the stream itself can never report the loss, and a tone
     *  nobody hears would play on while its open stream blocks every
     *  device-list rebuild. */
    @Override
    protected boolean deviceStillPresent() {
        return hal.devicePresent(deviceName, true);
    }
}
