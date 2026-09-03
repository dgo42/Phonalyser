/*
 * Phonalyser web - the generator pane (signal-form combo · freq/amp/duty · dual-tone ·
 * sweep · dither · snap-to-bin · .dpd corrections · Play/ON-AIR · Save-to · file player).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/generator/GeneratorPane. Owns the generator controls + their bound
 * handlers and the live DDS Play / file-player lifecycle; reads engine.generator.running (the
 * controller owns the running flag) and reaches the SHELL state it does not own (the shared
 * `busy` re-entrancy guard, the FFT align combo) through injected
 * closures. The generator NumericStepFields (freq / amp / duty /
 * sweep / dual-tone) are built in app.js's initStepFields and reached here via the injected
 * getField; the io decode/save helpers + formLabel/formIcon + sfVal/outRate are injected too.
 * Exposes syncFormUI() + restartGenerator() as public methods for the cross-pane callers (the
 * predistortion-wizard onApply, the fft init seed, the prefs dialog). Status-line writes go
 * straight to #status inline (verbatim from app.js), matching the moved handlers.
 */
import { t, stripMnemonics } from '../i18n/i18n.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { GenChangeCause } from '../enums/gen-change-cause.js';
import { GenSignalForm, isCompensated, isDualTone, isPeriodic } from '../enums/gen-signal-form.js';
import { DdsKernel, quantizePcm, outputLaneGate,
  loadHarmonics, loadIntermod, isDualToneCorrectionFile, rawRms } from './dds-kernel.js';
import * as fileStore from '../io/file-store.js';
import { backendDisplayName } from '../audio/audio-backend-type.js';
import { NumericStepModel, UNIT_FAMILIES, bindStepField, stepFieldFor } from '../widgets/numeric-step-field.js';
import { unitValueEquals } from '../widgets/unit-conversion.js';

/** Generator amplitude floor, V RMS (Java AMP_MIN_VRMS). */
const AMP_MIN_VRMS = 1e-9;
/** Generator frequency floor, Hz (Java GeneratorPane.GEN_FREQ_MIN_HZ). */
const GEN_FREQ_MIN_HZ = 0.01;

/** The ON-AIR tick period (Java GeneratorPane.scheduleOnAirBlink's 500 ms timerExec). The
 *  desktop toggles the banner's colour here; the web's banner blinks in CSS, so what is left is
 *  the lane-death consult that rides the same tick - the pane's existing visual sync point. */
const ON_AIR_TICK_MS = 500;

