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

import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.FftBinSnap;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Controller of the generator pane: owns both output engines — the DDS
 * playback thread and the WAV/FLAC {@link FilePlayController} — plus the
 * signal math around them (FFT-bin snap, effective frequency, period
 * samples) and the signal file export.  {@link #start()} resolves the
 * current output device + sample rate + bit depth from {@link Preferences},
 * constructs a {@link SignalGenerator} from the saved settings, opens an
 * {@link AudioPlayback} on a background thread, and runs continuously until
 * {@link #stop()} is called.
 *
 * <p>The constructor subscribes to every generator preference the engines
 * follow live (frequency, amplitude, duty, sweep params, dither, …) and to
 * the bus events that drive them (FLL trims, FFT-length re-snap, the
 * FreqResp sweep claiming the DAC) — the pane never relays.  Towards the
 * view the controller only publishes bus events and answers state getters:
 * the pane checks {@link #isRunning()} when toggling its Play button and
 * reads {@link #getLastStartError()} on a failed start so it can display
 * the reason in a MessageBox.
 */
@Log4j2
public final class GeneratorController {

    private volatile Thread          playThread;
    private volatile SignalGenerator generator;
    private volatile AudioPlayback   playback;
    private final    AtomicBoolean   stopFlag    = new AtomicBoolean(false);
    @Getter
    private volatile boolean         running;
    @Getter
    private volatile String          lastStartError;
    /** WAV/FLAC file playback engine — shares the output device with the
     *  DDS tone, so starting either engine stops the other. */
    private final FilePlayController filePlayer = new FilePlayController();
    /** Previous waveform — the restart-vs-live-swap decision needs both
     *  sides of a form change. */
    private GenSignalForm lastForm;
    /** Detach actions for every Preferences / bus subscription made in the
     *  constructor; run by {@link #shutdown()}. */
    private final List<Runnable> unsubscribes = new ArrayList<>();

    public GeneratorController() {
        Preferences prefs = Preferences.instance();
        lastForm = prefs.getGenSignalForm();
        bindPreferences(prefs);
        bindBus();
    }

    /** Engine-driving preference subscriptions.  Each live-applies to the
     *  running engine and publishes {@link Events#GENERATOR_SIGNAL_CHANGED}
     *  exactly once where the emitted signal changes (the FFT averaging
     *  restart hangs off that event). */
    private void bindPreferences(Preferences prefs) {
        onPref(prefs.genFrequencyHzProperty(), v -> {
            setFrequency(effectiveFrequency());
            publishSignalChanged();
        });
        onPref(prefs.genDualToneFreq1HzProperty(), v -> {
            setFrequency(snapDualTone(v));
            publishSignalChanged();
        });
        onPref(prefs.genDualToneFreq2HzProperty(), v -> {
            setDualToneFrequency2(snapDualTone(v));
            publishSignalChanged();
        });
        onPref(prefs.genSnapToFftBinProperty(), v -> {
            reapplySnap();
            publishSignalChanged();
        });
        onPref(prefs.genSignalFormProperty(), this::onFormChanged);
        onPref(prefs.genAmplitudeVrmsProperty(), v -> {
            setAmplitudeVrms(v);
            publishSignalChanged();
        });
        onPref(prefs.dacFsVoltageRmsProperty(), this::setDacFsVoltageRms);
        onPref(prefs.genRectangleDutyProperty(), v -> {
            setRectangleDuty(v);
            publishSignalChanged();
        });
        onPref(prefs.genTriangleDutyProperty(), v -> {
            setTriangleDuty(v);
            publishSignalChanged();
        });
        onPref(prefs.genDualToneSplitPctProperty(), a1 -> {
            setDualToneAmplitudes(a1, 100.0 - a1);
            publishSignalChanged();
        });
        onPref(prefs.genDitherBitsProperty(), this::setDitherBits);
        onPref(prefs.genSweepFreqStartHzProperty(), v -> {
            setSweepFreqStart(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFreqEndHzProperty(), v -> {
            setSweepFreqEnd(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepDurationSecProperty(), v -> {
            setSweepDurationSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFadeInSecProperty(), v -> {
            setSweepFadeInSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFadeOutSecProperty(), v -> {
            setSweepFadeOutSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepLoopProperty(), v -> {
            setSweepLoop(v);
            publishSignalChanged();
        });
        onPref(prefs.genPlayFromLoopProperty(), filePlayer::setLoop);
    }

    /** Bus subscriptions that drive the engines. */
    private void bindBus() {
        MessageBus bus = MessageBus.instance();
        // FLL trim: the FFT view publishes the corrected DDS frequency after
        // each result; live-apply and republish as FLL_TRIM so the FFT
        // worker keeps its averaging accumulator.
        Consumer<Double> freqTrim = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            setFrequency(newHz);
            bus.publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        onBus(Events.GENERATOR_FREQ_TRIM, freqTrim);
        // Companion for the dual-tone second-tone FLL.
        Consumer<Double> freqTrim2 = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            setDualToneFrequency2(newHz);
            bus.publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        onBus(Events.GENERATOR_FREQ_TRIM_2, freqTrim2);
        // FFT length changed: slide the running tone(s) onto the new bin
        // grid without the user having to toggle the snap checkbox.
        Consumer<Void> fftLength = ignored -> {
            if (Preferences.instance().isGenSnapToFftBin()) reapplySnap();
        };
        onBus(Events.FFT_LENGTH_CHANGED, fftLength);
        // The FreqResp sweep needs the DAC exclusively — stop both engines.
        Consumer<Void> freqRespStarted = ignored -> stopEngines();
        onBus(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStarted);
        // "Is the generator running?" — read by the FFT worker to decide
        // whether to anchor the fundamental to the generator's frequency.
        bus.registerResponder(Events.GENERATOR_RUNNING,
                (Supplier<Boolean>) this::isProducingSignal);
        unsubscribes.add(() -> bus.unregisterResponder(Events.GENERATOR_RUNNING));
    }

    private <T> void onPref(Property<T> property, Consumer<T> action) {
        property.addListener(action);
        unsubscribes.add(() -> property.removeListener(action));
    }

    private <T> void onBus(String eventName, Consumer<T> listener) {
        MessageBus bus = MessageBus.instance();
        bus.subscribe(eventName, listener);
        unsubscribes.add(() -> bus.unsubscribe(eventName, listener));
    }

    private void publishSignalChanged() {
        MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
    }

    /** Detaches every Preferences / bus subscription and stops both
     *  engines — called from the pane's dispose listener. */
    public void shutdown() {
        for (Runnable r : unsubscribes) r.run();
        unsubscribes.clear();
        stopEngines();
    }

    /**
     * Reads the current generator settings + output device from {@link
     * Preferences}, opens the playback line and starts streaming on a
     * background thread.  No-op if already running.  On any failure
     * {@link #isRunning()} returns {@code false} and {@link
     * #getLastStartError()} carries a human-readable message.
     */
    public synchronized void start() {
        if (running) return;
        lastStartError = null;
        // DDS tone and file playback share the output device — only one of
        // them may drive it at a time.
        filePlayer.stop();

        Preferences prefs = Preferences.instance();
        BackendPrefs bp = prefs.current();
        String deviceName = bp.getOutputDeviceName();
        if (deviceName == null || deviceName.isEmpty()) {
            lastStartError = "No output device selected.  Pick one in Preferences first.";
            return;
        }
        DeviceRef device = findOutputDevice(deviceName);
        if (device == null) {
            lastStartError = "Output device \"" + deviceName + "\" is no longer available.";
            return;
        }

        final int    sampleRate    = bp.getOutputSampleRate();
        final int    bitDepth      = bp.getOutputBitDepth();
        final int    ditherBits    = prefs.getGenDitherBits();
        final double amplitudeVRms = prefs.getGenAmplitudeVrms();
        final GenSignalForm form      = prefs.getGenSignalForm();
        // First tone frequency: the generator constructor's
        // {@code frequency} parameter feeds the primary DDS, so for
        // DUAL_TONE this is tone 1.  Snapped to the nearest FFT bin
        // when the user enabled snap-to-bin; the snap helper itself
        // gates on the waveform.
        final double rawFrequency  = (form == GenSignalForm.DUAL_TONE)
                ? prefs.getGenDualToneFreq1Hz()
                : prefs.getGenFrequencyHz();
        final double frequency = FftBinSnap.snapIfEnabled(prefs, form, sampleRate, rawFrequency);

        // The WASAPI exclusive-mode driver sometimes refuses to start the
        // render stream on the first attempt when a sibling capture stream
        // is already running in the same process (the in-built scope view).
        // External recording works because cross-process contention is
        // mediated by Windows' session manager; in-process, the first start
        // can lose the race.  We retry once after a brief delay before
        // reporting failure to the user.
        final int    MAX_ATTEMPTS  = 2;
        final long   RETRY_PAUSE_MS = 500;
        final long   READY_TIMEOUT_S = 5;
        String       attemptError  = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptError = tryStartOnce(prefs, device, sampleRate, bitDepth, ditherBits,
                    form, frequency, amplitudeVRms, READY_TIMEOUT_S);
            if (attemptError == null) {
                running = true;
                log.info("Generator started (attempt {}): device={}, form={}, freq={} Hz, amp={} Vrms, rate={} Hz, depth={} bit, dither={} bit",
                        attempt, device.displayName(), form, frequency, amplitudeVRms, sampleRate, bitDepth, ditherBits);
                return;
            }
            log.warn("Generator start attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, attemptError);
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(RETRY_PAUSE_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        lastStartError = attemptError;
    }

    /**
     * One open + play attempt.  Returns {@code null} on success (controller
     * left in the running state — caller flips {@link #running}); a
     * non-null string carries the failure reason and guarantees any
     * partially-opened resources are torn down before returning.
     */
    private String tryStartOnce(Preferences prefs, DeviceRef device,
                                int sampleRate, int bitDepth, int ditherBits,
                                GenSignalForm form, double frequency, double amplitudeVRms,
                                long readyTimeoutSeconds) {
        SignalGenerator gen;
        double dacFs = prefs.getDacFsVoltageRms();
        try {
            if (form == GenSignalForm.SINE_COMPENSATED) {
                String csv = prefs.getGenCorrectionsCsv();
                if (csv == null || csv.isEmpty()) {
                    return "Sine (compensated) needs a harmonics-correction CSV.  Pick one with the … button.";
                }
                gen = new SignalGenerator(frequency, sampleRate, amplitudeVRms, dacFs, csv);
            } else if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
                double f0 = prefs.getGenSweepFreqStartHz();
                double f1 = prefs.getGenSweepFreqEndHz();
                int durationSamples = Math.max(2,
                        (int) Math.round(prefs.getGenSweepDurationSec() * sampleRate));
                if (form == GenSignalForm.LINEAR_SWEEP) {
                    gen = new SignalGenerator(f0, f1, sampleRate, durationSamples, amplitudeVRms, dacFs);
                } else {
                    // No lead-in for the GUI-driven sweep — that's a CLI-
                    // measurement feature, not a music-style sweep control.
                    gen = new SignalGenerator(f0, f1, durationSamples, 0, sampleRate, amplitudeVRms, dacFs);
                }
            } else {
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms, dacFs);
            }
        } catch (Exception ex) {
            return "Could not build the signal generator: " + ex.getMessage();
        }
        // Apply any waveform-specific live-tunables before we hand the
        // generator off to the audio thread.
        gen.setRectangleDuty(prefs.getGenRectangleDuty());
        gen.setTriangleDuty (prefs.getGenTriangleDuty());
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
            int fadeIn  = Math.max(0, (int) Math.round(prefs.getGenSweepFadeInSec()  * sampleRate));
            int fadeOut = Math.max(0, (int) Math.round(prefs.getGenSweepFadeOutSec() * sampleRate));
            gen.setSweepParams(prefs.isGenSweepLoop(), fadeIn, fadeOut);
        }
        if (form == GenSignalForm.DUAL_TONE) {
            // Dual-tone: tone 1 uses the frequency the generator was
            // constructed with (already snapped above); tone 2's
            // frequency and the per-tone amplitude split are pushed
            // in here.  Tone 2 is snapped to the FFT bin grid
            // independently so both tones land on a bin centre.
            // {@code genDualToneSplitPct} carries Freq 1's amplitude
            // percentage; Freq 2's amplitude is the complement.
            double rawF2  = prefs.getGenDualToneFreq2Hz();
            double snapF2 = FftBinSnap.snapIfEnabled(prefs, form, sampleRate, rawF2);
            gen.setDualToneFrequency2(snapF2);
            double a1Pct = prefs.getGenDualToneSplitPct();
            gen.setDualToneAmplitudes(a1Pct, 100.0 - a1Pct);
        }
        this.generator = gen;
        final SignalGenerator generator = gen;

        AudioPlayback ag;
        try {
            ag = AudioBackend.instance().openPlayback(device, sampleRate, bitDepth, ditherBits);
            ag.open();
        } catch (Exception ex) {
            log.warn("Playback open failed", ex);
            this.generator = null;
            return "Could not open the output device: " + ex.getMessage();
        }
        this.playback = ag;
        stopFlag.set(false);

        final CountDownLatch readyLatch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                ag.play(generator, stopFlag, readyLatch);
            } catch (Exception ex) {
                log.warn("Playback thread terminated abnormally", ex);
            } finally {
                Closeables.closeQuietly(ag);
            }
        }, "generator-play");
        t.setDaemon(true);
        // Exclusive-mode WASAPI / WDM-KS underruns the moment the audio
        // thread misses an event tick; bump to MAX_PRIORITY so Java's
        // scheduler keeps it ahead of GC helpers and the GUI thread.
        // This is the canonical fix for the "brief mid-plateau dip on a
        // square wave" artifact you get when a buffer's worth of audio
        // is replaced by silence during a stall.
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        this.playThread = t;

        try {
            if (!readyLatch.await(readyTimeoutSeconds, TimeUnit.SECONDS)) {
                // Tear down so a retry starts from a clean slate.
                stop();
                return "Output device opened but did not start streaming within "
                        + readyTimeoutSeconds + " s.";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            return "Interrupted while waiting for the output to start.";
        }
        return null;
    }

    /**
     * Stops the playback thread (idempotent).  Waits up to 2 s for the
     * thread to exit and the output line to drain / close before returning.
     */
    public synchronized void stop() {
        stopFlag.set(true);
        Thread t = playThread;
        if (t != null) {
            try {
                t.join(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        playThread = null;
        generator  = null;
        playback   = null;
        running    = false;
    }

    /** Live-applies the dither bit count to the running playback.  No-op if not running. */
    public void setDitherBits(int bits) {
        AudioPlayback ag = playback;
        if (ag != null) ag.setDitherBits(bits);
    }

    /** Live-applies a duty-cycle (fraction in [0.001, 0.999]) to the running rectangle generator. */
    public void setRectangleDuty(double dutyFrac) {
        SignalGenerator g = generator;
        if (g != null) g.setRectangleDuty(dutyFrac);
    }

    /** Live-applies a duty-cycle (fraction in [0.001, 0.999]) to the running triangle generator. */
    public void setTriangleDuty(double dutyFrac) {
        SignalGenerator g = generator;
        if (g != null) g.setTriangleDuty(dutyFrac);
    }

    /**
     * Live-applies a new waveform to the running generator.  No-op if the
     * generator isn't running.  Switching to / from {@link GenSignalForm#SINE_COMPENSATED}
     * or any sweep form requires a stop+start because their state machines
     * aren't safely live-mutable; this method skips them.
     */
    public void setForm(GenSignalForm form) {
        SignalGenerator g = generator;
        if (g == null || form == null) return;
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) return;
        if (form == GenSignalForm.SINE_COMPENSATED) return;
        g.setForm(form);
    }

    /** Live-applies a new frequency (Hz) to the running generator.  No-op if not running. */
    public void setFrequency(double hz) {
        SignalGenerator g = generator;
        if (g != null) g.setFrequency(hz);
    }

    /** Live-applies the second tone's frequency (Hz) for the
     *  {@code DUAL_TONE} waveform.  No-op if not running; no audible
     *  effect for non-DUAL_TONE waveforms (the second accumulator
     *  stays idle until the form is switched to DUAL_TONE). */
    public void setDualToneFrequency2(double hz) {
        SignalGenerator g = generator;
        if (g != null) g.setDualToneFrequency2(hz);
    }

    /** Live-applies the dual-tone power split (first tone's percentage
     *  of total signal power).  Generator clamps to {@code [0, 100]}.
     *
     *  @deprecated Replaced by {@link #setDualToneAmplitudes} which
     *      takes both percentages explicitly (amp1 + amp2 = 100). */
    @Deprecated
    public void setDualToneSplitPercent(double firstPercent) {
        SignalGenerator g = generator;
        if (g != null) g.setDualToneAmplitudes(firstPercent, 100.0 - firstPercent);
    }

    /** Live-applies the dual-tone per-tone amplitude percentages.
     *  Both values together; generator clamps each to {@code [0, 100]}
     *  and re-normalises the internal amplitude scale so the combined
     *  signal's Vrms still matches the Amplitude field. */
    public void setDualToneAmplitudes(double amp1Pct, double amp2Pct) {
        SignalGenerator g = generator;
        if (g != null) g.setDualToneAmplitudes(amp1Pct, amp2Pct);
    }

    /** Live-applies a new amplitude (V RMS) to the running generator.  No-op if not running. */
    public void setAmplitudeVrms(double vrms) {
        SignalGenerator g = generator;
        if (g != null) g.setAmplitudeVrms(vrms);
    }

    /** Recomputes the running generator's amplitude scale against the current DAC
     *  full-scale (the cached requested Vrms is unchanged).  No-op if not running.
     *  Driven by the DAC-calibration binding so a full-scale change takes effect live. */
    public void setDacFsVoltageRms(double v) {
        SignalGenerator g = generator;
        if (g != null) g.setDacFsVoltageRms(v);
    }

    /** Live-applies sweep start frequency (Hz). */
    public void setSweepFreqStart(double hz) {
        SignalGenerator g = generator;
        if (g != null) g.setSweepFreqStart(hz);
    }

    /** Live-applies sweep stop frequency (Hz). */
    public void setSweepFreqEnd(double hz) {
        SignalGenerator g = generator;
        if (g != null) g.setSweepFreqEnd(hz);
    }

    /** Live-applies sweep duration (seconds) by converting to samples
     *  via the current sample rate. */
    public void setSweepDurationSeconds(double seconds) {
        SignalGenerator g = generator;
        if (g == null || !Double.isFinite(seconds) || seconds <= 0) return;
        int rate = Preferences.instance().current().getOutputSampleRate();
        int samples = Math.max(2, (int) Math.round(seconds * rate));
        g.setSweepDurationSamples(samples);
    }

    /** Live-applies sweep fade-in length (seconds). */
    public void setSweepFadeInSeconds(double seconds) {
        SignalGenerator g = generator;
        if (g == null || !Double.isFinite(seconds) || seconds < 0) return;
        int rate = Preferences.instance().current().getOutputSampleRate();
        g.setSweepFadeInSamples(Math.max(0, (int) Math.round(seconds * rate)));
    }

    /** Live-applies sweep fade-out length (seconds). */
    public void setSweepFadeOutSeconds(double seconds) {
        SignalGenerator g = generator;
        if (g == null || !Double.isFinite(seconds) || seconds < 0) return;
        int rate = Preferences.instance().current().getOutputSampleRate();
        g.setSweepFadeOutSamples(Math.max(0, (int) Math.round(seconds * rate)));
    }

    /** Live-applies the sweep loop flag. */
    public void setSweepLoop(boolean loop) {
        SignalGenerator g = generator;
        if (g != null) g.setSweepLoop(loop);
    }

    /** True when the running generator can accept live form updates for the given target. */
    public boolean canLiveSwitchForm(GenSignalForm target) {
        if (target == null || generator == null) return false;
        if (target == GenSignalForm.LINEAR_SWEEP || target == GenSignalForm.LOG_SWEEP) return false;
        if (target == GenSignalForm.SINE_COMPENSATED) return false;
        return true;
    }

    /** Stops and immediately restarts the playback so a not-live-swappable
     *  change takes effect.  On failure {@link #isRunning()} turns false
     *  and {@link #getLastStartError()} carries the reason. */
    public synchronized void restart() {
        stop();
        start();
    }

    /** Stops both engines — used when the FreqResp sweep claims the DAC
     *  and on shutdown. */
    public synchronized void stopEngines() {
        stop();
        filePlayer.stop();
    }

    /** True while either engine drives the output — the
     *  {@link Events#GENERATOR_RUNNING} responder, read by the FFT worker's
     *  fundamental anchoring. */
    public boolean isProducingSignal() {
        return running || filePlayer.isRunning();
    }

    // -------------------------------------------------------------------------
    // File playback (shares the output device with the DDS tone)
    // -------------------------------------------------------------------------

    /** Starts WAV/FLAC file playback, stopping the DDS tone first.  Check
     *  {@link #isFilePlaying()} and {@link #getFilePlayError()} after. */
    public synchronized void startFilePlayback(File file, boolean loop) {
        stop();
        filePlayer.start(file, loop);
    }

    public void stopFilePlayback() {
        filePlayer.stop();
    }

    public boolean isFilePlaying() {
        return filePlayer.isRunning();
    }

    public String getFilePlayError() {
        return filePlayer.getLastStartError();
    }

    // -------------------------------------------------------------------------
    // Signal math (FFT-bin snap, period samples)
    // -------------------------------------------------------------------------

    /** The frequency the primary DDS should emit for the current prefs —
     *  the bin-snapped value when snap-to-FFT-bin applies (SINE/DUAL_TONE),
     *  else the raw entered value.  Mirrors {@link #start()}'s own
     *  resolution; also feeds the pane's bracket label. */
    public double effectiveFrequency() {
        Preferences prefs = Preferences.instance();
        GenSignalForm form = prefs.getGenSignalForm();
        double raw = (form == GenSignalForm.DUAL_TONE)
                ? prefs.getGenDualToneFreq1Hz()
                : prefs.getGenFrequencyHz();
        return FftBinSnap.snapIfEnabled(prefs, form,
                prefs.current().getOutputSampleRate(), raw);
    }

    /** Samples in one waveform period at the current rate + entered
     *  frequency; always ≥ 2 so duty-cycle math has something to work
     *  with.  Feeds the pane's duty bracket label. */
    public int periodSamples() {
        Preferences prefs = Preferences.instance();
        int sr = prefs.current().getOutputSampleRate();
        double f = prefs.getGenFrequencyHz();
        if (f <= 0.0 || sr <= 0) return 2;
        return Math.max(2, (int) Math.round(sr / f));
    }

    /** Closest frequency the DDS rectangle can produce with an
     *  integer-sample period — the pane's Frequency bracket label. */
    public double correctedRectangleHz() {
        int sr = Preferences.instance().current().getOutputSampleRate();
        return (double) sr / periodSamples();
    }

    private double snapDualTone(double rawHz) {
        Preferences prefs = Preferences.instance();
        return FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                prefs.current().getOutputSampleRate(), rawHz);
    }

    /** Re-applies the FFT-bin snap to the running tone(s) — fired on a
     *  snap toggle and on FFT-length changes. */
    private void reapplySnap() {
        setFrequency(effectiveFrequency());
        Preferences prefs = Preferences.instance();
        if (prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE) {
            setDualToneFrequency2(snapDualTone(prefs.getGenDualToneFreq2Hz()));
        }
    }

    /** Waveform pref change: live-swap when the generator supports it,
     *  else a full stop+start — sweep and dual-tone set up dedicated DDS
     *  state (second accumulator, sweep state machine) that
     *  {@link #setForm} can't hot-swap. */
    private void onFormChanged(GenSignalForm f) {
        boolean needsRestart = requiresRestart(lastForm) || requiresRestart(f);
        lastForm = f;
        if (needsRestart && running) {
            restart();
        } else {
            setForm(f);
        }
        publishSignalChanged();
    }

    private boolean requiresRestart(GenSignalForm f) {
        return f == GenSignalForm.LINEAR_SWEEP || f == GenSignalForm.LOG_SWEEP
                || f == GenSignalForm.DUAL_TONE;
    }

    // -------------------------------------------------------------------------
    // Signal file export
    // -------------------------------------------------------------------------

    /** Renders the current generator settings to a WAV / FLAC / AIFF file
     *  (extension picks the format).  Returns {@code null} on success,
     *  else a human-readable failure reason — the same contract as
     *  {@link #start()}'s {@code lastStartError}. */
    public String exportSignal(String path) {
        Preferences prefs = Preferences.instance();
        GenSignalForm form = prefs.getGenSignalForm();
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
            return "Sweep waveforms cannot be exported to a file.";
        }
        int    sampleRate    = prefs.current().getOutputSampleRate();
        int    bitDepth      = prefs.current().getOutputBitDepth();
        int    ditherBits    = prefs.getGenDitherBits();
        double frequency     = prefs.getGenFrequencyHz();
        double amplitudeVRms = prefs.getGenAmplitudeVrms();
        double duration      = prefs.getGenWavDurationSeconds();
        try {
            SignalGenerator gen;
            if (form == GenSignalForm.SINE_COMPENSATED) {
                String csv = prefs.getGenCorrectionsCsv();
                if (csv == null || csv.isEmpty()) {
                    return "Sine (compensated) needs a harmonics-correction CSV.  Pick one with the … button.";
                }
                gen = new SignalGenerator(frequency, sampleRate, amplitudeVRms,
                        prefs.getDacFsVoltageRms(), csv);
            } else {
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms,
                        prefs.getDacFsVoltageRms());
            }
            gen.setRectangleDuty(prefs.getGenRectangleDuty());
            // Periodic forms truncate to an integer-period count; noise has
            // no period — 0 makes the exporter use the raw duration.
            double freqForTruncation = form.isPeriodic() ? frequency : 0.0;
            long bytes = SignalFileExporter.export(gen, new File(path),
                    sampleRate, bitDepth, duration, ditherBits, freqForTruncation);
            log.info("File saved: {} ({} bytes)", path, bytes);
            return null;
        } catch (Exception ex) {
            log.warn("Save failed", ex);
            return ex.getMessage();
        }
    }

    private DeviceRef findOutputDevice(String name) {
        List<DeviceRef> devices = AudioBackend.instance().listOutputDevices();
        for (DeviceRef d : devices) {
            if (name.equals(d.name())) return d;
        }
        return null;
    }
}
