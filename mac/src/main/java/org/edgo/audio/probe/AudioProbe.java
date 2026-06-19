/*
 * Phonalyser — JavaSound capture probe (standalone diagnostic).
 *
 * Lists every JavaSound mixer, and for each capture (TargetDataLine) line
 * actually TRIES to open it at the sample-rate / bit-depth / channel
 * combinations Phonalyser offers — reporting, per combination, whether the
 * format is unsupported, opens OK, or fails (and the REAL exception, instead
 * of the GUI's misleading "exclusive-mode" message).
 *
 * This is how we tell apart, on macOS:
 *   - which rates/bits the device genuinely supports        (issue: bogus list)
 *   - whether capture is actually blocked / unavailable      (issue: capture fails)
 *
 * Build + run:
 *   mvn -f mac/pom.xml package && java -jar mac/target/audio-probe.jar
 * Or single-file (no Maven):
 *   java mac/src/main/java/org/edgo/audio/probe/AudioProbe.java
 */
package org.edgo.audio.probe;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

public final class AudioProbe {

    /** Sample rates Phonalyser exposes, low → hi-res. */
    private static final float[] RATES = {
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 768000
    };
    /** Bit depths to try. */
    private static final int[] BITS = {16, 24, 32};
    /** Channel counts to try (mono + stereo). */
    private static final int[] CHANNELS = {1, 2};

    public static void main(String[] args) {
        new AudioProbe().run();
    }

    private void run() {
        System.out.println("=== Phonalyser JavaSound capture probe ===");
        System.out.printf("Java %s (%s)   OS %s %s%n",
                System.getProperty("java.version"),
                System.getProperty("os.arch"),
                System.getProperty("os.name"),
                System.getProperty("os.version"));

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        System.out.printf("%nMixers: %d%n", mixers.length);

        for (Mixer.Info mi : mixers) {
            Mixer mixer = AudioSystem.getMixer(mi);
            Line.Info[] captureLines = mixer.getTargetLineInfo();
            int captureDataLines = 0;
            for (Line.Info li : captureLines) {
                if (li instanceof DataLine.Info) {
                    captureDataLines++;
                }
            }

            System.out.printf("%n========================================================%n");
            System.out.printf("MIXER: %s   [vendor: %s]%n", mi.getName(), mi.getVendor());
            System.out.printf("  desc       : %s%n", mi.getDescription());
            System.out.printf("  capture    : %d line(s) (%d data-lines)   playback: %d line(s)%n",
                    captureLines.length, captureDataLines, mixer.getSourceLineInfo().length);

            if (captureDataLines == 0) {
                System.out.println("  (no capture data-lines — skipping format probe)");
                continue;
            }

            System.out.println("  capture open test  [.] unsupported  [OK] opened  [BUSY]/[ERR] see note");
            for (float rate : RATES) {
                StringBuilder line = new StringBuilder(String.format("  %7.0f Hz : ", rate));
                for (int ch : CHANNELS) {
                    line.append(ch == 1 ? "mono[" : "stereo[");
                    for (int bits : BITS) {
                        line.append(bits).append('=').append(tryOpen(mixer, rate, bits, ch)).append(' ');
                    }
                    line.append("] ");
                }
                System.out.println(line);
            }
        }

        System.out.printf("%n=== done ===%n");
        System.out.println("The [BUSY]/[ERR] notes are the REAL reason capture fails; the [.] cells");
        System.out.println("show which formats the device does NOT actually support.");
    }

    /** Tries to open + start a capture line at one format; returns a short code. */
    private String tryOpen(Mixer mixer, float rate, int bits, int channels) {
        int frameSize = channels * (bits / 8);
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels, frameSize, rate, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);

        if (!mixer.isLineSupported(info)) {
            return ".";   // format not offered by this device
        }
        TargetDataLine line = null;
        try {
            line = (TargetDataLine) mixer.getLine(info);
            line.open(fmt);
            line.start();
            line.stop();
            return "OK";
        } catch (LineUnavailableException e) {
            return "BUSY{" + msg(e) + "}";
        } catch (RuntimeException e) {
            return "ERR{" + e.getClass().getSimpleName() + ":" + msg(e) + "}";
        } finally {
            if (line != null) {
                line.close();
            }
        }
    }

    private String msg(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }
}
