package org.edgo.audio.measure.sound;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import org.edgo.audio.measure.common.StereoSample;

import lombok.extern.log4j.Log4j2;

/**
 * Records audio from a specific sound card mixer via javax.sound.sampled.
 * With csjsound-provider on the classpath the backend switches to WASAPI exclusive
 * mode, enabling true 32-bit capture.
 *
 * Native library required: csjsound_amd64.dll must be on java.library.path.
 */
@Log4j2
public class CsjsoundRecorder implements AudioCapture {

    private static final int HW_BUFFER_FRAMES = 262144;  // ~682 ms at 384 kHz

    /** Full-scale RMS input voltage of the ADC (V) — converts recorded peak_float to dBV.
     *  Mutable so a CLI flag (e.g. {@code --adc-fs-vrms}) can override the hardware default. */
    public static double adcFsVoltageRms = 1.7931;

    private final AudioFormat    format;
    private final Mixer.Info     mixerInfo;
    private final int            sampleBytes;   // bytes per sample (bitDepth / 8)
    private       TargetDataLine line;
    private final AtomicBoolean  recording = new AtomicBoolean(false);
    private       Thread         captureThread;
    private       StereoSample[] sampleBuf;
    private       Consumer<StereoSample[]> sampleListener;
    private       Consumer<byte[]> rawBytesListener;

    public CsjsoundRecorder(Mixer.Info mixerInfo, int sampleRate, int bitDepth) {
        this.mixerInfo   = mixerInfo;
        this.sampleBytes = bitDepth / 8;
        int frameSize    = sampleBytes * 2; // stereo
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitDepth,
                2,           // channels (stereo)
                frameSize,
                sampleRate,
                false        // little-endian
        );
    }

    /**
     * Attach a listener that receives decoded samples in real time.
     * Called on the capture thread; delivers channel-0 samples only.
     */
    public void setSampleListener(Consumer<StereoSample[]> listener) {
        this.sampleListener = listener;
    }

    /** Delivers raw interleaved PCM bytes (all channels) as read from the device. */
    public void setRawBytesListener(Consumer<byte[]> listener) {
        this.rawBytesListener = listener;
    }

    public void open() throws LineUnavailableException {
        DataLine.Info info  = new DataLine.Info(TargetDataLine.class, format);
        Mixer         mixer = AudioSystem.getMixer(mixerInfo);
        if (!mixer.isLineSupported(info)) {
            throw new LineUnavailableException(
                    "Mixer '" + mixerInfo.getName() + "' does not support format: " + format);
        }
        line = (TargetDataLine) mixer.getLine(info);
        line.open(format, HW_BUFFER_FRAMES * sampleBytes * 2);
        int actualFrames = line.getBufferSize() / (sampleBytes * 2);
        log.info("Recorder opened : {}", mixerInfo.getName());
        log.info("Capture format  : {}", format);
        log.info("HW buffer       : {} frames ({} ms)", actualFrames,
                 actualFrames * 1000 / (int) format.getSampleRate());
        // Pre-allocate sample buffer to avoid per-callback GC pressure.
        int readFrames = (line.getBufferSize() / 4 / (sampleBytes * 2));
        sampleBuf = new StereoSample[readFrames];
        for (int i = 0; i < readFrames; i++) sampleBuf[i] = new StereoSample();
    }

    public void startRecording() {
        if (line == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        line.start();
        captureThread = new Thread(this::captureLoop, "audio-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        log.info("Recording started on: {}", mixerInfo.getName());
    }

    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (line != null) {
            line.stop();
        }
        if (captureThread != null) {
            captureThread.join(2000);
        }
        log.info("Recording stopped.");
    }

    private void captureLoop() {
        int    frameSize = sampleBytes * 2; // stereo frame
        // Must match the pre-allocation in open(): sampleBuf.length frames.
        int    bufSize   = sampleBuf.length * frameSize;
        byte[] buffer    = new byte[bufSize];

        while (recording.get()) {
            int bytesRead = line.read(buffer, 0, buffer.length);
            if (bytesRead > 0 && rawBytesListener != null) {
                byte[] copy = Arrays.copyOf(buffer, bytesRead);
                rawBytesListener.accept(copy);
            }
            if (bytesRead > 0 && sampleListener != null) {
                int frames = bytesRead / frameSize;
                for (int f = 0; f < frames; f++) {
                    int offset = f * frameSize;
                    sampleBuf[f].ch0 = readSample(buffer, offset);
                    sampleBuf[f].ch1 = readSample(buffer, offset + sampleBytes);
                }
                sampleListener.accept(sampleBuf);
            }
        }
    }

    /**
     * Reads one little-endian signed sample of {@code sampleBytes} bytes
     * from {@code pcm} starting at {@code offset}.
     */
    public int readSample(byte[] pcm, int offset) {
        switch (sampleBytes) {
            case 1:
                return (pcm[offset] + (byte) 0x80) & 0xFF;
            case 2:
                return ((short) ((pcm[offset + 1] & 0xFF) << 8 | (pcm[offset] & 0xFF)) + (short) 0x8000) & 0xFFFF;
            case 3:
                return ((((pcm[offset + 2]) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x800000) & 0xFFFFFF;
            case 4:
                return (((pcm[offset + 3] << 24)
                     | ((pcm[offset + 2] & 0xFF) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x80000000) & 0xFFFFFFFF;
            default:
                throw new IllegalStateException("Unsupported sampleBytes: " + sampleBytes);
        }
    }

    public AudioFormat getFormat()   { return format; }
    public boolean     isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try {
                stopRecording();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.close();
            line = null;
        }
    }
}
