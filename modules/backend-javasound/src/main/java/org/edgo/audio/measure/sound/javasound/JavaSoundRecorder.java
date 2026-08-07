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

package org.edgo.audio.measure.sound.javasound;

import java.util.concurrent.locks.LockSupport;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM capture via {@code javax.sound.sampled.TargetDataLine} - the
 * cross-platform path used by the {@link AudioBackendType#JAVASOUND}
 * backend.  Mirrors {@link org.edgo.audio.measure.sound.wasapi.WasapiRecorder} / {@link org.edgo.audio.measure.sound.wdmks.WdmksRecorder}'s public
 * surface so the GUI scope view and CLI tools can drive it identically
 * through {@link AudioBackend}.
 */
@Log4j2
public class JavaSoundRecorder extends AbstractPcmCapture {

    private static final int BUFFER_FRAMES = 4096;
    /** How long the capture loop parks after a read that returned nothing -
     *  500 µs, the same pause the PortAudio recorders use on an empty ring: well
     *  under one audio period (10-20 ms), so it cannot cost a healthy capture a
     *  block, and enough to keep a provider that answers 0 for ever from burning
     *  a core on a MAX_PRIORITY thread. */
    private static final long EMPTY_READ_PARK_NANOS = 500_000L;
    private final JavaSoundDeviceManager.JavaSoundDeviceRef device;

    /** Puts this device's own volume controls at 0 dB as it is opened (Linux
     *  only; every other host answers immediately). */
    private final AlsaVolumes volumes;

    private TargetDataLine line;
    private Thread captureThread;

    public JavaSoundRecorder(JavaSoundDeviceManager.JavaSoundDeviceRef device, int sampleRate,
                             int bitDepth, AlsaVolumes volumes) {
        super(sampleRate, bitDepth);
        this.device = device;
        this.volumes = volumes;
    }

    @Override
    public void open() throws LineUnavailableException {
        Mixer mixer = AudioSystem.getMixer(device.mixerInfo());
        AudioFormat captureFmt = getFormat();   // stereo
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, captureFmt);
        if (!mixer.isLineSupported(info)) {
            // Mono fallback: many mics (and macOS inputs) are 1-channel.  Open
            // the line mono; AbstractPcmCapture#dispatch upmixes to the stereo
            // getFormat() the rest of the pipeline expects.
            AudioFormat mono = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, bitDepth, 1, sampleBytes, sampleRate, false);
            DataLine.Info monoInfo = new DataLine.Info(TargetDataLine.class, mono);
            if (!mixer.isLineSupported(monoInfo)) {
                throw new LineUnavailableException(
                        "Mixer '" + device.name() + "' does not support " + captureFmt + " or " + mono);
            }
            captureFmt      = mono;
            info            = monoInfo;
            captureChannels = 1;
        }
        line = (TargetDataLine) mixer.getLine(info);
        // Open with the provider's default buffer (csjsound: 500 ms) like
        // JavaSoundGenerator does.  Requesting an explicit small buffer
        // breaks csjsound's native capture ring (nGetBufferBytes=0, no
        // samples ever delivered, stop() wedges in native code) - and the
        // capture loop must read chunks SMALLER than the ring anyway.
        line.open(captureFmt);
        int captureFrameBytes = sampleBytes * captureChannels;
        int hwFrames = line.getBufferSize() / captureFrameBytes;
        log.info("JavaSound recorder opened : {}", device.name());
        log.info("Capture format             : {} ({})", captureFmt,
                captureChannels == 1 ? "mono upmixed to stereo" : "stereo");
        log.info("HW buffer                  : {} frames ({} ms)",
                hwFrames, hwFrames * 1000 / sampleRate);
        // The card's own capture gain is hardware gain INSIDE the calibrated
        // chain (plughw honours it), and a mixer left turned down scales every
        // measurement taken through it with nothing in the reading to say so -
        // a bench read 1 V as 34 mV that way.  Once per open, here, where the
        // device is known and the line is up.
        volumes.pinToUnity(device.name(), true);
    }

    @Override
    public void startRecording() {
        if (line == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        // Armed before the capture thread exists, so the write is safely
        // published to it.  This backend's ONLY workable loss signal: see
        // captureLoop's zero-read branch.
        armDeliveryDeadline();
        line.start();
        captureThread = new Thread(this::captureLoop, "javasound-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        log.info("JavaSound recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        TargetDataLine open = line;
        if (open != null) {
            open.stop();
        }
        if (captureThread != null) captureThread.join(2000);
        log.info("JavaSound recording stopped.");
    }

    private void captureLoop() {
        byte[] heap = new byte[BUFFER_FRAMES * sampleBytes * captureChannels];
        int frameBytes = sampleBytes * captureChannels;
        while (recording.get()) {
            int read;
            try {
                // PACED on the line's fill instead of blocking in it - the
                // capture twin of the generator's paced write (csjsound only):
                // only what available() says is ready is asked for, whole
                // frames, so the read
                // returns without entering a native wait.  csjsound's read
                // holds the line monitor across the blocking native call,
                // and a capture thread wedged in nRead on a dead device was
                // unreleasable (a thread dump shows it) exactly like the render
                // side.  Nothing ready funnels into the zero-read branch
                // below - the stall deadline and the park already live there.
                int ready = (line.available() / frameBytes) * frameBytes;
                int want  = Math.min(ready, heap.length);
                read = want > 0 ? line.read(heap, 0, want) : 0;
            } catch (Throwable t) {
                // A line whose device was pulled throws out of the provider's
                // native read - the one unambiguous signal this backend has.
                // Only reachable while recording, so it is never our own stop:
                // stopRecording() clears the flag before it touches the line.
                reportLost("TargetDataLine.read threw: " + t, t);
                return;
            }
            if (read < 0) {
                // End of stream.  TargetDataLine only reports this when the line
                // is finished for good, which for a capture line that nobody
                // stopped means the device behind it has gone.
                reportLost("TargetDataLine.read returned end-of-stream", null);
                return;
            }
            if (read == 0) {
                // A zero on its own is still NOT a loss: a provider with nothing
                // ready right now looks exactly like one whose device has gone.
                //
                // But zeroes FOR EVER are, and that is what a pulled USB device
                // really produces here - the bench pulled the card twice and got
                // no throw and no end-of-stream either time, just a capture that
                // went on returning nothing while the scope kept drawing and
                // neither the generator nor the scope reacted at all.  So the
                // deadline is what tells the two apart: a started capture line
                // owes a block every audio period, and
                // silence is delivered as blocks of zeroes, so "nothing at all
                // for two seconds" is not a quiet input.
                if (deliveryStalled()) {
                    return;
                }
                // Not stalled yet - park instead of spinning: this is a
                // MAX_PRIORITY thread and a provider answering 0 would otherwise
                // burn a whole core.
                LockSupport.parkNanos(EMPTY_READ_PARK_NANOS);
                continue;
            }
            dispatch(heap, read);
        }
    }

    /** Says the capture is over, once, and stops the loop.  {@code recording} is
     *  cleared first so {@code close()} does not then try to stop a line whose
     *  device is no longer there. */
    private void reportLost(String detail, Throwable cause) {
        recording.set(false);
        if (cause == null) {
            log.error("JavaSound capture on {} ended: {}", device.name(), detail);
        } else {
            log.error("JavaSound capture on {} ended: {}", device.name(), detail, cause);
        }
        endCapture(CaptureEndReason.DEVICE_LOST, "JavaSound: " + detail);
    }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        TargetDataLine open = line;
        line = null;
        if (open != null) {
            open.close();
        }
        // The line is no longer ours: put the mixer back as the open found it.
        volumes.restore(device.name(), true);
    }
}
