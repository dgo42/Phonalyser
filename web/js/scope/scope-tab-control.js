/*
 * Phonalyser web — the oscilloscope settings strip (channel / filter / trigger /
 * V&T scale / presets / save / load / ADC-calibrate controls).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/scope/ScopeTabControl. Owns the scope toolbar tabs and
 * their bound controls; reaches the scope PANE (canvas / scrollbars / record /
 * render loop, which stay in app.js) only through the narrow injected `host`
 * object — mirroring Java ScopeTabControl→ScopePane.Host (requestRedraw /
 * applyViewState / stopCaptureForFileLoad / onSignalFileLoaded). The scope V/div
 * + t/div + hysteresis + save-duration NumericStepFields are built in app.js's
 * initStepFields and reached here via the injected getField; the io / format
 * helpers + the shared confirm dialog + OscPreset are injected too.
 */
import { t } from '../i18n/i18n.js';
import { OscPreset } from '../store/preferences.js';
// Direct import (not via the injected `io` seam, which app.js assembles): the
// streaming-FLAC branch of the forward record lives with the codec in io/flac.js
// (Java streams FLAC through StereoPcmIo.openSink → FlacWriter exactly like
// WAV/AIFF; the web streaming WAV/AIFF sinks stay in io/scope-capture.js, whose
// openStreamingSink cannot host libFLAC's self-framing encoder).
import { saveStreamingFlac } from '../io/flac.js';

/** FLAC exports are capped at 24 bits/sample EVERYWHERE in the web port — the
 *  WASM libFLAC reference encoder rejects 32-bit (encodeFlac's guard; Java's
 *  javaFlacEncoder-based FlacWriter accepts 16/24/32). WAV/AIFF keep 32-bit. */
const FLAC_BIT_DEPTH = 24;

/** WAV / AIFF scope exports are full 32-bit PCM (matches the ring's precision). */
const PCM_BIT_DEPTH = 32;

// Adaptive V / mV / µV formatting for the ADC-cal current-reading readout (mirror of the
// dac-calibration-dialog fmtCalVoltage helper / Java AdcCalibrationDialog.formatVoltage).
function fmtCalVoltage(v) {
  const a = Math.abs(v);
  if (a >= 1) return `${v.toFixed(4)} V`;
  if (a >= 1e-3) return `${(v * 1e3).toFixed(3)} mV`;
  if (a >= 1e-6) return `${(v * 1e6).toFixed(2)} µV`;
  return `${v.toPrecision(3)} V`;
}

// Parses the entered value + unit to volts RMS (Java AdcCalibrationDialog.parseAsVrms):
// mV → /1000, dBV → 10^(v/20), V → as-is. Accepts decimal point or comma. NaN on failure.
function parseAsVrms(valueStr, unit) {
  const v = parseFloat(String(valueStr).trim().replace(',', '.'));
  if (!Number.isFinite(v)) return NaN;
  switch (unit) {
    case 'mV':  return v / 1000;
    case 'dBV': return Math.pow(10, v / 20);
    default:    return v;
  }
}

export class ScopeTabControl {
  /**
   * @param engine the AudioEngine (record state via host; ADC-cal reads the live scope view).
   * @param prefs  Preferences.
   * @param deps   {host, view, getField, io, WAV_TYPE, latestScope, showConfirm, setStatus}
   *   - host: the narrow scope-PANE seam (Java ScopeTabControl.Host) —
   *       requestRedraw() (repaint a frozen / file-mode view: app.js refreshScopeFileMode),
   *       refreshTiles() (toolbarTabs.refreshTab: app.js refreshScopeTiles),
   *       refreshFields() (re-sync V/T fields after an auto-setup: app.js refreshScopeFields),
   *       syncTriggerStart() (Start enabled only in Single mode),
   *       setTriggerControlsEnabled(on) (lock the trigger group in file mode),
   *       syncOffsetScrollbar() / redrawVScroll() (vertical nav slider),
   *       renderMeasurementTable() / syncMeasButtons() (measurement-table header),
   *       recState() (=> scopeRec), stopCaptureForFileLoad() (stop live capture before a load),
   *       onSignalFileLoaded(decoded) (centre the view on the loaded signal + show the nav slider).
   *   - view: the ScopeView (Java holds `view` as a field) — single-armed state, auto-setup,
   *       latest measurement, the single-disarmed / settings-changed / file-back callbacks.
   *   - getField: (id) => the scope NumericStepField (built in app.js initStepFields).
   *   - io: {pickSaveTarget, writeToTarget, encodeFlac, saveScopeCapture, findFullPeriodWindow,
   *          formatForName, readWav, readAiff, decodeFlac} — the file save / load collaborators.
   *   - WAV_TYPE: the save-picker accept descriptor (shared with the generator save).
   *   - latestScope: () => the latest live scope frame {buf, info} (Save reads the ring snapshot).
   *   - showConfirm: (title, message) => Promise<boolean> — the shared Bootstrap confirm modal.
   *   - setStatus: (msg) => set the status line.
   */
  constructor(engine, prefs, { host, view, getField, io, WAV_TYPE, latestScope,
    showConfirm, setStatus }) {
    this.engine = engine;
    this.prefs = prefs;
    this.host = host;
    this.view = view;
    this._getField = getField;
    this.io = io;
    this.WAV_TYPE = WAV_TYPE;
    this._latestScope = latestScope;
    this._showConfirm = showConfirm;
    this._setStatus = setStatus;
    // A pre-picked target (Browse) is held here so Save writes straight to it; if the
    // user clicks Save without browsing first, Save opens the picker inline. A request
    // longer than the capture ring records FORWARD to disk in real time
    // (startStreamingSave); a request within the ring dumps it instantly.
    this.pendingScopeSaveTarget = null;   // {name, handle} from pickSaveTarget, or null
  }

