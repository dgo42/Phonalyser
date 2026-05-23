package org.edgo.audio.measure.sound;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.StereoSample;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM capture via PortAudio's WDM-KS host API, callback mode.
 * PortAudio invokes {@link #paCallback} on its realtime audio thread with
 * freshly captured frames; the callback copies the bytes into
 * {@link #queue} and returns immediately. A dedicated consumer thread
 * drains the queue and invokes the user-supplied listeners, so heavy
 * listener work never blocks the audio thread.
 */
@Log4j2
public class WdmksRecorder implements AudioCapture {

    private final WdmksDeviceManager.WdmksDeviceRef device;
    private final int sampleRate;
    private final int bitDepth;
    private final int sampleBytes;
    private int frameSize;
    private final AudioFormat format;

    private Pointer stream;                 // PaStream*
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread consumerThread;
    private StereoSample[] sampleBuf;
    private Consumer<StereoSample[]> sampleListener;
    private Consumer<byte[]> rawBytesListener;

    private final BlockingQueue<byte[]>        queue         = new LinkedBlockingQueue<>(64);
    private final ConcurrentLinkedQueue<byte[]> bufferPool   = new ConcurrentLinkedQueue<>();
    private final AtomicLong                    overflowCount = new AtomicLong();

    /**
     * Audio-thread callback. Pulls a same-sized scratch buffer from
     * {@link #bufferPool} (allocating only on cold start or when PortAudio
     * changes its block size mid-stream), copies the captured PCM into it
     * and hands it to the consumer thread via {@link #queue}. Reusing
     * buffers keeps the audio thread allocation-free in steady state, so
     * the GC can't pause it into a dropout.
     */
    private final PortAudio.PaStreamCallback paCallback = (input, output, frameCount, ti, sf, ud) -> {
        if (!recording.get()) return PortAudio.paComplete;
        int frames = frameCount.intValue();
        int bytes  = frames * frameSize;
        byte[] buf = bufferPool.poll();
        if (buf == null || buf.length != bytes) buf = new byte[bytes];
        input.read(0, buf, 0, bytes);
        if (!queue.offer(buf)) {
            overflowCount.incrementAndGet();
            bufferPool.offer(buf);
        }
        return PortAudio.paContinue;
    };

    public WdmksRecorder(WdmksDeviceManager.WdmksDeviceRef device, int sampleRate, int bitDepth) {
        this.device      = device;
        this.sampleRate  = sampleRate;
        this.bitDepth    = bitDepth;
        this.sampleBytes = bitDepth / 8;
        this.frameSize   = sampleBytes * 2;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, 2,
                frameSize, sampleRate, false);
    }

    @Override
    public void setSampleListener(Consumer<StereoSample[]> listener) {
        this.sampleListener = listener;
    }

    @Override
    public void setRawBytesListener(Consumer<byte[]> listener) {
        this.rawBytesListener = listener;
    }

    @Override
    public void open() {
        PortAudio.Lib lib = PortAudio.lib();

        PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(device.paDeviceIndex());
        // WDM-KS stalls after the priming callback if suggestedLatency is 0 or
        // framesPerBuffer fights the KS pin's preferred size.  Use the device's
        // own default-high latency (more headroom against GC pauses / scheduler
        // jitter than defaultLow*) and let PortAudio pick the natural pin size.
        double suggestedLatency = (info != null && info.defaultHighInputLatency > 0)
                ? info.defaultHighInputLatency : 0.05;

        PortAudio.PaStreamParameters in = new PortAudio.PaStreamParameters();
        in.device                    = device.paDeviceIndex();
        in.channelCount              = 2;
        in.sampleFormat              = PortAudio.paSampleFormatFor(bitDepth);
        in.suggestedLatency          = suggestedLatency;
        in.hostApiSpecificStreamInfo = null;
        in.write();

        PortAudio.check(lib.Pa_IsFormatSupported(in, null, sampleRate),
                "Pa_IsFormatSupported(input " + sampleRate + " Hz / " + bitDepth + " bit)");

        PointerByReference handle = new PointerByReference();
        int rc = lib.Pa_OpenStream(handle,
                in, null,
                sampleRate,
                PortAudio.paFramesPerBufferUnspecified,
                PortAudio.paClipOff,
                paCallback, null);
        PortAudio.check(rc, "Pa_OpenStream(input)");
        stream = handle.getValue();

        sampleBuf = new StereoSample[0];

        log.info("WDM-KS recorder opened : {}", device.name());
        log.info("Capture format         : {}", format);
        log.info("Suggested latency      : {} ms (host-picked buffer size)",
                Math.round(suggestedLatency * 1000));
    }

    @Override
    public void startRecording() {
        if (stream == null) throw new IllegalStateException("Call open() before startRecording()");
        recording.set(true);
        queue.clear();
        bufferPool.clear();
        overflowCount.set(0);
        consumerThread = new Thread(this::consumeLoop, "wdmks-consume");
        consumerThread.setDaemon(true);
        consumerThread.setPriority(Thread.NORM_PRIORITY + 1);
        consumerThread.start();
        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(input)");
        log.info("WDM-KS recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (stream != null) {
            try {
                PortAudio.lib().Pa_StopStream(stream);
            } catch (Throwable t) {
                log.warn("Pa_StopStream: {}", t.getMessage());
            }
        }
        if (consumerThread != null) consumerThread.join(2000);
        long drops = overflowCount.get();
        if (drops > 0) log.warn("WDM-KS capture queue overflowed {} times", drops);
        log.info("WDM-KS recording stopped.");
    }

    private void consumeLoop() {
        while (recording.get() || !queue.isEmpty()) {
            byte[] buffer;
            try {
                buffer = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (buffer == null) continue;
            try {
                if (rawBytesListener != null) rawBytesListener.accept(buffer);
                if (sampleListener != null) {
                    int nFrames = buffer.length / frameSize;
                    if (sampleBuf.length != nFrames) {
                        sampleBuf = new StereoSample[nFrames];
                        for (int i = 0; i < nFrames; i++) sampleBuf[i] = new StereoSample();
                    }
                    for (int f = 0; f < nFrames; f++) {
                        int offset = f * frameSize;
                        sampleBuf[f].ch0 = readSample(buffer, offset);
                        sampleBuf[f].ch1 = readSample(buffer, offset + sampleBytes);
                    }
                    sampleListener.accept(sampleBuf);
                }
            } finally {
                bufferPool.offer(buffer);
            }
        }
    }

    /** Same offset-binary decoder as {@link CsjsoundRecorder#readSample}. */
    @Override
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

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public boolean isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (stream != null) {
            try { PortAudio.lib().Pa_CloseStream(stream); } catch (Throwable ignored) {}
            stream = null;
        }
    }
}
