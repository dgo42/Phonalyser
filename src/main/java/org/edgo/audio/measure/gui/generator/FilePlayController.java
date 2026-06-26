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

package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.SourceDataLine;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.sound.JavaSoundDeviceManager;
import org.edgo.audio.measure.wav.PcmFileLoader;

/**
 * Stream-based file player for the generator pane's "Play from…" row.
 * Decodes the chosen file via {@code javax.sound.sampled.AudioSystem}
 * (WAV / AIFF built-in; FLAC via jflac-codec's SPI) and pushes the
 * decoded PCM bytes to a default {@link SourceDataLine}.  Looping is
 * supported — on EOF the input stream is reopened from the original
 * file.
 *
 * <p>Intentionally <strong>not</strong> wired through
 * {@code AudioBackend}: file playback is a monitoring convenience
 * (verify a saved file sounds right) rather than measurement-grade
 * output, so the simpler JavaSound path is enough.  Routing through
 * the WASAPI / WDM-KS backends would need an in-memory adapter from
 * {@code AudioInputStream} to {@code AudioPlayback#play}, which is
 * more plumbing than this UX warrants today.
 */
@Log4j2
public final class FilePlayController {

    private volatile Thread        playThread;
    /** Per-session stop flag — a fresh instance per start so a stop request
     *  to a previous (possibly join-timed-out) play thread can never be
     *  revoked by the next session's reset. */
    private volatile AtomicBoolean stopFlag = new AtomicBoolean(false);
    @Getter
    private volatile boolean       running;
    /** Live-updates the loop flag.  Picked up at the next EOF check by the play thread. */
    @Setter
    private volatile boolean       loop;
    @Getter
    private volatile String        lastStartError;

    /** Opens the playback line on the user-selected output device's mixer —
     *  the same {@link JavaSoundDeviceManager} the DDS tone uses — so file
     *  playback honours the device setting and can open high formats (e.g.
     *  384&nbsp;kHz / 24-bit) the JavaSound default mixer refuses. */
    private final JavaSoundDeviceManager deviceManager;

    public FilePlayController(JavaSoundDeviceManager deviceManager) {
        this.deviceManager = deviceManager;
    }

    /**
     * Spawns a daemon thread that decodes {@code file} and writes its
     * samples to a fresh {@link SourceDataLine} on the {@code deviceName}
     * output device (or the default mixer when {@code deviceName} is blank).
     * No-op if already running.  On failure {@link #isRunning()} returns
     * {@code false} and {@link #getLastStartError()} carries the reason.
     */
    public synchronized void start(File file, boolean loop, String deviceName) {
        if (running) return;
        lastStartError = null;
        Thread old = playThread;
        if (old != null && old.isAlive()) {
            lastStartError = I18n.t("generator.error.shuttingDown");
            return;
        }
        if (file == null || !file.isFile()) {
            lastStartError = I18n.t("generator.error.playFile.pickFirst");
            return;
        }
        this.loop = loop;
        AtomicBoolean sessionStop = new AtomicBoolean(false);
        stopFlag = sessionStop;
        Thread t = new Thread(() -> playLoop(file, sessionStop, deviceName), "file-play");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        playThread = t;
        running = true;
        t.start();
    }

    /** Stops the play thread (idempotent).  Waits up to 2 s for it to exit. */
    public synchronized void stop() {
        stopFlag.set(true);
        Thread t = playThread;
        if (t != null) {
            try { t.join(2_000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) {
                // Keep the reference so start() refuses a second session while
                // this one is wedged; its session stop flag stays set.
                log.warn("File-play thread did not exit within 2 s — restart refused until it does.");
            } else {
                playThread = null;
            }
        }
        running = false;
    }

    private void playLoop(File file, AtomicBoolean sessionStop, String deviceName) {
        SourceDataLine line = null;
        AudioInputStream in = null;
        try {
            PcmFileLoader loader = PcmFileLoader.instance();
            in = loader.openAsPcm(file);
            AudioFormat fmt = in.getFormat();
            line = deviceManager.openOutputLine(deviceName, fmt);
            line.start();
            log.info("File playback started: {} ({}{})",
                    file.getName(), fmt, loop ? ", looping" : "");

            byte[] buf = new byte[8192];
            while (!sessionStop.get()) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (!loop) break;        // volatile — re-read each EOF
                    Closeables.closeQuietly(in);
                    in = loader.openAsPcm(file);
                    continue;
                }
                line.write(buf, 0, n);
            }
            line.drain();
            log.info("File playback stopped: {}", file.getName());
        } catch (Exception ex) {
            log.warn("File playback failed: {}", ex.getMessage(), ex);
            lastStartError = ex.getMessage();
        } finally {
            if (line != null) {
                final SourceDataLine fLine = line;
                Closeables.tryQuietly("SourceDataLine.stop", fLine::stop);
            }
            Closeables.closeQuietly(line);
            Closeables.closeQuietly(in);
            // A join-timed-out session finishing late must not clear the
            // flag (or notify) for a session started after it.
            if (Thread.currentThread() == playThread) {
                running = false;
                MessageBus.instance().publish(Events.FILE_PLAY_STOPPED);
            }
        }
    }
}