  // Channel / Edge / Mode are single-select toggle button groups (Java
  // buildTriggerGroup: makeDependentGroup of squareToggle buttons, NOT dropdowns).
  // trigGroupVal(id) reads the active button's data-value; trigGroupSet(id, v) marks
  // the matching button active (and only it). Each group's buttons fire `onPick`.
  trigGroupVal(id) {
    return $('#' + id + ' .sq-toggle.active').data('value');
  }
  trigGroupSet(id, v) {
    $('#' + id + ' .sq-toggle').each(function () {
      $(this).toggleClass('active', String($(this).data('value')) === String(v));
    });
  }
  wireTrigGroup(id, onPick) {
    $('#' + id + ' .sq-toggle').on('click', (ev) => {
      const v = $(ev.currentTarget).data('value');
      this.trigGroupSet(id, v);
      onPick(v);
    });
  }

  /** Pushes every scope pref into its control widget — the init seed and the
   *  preset-load mirror-back (Java applyOscPreset re-syncs the widgets via the
   *  two-way bindings). Step-field setValue() is guarded since the fields are
   *  built in initStepFields. */
  seedScopeControls() {
    const prefs = this.prefs;
    const getField = (id) => this._getField(id);
    $('#scopeLeftEnable').toggleClass('active', prefs.oscLeftChannelEnabled.get());
    $('#scopeRightEnable').toggleClass('active', prefs.oscRightChannelEnabled.get());
    $('#scopeLeftAc').toggleClass('active', prefs.oscLeftAcMode.get());
    $('#scopeRightAc').toggleClass('active', prefs.oscRightAcMode.get());
    $('#scopeLeftSinc').prop('checked', prefs.oscLeftSincInterpEnabled.get());
    $('#scopeRightSinc').prop('checked', prefs.oscRightSincInterpEnabled.get());
    $('#scopeLeftMains').val(prefs.oscLeftMainsSuppression.get());
    $('#scopeRightMains').val(prefs.oscRightMainsSuppression.get());
    $('#scopeLeftLpf').val(prefs.oscLeftLpf.get());
    $('#scopeRightLpf').val(prefs.oscRightLpf.get());
    this.trigGroupSet('scopeTrigCh', prefs.oscTriggerChannel.get());
    this.trigGroupSet('scopeTrigEdge', prefs.oscTriggerEdge.get());
    // Glitch in AUTO makes no sense — normalize a persisted GLITCH+AUTO combo back to
    // EDGE at seed time (Java buildTriggerGroup build-time normalization), then seed
    // the E/G type group and gate G on the trigger mode.
    if (prefs.oscTriggerMode.get() === 'AUTO' && prefs.oscTriggerType.get() === 'GLITCH') {
      prefs.oscTriggerType.set('EDGE');
    }
    this.trigGroupSet('scopeTrigType', prefs.oscTriggerType.get());
    this.host.syncGlitchTypeEnabled();
    this.trigGroupSet('scopeTrigMode', prefs.oscTriggerMode.get());
    $('#scopeTrigHystEn').prop('checked', prefs.oscTriggerHysteresisEnabled.get());
    $('#scopeTrigBeat').prop('checked', prefs.oscShowReconstructedBeat.get());
    // Save name: the Java save path carries the chosen file (oscSavePath); restore
    // it into the save-name field so the format (its extension) persists across
    // sessions. The loaded (open-signal) path mirrors Java getOscPlayFromPath into
    // its read-only field.
    const savedSavePath = prefs.oscSavePath.get();
    if (savedSavePath) $('#scopeSaveName').val(savedSavePath);
    const savedPlayPath = prefs.oscPlayFromPath.get();
    if (savedPlayPath) $('#scopeLoadedPath').val(savedPlayPath).attr('title', savedPlayPath);
    const fLV = getField('scopeLeftVdiv'); if (fLV) fLV.setValue(prefs.oscLeftVoltsPerDiv.get());
    const fRV = getField('scopeRightVdiv'); if (fRV) fRV.setValue(prefs.oscRightVoltsPerDiv.get());
    const fTd = getField('scopeTdiv'); if (fTd) fTd.setValue(prefs.oscTimePerDiv.get());
    const fTH = getField('scopeTrigHyst'); if (fTH) { fTH.setValue(prefs.oscTriggerHysteresisDiv.get()); fTH.setDisabled(!prefs.oscTriggerHysteresisEnabled.get()); }
    this.host.syncOffsetScrollbar();
    // Initial scrollbar paint once the flex layout has sized the gutter canvas
    // (clientWidth/Height are 0 until then, so a synchronous redraw would no-op).
    requestAnimationFrame(() => { this.host.redrawScrollbars(); });
    this.host.syncMeasChannelButtons();
    this.host.syncMeasButtons();
    // Initial enable-gate for the Reconstructed-beat checkbox: Java buildTriggerGroup
    // sets reconstructedBeatBtn.setEnabled(isGeneratorDualTone()) at build time, so the
    // initial state must match the current generator form (single-tone → greyed).
    this.host.syncReconstructedBeatEnabled();
    this.syncScopeSaveEnabled();   // #2: Save disabled until a target is chosen this session
    this.host.refreshTiles();   // tiles mirror the seeded settings (Java toolbarTabs tiles)
  }

