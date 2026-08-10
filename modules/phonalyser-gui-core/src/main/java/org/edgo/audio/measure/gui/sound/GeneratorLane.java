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

package org.edgo.audio.measure.gui.sound;

import java.io.File;
import java.io.IOException;

import org.edgo.audio.measure.dsp.FftBinSnap;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.GeneratorControls;
import org.edgo.audio.measure.generator.Predistortion;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.RemoteGenerator;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * The ONE generator implementation, local or remote: everything between "the
 * operator wants a tone" and the hardware emitting it lives here - the
 * {@link SignalGenerator} DDS and its builders, the {@link RemoteGenerator}
 * commands when the active backend runs the DDS somewhere else, and the
 * {@link PlaybackLane} underneath the local path.
 *
 * <p>Layering: a single implementation of the local/remote generator, with the
 * playback lane underneath it and the actual playback below that.
 * The generator controller above this keeps only the UI's business - bidi
 * preference bindings, bus events and the one boundary where a
 * {@link PlaybackStateEnum} becomes operator language.  Nothing here touches
 * the bus and nothing here manufactures operator wording: failures are enum
 * values, details go to the log.
 *
 * <p>{@code remote()} is asked per call rather than kept: the operator
 * switches backends while this lane lives, so the capability belongs to
 * whichever backend is active NOW.
 */
@Log4j2
public final class GeneratorLane {

    /** Octave rows in the generator's Voss-McCartney pink-noise source - the
     *  {@code PINK_OCTAVES + 1} summed terms set the pink forms' RMS in
     *  {@link #rmsPerPeak}.  Mirrors the generator's own constant. */
    private static final int PINK_OCTAVES = 16;
    /** How long a starting lane may take to signal ready before the attempt is
     *  declared failed - public because the controller's wording boundary
     *  shows the operator this number in the no-stream-start message. */
    public static final long READY_TIMEOUT_S = 5L;

    private volatile SignalGenerator generator;
    /** The local lane underneath - the play thread that owns the line from
     *  start to close, the bounded release, the informed-from-below end
     *  state. */
    private final PlaybackLane playbackLane = new PlaybackLane();
    /** The rate a remote bench GRANTED for the open generator lane (spec 4.5's
     *  {@code gen.open} answer), or 0 while the DDS is this process's.  A bench
     *  whose DAC only runs at 44.1 kHz gets every derived number - the bin
     *  grid, the sweep's sample counts, the frequency the operator reads back -
     *  computed against 44.1 kHz; see {@link #outputRateHz()}. */
    private volatile int remoteRateHz;
    /** What the bench last GRANTED at {@code gen.open}, surviving the lane's
     *  teardown - a consumer that refused a mismatch names this number to the
     *  operator. */
    @Getter
    private volatile int lastGrantedRateHz;
    /** How the last start attempt ended - the machine-readable answer the
     *  controller's wording boundary renders, and the value a caller consults
     *  to decide whether starting again can help at all.  {@link
     *  PlaybackStateEnum#PARKED} until the first start is attempted. */
    @Getter
    private volatile PlaybackStateEnum lastStartState = PlaybackStateEnum.PARKED;
    /** WHY the last start failed, as read by the backend that owns the error -
     *  the detail the controller renders into the operator's message, never a
     *  driver code.  {@link DeviceFailureReason#UNKNOWN} until a backend says
     *  otherwise, which is also the honest answer for a failure that is not a
     *  device's fault at all (a signal source that would not build). */
    @Getter
    private volatile DeviceFailureReason lastFailureReason = DeviceFailureReason.UNKNOWN;
    /** Previous waveform - the restart-vs-live-swap decision needs both sides
     *  of a form change. */
    private GenSignalForm lastForm = Preferences.instance().getGenSignalForm();
    /** The run the lane was last started with - what {@link #restart()}
     *  replays, so no caller re-derives anything for a stop/start pair. */
    private volatile GeneratorRun lastRun;

    // -------------------------------------------------------------------------
    // Start / stop / restart
    // -------------------------------------------------------------------------

