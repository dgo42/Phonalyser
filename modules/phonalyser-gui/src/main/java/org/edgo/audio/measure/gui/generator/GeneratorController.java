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

package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.FilePlaybackGenerator;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.GeneratorLane;
import org.edgo.audio.measure.gui.sound.GeneratorRun;
import org.edgo.audio.measure.gui.sound.PlaybackStateEnum;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.RemoteGenerator;
import org.edgo.audio.measure.wav.PcmFileLoader;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Controller of the generator pane - the UI's side of the generator and
 * nothing more: the bidi preference bindings that live-apply the operator's
 * edits, the bus events in and out, and the one boundary where a
 * {@link PlaybackStateEnum} becomes operator language.
 *
 * <p>Everything between "the operator wants a tone" and the hardware emitting
 * it lives in the {@link GeneratorLane} - one implementation for a local DDS
 * and a bench-side one, with the playback lane and the line underneath it.
 * This controller wraps its start/stop/restart, publishes
 * {@link Events#GENERATOR_SIGNAL_CHANGED} on the transitions the analyzers
 * restart their accumulators on, and answers the pane's state getters by
 * delegation.
 */
@Log4j2
public final class GeneratorController {

    /** The exported-file amplitude convention (CEA-2006 / IEC 61938): 0 dBFS
     *  is a 2 Vrms full-scale sine.  A file carries the SIGNAL, never this
     *  machine's DAC calibration - a saved sine read back with unequal
     *  channels when the export mirrored the per-lane DAC ratio - so it
     *  plays correctly on ANY device.  Peak volts: 2 Vrms · √2. */
    private static final double EXPORT_FS_VOLTAGE_AMPL = 2.0 * Constants.SQRT2;
    /** How often the remote file watcher asks the bench's pushed state whether
     *  the file is still playing.  The bench pushes on every change, so this only
     *  bounds how late an end-of-file reaches the indicator - a tenth of a second
     *  is below what an operator perceives, at four polls a second of nothing. */
    private static final long REMOTE_FILE_POLL_MS = 100;

    /** The generator itself - local or remote, resolved per call inside. */
    private final GeneratorLane lane = new GeneratorLane();

    /** The last start failure in OPERATOR language, or null - derived from the
     *  lane's machine-readable state at the one wording boundary,
     *  {@link #localize}. */
    @Getter
    private volatile String lastStartError;
    /** Detach actions for every Preferences / bus subscription made in the
     *  constructor; run by {@link #shutdown()}. */
    private final List<Runnable> unsubscribes = new ArrayList<>();

    // ----- file playback (shares the output device with the DDS tone) -----

    private volatile Thread        playThread;
    /** Per-session stop flag - a fresh instance per start so a stop request
     *  to a previous (possibly join-timed-out) play thread can never be
     *  revoked by the next session's reset. */
    private volatile AtomicBoolean filePlayStopFlag = new AtomicBoolean(false);
    private volatile boolean       filePlayRunning;
    /** Live-updates the loop flag (bound to its preference).  Picked up at
     *  the next EOF check by the play thread. */
    private volatile boolean       filePlayLoop;
    /** The source of the RUNNING file playback, or null - kept so the loop
     *  preference can reach the lap that is already playing. */
    private volatile FilePlaybackGenerator filePlaySource;
    @Getter
    private volatile String        filePlayError;
    /** True once the BENCH has confirmed a file playing, so the remote watcher
     *  does not read the gap between the command and the first pushed state as
     *  an end-of-file.  Remote path only. */
    private volatile boolean       remoteFileSeenPlaying;

    public GeneratorController() {
        bindPreferences(Preferences.instance());
        bindBus();
    }

    /** Engine-driving preference subscriptions.  Each live-applies to the
     *  lane and publishes {@link Events#GENERATOR_SIGNAL_CHANGED} exactly once
     *  where the emitted signal changes (the FFT averaging restart hangs off
     *  that event). */
    private void bindPreferences(Preferences prefs) {
        onPref(prefs.genFrequencyHzProperty(), v -> {
            // The COMMANDED value: what the operator just typed, resolved onto
            // the grid.  Reading it back from effectiveFrequency() would feed a
            // remote bench its own last emitted frequency as a new nominal.
            lane.setFrequency(lane.commandedFrequency());
            publishSignalChanged();
        });
        onPref(prefs.genDualToneFreq1HzProperty(), v -> {
            lane.setFrequency(lane.snapDualTone(v));
            publishSignalChanged();
        });
        onPref(prefs.genDualToneFreq2HzProperty(), v -> {
            lane.setDualToneFrequency2(lane.snapDualTone(v));
            publishSignalChanged();
        });
        onPref(prefs.genSnapToFftBinProperty(), v -> {
            lane.reapplySnap();
            publishSignalChanged();
        });
        onPref(prefs.genSignalFormProperty(), f -> {
            // The run is derived HERE, from the settings as they now stand, so a
            // form change that needs a restart plays the NEW waveform - the run
            // carries the form, and the lane's stored one is the old session.
            lane.formChanged(f, buildRun());
            publishSignalChanged();
        });
        // Loading / clearing a .dpd must take effect now: restart the running
        // generator when the file for the ACTIVE compensated form changes (the
        // build reads the new path).  Each slot only restarts its own form.
        onPref(prefs.genDpdProperty(),     v -> lane.dpdChanged(GenSignalForm.SINE_COMP));
        onPref(prefs.genDpdDualProperty(), v -> lane.dpdChanged(GenSignalForm.DUAL_TONE_COMP));
        onPref(prefs.genAmplitudeVrmsProperty(), v -> {
            lane.setAmplitudeVrms(v);
            publishSignalChanged();
        });
        onPref(prefs.dacFsVoltageAmplProperty(), v -> {
            // Left full-scale drives the generator's mono amplitude AND the
            // per-lane ratio (scaleR = fsLeft/fsRight), so re-push both.
            lane.setDacFsVoltageAmpl(v);
            lane.pushOutputRouting();
            publishSignalChanged();
        });
        onPref(prefs.dacFsVoltageAmplRightProperty(), v -> {
            lane.pushOutputRouting();
            publishSignalChanged();
        });
        onPref(prefs.genOutputChannelsProperty(), v -> {
            lane.pushOutputRouting();
            publishSignalChanged();
        });
        onPref(prefs.genRectangleDutyProperty(), v -> {
            lane.setRectangleDuty(v);
            publishSignalChanged();
        });
        onPref(prefs.genTriangleDutyProperty(), v -> {
            lane.setTriangleDuty(v);
            publishSignalChanged();
        });
        onPref(prefs.genDualToneSplitPctProperty(), a1 -> {
            lane.setDualToneAmplitudes(a1, 100.0 - a1);
            publishSignalChanged();
        });
        onPref(prefs.genDitherBitsProperty(), this::setDitherBits);
        onPref(prefs.genSweepFreqStartHzProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepFreqStart(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFreqEndHzProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepFreqEnd(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepDurationSecProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepDurationSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFadeInSecProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepFadeInSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepFadeOutSecProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepFadeOutSeconds(v);
            publishSignalChanged();
        });
        onPref(prefs.genSweepLoopProperty(), v -> {
            if (!lane.restartFarinaOnParamChange()) lane.setSweepLoop(v);
            publishSignalChanged();
        });
        onPref(prefs.genPlayFromLoopProperty(), v -> {
            filePlayLoop = v;
            // Forward to the RUNNING source too - its loop flag is re-read at
            // every end of stream, so the toggle takes effect on the next lap
            // (clearing it during play kept looping otherwise - the field
            // alone only reaches the NEXT start).
            FilePlaybackGenerator playing = filePlaySource;
            if (playing != null) {
                playing.setLoop(v);
            }
            // The same toggle when the file is playing on a BENCH: the far end
            // re-reads its own flag at each end of stream, so this reaches the
            // lap that is ending exactly as the local setter above does.
            RemoteGenerator remote = AudioBackend.instance().remoteGenerator();
            if (remote != null && filePlayRunning) {
                remote.setFileLoop(v);
            }
        });
    }

    /** Bus subscriptions that drive the engines. */
    private void bindBus() {
        MessageBus bus = MessageBus.instance();
        // FLL trim: the FFT view publishes the corrected DDS frequency after
        // each result; live-apply and republish as FLL_TRIM so the FFT
        // worker keeps its averaging accumulator.
        Consumer<Double> freqTrim = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                log.warn("FLL apply t1: {} Hz (generator running={})",
                        String.format(Locale.US, "%.6f", newHz), lane.isRunning());
            }
            lane.trimFrequency(newHz);
            bus.publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        onBus(Events.GENERATOR_FREQ_TRIM, freqTrim);
        // Companion for the dual-tone second-tone FLL.
        Consumer<Double> freqTrim2 = newHz -> {
            if (newHz == null || !Double.isFinite(newHz)) return;
            if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                log.warn("FLL apply t2: {} Hz (generator running={})",
                        String.format(Locale.US, "%.6f", newHz), lane.isRunning());
            }
            lane.trimFrequency2(newHz);
            bus.publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.FLL_TRIM);
        };
        onBus(Events.GENERATOR_FREQ_TRIM_2, freqTrim2);
        // FLL reset: drop any residual trim - slide the running tone(s)
        // back onto the configured (snapped) frequencies.  reapplySnap
        // publishes no signal-changed event itself, so no feedback loop.
        Consumer<Void> freqTrimReset = ignored -> {
            if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                log.warn("FLL trim reset: re-applying snap targets (generator running={})",
                        lane.isRunning());
            }
            lane.reapplySnap();
        };
        onBus(Events.GENERATOR_FREQ_TRIM_RESET, freqTrimReset);
        // FFT length changed: slide the running tone(s) onto the new bin
        // grid without the user having to toggle the snap checkbox.
        Consumer<Void> fftLength = ignored -> {
            if (Preferences.instance().isGenSnapToFftBin()) lane.reapplySnap();
        };
        onBus(Events.FFT_LENGTH_CHANGED, fftLength);
        // The FreqResp sweep needs the DAC exclusively - stop both engines.
        Consumer<Void> freqRespStarted = ignored -> stopEngines();
        onBus(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStarted);
        // "Is the generator running?" - read by the FFT worker to decide
        // whether to anchor the fundamental to the generator's frequency.
        bus.registerResponder(Events.GENERATOR_RUNNING,
                (Supplier<Boolean>) this::isProducingSignal);
        unsubscribes.add(() -> bus.unregisterResponder(Events.GENERATOR_RUNNING));
        // "At which frequencies is the generator emitting?" - read by the FFT's
        // fundamental hints and clock readouts and by the scope's beat
        // reconstruction and dual-tone comb seeds, all of which need the tones on
        // the wire and not the ones this process would have computed: a remote
        // bench snaps against ITS rate and applies the FLL trims itself.
        bus.registerResponder(Events.GENERATOR_EMITTED_HZ,
                (Function<Integer, double[]>) lane::emittedHz);
        unsubscribes.add(() -> bus.unregisterResponder(Events.GENERATOR_EMITTED_HZ));
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
     *  engines - called from the pane's dispose listener. */
    public void shutdown() {
        for (Runnable r : unsubscribes) r.run();
        unsubscribes.clear();
        stopEngines();
    }

    // -------------------------------------------------------------------------
    // The lane, wrapped - plus the one wording boundary
    // -------------------------------------------------------------------------

    /** Starts the tone - see {@link GeneratorLane#start(GeneratorRun)}.  On
     *  failure {@link #isRunning()} answers false and
     *  {@link #getLastStartError()} carries the operator-language reason. */
    public void start() {
        // DDS tone and file playback share the output device - only one of
        // them may drive it at a time (the exclusivity the lane's start used
        // to enforce before the file player merged up here).
        stopFilePlayback();
        PlaybackStateEnum state = lane.start(buildRun());
        lastStartError = localize(state);
        if (state == PlaybackStateEnum.STARTED) {
            // Starting IS a signal change: what the ADC sees goes from whatever
            // was there to the generated tone, so the scope's running statistics
            // and amplitude distribution, and the FFT's accumulator, must
            // restart rather than average the two together.
            publishSignalChanged();
        }
    }

    /**
     * The preferences -> run derivation: THIS is where the current generator
     * settings become the one value the lane runs (the derivation the lane
     * used to do itself - the lane takes parameters instead, so a sweep
     * engine can drive the same lane with its own).  Built fresh per start,
     * so a restart after a settings change plays the changed settings.
     */
    private GeneratorRun buildRun() {
        Preferences prefs = Preferences.instance();
        BackendPrefs bp = prefs.current();
        int sampleRate = bp.getOutputSampleRate();
        GenSignalForm form = prefs.getGenSignalForm();
        GeneratorRun.SweepSpec sweep = null;
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
            // No lead-in for the GUI-driven sweep - that's a CLI-measurement
            // feature, not a music-style sweep control.
            sweep = new GeneratorRun.SweepSpec(
                    prefs.getGenSweepFreqStartHz(),
                    prefs.getGenSweepFreqEndHz(),
                    Math.max(2, (int) Math.round(prefs.getGenSweepDurationSec() * sampleRate)),
                    0,
                    Math.max(0, (int) Math.round(prefs.getGenSweepFadeInSec() * sampleRate)),
                    Math.max(0, (int) Math.round(prefs.getGenSweepFadeOutSec() * sampleRate)),
                    prefs.isGenSweepLoop());
        }
        return new GeneratorRun(form,
                lane.resolveEmitFrequency(form, sampleRate),
                prefs.getGenAmplitudeVrms(),
                sampleRate,
                bp.getOutputBitDepth(),
                prefs.getGenDitherBits(),
                prefs.getDacFsVoltageAmpl(),
                prefs.dacRightLaneScale(),
                prefs.getGenOutputChannels(),
                false,   // the pane's tone ADAPTS to a bench's granted rate
                true,    // ...and pushes THIS machine's DAC calibration to it
                sweep);
    }

    /** Stops the tone - see {@link GeneratorLane#stop()}. */
    public void stop() {
        // The signal really went away: only a real transition restarts the
        // analyzers' accumulators - the failed-start paths stop with nothing
        // running.
        if (lane.stop()) {
            publishSignalChanged();
        }
    }

    /** Stop + start so a not-live-swappable change takes effect.  On failure
     *  {@link #isRunning()} turns false and {@link #getLastStartError()}
     *  carries the reason. */
    public void restart() {
        stop();
        start();
    }

    /** Stops both engines - used when the FreqResp sweep claims the DAC
     *  and on shutdown. */
    public void stopEngines() {
        boolean toneWasRunning = lane.stop();
        stopFilePlayback();
        if (toneWasRunning) {
            publishSignalChanged();
        }
    }

    /** The one boundary where a start state becomes operator language - the
     *  panes read {@link #getLastStartError()}; everything below them deals in
     *  {@link PlaybackStateEnum} alone. */
    private String localize(PlaybackStateEnum state) {
        if (state == PlaybackStateEnum.PARKED || state == PlaybackStateEnum.STARTED) {
            return null;   // not messages
        }
        if (state == PlaybackStateEnum.NO_STREAM_START) {
            return I18n.t(state.i18nKey(), GeneratorLane.READY_TIMEOUT_S);
        }
        Preferences prefs = Preferences.instance();
        if (state == PlaybackStateEnum.DEVICE_UNAVAILABLE) {
            return I18n.t(state.i18nKey(), prefs.current().getOutputDeviceName());
        }
        if (state == PlaybackStateEnum.PREDISTORTION_FILE_MISSING) {
            // The PATH, the same way the unavailable device names itself: the
            // operator can only fix a file they are told the name of, and
            // "could not build the signal generator" names nothing.
            return I18n.t(state.i18nKey(), prefs.getGenDpd(prefs.getGenSignalForm()));
        }
        // The keys that carry a {0} get the REASON, localized - never the raw
        // driver detail, which stays in the log: a code like "-9996" says the
        // operator nothing, while "the output device does not answer" is what
        // they can act on.  The backend that owned the error classified it;
        // this is only where it becomes a sentence.
        return I18n.t(state.i18nKey(), failureDetail());
    }

    /**
     * The failure reason as one sentence - and, for a device somebody else is
     * measuring on, WHO.
     *
     * <p>"The device is in use by another application" is true and useless on a
     * bench: the operator's next move is to ask that client to let go, and they
     * cannot ask until they are told which one it is.  The holder is the same
     * fact the device combos already show for a locked bench device (spec 4.3
     * publishes it with every device), read through the same seam and rendered
     * with the same wording, so the two places can never disagree about what a
     * held device reads like.
     *
     * <p>A local device in use has no holder to name - the operating system does
     * not say which application took it - and falls back to the plain reason,
     * as does a bench that named none.
     */
    private String failureDetail() {
        DeviceFailureReason reason = lane.getLastFailureReason();
        String plain = I18n.t(reason.i18nKey());
        if (reason != DeviceFailureReason.DEVICE_IN_USE) {
            return plain;
        }
        DeviceRef device = AudioBackend.instance().getActiveOutputDevice();
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        String by = (device == null || remote == null) ? null : remote.lockedBy(device);
        return by == null ? plain
                : I18n.t("preferences.device.lockedBy", device.displayName(), by);
    }

    /** How the last start attempt ended, machine-readably - for callers that
     *  decide whether starting again can help at all. */
    public PlaybackStateEnum getLastStartState() {
        return lane.getLastStartState();
    }

    // -------------------------------------------------------------------------
    // State getters + live-apply delegates the panes and wizards use
    // -------------------------------------------------------------------------

    public boolean isRunning() {
        return lane.isRunning();
    }

    /** Claims the local playback lane's from-below end (device unplugged,
     *  driver failure) for the ONE operator report - {@code null} while
     *  healthy, after a commanded stop, and for every caller after the
     *  first.  The pane polls this on its visual sync points. */
    public Throwable takePlaybackEndedFromBelow() {
        return lane.takePlaybackEndedFromBelow();
    }

    /** True while either engine drives the output - the
     *  {@link Events#GENERATOR_RUNNING} responder, read by the FFT worker's
     *  fundamental anchoring. */
    public boolean isProducingSignal() {
        return lane.isRunning() || isFilePlaying();
    }

    public double effectiveFrequency() {
        return lane.effectiveFrequency();
    }

    public double effectiveFrequency2() {
        return lane.effectiveFrequency2();
    }

    public int periodSamples() {
        return lane.periodSamples();
    }

    public double correctedPeriodAlignedHz() {
        return lane.correctedPeriodAlignedHz();
    }

    /** The capture rate the FFT bin grid is built on - what the pane's
     *  snap brackets are computed against, so a label and the emitted tone
     *  are read off the same grid. */
    public int analysisSampleRate() {
        return lane.analysisRateHz();
    }

    public double maxAmplitudeVrms(GenSignalForm form) {
        return lane.maxAmplitudeVrms(form);
    }

    public boolean canLiveSwitchForm(GenSignalForm target) {
        return lane.canLiveSwitchForm(target);
    }

    /** Live-applies the dither bit count, then signals a generator change so
     *  the FFT stats/accumulator and the scope persistence restart on the new
     *  signal. */
    public void setDitherBits(double bits) {
        lane.setDitherBits(bits);
        publishSignalChanged();
    }

    public void setRectangleDuty(double dutyFrac) {
        lane.setRectangleDuty(dutyFrac);
    }

    public void setTriangleDuty(double dutyFrac) {
        lane.setTriangleDuty(dutyFrac);
    }

    public void setForm(GenSignalForm form) {
        lane.setForm(form);
    }

    public void setFrequency(double hz) {
        lane.setFrequency(hz);
    }

    public void trimFrequency(double hz) {
        lane.trimFrequency(hz);
    }

    public void trimFrequency2(double hz) {
        lane.trimFrequency2(hz);
    }

    public void applyCompensation(double[] ampRatios, int[] hNums, double[] phiInits) {
        lane.applyCompensation(ampRatios, hNums, phiInits);
    }

    public void applyDualToneCompensation(double[] ampRatios, int[] coefA, int[] coefB,
            double[] phiInits) {
        lane.applyDualToneCompensation(ampRatios, coefA, coefB, phiInits);
    }

    public void clearCompensation() {
        lane.clearCompensation();
    }

    public void loadCorrectionsFromFile(String path) {
        lane.loadCorrectionsFromFile(path);
    }

    public void setDualToneFrequency2(double hz) {
        lane.setDualToneFrequency2(hz);
    }

    public void setDualToneAmplitudes(double amp1Pct, double amp2Pct) {
        lane.setDualToneAmplitudes(amp1Pct, amp2Pct);
    }

    public void setAmplitudeVrms(double vrms) {
        lane.setAmplitudeVrms(vrms);
    }

    // -------------------------------------------------------------------------
    // File playback (shares the output device with the DDS tone).
    // Stream-based player for the pane's "Play from..." row: decodes the chosen
    // file via javax.sound.sampled.AudioSystem (WAV / AIFF built-in; FLAC via
    // jflac-codec's SPI) and pushes the decoded PCM to the ACTIVE output
    // device.  Looping reopens the stream from the original file on EOF.
    // A monitoring convenience (verify a saved file sounds right), not
    // measurement-grade output.
    // -------------------------------------------------------------------------

    /**
     * Starts WAV/FLAC file playback, stopping the DDS tone first.  Spawns a
     * daemon thread that decodes {@code file} on the ACTIVE output device
     * (resolved by the backend when the play thread opens it).  No-op if
     * already running.  On failure {@link #isFilePlaying()} returns
     * {@code false} and {@link #getFilePlayError()} carries the reason.
     */
    public synchronized void startFilePlayback(File file, boolean loop) {
        if (filePlayRunning) return;
        lane.stop();
        filePlayError = null;
        Thread old = playThread;
        if (old != null && old.isAlive()) {
            filePlayError = I18n.t("generator.error.shuttingDown");
            return;
        }
        if (file == null || !file.isFile()) {
            filePlayError = I18n.t("generator.error.playFile.pickFirst");
            return;
        }
        this.filePlayLoop = loop;
        // A backend whose generator runs somewhere else has no downlink for audio
        // (net protocol §7: there is no client->server PCM in v1), so the file
        // travels the other way - up to the bench once, and the far end's own
        // lane renders it.  Everything the pane reads is kept exactly as the
        // local path keeps it; only who moves the samples differs.
        RemoteGenerator remote = AudioBackend.instance().remoteGenerator();
        if (remote != null) {
            startRemoteFilePlayback(remote, file);
            return;
        }
        AtomicBoolean sessionStop = new AtomicBoolean(false);
        filePlayStopFlag = sessionStop;
        Thread t = new Thread(() -> playLoop(file, sessionStop), "file-play");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        playThread = t;
        filePlayRunning = true;
        t.start();
    }

    /**
     * The remote half of {@link #startFilePlayback}: the file goes up to the
     * bench and its own generator lane plays it.
     *
     * <p>Everything the pane reads is kept exactly as the local path keeps it -
     * {@code filePlayRunning} true while the bench says it is playing,
     * {@code filePlayError} carrying a refusal in the operator's language.  A
     * watcher thread stands in for the local play loop: it polls the pushed file
     * state and clears the running flag when a non-looping file runs out, which
     * is the remote twin of {@code playLoop} simply returning.
     */
    private void startRemoteFilePlayback(RemoteGenerator remote, File file) {
        AtomicBoolean sessionStop = new AtomicBoolean(false);
        filePlayStopFlag = sessionStop;
        remoteFileSeenPlaying = false;
        // The UI state is set HERE, before the thread starts, exactly as the local
        // branch sets it before starting its play loop: the caller is the SWT
        // thread and it must return in microseconds.  Reading the file and pushing
        // it up the wire can take minutes on a slow link, so neither may happen
        // under this object's monitor - a Stop is a click on that same thread and
        // has to be dispatchable while the bytes are still moving.
        filePlayRunning = true;
        Thread t = new Thread(() -> remoteFilePlayLoop(remote, file, sessionStop),
                "file-play-remote");
        t.setDaemon(true);
        playThread = t;
        t.start();
    }

    /**
     * The whole remote session on its own thread: read, upload, command, watch.
     *
     * <p><b>This thread owns every command that reaches the bench.</b>  A stop is
     * a flag, never a command sent from the caller - which is what makes the
     * ordering safe. Were the stop to command the bench directly it could overtake
     * an upload still in flight, and the {@code gen.playFile} landing afterwards
     * would leave the far end playing a file this client had already forgotten.
     * Here the flag is read at each seam, and the one place that can know the
     * bench has the file is the line after the command returned.
     */
    private void remoteFilePlayLoop(RemoteGenerator remote, File file,
            AtomicBoolean sessionStop) {
        boolean commanded = false;
        try {
            // The notice covers the whole PREPARE-AND-UPLOAD phase, not the
            // transfer alone.  On a LAN the transfer is a blink, while opening an
            // exclusive device on the bench takes seconds and reading a large file
            // takes more - so a notice that spanned only the HTTP put appeared and
            // vanished after the wait it was meant to explain, leaving the operator
            // with five seconds of nothing.  It comes down the moment playback is
            // commanded and never covers playback itself.
            MessageBus.instance().publish(Events.FILE_UPLOAD_STARTED);
            try {
                // gen.playFile names a lane that must already exist, and only the
                // TONE's start ever opened one - so a file played without first
                // starting the tone was refused before a byte left this machine,
                // so Play-from silently did nothing.  The lane owns the open
                // policy, so it is asked to make sure there is a session, with
                // the same run the tone would have opened with.
                PlaybackStateEnum opened = lane.ensureRemoteOpen(buildRun());
                if (opened != PlaybackStateEnum.STARTED) {
                    filePlayError = localize(opened);
                    return;
                }
                byte[] content = Files.readAllBytes(file.toPath());
                if (sessionStop.get()) {
                    return;             // stopped while reading: nothing was sent
                }
                // The type comes from the decoder authority, so the bench is told
                // what this file IS by the very rule that would pick the decoder
                // here.
                //
                // The VOLATILE, read at command time - never the flag captured
                // when the button was clicked.  An upload can run for minutes,
                // and a loop ticked while the bytes were moving would otherwise
                // be lost at both ends: the captured parameter would win here,
                // and the live setFileLoop push would have reached a bench with
                // no file session yet, which ignores it by design.  This is the
                // same read the local twin makes in playLoop.
                boolean sent = filePlayLoop;
                remote.playFile(content, PcmFileLoader.instance().mimeTypeOf(file), sent);
                // The upload itself can run for minutes, and the flag read above
                // was fixed before it began.  A toggle made WHILE the bytes moved
                // reached a bench that had no file session yet, so its live push
                // was ignored by design - re-assert it now that the far end has
                // one, or the operator's last choice would be silently dropped.
                if (filePlayLoop != sent) {
                    remote.setFileLoop(filePlayLoop);
                }
                commanded = true;
            } finally {
                // Every exit of the block above takes the notice down: a refused
                // open, a read that failed, a stop while reading, a refused
                // upload, and the success that follows it.
                MessageBus.instance().publish(Events.FILE_UPLOAD_FINISHED);
            }
            // OUTSIDE the notice: the file is playing now, and a "being uploaded"
            // shell must not sit over it for the length of the track.
            watchRemoteFile(remote, sessionStop);
        } catch (RemoteGenerator.FileTooLargeException e) {
            filePlayError = I18n.t("generator.error.playFile.tooLargeForBench",
                    file.getName(), megabytes(e.getBytes()), megabytes(e.getLimitBytes()));
        } catch (IOException | OutOfMemoryError e) {
            log.warn("File playback: cannot read {}: {}", file, e.toString());
            filePlayError = I18n.t("generator.error.playFile");
        } catch (RuntimeException e) {
            // The bench's own words stay in the log; the operator is told in
            // theirs, with the classified reason when the backend gave one.
            log.warn("File playback on the bench failed: {}", e.toString(), e);
            filePlayError = benchFileError(e);
        } finally {
            if (commanded && sessionStop.get()) {
                // The operator stopped while the file was on its way, or while it
                // played: the bench has it, so the bench must be told.  Sent from
                // here and nowhere else, so it can never precede the play.
                try {
                    remote.stopFile();
                } catch (RuntimeException e) {
                    log.warn("Stopping the bench's file playback failed: {}", e.toString());
                }
            }
            filePlayRunning = false;
            // The same signal the LOCAL play loop ends on - one mechanism, both
            // engines.  Without it a remote failure had nowhere to surface: the
            // pane's one-shot check after startFilePlayback races this thread and
            // usually wins, and the error died in a log line.
            publishFilePlayStopped();
        }
    }

    /**
     * Claims the file-play failure for the ONE operator report, clearing it.
     *
     * <p>A file session can fail two ways and both must reach a dialog exactly
     * once: synchronously, before {@code startFilePlayback} returns (a missing
     * file), or asynchronously on the play thread minutes later (a bench that
     * refused, a device that died).  The pane checks after the call AND on
     * {@link Events#FILE_PLAY_STOPPED}; whichever arrives first takes the
     * message, and the other finds nothing - so a re-sync cannot repeat a dialog
     * the operator has already dismissed.
     */
    public synchronized String takeFilePlayErrorForReport() {
        String claimed = filePlayError;
        filePlayError = null;
        return claimed;
    }

    /** Publishes {@link Events#FILE_PLAY_STOPPED} for a session that is ending,
     *  from the thread that ran it - the one signal the pane resets its LED and
     *  reports a late failure on.  Guarded on the CURRENT thread still being the
     *  play thread so a join-timed-out session finishing late cannot notify for
     *  a session started after it. */
    private void publishFilePlayStopped() {
        if (Thread.currentThread() != playThread) {
            return;
        }
        // Published straight from the play thread, which is the contract
        // Events.FILE_PLAY_STOPPED states: "subscribers must marshal to the UI
        // thread if they touch widgets" - and the pane's listener does.
        // Marshalling HERE instead would hand the event to GuiUtil, which is a
        // no-op until MainWindow has published its shell: a failure raised before
        // the window exists, or in any headless run, was simply discarded.
        MessageBus.instance().publish(Events.FILE_PLAY_STOPPED);
    }

    /** Polls the bench's pushed file state until it stops playing - the remote
     *  twin of the local play loop ending.  A lane that died from below reports
     *  through the same claim-once the tone uses. */
    private void watchRemoteFile(RemoteGenerator remote, AtomicBoolean sessionStop) {
        try {
            while (!sessionStop.get()) {
                Throwable died = remote.takeFileErrorForReport();
                if (died != null) {
                    log.warn("File playback on the bench ended from below: {}",
                            died.toString());
                    filePlayError = benchFileError(died);
                    break;
                }
                RemoteGenerator.FileState state = remote.fileState();
                if (state.playing()) {
                    remoteFileSeenPlaying = true;
                }
                // "Finished" is the end-of-file edge; a state that stopped
                // playing AFTER we saw it play is the same end reached the other
                // way.  Before the first playing push, neither reads as an end -
                // that gap is the command still in flight.
                if (state.finished() || (remoteFileSeenPlaying && !state.playing())) {
                    break;
                }
                Thread.sleep(REMOTE_FILE_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A bench-side file failure in the operator's language, carrying the
     * classified REASON when the backend supplied one.
     *
     * <p>The rest of this release established a six-reason vocabulary for a device
     * that will not play; a file refused because the DAC is in use must not read
     * the same as one refused because the bench's store is full.  The reason is
     * asked of the backend that owns the error, exactly as the tone path asks.
     */
    private String benchFileError(Throwable failure) {
        DeviceFailureReason reason = DeviceFailureReason.UNKNOWN;
        DeviceRef output = AudioBackend.instance().getActiveOutputDevice();
        if (output != null) {
            reason = AudioBackend.instance().playbackManager(output).classifyFailure(failure);
        }
        if (reason == DeviceFailureReason.UNKNOWN) {
            return I18n.t("generator.error.playFile");
        }
        return I18n.t("generator.error.playFile") + " - " + I18n.t(reason.i18nKey());
    }

    /** Megabytes to one decimal, so a 50.4 MB file and a 50 MB cap cannot both
     *  print "50" and make the refusal read as though the file fitted. */
    private String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /** Stops the play thread (idempotent).  Waits up to 2 s for it to exit.
     *
     *  <p>Never commands the bench itself: the remote play thread owns that, and
     *  sends {@code gen.stopFile} when it sees this flag - see
     *  {@link #remoteFilePlayLoop}. */
    public synchronized void stopFilePlayback() {
        filePlayStopFlag.set(true);
        remoteFileSeenPlaying = false;
        Thread t = playThread;
        if (t != null) {
            try { t.join(2_000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) {
                // Keep the reference so start refuses a second session while
                // this one is wedged; its session stop flag stays set.
                log.warn("File-play thread did not exit within 2 s - restart refused until it does.");
            } else {
                playThread = null;
            }
        }
        filePlayRunning = false;
    }

    public boolean isFilePlaying() {
        return filePlayRunning;
    }

    private void playLoop(File file, AtomicBoolean sessionStop) {
        FilePlaybackGenerator source = null;
        AudioPlayback playback = null;
        try {
            source = new FilePlaybackGenerator(file, filePlayLoop);
            filePlaySource = source;

            // The ACTIVE output device, resolved by the backend - the same
            // handle the DDS tone opens.  That is what makes file playback come
            // out of the SELECTED backend and device instead of always the
            // computer's sound card.
            DeviceRef device = AudioBackend.instance().getActiveOutputDevice();
            if (device == null) {
                filePlayError = I18n.t("generator.error.deviceUnavailable",
                        Preferences.instance().current().getOutputDeviceName());
                return;
            }
            // The caller's step after resolution: the DAC full scale lands on
            // the runtime scalars via the UI thread (prefs bindings are plain
            // UI-only listeners).
            GuiUtil.marshal(() -> Preferences.instance().applyDeviceProfile(device, false));

            // Played at the file's own rate and depth, so nothing is resampled -
            // the SPI form via playbackManager, since the format is the FILE's,
            // not the preferences'.  No dither: the samples are already
            // quantised, and re-dithering a finished recording would only add
            // noise.
            playback = AudioBackend.instance().playbackManager(device).openPlayback(
                    device, source.getFileSampleRate(), source.getFileBitDepth(), 0.0);
            playback.open();
            // The DEVICE path's half of the file convention (0 dBFS = 2 Vrms,
            // see EXPORT_FS_VOLTAGE_AMPL): the file carries the pure signal,
            // so the LINE applies this machine's DAC calibration - the
            // absolute full-scale mapping and the per-lane ratio; without it
            // file play emitted uncalibrated.  A DAC whose full
            // scale is below 2 Vrms gets k > 1 and full-scale content clips -
            // the honest cost of asking a small DAC for convention level.
            Preferences filePrefs = Preferences.instance();
            double k = EXPORT_FS_VOLTAGE_AMPL / filePrefs.getDacFsVoltageAmpl();
            playback.setChannelScale(k, k * filePrefs.dacRightLaneScale());
            log.info("File playback started: {} ({} Hz, {} bit{}) on {}",
                    file.getName(), source.getFileSampleRate(), source.getFileBitDepth(),
                    filePlayLoop ? ", looping" : "", device.name());

            // The backend pulls samples until the flag is raised; raise it
            // ourselves when a non-looping file runs out.
            FilePlaybackGenerator watched = source;
            Thread eof = new Thread(() -> {
                while (!sessionStop.get() && !watched.isFinished()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                sessionStop.set(true);
            }, "file-play-eof");
            eof.setDaemon(true);
            eof.start();

            playback.play(source, sessionStop, new CountDownLatch(1));
            log.info("File playback stopped: {}", file.getName());
        } catch (PcmFileLoader.TooLargeException ex) {
            log.warn("File playback failed: {}", ex.getMessage());
            filePlayError = I18n.t("generator.error.playFile.tooLarge",
                    file.getName(), ex.needMb, ex.freeMb);
        } catch (Exception ex) {
            log.warn("File playback failed: {}", ex.getMessage(), ex);
            filePlayError = ex.getMessage();
        } finally {
            Closeables.closeQuietly(playback);
            if (source != null) {
                if (filePlaySource == source) {
                    filePlaySource = null;
                }
                source.close();
            }
            // A join-timed-out session finishing late must not clear the
            // flag (or notify) for a session started after it.
            if (Thread.currentThread() == playThread) {
                filePlayRunning = false;
            }
            publishFilePlayStopped();
        }
    }

    // -------------------------------------------------------------------------
    // Signal file export
    // -------------------------------------------------------------------------

    /** Renders the current generator settings to a WAV / FLAC / AIFF file
     *  (extension picks the format).  Returns {@code null} on success,
     *  else a human-readable failure reason - the same contract as
     *  {@link #getLastStartError()}. */
    public String exportSignal(String path) {
        Preferences prefs = Preferences.instance();
        GenSignalForm form = prefs.getGenSignalForm();
        boolean isSweep = form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP;
        boolean sweepLoops = isSweep && prefs.isGenSweepLoop();
        double  sweepDurSec = isSweep ? prefs.getGenSweepDurationSec() : 0.0;
        int    sampleRate    = prefs.current().getOutputSampleRate();
        int    bitDepth      = prefs.current().getOutputBitDepth();
        double ditherBits    = prefs.getGenDitherBits();
        // RECTANGLE and TRIANGLE export at the SAME integer-sample-period
        // frequency the live generator emits (fs/N) - so the file matches what
        // is heard, a looped WAV has no off-grid edge/corner seam, and the
        // integer-period truncation below lands exactly on N samples.  Every
        // other form is exact at any frequency and is exported as entered.
        double frequency     = (form == GenSignalForm.RECTANGLE || form == GenSignalForm.TRIANGLE)
                ? lane.samplePeriodAlignedHz(prefs.getGenFrequencyHz(), sampleRate)
                : prefs.getGenFrequencyHz();
        double amplitudeVRms = prefs.getGenAmplitudeVrms();
        // One-shot sweep: exactly one sweep (its frequency ends at the stop
        // frequency).  A LOOPED sweep is periodic - period = the sweep duration -
        // so it is exported like other periodic forms: the WAV-duration length,
        // trimmed to a whole number of sweeps so the file loops cleanly.
        double duration;
        if (!isSweep) {
            duration = prefs.getGenWavDurationSeconds();
        } else if (sweepLoops) {
            int sweeps = Math.max(1, (int) Math.floor(prefs.getGenWavDurationSeconds() / sweepDurSec));
            duration = sweeps * sweepDurSec;
        } else {
            duration = sweepDurSec;
        }
        try {
            SignalGenerator gen;
            if (form == GenSignalForm.SINE_COMP) {
                String dpd = prefs.getGenDpd(form);
                if (dpd == null || dpd.isEmpty()) {
                    return I18n.t("generator.error.needPredistortion");
                }
                gen = new SignalGenerator(frequency, sampleRate, amplitudeVRms,
                        EXPORT_FS_VOLTAGE_AMPL, dpd);
            } else if (isSweep) {
                double f0 = prefs.getGenSweepFreqStartHz();
                double f1 = prefs.getGenSweepFreqEndHz();
                int durationSamples = Math.max(2, (int) Math.round(sweepDurSec * sampleRate));
                gen = (form == GenSignalForm.LINEAR_SWEEP)
                        ? new SignalGenerator(f0, f1, sampleRate, durationSamples,
                                amplitudeVRms, EXPORT_FS_VOLTAGE_AMPL)
                        : new SignalGenerator(f0, f1, durationSamples, 0, sampleRate,
                                amplitudeVRms, EXPORT_FS_VOLTAGE_AMPL);
                int fadeIn  = Math.max(0, (int) Math.round(prefs.getGenSweepFadeInSec()  * sampleRate));
                int fadeOut = Math.max(0, (int) Math.round(prefs.getGenSweepFadeOutSec() * sampleRate));
                gen.setSweepParams(sweepLoops, fadeIn, fadeOut);
            } else {
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms,
                        EXPORT_FS_VOLTAGE_AMPL);
            }
            gen.setRectangleDuty(prefs.getGenRectangleDuty());
            // Non-sweep periodic forms truncate to an integer-period count; noise
            // and sweeps use 0 (raw) - a sweep's length is already fixed above
            // (one sweep, or whole sweeps when looped).
            double freqForTruncation = (form.isPeriodic() && !isSweep) ? frequency : 0.0;
            // BOTH lanes at 1.0 - the file carries the signal on the 0 dBFS =
            // 2 Vrms convention (EXPORT_FS_VOLTAGE_AMPL) and NO device
            // calibration: the per-lane DAC ratio belongs to the live encoder,
            // which applies it when THIS file is played on a device.  Only the
            // output-lane gate (a routing choice, not calibration) is kept.
            long bytes = SignalFileExporter.export(gen, new File(path),
                    sampleRate, bitDepth, duration, ditherBits, freqForTruncation,
                    1.0, 1.0, prefs.getGenOutputChannels());
            log.info("File saved: {} ({} bytes)", path, bytes);
            return null;
        } catch (Exception ex) {
            log.warn("Save failed", ex);
            return ex.getMessage();
        }
    }
}