  /** Suggested save name from the path field, defaulting + .wav-suffixing it so
   *  formatForName always resolves to a real container (Java default WAV). */
  scopeSaveSuggestedName() {
    let name = ($('#scopeSaveName').val() || 'scope.wav').trim() || 'scope.wav';
    if (!/\.(wav|flac|aiff|aif)$/i.test(name)) name += '.wav';
    return name;
  }

  /** Captures the most-recent N s of the scope buffer and encodes it into the
   *  format implied by {@code name}'s extension (formatForName). Returns the file
   *  bytes. FLAC routes through the async WASM encoder; WAV/AIFF through the
   *  synchronous scope-capture writer. */
  async encodeScopeSave(name) {
    const info = this._latestScope().info;
    const bitDepth = PCM_BIT_DEPTH;   // WAV/AIFF export 32-bit PCM
    // Both channels straight from the ring snapshot (Java ScopeFileSaver.save reads
    // L + R via readLatest); bound by `available` so the unfilled ring tail isn't
    // written, and take only the most-recent N s (the Duration field).
    const bufL = info.bufL || this._latestScope().buf, bufR = info.bufR || this._latestScope().buf;
    const have = Math.min(info.available != null ? info.available : bufR.length, bufR.length);
    const wantFrames = Math.max(1, Math.round(this.prefs.oscSaveDurationSeconds.get() * info.inRate));
    const n = Math.min(wantFrames, have);
    const start = have - n;
    const left = bufL.subarray(start, start + n);
    const right = bufR.subarray(start, start + n);
    if (this.io.formatForName(name) === 'FLAC') {
      // The WASM libFLAC reference encoder supports only up to 24 bits/sample
      // (init returns INVALID_BITS_PER_SAMPLE and writes NOTHING at 32-bit — the
      // 0-byte-file bug). WAV/AIFF keep the full 32-bit export above; FLAC caps at
      // 24-bit. (Java's javaFlacEncoder accepts 32-bit; the browser codec can't.)
      const flacBits = FLAC_BIT_DEPTH;
      const w = this.io.findFullPeriodWindow(left, n, info.inRate, info.snapped || 0);
      return this.io.encodeFlac([left.subarray(w.start, w.start + w.length),
                         right.subarray(w.start, w.start + w.length)], info.inRate, flacBits);
    }
    return this.io.saveScopeCapture(left, right, n, name, info.inRate, bitDepth, info.snapped || 0);
  }

  /**
   * Records {@code totalFrames} of LIVE capture straight to {@code target} in real
   * time (for captures longer than the ring buffer), showing a FLOATING non-modal
   * progress window with a Cancel — the user keeps watching the scope / FFT while
   * it records. Acquires its own capture reference for the duration (opening the
   * device if the scope isn't already recording), streams via
   * StereoPcmIo.saveStreaming (WAV / AIFF) or the flac.js streaming encoder
   * (.flac — Java routes all three through StereoPcmIo.openSink, ~line 71), and
   * releases when done / cancelled / errored.
   * Faithful port of ScopeTabControl.startStreamingSave (~line 1217).
   *
   * @param {{name:string, handle:?FileSystemFileHandle}} target  Picked save target.
   * @param {number} sampleRate
   * @param {number} bitDepth
   * @param {number} totalFrames
   */
  async startStreamingSave(target, sampleRate, bitDepth, totalFrames) {
    const setStatus = this._setStatus;
    // Streaming writes chunk-by-chunk to a FileSystemWritableFileStream; without
    // the FS Access handle (anchor-download fallback) there is no incremental disk
    // sink, so this path needs a real picked file handle.
    if (!target.handle) {
      setStatus(t('scope.save.error') + ': '
        + 'streaming record needs the File System Access API (a real save file).');
      return;
    }
    // Acquire a forward-read capture cursor, opening the device if the scope isn't
    // already recording (Java MessageBus.request(CAPTURE_ACQUIRE)).
    const reader = await this.engine.acquireCaptureReader();
    if (!reader) {
      setStatus(t('scope.save.error') + ': '
        + t('scope.save.error.message', target.name, this.engine.getLastStartError() || ''));
      return;
    }
    // The device may have opened (or re-pinned) at a rate other than the caller's
    // estimate; the ring's actual rate is authoritative for the file header.
    const ringRate = reader.getSampleRate() || sampleRate;

    const totalSeconds = totalFrames / ringRate;
    const win = $('#scopeRecWindow');
    const lbl = $('#scopeRecProgress');
    const bar = $('#scopeRecBar')[0];
    const cancelBtn = $('#scopeRecCancel');
    let cancelled = false;
    lbl.text(t('scope.save.recording.progress', '0.0', totalSeconds.toFixed(1)));
    if (bar) { bar.max = 1000; bar.value = 0; }
    const onCancel = () => { cancelled = true; };
    cancelBtn.off('click').on('click', onCancel);
    win.addClass('open');

    let err = null;
    let writable = null;
    try {
      writable = await target.handle.createWritable();
      const onProgress = (written) => {
        if (bar) bar.value = Math.min(1000, Math.round(1000 * written / totalFrames));
        lbl.text(t('scope.save.recording.progress',
          (written / ringRate).toFixed(1), totalSeconds.toFixed(1)));
      };
      // .flac routes to the libFLAC streaming encoder (scope-capture's
      // openStreamingSink has no FLAC sink and would throw — the old
      // "progress window closes instantly, no file" bug); WAV / AIFF keep
      // the header-patching streaming writers.
      if (this.io.formatForName(target.name) === 'FLAC') {
        await saveStreamingFlac(reader, writable, ringRate, bitDepth, totalFrames,
          () => cancelled, onProgress);
      } else {
        await this.io.saveStreaming(reader, writable, ringRate, bitDepth, totalFrames,
          target.name, () => cancelled, onProgress);
      }
    } catch (e) {
      err = e;
      try { if (writable) await writable.close(); } catch (_) { /* already closed / aborted */ }
    } finally {
      await this.engine.releaseCaptureReader();
      win.removeClass('open');
      cancelBtn.off('click', onCancel);
    }
    if (err) setStatus(t('scope.save.error') + ': '
      + t('scope.save.error.message', target.name, err.message));
    else setStatus('saved ' + target.name);
  }

