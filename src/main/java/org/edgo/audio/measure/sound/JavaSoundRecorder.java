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

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.common.Closeables;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Stereo PCM capture via {@code javax.sound.sampled.TargetDataLine} — the
 * cross-platform path used by the {@link AudioBackendType#JAVASOUND}
 * backend.  Mirrors {@link WasapiRecorder} / {@link WdmksRecorder}'s public
 * surface so the GUI scope view and CLI tools can drive it identically
 * through {@link AudioBackend}.
 */
@Log4j2
public class JavaSoundRecorder extends AbstractPcmCapture {

    private static final int BUFFER_FRAMES = 4096;

    private final JavaSoundDeviceManager.JavaSoundDeviceRef device;

    private TargetDataLine line;
    private Thread captureThread;

    public JavaSoundRecorder(JavaSoundDeviceManager.JavaSoundDeviceRef device,
                             int sampleRate, int bitDepth) {
        super(sampleRate, bitDepth);
        this.device = device;
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
        // samples ever delivered, stop() wedges in native code) — and the
        // capture loop must read chunks SMALLER than the ring anyway.
        line.open(captureFmt);
        int captureFrameBytes = sampleBytes * captureChannels;
        int hwFrames = line.getBufferSize() / captureFrameBytes;
        log.info("JavaSound recorder opened : {}", device.name());
        log.info("Capture format             : {} ({})", captureFmt,
                captureChannels == 1 ? "mono upmixed to stereo" : "stereo");
        log.info("HW buffer                  : {} frames ({} ms)",
                hwFrames, hwFrames * 1000 / sampleRate);
    }

    @Override
    public void startRecording() {
        if (line == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
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
        if (line != null) {
            try {
                line.stop();
            } catch (Throwable t) {
                log.warn("TargetDataLine.stop threw: {}", t.getMessage());
            }
        }
        if (captureThread != null) captureThread.join(2000);
        log.info("JavaSound recording stopped.");
    }

    private void captureLoop() {
        byte[] heap = new byte[BUFFER_FRAMES * sampleBytes * captureChannels];
        while (recording.get()) {
            int read = line.read(heap, 0, heap.length);
            if (read <= 0) continue;
            dispatch(heap, read);
        }
    }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Closeables.closeQuietly(line);
        line = null;
    }
}
