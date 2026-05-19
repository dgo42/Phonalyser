package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import lombok.extern.log4j.Log4j2;
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
    private final    AtomicBoolean stopFlag = new AtomicBoolean(false);
    private volatile boolean       running;
    private volatile boolean       loop;
    private volatile String        lastStartError;
    /** Fired on the play thread when playback ends (any reason — user stop, EOF, error). */
    private Runnable               onStopped;

    /** Sets the callback fired when playback ends.  May run on any thread; caller marshals to SWT if needed. */
    public void setOnStopped(Runnable r) { this.onStopped = r; }

    /** Live-updates the loop flag.  Picked up at the next EOF check by the play thread. */
    public void setLoop(boolean loop) { this.loop = loop; }

    /**
     * Spawns a daemon thread that decodes {@code file} and writes its
     * samples to a fresh {@link SourceDataLine}.  No-op if already
     * running.  On failure {@link #isRunning()} returns {@code false}
     * and {@link #getLastStartError()} carries the reason.
     */
    public synchronized void start(File file, boolean loop) {
        if (running) return;
        lastStartError = null;
        if (file == null || !file.isFile()) {
            lastStartError = "Pick a file first (the … button).";
            return;
        }
        this.loop = loop;
        stopFlag.set(false);
        Thread t = new Thread(() -> playLoop(file), "file-play");
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
        }
        playThread = null;
        running    = false;
    }

    public boolean isRunning()        { return running; }
    public String  getLastStartError() { return lastStartError; }

    private void playLoop(File file) {
        SourceDataLine line = null;
        AudioInputStream in = null;
        try {
            PcmFileLoader loader = PcmFileLoader.instance();
            in = loader.openAsPcm(file);
            AudioFormat fmt = in.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();
            log.info("File playback started: {} ({}{})",
                    file.getName(), fmt, loop ? ", looping" : "");

            byte[] buf = new byte[8192];
            while (!stopFlag.get()) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (!loop) break;        // volatile — re-read each EOF
                    try { in.close(); } catch (Exception ignored) {}
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
                try { line.stop();  } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            running = false;
            Runnable r = onStopped;
            if (r != null) {
                try { r.run(); }
                catch (Throwable t) { log.warn("onStopped callback failed: {}", t.toString()); }
            }
        }
    }
}