  // ----- Scope Presets (Java ScopeTabControl PresetBar<OscPreset>) -----
  // Capture / apply the full OscPreset field set to the persisted prefs.oscPresets
  // store. Save overwrites by name; Load applies; Delete removes.
  captureOscPreset() {
    const prefs = this.prefs;
    const p = new OscPreset();
    p.leftChannelEnabled = prefs.oscLeftChannelEnabled.get();
    p.rightChannelEnabled = prefs.oscRightChannelEnabled.get();
    p.leftAcMode = prefs.oscLeftAcMode.get();
    p.rightAcMode = prefs.oscRightAcMode.get();
    p.leftSincInterpEnabled = prefs.oscLeftSincInterpEnabled.get();
    p.rightSincInterpEnabled = prefs.oscRightSincInterpEnabled.get();
    p.leftMainsSuppression = prefs.oscLeftMainsSuppression.get();
    p.rightMainsSuppression = prefs.oscRightMainsSuppression.get();
    p.leftLpf = prefs.oscLeftLpf.get();
    p.rightLpf = prefs.oscRightLpf.get();
    p.leftVoltsPerDiv = prefs.oscLeftVoltsPerDiv.get();
    p.rightVoltsPerDiv = prefs.oscRightVoltsPerDiv.get();
    p.leftOffsetFrac = prefs.oscLeftOffsetFrac.get();
    p.rightOffsetFrac = prefs.oscRightOffsetFrac.get();
    p.timePerDiv = prefs.oscTimePerDiv.get();
    p.triggerPositionFrac = prefs.oscTriggerPositionFrac.get();
    p.triggerChannel = prefs.oscTriggerChannel.get();
    p.triggerEdge = prefs.oscTriggerEdge.get();
    p.triggerType = prefs.oscTriggerType.get();
    p.triggerMode = prefs.oscTriggerMode.get();
    p.triggerLevelFrac = prefs.oscTriggerLevelFrac.get();
    return p;
  }
  applyOscPreset(p) {
    const prefs = this.prefs;
    // Java applyOscPreset order is load-bearing: the V/T scale selectors write
    // VoltsPerDiv/TimePerDiv AND clobber offsetFrac (preserveCanvasMiddle), so they
    // run FIRST; the offset / trigger-position / level fractions are re-written LAST
    // so the preset's values win.
    prefs.oscLeftVoltsPerDiv.set(p.leftVoltsPerDiv);
    prefs.oscRightVoltsPerDiv.set(p.rightVoltsPerDiv);
    prefs.oscTimePerDiv.set(p.timePerDiv);
    prefs.oscLeftChannelEnabled.set(p.leftChannelEnabled);
    prefs.oscRightChannelEnabled.set(p.rightChannelEnabled);
    prefs.oscLeftAcMode.set(p.leftAcMode);
    prefs.oscRightAcMode.set(p.rightAcMode);
    prefs.oscLeftSincInterpEnabled.set(p.leftSincInterpEnabled);
    prefs.oscRightSincInterpEnabled.set(p.rightSincInterpEnabled);
    prefs.oscLeftMainsSuppression.set(p.leftMainsSuppression);
    prefs.oscRightMainsSuppression.set(p.rightMainsSuppression);
    prefs.oscLeftLpf.set(p.leftLpf);
    prefs.oscRightLpf.set(p.rightLpf);
    prefs.oscTriggerChannel.set(p.triggerChannel);
    prefs.oscTriggerEdge.set(p.triggerEdge);
    prefs.oscTriggerType.set(p.triggerType || 'EDGE');
    prefs.oscTriggerMode.set(p.triggerMode);
    this.host.syncTriggerStart();
    // Fractions — overwrite the values the scale listeners would have clobbered.
    // Goes last so the preset wins (Java applyOscPreset).
    prefs.oscLeftOffsetFrac.set(p.leftOffsetFrac);
    prefs.oscRightOffsetFrac.set(p.rightOffsetFrac);
    prefs.oscTriggerPositionFrac.set(p.triggerPositionFrac);
    prefs.oscTriggerLevelFrac.set(p.triggerLevelFrac);
    prefs.save();
    // Mirror everything back into the controls.
    this.seedScopeControls();
  }
  refreshOscPresetList() {
    // Populate the input-group dropdown (Bootstrap "buttons with dropdowns"): one
    // menu item per saved preset name. The toggle button is disabled when empty.
    const $menu = $('#scopePresetMenu').empty();
    const names = [...this.prefs.oscPresets.keys()];
    for (const name of names) {
      $('<li>').append($('<button type="button" class="dropdown-item">').text(name)).appendTo($menu);
    }
    $('#scopePresetMenuBtn').prop('disabled', names.length === 0);
    this.refreshOscPresetButtons();
    this.host.refreshTiles();   // "N saved" Presets tile follows the preset count (Java refreshTab)
  }

