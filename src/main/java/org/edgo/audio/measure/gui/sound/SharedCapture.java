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

package org.edgo.audio.measure.gui.sound;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.LineUnavailableException;
import java.util.List;

/**
 * Single owner of the application's input-capture device and the
 * {@link SignalBuffer} fed by it.  Both the oscilloscope and the FFT
 * pane consume from the same underlying stream; reference-counted
 * {@link #acquire()} / {@link #release()} keep the device open as long
 * as either pane is recording and close it when the last reference
 * goes away.
 *
 * <p>Previously these responsibilities lived inside
 * {@code OscilloscopeController} — a layering inversion, since the FFT
 * pane had to reach across through the oscilloscope to share the
 * device.  Lifted here so the controller is purely a scope concern and
 * any consumer can drive the shared capture directly.
 *
 * <h2>Threading</h2>
 * <p>{@link #acquire()} and {@link #release()} are synchronised on the
 * instance; the audio-callback thread writes into the shared
 * {@link SignalBuffer} directly (the buffer synchronises read / write
 * itself).
 */
@Log4j2
public final class SharedCapture {

    /** Ring-buffer length, in seconds.  Sized for at least two display
     *  windows at the slowest time/div (1 s/div × 10 = 10 s) plus margin
     *  so the trigger-position slider can sweep across the full window
     *  without the display falling off the buffer. */
    private static final double BUFFER_SECONDS = 22.0;

    private static volatile SharedCapture instance;

    public static SharedCapture instance() {
        SharedCapture local = instance;
        if (local != null) return local;
        synchronized (SharedCapture.class) {
            if (instance == null) instance = new SharedCapture();
            return instance;
        }
    }

    private AudioCapture capture;
    /** The currently-open shared buffer — owned by the active capture
     *  session.  Both the scope view and the FFT pane read from this
     *  same instance so they see consistent data. */
    private SignalBuffer sharedBuffer;
    /** Reference count for the open device.  Incremented by every
     *  {@link #acquire()}, decremented by every {@link #release()};
     *  the device is open while this is &gt; 0. */
    private int refCount;
    /** Human-readable message from the last failed {@link #acquire()},
     *  or {@code null} on success / clean state.  Cleared on the next
     *  successful acquire. */
    private String lastStartError;

    /** Wires the singleton into the {@link MessageBus} on first
     *  construction so any consumer can drive the shared capture by
     *  publishing {@link Events#CAPTURE_ACQUIRE} (request — returns the
     *  live {@link SignalBuffer} or {@code null}) and
     *  {@link Events#CAPTURE_RELEASE} (notification). */
    private SharedCapture() {
        MessageBus bus = MessageBus.instance();
        bus.registerResponder(Events.CAPTURE_ACQUIRE, this::acquire);
        bus.subscribe(Events.CAPTURE_RELEASE, ignored -> release());
    }

    /** Returns {@code true} if the shared audio device is currently open
     *  (at least one consumer holds a reference). */
    public synchronized boolean isCapturing() {
        return refCount > 0;
    }

    /** Human-readable description of the last {@link #acquire()}
     *  failure, or {@code null} if the last acquire succeeded /
     *  wasn't attempted. */
    public synchronized String getLastStartError() {
        return lastStartError;
    }