    /**
     * Starts emitting {@code run} - locally through the {@link PlaybackLane},
     * or by commanding the active backend's {@link RemoteGenerator}.  The
     * caller derives the run (the controller from the preferences, a sweep
     * engine from its own numbers); the lane keeps it, so {@link #restart()}
     * replays the same session.  No-op when already running.
     *
     * @return how the attempt ended, machine-readably; also kept in
     *         {@link #getLastStartState()}
     */
    public synchronized PlaybackStateEnum start(GeneratorRun run) {
        if (isRunning()) {
            return lastStartState = PlaybackStateEnum.STARTED;
        }
        lastRun = run;
        // Every attempt answers for itself: a reason left over from an earlier
        // failure must never be rendered into a later message.
        lastFailureReason = DeviceFailureReason.UNKNOWN;

        DeviceRef device = AudioBackend.instance().getActiveOutputDevice();
        if (device == null) {
            String deviceName = Preferences.instance().current().getOutputDeviceName();
            return lastStartState = (deviceName == null || deviceName.isEmpty())
                    ? PlaybackStateEnum.NO_DEVICE
                    : PlaybackStateEnum.DEVICE_UNAVAILABLE;
        }
        // Applying the profile (DAC full scale + rightLaneScale) is the
        // caller's step after resolution - a preference write belongs on the
        // UI thread, whose bindings are plain UI-only listeners.
        GuiUtil.marshal(() -> Preferences.instance().applyDeviceProfile(device, false));

        // The DDS may not be in this process at all - then the whole engine
        // below is somebody else's, and what is left here is the command.
        RemoteGenerator remote = remote();
        if (remote != null) {
            PlaybackStateEnum state = startRemote(remote, run, device);
            if (state == PlaybackStateEnum.STARTED && log.isInfoEnabled()) {
                log.info("Generator started on a remote bench: device={}, form={}, freq={} Hz, amp={} Vrms, rate={} Hz, depth={} bit, dither={} bit",
                        device.displayName(), run.form(), run.frequencyHz(), run.amplitudeVrms(),
                        run.sampleRate(), run.bitDepth(), run.ditherBits());
            }
            return lastStartState = state;
        }

        // The WASAPI exclusive-mode driver sometimes refuses to start the
        // render stream on the first attempt when a sibling capture stream
        // is already running in the same process (the in-built scope view).
        // External recording works because cross-process contention is
        // mediated by Windows' session manager; in-process, the first start
        // can lose the race.  Only a state that says it is worth retrying is
        // retried - a card that is not there is not there half a second
        // later, and retrying it only delays the truth.
        final int  MAX_ATTEMPTS   = 2;
        final long RETRY_PAUSE_MS = 500;
        PlaybackStateEnum attemptState = PlaybackStateEnum.STARTED;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptState = tryStartOnce(run, device, READY_TIMEOUT_S);
            if (attemptState == PlaybackStateEnum.STARTED) {
                log.info("Generator started (attempt {}): device={}, form={}, freq={} Hz, amp={} Vrms, rate={} Hz, depth={} bit, dither={} bit",
                        attempt, device.displayName(), run.form(), run.frequencyHz(), run.amplitudeVrms(),
                        run.sampleRate(), run.bitDepth(), run.ditherBits());
                return lastStartState = attemptState;
            }
            log.warn("Generator start attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, attemptState);
            if (!attemptState.retryable()) {
                break;
            }
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(RETRY_PAUSE_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return lastStartState = attemptState;
    }

    /**
     * Stops the tone (idempotent).  Locally: sets the playback lane's stop
     * flag and waits a bounded time for its play thread - the lane's owner -
     * to leave; the drain / stop / close of the line happen on THAT thread,
     * never this one.  Remotely: emission first, then the lane and the device
     * lock, in the same order the local path drains and closes in.
     *
     * @return whether a tone was actually playing - the controller publishes
     *         its signal-changed event only on a real transition
     */
    public synchronized boolean stop() {
        boolean wasRunning = isRunning();
        RemoteGenerator remote = remote();
        if (remote != null) {
            // Only what was actually started is stopped: every teardown path
            // calls this blindly, and a gen.stop for a generator that was
            // never opened is a refusal, not a stop.
            if (wasRunning) {
                try {
                    remote.stopGenerator();
                } catch (RuntimeException ex) {
                    if (log.isWarnEnabled()) {
                        log.warn("Remote generator stop failed", ex);
                    }
                }
            }
            remote.closeGenerator();
        } else {
            playbackLane.release();
        }
        generator = null;
        // The granted rate described a lane that is now closed; anything asked
        // afterwards is answered from the configured rate again.
        remoteRateHz = 0;
        return wasRunning;
    }

    /**
     * Makes sure the REMOTE generator session exists, opening it if the tone
     * never did - the remote twin of the local play loop opening its own line.
     *
     * <p>Playing a file on a bench needs the same open the tone needs, because
     * {@code gen.playFile} names a lane that must already be there.  Until this
     * existed, a file played without first starting the tone was refused before
     * a byte left the machine: no upload, no server activity, and the refusal
     * lost in a log line on a worker thread.
     *
     * <p>Only the OPEN is done here - no form, no frequency, no emission.  The
     * server reopens the lane at the file's own rate when the file arrives, so
     * opening at the preferences' rate is right and the tone's configuration
     * would be thrown away anyway.
     *
     * @return {@link PlaybackStateEnum#STARTED} when a session is open
     *         afterwards (including one that already was), otherwise the same
     *         machine-readable refusal {@link #start} answers with
     */
    public synchronized PlaybackStateEnum ensureRemoteOpen(GeneratorRun run) {
        RemoteGenerator remote = remote();
        if (remote == null || remote.isGeneratorOpen()) {
            return PlaybackStateEnum.STARTED;   // local engine, or already open
        }
        lastFailureReason = DeviceFailureReason.UNKNOWN;
        DeviceRef device = AudioBackend.instance().getActiveOutputDevice();
        if (device == null) {
            String deviceName = Preferences.instance().current().getOutputDeviceName();
            return (deviceName == null || deviceName.isEmpty())
                    ? PlaybackStateEnum.NO_DEVICE
                    : PlaybackStateEnum.DEVICE_UNAVAILABLE;
        }
        GuiUtil.marshal(() -> Preferences.instance().applyDeviceProfile(device, false));
        return openRemoteLane(remote, run, device) ? PlaybackStateEnum.STARTED
                                                   : PlaybackStateEnum.REMOTE_REFUSED;
    }

    /** Stop + start of the SAME run (the parameters stored by the last
     *  {@link #start(GeneratorRun)}), for a change the running session cannot
     *  take live.  A caller whose parameters changed passes a fresh run to
     *  {@code start} instead.  The pair is atomic against a concurrent
     *  start/stop from another control. */
    public synchronized PlaybackStateEnum restart() {
        GeneratorRun run = lastRun;
        if (run == null) {
            return lastStartState;   // never started - nothing to replay
        }
        stop();
        return start(run);
    }

    // -------------------------------------------------------------------------
    // The two halves of a start
    // -------------------------------------------------------------------------

    /**
     * One local open + play attempt.  Answers {@link PlaybackStateEnum#STARTED}
     * on success; any other value names the failure machine-readably (the log
     * already holds the technical detail) and guarantees any partially-opened
     * resources are torn down before returning.
     */
    private PlaybackStateEnum tryStartOnce(GeneratorRun run, DeviceRef device,
                                long readyTimeoutSeconds) {
        // The previous lane, if there still is one, is waited out and let go -
        // the playback lane releases it again before starting the new one, but
        // doing it BEFORE the open keeps a wedged device from being reopened
        // while its old line is still being given back.
        playbackLane.release();
        Preferences prefs = Preferences.instance();
        final GenSignalForm form  = run.form();
        final int    sampleRate    = run.sampleRate();
        final double frequency     = run.frequencyHz();
        final double amplitudeVRms = run.amplitudeVrms();
        final double dacFs         = run.dacFsVoltage();
        SignalGenerator gen;
        try {
            if (form == GenSignalForm.SINE_COMP) {
                String dpd = prefs.getGenDpd(form);
                if (dpd == null || dpd.isEmpty()) {
                    return PlaybackStateEnum.NEED_PREDISTORTION;
                }
                if (compensationFileMissing(dpd)) {
                    return PlaybackStateEnum.PREDISTORTION_FILE_MISSING;
                }
                gen = new SignalGenerator(frequency, sampleRate, amplitudeVRms, dacFs, dpd);
            } else if (form == GenSignalForm.DUAL_TONE_COMP) {
                // Plain two-tone generator, then load the dual-tone intermod
                // corrections (freq-independent (a,b) products) onto it.  The
                // second tone + amplitude split are pushed below.
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms, dacFs);
                String dpd = prefs.getGenDpd(form);
                if (compensationFileMissing(dpd)) {
                    return PlaybackStateEnum.PREDISTORTION_FILE_MISSING;
                }
                if (dpd != null && !dpd.isEmpty()) {
                    gen.readDpd(dpd, frequency, sampleRate);
                }
            } else if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
                GeneratorRun.SweepSpec sweep = run.sweep();
                if (form == GenSignalForm.LINEAR_SWEEP) {
                    gen = new SignalGenerator(sweep.startHz(), sweep.stopHz(), sampleRate,
                            sweep.durationSamples(), amplitudeVRms, dacFs);
                } else {
                    gen = new SignalGenerator(sweep.startHz(), sweep.stopHz(),
                            sweep.durationSamples(), sweep.leadInSamples(), sampleRate,
                            amplitudeVRms, dacFs);
                }
            } else {
                gen = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms, dacFs);
            }
        } catch (Exception ex) {
            log.warn("Signal source build failed", ex);
            return PlaybackStateEnum.BUILD_FAILED;
        }
        // Apply any waveform-specific live-tunables before we hand the
        // generator off to the audio thread.
        gen.setRectangleDuty(prefs.getGenRectangleDuty());
        gen.setTriangleDuty (prefs.getGenTriangleDuty());
        if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
            GeneratorRun.SweepSpec sweep = run.sweep();
            gen.setSweepParams(sweep.loop(), sweep.fadeInSamples(), sweep.fadeOutSamples());
        }
        if (form.isDualTone()) {
            // Dual-tone: tone 1 uses the frequency the generator was
            // constructed with (already snapped above); tone 2's frequency
            // and the per-tone amplitude split are pushed in here.  Tone 2 is
            // snapped through the same one authority every other tone-2 path
            // uses, so both tones land on bin centres of the SAME grid - the
            // analysis one.  Snapping it here against the run's rate would put
            // tone 2 on the DAC grid while tone 1 sat on the analysis grid, and
            // a tone smeared across bins manufactures the very intermodulation
            // products the IMD table then reports.  {@code genDualToneSplitPct}
            // carries Freq 1's amplitude percentage; Freq 2's is the complement.
            gen.setDualToneFrequency2(snapDualTone(prefs.getGenDualToneFreq2Hz()));
            double a1Pct = prefs.getGenDualToneSplitPct();
            gen.setDualToneAmplitudes(a1Pct, 100.0 - a1Pct);
        }
        this.generator = gen;

        AudioPlayback ag;
        // Hoisted out of the try so the catch can ask the SAME manager what its
        // own failure meant - classification belongs to the backend that owns
        // the native error, and this is the one place that holds both.
        AudioDeviceManager playback = AudioBackend.instance().playbackManager(device);
        try {
            // The SPI form on purpose: the line opens at the RUN's rate and
            // depth - a sweep engine's duplex session must not pick up a
            // diverging output-prefs rate (the controller's run carries the
            // prefs values anyway).
            ag = playback.openPlayback(device, sampleRate, run.bitDepth(), run.ditherBits());
            ag.open();
        } catch (Exception ex) {
            // The raw throwable to the LOG (it is what a developer needs), the
            // backend's reading of it to the OPERATOR - a driver code says them
            // nothing, "the device does not answer" says everything.
            lastFailureReason = playback.classifyFailure(ex);
            log.warn("Playback open failed ({})", lastFailureReason, ex);
            this.generator = null;
            return PlaybackStateEnum.OPEN_FAILED;
        }
        // Push the per-lane scale (scaleR = fsLeft/fsRight) and the output gate
        // before the render thread starts - the quantizer reads them per block.
        pushOutputRoutingTo(ag, run);
        // The playback lane owns the line from here: play thread, ready wait,
        // bounded release and the informed-from-below end state all live in it.
        PlaybackStateEnum state = playbackLane.start(ag, gen, readyTimeoutSeconds);
        if (state != PlaybackStateEnum.STARTED) {
            this.generator = null;
        }
        return state;
    }

    /**
     * The same start against a generator that runs on the far end of a backend:
     * the lane is opened there, the settings this process just resolved are
     * pushed as commands, and emission begins.
     *
     * <p>The frequency handed in is the one {@link #emitFrequency} resolved -
     * already bin-snapped, and already period-aligned for the forms that need an
     * integer-sample period, which the far end does not do for them.  Sending it
     * as the nominal costs nothing: the far end's own snap is idempotent on a
     * value that is already on the grid, so both ends compute the same emitted
     * tone.  It is re-resolved when the bench GRANTS another rate than the one
     * asked for - and so is every sweep length, because a duration in seconds
     * turned into samples at the wrong rate is a sweep of the wrong length.
     *
     * <p>The FFT grid goes LAST, after the waveform: the far end snaps against
     * the form it currently holds, and a grid pushed before it would be applied
     * to the previous one.
     */
    /**
     * The one {@code gen.open} on the remote path - shared by the tone's start
     * and by {@link #ensureRemoteOpen}, so the open's parameters are assembled
     * in exactly one place and a file-play open can never drift from a tone's.
     *
     * <p>Leaves {@link #lastGrantedRateHz} / {@link #remoteRateHz} set on
     * success and {@link #lastFailureReason} set on refusal.
     *
     * @return false when the bench refused; the lane was never this session's,
     *         so there is nothing to give back (a close for a lane that never
     *         opened would itself be refused)
     */
    private boolean openRemoteLane(RemoteGenerator remote, GeneratorRun run,
            DeviceRef device) {
        try {
            int granted = remote.openGenerator(device, run.sampleRate(), run.bitDepth(),
                    run.ditherBits(), run.channels());
            lastGrantedRateHz = granted;
            remoteRateHz = granted > 0 ? granted : run.sampleRate();
            return true;
        } catch (RuntimeException ex) {
            // The reason is asked of the SAME backend here as on the local path,
            // and for a remote device that backend only hands on what the BENCH
            // decided: it classified the fault with the module that owns the
            // driver and sent the enum's name over the wire.  Nothing on this
            // side reads the server's English.
            lastFailureReason = AudioBackend.instance().playbackManager(device)
                    .classifyFailure(ex);
            if (log.isWarnEnabled()) {
                log.warn("Remote generator open refused ({})", lastFailureReason, ex);
            }
            remoteRateHz = 0;
            return false;
        }
    }

    private PlaybackStateEnum startRemote(RemoteGenerator remote, GeneratorRun run,
                               DeviceRef device) {
        Preferences prefs = Preferences.instance();
        final GenSignalForm form = run.form();
        if (!openRemoteLane(remote, run, device)) {
            return PlaybackStateEnum.REMOTE_REFUSED;
        }
        int granted = lastGrantedRateHz;
        try {
            int rate = outputRateHz();
            if (run.requireGrantedRate() && rate != run.sampleRate()) {
                // A sweep consumer's premise is sample counts on the asked
                // clock - a lane on another one is refused BEFORE anything is
                // configured or emitted, and the lane the open took goes back.
                if (log.isWarnEnabled()) {
                    log.warn("Remote generator granted {} Hz where the run requires {} Hz - refused",
                            granted, run.sampleRate());
                }
                closeRemote(remote);
                return PlaybackStateEnum.REMOTE_REFUSED;
            }
            double emitHz = rate == run.sampleRate() ? run.frequencyHz()
                    : emitFrequency(prefs, form, rate, analysisRateHz(),
                            rawFrequency(prefs, form));
            remote.setForm(form);
            if (form != GenSignalForm.LINEAR_SWEEP && form != GenSignalForm.LOG_SWEEP) {
                // A sweep has no tone frequency - its band travels in the
                // sweep block below.
                remote.setFrequency(emitHz);
            }
            remote.setAmplitudeVrms(run.amplitudeVrms());
            if (run.pushCalibration()) {
                // The pane's tone: the operator calibrates the DAC from this
                // GUI even when the DDS is remote, and a card whose two DAC
                // full-scales differ emits an uncalibrated right lane without
                // the per-lane scale.  A sweep engine's run skips both - the
                // bench's own device card stays in force.
                remote.setDacFsVoltageAmpl(run.dacFsVoltage());
                remote.setRightLaneScale(run.rightLaneScale());
            }
            remote.setRectangleDuty(prefs.getGenRectangleDuty());
            remote.setTriangleDuty(prefs.getGenTriangleDuty());
            if (form.isDualTone()) {
                remote.setDualToneFrequency2(snapDualTone(prefs.getGenDualToneFreq2Hz()));
                double a1Pct = prefs.getGenDualToneSplitPct();
                remote.setDualToneAmplitudes(a1Pct, 100.0 - a1Pct);
            }
            if (form == GenSignalForm.LINEAR_SWEEP || form == GenSignalForm.LOG_SWEEP) {
                // The run's counts are samples at ITS rate; a bench that granted
                // another one is told counts scaled to that clock, or a duration
                // in seconds would silently change.  (A sweep ENGINE refuses a
                // granted mismatch outright - the scale is then 1.)
                double scale = rate == run.sampleRate() ? 1.0
                        : rate / (double) run.sampleRate();
                GeneratorRun.SweepSpec sweep = run.sweep();
                remote.setSweepFreqStart(sweep.startHz());
                remote.setSweepFreqEnd(sweep.stopHz());
                remote.setSweepDurationSamples((int) Math.round(sweep.durationSamples() * scale));
                remote.setSweepLeadInSamples((int) Math.round(sweep.leadInSamples() * scale));
                remote.setSweepFadeInSamples((int) Math.round(sweep.fadeInSamples() * scale));
                remote.setSweepFadeOutSamples((int) Math.round(sweep.fadeOutSamples() * scale));
                remote.setSweepLoop(sweep.loop());
                // The LOCAL model of the same sweep is still built: it is the
                // deconvolution reference a sweep consumer reads through
                // {@link #sweepReference()} - the identical chirp the bench
                // renders from the same numbers.  Nothing renders it here.
                try {
                    SignalGenerator reference = form == GenSignalForm.LINEAR_SWEEP
                            ? new SignalGenerator(sweep.startHz(), sweep.stopHz(), run.sampleRate(),
                                    sweep.durationSamples(), run.amplitudeVrms(), run.dacFsVoltage())
                            : new SignalGenerator(sweep.startHz(), sweep.stopHz(),
                                    sweep.durationSamples(), sweep.leadInSamples(), run.sampleRate(),
                                    run.amplitudeVrms(), run.dacFsVoltage());
                    reference.setSweepParams(sweep.loop(), sweep.fadeInSamples(), sweep.fadeOutSamples());
                    this.generator = reference;
                } catch (RuntimeException ex) {
                    log.warn("Sweep reference build failed", ex);
                }
            }
            PlaybackStateEnum compensation = applySavedCompensation(remote, prefs, form, emitHz, rate);
            if (compensation != PlaybackStateEnum.STARTED) {
                closeRemote(remote);
                return compensation;
            }
            remote.fftGrid(prefs.getFftLength(), prefs.isGenSnapToFftBin());
            remote.startGenerator();
            return PlaybackStateEnum.STARTED;
        } catch (RuntimeException ex) {
            if (log.isWarnEnabled()) {
                log.warn("Remote generator start failed", ex);
            }
            // Whatever was opened goes back: a lane left behind on the bench is a
            // DAC no other client can take, and the operator will press Play again.
            closeRemote(remote);
            return PlaybackStateEnum.REMOTE_REFUSED;
        }
    }

    /**
     * Closes a remote lane that failed to start, and forgets the rate it granted.
     *
     * <p>The rate is half of the teardown, not a detail: {@link #outputRateHz()}
     * prefers {@link #remoteRateHz} over the configured output rate for EVERY
     * second->sample conversion and every bin snap, so a lane that opened at
     * 48 kHz and then refused to play would go on defining the grid - including
     * after the operator switched back to a local 44.1 kHz backend, where every
     * snapped frequency, every sweep length and every period-aligned tone would
     * be computed against a rate nothing is clocked at.
     */
    private void closeRemote(RemoteGenerator remote) {
        remote.closeGenerator();
        remoteRateHz = 0;
    }

    /**
     * Loads the saved predistortion for a compensated waveform onto
     * {@code target} - the remote half of what {@link #tryStartOnce} does by
     * handing the {@code .dpd} path to the generator's constructor.
     *
     * <p>Compensated sine IS its corrections: a bench started on
     * {@code SINE_COMP} with no table emits a plain sine, and its THD is then
     * measured and recorded as if it were predistorted.  So the missing file is
     * refused here exactly as the local path refuses it, rather than silently
     * downgraded.  The dual-tone slot stays optional, as it is locally.
     *
     * @return {@link PlaybackStateEnum#STARTED} when there was nothing to load
     *         or it was loaded, else the machine-readable refusal
     */
    private PlaybackStateEnum applySavedCompensation(GeneratorControls target, Preferences prefs,
                                          GenSignalForm form, double frequency, int sampleRate) {
        if (form != GenSignalForm.SINE_COMP && form != GenSignalForm.DUAL_TONE_COMP) {
            return PlaybackStateEnum.STARTED;
        }
        String dpd = prefs.getGenDpd(form);
        if (dpd == null || dpd.isEmpty()) {
            return form == GenSignalForm.SINE_COMP
                    ? PlaybackStateEnum.NEED_PREDISTORTION : PlaybackStateEnum.STARTED;
        }
        if (compensationFileMissing(dpd)) {
            return PlaybackStateEnum.PREDISTORTION_FILE_MISSING;
        }
        try {
            new Predistortion(dpd, frequency, sampleRate).applyTo(target);
            return PlaybackStateEnum.STARTED;
        } catch (IOException | RuntimeException ex) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to load predistortion corrections from {}", dpd, ex);
            }
            return PlaybackStateEnum.BUILD_FAILED;
        }
    }

    /**
     * Whether a configured {@code .dpd} is named but not on disk - the one
     * compensation fault that is the PATH's, asked before the file is opened so
     * it stays a fault of its own.
     *
     * <p>Opened blind it is an {@code IOException} like any other, and both
     * engines answer the same "could not build the signal generator" for it,
     * whose {@code {0}} is a device reason that no missing file has: the operator
     * was told "reason unknown" about a path only they can correct.  Asked here,
     * the state names the file instead.  An empty slot is NOT this: it is the
     * operator not having picked one yet, which the compensated forms answer for
     * themselves.
     */
    private boolean compensationFileMissing(String dpd) {
        return dpd != null && !dpd.isEmpty() && !new File(dpd).isFile();
    }

    // -------------------------------------------------------------------------
    // Which generator, which rate
    // -------------------------------------------------------------------------

    /** The backend's remote-generator capability, or null when the DDS runs in
     *  this process.  Asked per call rather than kept: the operator switches
     *  backends while this lane lives, so the capability belongs to whichever
     *  backend is active NOW. */
    private RemoteGenerator remote() {
        return AudioBackend.instance().remoteGenerator();
    }

    /**
     * Whichever generator is emitting right now - the far end's when the active
     * backend runs one, this process's DDS otherwise, and null when nothing is
     * playing at all (which is what makes every live setter below a one-line
     * no-op instead of a special case).
     *
     * <p>This is the ONLY place the two are told apart.  Every live parameter
     * used to repeat the choice, and four of them quietly did not: a setter
     * without its remote branch is not a compile error, it is a value that never
     * leaves the machine while the pane goes on showing it as set.
     */
    private GeneratorControls live() {
        RemoteGenerator remote = remote();
        return remote != null ? remote : generator;
    }

    /** The rate the generator's output lane is clocked at: the one a remote
     *  bench granted while its lane is open, else the configured output rate.
     *  Every second->sample conversion and every bin-snap here goes through it,
     *  so both ends compute against the same grid. */
    public int outputRateHz() {
        int granted = remoteRateHz;
        return granted > 0 ? granted
                : Preferences.instance().current().getOutputSampleRate();
    }

    /**
     * True while the DDS tone is playing.  With a remote generator this is the
     * BENCH's answer - its last pushed state - because the tone can stop there
     * without this process asking (a lane that failed, a session that died),
     * and a Play button that went on claiming otherwise would be lying about
     * hardware.
     */
    public boolean isRunning() {
        RemoteGenerator remote = remote();
        return remote != null ? remote.state().running() : playbackLane.isRunning();
    }

    /** True while the active backend runs the generator on a REMOTE bench -
     *  the one fact a sweep consumer still needs (its record anchors at the
     *  bench's in-band mark instead of the local ready instant); everything
     *  else about the two is this lane's own business. */
    public boolean isRemoteSession() {
        return remote() != null;
    }

    /** The emit frequency the CURRENT preferences resolve to at {@code rate} -
     *  bin-snapped and period-aligned per form.  The controller derives its
     *  {@link GeneratorRun} with this; the lane itself no longer reads the
     *  tone preferences at start. */
    public double resolveEmitFrequency(GenSignalForm form, int sampleRate) {
        Preferences prefs = Preferences.instance();
        return emitFrequency(prefs, form, sampleRate, analysisRateHz(),
                rawFrequency(prefs, form));
    }

    /** The running log-sweep's one-period reference X(t) - what a deconvolving
     *  consumer (the notch tuner) divides by; {@code null} while no local
     *  sweep generator is up.  On a remote session the caller builds its own
     *  reference from the same numbers it put into the run. */
    public double[] sweepReference() {
        SignalGenerator g = generator;
        return g == null ? null : g.getLogSweepBuffer();
    }

    /**
     * Live output-lane change.  Locally the gate goes straight to the running
     * playback.  A REMOTE lane is restarted with the changed run instead -
     * spec 4.5 fixes the gate at {@code gen.open}, so there is no command
     * that re-gates a running lane, and pushing one would be a setting the
     * operator watched apply and the bench never heard.  The stored run is
     * updated either way, so a later {@link #restart()} keeps the new gate.
     */
    public synchronized void setOutputChannels(OutputChannels channels) {
        GeneratorRun run = lastRun;
        if (run != null) {
            lastRun = run.withChannels(channels);
        }
        if (remote() == null) {
            AudioPlayback ag = playbackLane.getPlayback();
            if (ag != null) ag.setOutputChannels(channels);
            return;
        }
        // The reopen is gated on a SESSION having been started, not on the
        // bench's pushed running state - the state is the bench's own answer
        // and may lag; a session that was started must be re-gated either way.
        if (lastRun != null) {
            stop();
            start(lastRun);
        }
    }

    /** Claims the lane's from-below end for the ONE operator report - the
     *  LOCAL playback lane's claim ({@link
     *  PlaybackLane#takeEndedFromBelowForReport()}) or, on a remote session,
     *  the bench's ({@link RemoteGenerator#takeEndedFromBelowForReport()}:
     *  its output lane failed, or the session died with the tone up).
     *  {@code null} while the lane is healthy, after a commanded stop, and
     *  for every caller after the first. */
    public Throwable takePlaybackEndedFromBelow() {
        RemoteGenerator remote = remote();
        if (remote != null) {
            Throwable ended = remote.takeEndedFromBelowForReport();
            if (ended != null) {
                return ended;
            }
        }
        return playbackLane.takeEndedFromBelowForReport();
    }

    /** The same consult WITHOUT claiming - for a sweep assembly that fails
     *  its own measurement on the death and reports through its own dialog,
     *  leaving the claim untouched. */
    public Throwable playbackEndedFromBelow() {
        return playbackLane.endedFromBelow();
    }

    // -------------------------------------------------------------------------
    // Live-apply setters (each a one-line no-op when nothing is playing)
    // -------------------------------------------------------------------------

    /** Live-applies the dither bit count to the running local playback (a
     *  remote line's dither is fixed at {@code gen.open}). */
    public void setDitherBits(double bits) {
        AudioPlayback ag = playbackLane.getPlayback();
        if (ag != null) ag.setDitherBits(bits);
    }

    /** Live-applies a duty-cycle (fraction in [0.001, 0.999]) to the running rectangle generator. */
    public void setRectangleDuty(double dutyFrac) {
        GeneratorControls g = live();
        if (g != null) g.setRectangleDuty(dutyFrac);
    }

    /** Live-applies a duty-cycle (fraction in [0.001, 0.999]) to the running triangle generator. */
    public void setTriangleDuty(double dutyFrac) {
        GeneratorControls g = live();
        if (g != null) g.setTriangleDuty(dutyFrac);
    }

    /**
     * Live-applies a new waveform to the running generator.  No-op if the
     * generator isn't running.  Switching to / from {@link GenSignalForm#SINE_COMP}
     * or any sweep form requires a stop+start because their state machines
     * aren't safely live-mutable; this method skips them.
     */
    public void setForm(GenSignalForm form) {
        if (form == null) return;
        // A remote bench is sent it whatever the waveform: which forms need the
        // DDS rebuilt is its own decision, and it makes the same one this guard
        // makes (it restarts them rather than swapping them live).
        if (remote() == null && (form == GenSignalForm.LINEAR_SWEEP
                || form == GenSignalForm.LOG_SWEEP || form == GenSignalForm.SINE_COMP)) {
            return;
        }
        GeneratorControls g = live();
        if (g != null) g.setForm(form);
    }

    /** Live-applies a new NOMINAL frequency (Hz) to the running generator.  No-op
     *  if not running.  The FLL's correction goes through
     *  {@link #trimFrequency(double)} instead - a remote generator keeps the two
     *  apart, so that a trim can be reset without losing what the operator
     *  typed. */
    public void setFrequency(double hz) {
        GeneratorControls g = live();
        if (g != null) g.setFrequency(hz);
    }

    /** The frequency-lock loop's absolute corrected frequency for tone 1.  On
     *  the local DDS that is simply the running frequency; a remote generator
     *  takes it as a TRIM, which its {@code trimReset} can undo and which does
     *  not overwrite the nominal the operator entered. */
    public void trimFrequency(double hz) {
        RemoteGenerator remote = remote();
        if (remote != null) {
            remote.trim(hz);
            return;
        }
        setFrequency(hz);
    }

    /** The same for tone 2 of a dual-tone signal. */
    public void trimFrequency2(double hz) {
        RemoteGenerator remote = remote();
        if (remote != null) {
            remote.trim2(hz);
            return;
        }
        setDualToneFrequency2(hz);
    }

    /** Hot-applies harmonic predistortion to the running generator and
     *  switches it to compensated sine (no restart) - the predistortion
     *  wizard's per-round apply.  No-op when nothing is playing. */
    public void applyCompensation(double[] ampRatios, int[] hNums, double[] phiInits) {
        GeneratorControls g = live();
        if (g != null) g.applyCompensation(ampRatios, hNums, phiInits);
    }

    /** Hot-applies dual-tone intermod predistortion to the running generator
     *  and switches it to compensated dual tone (no restart) - the dual-tone
     *  counterpart of {@link #applyCompensation}.  No-op when nothing is
     *  playing. */
    public void applyDualToneCompensation(double[] ampRatios, int[] coefA, int[] coefB, double[] phiInits) {
        GeneratorControls g = live();
        if (g != null) g.applyDualToneCompensation(ampRatios, coefA, coefB, phiInits);
    }

    /** Clears wizard predistortion and returns the running generator to
     *  plain sine. */
    public void clearCompensation() {
        GeneratorControls g = live();
        if (g != null) g.clearCompensation();
    }

    /** Loads a saved predistortion file (.dpd) onto the running generator - the
     *  wizard's Apply path.  The file is self-describing (single-tone harmonic
     *  vs dual-tone intermod), so the generator switches to the matching
     *  compensated form.  No-op when nothing is playing - the persisted prefs
     *  make the next start load it.
     *
     *  <p>The file is read HERE and the tables travel, which is what lets the
     *  wizard commit against a remote bench: the {@code .dpd} sits on the
     *  operator's machine and the bench has no way to open it. */
    public void loadCorrectionsFromFile(String path) {
        GeneratorControls g = live();
        if (g == null || path == null) return;
        try {
            new Predistortion(path, effectiveFrequency(), outputRateHz()).applyTo(g);
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to load predistortion corrections from {}", path, ex);
        }
    }

    /** Live-applies the second tone's frequency (Hz) for the
     *  {@code DUAL_TONE} waveform.  No-op if not running; no audible
     *  effect for non-DUAL_TONE waveforms (the second accumulator
     *  stays idle until the form is switched to DUAL_TONE). */
    public void setDualToneFrequency2(double hz) {
        GeneratorControls g = live();
        if (g != null) g.setDualToneFrequency2(hz);
    }

    /** Live-applies the dual-tone per-tone amplitude percentages.
     *  Both values together; generator clamps each to {@code [0, 100]}
     *  and re-normalises the internal amplitude scale so the combined
     *  signal's Vrms still matches the Amplitude field. */
    public void setDualToneAmplitudes(double amp1Pct, double amp2Pct) {
        GeneratorControls g = live();
        if (g != null) g.setDualToneAmplitudes(amp1Pct, amp2Pct);
    }

    /** Live-applies a new amplitude (V RMS) to the running generator.  No-op if not running. */
    public void setAmplitudeVrms(double vrms) {
        GeneratorControls g = live();
        if (g != null) g.setAmplitudeVrms(vrms);
    }

    /** Recomputes the running generator's amplitude scale against the current DAC
     *  full-scale (the cached requested Vrms is unchanged).  No-op if not running.
     *  Driven by the DAC-calibration binding so a full-scale change takes effect live. */
    public void setDacFsVoltageAmpl(double v) {
        GeneratorControls g = live();
        if (g != null) g.setDacFsVoltageAmpl(v);
    }

    /** Pushes the current per-lane scale + output gate to the running playback.
     *  No-op if nothing is playing - the values ride the quantizer's defaults
     *  (both scales 1.0, gate BOTH) until a session opens and re-pushes them.
     *
     *  <p>A remote bench gets the lane scale as one command, because the lane it
     *  belongs to is the far end's.  The output GATE is not sent: it is fixed at
     *  {@code gen.open} (see {@link RemoteGenerator}), so a change to it reaches
     *  the bench at the next start. */
    public void pushOutputRouting() {
        Preferences prefs = Preferences.instance();
        RemoteGenerator remote = remote();
        if (remote != null) {
            remote.setRightLaneScale(prefs.dacRightLaneScale());
            return;
        }
        AudioPlayback ag = playbackLane.getPlayback();
        if (ag == null) return;
        ag.setChannelScale(1.0, prefs.dacRightLaneScale());
        ag.setOutputChannels(prefs.getGenOutputChannels());
    }

    /** The local half of the push, against an explicit line - the pre-start
     *  path targets the just-opened line before the playback lane owns it,
     *  with the RUN's scale and gate (the controller's run carries the prefs
     *  values; a sweep engine's carries its own). */
    private void pushOutputRoutingTo(AudioPlayback ag, GeneratorRun run) {
        if (ag == null) return;
        ag.setChannelScale(1.0, run.rightLaneScale());
        ag.setOutputChannels(run.channels());
    }

    /** Live-applies sweep start frequency (Hz).  Applied to the LOCAL
     *  generator as well while a bench emits - it is then the deconvolution
     *  reference ({@link #sweepReference()}), and a reference left on the old
     *  band would divide the new band's response by the wrong chirp.  The
     *  stored run follows, so a restart replays the band the operator is on. */
    public void setSweepFreqStart(double hz) {
        GeneratorRun run = lastRun;
        if (run != null) lastRun = run.withSweepStart(hz);
        SignalGenerator localGen = generator;
        if (localGen != null) localGen.setSweepFreqStart(hz);
        RemoteGenerator remote = remote();
        if (remote != null) remote.setSweepFreqStart(hz);
    }

    /** Live-applies sweep stop frequency (Hz) - both halves, see
     *  {@link #setSweepFreqStart}. */
    public void setSweepFreqEnd(double hz) {
        GeneratorRun run = lastRun;
        if (run != null) lastRun = run.withSweepStop(hz);
        SignalGenerator localGen = generator;
        if (localGen != null) localGen.setSweepFreqEnd(hz);
        RemoteGenerator remote = remote();
        if (remote != null) remote.setSweepFreqEnd(hz);
    }

    /** Live-applies sweep duration (seconds) by converting to samples
     *  via the rate the output lane is actually clocked at. */
    public void setSweepDurationSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return;
        GeneratorControls g = live();
        if (g != null) g.setSweepDurationSamples(sweepSamples(seconds));
    }

    /** Live-applies sweep fade-in length (seconds). */
    public void setSweepFadeInSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return;
        GeneratorControls g = live();
        if (g != null) g.setSweepFadeInSamples(fadeSamples(seconds));
    }

    /** Live-applies sweep fade-out length (seconds). */
    public void setSweepFadeOutSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return;
        GeneratorControls g = live();
        if (g != null) g.setSweepFadeOutSamples(fadeSamples(seconds));
    }

    /** Live-applies the sweep loop flag. */
    public void setSweepLoop(boolean loop) {
        GeneratorControls g = live();
        if (g != null) g.setSweepLoop(loop);
    }

    /** True when the running generator can accept live form updates for the given target. */
    public boolean canLiveSwitchForm(GenSignalForm target) {
        if (target == null || generator == null) return false;
        if (target == GenSignalForm.LINEAR_SWEEP || target == GenSignalForm.LOG_SWEEP) return false;
        if (target == GenSignalForm.SINE_COMP) return false;
        return true;
    }

    /** A Farina (LOG) sweep can't live-edit its pre-rendered buffer without the
     *  playback dropping to silence, so a parameter change while it is running
     *  does a full restart instead - the tone resumes with the new parameters
     *  ({@link #start()} rebuilds the generator from the just-committed prefs).
     *  Returns {@code true} when it restarted, so the caller skips the live
     *  setter. */
    public boolean restartFarinaOnParamChange() {
        if (isRunning() && Preferences.instance().getGenSignalForm() == GenSignalForm.LOG_SWEEP) {
            restart();
            return true;
        }
        return false;
    }

    /** Waveform pref change: live-swap when the generator supports it,
     *  else a full stop+start - sweep and dual-tone set up dedicated DDS
     *  state (second accumulator, sweep state machine) that
     *  {@link #setForm} can't hot-swap. */
    public void formChanged(GenSignalForm f) {
        boolean needsRestart = requiresRestart(lastForm) || requiresRestart(f);
        lastForm = f;
        if (needsRestart && isRunning()) {
            restart();
        } else {
            setForm(f);
        }
    }

    /** Restarts the generator when the {@code .dpd} for {@code slotForm} changes
     *  and that form is the one currently playing - so a load / clear of the
     *  correction file takes effect immediately. */
    public void dpdChanged(GenSignalForm slotForm) {
        if (isRunning() && Preferences.instance().getGenSignalForm() == slotForm) {
            restart();
        }
    }

    private boolean requiresRestart(GenSignalForm f) {
        // Both dual-tone forms stand up a dedicated second DDS accumulator, so
        // entering OR leaving either one needs a full rebuild - a live form-swap
        // would leave the second tone (and its frequency) running.
        return f == GenSignalForm.LINEAR_SWEEP || f == GenSignalForm.LOG_SWEEP
                || f.isDualTone();
    }

    /** Re-applies the FFT-bin snap to the running tone(s) - fired on a
     *  snap toggle, on FFT-length changes and on an FLL reset. */
    public void reapplySnap() {
        Preferences prefs = Preferences.instance();
        RemoteGenerator remote = remote();
        if (remote != null) {
            // The bench owns the snap (spec 4.5's gen.fftGrid), so it is told
            // the new grid rather than being handed a frequency this process
            // computed; the trim reset is what slides the tones back onto it,
            // and it is what an FLL reset means.
            remote.fftGrid(prefs.getFftLength(), prefs.isGenSnapToFftBin());
            remote.trimReset();
            return;
        }
        setFrequency(commandedFrequency());
        if (prefs.getGenSignalForm().isDualTone()) {
            setDualToneFrequency2(snapDualTone(prefs.getGenDualToneFreq2Hz()));
        }
    }

    // -------------------------------------------------------------------------
    // Amplitude ceiling
    // -------------------------------------------------------------------------

    /** Highest output V RMS that still does NOT clip, for {@code form} at the
     *  current DAC full scale.  The generator scales a waveform by
     *  {@code amplitude = Vrms / (fsPeak · rawRms(form))} and clips above 1, so
     *  digital full scale sits exactly at {@code Vrms = fsPeak · rawRms(form)} -
     *  which is why the ceiling follows the waveform (a rectangle may go 3 dB
     *  higher in RMS than a sine, a triangle sits between them, a dual tone
     *  tracks its split).  The amplitude field caps itself with this, so V, dBV
     *  and dBFS all trim to the same maximum and 0 dBFS is the top of the range. */
    public double maxAmplitudeVrms(GenSignalForm form) {
        return Preferences.instance().getDacFsVoltageAmpl() * rmsPerPeak(form);
    }

    /** RMS of the unit-amplitude waveform - the peak->RMS factor behind
     *  {@link #maxAmplitudeVrms}.  Mirrors the signal generator's own raw-RMS
     *  table (private there); keep the two in step if a waveform is added. */
    private double rmsPerPeak(GenSignalForm form) {
        return switch (form) {
            case SINE, SINE_COMP, LINEAR_SWEEP, LOG_SWEEP -> 1.0 / Math.sqrt(2.0);
            case TRIANGLE                                 -> 1.0 / Math.sqrt(3.0);
            case RECTANGLE, WHITE_NOISE                   -> 1.0;
            case PINK_NOISE                               -> 1.0 / Math.sqrt(PINK_OCTAVES + 1.0);
            case PINK_NOISE_LINEAR                        -> 1.0 / Math.sqrt(3.0 * (PINK_OCTAVES + 1.0));
            case DUAL_TONE, DUAL_TONE_COMP                -> dualToneRmsPerPeak();
        };
    }

    /** Two-tone crest factor for the current split: the tones are uncorrelated,
     *  so {@code RMS = √((w₁² + w₂²) / 2)} while their peaks still sum to one. */
    private double dualToneRmsPerPeak() {
        double w1 = Math.max(0.0, Math.min(100.0,
                Preferences.instance().getGenDualToneSplitPct())) / 100.0;
        double w2 = 1.0 - w1;
        return Math.max(1e-12, Math.sqrt((w1 * w1 + w2 * w2) / 2.0));
    }

    // -------------------------------------------------------------------------
    // Signal math (FFT-bin snap, period samples, emitted tones)
    // -------------------------------------------------------------------------

    /**
     * The frequency the primary tone is actually EMITTED at - the pane's bracket
     * label and the predistortion wizard's align target.
     *
     * <p>With a remote generator that is the bench's own answer (post-snap,
     * post-trim), which is what spec 4.5 requires of every consumer of an
     * emitted frequency; a bench that reports none for this waveform - a sweep,
     * the noise forms - falls through to the commanded value, exactly as the
     * local path reports it.  Locally it is {@link #commandedFrequency()}
     * unchanged.
     */
    public double effectiveFrequency() {
        RemoteGenerator remote = remote();
        if (remote != null) {
            double emitted = remote.state().emitHz();
            if (emitted > 0) return emitted;
        }
        return commandedFrequency();
    }

    /** The second tone's emitted frequency, the dual-tone companion of
     *  {@link #effectiveFrequency()}. */
    public double effectiveFrequency2() {
        RemoteGenerator remote = remote();
        if (remote != null) {
            double emitted = remote.state().emit2Hz();
            if (emitted > 0) return emitted;
        }
        return snapDualTone(Preferences.instance().getGenDualToneFreq2Hz());
    }

    /** The frequency the primary DDS should emit for the current prefs -
     *  the bin-snapped value when snap-to-FFT-bin applies (SINE/DUAL_TONE),
     *  else the raw entered value.  Mirrors {@link #start()}'s own
     *  resolution; this is what is COMMANDED, never what came back. */
    public double commandedFrequency() {
        Preferences prefs = Preferences.instance();
        GenSignalForm form = prefs.getGenSignalForm();
        return emitFrequency(prefs, form, outputRateHz(), analysisRateHz(),
                rawFrequency(prefs, form));
    }

    /**
     * The frequencies the generator is actually emitting.
     *
     * <p>A remote bench's answer wins: it snapped against the rate ITS lane is
     * clocked at and it holds the FLL trims, so a client recomputing the pair
     * from its own preferences would point every hint at a frequency the
     * hardware is not on - and reconstruct a beat that slowly walks away from
     * the capture.  {@code sampleRate} is the analyzer's, used only by the local
     * answer, which falls back to the lane's own rate.
     *
     * <p>{@code 0.0} is not a frequency in either answer: it says this waveform
     * emits no such tone (a sweep and the noise forms have no tone 1, every
     * single-tone form has no tone 2), and a consumer must not draw a hint for
     * it.  That is the far end's contract (spec 4.5) and the local answer keeps
     * it so both look the same to a reader.
     */
    public double[] emittedHz(Integer sampleRate) {
        RemoteGenerator remote = remote();
        if (remote != null) {
            RemoteGenerator.State state = remote.state();
            if (state.emitHz() > 0 || state.emit2Hz() > 0) {
                return new double[] {state.emitHz(), state.emit2Hz()};
            }
        }
        // The rate is used AS the analysis rate below, so a caller that names
        // none falls back to the analysis rate - never the DAC's, which would
        // reintroduce the wrong grid through the back door.
        int rate = (sampleRate == null || sampleRate <= 0) ? analysisRateHz() : sampleRate;
        return localTonesHz(Preferences.instance(), rate);
    }

    /** What THIS process's generator emits for the current preferences: the
     *  local half of {@link #emittedHz}, and the only place that arithmetic
     *  exists - an analyzer with no generator to ask has no emitted tone to
     *  report, not a pair it may re-derive on its own. */
    private double[] localTonesHz(Preferences prefs, int sampleRate) {
        GenSignalForm form = prefs.getGenSignalForm();
        if (form.isDualTone()) {
            return new double[] {
                FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE, sampleRate,
                        prefs.getFftLength(), prefs.isGenSnapToFftBin(),
                        prefs.getGenDualToneFreq1Hz()),
                FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE, sampleRate,
                        prefs.getFftLength(), prefs.isGenSnapToFftBin(),
                        prefs.getGenDualToneFreq2Hz())
            };
        }
        // The noise forms have no tone at all and a sweep is a different one every
        // sample - the same pair a remote bench reports 0 for.
        boolean singleTone = form.isPeriodic()
                && form != GenSignalForm.LINEAR_SWEEP && form != GenSignalForm.LOG_SWEEP;
        return new double[] {
            // sampleRate is the ANALYZER's here (see emittedHz), so it is the
            // rate the snap belongs on; the DDS period alignment stays on the
            // lane's own clock.
            singleTone ? emitFrequency(prefs, form, outputRateHz(), sampleRate,
                    prefs.getGenFrequencyHz()) : 0.0,
            0.0
        };
    }

    /**
     * The exact frequency the DDS must be driven at for {@code form}.
     *
     * <p>A {@link GenSignalForm#RECTANGLE} or {@link GenSignalForm#TRIANGLE} can
     * only place its hard edge (or its duty corner, a derivative discontinuity
     * with the same problem) ON a sample, so both run at the nearest
     * integer-sample-period frequency; every other form is exact at any frequency
     * and takes the optional FFT-bin snap instead.
     *
     * <p>The two corrections answer to DIFFERENT clocks, which is why they take
     * separate rates.  A whole number of samples per period is a property of the
     * DAC that emits them, so it uses {@code dacRateHz}.  An FFT bin is a
     * property of the ANALYSIS, whose grid is {@code fs_in / N} on the captured
     * signal, so the snap uses {@code analysisRateHz}: a tone snapped to the
     * output clock lands between bins whenever the two rates differ, and the
     * whole point of the snap is that it does not.
     */
    private double emitFrequency(Preferences prefs, GenSignalForm form,
                                 int dacRateHz, int analysisRateHz, double raw) {
        if (form == GenSignalForm.RECTANGLE || form == GenSignalForm.TRIANGLE) {
            return samplePeriodAlignedHz(raw, dacRateHz);
        }
        return FftBinSnap.snapIfEnabled(form, analysisRateHz, prefs.getFftLength(),
                prefs.isGenSnapToFftBin(), raw);
    }

    /** The rate the ANALYSIS runs at - the capture side, whose bin grid the snap
     *  has to hit.  Read live from the current backend's input configuration, the
     *  same source the cached analysis bin bandwidth is derived from, so a rate
     *  change moves the snap with it. */
    public int analysisRateHz() {
        return Preferences.instance().current().getInputSampleRate();
    }

    /** Nearest frequency with a whole number of samples per period -
     *  {@code fs / round(fs/f)}, the period floored at 2 (Nyquist).  Public:
     *  the signal-file export renders at exactly this frequency so a saved
     *  waveform matches the emitted one. */
    public double samplePeriodAlignedHz(double f, int sampleRate) {
        if (f <= 0.0 || sampleRate <= 0) return f;
        int n = Math.max(2, (int) Math.round(sampleRate / f));
        return (double) sampleRate / n;
    }

    /** Samples in one waveform period at the current rate + entered
     *  frequency; always ≥ 2 so duty-cycle math has something to work
     *  with.  Feeds the pane's duty bracket label. */
    public int periodSamples() {
        Preferences prefs = Preferences.instance();
        int sr = outputRateHz();
        double f = prefs.getGenFrequencyHz();
        if (f <= 0.0 || sr <= 0) return 2;
        return Math.max(2, (int) Math.round(sr / f));
    }

    /** Closest frequency the period-aligned forms (RECTANGLE, TRIANGLE)
     *  can produce with an integer-sample period - the pane's Frequency
     *  bracket label.  This is the SAME value {@link #emitFrequency}
     *  drives them at, so the displayed bracket and the emitted tone can
     *  never diverge. */
    public double correctedPeriodAlignedHz() {
        return samplePeriodAlignedHz(Preferences.instance().getGenFrequencyHz(),
                outputRateHz());
    }

    /** Tone 2's bin-snapped frequency for the current grid - dual-tone
     *  companion of {@link #commandedFrequency()}. */
    public double snapDualTone(double rawHz) {
        Preferences prefs = Preferences.instance();
        return FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE, analysisRateHz(),
                prefs.getFftLength(), prefs.isGenSnapToFftBin(), rawHz);
    }

    /** The first tone's raw (as-entered) frequency for {@code form} - tone 1 for
     *  the two-tone waveforms, the single Frequency field for every other. */
    private double rawFrequency(Preferences prefs, GenSignalForm form) {
        return form.isDualTone() ? prefs.getGenDualToneFreq1Hz() : prefs.getGenFrequencyHz();
    }

    /** A sweep length in seconds as samples of the lane's own clock, floored at
     *  the two samples the generator will build a sweep from. */
    private int sweepSamples(double seconds) {
        return Math.max(2, (int) Math.round(seconds * outputRateHz()));
    }

    /** A fade length in seconds as samples of the lane's own clock. */
    private int fadeSamples(double seconds) {
        return Math.max(0, (int) Math.round(seconds * outputRateHz()));
    }

}