  /** Save / Load / Delete enablement (faithful port of PresetBar.refreshButtons):
   *  empty name → all disabled; a NEW name → Save only; an EXISTING name → Load /
   *  Delete always, and Save only when the current settings differ from the saved
   *  snapshot. JSON equality stands in for Java's OscPreset.equals (plain POJO of
   *  primitives + strings). Called on name input, dropdown pick, and after
   *  save/delete. */
  refreshOscPresetButtons() {
    const name = ($('#scopePresetName').val() || '').trim();
    const $save = $('#scopePresetSave'), $load = $('#scopePresetLoad'), $delete = $('#scopePresetDelete');
    if (!name) {
      $save.prop('disabled', true); $load.prop('disabled', true); $delete.prop('disabled', true);
      return;
    }
    const existing = this.prefs.oscPresets.get(name);
    if (!existing) {
      $save.prop('disabled', false);   // new name — Save creates it
      $load.prop('disabled', true); $delete.prop('disabled', true);
    } else {
      // Existing — Save only if the current settings differ; Load / Delete always.
      const differs = JSON.stringify(existing) !== JSON.stringify(this.captureOscPreset());
      $save.prop('disabled', !differs);
      $load.prop('disabled', false); $delete.prop('disabled', false);
    }
  }

  /** Save is enabled only once a target has been chosen this session (#2): the
   *  two-button flow means Save writes to the pre-picked target silently and never
   *  re-opens the picker, so with no live file handle there is nowhere to write. */
  syncScopeSaveEnabled() {
    $('#scopeSaveGo').prop('disabled', !this.pendingScopeSaveTarget);
  }

  /** Calibrate is enabled only with a live capture and a signal ≥ 25 % of full
   *  scale p-p (Java setCalibrateEnabled gate). */
  syncCalibrateEnabled() {
    const m = this.view.latest;
    const fsPp = this.prefs.adcFsVoltageRms.get() * Math.SQRT2 * 2;
    const ok = this.host.recState() && m && m.vpp > 0 && fsPp > 0 && m.vpp >= 0.25 * fsPp;
    $('#scopeCalibrate').prop('disabled', !ok);
  }