    /**
     * Opens the input device if no reference is held yet, otherwise
     * increments the reference count.  Returns a fresh
     * {@link SignalBufferReader} cursor over the live shared buffer the
     * device is writing into (each consumer gets its own read position
     * over the one shared stream), or {@code null} on failure (with a
     * human-readable reason stored in {@link #getLastStartError()}).
     */
    public synchronized SignalBufferReader acquire() {
        if (refCount > 0) {
            refCount++;
            return new SignalBufferReader(sharedBuffer);
        }
        lastStartError = null;

        Preferences prefs = Preferences.instance();
        BackendPrefs bp = prefs.current();
        String deviceName = bp.getInputDeviceName();
        if (deviceName == null) {
            lastStartError = I18n.t("capture.error.noInputDevice");
            log.warn("Capture: {}", lastStartError);
            return null;
        }
        DeviceRef device = findInputDevice(deviceName);
        if (device == null) {
            lastStartError = I18n.t("capture.error.inputDeviceGone", deviceName);
            log.warn("Capture: {}", lastStartError);
            return null;
        }
        final int sampleRate = bp.getInputSampleRate();
        final int bitDepth   = bp.getInputBitDepth();

        try {
            AudioCapture cap = AudioBackend.instance().openCapture(device, sampleRate, bitDepth);
            cap.open();

            SignalBuffer buf = new SignalBuffer(sampleRate, BUFFER_SECONDS);

            final int sampleBytes = bitDepth / 8;
            final int frameSize   = sampleBytes * 2;   // stereo
            // Per sample we call cap.readSample (offset-binary unsigned),
            // then go through the (uL − midpoint) / midpoint conversion
            // in double precision and KEEP it double end-to-end — the whole
            // time-domain path (this staging, the ring buffer, the FFT read
            // buffers) is double, so no single-precision narrowing happens
            // between the ADC word and the transform.  Reusable per-chunk
            // staging buffers — fed by the capture thread, then pushed in one
            // synchronised appendBatch() call so the UI thread isn't
            // fighting the per-sample monitor entries.
            final long unsignedMask = (bitDepth >= 32) ? 0xFFFFFFFFL : ((1L << bitDepth) - 1);
            final double midpoint   = 1L << (bitDepth - 1);
            final double[][] convBuf = { new double[1], new double[1] };
            cap.setPcmBatchListener((pcm, validBytes) -> {
                int frames = validBytes / frameSize;
                if (convBuf[0].length < frames) {
                    convBuf[0] = new double[frames];
                    convBuf[1] = new double[frames];
                }
                double[] l = convBuf[0];
                double[] r = convBuf[1];
                for (int f = 0, o = 0; f < frames; f++, o += frameSize) {
                    long uL = ((long) cap.readSample(pcm, o)) & unsignedMask;
                    long uR = ((long) cap.readSample(pcm, o + sampleBytes)) & unsignedMask;
                    l[f] = (uL - midpoint) / midpoint;
                    r[f] = (uR - midpoint) / midpoint;
                }
                buf.appendBatch(l, r, frames);
                MessageBus.instance().publish(Events.CAPTURE_BATCH_AVAILABLE);
            });

            this.capture      = cap;
            this.sharedBuffer = buf;
            cap.startRecording();
            refCount = 1;
            log.info("Audio capture started: device={}, sampleRate={} Hz, bitDepth={} bits",
                    device.displayName(), sampleRate, bitDepth);
            return new SignalBufferReader(sharedBuffer);
        } catch (Exception ex) {
            lastStartError = translateOpenFailure(ex, device, sampleRate, bitDepth);
            log.error("Capture: failed to start — {}", ex.getMessage(), ex);
            cleanupAfterFailure();
            return null;
        }
    }

    /** Releases one reference.  Closes the device only when the last
     *  reference goes away.  Safe to call when no reference is held. */
    public synchronized void release() {
        if (refCount <= 0) return;
        refCount--;
        if (refCount > 0) return;
        if (capture != null) {
            Closeables.tryQuietly("capture.stopRecording", capture::stopRecording);
            Closeables.closeQuietly(capture);
            capture = null;
        }
        sharedBuffer = null;
        log.info("Audio capture stopped (last reference released).");
    }

    /**
     * Maps the various low-level capture-open exceptions
     * ({@link javax.sound.sampled.LineUnavailableException} from the
     * csjsound backend, {@link IllegalStateException} from PortAudio's
     * {@code check()} on the WDM-KS backend, etc.) to a human-readable
     * sentence suitable for a UI dialog.  Falls back to the raw
     * exception message when no specific keyword match wins.
     */
    private String translateOpenFailure(Exception ex, DeviceRef device,
                                        int sampleRate, int bitDepth) {
        String raw = ex.getMessage();
        String lower = (raw == null) ? "" : raw.toLowerCase();
        String header = I18n.t("capture.error.openHeader",
                device.displayName(), String.valueOf(sampleRate), String.valueOf(bitDepth)) + "\n\n";
        if (lower.contains("does not support format")
                || lower.contains("invalid sample rate")
                || lower.contains("invalid sample format")
                || lower.contains("paunanticipatedhosterror") && lower.contains("format")) {
            return header + I18n.t("capture.error.formatUnsupported");
        }
        if (ex instanceof LineUnavailableException
                || lower.contains("device unavailable")
                || lower.contains("paunanticipatedhosterror")   // WDM-KS exclusive-pin conflict
                || lower.contains("line with the given format is not available")
                || lower.contains("line unavailable")
                || lower.contains("exclusive")
                || lower.contains("in use")
                || lower.contains("busy")) {
            return header + I18n.t("capture.error.deviceInUse");
        }
        if (lower.contains("not found") || lower.contains("no such device")) {
            return header + I18n.t("capture.error.deviceNotFound");
        }
        if (raw != null && !raw.isBlank()) {
            return header + raw;
        }
        return header + ex.getClass().getSimpleName();
    }

    private void cleanupAfterFailure() {
        refCount = 0;
        Closeables.closeQuietly(capture);
        capture = null;
        sharedBuffer = null;
    }

    private DeviceRef findInputDevice(String name) {
        List<DeviceRef> devices = AudioBackend.instance().listInputDevices();
        for (DeviceRef d : devices) {
            if (name.equals(d.name())) return d;
        }
        return null;
    }
}