export class GeneratorPane {
  /**
   * @param engine the AudioEngine (DDS generator + file playback lane; live retune/restart).
   * @param prefs  Preferences.
   * @param deps   {io, WAV_TYPE, formLabel, formIcon, outRate,
   *                isGenRunning, isBusy, setBusy, syncFftAlign}
   *   - io: {pickSaveTarget, writeToTarget, saveScopeCapture, readWav, readAiff, decodeFlac} -
   *       the generator Save-to + file-player decode collaborators.
   *   - WAV_TYPE: the save-picker accept descriptor (shared with the scope save).
   *   - formLabel / formIcon: (form) => the localized label / waveform pictogram src (combo).
   *   - outRate: () => the live output sample rate (the frequency/sweep Nyquist + emit grid).
   *   - isGenRunning: () => engine.generator.running - the controller owns the running flag; the pane only reads it.
   *   - isBusy / setBusy: the shared async re-entrancy guard accessors (start/stop serialize).
   *   - syncFftAlign: () => re-gate the FFT align combo (snap gates it; FftTabControl).
   */
  constructor(engine, prefs, { io, WAV_TYPE, formLabel, formIcon, outRate,
    isGenRunning, isBusy, setBusy, syncFftAlign, showAlert }) {
    this.engine = engine;
    this.prefs = prefs;
    // The pane's NumericStepFields by input id (Java GeneratorPane builds its own: frequency,
    // amplitude, dither, duty, the dual-tone split, the sweep fields, the export duration),
    // built in buildStepFields() once the page's unit suffixes resolve.
    this._fields = {};
    // Re-applies the waveform-aware amplitude ceiling; set by buildStepFields, called from the
    // dual-tone split fields' onChange (they feed the ceiling).
    this._syncAmpMax = () => {};
    // The shell's one alert surface, with the same console fallback every other holder of it
    // keeps (preferences-dialog.js, tune-notch-wizard.js, calibration-dialog.js).
    this._showAlert = showAlert || ((title, message) => console.warn(title, message));
    this.io = io;
    this.WAV_TYPE = WAV_TYPE;
    this._formLabel = formLabel;
    this._formIcon = formIcon;
    this._outRate = outRate;
    this._isGenRunning = isGenRunning;
    this._isBusy = isBusy;
    this._setBusy = setBusy;
    this._syncFftAlign = syncFftAlign;
    // The .dpd display filename per compensated form (session-only; the .dpd text itself
    // lives in the per-form genDpd pref so it survives a reload).
    this.genCorrNames = {};
    // The loaded file-player signal: { channels:[Float32Array,Float32Array], sampleRate }.
    this.genFileSig = null;

    // FreqResp measurement lifecycle (Java GeneratorPane freqRespStarted/StoppedListener):
    // the GeneratorController stops both engines in its OWN subscription; here only the
    // visuals - clear the play LEDs + ON-AIR and gray both Play buttons for the sweep.
    const bus = MessageBus.instance();
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, () => this.onFreqRespMeasurementStarted());
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, () => this.onFreqRespMeasurementStopped());
    // Output device lost / failed to open (GeneratorController._reportDeviceError, direction
    // 'output'): the controller stays UI-free and expects the pane to reset its visuals - clear
    // the Play / ON-AIR / file-Play indicators so they don't read "playing" while no line is open.
    bus.subscribe(Events.AUDIO_DEVICE_ERROR, (p) => {
      if (p && p.direction === 'output') {
        $('#genPlay').removeClass('playing');
        this.setOnAir(false);
        this.setGenFileBtn(false);
      }
    });
    // A committed INPUT-rate edit moves the FFT bin grid the snap brackets are computed on, so
    // they are stale until re-rendered - the same follow-up the FFT-length change already makes
    // - and a committed OUTPUT rate moves the Nyquist ceiling of every frequency field (Java
    // GeneratorPane's AUDIO_FORMAT_CHANGED listener).
    bus.subscribe(Events.AUDIO_FORMAT_CHANGED, () => {
      const outNyq = this._outRate() / 2;
      for (const id of ['toneHz', 'tone2Hz', 'sweepStart', 'sweepStop']) {
        if (this._fields[id]) this._fields[id].setMax(outNyq);
      }
      this.refreshFreqLabel();
    });
    // A file played on a BENCH is uploaded first, and that wait needs saying (Java GeneratorPane
    // openUploadNotice / closeUploadNotice). The controller publishes FINISHED on every outcome
    // of the upload, so the notice cannot outlive the transfer it explains.
    bus.subscribe(Events.FILE_UPLOAD_STARTED, () => this.openUploadNotice());
    bus.subscribe(Events.FILE_UPLOAD_FINISHED, () => this.closeUploadNotice());
    // The file player ended on its own - EOF without loop, or the bench ran out (Java
    // GeneratorPane filePlayStoppedListener): reset the play-from LED.
    bus.subscribe(Events.FILE_PLAY_STOPPED, () => this.setGenFileBtn(false));
    // A new FFT length moves the bin grid the snap brackets are computed on (Java GeneratorPane
    // fftLengthListener -> updateFreqLabel + updateDualToneFreqLabels, unconditionally - the
    // controller re-snaps the running tone in its own subscription; the pane only follows with
    // its labels). Without this, changing the length while stopped left a stale "(xxx Hz)".
    bus.subscribe(Events.FFT_LENGTH_CHANGED, () => this.refreshFreqLabel());
  }

  /** FREQRESP_MEASUREMENT_STARTED handler - the controller stops both engines in its own
   *  subscription; here only the visuals: dim and gray both Play buttons while the sweep
   *  drives the DAC (Java GeneratorPane.onFreqRespMeasurementStarted). */
  onFreqRespMeasurementStarted() {
    $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
    this.setGenFileBtn(false);   // also drops the ON-AIR banner (generator flag is off)
    $('#genPlay, #genFilePlay').prop('disabled', true);
  }

  /** Counterpart that re-enables both Play buttons after the sweep
   *  (Java GeneratorPane.onFreqRespMeasurementStopped). */
  onFreqRespMeasurementStopped() {
    $('#genPlay, #genFilePlay').prop('disabled', false);
  }

  /** Push every generator pref into its control widget - the init seed + the FFT-preset
   *  recall mirror-back (app.js applyPrefsToUi delegates here). The toneHz / ampDbfs /
   *  tone2Hz / amp1Pct / amp2Pct / duty fields are owned by their NumericStepField
   *  controllers (seeded in initStepFields); nothing to set for those here. */
  seedGeneratorControls() {
    const prefs = this.prefs;
    $('#signalForm').val(prefs.genSignalForm.get());
    // toneHz / ampDbfs / tone2Hz / amp1Pct / amp2Pct / duty / dither are owned by their
    // NumericStepField controllers (seeded in initStepFields); here only the "Dither" caption's
    // bracketed companion readout is (re)rendered.
    this.updateDitherLabel();
    $('#outputChannel').val(prefs.genOutputChannels.get());
    $('#snap').prop('checked', prefs.genSnapToFftBin.get());
    $('#genFileLoop').prop('checked', prefs.genPlayFromLoop.get());   // Java playFromLoopBtn is two-way bound
    this.seedSweepFields();   // sweep params (engine.config + loop) from prefs (numeric fields are stepfields)
    this.restoreDpdFromStore();     // re-seed a persisted .dpd from the file-store before showing the row
    this.refreshCorrectionsRow();   // .dpd slot enable + display for the current form
  }

  // ----- generator: frequency label (bracketed snapped Hz when snap is on) -----
  // Mirrors Java updateFreqLabel: SINE-with-snap shows the bin-snapped frequency.
  // Frequency label (Java GeneratorPane.updateFreqLabel): brackets for RECTANGLE and TRIANGLE
  // (sample-period-aligned Hz, fs/round(fs/f)), or SINE / SINE_COMP with snap-to-FFT-bin on
  // (bin-snapped Hz - SINE_COMP is FLL-aligned to the bin here, see below); every other form
  // (noise, ...) shows the plain "Frequency".
  refreshFreqLabel() {
    const engine = this.engine;
    const sfVal = (id, dflt) => this._sfVal(id, dflt);
    const form = $('#signalForm').val();
    const raw = sfVal('toneHz', 1000);
    const fs = this._outRate();
    const snap = $('#snap').is(':checked');
    // The bracket shows where the tone lands on the ANALYSIS grid, so its bin width is the
    // CAPTURE rate's (inRate/fftSize) - the same rate the emit path snaps to
    // (_genEmitFreq / Java GeneratorController.analysisSampleRate). The output rate is the
    // DAC's and would put the bracket on a different grid whenever the two differ; the
    // sample-period alignment below is the one correction that stays on the DAC's clock.
    // fftSize is read LIVE from the #fftSize select - Java FftBinSnap.snapIfEnabled reads
    // prefs.getFftLength() (the FFT pane's CURRENT length), NOT a cached engine.config.fftSize
    // that only refreshes on (re)start; reading the stale config showed a bracket that diverged
    // from the just-changed FFT length. Java guard: fftSize >= 8 (FftBinSnap.snapIfEnabled).
    const fftSize = parseInt($('#fftSize').val(), 10) || engine.config.fftSize;
    const analysisFs = engine.analysisSampleRate();
    const binW = (analysisFs > 0 && fftSize >= 8) ? analysisFs / fftSize : 0;
    // DUAL_TONE: tone-1 row is "Frequency 1", tone-2 is "Frequency 2"; each gets its
    // bin-snapped value in brackets when snap is on (Java updateDualToneFreqLabels).
    if (isDualTone(form)) {
      const lab = (key, v) => (snap && binW > 0)
        ? `${t(key)}  (${(Math.round(v / binW) * binW).toFixed(3)} Hz)`
        : t(key);
      $('#freqLabel').text(lab('generator.dualTone.freq1', raw));
      $('#tone2Label').text(lab('generator.dualTone.freq2', sfVal('tone2Hz', 1100)));
      return;
    }
    let corrected = null;
    if (form === GenSignalForm.RECTANGLE || form === GenSignalForm.TRIANGLE) {
      corrected = (raw > 0 && fs > 0) ? fs / Math.max(2, Math.round(fs / raw)) : raw;
    } else if ((form === GenSignalForm.SINE || form === GenSignalForm.SINE_COMP) && snap && binW > 0) {
      // SINE_COMP included: Java updateFreqLabel brackets SINE and SINE_COMP alike, and
      // FftBinSnap.snapIfEnabled admits SINE_COMP (4887ecb), so a compensated sine both emits
      // and is labelled with the bin-snapped frequency exactly like plain SINE.
      corrected = Math.round(raw / binW) * binW;
    }
    $('#freqLabel').text(corrected == null
      ? t('generator.frequency')
      : t('generator.frequency.bracket', `${corrected.toFixed(3)} Hz`));
  }

  // Duty label: RECTANGLE and TRIANGLE show the real (sample-grid achievable) duty in brackets - k
  // whole samples of n per period, clamped to [1, n-1] - so the user sees the duty actually emitted,
  // not just the typed value (Java GeneratorPane). Both are driven at the period-aligned grid (fs/N),
  // so RECTANGLE's +1/-1 step edge and TRIANGLE's duty corner land on whole samples; every other form
  // keeps the plain "Duty cycle" label.
  updateDutyLabel() {
    const sfVal = (id, dflt) => this._sfVal(id, dflt);
    const form = $('#signalForm').val();
    if (form !== GenSignalForm.RECTANGLE && form !== GenSignalForm.TRIANGLE) {
      $('#dutyLabel').text(t('generator.dutyCycle'));
      return;
    }
    const raw = sfVal('toneHz', 1000), fs = this._outRate();
    const n = (raw > 0 && fs > 0) ? Math.max(2, Math.round(fs / raw)) : 2;
    let k = Math.round((sfVal('duty', 50) / 100) * n);
    if (n > 1) k = Math.max(1, Math.min(n - 1, k));
    $('#dutyLabel').text(t('generator.dutyCycle.bracket', `${(k * 100 / n).toFixed(3)} %`));
  }

  // "Dither" caption (Java GeneratorPane.updateDitherLabel): append the dither value in the OTHER
  // unit in brackets - dBV when the field shows bits, bits when it shows dBV - mirroring how
  // refreshFreqLabel annotates the Frequency caption. Off shows the plain caption. The dBV side
  // tracks the live DAC full-scale, so this is re-run wherever the dither field is re-seeded or
  // re-rendered (a recalibration, an FFT-window change, a committed edit).
  updateDitherLabel() {
    const f = this._getField('dither');
    const other = f ? f.companionText() : '';
    $('#ditherLabel').text(other ? t('generator.dither.bracket', other) : t('generator.dither'));
  }

  // Compensation (.dpd) row (Java GeneratorPane corrections row): the path field + browse + clear
  // are enabled ONLY for the compensated forms (SINE_COMP / DUAL_TONE_COMP) and show that form's
  // own loaded predistortion file; every other form empties + disables the row. The .dpd text is
  // stored in the per-form genDpd / genDpdDual pref (so it survives a reload); genCorrNames keeps
  // the display filename for the session.
  refreshCorrectionsRow() {
    const form = $('#signalForm').val();
    const comp = isCompensated(form);
    const text = comp ? this.prefs.getGenDpd(form) : null;
    // Show the original .dpd basename - the session map first, else the persisted name pref
    // (survives a reload; the full OS path stays unavailable). Fall back to ✓ only if a .dpd is
    // loaded with no remembered name (e.g. a wizard-applied compensation).
    const label = !comp ? '' : (this.genCorrNames[form] || this.prefs.getGenDpdName(form) || (text ? '✓' : ''));
    $('#genCorrPath').val(label).attr('title', label || '');
    $('#genCorrPath, #genCorrBrowse, #genCorrClear').prop('disabled', !comp);
    $('#genCorrPath').closest('.filefield').css('opacity', comp ? '' : '0.5');
  }
  // The file-store key for the .dpd of `form` - one slot per compensation family (single vs
  // dual tone), matching how prefs.getGenDpd(form) buckets the two forms. Persisted here
  // so the loaded .dpd survives a reload through the SAME file-store module the FFT calibration
  // rows use; the worklet still reads the live text out of prefs.getGenDpd.
  dpdKey(form) { return 'gendpd.' + (isDualTone(form) ? 'dual' : 'single'); }

  bindCorrections() {
    const prefs = this.prefs;
    // ONE hot-load path for all three ways a slot can be written - the browse button, the clear
    // button and the predistortion wizard's Apply - by listening on the per-form .dpd
    // PREFERENCES, exactly as Java's GeneratorController listens on genDpdProperty /
    // genDpdDualProperty and calls lane.dpdChanged. Apply writes only preferences, so before
    // these listeners existed it reached no running generator at all.
    prefs.genDpd.addListener(() => this.dpdChanged(GenSignalForm.SINE_COMP));
    prefs.genDpdDual.addListener(() => this.dpdChanged(GenSignalForm.DUAL_TONE_COMP));
    // The FORM preference funnels too, and this is what settles the Apply ordering: Apply writes
    // the slot FIRST (so a live reaction finds the correction in place) and the form SECOND, so
    // the slot's own listener still sees the OLD form and returns; this one then fires with both
    // written and loads the correction onto the running tone.
    prefs.genSignalForm.addListener((form) => this.dpdChanged(form));
    $('#genCorrBrowse').on('change', async (ev) => {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      const form = $('#signalForm').val();
      try {
        const text = await file.text();
        prefs.setGenDpd(form, text); prefs.setGenDpdName(form, file.name); prefs.save();
        fileStore.put(this.dpdKey(form), file.name, text);   // persist the compensated .dpd (name + contents)
        this.genCorrNames[form] = file.name;
        this.refreshCorrectionsRow();
        // No dpdChanged call here: setGenDpd above is a preference write, and the listener
        // wired in this method carries it to the running DDS.
        $('#status').text('loaded ' + file.name);
      } catch (e) { $('#status').text('load failed: ' + e.message); }
    });
    $('#genCorrClear').on('click', async () => {
      const form = $('#signalForm').val();
      prefs.setGenDpd(form, null); prefs.setGenDpdName(form, null); prefs.save();
      fileStore.remove(this.dpdKey(form));   // clearing removes it from storage
      delete this.genCorrNames[form];
      this.refreshCorrectionsRow();
      // The emptied slot reaches the generator through the same preference listener.
    });
  }

  /** Takes the .dpd of `slotForm` onto the RUNNING generator when that form is the one playing -
   *  so a load or a clear of the correction file takes effect immediately, and only for its own
   *  slot. Java GeneratorLane.dpdChanged; the web has no lane object, so the pane that owns the
   *  row carries the behaviour, over the same two engine messages a compensated start and the
   *  predistortion wizard use.
   *
   *  LIVE, not a restart. The correction tables are published into the running DDS, so a new
   *  correction set never needs the device stopped - and a stop+start would not merely cost the
   *  tone an audible gap, it would be destructive: the restart replays the parameters the
   *  generator was last STARTED with, silently undoing every live amplitude and frequency edit
   *  made since.
   *
   *  An EMPTIED slot is the exception, and ends the tone rather than swapping anything: a
   *  compensated form IS its corrections, so with no table it would emit the plain waveform under
   *  the compensated label and the distortion measured on it would be recorded as predistorted -
   *  the reading a start with an empty slot refuses to produce. Both compensated forms are ended
   *  alike: the dual-tone slot is no more optional than the sine's, since a plain two-tone pair
   *  under that label has its INTERMODULATION recorded as predistorted. */
  async dpdChanged(slotForm) {
    // Only a COMPENSATED form has a slot to change - Java reaches dpdChanged from the two slot
    // properties alone, and the web's form-preference listener would otherwise hand it any form.
    if (!isCompensated(slotForm)) return;
    // The guard reads the form PREFERENCE, not the combo: it is the value the writes commit to
    // and the one Java's lane consults (prefs.getGenSignalForm() != slotForm), so a slot change
    // and a form change land in the same order however the DOM is synced afterwards.
    if (!this.engine.generatorOn || this.prefs.genSignalForm.get() !== slotForm) return;
    const text = this.prefs.getGenDpd(slotForm);
    if (text) {
      // The same message a compensated start sends (dpdText + dpdFrequency): the sink's kernel
      // parses the text and applies the tables at the effective frequency.
      this.engine.postGen({ dpdText: text, dpdFrequency: this.engine.config.frequency });
      return;
    }
    await this.engine.stopGenerator();
    // The play LED and the ON-AIR indicator follow the generator, which just stopped without
    // going through the play button (Java syncPlayButtonVisuals after the lane's own stop).
    $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
    this.setOnAir(false);
  }

  // Restore any .dpd persisted through the file-store into the live prefs slot on startup:
  // the worklet reads the .dpd from prefs.getGenDpd, so re-seed prefs from the file-store when the
  // prefs slot is empty (e.g. cleared prefs but retained file-store). Idempotent - a slot already
  // holding the same text is left untouched. Mirrors how the FFT calibration rows restore.
  restoreDpdFromStore() {
    const prefs = this.prefs;
    for (const [form, key] of [[GenSignalForm.SINE_COMP, this.dpdKey(GenSignalForm.SINE_COMP)],
      [GenSignalForm.DUAL_TONE_COMP, this.dpdKey(GenSignalForm.DUAL_TONE_COMP)]]) {
      const stored = fileStore.get(key);
      if (!stored) continue;
      if (prefs.getGenDpd(form) == null) { prefs.setGenDpd(form, stored.data); prefs.setGenDpdName(form, stored.name); }
      this.genCorrNames[form] = stored.name;
    }
    prefs.save();
  }

  // Show/hide form-dependent generator fields (dual-tone second tone/split, duty).
  syncFormUI() {
    const prefs = this.prefs;
    const stepFields = { toneHz: this._getField('toneHz'), duty: this._getField('duty') };
    const form = $('#signalForm').val();
    const dual = isDualTone(form);
    const sweep = form === GenSignalForm.LINEAR_SWEEP || form === GenSignalForm.LOG_SWEEP;
    $('#tone2Wrap, #amp2Wrap').toggle(dual);
    // Frequency row visible for every form except sweeps; in DUAL_TONE it IS the tone-1 row
    // (refreshFreqLabel relabels it "Frequency 1"). Java keeps tone 1 editable in dual mode.
    $('#freqLabel').toggle(!sweep);
    $('#toneHz').closest('.numfield').toggle(!sweep);
    // Frequency field stays VISIBLE but DISABLED for noise forms (Java freqField.setEnabled).
    if (stepFields.toneHz) stepFields.toneHz.setDisabled(!isPeriodic(form));
    // Freq-1 field follows its per-form pref (dual-tone tone 1 vs single-tone), so each remembers
    // its own value/default (1 kHz single, 19 kHz dual tone 1).
    if (stepFields.toneHz) stepFields.toneHz.setValue(dual ? prefs.genDualToneFreq1Hz.get() : prefs.genFrequencyHz.get());
    // The reused tone-1 row carries the dual-tone freq-1 tooltip in DUAL_TONE (Java's separate
    // dualToneFreq1Field tooltip); restore the single-tone frequency tooltip otherwise. Set the
    // data-i18n-title attr + re-run the title refresh so the live title tracks the form.
    const toneTip = dual ? 'generator.dualTone.freq1.tooltip' : 'generator.frequency.tooltip';
    $('#toneHz').attr('data-i18n-title', toneTip).attr('title', stripMnemonics(t(toneTip)));
    $('#sweepWrap').css('display', sweep ? 'grid' : 'none');   // sweep params: 2-col grid (Java sweepPanel) when on
    // Duty row stays ALWAYS laid out (Java updateDutyFieldEnabled) - never hidden by form.
    // Only enable/disable the duty field + grey its label per form (RECTANGLE / TRIANGLE).
    const duty = form === GenSignalForm.RECTANGLE || form === GenSignalForm.TRIANGLE;
    if (stepFields.duty) stepFields.duty.setDisabled(!duty);
    $('#dutyLabel').toggleClass('disabled', !duty);
    // Each duty-aware form remembers its own duty - reload the field from the form's pref
    // (Java reloadDutyForForm).
    if (form === GenSignalForm.TRIANGLE && stepFields.duty) stepFields.duty.setValue(prefs.genTriangleDuty.get() * 100);
    else if (form === GenSignalForm.RECTANGLE && stepFields.duty) stepFields.duty.setValue(prefs.genRectangleDuty.get() * 100);
    // Snap checkbox visible for SINE and DUAL_TONE; hidden for sweeps (setSnapBtnVisible).
    $('#snap').closest('.form-check').toggle(!sweep);
    // The scope's Reconstructed-beat checkbox is re-gated by the SCOPE layer on
    // GENERATOR_SIGNAL_CHANGED (Java ScopeTabControl.syncReconstructedBeatEnabled) - the
    // generator does NOT reach across panes to mutate #scopeTrigBeat.
    this.refreshFreqLabel();   // bracket annotation is form-dependent (RECTANGLE / TRIANGLE / SINE+snap / dual)
    this.updateDutyLabel();    // RECTANGLE / TRIANGLE show the sample-quantised duty; others plain
    this.refreshCorrectionsRow();   // .dpd slot enabled + shown only for compensated forms
    this.syncFormCombo();
  }

  // Seed the sweep fields from prefs into BOTH the UI and engine.config (so the next start
  // uses the saved values), and bind their change handlers (write pref + config + live retune).
  seedSweepFields() {
    const prefs = this.prefs, engine = this.engine;
    // The five numeric sweep fields are NumericStepFields seeded in initStepFields; here we
    // only mirror the saved params into engine.config (for the first start) + the loop toggle.
    engine.config.sweepStartHz = prefs.genSweepFreqStartHz.get();
    engine.config.sweepEndHz = prefs.genSweepFreqEndHz.get();
    engine.config.sweepDurationSec = prefs.genSweepDurationSec.get();
    engine.config.sweepFadeInSec = prefs.genSweepFadeInSec.get();
    engine.config.sweepFadeOutSec = prefs.genSweepFadeOutSec.get();
    $('#sweepLoop').prop('checked', prefs.genSweepLoop.get());
    engine.config.sweepLoop = prefs.genSweepLoop.get();
  }
  bindSweepFields() {
    const prefs = this.prefs, engine = this.engine;
    // Numeric sweep fields are NumericStepFields (their onChange writes pref+config+retune in
    // initStepFields); only the loop toggle is bound here.
    $('#sweepLoop').on('change', async () => {
      const on = $('#sweepLoop').is(':checked');
      prefs.genSweepLoop.set(on);
      // Java genSweepLoopProperty binding (4887ecb): a running Farina sweep restarts
      // instead of live-editing; the restart's readConfig picks the new loop flag up.
      if (await this.restartFarinaOnParamChange()) return;
      engine.config.sweepLoop = on; engine.retuneGenerator();
    });
  }

  /** Faithful port of GeneratorController.restartFarinaOnParamChange (4887ecb): a Farina
   *  (LOG) sweep can't live-edit its pre-rendered buffer without the playback dropping to
   *  silence, so a sweep-parameter change while it is running does a full restart instead -
   *  the tone resumes with the new parameters (restartGenerator re-reads the just-committed
   *  UI via readConfig, as Java's start() rebuilds from the committed prefs). Returns
   *  {@code true} when it restarted, so the caller skips the live setter + retune. */
  async restartFarinaOnParamChange() {
    if (this._isGenRunning() && this.prefs.genSignalForm.get() === GenSignalForm.LOG_SWEEP) {
      await this.restartGenerator();
      return true;
    }
    return false;
  }

  // ----- custom signal-form combo: waveform pictogram + label per item -----
  // Builds the dropdown from the hidden native #signalForm <select> (same order =
  // GenSignalForm). Selecting an item writes the hidden select's value and fires its
  // native 'change', so every existing handler (readConfig, syncFormUI, restart,
  // prefs binding) keeps working unchanged.
  buildFormCombo() {
    const formIcon = this._formIcon;
    const $menu = $('#signalFormMenu').empty();
    $('#signalForm option').each((i, opt) => {
      const form = opt.value, label = opt.text;
      $('<li>').append(
        $(`<button type="button" class="dropdown-item form-combo-item">
             <img class="form-combo-ico" src="${formIcon(form)}" alt=""/>
             <span>${label}</span>
           </button>`).on('click', () => {
          const sel = document.getElementById('signalForm');
          if (sel.value !== form) { sel.value = form; sel.dispatchEvent(new Event('change')); }
          else this.syncFormCombo();
        })
      ).appendTo($menu);
    });
    this.syncFormCombo();
  }

  // Mirror the hidden select's current form onto the closed combo button.
  syncFormCombo() {
    const form = $('#signalForm').val();
    if (!form) return;
    $('#signalFormIco').attr('src', this._formIcon(form));
    $('#signalFormLabel').text(this._formLabel(form));
  }

  /** The pane's NumericStepField with input id `id`, or undefined before buildStepFields() /
   *  without the markup. */
  _getField(id) { return this._fields[id]; }

  /** Canonical value of the pane's step field `id`, or the input's plain number / `dflt`
   *  before the field exists (the shell's config snapshot reads the generator through this). */
  fieldValue(id, dflt) {
    const f = this._fields[id];
    if (f) return f.getValue();
    const el = document.getElementById(id);
    return (el && parseFloat(el.value)) || dflt;
  }

  _sfVal(id, dflt) { return this.fieldValue(id, dflt); }

  /** Re-renders every field's unit suffix after a UI-language switch. */
  refreshFields() { for (const f of Object.values(this._fields)) f.refresh(); }

  /** The no-clip amplitude CEILING, in the canonical Vrms the amplitude field stores. The DDS
   *  drives amplitude = Vrms / (fsPeak · rawRms(form)) (dds-kernel), so digital full scale - the
   *  point where the waveform starts to clip - is exactly Vrms = fsPeak · rawRms(form). It is
   *  therefore WAVEFORM-AWARE: a sine tops out at fsPeak/√2, a rectangle at fsPeak, a triangle at
   *  fsPeak/√3, a dual tone at fsPeak·√((w1²+w2²)/2), and so on. It reads the LIVE DAC full-scale
   *  calibration and the LIVE form / dual-tone split on every call - never a value snapshotted at
   *  construction. (Capping at the raw PEAK voltage let a sine entry run √2 (+3 dB) past full
   *  scale.) This one ceiling backs all three unit views - V, dBV and dBFS (where it is exactly
   *  0 dBFS). */
  ampMaxVrms() {
    return this.prefs.getDacFsVoltageAmpl()
      * rawRms(this.prefs.genSignalForm.get(), this.fieldValue('amp1Pct', 50) / 100, this.fieldValue('amp2Pct', 50) / 100);
  }

  /** Builds the pane's NumericStepFields from the `.numfield` DOM (Java GeneratorPane's own
   *  field construction). Each field owns parsing / formatting / clamping / stepping; its onChange
   *  writes the bound preference and live-retunes the engine. Called from the shell's init once
   *  the unit suffixes resolve (after applyI18n). */
  buildStepFields() {
    const prefs = this.prefs;
    const engine = this.engine;
    const F = UNIT_FAMILIES;
    const mk = (id, model, onChange) => {
      const f = stepFieldFor(id, model, onChange);
      if (f) this._fields[id] = f;
      return f;
    };
    const outRate = () => this._outRate();

    // Frequency: FREQUENCY family, PERCENT policy, [GEN_FREQ_MIN_HZ .. outRate/2].
    // toneHz doubles as the single-tone Frequency AND the dual-tone Frequency 1; it writes the
    // matching pref per form (genDualToneFreq1Hz in dual, else genFrequencyHz) - Java keeps them
    // as distinct fields, so their defaults (1 kHz vs 19 kHz) differ.
    const fTone = mk('toneHz', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
      (v) => {
        if (isDualTone($('#signalForm').val())) prefs.genDualToneFreq1Hz.set(v);
        else prefs.genFrequencyHz.set(v);
        engine.config.toneHz = v; engine.retuneGenerator(); this.refreshFreqLabel(); this.updateDutyLabel();
      });
    if (fTone) fTone.setValue(prefs.genFrequencyHz.get());

    const fTone2 = mk('tone2Hz', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
      (v) => { prefs.genDualToneFreq2Hz.set(v); engine.config.tone2Hz = v; engine.retuneGenerator(); this.refreshFreqLabel(); });
    if (fTone2) fTone2.setValue(prefs.genDualToneFreq2Hz.get());

    // Amplitude: AMPLITUDE family, PERCENT policy. What is STORED is the entered value - the
    // displayed number and its unit - and the store resolves it to V RMS at use, so a dBFS entry
    // still means what was typed after a recalibration. fsAmplSupplier (the live DAC PEAK full
    // scale) enables that dBFS entry - 0 dBFS is a full-scale SINE (AES17), anchored at fsPeak/√2.
    const fAmp = mk('ampDbfs', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: this.ampMaxVrms(), maxDecimals: 5,
      fsAmplSupplier: () => prefs.getDacFsVoltageAmpl() }),
      (v) => {
        // The pair the operator entered - number and unit as ONE write, so nothing
        // downstream can see the new unit against the old number.
        prefs.genAmplitude.set(fAmp.enteredValue());
        engine.config.ampVrms = v; engine.retuneGenerator();
      });
    if (fAmp) {
      fAmp.seedPair(prefs.genAmplitude.get());
      // Keep the no-clip ceiling live (Bindings.onChange(... ampField::setMax)): it moves with the
      // DAC full-scale CALIBRATION and with the waveform (and its dual-tone split), so re-apply it
      // on every input that feeds ampMaxVrms(). setMax re-clamps the current value, so switching to
      // a form with a lower headroom (e.g. rectangle -> sine) trims an now-over-scale amplitude
      // down to the new max.
      this._syncAmpMax = () => fAmp.setMax(this.ampMaxVrms());
      prefs.dacFsVoltageAmpl.addListener(this._syncAmpMax);
      prefs.genSignalForm.addListener(this._syncAmpMax);
    }

    // Dither depth: DITHER-policy NumericStepField (whole/fractional bits OR a full-scale-aware dBV
    // VIEW of the same value) - Java GeneratorPane ditherField. fsAmplSupplier = the DAC PEAK
    // full-scale (Vpeak) so the dBV view tracks recalibration. The dBV is the PHYSICAL TPDF level
    // relative to that full-scale - window-invariant, NO FFT-window term: since the analyser's NENBW
    // correction the integrated noise metrics (N, SNR, ...) read the true level, so the entered dBV
    // checks against them with any analysis window. maxBits = 32. The TPDF dither is applied LIVE to
    // the generated signal in the dds worklet (Java PcmQuantizer live-apply) so it shows on the FFT
    // floor exactly where the dBV view sets it - hence the engine push below, mirroring the
    // amplitude field. Seed the ENTERED value BEFORE the change path re-enters (seedPair replays it
    // through commit without firing onChange). On a committed change: persist the entered value,
    // push the depth to the running worklet, then re-annotate the "Dither" caption.
    const fDither = mk('dither', new NumericStepModel({ family: F.DITHER, maxBits: 32,
      fsAmplSupplier: () => prefs.getDacFsVoltageAmpl() }),
      (v) => {
        prefs.genDither.set(fDither.enteredValue());
        engine.config.ditherBits = v; engine.retuneGenerator();
        this.updateDitherLabel();
      });
    if (fDither) {
      fDither.seedPair(prefs.genDither.get());
      // Render the "Dither" caption's companion-unit bracket NOW: the init seed ran before this
      // field existed (updateDitherLabel found none), so without this the bracket stays empty
      // until the first change.
      this.updateDitherLabel();
    }

    // Duty / dual-tone amplitude split: PERCENT family + PERCENT policy.
    const fDuty = mk('duty', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
      () => {
        const duty = (this.fieldValue('duty', 50) || 50) / 100;
        const form = $('#signalForm').val();
        // Write ONLY the active form's duty pref - each form remembers its own (Java dutyField).
        if (form === GenSignalForm.TRIANGLE) prefs.genTriangleDuty.set(duty);
        else if (form === GenSignalForm.RECTANGLE) prefs.genRectangleDuty.set(duty);
        engine.config.rectDuty = duty; engine.config.triDuty = duty;
        engine.retuneGenerator();
        this.updateDutyLabel();
      });
    if (fDuty) {
      fDuty.setValue(((prefs.genSignalForm.get() === GenSignalForm.TRIANGLE
        ? prefs.genTriangleDuty.get() : prefs.genRectangleDuty.get()) || 0.5) * 100);
    }

    const fAmp1 = mk('amp1Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
      (v) => { this.applyDualToneAmpSplit('amp1Pct', v); this._syncAmpMax(); });
    if (fAmp1) fAmp1.setValue(prefs.genDualToneSplitPct.get());
    const fAmp2 = mk('amp2Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
      (v) => { this.applyDualToneAmpSplit('amp2Pct', v); this._syncAmpMax(); });
    if (fAmp2) fAmp2.setValue(100 - prefs.genDualToneSplitPct.get());

    // Sweep fields (LINEAR_SWEEP / LOG_SWEEP) - NumericStepField like the scope's, faithful to
    // Java sweep*Field: Start/Stop FREQUENCY [0.01 .. Nyquist] 9 dec; Duration/Fade-in/Fade-out
    // TIME (min 0.001 / 0, 3 dec). Wheel/arrow/keyboard stepping + unit auto-ranging come free.
    // Java GeneratorController sweep-pref bindings (4887ecb): a running Farina (LOG) sweep
    // can't live-edit its pre-rendered buffer, so a param change does a full restart (the
    // restart's readConfig picks the new value up) and skips the live setter + retune.
    const sweepOnChange = (pref, cfgKey) => async (v) => {
      pref.set(v);
      if (await this.restartFarinaOnParamChange()) return;
      engine.config[cfgKey] = v; engine.retuneGenerator();
    };
    const fSwStart = mk('sweepStart', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
      sweepOnChange(prefs.genSweepFreqStartHz, 'sweepStartHz'));
    if (fSwStart) fSwStart.setValue(prefs.genSweepFreqStartHz.get());
    const fSwStop = mk('sweepStop', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
      sweepOnChange(prefs.genSweepFreqEndHz, 'sweepEndHz'));
    if (fSwStop) fSwStop.setValue(prefs.genSweepFreqEndHz.get());
    const fSwDur = mk('sweepDur', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
      sweepOnChange(prefs.genSweepDurationSec, 'sweepDurationSec'));
    if (fSwDur) fSwDur.setValue(prefs.genSweepDurationSec.get());
    const fSwFi = mk('sweepFadeIn', new NumericStepModel({ family: F.TIME, min: 0, max: 1000000, maxDecimals: 3 }),
      sweepOnChange(prefs.genSweepFadeInSec, 'sweepFadeInSec'));
    if (fSwFi) fSwFi.setValue(prefs.genSweepFadeInSec.get());
    const fSwFo = mk('sweepFadeOut', new NumericStepModel({ family: F.TIME, min: 0, max: 1000000, maxDecimals: 3 }),
      sweepOnChange(prefs.genSweepFadeOutSec, 'sweepFadeOutSec'));
    if (fSwFo) fSwFo.setValue(prefs.genSweepFadeOutSec.get());
    // WAV-export duration - TIME NumericStepField (Java durationField).
    const fGenDur = mk('genDuration', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
      (v) => prefs.genWavDurationSeconds.set(v));
    if (fGenDur) fGenDur.setValue(prefs.genWavDurationSeconds.get());

    // Two-way bind the fields to their prefs (external changes update the field).
    bindStepField(fTone, prefs.genFrequencyHz);
    bindStepField(fTone2, prefs.genDualToneFreq2Hz);
    // The amplitude and the dither are bound as ENTERED values, so an external write replays the
    // pair and the field shows the unit it was stored in - a canonical writer (a calibration, a
    // preset) stores the base unit, which replays as volts or bits exactly as before.
    if (fAmp) {
      prefs.genAmplitude.addListener((v) => {
        if (!unitValueEquals(fAmp.enteredValue(), v)) fAmp.seedPair(v);
      });
    }
    if (fDither) {
      prefs.genDither.addListener((v) => {
        if (!unitValueEquals(fDither.enteredValue(), v)) fDither.seedPair(v);
      });
    }
    bindStepField(fSwStart, prefs.genSweepFreqStartHz);
    bindStepField(fSwStop, prefs.genSweepFreqEndHz);
    bindStepField(fSwDur, prefs.genSweepDurationSec);
    bindStepField(fSwFi, prefs.genSweepFadeInSec);
    bindStepField(fSwFo, prefs.genSweepFadeOutSec);
    bindStepField(fGenDur, prefs.genWavDurationSeconds);
  }

  /** Couple the two dual-tone amplitude % fields to sum to 100 % (Java
   *  applyDualToneAmpSplit); track Freq-1's share in genDualToneSplitPct. */
  applyDualToneAmpSplit(edited, value) {
    const other = edited === 'amp1Pct' ? 'amp2Pct' : 'amp1Pct';
    const counterpart = Math.max(0, Math.min(100, 100 - value));
    const otherField = this._getField(other);
    if (otherField) otherField.setValue(counterpart);
    const split = edited === 'amp1Pct' ? value : counterpart;
    this.prefs.genDualToneSplitPct.set(split);
    this.retuneDualAndDuty();
  }

  /** Snapshots the generator's live controls into the shared engine config - what a (re)start
   *  and a live retune read (Java GeneratorController rebuilds from the committed prefs at
   *  start). The GENERATOR's part only: the capture / playback devices and rates are the
   *  Preferences dialog's (written on scan and on OK), the FFT geometry the FFT strip's, the
   *  scope window the scope pane's. Run once at load and on every generator control the field
   *  handlers do not push live, so the config the analysers consult through GENERATOR_EMITTED_HZ
   *  is current before the generator ever started. */
  readConfig() {
    const c = this.engine.config, prefs = this.prefs;
    c.toneHz = this.fieldValue('toneHz', 1000); c.ampVrms = this.fieldValue('ampDbfs', 0.5);
    c.form = $('#signalForm').val();
    c.snapToBin = $('#snap').is(':checked');   // gates the SINE/DUAL_TONE FFT-bin emit snap
    // Compensated forms carry the loaded .dpd text (per-form slot) for the worklet to apply.
    c.dpdText = isCompensated(c.form) ? (prefs.getGenDpd(c.form) || null) : null;
    c.tone2Hz = this.fieldValue('tone2Hz', 1100);
    c.amp1Pct = this.fieldValue('amp1Pct', 50); c.amp2Pct = this.fieldValue('amp2Pct', 50);
    const duty = (this.fieldValue('duty', 50) || 50) / 100;
    c.rectDuty = duty; c.triDuty = duty;
    c.ditherBits = this.fieldValue('dither', 0);   // fractional bits from the DITHER NumericStepField (0 = Off)
    // Sweep params (LINEAR_SWEEP / LOG_SWEEP) - read from the sweep fields.
    c.sweepStartHz = this.fieldValue('sweepStart', 20);
    c.sweepEndHz = this.fieldValue('sweepStop', 20000);
    c.sweepDurationSec = Math.max(0.001, this.fieldValue('sweepDur', 1));
    c.sweepFadeInSec = Math.max(0, this.fieldValue('sweepFadeIn', 0));
    c.sweepFadeOutSec = Math.max(0, this.fieldValue('sweepFadeOut', 0));
    c.sweepLoop = $('#sweepLoop').is(':checked');
    // The bin grid the snap lands on: the FFT length the FFT strip keeps in its preference
    // (Java FftBinSnap.snapIfEnabled reads prefs.getFftLength()).
    c.fftSize = prefs.fftLength.get();
    // Calibration anchor (the DAC full scale scales the DDS amplitude) and the output-lane
    // routing (Java GeneratorController.pushOutputRoutingToPlayback): the lane gate + the
    // right-lane scale (= fsLeft/fsRight). Left stays the mono amplitude reference (scale 1.0),
    // so the DDS amplitude math is unchanged - only the interleave seam gates/scales.
    c.dacFsVoltageAmpl = prefs.dacFsVoltageAmpl.get();
    c.outputChannels = prefs.genOutputChannels.get();
    c.rightLaneScale = prefs.dacRightLaneScale();
  }

  /** Push duty + dual-tone split into the engine and live-retune. */
  retuneDualAndDuty() {
    const sfVal = (id, dflt) => this._sfVal(id, dflt);
    const c = this.engine.config;
    c.tone2Hz = sfVal('tone2Hz', 1100);
    c.amp1Pct = sfVal('amp1Pct', 50); c.amp2Pct = sfVal('amp2Pct', 50);
    const duty = (sfVal('duty', 50) || 50) / 100; c.rectDuty = duty; c.triDuty = duty;
    this.engine.retuneGenerator();
  }

  // A structural GENERATOR change (form) restarts ONLY the generator consumer; a
  // structural FFT change re-acquires ONLY the FFT consumer - neither disturbs the
  // other two lifecycles (Java: a form change is GeneratorController's concern, an
  // FFT-length change is FftController's, and the scope keeps running throughout).
  async restartGenerator() {
    if (this._isBusy() || !this._isGenRunning()) return null;
    this._setBusy(true);
    try {
      await this.engine.stopGenerator(); this.readConfig();
      // Returns the LOCALIZED start-error reason (null on success), mirroring Java
      // controller.getLastStartError() - the form handler surfaces a failed restart.
      return await this.engine.startGenerator();
    } finally { this._setBusy(false); }
  }

  bind() {
    const prefs = this.prefs;
    const engine = this.engine;
    const io = this.io;
    const sfVal = (id, dflt) => this._sfVal(id, dflt);

    // ----- GENERATOR_SIGNAL_CHANGED publisher (Java GeneratorController.bindPreferences /
    // publishSignalChanged) -----
    // Every signal-affecting generator pref publishes USER_INPUT exactly once where the emitted
    // signal changes. Scope-side reactions (reset measurement history, re-gate the reconstructed-
    // beat checkbox) hang off this event in the SCOPE layer - the generator never reaches into a
    // scope widget. The web has no closed-loop FLL trim path, so only the USER_INPUT cause is
    // emitted here; the FLL_TRIM cause is kept for fidelity with the bus contract.
    // The dither, genOutputChannels and the two DAC full-scale prefs join the list per Java's
    // dither/routing fix (GeneratorController.setDitherBits + the dacFsVoltageAmpl/dacFsVoltageAmplRight/
    // genOutputChannels listeners each now publishSignalChanged): a dither, output-routing or DAC-
    // full-scale change restarts the FFT stats/accumulator and clears the scope persistence.
    // The amplitude and the dither are stored AS ENTERED (number plus unit as ONE value), so each
    // subscribes once and an edit that moves both halves publishes once - never twice, and never
    // with a half-changed value in between.
    for (const pref of [prefs.genSignalForm, prefs.genFrequencyHz, prefs.genDualToneFreq1Hz,
      prefs.genDualToneFreq2Hz, prefs.genSnapToFftBin, prefs.genAmplitude, prefs.genRectangleDuty,
      prefs.genTriangleDuty, prefs.genDualToneSplitPct, prefs.genSweepFreqStartHz, prefs.genSweepFreqEndHz,
      prefs.genSweepDurationSec, prefs.genSweepFadeInSec, prefs.genSweepFadeOutSec, prefs.genSweepLoop,
      prefs.genDither, prefs.genOutputChannels, prefs.dacFsVoltageAmpl, prefs.dacFsVoltageAmplRight]) {
      pref.addListener(() => {
        // Published only while the lane is ON AIR: the event's contract is "the EMITTED
        // signal changed", and with the generator off the output is silence before and
        // after any edit - the FFT/scope statistics keep accumulating. The start itself
        // publishes the silence->tone transition, so edits made while off land then.
        if (this.engine.generatorOn) {
          MessageBus.instance().publish(Events.GENERATOR_SIGNAL_CHANGED, GenChangeCause.USER_INPUT);
        }
      });
    }

    // A DAC recalibration shifts the dBV mapping. The ENTERED value is what holds: replaying it
    // leaves the field text exactly as typed and re-solves the bits underneath, so a dither entered
    // as a level keeps that level and only the companion readout moves. The entered value itself
    // does not change here, so nothing re-publishes from it - dacFsVoltageAmpl is in the publisher
    // list above and is what announces the signal change.
    // Mirrors Java GeneratorPane's Bindings.onChange for dacFsVoltageAmplProperty. Dither is NOT
    // live-applied to the worklet (accepted Web-Audio divergence, like Java's live ag.setDitherBits)
    // - readConfig reads the field fresh at each (re)start and the Save-to export path applies it via quantizePcm.
    const reseedDither = () => {
      const f = this._getField('dither');
      if (f) f.seedPair(prefs.genDither.get());
      this.updateDitherLabel();
    };
    prefs.dacFsVoltageAmpl.addListener(reseedDither);
    // An FFT-window change NEVER touches the dither: bits and dBV are the physical level, window-
    // invariant since the analyser's NENBW correction. A pure re-render (field text + caption) -
    // nothing is re-solved, nothing is stored, nothing restarts; the generated output stays put.
    prefs.fftWindow.addListener(() => {
      const f = this._getField('dither');
      if (f) f.refresh();
      this.updateDitherLabel();
    });

    // Signal-form change is structural (SINGLE↔DUAL_TONE changes generator structure;
    // the kernel's form is set from processorOptions) -> restart the GENERATOR only.
    $('#signalForm').on('change', async () => {
      this.syncFormUI();
      const wasRunning = this._isGenRunning();
      // A not-live-swappable form change restarts the generator; a non-null reason means the
      // restart failed (now stopped) - surface it (Java formCombo wasRunning && !isRunning ->
      // Dialogs.error(generator.error.restart, getLastStartError)). Already operator language:
      // the controller localized it at its one wording boundary.
      const err = await this.restartGenerator();
      if (wasRunning && err) {
        $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
        this.setOnAir(false);
        $('#status').text(t('generator.error.restart') + ': ' + err);
      }
    });

    // ----- generator prefs bindings (Java GeneratorPane) -----
    // ...and keeps the config's form current while the generator is NOT running (the restart
    // above re-reads only a running one): the analysers' GENERATOR_EMITTED_HZ answer reads
    // config.form, and a form picked before the first start must not answer the previous one.
    $('#signalForm').on('change', () => { prefs.genSignalForm.set($('#signalForm').val()); this.readConfig(); });
    // toneHz / ampDbfs / dither prefs are written by their NumericStepField onChange handlers
    // (the dither field writes its ENTERED value - number plus unit - and re-annotates #ditherLabel).
    // Output-lane gate: persist + push the routing to the running worklet (Java
    // GeneratorController.pushOutputRoutingToPlayback on genOutputChannels change). rightLaneScale
    // is recomputed fresh (= fsLeft/fsRight); retuneGenerator is a no-op when nothing is playing.
    $('#outputChannel').on('change', () => {
      prefs.genOutputChannels.set($('#outputChannel').val());
      engine.config.outputChannels = prefs.genOutputChannels.get();
      engine.config.rightLaneScale = prefs.dacRightLaneScale();
      engine.retuneGenerator();
    });
    $('#snap').on('change', () => {
      prefs.genSnapToFftBin.set($('#snap').is(':checked'));
      // Snapshot the LIVE UI into engine.config BEFORE retuning - Java reapplySnap() resolves
      // effectiveFrequency() from live prefs every time: emitFrequency(prefs, form,
      // prefs.current().getOutputSampleRate(), prefs.getGenFrequencyHz()), and FftBinSnap reads
      // prefs.getFftLength(). Setting only config.snapToBin left config.fftSize / outRate / toneHz
      // stale (they refresh only on (re)start), so _genEmitFreq snapped against a stale bin grid -
      // or, with fftSize never refreshed, failed to move the emitted tone at all (the reported bug:
      // the FFT showed the entered freq, not the snapped one).
      this.readConfig();   // refreshes config.snapToBin + config.fftSize + toneHz from the live UI
      engine.retuneGenerator();   // re-resolve the emit frequency (snap now gates SINE/DUAL)
      this.refreshFreqLabel(); this._syncFftAlign();   // snap gates the FFT align combo (FftTabControl)
    });
    this.bindSweepFields();   // sweep loop toggle (numeric fields are stepfields)
    this.bindCorrections();   // .dpd browse / clear -> per-form genDpd pref + live re-apply

    // Surfacing start / restart / playFile failures inline on the #status line (rather than a
    // modal, as Java does via Dialogs.error) is an accepted web idiom.
    // ----- start / stop (the green play triangle = the GENERATOR only) -----
    // Three independent lifecycles now (Java: generator / scope record / FFT record):
    // #genPlay starts/stops the DDS generator; the per-pane Record LEDs drive
    // setScopeRecording / setFftRecording. `busy` is the shared re-entrancy guard -
    // startGenerator/stopGenerator and the consumer acquire/release are async
    // (open/close AudioContexts); a second click mid-transition races and can tear
    // down a half-built audio graph (STATUS_BREAKPOINT). Ignore clicks until settled.
    $('#genPlay').on('click', async () => {
      if (this._isBusy()) return;
      if (!this._isGenRunning()) {
        this._setBusy(true);
        this.readConfig();
        // DDS tone and file playback share the one output device - only one may drive it.
        // Java controller.start() stops the file player first; mirror that + re-sync its LED.
        await engine.stopFile(); this.setGenFileBtn(false);
        try {
          // Light the play LED + ON-AIR only AFTER a successful start (Java syncPlayButtonVisuals
          // reads controller.isRunning() AFTER start()); on failure roll the visuals back and
          // surface the specific reason (Java: getLastStartError -> Dialogs.error).
          const err = await engine.startGenerator();
          if (err) {
            $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
            this.setOnAir(false);
            $('#status').text(err);
          } else {
            $('#genPlay').addClass('playing').attr('title', t('generator.play.stop'));   // lit green play (Java playLit)
            this.setOnAir(true);
          }
        } finally { this._setBusy(false); }
      } else {
        this._setBusy(true);
        $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
        this.setOnAir(false);
        try { await engine.stopGenerator(); } finally { this._setBusy(false); }
      }
    });

    // ----- Generator "Save to..." -> render via DdsKernel, dither, write STEREO WAV/AIFF/FLAC -----
    $('#genSaveBtn').on('click', async () => {
      const c = engine.config;
      // Suggested name encodes signal form + sample rate (kHz) + output bit depth, WAV by
      // default (Java buildSuggestedSaveName: "%s_%dkHz_%dbit.wav").
      const cur = prefs.current();
      const rateKhz = Math.round((parseInt($('#outRate').val(), 10) || cur.outputSampleRate || 48000) / 1000);
      const suggested = `${(c.form || GenSignalForm.SINE).toLowerCase()}_${rateKhz}kHz_${cur.outputBitDepth || 24}bit.wav`;
      // Pick the target FIRST so the chosen file's extension drives the container (Java
      // doSaveToBrowseAndWrite) - WAV / FLAC / AIFF, never the field's stale extension.
      const target = await io.pickSaveTarget($('#genSaveName').val() || suggested, this.WAV_TYPE);
      if (!target) return;
      $('#genSaveName').val(target.name);
      const name = target.name;
      const rate = parseInt($('#outRate').val(), 10) || prefs.current().outputSampleRate || 48000;
      const seconds = Math.max(0.001, sfVal('genDuration', 5) || prefs.genWavDurationSeconds.get() || 5);
      // Export at the configured output bit depth (16/24/32), like Java exportSignal
      // (prefs.current().getOutputBitDepth()) - so a "..._16bit.wav" name truly carries 16-bit PCM.
      const bitDepth = Math.max(8, prefs.current().outputBitDepth || 24);
      const dither = sfVal('dither', 0);   // fractional bits from the DITHER NumericStepField (0 = Off)
      // RECTANGLE and TRIANGLE export at the same sample-period-aligned frequency they play at, so a
      // looped file has no edge/corner seam and the whole-period truncation lands on N samples (Java exportSignal).
      const rawHz = sfVal('toneHz', 1000);
      const emitHz = (c.form === GenSignalForm.RECTANGLE || c.form === GenSignalForm.TRIANGLE)
        ? rate / Math.max(2, Math.round(rate / rawHz)) : rawHz;
      try {
        const kernel = new DdsKernel({
          form: c.form, frequency: emitHz, sampleRate: rate,
          amplitudeVRms: sfVal('ampDbfs', 0.5), dacFsVoltageAmpl: prefs.dacFsVoltageAmpl.get(),
        });
        if (isDualTone(c.form)) {
          // Snap tone 2 to the OUTPUT-rate bin grid (rate/fftSize) when snap-to-bin is on,
          // matching _genEmitFreq2 - the saved file's second tone lands on a bin centre exactly
          // like the live emit (Java GeneratorController snaps both tones).
          const rawF2 = sfVal('tone2Hz', 1100);
          const binF2 = rate / c.fftSize;
          const f2 = (c.snapToBin && c.fftSize >= 8 && rate > 0 && binF2 > 0)
            ? Math.round(rawF2 / binF2) * binF2 : rawF2;
          kernel.setDualToneFrequency2(f2);
          kernel.setDualToneAmplitudes(sfVal('amp1Pct', 50), sfVal('amp2Pct', 50));
        }
        const duty = (sfVal('duty', 50) || 50) / 100;
        kernel.setRectangleDuty(duty); kernel.setTriangleDuty(duty);
        // Compensated forms: apply the loaded .dpd so the saved file pre-distorts like the live tone.
        if (isCompensated(c.form)) {
          const dpd = prefs.getGenDpd(c.form);
          if (dpd) {
            if (isDualToneCorrectionFile(dpd)) kernel.applyDualToneCompensation(loadIntermod(dpd));
            else kernel.applyCompensation(loadHarmonics(dpd, emitHz));
          }
        }
        const isSweep = c.form === GenSignalForm.LINEAR_SWEEP || c.form === GenSignalForm.LOG_SWEEP;
        if (isSweep) {
          const swStart = sfVal('sweepStart', 20);
          const swEnd = sfVal('sweepStop', 20000);
          const swSamples = Math.max(1, Math.round(rate * (sfVal('sweepDur', 1) || 1)));
          if (c.form === GenSignalForm.LINEAR_SWEEP) kernel.configureLinearSweep(swStart, swEnd, swSamples);
          else kernel.configureLogSweep(swStart, swEnd, swSamples, 0);
          kernel.setSweepParams($('#sweepLoop').is(':checked'),
            Math.round(rate * (sfVal('sweepFadeIn', 0) || 0)),
            Math.round(rate * (sfVal('sweepFadeOut', 0) || 0)));
        }
        const total = Math.round(rate * seconds);
        // Render + apply the selected dither, then normalise back to a float that re-quantises to the
        // SAME value (so saveScopeCapture's quantiser preserves the dither). The float is duplicated
        // to a STEREO pair - Java exports stereo, not mono. Periodic non-sweep forms truncate to whole
        // periods inside saveScopeCapture (signalFrequencyHz = emitHz); noise + sweeps pass 0.
        // Float64 (not Float32) so a 32-bit quantised value survives the normalise->re-quantise round
        // trip - Float32's 24-bit mantissa would drop the low 8 bits of a 32-bit sample.
        const maxVal = Math.pow(2, bitDepth - 1) - 1;
        // Interleave seam (Java SignalFileExporter.fillBuffer): render the mono sample ONCE - a
        // single dithered value feeds BOTH lanes, so their dither stays correlated exactly as
        // Java's shared `sample` does - then apply the output-lane gate + right-lane scale. Left is
        // the amplitude reference (scale 1.0) and drives the whole-period truncation; the right lane
        // scales by fsLeft/fsRight; a gated-off lane is digital zero. With gate BOTH + scale 1.0 both
        // lanes carry the identical quantised sample (byte-identical to the pre-feature stereo export).
        const { wantL, wantR } = outputLaneGate(prefs.genOutputChannels.get());
        const scaleR = prefs.dacRightLaneScale();
        const chL = new Float64Array(total), chR = new Float64Array(total);
        for (let i = 0; i < total; i++) {
          const q = quantizePcm(kernel.nextSample(), bitDepth, dither) / maxVal;
          chL[i] = wantL ? q : 0;
          chR[i] = wantR ? q * scaleR : 0;
        }
        const truncHz = (isPeriodic(c.form) && !isSweep) ? emitHz : 0;
        const bytes = io.saveScopeCapture(chL, chR, total, name, rate, bitDepth, truncHz);
        const res = await io.writeToTarget(target, bytes, 'audio/wav');
        if (res.saved) {
          $('#status').text(res.viaDownload
            ? t('web.save.handedToDownload', res.name) : 'saved ' + res.name);
        }
      } catch (e) { $('#status').text('save failed: ' + e.message); }
    });

    // ----- Generator "Load from..." file player (faithful to FilePlayController) -----
    // A monitoring convenience: decode the picked file with the project's readers
    // (FLAC-capable, like PcmFileLoader) and hand the float channels to the backend's
    // own DAC playback lane. Loop is live-toggled; the play button doubles as stop.
    $('#genFileBrowse').on('change', async (ev) => {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      $('#genFilePath').val(file.name);
      // The decode of a large file takes a while, and until it lands the previous pick is still
      // the loaded signal - a Play click in that window played (or was refused for) the OLD file
      // while the field already named the new one. Java's FilePlayController loads synchronously
      // and has no such window; here the pick is published as an in-flight load that Play awaits.
      this.genFileSig = null;
      let loaded;
      this._genFileLoad = new Promise((resolve) => { loaded = resolve; });
      try {
        const bytes = new Uint8Array(await file.arrayBuffer());
        const lower = file.name.toLowerCase();
        const dec = lower.endsWith('.flac') ? await io.decodeFlac(bytes)
          : /\.aiff?$/.test(lower) ? io.readAiff(bytes) : io.readWav(bytes);
        const frames = dec.frameCount != null ? dec.frameCount : dec.ch0.length;
        const mid = Math.pow(2, dec.bitsPerSample - 1);
        const ch0 = new Float32Array(frames), ch1 = new Float32Array(frames);
        for (let i = 0; i < frames; i++) { ch0[i] = dec.ch0[i] / mid; ch1[i] = dec.ch1[i] / mid; }
        // The RAW bytes are kept beside the decoded channels: a generator on a Phonalyser server
        // has no downlink for audio, so playing there uploads the FILE and lets the bench decode
        // it (spec §3 + 4.5). The local paths use the channels exactly as before.
        // The decoded FORMAT travels with the samples - rate and depth both: what a lane can be
        // asked to play is a property of the file, and a caller that only knows the rate cannot
        // tell whether the depth is one the output can carry.
        this.genFileSig = { channels: [ch0, ch1], sampleRate: dec.sampleRate,
          bitsPerSample: dec.bitsPerSample, file: { bytes, name: file.name } };
        $('#status').text('loaded ' + file.name);
      } catch (e) { this.genFileSig = null; $('#status').text('load failed: ' + e.message); }
    });
    $('#genFilePlay').on('click', async () => {
      if (engine.filePlaying) { await engine.stopFile(); this.setGenFileBtn(false); return; }
      if (!this.genFileSig) { $('#status').text(t('generator.error.playFile.pickFirst')); return; }
      try {
        // DDS tone and file playback share the one output device - only one may drive it.
        // Java startFilePlayback() stops the DDS first; mirror that + re-sync the Play button.
        if (this._isGenRunning()) {
          await engine.stopGenerator();
          $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
        }
        await engine.playFileBuffer(this.genFileSig.channels, this.genFileSig.sampleRate,
          $('#genFileLoop').is(':checked'), this.genFileSig.file, this.genFileSig.bitsPerSample);
        // A refusal the controller localized (an over-size upload, a bench that said no) never
        // throws - it lands in filePlayError, and the status line is where it belongs.
        // A refusal is shown where every other error in this app is shown - the shell's one alert
        // modal, the web twin of the desktop's Cannot-play-file MessageBox. The #status write
        // beside it is the aria-live region and reaches a screen reader only, which is why a
        // refused file used to look like nothing at all.
        const refused = engine.filePlayError;
        if (refused) {
          $('#status').text(refused);
          this._showAlert(t('generator.error.playFile'), refused);
          this.setGenFileBtn(false);
          return;
        }
        // Read back rather than assumed: an upload the operator cancelled resolves here with no
        // error and nothing playing, and the LED must not claim otherwise.
        this.setGenFileBtn(engine.filePlaying);
      } catch (e) { $('#status').text('play failed: ' + e.message); this.setGenFileBtn(false); }
    });
    $('#genFileLoop').on('change', () => {
      const on = $('#genFileLoop').is(':checked');
      // Two-way bound in Java (Bindings.check(playFromLoopBtn, genPlayFromLoopProperty)): persist
      // the toggle so it survives a reload, then live-apply it to the running playback (loop takes
      // effect at the next EOF).
      prefs.genPlayFromLoop.set(on); prefs.save();
      engine.setFilePlayLoop(on);
    });

    return this;
  }

  /**
   * Puts up the "uploading to the bench" notice - a MODAL with one Cancel, in the same busy-shell
   * shape the sweep uses. Nothing is playing while the bytes travel, so what the operator needs
   * is not the pane's file Stop but a Cancel of this transfer, and the page underneath stays
   * blocked until the upload ends one way or another. Static backdrop and no keyboard dismissal
   * (markup): the only ways out are Cancel and the transfer finishing, so no dismissal can leave
   * an upload running behind a page that looks idle - and with no user-close path there is
   * nothing for a hide veto to catch.
   *
   * <p>A second STARTED while it is up changes nothing: the Cancel handler is re-bound in place
   * rather than stacked, and showing a shown modal is a no-op.
   */
  openUploadNotice() {
    this._uploadWanted = true;
    $('#genUploadCancel').prop('disabled', false)
      .off('click.genUpload').on('click.genUpload', () => this._cancelUpload());
    const el = (typeof document === 'undefined') ? null : document.getElementById('genUploadModal');
    if (!el || typeof window === 'undefined' || !window.bootstrap) return;
    const modal = window.bootstrap.Modal.getOrCreateInstance(el);
    const onShown = () => {
      el.removeEventListener('shown.bs.modal', onShown);
      // A refusal can end the transfer inside the fade-in, and a hide issued during that
      // transition is ignored - re-issue it here, or the notice would stay up over nothing.
      if (!this._uploadWanted) this.closeUploadNotice();
    };
    el.addEventListener('shown.bs.modal', onShown);
    modal.show();
  }

  /** Takes the notice down. Idempotent, and harmless without a preceding open: it is driven by
   *  the finished event, which fires on every outcome of the upload - the file playing, a
   *  refusal, a link that died, or the operator's Cancel. */
  closeUploadNotice() {
    this._uploadWanted = false;
    const el = (typeof document === 'undefined') ? null : document.getElementById('genUploadModal');
    if (!el || typeof window === 'undefined' || !window.bootstrap) return;
    // Re-resolved from the element instead of a stored instance: a close that arrives before
    // this pane ever opened the notice must find nothing and do nothing.
    const modal = window.bootstrap.Modal.getInstance(el);
    if (modal) modal.hide();
  }

  /**
   * Cancel: stops the transfer through the same cooperative seam the pane's file button uses -
   * the controller's stop flag, which the upload span reads at its next look and which turns
   * into the bench's own stop once the command in flight has returned. The notice is NOT taken
   * down here: it comes down with the finished event the span publishes on its way out, so a
   * cancel ends exactly like a refusal.
   *
   * <p>One shot - the button goes dead as soon as it is pressed, because the stop is honoured
   * where the span next looks, not immediately.
   */
  _cancelUpload() {
    if ($('#genUploadCancel').prop('disabled')) return;
    $('#genUploadCancel').prop('disabled', true);
    Promise.resolve(this.engine.stopFile())
      .catch((e) => console.warn('Cancelling the bench upload failed: ' + e.message));
  }

  setGenFileBtn(on) {
    // Java tinyPlayDim -> tinyPlayLit: the play glyph stays, lit green while playing (no stop swap).
    $('#genFilePlay').toggleClass('playing', on)
      .attr('title', t(on ? 'generator.loadFrom.stop' : 'generator.loadFrom.play'));
    // The BIG play button is the DDS tone's alone - a file plays on the small button above it.
    // ON-AIR banner mirrors EITHER engine (Java syncFilePlayVisuals startOnAirBlink while playing;
    // syncPlayButtonVisuals stops the blink only when the DDS tone is also stopped). Light it while
    // the file plays; on file stop / natural end clear it only if the DDS generator isn't running.
    if (on) this.setOnAir(true);
    else if (!this._isGenRunning()) this.setOnAir(false);
  }

  /**
   * Lights or clears the ON-AIR banner - and starts/stops the tick that rides with it (Java
   * startOnAirBlink / stopOnAirBlink). The blink itself is CSS here; what the tick is FOR is the
   * lane-death consult: an output device that dies mid-tone leaves the play LED lit over a lane
   * that stopped, exactly the way a dead capture left the trace drawing.
   *
   * @param {boolean} on
   */
  setOnAir(on) {
    $('#onAir').toggleClass('live', !!on);
    if (on) this._startOnAirTick(); else this._stopOnAirTick();
  }

  _startOnAirTick() {
    if (this._onAirTimer != null) return;
    this._onAirTimer = setInterval(() => this._onAirTick(), ON_AIR_TICK_MS);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this._onAirTimer && typeof this._onAirTimer.unref === 'function') this._onAirTimer.unref();
  }

  _stopOnAirTick() {
    if (this._onAirTimer != null) { clearInterval(this._onAirTimer); this._onAirTimer = null; }
  }

  /** The tick: the play lane records WHY it ended (the lane is already down and its line
   *  closed); the pane that owns Play reports it (Java scheduleOnAirBlink's consult). */
  async _onAirTick() {
    const died = await this.engine.takePlaybackEndedFromBelow();
    if (died) this.onPlaybackEndedFromBelow();
  }

  /** The playback lane ended from below - device unplugged, taken exclusively, or the driver
   *  failed. The lane already stopped itself, so only the visuals and the operator report are
   *  left. The unified stop message: same form as the capture side's, the BACKEND is named, and
   *  the technical detail stays in the log - never in the dialog. */
  onPlaybackEndedFromBelow() {
    this.setOnAir(false);
    $('#genPlay').removeClass('playing').attr('title', t('generator.play.start'));
    this.setGenFileBtn(false);
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, {
      direction: 'output',
      message: t('audio.deviceError.playbackEnded',
        backendDisplayName(this.engine.activeBackend())),
    });
  }
}