  bind() {
    const prefs = this.prefs;
    const host = this.host;
    const view = this.view;
    const io = this.io;
    const getField = (id) => this._getField(id);
    const setStatus = (m) => this._setStatus(m);

    // Scope tile-tabs — Left/Right/Horizontal/Trigger own their control panels.
    $('#scopeTabs .tab').on('click', function () {
      const panel = $(this).data('panel');
      const wasOpen = $(this).hasClass('active') && panel && $('#' + panel).hasClass('show');
      $('#scopeTabs .tab').removeClass('active'); $(this).addClass('active');
      $('#scopeLeft, #scopeRight, #scopeHoriz, #scopeTrig, #scopePresets, #scopeUtility, #scopeSavePanel, #scopeLoadPanel').removeClass('show');
      if (panel && !wasOpen) $('#' + panel).addClass('show');
    });

    // Scope control panels (Java ScopeTabControl) → the osc* prefs the scope view reads.
    // Channel-enable + AC coupling are square toggle BUTTONS (Java squareToggle):
    // click flips the .active class and writes the bound pref.
    $('#scopeLeftEnable').on('click', function () { const on = !$(this).hasClass('active'); $(this).toggleClass('active', on); prefs.oscLeftChannelEnabled.set(on); host.refreshTiles(); host.requestRedraw(); });
    $('#scopeRightEnable').on('click', function () { const on = !$(this).hasClass('active'); $(this).toggleClass('active', on); prefs.oscRightChannelEnabled.set(on); host.refreshTiles(); host.requestRedraw(); });
    $('#scopeLeftAc').on('click', function () { const on = !$(this).hasClass('active'); $(this).toggleClass('active', on); prefs.oscLeftAcMode.set(on); host.refreshTiles(); host.requestRedraw(); });
    $('#scopeRightAc').on('click', function () { const on = !$(this).hasClass('active'); $(this).toggleClass('active', on); prefs.oscRightAcMode.set(on); host.refreshTiles(); host.requestRedraw(); });
    $('#scopeLeftSinc').on('change', () => { prefs.oscLeftSincInterpEnabled.set($('#scopeLeftSinc').is(':checked')); host.refreshTiles(); host.requestRedraw(); });
    $('#scopeRightSinc').on('change', () => { prefs.oscRightSincInterpEnabled.set($('#scopeRightSinc').is(':checked')); host.refreshTiles(); host.requestRedraw(); });
    // Per-channel mains-suppression + LPF combos (Java oscLeft/RightMainsSuppression /
    // oscLeft/RightLpf) — the scope view applies them to the active channel's buffer
    // before the trigger search + trace.
    $('#scopeLeftMains').on('change', () => { prefs.oscLeftMainsSuppression.set($('#scopeLeftMains').val()); host.requestRedraw(); });
    $('#scopeRightMains').on('change', () => { prefs.oscRightMainsSuppression.set($('#scopeRightMains').val()); host.requestRedraw(); });
    $('#scopeLeftLpf').on('change', () => { prefs.oscLeftLpf.set($('#scopeLeftLpf').val()); host.requestRedraw(); });
    $('#scopeRightLpf').on('change', () => { prefs.oscRightLpf.set($('#scopeRightLpf').val()); host.requestRedraw(); });

    // Channel / edge / type changes drop the held trigger anchor (Java onChange
    // listeners → view.resetTriggerHold + controller.redrawViews): the old anchor
    // belongs to the OLD trigger source and would keep re-rendering a stale trace.
    // NORMAL / SINGLE then stay blank until the new trigger fires.
    this.wireTrigGroup('scopeTrigCh', (v) => {
      prefs.oscTriggerChannel.set(v); host.refreshTiles();
      view.resetTriggerHold();   // other channel's anchor is stale
      host.requestRedraw();
    });
    this.wireTrigGroup('scopeTrigEdge', (v) => {
      prefs.oscTriggerEdge.set(v); host.refreshTiles();
      view.resetTriggerHold();   // old-edge anchor is stale
      host.requestRedraw();
    });
    // Trigger event type: E = level-crossing edge trigger, G = dV/dt glitch trigger
    // (dropped-sample DAC gaps). The ↑/↓ edge selection applies to both — crossing
    // direction vs. glitch start/end anchor.
    this.wireTrigGroup('scopeTrigType', (v) => {
      prefs.oscTriggerType.set(v); host.refreshTiles();
      view.resetTriggerHold();   // the held anchor belongs to the OLD trigger type
      host.requestRedraw();
    });
    this.wireTrigGroup('scopeTrigMode', (v) => {
      prefs.oscTriggerMode.set(v);
      // Glitch in AUTO makes no sense — free-run repaints at the render rate, so a
      // caught glitch frame would be overwritten immediately. Selecting AUTO flips
      // the type back to EDGE, and G stays disabled until NORMAL / SINGLE (Java
      // oscTriggerModeProperty listener).
      if (v === 'AUTO' && prefs.oscTriggerType.get() === 'GLITCH') {
        prefs.oscTriggerType.set('EDGE');
        this.trigGroupSet('scopeTrigType', 'EDGE');
        view.resetTriggerHold();   // the glitch anchor is stale under the new type
      }
      host.syncGlitchTypeEnabled();
      host.syncTriggerStart(); host.refreshTiles();
      host.requestRedraw();
    });
    // Trigger level (fraction of canvas height, Java oscTriggerLevelFrac) +
    // hysteresis enable/div (oscTriggerHysteresisEnabled / oscTriggerHysteresisDiv).
    $('#scopeTrigHystEn').on('change', () => {
      const on = $('#scopeTrigHystEn').is(':checked');
      prefs.oscTriggerHysteresisEnabled.set(on);
      const fTH = getField('scopeTrigHyst');
      if (fTH) fTH.setDisabled(!on);
      host.refreshTiles();
    });
    // Reconstructed-beat overlay (oscShowReconstructedBeat) — gated to dual-tone form.
    // Java binds oscShowReconstructedBeat with onChange→requestRedraw so the overlay toggles
    // immediately in file / frozen mode (the live render loop is idle then).
    $('#scopeTrigBeat').on('change', () => { prefs.oscShowReconstructedBeat.set($('#scopeTrigBeat').is(':checked')); host.requestRedraw(); });
    // SINGLE-mode Start button: arms one shot (the scope view freezes on the next
    // trigger and pops this back out via onSingleDisarmed). Toggle to cancel.
    $('#scopeTrigStart').on('click', function () {
      const arm = !view.isSingleArmed();
      $(this).toggleClass('active armed', arm);
      view.setSingleArmed(arm);
    });
    view.onSingleDisarmed = () => $('#scopeTrigStart').removeClass('active armed');
    // Canvas wheel-zoom (V/div, t/div) and pan changed osc* prefs directly — re-sync the
    // numeric fields and the tab tiles that mirror them.
    view.onSettingsChanged = () => { host.refreshFields(); host.refreshTiles(); host.requestRedraw(); };
    // File-mode horizontal navigation from the canvas wheel (Shift+wheel pan, Ctrl+Shift
    // t/div zoom-around-cursor): the scope view computes a target back-offset against the
    // loaded buffer; clamp it to the valid range and re-render through the nav scrollbar
    // (Java ScopePane.stepHorizontalOffset / applyViewState).
    view.onFileBack = (back) => host.onFileBack(back);

    // Scope view header button (Java SCOPE_AUTO_SETUP) — range V/div + t/div + centre to the signal.
    // refreshScopeFileMode() repaints in file mode (no live loop), so the fit shows on a loaded signal.
    $('#scopeAutoSetup').on('click', () => { view.autoSetup(); host.refreshFields(); host.refreshTiles(); host.requestRedraw(); });

    // Save name: persist the typed file name (extension included → format) into
    // oscSavePath (Java openScopeSaveBrowse → setOscSavePath persists the full save
    // path, extension included).
    $('#scopeSaveName').on('change', () => {
      prefs.oscSavePath.set($('#scopeSaveName').val() || 'scope.wav');
      prefs.save();
    });

    // ----- Scope "Save to…" / "Load signal…" -----
    // Faithful to Java ScopeTabControl.buildScopeSaveToGroup: a read-only path field
    // + Browse (Save-As picker: "Choose target") + duration + Save. TWO separate
    // buttons (#2): Browse ONLY picks + stores the destination (openScopeSaveBrowse);
    // Save writes to the already-chosen target WITHOUT re-prompting (doScopeSave reads
    // pathField.getText() and never re-opens the picker). Save stays disabled until a
    // target has been chosen this session — the browser needs a live file HANDLE to
    // write silently (a persisted path string alone can't be written without a picker).
    // The chosen file's EXTENSION derives the format (WAV / FLAC / AIFF) via
    // formatForName — no format dropdown.

    // Browse = "Choose target": pick the destination file up-front so its real
    // extension drives the format (Java openScopeSaveBrowse → FileDialog SWT.SAVE).
    // Mirror the chosen name back into the path field + oscSavePath, HOLD the target
    // for the Save click, and enable Save. Does NOT write anything.
    $('#scopeSaveBrowse').on('click', async () => {
      try {
        const target = await io.pickSaveTarget(this.scopeSaveSuggestedName(), this.WAV_TYPE);
        if (!target) return;   // user cancelled
        this.pendingScopeSaveTarget = target;
        $('#scopeSaveName').val(target.name);
        prefs.oscSavePath.set(target.name); prefs.save();
        this.syncScopeSaveEnabled();
      } catch (e) {
        // Surface ANY picker failure (e.g. a TypeError from a rejected
        // types/accept descriptor, which aborts showSaveFilePicker as the dialog
        // opens) on the status line instead of letting it vanish.
        setStatus(t('scope.save.error') + ': ' + ((e && e.message) || String(e)));
      }
    });

    $('#scopeSaveGo').on('click', async () => {
      if (!this._latestScope()) return;
      try {
        // Save writes to the ALREADY-CHOSEN target silently — it does NOT re-prompt
        // (mirror of Java doScopeSave: reads pathField.getText(); if empty shows the
        // "pick first" info and returns, never opening the picker). If no target was
        // chosen this session, surface the message and stop.
        const target = this.pendingScopeSaveTarget;
        if (!target) { setStatus(t('scope.save.pickFirst')); return; }
        $('#scopeSaveName').val(target.name);
        prefs.oscSavePath.set(target.name); prefs.save();
        // Not a loaded file AND (the scope isn't recording — so there's no ring to
        // dump — OR the request is longer than the ring can hold) ⇒ record FORWARD
        // to disk in real time (streaming). Otherwise dump the most-recent N seconds
        // instantly (ScopeFileSaver). Faithful to doScopeSave (~line 1188):
        //   long requestedFrames = Math.max(1L, Math.round(durationSeconds * sampleRate));
        //   if (!view.isFileMode() && (reader == null || requestedFrames > reader.getCapacity())) {
        //       startStreamingSave(path, sampleRate, bitDepth, requestedFrames);
        //       return;
        //   }
        // Java `reader == null` (not recording) maps to !host.recState() here;
        // reader.getCapacity() maps to engine.getCaptureCapacity().
        const info = this._latestScope().info;
        const sampleRate = info.inRate;
        // Bit depth by target format: FLAC saves route at 24-bit EVERYWHERE (the
        // WASM encoder rejects 32-bit — handing 32 to the streaming path used to
        // kill the save); WAV/AIFF keep the full 32-bit export (encodeScopeSave).
        const bitDepth = io.formatForName(target.name) === 'FLAC'
          ? FLAC_BIT_DEPTH : PCM_BIT_DEPTH;
        const requestedFrames = Math.max(1,
          Math.round(this.prefs.oscSaveDurationSeconds.get() * sampleRate));
        const capacity = this.engine.getCaptureCapacity();
        if (!this.view.fileMode && (!this.host.recState() || requestedFrames > capacity)) {
          await this.startStreamingSave(target, sampleRate, bitDepth, requestedFrames);
          return;
        }
        // Instant dump of the most-recent N seconds from the capture ring (clamped to
        // the ring length) — ScopeFileSaver.save.
        const bytes = await this.encodeScopeSave(target.name);
        const res = await io.writeToTarget(target, bytes, 'audio/wav');
        if (res.saved) setStatus('saved ' + res.name);
      } catch (e) { setStatus(t('scope.save.error') + ': ' + ((e && e.message) || String(e))); }
    });

    // #scopeLoadGo is now a Bootstrap custom-file <input type=file> (item 3); load on
    // its change event and read the chosen File into the {name, bytes} shape the
    // decode path below expects. The visible file label echoes the chosen name.
    $('#scopeLoadGo').on('change', async (ev) => {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      const f = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
      // Open-signal and Record share the scope buffer: stop live capture before swapping
      // the buffer out (Java host.stopCaptureForFileLoad), then enter file mode.
      await host.stopCaptureForFileLoad();
      try {
        const lower = f.name.toLowerCase();
        // Each reader hands back per-channel SIGNED integer PCM (StereoPcmIo
        // .decodeStereo convention); normalise to float [-1,+1] by 2^(bits-1).
        let dec;
        if (lower.endsWith('.flac')) { dec = await io.decodeFlac(f.bytes); }
        else if (/\.aiff?$/.test(lower)) { dec = io.readAiff(f.bytes); }
        else { dec = io.readWav(f.bytes); }

        const frames = dec.frameCount != null ? dec.frameCount : dec.ch0.length;
        const mid = Math.pow(2, dec.bitsPerSample - 1);
        const left = new Float32Array(frames), right = new Float32Array(frames);
        for (let i = 0; i < frames; i++) {
          left[i] = dec.ch0[i] / mid;
          right[i] = dec.ch1[i] / mid;
        }
        // File mode: a static loaded signal free-runs with no trigger search / overlay.
        view.fileMode = true;
        // A loaded file has no trigger; SINGLE (unarmed) would render nothing, so
        // switch to AUTO on load — file mode ignores the trigger anyway (Java
        // ScopeOpenSignal.loadFile → setOscTriggerMode(AUTO)). AUTO forces the
        // trigger type back to EDGE (the mode-listener rule).
        prefs.oscTriggerMode.set('AUTO');
        if (prefs.oscTriggerType.get() === 'GLITCH') prefs.oscTriggerType.set('EDGE');
        this.trigGroupSet('scopeTrigMode', 'AUTO');
        this.trigGroupSet('scopeTrigType', prefs.oscTriggerType.get());
        host.syncTriggerStart();
        host.refreshTiles();
        host.setTriggerControlsEnabled(false);
        prefs.oscPlayFromPath.set(f.name); prefs.save();   // Java doOpenSignalBrowse persists the load path
        $('#scopeLoadedPath').val(f.name).attr('title', f.name);   // #15 readonly last-loaded-file field
        // A signal file finished loading → centre the view on its start, show the nav
        // slider (file mode) and apply the view state — pane state (Java host.onSignalFileLoaded).
        host.onSignalFileLoaded({ left, right, frames, sampleRate: dec.sampleRate, name: f.name });
        setStatus(`loaded ${f.name} — ${frames} frames @ ${dec.sampleRate} Hz`);
      } catch (e) { setStatus(t('scope.openSignal.error') + ': ' + e.message); }
    });

    // ----- Scope Presets (Java ScopeTabControl PresetBar<OscPreset>) -----
    // Re-evaluate Save enablement as the name is typed (Java combo SWT.Modify).
    $('#scopePresetName').on('input', () => this.refreshOscPresetButtons());
    // Picking a saved preset from the dropdown fills the name field so Load / Delete
    // act on it (Java PresetBar editable combo: selecting a name populates the combo).
    $('#scopePresetMenu').on('click', '.dropdown-item', (ev) => {
      $('#scopePresetName').val($(ev.currentTarget).text());
      this.refreshOscPresetButtons();
    });
    $('#scopePresetSave').on('click', async () => {
      const name = ($('#scopePresetName').val() || '').trim();
      if (!name) return;
      // Confirm before overwriting an existing preset (Java PresetBar.onSave → Dialogs.confirm
      // with the scope.presets.overwrite.title / .message keys); Cancel aborts.
      if (prefs.oscPresets.has(name)
          && !await this._showConfirm(t('scope.presets.overwrite.title'), t('scope.presets.overwrite.message', name))) return;
      prefs.putOscPreset(name, this.captureOscPreset());
      this.refreshOscPresetList();
    });
    $('#scopePresetLoad').on('click', () => {
      const name = ($('#scopePresetName').val() || '').trim();
      const p = prefs.oscPresets.get(name);
      if (!p) return;
      this.applyOscPreset(p);
      this.refreshOscPresetButtons();
    });
    $('#scopePresetDelete').on('click', async () => {
      const name = ($('#scopePresetName').val() || '').trim();
      if (!prefs.oscPresets.has(name)) return;
      // Confirm before deleting (Java PresetBar.onDelete → Dialogs.confirm with the
      // scope.presets.delete.title / .message keys); Cancel aborts. Uses the shared
      // Bootstrap confirm modal, not the native confirm().
      const ok = await this._showConfirm(t('scope.presets.delete.title'), t('scope.presets.delete.message', name));
      if (!ok) return;
      prefs.removeOscPreset(name);
      this.refreshOscPresetList();
    });

    // ----- Scope Utility: ADC calibrate (Java AdcCalibrationDialog) -----
    // Opens a small Bootstrap modal (mirroring DacCalibrationDialog) seeded with the live
    // measured Vrms; OK rescales adcFsVoltageRms so the entered ACTUAL Vrms becomes the reading.
    // The modal is created lazily (the shared modal instances are built later in init).
    $('#scopeCalibrate').on('click', () => {
      const m = view.latest;
      // Gated on a live signal (Java AdcCalibrationDialog only opens with a measured Vrms).
      if (!m || !(m.vrms > 0)) { setStatus(t('calibrate.error.noVrms')); return; }
      $('#adcCalCurrent').text(t('calibrate.current', fmtCalVoltage(m.vrms)));
      $('#adcCalValue').val(m.vrms.toFixed(6));   // seed with the current reading
      $('#adcCalError').addClass('d-none');
      window.bootstrap.Modal.getOrCreateInstance(document.getElementById('adcCalModal')).show();
    });
    $('#adcCalOk').on('click', () => {
      const m = view.latest;
      // Read the V / mV / dBV unit and convert to Vrms (Java AdcCalibrationDialog.parseAsVrms).
      const actual = parseAsVrms($('#adcCalValue').val(), $('#adcCalUnit').val());
      // Java pops an error dialog and keeps the dialog open on a non-positive / unparsable value;
      // surface it inline instead of silently hiding (mirror of DacCalibrationDialog).
      if (!m || !(m.vrms > 0) || !(actual > 0) || !Number.isFinite(actual)) {
        $('#adcCalError').removeClass('d-none');
        return;
      }
      prefs.setAdcFsVoltageRms(prefs.adcFsVoltageRms.get() * (actual / m.vrms));
      prefs.save();
      $('#adcFsVrms').val(prefs.adcFsVoltageRms.get().toFixed(6));
      setStatus(`ADC calibrated: full-scale = ${prefs.adcFsVoltageRms.get().toFixed(4)} V RMS`);
      window.bootstrap.Modal.getOrCreateInstance(document.getElementById('adcCalModal')).hide();
    });

    return this;
  }
}
