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

package org.edgo.audio.measure.gui.scope;

import java.io.File;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.wav.PcmFileLoader;

/**
 * Loads a WAV / FLAC / AIFF file synchronously into a fresh
 * {@link SignalBufferReader} sized to the file's exact sample count, and
 * attaches it to the scope's main and condensed views.  No threads,
 * no measurement-worker coordination, no per-tick scheduling — just
 * decode, populate, hand to the views.
 *
 * <p>Replaces the old {@code ScopePlayController}'s live-streaming
 * "play" model, which had inherent race conditions when switching
 * between record and file modes (the play thread and the record-side
 * measurement thread could overlap and starve each other).  Loading
 * the entire signal up front lets the scope render it as a static
 * waveform that the user can scroll and zoom through.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Caller stops the live recording controller (if running).</li>
 *   <li>{@link #loadFile} is called on the SWT thread.</li>
 *   <li>The file is decoded fully; a buffer of exactly the file's
 *       frame count is allocated and populated.</li>
 *   <li>{@link ScopeView#setBuffer} and
 *       {@link ZoomedView#setBuffer} are invoked.</li>
 *   <li>{@link #clear} reverses the attachment and forgets the file
 *       so subsequent record sessions get a fresh buffer.</li>
 * </ol>
 */
@Log4j2
public final class ScopeOpenSignal {

    private final ScopeView mainView;
    private final ZoomedView    condensedView;

    @Getter
    private File          loadedFile;
    @Getter
    private String        lastError;

    public ScopeOpenSignal(ScopeView mainView, ZoomedView condensedView) {
        this.mainView      = mainView;
        this.condensedView = condensedView;
    }

    public boolean isLoaded()     { return loadedFile != null; }

    /**
     * Decodes {@code file} entirely into a new {@link SignalBufferReader} and
     * attaches it to both views.  Returns {@code true} on success;
     * inspect {@link #getLastError} when {@code false}.  Must be called
     * on the SWT thread.
     */
    public boolean loadFile(File file) {
        lastError = null;
        if (file == null || !file.isFile()) {
            lastError = I18n.t("scope.openSignal.pickFirst");
            return false;
        }
        SignalBufferReader.Builder builder;
        try (AudioInputStream in = PcmFileLoader.instance().openAsPcm(file)) {
            AudioFormat fmt = in.getFormat();
            int sampleRate     = (int) fmt.getSampleRate();
            int channels       = fmt.getChannels();
            int frameSize      = fmt.getFrameSize();
            int bytesPerSample = fmt.getSampleSizeInBits() / 8;
            boolean bigEndian  = fmt.isBigEndian();
            boolean signed     = fmt.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED);
            long totalFrames   = (in.getFrameLength() > 0) ? in.getFrameLength() : -1;
            if (totalFrames <= 0) {
                lastError = I18n.t("scope.openSignal.noSampleCount", file.getName());
                return false;
            }
            // Buffer sized to the exact frame count so the entire signal
            // fits without ring-wrap; capacity must be ≥ 1 sample so the
            // builder's backing buffer doesn't reject it.
            double bufSeconds = Math.max(1.0 / sampleRate, totalFrames / (double) sampleRate);
            builder = SignalBufferReader.builder(sampleRate, bufSeconds);

            int chunkFrames = Math.max(256, sampleRate / 50);
            byte[]  pcm   = new byte[chunkFrames * frameSize];
            float[] left  = new float[chunkFrames];
            float[] right = new float[chunkFrames];

            while (true) {
                int read = in.read(pcm, 0, pcm.length);
                if (read <= 0) break;
                int frames = read / frameSize;
                if (frames > left.length) {
                    left  = new float[frames];
                    right = new float[frames];
                }
                StereoPcmIo.decodeStereo(pcm, frames, channels, bytesPerSample, signed, bigEndian,
                        left, right);
                builder.append(left, right, frames);
            }
            log.info("Scope open signal: {} ({} Hz, {} frames, {} s)",
                    file.getName(), sampleRate, totalFrames,
                    String.format("%.3f", totalFrames / (double) sampleRate));
        } catch (Exception ex) {
            lastError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("Scope open signal failed: {}", lastError, ex);
            return false;
        }

        loadedFile = file;
        SignalBufferReader reader = builder.build();
        if (!mainView.isDisposed()) {
            mainView.setBuffer(reader);
            mainView.setFileMode(true);
            mainView.startMeasurementThread();   // compute the measurement table for the loaded frame
        }
        if (!condensedView.isDisposed()) condensedView.setBuffer(reader);
        // Redraw immediately so the user sees the static waveform without
        // waiting for whatever timer happens to fire next.
        if (!mainView.isDisposed())      mainView.redraw();
        if (!condensedView.isDisposed()) condensedView.redraw();
        return true;
    }

    /**
     * Detaches the loaded buffer and forgets the file so the views can
     * be re-attached to a fresh live capture buffer.  Safe to call when
     * nothing is loaded.
     */
    public void clear() {
        loadedFile = null;
        lastError  = null;
        if (!mainView.isDisposed()) {
            mainView.setFileMode(false);
            mainView.setBuffer(null);
        }
        if (!condensedView.isDisposed()) condensedView.setBuffer(null);
        if (!mainView.isDisposed())      mainView.redraw();
        if (!condensedView.isDisposed()) condensedView.redraw();
    }

}
