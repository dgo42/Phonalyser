/*
 * Phonalyser web - the Preferences dialog (staged audio/L&F/Osc/FFT/FR prefs, commit on OK).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/preferences/PreferencesDialog.
 */

import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { NumericStepField, NumericStepModel, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';
import { CardSection } from './card-section.js';
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { netBackendValue, remoteBackendOf } from '../net/net-device-ref.js';
import { backendDisplayName } from '../audio/audio-backend-type.js';
import { EMBEDDED } from './build-profile.js';
import { t } from '../i18n/i18n.js';

/**
 * Shown when the selected backend enumerates no formats of its own - today only Web Audio.
 * A backend is always selected, so the depth combos are never empty.
 *
 * ONE entry, and it is 32 on purpose. Java's fallback here is {16, 24, 32}, because its OS backends
 * really can run at those depths and simply failed to report a list. The Web platform is different:
 * it does not expose a device's bit depth AT ALL, and the capture it hands us is float32 taken
 * post-mixer - never raw 16/24-bit PCM (doc/htmls/audio-devices.html: "Bit depth - none. Capture is
 * float32 post-mixer"). Offering 16 and 24 would advertise a choice the platform can neither honour
 * nor report, so the combo states the one depth the pipeline actually carries.
 */
const DEFAULT_BIT_DEPTHS = [32];

// The ten NumericStepField rows across the Osc / FFT / FreqResp tabs - min/max/wheelStep/
// arrowStep/decimals lifted verbatim from PreferencesDialog.java (constants at :100-133, field
// ctors at :316-596). All FIXED policy (family, min, max, wheelStep, arrowStep, decimals).
const F = UNIT_FAMILIES;
const PREF_FIELD_SPECS = {
  // Oscilloscope tab
  prefOscMeasAvg:   { model: { family: F.SECONDS, min: 0.5, max: 100, wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefOscLineWidth: { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefOscDotDia:    { model: { family: F.PIXEL,   min: 3,   max: 12,  wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
  prefOscHistBins:  { model: { family: F.NONE,    min: 10,  max: 200, wheelStep: 5,   arrowStep: 5,   decimals: 0 } },
  prefOscPersistManual: { model: { family: F.SECONDS, min: 0.1, max: 60, wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  // FFT tab
  prefFftLineWidth: { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefFftDotDia:    { model: { family: F.PIXEL,   min: 3,   max: 12,  wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
  prefFftStrongTone:{ model: { family: F.DECIBEL, min: 10,  max: 140, wheelStep: 10,  arrowStep: 1,   decimals: 1 } },
  // FreqResp tab (prefFrMaxNyq: `pct` -> its onChange keeps the live (Hz) readout in sync)
  prefFrLineWidth:  { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefFrMaxNyq:     { pct: true, model: { family: F.PERCENT, min: 83, max: 100, wheelStep: 0.5, arrowStep: 1, decimals: 1 } },
  prefFrSmooth:     { model: { family: F.NONE,    min: 0,   max: 100, wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
};

// Staged exactly like the audio controls: seeded from the live prefs on open, written to
// prefs + applied ONLY on OK (Cancel/Esc/X leave the prefs untouched - the next open
// re-seeds). Every label reuses an existing Java i18n key; no new keys. Colours are stored
// as 0xRRGGBB ints, the <input type=color> uses #rrggbb; UI fonts are "Family|size|style".
const intToHex = (n) => '#' + (((n | 0) & 0xffffff)).toString(16).padStart(6, '0');
const hexToInt = (s) => parseInt(String(s).replace('#', ''), 16) | 0;
// UI fonts are stored "Family|size|style". The web has no native font dialog, so the picker
// is a curated family dropdown + a size field - the faithful web analog of
// PreferencesDialog.buildFontRow's OS FontDialog (minus OS font enumeration the browser
// can't do). The style (normal/bold) is fixed per row and preserved from the stored spec.
const FONT_FAMILIES = ['Consolas', 'Cascadia Code', 'Courier New', 'Menlo', 'Monaco', 'monospace',
  'Segoe UI', 'Arial', 'Tahoma', 'Verdana', 'system-ui', 'sans-serif', 'serif'];
function fillFontFamilies(sel) {
  const $s = $(sel);
  if ($s.children('option').length) return;
  for (const f of FONT_FAMILIES) $s.append(`<option value="${f}">${f}</option>`);
}
function seedFont(spec, familySel, sizeSel, boldChk, italicChk) {
  fillFontFamilies(familySel);
  const p = String(spec).split('|');
  const fam = p[0] || 'Consolas', size = p[1] || '9', style = p[2] || 'normal';
  if (!$(familySel).find(`option[value="${fam}"]`).length) $(familySel).append(`<option value="${fam}">${fam}</option>`);
  $(familySel).val(fam);
  $(sizeSel).val(size);
  $(boldChk).prop('checked', style.includes('bold'));
  $(italicChk).prop('checked', style.includes('italic'));
}
// Bold + italic are the only styles an SWT Font carries (Fonts.toSpec); combine as "bold+italic".
function buildFont(familySel, sizeSel, boldChk, italicChk) {
  const fam = $(familySel).val() || 'Consolas';
  const size = Math.max(6, Math.min(48, parseInt($(sizeSel).val(), 10) || 9));
  const b = $(boldChk).is(':checked'), i = $(italicChk).is(':checked');
  const style = (b && i) ? 'bold+italic' : b ? 'bold' : i ? 'italic' : 'normal';
  return `${fam}|${size}|${style}`;
}

export class PreferencesDialog {
  /**
   * @param engine the AudioEngine (capture reopen + generator restart on a committed device/rate).
   * @param prefs  Preferences.
   * @param deps   {modal, RATES, stepFields, inRate, outRate, isBusy, setBusy, fftView}
   *               - modal: the bootstrap Modal for #prefsModal
   *               - RATES: the full output sample-rate list (input-rate fallback when native unknown)
   *               - stepFields: the NumericStepField map (FR/generator Nyquist ceilings re-pin)
   *               - inRate / outRate: () => the live input/output sample rate (Nyquist source)
   *               - isBusy / setBusy: the shared re-entrancy guard accessors (capture-reopen serialize)
   *               - fftView: the FFT view (applyPrefs() on OK so it re-reads its colour/line prefs)
   *               - deviceStore: the DeviceProfileStore - apply the resolved card's per-channel
   *                 full-scale on a device selection (Java SharedCapture / GeneratorController /
   *                 PreferencesDialog applyInput|OutputDeviceProfile)
   *               - backendManager: (backendName) => that backend's AudioDeviceManager, or null
   *                 when it has none (Java AudioBackend.instance().manager(type))
   */
  constructor(engine, prefs, { modal, RATES, stepFields, inRate, outRate, isBusy, setBusy, fftView, deviceStore, cardEditorDialog, showConfirm, showAlert, backendManager, netBackends, cardSource, resetStatistics, servedByServer }) {
    this.engine = engine;
    this.prefs = prefs;
    this.modal = modal;
    this.RATES = RATES;
    this.stepFields = stepFields;
    this.inRate = inRate;
    this.outRate = outRate;
    // The shell's busy guard. Defaulted, so a dialog constructed without a shell (a test, a
    // headless build) can still run the paths that consult it - scan() is now reached from a
    // bus notification as well as from the button (onNetBackendsChanged).
    this.isBusy = isBusy || (() => false);
    this.setBusy = setBusy || (() => {});
    this.fftView = fftView;
    this.deviceStore = deviceStore;
    /** The connected server's backend entries, read synchronously at every combo build (see
     *  buildBackendSelector). Absent -> no server backends are offered, which is what a build
     *  with no net client shows. */
    this.netBackends = netBackends || (() => []);
    // The shell's one alert surface (the web's Dialogs.warn) - the OK-time uncalibrated warning
    // is the only thing this dialog raises through it. Absent -> console (a headless construction).
    this._showAlert = showAlert || ((title, message) => console.warn(title, message));
    // Per-backend manager lookup (Java AudioBackend.instance().manager(type)): what answers
    // hasCustomPreferences() / openCustomPreferences() for the Settings button, and - for a
    // backend that enumerates its own audio formats (the QA40x) - the sample-rate list. Absent
    // (or answering null) means "no manager", and every backend then behaves like Web Audio.
    this.backendManager = backendManager || (() => null);
    // Whether THIS page was served by a Phonalyser server - the shell probes its own origin once
    // at load (spec §3 `GET /info`) and answers from that. Absent -> false, which is what every
    // context that is not a served page answers anyway.
    this.servedByServer = servedByServer || (() => false);
    // Clears the scope's running measurement statistics and restarts the FFT's cross-tick average -
    // the shell owns both views, so it supplies the one callback (the same one a calibration change
    // uses). Absent -> nothing to reset (a headless / test construction).
    this._resetStatistics = resetStatistics || (() => {});
    // The card list a REMOTE selection chooses from, and where a pick is staged / sent / read back
    // (Java's benchCards, built per dialog session on the working copy). Absent -> local cards only.
    this._cardSource = cardSource || null;
    // The Audio-tab per-card profile sections (Java PreferencesDialog.CardSection) - the card
    // combo + edit button + ranges table for each direction. Built only when the device store +
    // card editor are injected (they always are in the live app).
    this.inputCard = (deviceStore && cardEditorDialog) ? new CardSection(prefs, deviceStore, cardEditorDialog, {
      input: true, comboSel: '#inCardSel', editSel: '#inCardEdit', rangesSel: '#inRanges',
      deviceLabel: () => this.inputDeviceLabel(), showConfirm, showAlert: (title, message) => this._showAlert(title, message),
      onChanged: () => this.refreshFsReadouts(),
      isStaging: () => this._staging, cardSource: this._cardSource,
    }) : null;
    this.outputCard = (deviceStore && cardEditorDialog) ? new CardSection(prefs, deviceStore, cardEditorDialog, {
      input: false, comboSel: '#outCardSel', editSel: '#outCardEdit', rangesSel: '#outRanges',
      deviceLabel: () => this.outputDeviceLabel(), showConfirm, showAlert: (title, message) => this._showAlert(title, message),
      onChanged: () => this.refreshFsReadouts(),
      isStaging: () => this._staging, cardSource: this._cardSource,
    }) : null;
    // What the dialog ALREADY KNOWS about each backend, keyed by backend name:
    // { inputs, outputs, inRates, outRates } as the last enumeration for that backend left them.
    // Filled by scan() - the ONLY enumeration path (the load-time scan and the Scan click; for the
    // QA40x the click is also the WebUSB chooser's user activation). A backend SWITCH repopulates
    // the combos from here instead of enumerating, so picking a backend never touches hardware.
    this._known = new Map();
    // The backend whose devices the combos are currently showing, or null while they have never
    // been filled (fillDeviceCombos writes it).
    this._comboBackend = null;
    // Open-time active-range snapshot per direction (Java's inRangeBefore / outRangeBefore locals):
    // OK publishes DEVICE_ACTIVE_RANGE_CHANGED only for a direction whose range actually moved.
    this._inRangeBefore = null;
    this._outRangeBefore = null;
    // The same moment captured for the CANCEL path, and deeper: the range radios write straight
    // into the LIVE card (CardSection._userSetActive persists it and pushes the new full-scale), so
    // a cancelled dialog otherwise left the new range selected AND applied. These hold the card name
    // plus BOTH channel labels per direction - enough to put the card back exactly as it was.
    this._inRangeSnapshot = null;
    this._outRangeSnapshot = null;
    // True while the Preferences dialog is open: its device/rate controls STAGE
    // edits (Java PreferencesDialog edits a detached copy) and apply only on OK.
    this._staging = false;
    // Snapshot of the audio-control DOM values taken when the dialog opens, so a
    // Cancel/close restores the on-screen selections to what they were on open.
    this._audioSnapshot = null;
    // Stage-on-open / commit-on-OK / discard-on-close (Java PreferencesDialog).
    // Set true by the OK button so the `hidden` handler commits instead of reverting;
    // any other dismissal (Cancel / backdrop / Esc / X) leaves it false -> revert.
    this._okClicked = false;
    // True once buildBackendSelector has attached the prefs->combo listener, which must happen
    // exactly once however often the combo is rebuilt.
    this._backendListenerBound = false;
    // A server's backends appear and disappear with its session, and the combo is built
    // synchronously from the cache - so rebuild it whenever the offer moves (Java
    // refreshBackendCombo). Subscribed HERE rather than in bind(): the combo build is guarded on
    // the element existing, so this is safe before the DOM is wired and cannot be forgotten by a
    // caller that only constructs the dialog.
    MessageBus.instance().subscribe(Events.REMOTE_BACKENDS_CHANGED, (e) => this.onNetBackendsChanged(e));
  }

  // ----- Preferences dialog: stage on open, commit on OK, discard on Cancel -----
  // Mirrors Java PreferencesDialog (edits a detached Preferences.copyForDialog(),
  // hands it to applyFromDialog() only on OK; Cancel touches nothing live).  The
  // dialog's only live controls are the audio device/rate <select>s, so the
  // "detached copy" here is just those DOM values plus the staged backend name; the
  // live prefs / engine are untouched until applyAudioPrefs() commits them.

  /** Captures the current audio-control DOM values so a Cancel/close can restore
   *  them.  Called on dialog open. */
  snapshotAudioPrefs() {
    this._audioSnapshot = {
      backend: this.prefs.backend.get(),
      inSel:   $('#inSel').val(),
      outSel:  $('#outSel').val(),
      inRate:  $('#inRate').val(),
      outRate: $('#outRate').val(),
      inDepth:  $('#inDepth').val(),
      outDepth: $('#outDepth').val(),
    };
  }

  /** Restores the audio controls to the snapshot taken on open - the discard path
   *  for Cancel / backdrop / Esc / X. Nothing live was written while staging EXCEPT
   *  the backend (prefs.backend is what every per-backend lookup keys off, so the
   *  combo has to stage there - see onBackendChanged); that one is rolled back here
   *  and its device lists re-derived. Everything else is on-screen widgets only. */
  async restoreAudioPrefs() {
    if (!this._audioSnapshot) return;
    const snapshot = this._audioSnapshot;
    this._audioSnapshot = null;
    if (snapshot.backend !== this.prefs.backend.get()) {
      // The devices / rates on screen belong to the backend the user was TRYING, so restoring their
      // VALUES would leave a foreign list behind: roll the backend back, then rebuild its combos
      // from what is already known for it - NOT by re-enumerating (a backend change must never scan;
      // see repopulateFromKnown). The rebuild re-selects the persisted pair, which is exactly the
      // snapshot (staging never committed).
      this.prefs.backend.set(snapshot.backend);
      $('#backendSel').val(snapshot.backend);
      this.refreshCustomPrefsButton();
      // A bench backend is restored the way it is PICKED: the session answers a client's
      // reads only for the backend it was last routed at by backend.select, and browsing another
      // of the server's backends routed it there. Rebuilding from the cache alone would put this
      // backend's devices back on screen while the session still pointed at the browsed one - the
      // empty card combo, reached by Cancel instead of by a second pick.
      if (remoteBackendOf(snapshot.backend) != null && !this.isBusy()) await this.scan();
      else await this.repopulateFromKnown();
      return;
    }
    $('#inSel').val(snapshot.inSel);
    $('#outSel').val(snapshot.outSel);
    $('#outRate').val(snapshot.outRate);
    // The depth combos' OPTIONS are rebuilt per backend, but a same-backend Cancel only has to put
    // the VALUES back - the options on screen are already this backend's.
    $('#inDepth').val(snapshot.inDepth);
    $('#outDepth').val(snapshot.outDepth);
    if (this.formatSource()) {
      // A backend that enumerates its own rates (the QA40x: one reg-9 clock, so both combos carry
      // the same list) never had those options restaged - restoring the value is the whole job.
      $('#inRate').val(snapshot.inRate);
      return;
    }
    // #inRate is DERIVED from the input device's native rate (one option), so rebuild it for the
    // RESTORED device - its options changed while staging. Discard path -> no persist.
    const native = parseInt($('#inSel option:selected').attr('data-rate'), 10);
    if (native > 0) {
      $('#inRate').empty().append(`<option value="${native}">${native} Hz</option>`).val(String(native));
    } else {
      $('#inRate').empty(); this.RATES.forEach((r) => $('#inRate').append(`<option value="${r}">${r} Hz</option>`));
      $('#inRate').val(snapshot.inRate);
    }
  }

  /** Commits the staged audio selections to the live prefs and applies them
   *  (persist, re-pin the FR/generator Nyquist field bounds, re-acquire the
   *  capture device, restart the generator).  The OK path of the dialog. */
  async applyAudioPrefs() {
    const inDev = $('#inSel').val(), outDev = $('#outSel').val();
    const inR = parseInt($('#inRate').val(), 10), outR = parseInt($('#outRate').val(), 10);
    // Only a backend that HAS a sample width contributes one; Web Audio's pipeline is float32, so
    // its (hidden) combos are not read, not committed, and cannot bounce a direction.
    const hasDepth = this.backendHasBitDepth();
    const inD = hasDepth ? parseInt($('#inDepth').val(), 10) : 0;
    const outD = hasDepth ? parseInt($('#outDepth').val(), 10) : 0;
    const bp = this.prefs.current();
    // A BACKEND change counts as both directions changed. Java gets there via
    // audioConfigFingerprint (which carries the backend) bouncing all three consumers; here it is
    // spelled out because the QA40x is ONE always-duplex device - its ADC will not stream unless
    // the DAC is fed, so a single-direction bounce is meaningless - and the outgoing backend's
    // lines have to close whatever they were doing.
    const backendChanged = this._audioSnapshot != null && this._audioSnapshot.backend !== this.prefs.backend.get();
    // A DEPTH change bounces its direction too: it is the capture/playback format, so the open line
    // has to be reopened on it - and on the QA40x the output depth is what reg 0x0B (the I2S frame
    // width) is written from at the next session start.
    const captureChanged = backendChanged || bp.inputDeviceName  !== inDev
      || bp.inputSampleRate  !== inR || (inD > 0 && bp.inputBitDepth !== inD);
    const outputChanged  = backendChanged || bp.outputDeviceName !== outDev
      || bp.outputSampleRate !== outR || (outD > 0 && bp.outputBitDepth !== outD);
    // What the OK-time uncalibrated warning is gated on: the SELECTION - the
    // backend or either device - not a rate or a depth. Computed here, before the commit
    // overwrites the values it compares against.
    const selectionChanged = backendChanged
      || bp.inputDeviceName !== inDev || bp.outputDeviceName !== outDev;

    // Two-phase bracket (Java PreferencesDialog OK -> MainWindow.before/afterApplyBackendChanges):
    // STOP the live consumers each CHANGED direction owns BEFORE the commit - while the current
    // device is still open - then COMMIT, then RESTART exactly what was running on the new config.
    // Capture (scope+FFT) and the generator are bracketed INDEPENDENTLY: Web Audio's input and
    // output are separate devices, so an input-only change must not disturb a playing generator
    // (unlike Java's shared-clock backend, which bounces all three on any audio change). The whole
    // apply runs under the busy guard so it can't overlap another reopen; unlike the old code it is
    // never SKIPPED when busy - an OK must never be silently dropped, leaving streams at the old rate.
    this.setBusy(true);
    try {
      await this.engine.beforeApplyBackendChanges(captureChanged, outputChanged);

      // Commit the staged working copy -> live prefs (Java applyFromDialog).
      bp.inputDeviceName = inDev; bp.outputDeviceName = outDev;
      if (inR)  bp.inputSampleRate  = inR;
      if (outR) bp.outputSampleRate = outR;
      // The DEPTHS commit here too. Without these two lines the combo's pick was used to decide
      // whether to bounce the direction and then thrown away, so the next open re-derived from the
      // stored 24 - which the I2S list [16, 32] does not offer, leaving the combo on its first
      // entry: pick 32, press OK, reopen, find 16.
      if (inD)  bp.inputBitDepth  = inD;
      if (outD) bp.outputBitDepth = outD;
      // Component-owned blocks (a backend's own settings, e.g. the QA40x I2S port) commit FIRST:
      // the save below is what persists them, so committing after it would write the previous
      // values and leave the new ones on disk only after some later save. Java: commit them ahead
      // of applyFromDialog, which ends in save(). This is also what makes the Settings dialog's
      // result pending - Cancel never reaches here, so its edit is simply never committed.
      this.prefs.commitCustomPreferencesEdit();
      // The same OK for a backend whose settings live on a BENCH: nothing of it is persisted here,
      // so the manager sends its pending write now (Java Qa40xSettingsUi.commitEdit). Not awaited
      // - it is a wire round trip, and the dialog's OK is not the operator's cue to wait for a
      // server; a bench that has gone away simply does not get it and the panel reads the real
      // state again the next time it opens.
      this._settingsEdit('commitCustomPreferencesEdit');
      this.prefs.save();
      this._audioSnapshot = null;

      // Apply the committed devices' per-card per-channel full-scale (Java
      // PreferencesDialog OK path: applyInput/OutputDeviceProfile on commit).
      this._applyDeviceProfiles();

      // An active-range change reaches the DEVICE only here, on OK - before the
      // restart below, exactly where Java publishes it (PreferencesDialog.java:934-935).
      this.publishActiveRangeChanges();

      // Re-pin the Nyquist-derived field bounds from the committed input/output rates.
      const inNyq = this.inRate() / 2;
      for (const id of ['frStart', 'frStop']) if (this.stepFields[id]) this.stepFields[id].setMax(inNyq);
      const outNyq = this.outRate() / 2;
      for (const id of ['toneHz', 'tone2Hz', 'sweepStart', 'sweepStop']) if (this.stepFields[id]) this.stepFields[id].setMax(outNyq);

      // Flow the committed device/rate into the live engine config so each restart re-acquires there.
      if (captureChanged) {
        this.engine.config.inDeviceId = inDev;
        this.engine.config.inRate = inR || this.engine.config.inRate;
      }
      if (outputChanged) this.engine.config.outDeviceId = outDev;

      // The committed audio format is now in the engine config, so anything derived from it is
      // stale until it re-reads: the generator's snap brackets sit on the CAPTURE rate's bin
      // grid. Announced here, once, at the commit - Java's AUDIO_FORMAT_CHANGED, published from
      // the same place (PreferencesDialog OK) and for the same reason.
      if (captureChanged || outputChanged) MessageBus.instance().publish(Events.AUDIO_FORMAT_CHANGED);

      // Everything accumulated at the OLD settings is now inconsistent with what follows: the scope's
      // running measurement statistics and the FFT's cross-tick average were folded at a different
      // device, rate, range or full scale. Restarting the consumers alone would keep averaging the
      // pre-change frames into the post-change display, so the statistics are reset too - the same
      // treatment a calibration change already gets (Java FftView wires both prefs to
      // resetStatistics(); ScopeView to clearMeasurementHistory). Only for a direction that actually
      // changed, and the restart itself is still gated on what was running.
      if (captureChanged || outputChanged) this.resetMeasurementStatistics();

      await this.engine.afterApplyBackendChanges(captureChanged, outputChanged);
    } finally { this.setBusy(false); }
    // The operator must be told when what they just committed has no calibration
    // behind it - LAST, so the commit is complete and the streams are already back up while the
    // warning is on screen.
    if (selectionChanged) this._warnIfUncalibrated();
  }

  /** Sends every card choice staged on the bench (spec 4.3 device.setCard) - the OK half of the
   *  card chooser, and the only place a binding leaves this machine.
   *
   *  ONE error dialog for the whole commit, like the uncalibrated warning: a bench that refuses one
   *  binding is refusing them for one reason (the device is locked by somebody else, or it dropped
   *  the card), and two dialogs in a row on OK would say the same thing twice. A refusal is
   *  reported and the commit continues - the rest of the dialog's work is not the binding's to undo
   *  (Java PreferencesDialog.commitStagedCardBindings). */
  async _commitStagedCardBindings() {
    const refused = [];
    if (this.inputCard) refused.push(...await this.inputCard.commitStagedBindings());
    if (this.outputCard) refused.push(...await this.outputCard.commitStagedBindings());
    if (refused.length === 0) return;
    this._showAlert(t('net.servers.error.title'),
      t('preferences.audio.card.bindFailed', refused.join(', ')));
  }

  /** ONE warning per OK: both directions are tested but only one dialog is raised,
   *  naming the device(s) it applies to. A calibrationFromDevice endpoint is exempt - the store
   *  answers that (Java PreferencesDialog.warnIfUncalibrated). */
  _warnIfUncalibrated() {
    if (!this.deviceStore) return;
    const uncalibrated = [];
    const inName = this.inputDeviceLabel();
    const outName = this.outputDeviceLabel();
    if (inName && this._isUncalibrated(inName, true)) uncalibrated.push(inName);
    if (outName && this._isUncalibrated(outName, false)) uncalibrated.push(outName);
    if (uncalibrated.length === 0) return;
    const names = uncalibrated.join(', ');
    console.info('Uncalibrated selection committed: ' + names);
    this._showAlert(t('preferences.audio.uncalibrated.title'),
      t('preferences.audio.uncalibrated.message', names));
  }

  /** One direction's half of {@link #_warnIfUncalibrated}: the DEVICE's own answer when it carries
   *  one - a device on a bench is measured against the calibration the SERVER stores for it (spec
   *  4.3 `cal`), so this machine having no card for its name says nothing about it - else the
   *  name-only lookup, which is all there ever was for a local device. Java composes the same two
   *  steps in Preferences.isUncalibrated(DeviceRef, boolean). */
  _isUncalibrated(deviceName, input) {
    if (this._cardSource && this._cardSource.calibration(input, deviceName) != null) return false;
    return this.deviceStore.isUncalibrated(deviceName, input);
  }

  // ----- Preferences dialog: Look&Feel / Oscilloscope / FFT / FreqResp tabs -----

  /** Show one prefs panel, hide the rest (the tab strip switches panels - no Bootstrap tabs). */
  prefsTab(id) {
    $('#prefsTabs .nav-link').each(function () { $(this).toggleClass('active', this.dataset.prefsPanel === id); });
    $('.prefs-panel').each(function () { $(this).toggleClass('d-none', this.dataset.prefsPanel !== id); });
  }

  /** Seed the Look&Feel / Oscilloscope / FFT / FreqResp controls from the live prefs (open). */
  seedPrefsTabs() {
    const prefs = this.prefs;
    $('#prefTabOrientation').val(prefs.tabOrientation.get());
    $('#prefSmallIcons').prop('checked', prefs.smallIconsInMainTab.get());
    $('#prefShowTips').prop('checked', prefs.showTipsAtStartup.get());
    seedFont(prefs.uiFontNormal.get(), '#prefUiFontFamily', '#prefUiFontSize', '#prefUiFontWBold', '#prefUiFontWItalic');
    seedFont(prefs.uiFontBold.get(), '#prefUiFontBoldFamily', '#prefUiFontBoldSize', '#prefUiFontBoldWBold', '#prefUiFontBoldWItalic');

    // NumericStepField rows: seed the model (auto-formats the unit-in-text). prefFrMaxNyq holds a
    // percent (fraction × 100); the others hold their canonical pref value directly.
    this.prefFields.prefOscMeasAvg.setValue(prefs.oscMeasurementAverageSeconds.get());
    this.prefFields.prefOscLineWidth.setValue(prefs.oscLineWidth.get());
    this.prefFields.prefOscDotDia.setValue(prefs.oscDotDiameter.get());
    this.prefFields.prefOscHistBins.setValue(prefs.oscHistogramBins.get());
    // Persistence mode combo + manual-seconds field (enabled only when mode == MANUAL,
    // re-gated live on combo change; see the #prefOscPersistence handler in bind()).
    $('#prefOscPersistence').val(prefs.oscPersistenceMode.get());
    this.prefFields.prefOscPersistManual.setValue(prefs.oscPersistenceManualSeconds.get());
    this.gateOscPersistManual();
    this.prefFields.prefFftLineWidth.setValue(prefs.fftLineWidth.get());
    this.prefFields.prefFftDotDia.setValue(prefs.fftHarmonicDotDiameter.get());
    this.prefFields.prefFftStrongTone.setValue(prefs.fftStrongToneRelDb.get());
    this.prefFields.prefFrLineWidth.setValue(prefs.freqRespLineWidth.get());
    this.prefFields.prefFrMaxNyq.setValue(prefs.freqRespNyquistFraction.get() * 100);
    this.prefFields.prefFrSmooth.setValue(prefs.freqRespCompareSmoothWindow.get());
    this.updateFrMaxNyqHz();

    // Colour buttons: seed the native picker value, then paint the swatch + hex text.
    this.seedColor('#prefOscLeftColor', prefs.oscLeftChannelColor.get());
    this.seedColor('#prefOscRightColor', prefs.oscRightChannelColor.get());
    this.seedColor('#prefFftLineColor', prefs.fftLineColor.get());
    this.seedColor('#prefFftBgColor', prefs.fftChartBackgroundColor.get());
    this.seedColor('#prefFftDotColor', prefs.fftHarmonicDotColor.get());
    this.seedColor('#prefFftFilterColor', prefs.fftFreqRespColor.get());
    this.seedColor('#prefFftBeforeCalColor', prefs.fftBeforeCalDotColor.get());
    this.seedColor('#prefFftCalColor', prefs.fftCalOverlayColor.get());
    this.seedColor('#prefFrSignalColor', prefs.freqRespSignalColor.get());
    this.seedColor('#prefFrPhaseColor', prefs.freqRespPhaseColor.get());
    this.seedColor('#prefFrRefColor', prefs.freqRespReferenceColor.get());
    this.seedColor('#prefFrBgColor', prefs.freqRespBackgroundColor.get());

    $('#prefFrNotch').prop('checked', prefs.freqRespNotchEnabled.get());
    $('#prefFrNotchHz').val(String(prefs.freqRespNotchBaseHz.get()));
  }

  /** Seeds one colour picker from a 0xRRGGBB int and paints its swatch + hex text. */
  seedColor(sel, rgbInt) {
    const inp = $(sel)[0];
    if (!inp) return;
    inp.value = intToHex(rgbInt);
    this.paintColorButton(inp);
  }

  /** Live "(... Hz/kHz)" readout next to the Max-analysed-frequency field - mirrors
   *  PreferencesDialog.formatMaxFreqLabel: shows the concrete band (typed % of the
   *  live input Nyquist), tracking the field on every edit. */
  updateFrMaxNyqHz() {
    const sr = this.inRate();
    let hz = '- Hz';
    if (sr > 0) {
      // Parse the leading number of the input text so the (Hz) readout tracks live typing
      // (before commit) as well as stepper/commit changes - the committed "95.0 %" also leads
      // with the number, so one parse serves both (Java nyqField selectionListener reads the value).
      const raw = parseFloat($('#prefFrMaxNyq').val());
      const pct = Math.max(83, Math.min(100, Number.isFinite(raw) ? raw : 100));
      const f = sr * 0.5 * (pct / 100);
      hz = (f >= 1000) ? `${(f / 1000).toFixed(2)} kHz` : `${f.toFixed(0)} Hz`;
    }
    $('#prefFrMaxNyqHz').text(`(${hz})`);
  }

  /** Enables the manual-persistence field only when the combo is on "Manual" - mirrors
   *  PreferencesDialog.java:355-357 (setEnabled + onChange re-gate). Called from seed and
   *  from the #prefOscPersistence change handler. */
  gateOscPersistManual() {
    const f = this.prefFields.prefOscPersistManual;
    if (f) f.setDisabled($('#prefOscPersistence').val() !== 'MANUAL');
  }

  /** Commit all four tabs' controls to the live prefs and apply (the OK path). */
  applyPrefsTabs() {
    const prefs = this.prefs;
    // Commit any pending text in each NumericStepField (Java NumericStepField commits on focus-out;
    // on OK the user may not have blurred), then read the already-clamped canonical value - exactly
    // the DacCalibrationDialog OK path (field.model.commit(input.value); field.getValue()).
    const fv = (id) => {
      const f = this.prefFields[id];
      if (!f) return NaN;
      f.model.commit(f.input.value.trim());
      return f.getValue();
    };

    prefs.tabOrientation.set($('#prefTabOrientation').val());
    prefs.smallIconsInMainTab.set($('#prefSmallIcons').is(':checked'));
    prefs.showTipsAtStartup.set($('#prefShowTips').is(':checked'));
    prefs.uiFontNormal.set(buildFont('#prefUiFontFamily', '#prefUiFontSize', '#prefUiFontWBold', '#prefUiFontWItalic'));
    prefs.uiFontBold.set(buildFont('#prefUiFontBoldFamily', '#prefUiFontBoldSize', '#prefUiFontBoldWBold', '#prefUiFontBoldWItalic'));

    prefs.oscMeasurementAverageSeconds.set(fv('prefOscMeasAvg'));
    prefs.oscLineWidth.set(fv('prefOscLineWidth'));
    prefs.oscDotDiameter.set(Math.round(fv('prefOscDotDia')));
    prefs.oscHistogramBins.set(Math.round(fv('prefOscHistBins')));
    prefs.oscPersistenceMode.set($('#prefOscPersistence').val());
    prefs.oscPersistenceManualSeconds.set(fv('prefOscPersistManual'));
    prefs.oscLeftChannelColor.set(hexToInt($('#prefOscLeftColor').val()));
    prefs.oscRightChannelColor.set(hexToInt($('#prefOscRightColor').val()));

    prefs.fftLineWidth.set(fv('prefFftLineWidth'));
    prefs.fftHarmonicDotDiameter.set(Math.round(fv('prefFftDotDia')));
    prefs.fftStrongToneRelDb.set(fv('prefFftStrongTone'));
    prefs.fftLineColor.set(hexToInt($('#prefFftLineColor').val()));
    prefs.fftChartBackgroundColor.set(hexToInt($('#prefFftBgColor').val()));
    prefs.fftHarmonicDotColor.set(hexToInt($('#prefFftDotColor').val()));
    prefs.fftFreqRespColor.set(hexToInt($('#prefFftFilterColor').val()));
    prefs.fftBeforeCalDotColor.set(hexToInt($('#prefFftBeforeCalColor').val()));
    prefs.fftCalOverlayColor.set(hexToInt($('#prefFftCalColor').val()));

    prefs.freqRespLineWidth.set(fv('prefFrLineWidth'));
    prefs.freqRespNyquistFraction.set(fv('prefFrMaxNyq') / 100);
    // FreqResp Nyquist fraction -> freq-window clamp (Java Preferences.applyFromDialog,
    // Preferences.java:797-805): if the new max-band drops below the current right edge,
    // pull freqMaxHz (and freqMinHz if needed) in so the view re-clamps to the new ceiling.
    {
      const sr = this.inRate();
      const maxBand = (sr > 0 ? sr * 0.5 : 24000.0) * prefs.freqRespNyquistFraction.get();
      if (prefs.freqRespFreqMaxHz.get() > maxBand) {
        prefs.freqRespFreqMaxHz.set(maxBand);
        if (prefs.freqRespFreqMinHz.get() > maxBand) prefs.freqRespFreqMinHz.set(Math.max(1.0, maxBand * 0.5));
      }
    }
    prefs.freqRespCompareSmoothWindow.set(Math.round(fv('prefFrSmooth')));
    prefs.freqRespNotchEnabled.set($('#prefFrNotch').is(':checked'));
    prefs.freqRespNotchBaseHz.set(parseInt($('#prefFrNotchHz').val(), 10) || 50);
    prefs.freqRespSignalColor.set(hexToInt($('#prefFrSignalColor').val()));
    prefs.freqRespPhaseColor.set(hexToInt($('#prefFrPhaseColor').val()));
    prefs.freqRespReferenceColor.set(hexToInt($('#prefFrRefColor').val()));
    prefs.freqRespBackgroundColor.set(hexToInt($('#prefFrBgColor').val()));
    prefs.save();

    // Apply immediately (no restart): Look & Feel re-lays out the main tabs; the FFT view
    // re-reads its prefs; the scope re-renders every paint so it picks up width / colour /
    // measurement-average on the next frame. (FreqResp view picks the values up when it
    // exists, part 5.)
    this.applyLookAndFeel();
    if (typeof this.fftView !== 'undefined' && this.fftView && this.fftView.applyPrefs) this.fftView.applyPrefs();
    // Fire the FreqResp range-changed event so the FreqResp host re-clamps its view to the
    // new Nyquist ceiling and re-syncs its scrollbars (Java PreferencesDialog OK handler,
    // PreferencesDialog.java:784: bus.publish(Events.FREQRESP_RANGE_CHANGED)).
    MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
  }

  /** Applies Look & Feel prefs LIVE (no reload): main-tab orientation (TOP strip / LEFT
   *  vertical icon sidebar) + small icons, by toggling body classes the CSS keys off -
   *  faithful to MainTab's live orientation / icon-size rebuild. Called on load + on OK. */
  applyLookAndFeel() {
    const prefs = this.prefs;
    document.body.classList.toggle('orient-left', prefs.tabOrientation.get() === 'LEFT');
    document.body.classList.toggle('small-icons', prefs.smallIconsInMainTab.get());
    // UI font -> CSS variables the measurement table / readouts consume (Java applies
    // uiFontNormal there). Point size -> px at the conventional 96 dpi (1 pt ≈ 1.333 px).
    const p = String(prefs.uiFontNormal.get()).split('|');
    const style = p[2] || 'normal';
    const root = document.documentElement.style;
    root.setProperty('--ui-font', p[0] || 'Consolas');
    root.setProperty('--ui-font-size', Math.round((parseFloat(p[1]) || 9) * 1.333) + 'px');
    root.setProperty('--ui-font-weight', style.includes('bold') ? 'bold' : 'normal');
    root.setProperty('--ui-font-style', style.includes('italic') ? 'italic' : 'normal');
    // The emphasised variant (uiFontBold) rides its own variable set - canvas readouts
    // resolve it through ui-font.js exactly as the base font (falls back to the base
    // face in bold, per the preference's own default).
    const b = String(prefs.uiFontBold.get()).split('|');
    const bStyle = b[2] || 'bold';
    root.setProperty('--ui-font-bold', b[0] || p[0] || 'Consolas');
    root.setProperty('--ui-font-bold-size',
      Math.round((parseFloat(b[1]) || parseFloat(p[1]) || 9) * 1.333) + 'px');
    root.setProperty('--ui-font-bold-weight', bStyle.includes('bold') ? 'bold' : 'normal');
    root.setProperty('--ui-font-bold-style', bStyle.includes('italic') ? 'italic' : 'normal');
  }

  // Enumerate input/output devices and populate the selects. THE ONLY ENUMERATION PATH, and there
  // are exactly two callers: the load-time scan in app.js init and the Preferences "Scan devices"
  // button (no dialog needed to get going). Nothing else may enumerate - a backend switch rebuilds
  // its combos from the cache (repopulateFromKnown) - because for the QA40x this click is also the
  // ONLY user activation there is, and navigator.usb.requestDevice()'s chooser needs it.
  //
  // USER ACTIVATION: keep every statement ahead of engine.scanDevices() synchronous. An await
  // inserted before it (a confirm, a fetch, a timer) spends the click's transient activation and the
  // browser then refuses the chooser - the QA40x would silently never be found.
  //
  // @param fromUserGesture true ONLY from the Scan click - the engine hands the WebUSB grant step
  //   its finder only for such a call (engine.scanDevices), so the load-time scan lists just the
  //   analyzers this origin was already granted and never prompts behind the user's back.
  async scan(fromUserGesture = false) {
    if (this.isBusy()) return;   // serialize with the live capture reopen - don't probe a device mid-reopen
    this.setBusy(true);
    $('#scan').prop('disabled', true);
    // Read BEFORE the await: a backend switch while this scan is in flight moves prefs.backend,
    // and everything below - the keep-guard and above all the _known cache write - belongs to the
    // backend that was actually enumerated. Keyed on the post-await value, a scan overtaken by a
    // switch wrote its listing into the NEW backend's cache slot and poisoned it for the session.
    const backend = this.prefs.backend.get();
    try {
      const { inputs, outputs } = await this.engine.scanDevices(fromUserGesture);
      // A backend switch that landed INSIDE that await has already rebuilt the combos for the
      // backend now selected (onBackendChanged), and this listing belongs to the one the scan
      // started on: painting it would put one backend's devices on screen under another's name -
      // and stamp them as that backend's, so the guard below would later keep them as its own.
      // The result is dropped WHOLE rather than half-filed: a cache entry carries the rate lists
      // too, and those can only be read off the combos this scan may no longer touch. The next
      // visit to that backend enumerates again. The finally still frees the busy flag + button.
      if (this.prefs.backend.get() !== backend) {
        console.info(`the ${backend} scan finished after the backend had been switched - dropped`);
        return;
      }
      // A listing that answered NOTHING never wipes the combos. A refused backend.select and
      // an unreachable server both arrive here as {inputs:[],outputs:[]} (devices.js), and
      // fillDeviceCombos empties both selects before it fills them: the operator lost the devices
      // AND - through the card sections, which key off the device label - the card combos, for a
      // scan that found nothing to put there. What is on screen came from the last enumeration
      // that DID answer, so it stays, and the reason goes on the status line. With nothing shown
      // yet the combos stay empty, which is the truthful "there is nothing" case.
      const answered = inputs.length > 0 || outputs.length > 0;
      // ...but only what is on screen for THIS backend may stay. On a SWITCH to a backend that
      // answers nothing - a server that is not connected, a backend it cannot run (devices.js) -
      // keeping the options would show the OUTGOING backend's devices as the new one's, and OK
      // would write one of those ids into the new backend's slot for the first open to fail on.
      // The new backend's own cache (or the stub built from its persisted selection, or nothing
      // at all) is the only truthful list here, which is exactly what repopulateFromKnown builds
      // - including the rates, depths, cards and readouts, so this scan is finished afterwards.
      if (!answered && this._comboBackend != null && this._comboBackend !== backend) {
        await this.repopulateFromKnown();
        $('#status').text('nothing was listed - showing what is known for this backend.');
        return;
      }
      if (answered) this.fillDeviceCombos(inputs, outputs);
      // Rates for the just-enumerated backend: its own format list where it has one (QA40x), else
      // the selected device's native rate. Announced on the bus, so a constrained pair is already
      // equal before engine.config is seeded below.
      await this.refreshRates();
      // Remember what this backend has, so a later switch BACK to it needs no enumeration. An
      // ANONYMOUS listing is not worth remembering: before the microphone permission is granted the
      // browser exposes one unlabelled input, and devices.js then falls back to `label = deviceId`,
      // so caching it would pin that hash into the combo for the rest of the session. Leaving it
      // uncached makes the next switch to this backend scan again - by which time the permission
      // usually exists and the real labels appear.
      const named = inputs.some((d) => d.label && d.label !== d.id);
      if (answered && (named || inputs.length === 0)) {
        this._known.set(backend, {
          inputs, outputs, inRates: this.rateOptions('#inRate'), outRates: this.rateOptions('#outRate'),
        });
      }
      // A rescan repopulates the device selects (no change event) - re-derive the card combos +
      // range tables + FS readouts for the restored selection.
      if (this.inputCard) this.inputCard.refresh();
      if (this.outputCard) this.outputCard.refresh();
      this.refreshFsReadouts();
      // ...and the ANALYZER's own card, for the backend this scan just settled on -
      // whichever machine that analyzer is plugged into.
      this.syncQa40xCardFor(backend);
      // Seed the live engine config from the freshly-selected devices. The pane Record
      // paths call readConfig() before opening the device, but the FreqResp sweep and the
      // Tune-notch wizard read engine.config DIRECTLY - so on a cold page (before any
      // scope/gen/FFT start ran readConfig) config.inDeviceId was still '' and their
      // getUserMedia({deviceId:{exact:''}}) threw OverconstrainedError -> the measurement
      // couldn't start until a scope/gen start happened to populate it. Seed it here so
      // every path has the configured device from load. Skipped while the Preferences
      // dialog is staging (it commits its own selection on OK via applyAudioPrefs).
      if (!this._staging) {
        this.engine.config.inDeviceId = $('#inSel').val();
        this.engine.config.outDeviceId = $('#outSel').val();
        this.engine.config.inRate = parseInt($('#inRate').val(), 10) || this.engine.config.inRate;
        this.engine.config.outRate = parseInt($('#outRate').val(), 10) || this.engine.config.outRate;
        // Apply the resolved card's per-channel full-scale for the selected devices
        // (Java: applyInput/OutputDeviceProfile on the initial capture / generator open).
        // resolveDeviceProfile matches the human LABEL (substring), not the deviceId the
        // <select> value carries - so pass the option text.
        this._applyDeviceProfiles();
      }
      $('#status').text(answered
        ? `${inputs.length} input(s), ${outputs.length} output(s) found - pick devices and press ▶.`
        : 'nothing was listed - the devices already shown are kept.');
    } catch (e) { $('#status').text('scan failed: ' + e.message); }
    finally { this.setBusy(false); $('#scan').prop('disabled', false); }
  }

  /**
   * Reads the bench analyzer's calibration and ranges into this machine's card store, for a
   * backend the app has just SETTLED on - whether the operator picked it or the page booted
   * already pointed at it (Java Qa40xSettingsUi.onSelected).
   *
   * ONE ENTRY POINT, on the scan's tail, because arriving at a remote backend and switching to
   * one must be indistinguishable. It used to hang off the combo's change handler alone,
   * so a page whose selection was the server's from the first paint - every page a Phonalyser
   * server serves - never ran it: no device-provided card was ever written, and the Ranges block
   * that renders only from such a card (card-section.js rangeCard) was therefore absent for every
   * backend. A change handler is the one place a boot cannot reach.
   *
   * Not awaited, like the call it replaces: the combos must not wait on a wire, and a bench that
   * will not answer costs the sync, not the dialog. Re-running it is harmless - the sync re-reads
   * the same two lock-free values and replaces the card by name - so the scan a backend switch
   * runs and any later scan simply write it again. The card sections are re-rendered when it
   * lands, since the card is what their range table reads.
   *
   * @param {string} backend the settled backend, in the combo's own spelling
   */
  syncQa40xCardFor(backend) {
    const settings = this.settingsManager();
    if (settings == null) return;
    const remote = remoteBackendOf(backend) != null;
    // Leaving a bench takes its transient card with it: it is resolved while that
    // selection is shown and by nothing afterwards, and this machine's own card of the same
    // name has been waiting behind it. The other way out is the session ENDING, which
    // {@link #onNetBackendsChanged} catches.
    if (!remote) this.dropBenchCard();
    // The LOCAL analyzer needs the same tail: its card used to be built only when a
    // capture opened the device, so a first selection with an empty store drew no ranges at
    // all. Both syncs answer null when the analyzer does not answer, and neither throws.
    const sync = remote
      ? (typeof settings.syncRemoteCard === 'function' ? settings.syncRemoteCard(backend) : null)
      : (typeof settings.syncLocalCard === 'function' ? settings.syncLocalCard() : null);
    if (sync == null) return;
    sync.then((card) => {
      if (card == null) return;            // the analyzer did not answer - nothing new to show
      if (this.inputCard) this.inputCard.refresh();
      if (this.outputCard) this.outputCard.refresh();
      this.refreshFsReadouts();
    }).catch((e) => console.warn('QA40x card sync failed', e));
  }

  /** Fills the two device selects from one backend's listing and re-selects that backend's
   *  persisted pair when its ids are still present (Java populateDeviceCombo(combo, devices,
   *  savedName)). Shared by the enumerating scan() and the cache-driven repopulateFromKnown(), so
   *  both build IDENTICAL options - including the `data-rate` attribute applyInputDeviceRate reads. */
  fillDeviceCombos(inputs, outputs) {
    // WHOSE devices are on screen. A scan that answers nothing may keep the options standing only
    // when they are this backend's own (see scan) - without this the combos are just a list, and
    // a list says nothing about which backend it describes.
    this._comboBackend = this.prefs.backend.get();
    // Inputs whose native sample rate was determined are listed with that rate; on engines that
    // can't determine any rate scanDevices falls back to listing them with no rate (suffix hidden).
    $('#inSel').empty(); inputs.forEach(d => {
      const suffix = (d.nativeRate > 0) ? ` - ${d.nativeRate} Hz` : '';
      $('#inSel').append(`<option value="${d.id}" data-rate="${d.nativeRate || ''}"`
        + `${this.lockedAttrs(d)}>${this.deviceLabel(d)}${suffix}</option>`);
    });
    $('#outSel').empty(); outputs.forEach(d => $('#outSel').append(
      `<option value="${d.id}"${this.lockedAttrs(d)}>${this.deviceLabel(d)}</option>`));
    const bp = this.prefs.current();
    if (bp.inputDeviceName && inputs.some(d => d.id === bp.inputDeviceName)) $('#inSel').val(bp.inputDeviceName);
    if (bp.outputDeviceName && outputs.some(d => d.id === bp.outputDeviceName)) $('#outSel').val(bp.outputDeviceName);
    this.selectFirstIfNothingHolds('#inSel', inputs);
    this.selectFirstIfNothingHolds('#outSel', outputs);
  }

  /**
   * Makes sure a filled combo actually CARRIES a selection - the desktop's "falls back to the
   * first device when the saved name matches nothing enumerated" (PreferencesDialog
   * .populateDeviceCombo), which a browser does NOT do for itself here.
   *
   * A &lt;select&gt; whose every option is disabled has no selection at all: the browser answers
   * selectedIndex -1 and jQuery's val() answers null. That is the state a bench in use by
   * another client puts this combo in - every row locked, so every row disabled - and the null
   * then travelled all the way to the wire as the device NAME, where the server could only say
   * it has no device called "null" - so the operator was told "reason unknown" instead of "the
   * device is in use by another application". Setting the value explicitly sticks even on a
   * disabled option, so the
   * stored selection survives being locked and the config keeps a real device name.
   */
  selectFirstIfNothingHolds(sel, devices) {
    if (devices.length === 0 || $(sel).val() != null) return;
    $(sel).val(devices[0].id);
  }

  /** What a device option READS: its label, and - for a bench device another client is measuring
   *  on - who holds it (Java PreferencesDialog.deviceLabel, the same key). Only the net listing
   *  carries `lockedBy`; every other backend's rows have no such state and read exactly as
   *  before. */
  deviceLabel(d) {
    return d.lockedBy ? t('preferences.device.lockedBy', d.label, d.lockedBy) : d.label;
  }

  /**
   * What marks a held device in the combo: the class that greys it, AND `disabled`, which is
   * what stops it being picked at all.
   *
   * Both, not one. The desktop REFUSES the pick of a device another client is measuring on -
   * it snaps the combo back to the previous selection and the click does nothing
   * (PreferencesDialog.revertLockedDevicePick), because a native SWT combo can neither disable
   * nor grey a single item. A browser can, so the web says the same thing the way HTML says it.
   * Greying it without disabling it left the entry italic and still selectable, which reads as
   * "taken, but yours if you want it" and is exactly the pick the bench would then refuse.
   */
  lockedAttrs(d) { return d.lockedBy ? ' class="device-locked" disabled' : ''; }

  /** The rates a rate <select> currently offers, as numbers - how scan() records what the
   *  just-enumerated backend answered, so a switch back to it can re-offer the same list. */
  rateOptions(sel) {
    return $(sel).find('option').map((_, o) => parseInt(o.value, 10)).get().filter((hz) => hz > 0);
  }

  /** What is ALREADY KNOWN about the SHOWN backend: the listing + rate lists its last scan left, or
   *  - for a backend never scanned this session - a one-entry stand-in per direction built from its
   *  persisted selection, so the user's saved device / rate stay visible and pressing Scan remains
   *  the only thing that touches hardware. */
  knownForShownBackend() {
    const cached = this._known.get(this.prefs.backend.get());
    if (cached) return cached;
    const bp = this.prefs.current();
    // The persisted name is the <select> VALUE; with no listing to draw a label from it is also the
    // only text available - and it is what resolveDeviceProfile matches a card on anyway.
    const stub = (name) => (name ? [{ id: name, label: name, nativeRate: null }] : []);
    return {
      inputs: stub(bp.inputDeviceName), outputs: stub(bp.outputDeviceName),
      inRates: bp.inputSampleRate ? [bp.inputSampleRate] : [],
      outRates: bp.outputSampleRate ? [bp.outputSampleRate] : [],
    };
  }

  /** Rebuilds every audio control for the SHOWN backend WITHOUT enumerating anything - the
   *  backend-switch path (and the Cancel rollback of one). Java's refreshDevices() re-enumerates
   *  here, which the web must not: for the QA40x that would mean a WebUSB call outside the Scan
   *  click, and requestDevice()'s chooser belongs to that click alone (device enumeration happens
   *  in exactly two places: the load-time scan and the Scan button). So the
   *  combos come from knownForShownBackend(), and everything downstream of them is re-derived
   *  exactly as refreshDevices does: rates, the rate announcement (so a one-clock backend lands
   *  already coupled), the per-card combos + range tables, and the FS readouts. */
  async repopulateFromKnown() {
    const known = this.knownForShownBackend();
    const bp = this.prefs.current();
    this.fillDeviceCombos(known.inputs, known.outputs);
    this.fillRates('#inRate', known.inRates, bp.inputSampleRate);
    this.fillRates('#outRate', known.outRates, bp.outputSampleRate);
    // The depth rows belong to the backend, so they are re-derived here too - this is the path a
    // backend SWITCH takes, and without it the QA40x rows survived a switch to Web Audio.
    await this.refreshDepths();
    this.publishRateChange(true);
    if (this.inputCard) this.inputCard.refresh();
    if (this.outputCard) this.outputCard.refresh();
    this.refreshFsReadouts();
    // Outside a staging session (the Cancel rollback) the restored device's card owns the live
    // full-scale again, exactly as scan()'s tail applies it.
    if (!this._staging) this._applyDeviceProfiles();
  }

  // The input rate IS the selected device's native rate - the only truly meaningful input rate
  // (docs/htmls/audio-devices.html): requesting any other rate just resamples. So #inRate lists
  // EXACTLY that one rate (the full rate list applies only to the output / DAC). When the native
  // rate couldn't be determined (non-Chromium fallback) the full rate list is shown so the user
  // can still choose. During the Preferences dialog only the DOM value is staged - applyAudioPrefs()
  // commits it on OK (no premature prefs.save()).
  applyInputDeviceRate() {
    const native = parseInt($('#inSel option:selected').attr('data-rate'), 10);
    if (native > 0) {
      $('#inRate').empty().append(`<option value="${native}">${native} Hz</option>`).val(String(native));
      if (this._staging) return;
      this.prefs.current().inputSampleRate = native; this.prefs.save();
    } else {
      // Native rate unknown -> graceful fallback to the full list (keep/restore the persisted value).
      const saved = parseInt(this.prefs.current().inputSampleRate, 10) || this.RATES[0];
      $('#inRate').empty();
      this.RATES.forEach((r) => $('#inRate').append(`<option value="${r}">${r} Hz</option>`));
      $('#inRate').val(String(saved));
    }
  }

  /** The manager of the backend the dialog is SHOWING, or null when that backend has none - the
   *  web's AudioBackend.instance().manager(edit.getBackend()). Every per-backend question the
   *  dialog asks (settings button, custom settings, format list) goes through here. */
  shownManager() {
    return this.backendManager(this.prefs.backend.get());
  }

  /**
   * The manager whose SETTINGS the shown selection has - the same analyzer whether it hangs on
   * this machine or on a server, because a backend's own settings belong to its TYPE (Java
   * registers one BackendSettingsUi per AudioBackendType and resolves a remote BackendKey through
   * its type). That is deliberately NOT {@link #shownManager}, which answers per backend
   * INSTANCE: the format list of a bench's QA40x is the bench's catalogue, never the local
   * analyzer's - asking the local manager for it would offer rates of hardware that is not the
   * one selected, or none at all when no analyzer is attached here.
   */
  settingsManager() {
    const selection = this.prefs.backend.get();
    // A remote selection is resolved by its TYPE and by nothing else. It used to try the whole
    // selection first and fall back to the type, which was harmless only while no manager
    // answered for a net: value; now that the bench's own manager does (it owns the formats),
    // that first hit would hand the QA40x's settings question to the net manager. Asking the
    // type outright is also the answer for a bench backend this machine has no manager for: a
    // JavaSound device on a server has no local analyzer behind it, and must never reach one.
    const remote = remoteBackendOf(selection);
    return this.backendManager(remote != null ? remote : selection);
  }

  /** Runs one step of the dialog's edit session on the settings manager, when it takes part in it
   *  at all - the begin/commit pair a backend whose settings live on a BENCH needs, and which a
   *  manager with only persisted settings does not have. */
  _settingsEdit(step) {
    const manager = this.settingsManager();
    if (manager && typeof manager[step] === 'function') manager[step]();
  }

  /** The shown backend's manager when it enumerates its own audio formats - today only the QA40x,
   *  whose rates are sampleRatesHz(model) and NEVER the Web-Audio native-rate probe - else null.
   *  Java asks AudioBackend for any backend's formats; the web's Web-Audio path has no format
   *  enumeration at all, so the absence of one IS the switch between the two rate policies. */
  formatSource() {
    const manager = this.shownManager();
    return (manager && typeof manager.listSupportedFormats === 'function') ? manager : null;
  }

  /** The sample rates the shown backend offers for one direction, or null when it enumerates no
   *  formats. Mirrors Java ratesOf(listSupported{Input,Output}Formats(backend, pickedDevice)): the
   *  device is picked by the <select>'s index into that manager's own listing, exactly as Java's
   *  pickedDevice indexes devices.inputs. */
  async backendRates(input) {
    return this.#backendFormatValues(input, (format) => format.sampleRate);
  }

  /**
   * The bit depths the shown backend offers for one direction - Java's
   * depthsOf(listSupported{Input,Output}Formats(...)), feeding the depth combo the same way the
   * rates feed theirs. Null when this backend enumerates no formats, in which case the caller
   * shows DEFAULT_BIT_DEPTHS, exactly as Java's fallback(depths, DEFAULT_BIT_DEPTHS) does: A
   * BACKEND IS ALWAYS SELECTED, so the combo is always populated - "no formats" means "we could
   * not ask", never "there is no backend".
   *
   * The web had no depth combo at all until now, on the reasoning that Web Audio is 32-bit float so
   * there is nothing to choose. QA40x ends that: the analyzer captures at 24 bits and its
   * front-panel I2S port runs 16 or 32 - precisely what a format list exists to report. (The "no
   * bit-depth selector" divergence recorded in the sync-java-to-web skill dates from the era when
   * Web Audio was the only backend, as did "no backend selector"; both died with the QA40x.)
   *
   * @param {boolean} input true for the capture direction
   * @returns {Promise<?number[]>} ascending depths, or null when the backend lists no formats
   */
  /**
   * Does the SHOWN backend have a selectable sample width at all? A backend declares this itself
   * (Qa40xDeviceManager.hasBitDepth); Web Audio has no manager and therefore answers false, which is
   * the truthful answer - its samples are float32 post-mixer, so there is no depth to pick and none
   * to report (doc/htmls/audio-devices.html §4). False hides both combos AND stops the dialog
   * reading, committing or acting on their values: the pipeline is float32 and says so.
   *
   * @returns {boolean}
   */
  backendHasBitDepth() {
    const manager = this.shownManager();
    return !!(manager && typeof manager.hasBitDepth === 'function' && manager.hasBitDepth());
  }

  /** Shows or hides the Input + Output depth rows for the shown backend (label AND combo, so the
   *  grid closes up rather than leaving a gap). */
  refreshDepthVisibility() {
    $('.depth-row').toggleClass('d-none', !this.backendHasBitDepth());
  }

  async backendDepths(input) {
    // `bits` is the field name Qa40xAudioFormat uses (the web stand-in for
    // javax.sound.sampled.AudioFormat, whose getSampleSizeInBits() Java reads here).
    return this.#backendFormatValues(input, (format) => format.bits);
  }

  /** The distinct values one field of the shown backend's format list takes for a direction. */
  async #backendFormatValues(input, pick) {
    const manager = this.formatSource();
    if (!manager) return null;
    const devices = input ? await manager.listInputDevices() : await manager.listOutputDevices();
    const sel = document.getElementById(input ? 'inSel' : 'outSel');
    const device = (sel && sel.selectedIndex >= 0) ? devices[sel.selectedIndex] : null;
    if (!device) return null;
    const values = new Set();
    for (const format of manager.listSupportedFormats(device, !input)) {
      const value = pick(format);
      if (value > 0) values.add(value);
    }
    return [...values].sort((a, b) => a - b);
  }

  /** Replaces a rate <select>'s options, keeping `preferred` selected when it is offered and
   *  falling back to the first entry otherwise (Java populateIntCombo(combo, rates, " Hz", ...)). */
  fillRates(sel, rates, preferred) {
    this.fillIntCombo(sel, rates, preferred, ' Hz');
  }

  /** Java's populateIntCombo(combo, values, suffix, saved) - the one populate both the rate and the
   *  depth combos go through, so they can never drift apart in selection behaviour. */
  fillIntCombo(sel, values, preferred, suffix) {
    const $s = $(sel).empty();
    values.forEach((v) => $s.append(`<option value="${v}">${v}${suffix}</option>`));
    $s.val(String(values.includes(preferred) ? preferred : (values.length ? values[0] : '')));
  }

  /** Repopulates both rate combos for the SHOWN backend, then announces the input rate on the bus
   *  (Java refreshDevices: the two refresh*RatesAndDepths calls plus the unconditional
   *  publishRateChange(true) tail). A backend that enumerates no formats keeps the native-probe
   *  input rate and its static output list; the QA40x lands already rate-coupled, on a dialog
   *  open, a backend switch AND the load-time scan. */
  /**
   * @param {Object} [opts]
   * @param {boolean} [opts.preferOnScreen] keep the depth currently SHOWN when the new list still
   *   offers it, instead of the stored one. True only after the backend's own settings dialog was
   *   accepted mid-session (the I2S port swaps the output list and a depth already staged must
   *   survive); false on a dialog OPEN, where the on-screen value is the previous session's
   *   leftover and the stored preference is the truth.
   */
  async refreshRates({ preferOnScreen = false } = {}) {
    const bp = this.prefs.current();
    const rates = await this.backendRates(true);
    if (rates == null) {
      this.applyInputDeviceRate();
    } else {
      this.fillRates('#inRate', rates, bp.inputSampleRate);
      // Enumerated for the output direction in its own right (Java refreshOutputRatesAndDepths),
      // even though a one-clock device answers the same list both ways.
      this.fillRates('#outRate', await this.backendRates(false) || rates, bp.outputSampleRate);
    }
    // The DEPTHS go with them (Java refresh*RatesAndDepths does both in one call).
    await this.refreshDepths({ preferOnScreen });
    this.publishRateChange(true);
  }

  /**
   * Shows or hides the depth rows for the shown backend and, when it has depths, repopulates them.
   * EVERY path that rebuilds the audio controls must call this - a backend switch reaches the combos
   * through repopulateFromKnown(), not refreshRates(), and skipping it there left the rows on screen
   * after switching from QA40x to Web Audio.
   *
   * @param {Object} [opts]
   * @param {boolean} [opts.preferOnScreen] see refreshRates
   */
  async refreshDepths({ preferOnScreen = false } = {}) {
    this.refreshDepthVisibility();
    if (!this.backendHasBitDepth()) {
      return;                     // Web Audio: float32 post-mixer, nothing to choose or report
    }
    const bp = this.prefs.current();
    // Prefer what is ON SCREEN over the stored value: this also runs after the backend's own
    // settings dialog is accepted (the I2S port swaps the output list to 16 / 32), and the depth the
    // user had already staged must survive that re-derive - it is committed only on OK.
    const staged = (sel, stored) => (preferOnScreen ? (parseInt($(sel).val(), 10) || stored) : stored);
    this.fillIntCombo('#inDepth', await this.backendDepths(true) || DEFAULT_BIT_DEPTHS,
      staged('#inDepth', bp.inputBitDepth), ' bits');
    this.fillIntCombo('#outDepth', await this.backendDepths(false) || DEFAULT_BIT_DEPTHS,
      staged('#outDepth', bp.outputBitDepth), ' bits');
  }

  /**
   * Re-derives the OUTPUT direction's rate and width lists for the device now selected there -
   * Java's refreshOutputRatesAndDepths, and the counterpart of what {@link #refreshRates} does
   * when the INPUT device changes.
   *
   * Scoped to the one direction on purpose: the input combos are mid-edit and their staged
   * values are not committed yet, so re-deriving them from the stored preference here would
   * throw away a rate the operator had just picked.
   *
   * A backend that enumerates no formats (Web Audio) has nothing per device to re-derive, and
   * both halves below are no-ops for it: its output list is static and its widths are hidden.
   */
  async refreshOutputRatesAndDepths() {
    const bp = this.prefs.current();
    const rates = await this.backendRates(false);
    if (rates != null && rates.length > 0) this.fillRates('#outRate', rates, bp.outputSampleRate);
    this.refreshDepthVisibility();
    if (!this.backendHasBitDepth()) return;
    const depths = await this.backendDepths(false);
    this.fillIntCombo('#outDepth', (depths && depths.length > 0) ? depths : DEFAULT_BIT_DEPTHS,
      bp.outputBitDepth, ' bits');
  }

  /** Announces one direction's chosen sample rate so a rate-constraint subscriber (today
   *  Qa40xRateConstraint, which holds the QA40x's one reg-9 clock) can mirror it onto the other
   *  direction. Device-agnostic and UNGUARDED by backend, exactly as Java's publishRateChange: the
   *  payload carries the shown backend AND the resolved card, and a backend nobody constrains
   *  simply gets no answer back. */
  publishRateChange(input) {
    const hz = parseInt($(input ? '#inRate' : '#outRate').val(), 10);
    if (!hz) return;
    MessageBus.instance().publish(Events.PREFS_SAMPLE_RATE_CHANGED,
      { input, sampleRateHz: hz, backend: this.prefs.backend.get(), card: this.cardNameFor(input) });
  }

  /** The resolved card name for a direction's selected device, or null when the device maps to no
   *  card - carried on the rate-change payload so a card-scoped constraint can match it (Java
   *  cardNameFor). resolveDeviceProfile matches the human LABEL, so pass the option text. */
  cardNameFor(input) {
    if (!this.deviceStore) return null;
    const card = this.deviceStore.resolveDeviceProfile(input ? this.inputDeviceLabel() : this.outputDeviceLabel());
    return card ? card.name : null;
  }

  /** One direction's active range as the DEVICE_ACTIVE_RANGE_CHANGED payload - { input,
   *  activeRangeLabel } - or null when the selected device maps to no card, or that card's endpoint
   *  for this direction has no active range (Java activeRange(prefs, bp, input): a null anywhere in
   *  the chain means "nothing to compare", and Java publishes nothing when either side is null).
   *  Deliberately card-LESS, like the Java record: the subscriber consults its own state. */
  activeRangeOf(input) {
    if (!this.deviceStore) return null;
    const card = this.deviceStore.resolveDeviceProfile(input ? this.inputDeviceLabel() : this.outputDeviceLabel());
    if (card == null) return null;
    const endPoint = input ? card.input : card.output;
    const label = endPoint == null ? null : endPoint.activeRange;
    return label == null ? null : { input, activeRangeLabel: label };
  }

  /** Drops every statistic accumulated at the PREVIOUS audio settings - the scope's running
   *  measurement history and the FFT's cross-tick average. Called from the OK path for a direction
   *  that changed, so a new device / rate / range never shows figures averaged across the change. */
  resetMeasurementStatistics() {
    this._resetStatistics();
  }

  /** Open-time snapshot of ONE direction's active-range selection: the card it belongs to and BOTH
   *  channel labels. {@link activeRangeOf} stays the publish baseline (it returns the event payload
   *  shape the range controller expects); this is the richer record Cancel needs to restore. */
  cardRangeSnapshot(input) {
    if (!this.deviceStore) return null;
    const card = this.deviceStore.resolveDeviceProfile(
      input ? this.inputDeviceLabel() : this.outputDeviceLabel());
    if (card == null) return null;
    const endPoint = input ? card.input : card.output;
    if (endPoint == null) return null;
    return {
      input, cardName: card.name,
      activeRange: endPoint.activeRange, activeRangeRight: endPoint.activeRangeRight,
    };
  }

  /** Puts both cards' active-range selections back to the open-time snapshot - the Cancel path.
   *  Without it a cancelled Preferences left a range the user only tried: the radios mutate the
   *  live card, persist it, and push the range's full-scale into prefs, none of which OK gates. */
  restoreCardRanges() {
    if (!this.deviceStore) return;
    let restored = false;
    for (const snap of [this._inRangeSnapshot, this._outRangeSnapshot]) {
      if (snap == null) continue;
      const card = this.deviceStore.findAudioDeviceProfile(snap.cardName);
      if (card == null) continue;
      const endPoint = snap.input ? card.input : card.output;
      if (endPoint == null) continue;
      if (endPoint.activeRange === snap.activeRange
        && endPoint.activeRangeRight === snap.activeRangeRight) continue;
      endPoint.activeRange = snap.activeRange;
      endPoint.activeRangeRight = snap.activeRangeRight;
      restored = true;
    }
    this._inRangeSnapshot = this._outRangeSnapshot = null;
    if (!restored) return;
    this.deviceStore.saveDevices();          // the radio persisted the change; persist the undo too
    if (this.inputCard) this.inputCard.refresh();
    if (this.outputCard) this.outputCard.refresh();
    this._applyDeviceProfiles();             // full-scale back to the restored ranges
    this.refreshFsReadouts();
  }

  /** Announces, per direction, an active range that MOVED while the dialog was open - the
   *  open-time snapshot against the committed value (Java PreferencesDialog.java:934-935). This is
   *  what turns a card's range radio into a physical re-range: the QA40x range controller decodes
   *  the label and writes reg 0x05 / 0x06 (or, with no session open, stores it for the next open).
   *  Without it the card and every dBV readout would move while the attenuator did not. Cancel
   *  never reaches here, and an unchanged range publishes nothing. */
  publishActiveRangeChanges() {
    const bus = MessageBus.instance();
    const publishMoved = (before, after) => {
      if (before == null || after == null) return;
      if (before.activeRangeLabel === after.activeRangeLabel) return;
      bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, after);
    };
    publishMoved(this._inRangeBefore, this.activeRangeOf(true));
    publishMoved(this._outRangeBefore, this.activeRangeOf(false));
  }

  /** Selects the rate option worth `hz`, a no-op when that rate isn't offered. Setting a
   *  <select>'s value fires no change event (as SWT's Combo.select fires no Selection), so the
   *  correction this applies never re-announces - the coupling round-trip ends here. */
  selectRateItem(sel, hz) {
    const $s = $(sel);
    if ($s.find(`option[value="${hz}"]`).length) $s.val(String(hz));
  }

  /** Shows the per-backend Settings button only for a backend whose manager has settings of its
   *  own (Java refreshCustomPrefsButton; `d-none` is the web's exclude-from-layout), and NAMES it
   *  after that backend - "QA40x preferences" - exactly as the desktop does.
   *
   *  The label is computed here rather than bound with `data-i18n` in the markup: the key takes the
   *  backend name as {0}, which a static binding cannot supply, and a page that carried the binding
   *  would render the placeholder literally. The name is the backend's OWN display name, never the
   *  "<server> -> <backend>" composite - a bench's analyzer opens the same settings as a local one,
   *  and the desktop's button says so too. */
  refreshCustomPrefsButton() {
    const manager = this.settingsManager();
    const has = !!(manager && manager.hasCustomPreferences && manager.hasCustomPreferences());
    const $button = $('#backendSettings');
    $button.toggleClass('d-none', !has);
    if (!has) return;
    const selection = this.prefs.backend.get();
    const remote = remoteBackendOf(selection);
    $button.text(t('preferences.backend.customPrefs',
                   backendDisplayName(remote != null ? remote : selection)));
  }

  /** Opens the shown backend's own settings (Java: the customPrefs button ->
   *  manager.openCustomPreferences(dialog)). What the user accepts stays PENDING in that backend's
   *  preference block and reaches the live settings only when THIS dialog is OK'd
   *  (commitCustomPreferencesEdit in applyAudioPrefs), so a Preferences Cancel discards it too.
   *  Java re-reads the output DEPTH list afterwards because the QA40x's I2S port swaps it - while
   *  the port is on the output offers the port's 16 / 32 in place of the analyzer's 24 - so the
   *  combos are re-derived here too. The list comes from the backend's EDIT value, which is why it
   *  updates the moment this dialog is accepted rather than waiting for Preferences OK. */
  async openBackendSettings() {
    const manager = this.settingsManager();
    if (manager && manager.openCustomPreferences) {
      await manager.openCustomPreferences(document.getElementById('prefsModal'));
      await this.refreshRates({ preferOnScreen: true });
    }
  }

  /** Applies the resolved per-card per-channel full-scale for the currently-selected
   *  input + output devices (Java applyInput/OutputDeviceProfile). resolveDeviceProfile
   *  matches the human device LABEL (the option text) as a substring - the <select>
   *  value carries the Web Audio deviceId, which the recognition patterns do not match.
   *  A no-op when the device resolves to no card (the legacy scalars stand). */
  _applyDeviceProfiles() {
    if (!this.deviceStore) return;
    this._applyDeviceProfile(this.inputDeviceLabel(), true);
    this._applyDeviceProfile(this.outputDeviceLabel(), false);
  }

  /**
   * One direction's half of {@link #_applyDeviceProfiles}: it puts into force the calibration
   * {@link #_selectedCalibration} answers - the ONE decision about WHOSE a measurement is scaled
   * by (Java Preferences.applyDeviceProfile(DeviceRef, boolean), whose remote() branch this is).
   *
   * <p>A device on a BENCH is measured against the full scale the SERVER stores for it (spec 4.3
   * `cal`) and against nothing else. This machine may well hold a card whose match string fits
   * the same name - a bench routinely has the same model at both ends - but that card
   * describes a DIFFERENT EXEMPLAR, with its own attenuators and its own measured full scale, so
   * resolving it here would scale the bench's samples by this machine's front end. That was the
   * observed defect: three clients with three card stores read three different Vpp off one
   * capture, and editing the server's devices.yaml moved none of them.
   *
   * <p>A bench device the server has NO calibration for applies nothing at all - the standing
   * scalars keep their value and the dialog's uncalibrated warning is what tells the operator
   * (the local store is deliberately NOT consulted as a fallback, for the reason above; the
   * copy offer is how a local card gets to the bench, with the operator's yes).
   */
  _applyDeviceProfile(deviceName, input) {
    const cal = this._selectedCalibration(input, deviceName);
    if (cal != null) this.deviceStore.applyCalibration(input, cal.left, cal.right);
  }

  /**
   * The full-scale PAIR that belongs to the device NAMED here, as the stored RMS volts, or null
   * when nothing calibrated resolves for it - {@link #_applyDeviceProfile}'s decision, split out
   * because the READOUTS have to make exactly the same one.
   *
   * <p>Two consumers, one answer, on purpose: what the dialog SHOWS as a device's full scale and
   * what a measurement on it would be scaled by must never be two different resolutions, or the
   * screen would vouch for a number nothing uses.
   */
  _selectedCalibration(input, deviceName) {
    if (this.deviceStore == null) return null;
    if (remoteBackendOf(this.prefs.backend.get()) != null) {
      const cal = this._cardSource ? this._cardSource.calibration(input, deviceName) : null;
      return cal == null ? null : { left: cal.fsRmsLeft, right: cal.fsRmsRight };
    }
    const cal = this.deviceStore.deviceCalibration(deviceName, input);
    return cal == null ? null : { left: cal.fsLeft, right: cal.fsRight };
  }

  /** The human LABEL of the currently selected input / output device - the <option> text the
   *  recognition patterns match (NOT the Web Audio deviceId the <select> value carries). The
   *  small accessor the calibration dialog + card sections resolve the current card by. */
  inputDeviceLabel() { return $('#inSel option:selected').text(); }

  outputDeviceLabel() { return $('#outSel option:selected').text(); }

  /**
   * Re-renders the per-channel ADC/DAC full-scale readouts for the devices SELECTED right now
   * (dialog open, card edit / calibrate, and every device pick). '-' for a non-positive value.
   *
   * <p><b>From the selection, not from what is in force.</b> The device combos STAGE - nothing is
   * applied until OK - so reading the live scalars showed the OUTGOING device's full scale under
   * the incoming device's name: switching to a bench device went on displaying this machine's
   * values until OK, and the server's appeared only when the dialog was opened again - so
   * switching to a remote device showed the wrong ADC/DAC calibration values until OK.
   * {@link #_selectedCalibration} answers for the device on screen - the SERVER's
   * stored pair for a bench device, this machine's card for a local one.
   *
   * <p>A selection that resolves nothing calibrated falls back to the live scalars, because that
   * is then the literal truth: {@link #_applyDeviceProfile} applies nothing for such a device and
   * the standing values are what a measurement on it would use.
   *
   * <p>Display only. Nothing here writes a preference or touches the live session, so a Cancel
   * still leaves the running measurement exactly where it was.
   */
  refreshFsReadouts() {
    const fmt = (v) => ((v > 0 && Number.isFinite(v)) ? v.toFixed(6) : '-');
    const adc = this._selectedCalibration(true, this.inputDeviceLabel());
    // The DAC line reads in PEAK volts and a card stores RMS - the conversion applyCalibration
    // makes on its way into the scalar, made here on its way to the screen.
    const dac = this._selectedCalibration(false, this.outputDeviceLabel());
    $('#adcFsVrms').text(fmt(adc != null ? adc.left : this.prefs.getAdcFsVoltageRms('L')));
    $('#adcFsVrmsRight').text(fmt(adc != null ? adc.right : this.prefs.getAdcFsVoltageRms('R')));
    $('#dacFsAmpl').text(fmt(dac != null ? dac.left * Math.SQRT2 : this.prefs.getDacFsVoltageAmpl('L')));
    $('#dacFsAmplRight').text(fmt(dac != null ? dac.right * Math.SQRT2 : this.prefs.getDacFsVoltageAmpl('R')));
  }

  /** Builds the ten Osc/FFT/FreqResp NumericStepFields over their `.numfield` chrome and the
   *  twelve colour buttons' live repaint, once (the DOM is static). Mirrors PreferencesDialog's
   *  per-field NumericStepField ctors + applyButtonColor. Values are staged/committed by
   *  seedPrefsTabs()/applyPrefsTabs(); this only owns the widget construction + chrome. */
  buildPrefFields() {
    this.prefFields = {};
    for (const [id, spec] of Object.entries(PREF_FIELD_SPECS)) {
      const input = document.getElementById(id);
      if (!input) continue;
      const onChange = spec.pct ? () => this.updateFrMaxNyqHz() : null;
      this.prefFields[id] = new NumericStepField(input, new NumericStepModel(spec.model),
        { onChange, tooltipBase: input.title || '' });
    }
    // Colour buttons: repaint background + hex text live as the native picker changes, and open
    // the picker on a click anywhere on the button (the label already forwards to its input, but
    // the hidden 1px input isn't the click target, so trigger it explicitly).
    $('.pref-color').each((_, el) => {
      const inp = el.querySelector('input[type=color]');
      $(inp).on('input change', () => this.paintColorButton(inp));
      // The <label> natively forwards a click to its inner input; cancel that default and open
      // the picker once ourselves so the OS colour dialog can't double-open (open->close->reopen).
      $(el).on('click', (ev) => { if (ev.target === inp) return; ev.preventDefault(); inp.click(); });
    });
  }

  /**
   * Builds the Backend selector beside Scan (Java PreferencesDialog's backend combo). Lists the
   * backends a browser actually has: Web Audio always, QA40x only where WebUSB exists - the web
   * analogue of Java AudioBackendType.isAvailable(), which gates QA40X on libusb loading.
   *
   * A value persisted by an earlier build is one of the Java OS names (default 'WASAPI'). Those are
   * still legal enum values, but they are not offered here, so such a value is migrated to
   * WEB_AUDIO - carrying its BackendPrefs across first, or the user's saved device / rate / card
   * selections would appear empty under the new key (prefs.current() is keyed by backend name).
   */
  buildBackendSelector() {
    const sel = $('#backendSel');
    if (!sel.length) return;
    const webUsb = typeof navigator !== 'undefined' && 'usb' in navigator;

    // Migrate a legacy OS-backend value, preserving that backend's device slot.
    const LEGACY = ['WASAPI', 'WDMKS', 'COREAUDIO', 'JAVASOUND'];
    if (LEGACY.includes(this.prefs.backend.get())) {
      const carried = this.prefs.current().snapshot();
      this.prefs.prefsFor('WEB_AUDIO').copyFrom(carried);
      this.prefs.backend.set('WEB_AUDIO');
    }

    // QA40x is listed even when unusable, so its absence is explained rather than mysterious.
    // NO LOCAL ENTRIES AT ALL when this page came from a Phonalyser server - whether that is the
    // embedded packaging (a build fact) or the full bundle served from the same place (a runtime
    // one, {@link #servedByServer}). Such a page is plain http:// from a LAN host, which is not a
    // secure context: getUserMedia and WebUSB do not exist on it, so Web Audio is a choice whose
    // every open must fail and even the disabled-and-explained QA40x row is a choice the operator
    // cannot act on - it is not "this browser lacks WebUSB", it is "no page served from here has
    // it". Anywhere else - docs/web, a dev server, file:// - the local
    // entries stand exactly as before.
    const opts = (EMBEDDED || this.servedByServer()) ? [] : [
      { value: 'WEB_AUDIO', label: 'Web Audio', enabled: true, why: '' },
      { value: 'QA40X', label: 'QA40x', enabled: webUsb,
        // The reason names the SAME four browsers as the start-up gate's own notice
        // (web.browser.unsupported.message) - it used to say "Chrome or Edge", which told an
        // Opera or Brave operator their browser was the problem when it is not.
        why: webUsb ? '' : t('web.preferences.qa40x.requiresWebUsb') },
    ];
    // A CONNECTED server's backends stand beside the local ones, one entry each, named
    // "<server> -> <backend>" (spec 4.3's explicit listing rule). Read SYNCHRONOUSLY from the
    // manager's cache - it re-asks on connect and on every ev.devices.changed - because this
    // build fills a combo and an await here would either block it or fill it in after the
    // operator had already looked. A backend the bench has but cannot run (CoreAudio on
    // Windows) is listed and disabled, for the same reason the QA40x is.
    for (const entry of this.netBackends()) {
      // NOT OFFERED AT ALL when the server says it cannot serve it (this supersedes listing it
      // disabled): CoreAudio on a server that is not a
      // Mac, or a QA40X with no analyzer plugged into it, are not choices the operator has -
      // they are backends that do not exist on that machine. A greyed row invites the question
      // "why can I see it"; an absent one is the truth. The LOCAL QA40x above keeps its
      // disabled-and-explained row: that one is about THIS browser lacking WebUSB, which the
      // operator can act on.
      if (!(entry.available && entry.operational)) continue;
      opts.push({
        // net:<remote name>: a bench's "QA40X" and this machine's are different devices on
        // different transports, and a bare name could not say which (see NET_BACKEND_PREFIX).
        value: netBackendValue(entry.backend), label: entry.displayName, enabled: true, why: '',
      });
    }
    // A stored selection this build cannot offer falls back to the FIRST entry, and the
    // PREFERENCE moves with it (Java BackendChoices.resolve + populateBackendCombo's
    // edit.setSelectedBackend). It is not cosmetic: the dialog edits the selection it SHOWS, so a
    // combo left displaying Web Audio while prefs.backend still answers 'net:QA40X' would write
    // Web Audio's devices into the bench's settings - and every capture acquire would meanwhile
    // throw "no session is open". Unlike the legacy OS names above, the BackendPrefs are NOT
    // carried across: a net: backend comes back when its bench does (the subscription in the
    // constructor rebuilds this combo), and its parked device / rate / card selections must be
    // waiting for it rather than smeared onto Web Audio.
    const wanted = this.prefs.backend.get();
    if (remoteBackendOf(wanted) != null && !opts.some((o) => o.value === wanted)) {
      // A REMEMBERED BENCH BACKEND IS NEVER MIGRATED AWAY. Its bench is simply not connected
      // YET - this combo is built when the dialog is wired, before the auto-connect or the
      // servers dialog can have opened a session - and rewriting the preference here is what
      // made the operator's remote device, rate, width and card selections "gone after a
      // reload": they are all stored under the net: key, and that key stopped being the
      // selected one. So the row is SHOWN instead, marked with the state
      // the server list already has a word for, and the combo still cannot disagree with the
      // preference - which is all that was ever required. The moment the bench answers,
      // REMOTE_BACKENDS_CHANGED rebuilds this combo and the real entry takes its place.
      opts.push({ value: wanted, enabled: true, why: '',
        label: `${remoteBackendOf(wanted)} (${t('net.servers.state.offline')})` });
    } else if (opts.length > 0 && !opts.some((o) => o.value === wanted)) {
      // A LOCAL backend this build cannot offer still falls back (Java BackendChoices.resolve):
      // there is no session that could bring it, so the preference is simply wrong here.
      console.info(`Backend ${wanted} is not reachable from here; falling back to ${opts[0].value}`);
      this.prefs.backend.set(opts[0].value);
    }
    // The options are written AFTER the resolution above, because it can add one: a remembered
    // bench backend gets its own row, and appending before that decision left it out of the DOM.
    sel.empty();
    for (const o of opts) {
      sel.append($('<option>').val(o.value).text(o.label)
        .prop('disabled', !o.enabled).attr('title', o.why || null));
    }
    sel.val(this.prefs.backend.get());
    // Two-way: a selection stages the switch (onBackendChanged), and an external change (preset
    // load) pushes back. The change handler is namespaced so a rebuild replaces it; the prefs
    // listener has no such handle, so it is attached ONCE.
    sel.off('change.backend').on('change.backend', () => this.onBackendChanged(sel.val()));
    if (!this._backendListenerBound) {
      this._backendListenerBound = true;
      this.prefs.backend.addListener((v) => { if ($('#backendSel').val() !== v) $('#backendSel').val(v); });
    }
    this.refreshCustomPrefsButton();
  }

  /**
   * The connected server's offer moved - it just connected, reconnected, or its devices changed
   * (spec 4.3's ev.devices.changed). The backend combo is rebuilt from the new offer, AND, when
   * the backend on screen is one of that server's, its device / rate / width combos with it:
   * they were filled from the PREVIOUS session's catalogue, and after a reconnect they still
   * offered whatever the bench had answered before it went away.
   *
   * The re-enumeration is the ordinary scan - one backend.select round trip for a net backend,
   * the same call the Scan button makes - so nothing new decides what a device list is. It
   * carries no user gesture (this is a notification, not a click), which is exactly right: the
   * QA40x's WebUSB chooser must never be raised from here, and for a remote backend there is no
   * chooser to raise.
   */
  onNetBackendsChanged(entries) {
    // An EMPTY offer is a session that has gone - a disconnect, or one that ended under us
    // (both empty the entry list). The bench's card goes with it: it is a transient overlay of
    // THAT analyzer, and a dead bench's attenuator positions must not stay on screen until
    // the operator happens to change backend. A non-empty offer is a live session - an
    // ev.devices.changed mid-measurement - and the card stays exactly as it is.
    if (!Array.isArray(entries) || entries.length === 0) this.dropBenchCard();
    this.buildBackendSelector();
    if (remoteBackendOf(this.prefs.backend.get()) == null) return;
    this.scan().catch((e) => console.warn('net client: re-reading the bench\'s devices failed', e));
  }

  /** Drops the transient bench card and repaints the card sections, so the ranges block a
   *  bench card was drawing empties with it. The store's own cards are untouched - a local card
   *  of the same name has been waiting behind the overlay all along. */
  dropBenchCard() {
    if (this.deviceStore == null || typeof this.deviceStore.clearTransientProfiles !== 'function') {
      return;
    }
    this.deviceStore.clearTransientProfiles();
    if (this.inputCard) this.inputCard.refresh();
    if (this.outputCard) this.outputCard.refresh();
  }

  /**
   * A backend pick. Java stages it in the detached working copy (edit.setBackend), enumerates the
   * new backend WITHOUT activating it, and calls AudioBackend.setActive only on OK. The web has no
   * detached copy and prefs.backend IS what prefs.current() - hence every device / rate / card
   * lookup and the scan's own dispatch - keys off, so the switch stages THERE: snapshotted on open
   * and rolled back by restoreAudioPrefs on Cancel. Nothing audio-side moves until OK, where
   * applyAudioPrefs brackets it with before/afterApplyBackendChanges(true, true).
   *
   * Unlike Java the OUTGOING backend's UNCOMMITTED device/rate edits are dropped rather than kept
   * (Java's captureUiToActive parks them in the working copy). Writing them into the live
   * per-backend slot here would survive a Cancel - worse than losing an edit on a backend the user
   * is switching away from.
   *
   * It also does NOT scan. Java's refreshDevices() enumerates the newly chosen backend here; the web
   * cannot, because the QA40x's chooser may only be raised from the Scan click's user activation
   * - so the combos are rebuilt from what is already known for that backend and
   * the hardware is left alone until Scan or OK.
   */
  onBackendChanged(backend) {
    // A bound property: it persists itself, which is why the Cancel path rolls it back explicitly.
    this.prefs.backend.set(backend);
    this.refreshCustomPrefsButton();
    // The analyzer's card is synced by the SCAN this switch runs (see syncQa40xCardFor,
    // called from scan's tail): picking a backend and booting on one must be the same thing.
    // A BENCH backend is always re-scanned, cache or no cache. The session answers only for
    // the backend it was last ROUTED at by backend.select (net-device-manager.call), so after a
    // scan of another of the server's backends a switch BACK to this one rebuilt its combos from
    // the cache while the session was still pointed elsewhere: the card list came back null and
    // the card combo was empty until "Scan devices" re-routed it. The scan IS the routing - one
    // backend.select round trip, and the catalogue, rates, card sections and FS readouts all
    // follow through the one enumeration path. No user gesture: the net branch never touches
    // WebUSB (the same reasoning onNetBackendsChanged re-scans on).
    if (remoteBackendOf(backend) != null) {
      // scan() refuses while the shared busy flag is held (a live capture reopen), and a switch
      // that enumerates nothing must still rebuild the combos for the backend now selected - the
      // cache path is what every backend switch did before this branch existed. Read here rather
      // than left to scan()'s own guard: there is no await in between, so the two agree.
      if (this.isBusy()) this.repopulateFromKnown();
      else this.scan();
      return;
    }
    // A backend never enumerated in THIS session has nothing to rebuild from: its cache is empty and
    // the fallback stub is built from the persisted selection, whose "name" for Web Audio is an
    // opaque deviceId - which is why picking it showed one garbage entry like `04c0bd5b9d7e...`.
    // Scan it once; a backend already listed this session still rebuilds from the cache and never
    // touches hardware.
    if (!this._known.has(backend)) {
      this.scan(true);
      return;
    }
    if (backend === QA40X_BACKEND) {
      // Picking QA40x asks for the analyzer RIGHT AWAY rather than making the operator
      // find "Scan devices" first. A <select> change IS user activation, so requestDevice()'s
      // chooser is permitted here - the Scan click is not the only gesture available, which is what
      // the earlier "only Scan may enumerate" reading got wrong. With no analyzer yet granted this
      // raises the chooser; with one granted it simply lists it.
      this.scan(true);
      return;
    }
    // Every OTHER backend rebuilds from the cache and never enumerates: the Web Audio probe costs
    // ~½ s per device and the load-time scan already listed them. Scanning here also showed the
    // WRONG devices - a scan that finds nothing or throws leaves scan()'s catch to set a status line
    // only, so the OUTGOING backend's options stayed on screen (picking QA40x listed microphones).
    this.repopulateFromKnown();   // async: the combos settle on the next tick, nothing awaits them
  }

  /** Paints one colour button's swatch: background = the picked colour, centred "#RRGGBB" text
   *  (PreferencesDialog.applyButtonColor - foreground left black, no contrast rule). */
  paintColorButton(inp) {
    const hex = String(inp.value || '#000000').toLowerCase();
    const btn = inp.closest('.pref-color');
    if (!btn) return;
    btn.style.background = hex;
    const span = btn.querySelector('.pref-color-hex');
    if (span) span.textContent = hex.toUpperCase();
  }

  /** Wires #menuPrefs/#prefsOk + the staged audio device/rate handlers + the tab strip. */
  bind() {
    const engine = this.engine, prefs = this.prefs;

    this.buildPrefFields();
    if (this.inputCard) this.inputCard.bind();
    if (this.outputCard) this.outputCard.bind();

    $('#menuPrefs').on('click', () => this.modal.show());

    // #inSel change -> re-derive the rate combos for the new device (refreshRates: the backend's own
    // format list, else the device's native rate). Staging only: the device/rate selections apply to
    // the live engine solely via applyAudioPrefs() on OK (there is no live reopen/restart on
    // selection). Pairs with the Scan button.
    $('#inSel').on('change', () => this.refreshRates());
    // A device change re-resolves the direction's card (visibly switching / clearing to "New
    // card...", offering to create one for an unrecognised device) - Java CardSection.onDeviceChanged.
    $('#inSel').on('change', () => { if (this.inputCard) this.inputCard.onDeviceChanged(); });
    // ...and the full-scale line follows the pick at once, from the newly selected device's own
    // calibration - the card section re-derives its combo and range table here, and the readout
    // above them was the one thing left showing the previous device's numbers (see
    // refreshFsReadouts). Display only, like everything else a staged pick does.
    $('#inSel').on('change', () => this.refreshFsReadouts());
    // The OUTPUT device's own rate and width lists follow the device, exactly as the input's do
    // above (Java refreshOutputRatesAndDepths). Missing here, the output combos went on showing
    // the FIRST output device's formats: on a bench whose primary output takes 16 bit only, a
    // line out offering 16/24/32 still showed a lone "16 bits". It costs
    // nothing on a backend with no per-device formats - the lists are then not rebuilt at all.
    $('#outSel').on('change', () => this.refreshOutputRatesAndDepths());
    $('#outSel').on('change', () => { if (this.outputCard) this.outputCard.onDeviceChanged(); });
    $('#outSel').on('change', () => this.refreshFsReadouts());
    this.buildBackendSelector();
    $('#backendSettings').on('click', () => this.openBackendSettings());
    // The Scan click is the app's ONE user activation (see scan()): call it DIRECTLY - never through
    // a setTimeout / requestAnimationFrame hop or behind an await - and tell it so, since that flag
    // is what lets the QA40x branch raise the WebUSB chooser.
    $('#scan').on('click', () => this.scan(true));

    // Rate coupling for a backend with ONE shared clock (the QA40x's reg 9), as a MessageBus
    // round-trip: each combo announces its pick with PREFS_SAMPLE_RATE_CHANGED, the owning
    // subscriber (Qa40xRateConstraint) compares the pair and answers with PREFS_SAMPLE_RATE_SET,
    // and this listener aligns the OTHER combo - which fires no change event, so the trip ends.
    // Java subscribes in open() and unsubscribes on dispose; this dialog is built once and lives as
    // long as the page, so one subscription covers the same span.
    $('#inRate').on('change', () => this.publishRateChange(true));
    $('#outRate').on('change', () => this.publishRateChange(false));
    MessageBus.instance().subscribe(Events.PREFS_SAMPLE_RATE_SET, (set) => {
      if (set == null || this.prefs.backend.get() !== set.backend) return;
      this.selectRateItem(set.input ? '#inRate' : '#outRate', set.sampleRateHz);
    });

    $('#prefsTabs').on('click', '.nav-link', (ev) => this.prefsTab(ev.currentTarget.dataset.prefsPanel));
    $('#prefFrMaxNyq').on('input', () => this.updateFrMaxNyqHz());
    $('#prefOscPersistence').on('change', () => this.gateOscPersistManual());

    $('#prefsModal').on('show.bs.modal', async () => {
      this._staging = true;
      this._okClicked = false;
      this.seedPrefsTabs();
      // Component-owned preference blocks (a backend's own settings) get the same session as the
      // dialog: seed their edit values now, commit them on OK, leave them alone on Cancel - so an
      // edit abandoned by a previous Cancel cannot leak in (Java beginCustomPreferencesEdit).
      this.prefs.beginCustomPreferencesEdit();
      // A backend whose settings live on a BENCH keeps its pending write on its manager rather
      // than in a preference block (nothing of it is persisted here - the bench stores it), so the
      // same session boundary is given to the manager too (Java Qa40xSettingsUi.beginEdit).
      this._settingsEdit('beginCustomPreferencesEdit');
      // The manager that answers hasCustomPreferences() is built lazily, so re-ask on every open.
      this.refreshCustomPrefsButton();
      // Re-derive the rate AND depth combos from the just-reset edit values, BEFORE the snapshot.
      // Both matter here: the options a previous session left on screen belong to that session's
      // edit state, and beginEdit has just thrown it away. Without this, switching the I2S port on,
      // accepting the backend's dialog and then CANCELLING Preferences left the output depth combo
      // still offering the port's 16 / 32 on the next open, while the live setting was correctly
      // off again.
      await this.refreshRates();
      // Snapshot AFTER the re-derive, so the Cancel path restores this session's values rather than
      // the stale ones the combos happened to be showing when the dialog opened.
      this.snapshotAudioPrefs();
      // A new dialog session for the bench card chooser too: Java builds its BenchCards on the
      // working copy this open creates, so a pick left staged by a previous session - one that was
      // cancelled, or one whose OK already sent it - can never be shown or re-sent by this one.
      if (this._cardSource) this._cardSource.clearStaged();
      // Re-derive the per-card combo + range table for the current devices, and the FS readouts.
      // AWAITED: on a bench the list is a wire round trip, and the open-time range baselines below
      // must be taken from the card this session is about to edit, not from the previous one's.
      if (this.inputCard) await this.inputCard.refresh();
      if (this.outputCard) await this.outputCard.refresh();
      this.refreshFsReadouts();
      // Open-time active range per direction - the baseline OK compares against, taken AFTER the
      // card sections have re-resolved so it reflects the same card the user is about to edit.
      this._inRangeBefore = this.activeRangeOf(true);
      this._outRangeBefore = this.activeRangeOf(false);
      this._inRangeSnapshot = this.cardRangeSnapshot(true);
      this._outRangeSnapshot = this.cardRangeSnapshot(false);
    });
    $('#prefsOk').on('click', () => { this._okClicked = true; });
    $('#prefsModal').on('hidden.bs.modal', async () => {
      this._staging = false;
      if (this._okClicked) {
        this._okClicked = false;
        // Write the staged active-range picks FIRST: applyAudioPrefs' publish compares the
        // open-time baseline against the card, so the card has to hold the new value by then.
        if (this.inputCard) this.inputCard.commitStagedActive();
        if (this.outputCard) this.outputCard.commitStagedActive();
        // The card choices staged on the bench go out BEFORE the commit + restart, exactly where
        // Java sends them (PreferencesDialog OK: routeRemote -> commitStagedCardBindings ->
        // applyFromDialog): the binding decides which calibration the reopened streams measure
        // against, so it has to be in force on the server before they come back up.
        await this._commitStagedCardBindings();
        await this.applyAudioPrefs();
        this.applyPrefsTabs();
      } else {
        // Nothing was written, so this only forgets the pick and repaints the radios.
        if (this.inputCard) this.inputCard.discardStagedActive();
        if (this.outputCard) this.outputCard.discardStagedActive();
        this.restoreCardRanges();   // belt and braces for any non-staged write (card editor, etc.)
        await this.restoreAudioPrefs();
      }
    });

    return this;
  }
}
