/*
 * Phonalyser web - DAC predistortion wizard (the live UI half).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors gui/fft/predistortion/PredistortionWizardDialog. The DSP/convergence engine
 * (PredistortionEngine) and the engine↔host bridge (PredistortionHost) are separate modules; this
 * owns ONLY the dialog: the 100 ms live poll, the log-scale convergence chart, the metric panel,
 * and the Start / Stop / Stop-round / Save->Apply handlers. Collaborators come in via the
 * constructor - the AudioEngine, Preferences, the already-built PredistortionHost, the bootstrap
 * modal, a getResult() view of the latest FftResult, the io saveFile, and an onApply callback that
 * performs the generator-side waveform switch (a generator-pane concern, injected for now).
 */
import { t } from '../i18n/i18n.js';
import { PredistortionEngine, Phase, StopReason } from '../predistortion/engine.js';
import { GenSignalForm, isDualTone } from '../generator/dds-kernel.js';
import { writeHarmonicDpd, writeIntermodDpd } from '../io/dpd.js';

const DPD_TYPE = [{ description: 'DAC predistortion', accept: 'text/plain', extensions: ['.dpd'] }];

// FftOverlap enum token -> display label (mirrors enums/FftOverlap .label).
const OVERLAP_LABEL = { PCT_0: '0%', PCT_50: '50%', PCT_75: '75%', PCT_87_5: '87.5%', PCT_93_75: '93.75%' };

export class PredistortionWizard {
  /**
   * @param engine     the AudioEngine (generator + FFT pipeline)
   * @param prefs      Preferences
   * @param host       the built PredistortionHost (engine↔convergence bridge)
   * @param deps       { modal, getResult, saveFile, onApply }
   *                   - modal: the bootstrap Modal instance for #predistModal
   *                   - getResult: () => the latest FftResult (or null)
   *                   - saveFile: io saveFile(text, name, types) => {saved,name,viaDownload}
   *                   - onApply: (applyForm, savedName) => void - switch the generator to the
   *                     compensated waveform + persist the .dpd path (generator-pane concern)
   */
  constructor(engine, prefs, host, { modal, getResult, saveFile, onApply }) {
    this.engine = engine;
    this.prefs = prefs;
    this.host = host;
    this.modal = modal;
    this.getResult = getResult;
    this.saveFile = saveFile;
    this.onApply = onApply;
    this.poll = null;
    this.history = { dist: [], avg: [] };
    this.state = { round: 0, averages: 0, dual: false };
    this.pdEngine = null;        // the running PredistortionEngine
    this.running = false;        // Java `running` flag - toggles #dpdStart Start<->Stop
    this.applied = false;        // true once the .dpd has been applied to the generator
    this.savedName = null;       // name of the just-saved .dpd; non-null => #dpdSave is in Apply mode
  }

  // ---- Wizard live UI: 100 ms polling + log convergence chart + metric panel ----

  _stopPoll() { if (this.poll) { clearTimeout(this.poll); this.poll = null; } }

  // The single persistent 100 ms poll, armed at open (Java armTimer => tick => refresh,
  // PredistortionWizardDialog:165/290-300). It mirrors refresh() in full: the live FFT
  // info + metrics are refreshed off the running getResult() even while idle, the chart
  // is redrawn, and the phase headline tracks the engine once a run is underway.
  // SELF-RESCHEDULING like Java's tick() (:296-300 timerExec(TIMER_MS, this::tick)
  // arms the NEXT tick only AFTER refresh() completes) so an overrunning refresh
  // stretches the cadence instead of queueing. setInterval kept firing on schedule
  // and stacked overrunning refreshes back-to-back at large FFT sizes, saturating
  // the main thread, which left the UI unresponsive during a predistortion run.
  _startPoll() {
    this._stopPoll();
    const tick = () => {
      this._renderPhase(); this._updateMetrics(); this._updateFftInfo(); this._drawChart();
      this.poll = setTimeout(tick, 100);
    };
    this.poll = setTimeout(tick, 100);
  }

  _resetLive(dual) {
    $('#dpdWarn').remove();
    this.history.dist.length = 0; this.history.avg.length = 0;
    this.state.round = 0; this.state.averages = 0; this.state.dual = dual;
    this._updateFftInfo(); this._drawChart(); this._updateMetrics();
  }

  _updateFftInfo() {
    // Mirror Java refresh() (PredistortionWizardDialog:307-312): size / rate / window
    // on 3 separate lines, with the bin width via fmtHz (%.4f).  Prefer the live
    // result's fftSize / sampleRate / freqResolution, falling back to prefs.
    const r = this.getResult();
    const size = (r && r.fftSize) || this.prefs.fftLength.get();
    const rate = (r && r.sampleRate) || this.engine.outSampleRate || this.engine.config.outRate || 0;
    const binHz = (r && Number.isFinite(r.freqResolution)) ? r.freqResolution : (rate && size ? rate / size : 0);
    $('#dpdFftInfo').html(
      t('predistortion.fft.size', size, binHz.toFixed(4)) + '<br>'
      + t('predistortion.fft.rate', rate) + '<br>'
      + t('predistortion.fft.window', this.prefs.fftWindow.get()));
  }

  // Live "what's going on" headline + Stop-round gating, polled every 100 ms.
  // Mirrors Java statusText() (PredistortionWizardDialog:352-366) and the
  // stopRoundBtn.setEnabled gate (line 317): the #dpdProgress headline tracks the
  // engine phase (ALIGNING / COLLECTING countdown / APPLYING / SETTLING), and
  // Stop-round is enabled ONLY while a round is averaging (Phase.COLLECTING). The
  // terminal headline (FINISHED / IDLE) is owned by onFinished, so this leaves it
  // untouched once the loop ends.
  _renderPhase() {
    const e = this.pdEngine;
    const phase = e ? e.phase : Phase.IDLE;
    $('#dpdStopRound').prop('disabled', !(e && phase === Phase.COLLECTING));
    const round = (e ? e.currentRound : 0) + 1;
    switch (phase) {
      case Phase.ALIGNING:
        $('#dpdProgress').text(t('predistortion.phase.aligning'));
        break;
      case Phase.COLLECTING:
        $('#dpdProgress').text(t('predistortion.phase.collecting', round, e.getCollectRemainingAverages()));
        break;
      case Phase.APPLYING:
        $('#dpdProgress').text(t('predistortion.phase.applying', round));
        break;
      case Phase.SETTLING:
        $('#dpdProgress').text(t('predistortion.phase.settling'));
        break;
      default:
        break;   // IDLE / FINISHED: headline owned by onFinished (terminal status).
    }
  }

  // STALLED warning surfaced in-page (Java raised a separate Dialogs.info modal,
  // PredistortionWizardDialog:547-550) - a dismissible Bootstrap alert above the
  // progress readout, alongside the terminal status headline.
  _warn(text) {
    $('#dpdWarn').remove();
    $(`<div id="dpdWarn" class="alert alert-warning alert-dismissible py-1 px-2 small mb-2" role="alert">`
      + `${$('<div>').text(text).html()}`
      + `<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>`)
      .insertBefore('#dpdProgress');
  }

  _updateMetrics() {
    const r = this.getResult(), dual = this.state.dual, off = this.prefs.dbvOffsetDb;
    const dist = !r ? NaN : (dual ? this.host.imdPct(r) : r.thdPct);
    // bestThdPct starts at Number.MAX_VALUE (Java Double.MAX_VALUE "no best yet" sentinel), which
    // IS finite - so the isFinite guard alone showed 1.79e+308. Guard on < MAX_VALUE too.
    const best = this.pdEngine && Number.isFinite(this.pdEngine.bestThdPct)
      && this.pdEngine.bestThdPct < Number.MAX_VALUE ? this.pdEngine.bestThdPct : NaN;
    // Java PredistortionWizardDialog:346 fmtDbv(live.noisePeakFloorDbFs() + dbvOffset) - the PEAK
    // floor, computed once (noisePeakFloorDbFs() sorts internally).
    const floor = r ? r.noisePeakFloorDbFs() + off : NaN;
    const rows = [
      // ROUND - Java :325 running ? Integer.toString(engine.getCurrentRound() + 1) : "-".
      [t('predistortion.metric.round'), this.running && this.pdEngine ? this.pdEngine.currentRound + 1 : '-'],
      // AVERAGES - Java :334 Integer.toString(fft.completedAnalyses()); always the host's live count.
      [t('predistortion.metric.averages'), this.host.completedAnalyses()],
      // Distortion percentages: Java fmtPct is "%.8f %%" (PredistortionWizardDialog:708-710).
      [t(dual ? 'predistortion.metric.currentImd' : 'predistortion.metric.currentThd'), Number.isFinite(dist) ? dist.toFixed(8) + ' %' : '-'],
      [t(dual ? 'predistortion.metric.best_imd' : 'predistortion.metric.best_thd'), Number.isFinite(best) ? best.toFixed(8) + ' %' : '-'],
      // Ratio figures: Java fmtDbv is "%.2f dBV" (PredistortionWizardDialog:712-714) -
      // relabelled to dBV, never offset.
      [t(dual ? 'predistortion.metric.d_n' : 'predistortion.metric.thd_n'), r ? r.thdNDb.toFixed(2) + ' dBV' : '-'],
      [t('predistortion.metric.snr'), r ? r.snrDb.toFixed(2) + ' dBV' : '-'],
      [t('predistortion.metric.sinad'), r ? r.sinadDb.toFixed(2) + ' dBV' : '-'],
      [t('predistortion.metric.fund'), r ? (r.fundamentalDbFs + off).toFixed(2) + ' dBV' : '-'],
      // NOISE FLOOR - Java :346 fmtDbv(live.noisePeakFloorDbFs() + dbvOffset): the PEAK floor,
      // now that the live result is a real FftResult and noisePeakFloorDbFs() is callable.
      [t('predistortion.metric.floor'), Number.isFinite(floor) ? floor.toFixed(2) + ' dBV' : '-'],
    ];
    // Java renders each metric as a PLAIN SWT Label with the text "Label:  value"
    // (PredistortionWizardDialog metric() :373-374), laid out in a 2-column grid
    // (buildProgressGroup :197 GridLayout(2, true)) - no dotted rules, no styled
    // key/value split. Mirror that: one plain line per metric, "Key:  value".
    $('#dpdMetrics').html(rows.map(([k, v]) =>
      `<div class="dpd-line">${$('<span>').text(`${k}:  ${v}`).html()}</div>`).join(''));
  }

  // Log-scale convergence chart - faithful port of Java paintChart
  // (PredistortionWizardDialog:381-460): white background, L=50/R=10/T=8/B=18
  // margins, gray border + decade gridlines with "%.0e" labels, red-dashed
  // target line clipped to the plot, dark-blue polyline + 4 px round markers
  // spaced along X in proportion to each round's averaging depth (cum[0]=0 =>
  // first point at the LEFT edge; a single point sits centered). The Y range
  // auto-widens to whole decades from the DATA (+ the target), not a fixed
  // 10%..1e-5 span - that fixed span squashed the falling trace, so the
  // calibration process was not visible in it.
  _drawChart() {
    const cv = document.getElementById('dpdChart');
    if (!cv) return;
    const g = cv.getContext('2d');
    // HiDPI backing store (the fft-view render() pattern, fft-view.js:349-360):
    // backing px = CSS px × devicePixelRatio + setTransform(dpr,...) so drawing
    // below is in CSS-px coordinates. The old fixed width=440 attribute was
    // CSS-stretched to the (resizable) 100%-wide box - the "distorted" chart.
    const rect = cv.getBoundingClientRect ? cv.getBoundingClientRect() : null;
    const W = Math.max(1, Math.round((rect && rect.width) || cv.clientWidth || cv.width || 440));
    const H = Math.max(1, Math.round((rect && rect.height) || cv.clientHeight || cv.height || 150));
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    const bw = Math.round(W * dpr), bh = Math.round(H * dpr);
    if (cv.width !== bw) cv.width = bw;
    if (cv.height !== bh) cv.height = bh;
    if (g.setTransform) g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = '#fff'; g.fillRect(0, 0, W, H);

    // Margins + plot rect (Java :386-390); SWT COLOR_GRAY border.
    const L = 50, R = 10, T = 8, B = 18;
    const px = L, py = T, pw = W - L - R, ph = H - T - B;
    if (pw < 20 || ph < 20) return;
    g.lineWidth = 1;
    g.strokeStyle = '#c0c0c0'; g.strokeRect(px, py, pw, ph);

    // Committed per-round history (Java onRound :520-529) PLUS a provisional
    // live sample for the round in progress: while COLLECTING, the 100 ms poll
    // plots the live distortion at the live averaging depth, so the trace
    // advances DURING a round as averages accumulate and lands exactly on the
    // committed Java point when onRound fires - the calibration process has to
    // be visible while it runs (Java shows the same live figure in curThdLbl,
    // refresh() :328-330, and redraws the chart every tick, :348).
    let h = this.history.dist, ga = this.history.avg;
    const eng = this.pdEngine;
    if (this.running && eng && eng.phase === Phase.COLLECTING) {
      const r = this.getResult();
      const live = !r ? NaN : (this.state.dual ? this.host.imdPct(r) : r.thdPct);
      const avgs = this.host.completedAnalyses();
      if (Number.isFinite(live) && live > 0 && avgs > 0) {
        h = h.concat(live); ga = ga.concat(avgs);
      }
    }
    const n = h.length;
    if (n < 1) {
      // Empty placeholder (Java :394-398): centered em-dash, SWT COLOR_DARK_GRAY.
      g.fillStyle = '#808080'; g.font = '11px sans-serif';
      g.textAlign = 'center'; g.textBaseline = 'middle';
      g.fillText('-', px + pw / 2, py + ph / 2);
      return;
    }

    // Log-% Y range from the data, widened to whole decades, target kept in
    // view (Java :403-411).
    const target = parseFloat($('#dpdTarget').val()) || 0;
    let lo = Infinity, hi = -Infinity;
    for (const v of h) if (v > 0 && Number.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
    if (!(hi > 0)) return;
    if (!(lo > 0) || !Number.isFinite(lo)) lo = hi / 10;
    if (target > 0) lo = Math.min(lo, target);
    const yhi = Math.ceil(Math.log10(hi) + 1e-6);
    let ylo = Math.floor(Math.log10(lo) - 1e-6);
    if (yhi - ylo < 1) ylo = yhi - 1;

    // Decade gridlines + labels (Java :414-421): "%.0e" of 10^k right-aligned
    // 4 px left of the plot ("1e+00" / "1e-05" - mantissa 1, 2-digit exponent).
    g.strokeStyle = '#c0c0c0'; g.fillStyle = '#c0c0c0';
    g.font = '11px sans-serif'; g.textAlign = 'right'; g.textBaseline = 'middle';
    for (let k = ylo; k <= yhi + 1e-9; k += 1) {
      const y = py + Math.round((yhi - k) / (yhi - ylo) * ph);
      g.beginPath(); g.moveTo(px, y); g.lineTo(px + pw, y); g.stroke();
      g.fillText('1e' + (k < 0 ? '-' : '+') + String(Math.abs(k)).padStart(2, '0'), px - 4, y);
    }

    // Target line - red dashed, only when inside the plot (Java :424-432).
    if (target > 0) {
      const y = py + Math.round((yhi - Math.log10(target)) / (yhi - ylo) * ph);
      if (y >= py && y <= py + ph) {
        g.strokeStyle = '#f00'; g.setLineDash([4, 4]);
        g.beginPath(); g.moveTo(px, y); g.lineTo(px + pw, y); g.stroke();
        g.setLineDash([]);
      }
    }

    // Per-round x fractions (Java :437-444): cum[0]=0, gap i = round i's
    // averaging depth (>0, else 1) - a deeply-averaged round occupies a
    // correspondingly wide slice of the x-axis.
    const cum = new Array(n).fill(0);
    let total = 0;
    for (let i = 1; i < n; i++) {
      const gap = (i < ga.length && ga[i] > 0) ? ga[i] : 1;
      total += gap; cum[i] = total;
    }

    // Convergence trace + per-round markers (Java :447-459, SWT COLOR_DARK_BLUE).
    g.strokeStyle = '#000080'; g.fillStyle = '#000080';
    let prevX = 0, prevY = 0;
    for (let i = 0; i < n; i++) {
      const v = h[i] > 0 ? h[i] : lo;
      const x = px + (n === 1 ? pw / 2
        : total > 0 ? Math.round(cum[i] / total * pw)
          : Math.round(i / (n - 1) * pw));
      const y = py + Math.round((yhi - Math.log10(v)) / (yhi - ylo) * ph);
      if (i > 0) { g.beginPath(); g.moveTo(prevX, prevY); g.lineTo(x, y); g.stroke(); }
      g.beginPath(); g.arc(x, y, 2, 0, 2 * Math.PI); g.fill();
      prevX = x; prevY = y;
    }
  }

  // ---- .dpd header + default name (Java buildHeader / defaultDpdFileName) ----

  /** Java fmt(): "%.6f" (US locale). */
  _fmt(v) { return Number(v).toFixed(6); }

  /** Mirrors Java buildHeader(FftResult) - the full provenance + measurement header. */
  _buildHeader(r, dual) {
    const p = this.prefs;
    const h = [
      '# Phonalyser DAC predistortion',
      '# format_version=1',
      '# kind=predistortion',
      `# gen_form=${p.genSignalForm.get()}`,
    ];
    if (dual) {
      h.push(`# gen_frequency1_hz=${this._fmt(p.genDualToneFreq1Hz.get())}`);
      h.push(`# gen_frequency2_hz=${this._fmt(p.genDualToneFreq2Hz.get())}`);
    } else {
      h.push(`# gen_frequency_hz=${this._fmt(p.genFrequencyHz.get())}`);
    }
    h.push(`# gen_amplitude_vrms=${this._fmt(p.genAmplitudeVrms.get())}`);
    h.push(`# gen_dither_bits=${p.genDitherBits.get()}`);
    h.push(`# fft_size=${r.fftSize}`);
    h.push(`# fft_window=${p.fftWindow.get()}`);
    h.push(`# fft_overlap=${OVERLAP_LABEL[p.fftOverlap.get()] || p.fftOverlap.get()}`);
    h.push(`# fft_max_harmonic=${p.fftCalcMaxHarmonic.get()}`);
    h.push(`# fft_averages=${r.frameCount}`);
    h.push(`# thd_pct=${this._fmt(r.thdPct)}`);
    h.push(`# thd_plus_n_db=${this._fmt(-r.sinadDb)}`);
    h.push(`# snr_db=${this._fmt(r.snrDb)}`);
    h.push(`# dist_min_hz=${this._fmt(r.snrFreqMin)}`);
    h.push(`# dist_max_hz=${this._fmt(r.snrFreqMax)}`);
    return h;
  }

  /** Suggested .dpd name encoding the measurement conditions (Java defaultDpdFileName):
   *  predistortion-<THD|IMD>-<freq>[-<freq2>]-<fftSize>-<overlap>-<window>-<distPpm>.dpd.
   *  Decimal points become underscores so the name stays path-friendly. */
  _defaultDpdFileName(r, dual) {
    const kind = dual ? 'IMD' : 'THD';
    const freq = r.fundamentalHzRefined.toFixed(4).replace('.', '_');
    const freq2 = dual ? '-' + r.fundamental2HzRefined.toFixed(4).replace('.', '_') : '';
    const size = this._humanFftSize(r.fftSize);
    const overlapLabel = OVERLAP_LABEL[r.overlap] || OVERLAP_LABEL[this.prefs.fftOverlap.get()] || '';
    const overlap = overlapLabel.replace('%', '').replace('.', '_');
    const window = r.windowType != null ? r.windowType : this.prefs.fftWindow.get();
    const distPpm = this.pdEngine.bestThdPct * 1_000_000.0;   // % -> ppm % (THD single / IMD dual)
    const dist = distPpm.toFixed(2).replace('.', '_');
    return `predistortion-${kind}-${freq}${freq2}-${size}-${overlap}-${window}-${dist}.dpd`;
  }

  /** Compact power-of-two FFT length: 2097152 -> "2M", 524288 -> "512k". */
  _humanFftSize(n) {
    if (n >= (1 << 20) && n % (1 << 20) === 0) return (n >> 20) + 'M';
    if (n >= (1 << 10) && n % (1 << 10) === 0) return (n >> 10) + 'k';
    return String(n);
  }

  /** Wires the wizard's modal + button handlers. Call once after the modal instance is live. */
  bind() {
    $('#predistBtn').on('click', () => {
      this.applied = false; this.savedName = null;
      $('#dpdSave').text(t('predistortion.button.save')).prop('disabled', true);
      // Seed both fields from Preferences so the user's last-used values survive
      // reopening the wizard AND restarting the app (Java buildSettings:234/247).
      $('#dpdAverages').val(this.prefs.predistortionAverages.get());
      $('#dpdTarget').val(this.prefs.predistortionTargetPct.get());
      // Java open() calls refresh() then armTimer() BEFORE the dialog is shown, so the FFT
      // group, convergence chart and metric panel are live from open - not blank until Start
      // (PredistortionWizardDialog:161/165). Seed the live UI once, then arm the persistent
      // 100 ms poll that keeps FFT info + metrics current off getResult() even while idle.
      this._updateFftInfo(); this._drawChart(); this._updateMetrics();
      this._startPoll();
      this.modal.show();
    });

    // Wizard close (Java handleCancel, wired to the X / Esc / Cancel): stop the loop, revert the
    // live compensation unless it was applied, and reset the FFT statistics so the view restarts clean.
    $('#predistModal').on('hidden.bs.modal', () => {
      this._stopPoll();
      if (this.pdEngine) this.pdEngine.stop();
      if (!this.applied) this.host.clearCompensation();
      this.host.resetStatistics();
    });

    // Persist both fields on EDIT (not at Start/Save), mirroring the Java
    // addSelectionListener bindings (buildSettings:239/249) - the value is
    // remembered the moment the user changes it, whether or not a tune runs.
    $('#dpdAverages').on('change input', () => {
      this.prefs.predistortionAverages.set(Math.round(parseFloat($('#dpdAverages').val()) || 0));
      this.prefs.save();
    });
    // Coarse wheel step: ±10 per notch on #dpdAverages (Java averagesField wheel=10,
    // arrows stay ±1), clamped to [MIN_AVERAGES, MAX_AVERAGES] = [10, 1000000].
    $('#dpdAverages').on('wheel', (ev) => {
      const el = ev.currentTarget;
      if (el.disabled) return;
      ev.preventDefault();
      const dir = ev.originalEvent.deltaY < 0 ? 1 : -1;
      const next = Math.min(1000000, Math.max(10, (Math.round(parseFloat(el.value) || 0)) + dir * 10));
      $(el).val(next).trigger('input');
    });
    $('#dpdTarget').on('change input', () => {
      this.prefs.predistortionTargetPct.set(parseFloat($('#dpdTarget').val()) || 0);
      this.prefs.save();
    });

    // Footer: ONE #dpdStart button that toggles Start<->Stop, mirroring Java's
    // single startStopBtn (onStartStop branches on `running`,
    // PredistortionWizardDialog:466-508). On Start it runs the tune, its label
    // flips to predistortion.button.stop and its colour to danger; the running
    // branch routes to engine.stop() and disables itself (re-enabled by
    // onFinished, which flips label + colour back to start/success).
    $('#dpdStart').on('click', async () => {
      // Running branch (Java onStartStop:467-471): stop the engine, disable self
      // until onFinished flips the button back.
      if (this.running) {
        if (this.pdEngine) this.pdEngine.stop();
        $('#dpdStart').prop('disabled', true);
        return;
      }
      if (!this.engine.running) { $('#dpdProgress').text(t('predistortion.error.noGenerator')); return; }
      // Predistortion only supports the Sine / Dual-tone waveforms (Java validation).
      const okForm = [GenSignalForm.SINE, GenSignalForm.SINE_COMP, GenSignalForm.DUAL_TONE, GenSignalForm.DUAL_TONE_COMP]
        .includes(this.engine.config.form);
      if (!okForm) { $('#dpdProgress').text(t('predistortion.error.notSupported')); return; }
      // Minimum 10 averages per round (Java MIN_AVERAGES) - too few can't build
      // a deep-enough coherent average to read the harmonics cleanly.
      const base = Math.max(10, parseInt($('#dpdAverages').val(), 10) || 16);
      const target = parseFloat($('#dpdTarget').val()) || 0;
      // Toggle #dpdStart into its Stop role: stays enabled (it IS the stop control
      // now), label + colour flip to stop/danger (Java onStartStop:504 setText, plus
      // the web's colour cue). Reverted in onFinished / the catch path.
      this.running = true;
      $('#dpdStart').text(t('predistortion.button.stop'))
        .removeClass('btn-success').addClass('btn-danger');
      // Lock the run parameters for the duration of the tune (Java onStartStop
      // disables averagesField + targetField, PredistortionWizardDialog:502-503);
      // re-enabled in onFinished and the catch path (lines 538-539).
      $('#dpdAverages, #dpdTarget').prop('disabled', true);
      // Stop-round stays disabled until the poll sees Phase.COLLECTING (Java gate,
      // PredistortionWizardDialog:317).
      this.savedName = null; $('#dpdSave').text(t('predistortion.button.save')).prop('disabled', true);
      const dual = isDualTone(this.engine.config.form);
      $('#dpdProgress').text(t('predistortion.phase.aligning'));
      this._resetLive(dual);
      // The persistent poll armed at open already renders phase + metrics + FFT info + chart;
      // re-arm it (idempotent) so a run begun after the idle poll was somehow torn down still polls.
      this._startPoll();

      this.pdEngine = new PredistortionEngine(this.host, {
        onAligning: () => $('#dpdProgress').text(t('predistortion.phase.aligning')),
        onRound: (round, distPct, averages) => {
          // Java onRound (PredistortionWizardDialog:519-530) only stashes the round
          // data; the polling timer renders the headline off the engine phase.
          this.state.round = round; this.state.averages = averages;
          this.history.dist.push(distPct); this.history.avg.push(averages);   // convergence history
          this._drawChart(); this._updateMetrics();
        },
        onFinished: (reason, bestDistPct, hasResult) => {
          // Java leaves the timer running the whole dialog lifetime (torn down only on close),
          // so the metrics + chart stay live after a run ends - don't stop the poll here; the
          // terminal headline set below survives subsequent polls (_renderPhase leaves IDLE
          // headlines untouched). The poll is torn down on hidden.bs.modal.
          this._updateMetrics();
          // Map the StopReason to a terminal status key for the headline (Java
          // PredistortionWizardDialog:541-546), not the raw enum.
          const key = reason === StopReason.TARGET_REACHED ? 'predistortion.status.targetReached'
            : reason === StopReason.STALLED ? 'predistortion.status.stalled'
            : reason === StopReason.ERROR ? 'predistortion.status.error'
            : 'predistortion.status.stopped';
          $('#dpdProgress').text(t(key));
          // On STALLED surface the warn banner (Java Dialogs.info, line 547-550).
          if (reason === StopReason.STALLED) this._warn(t('predistortion.warn.stalled'));
          // Flip #dpdStart back to its Start role: enabled, start label + success
          // colour (Java onFinished:536-537 setEnabled(true) + setText(start)).
          this.running = false;
          $('#dpdStart').prop('disabled', false).text(t('predistortion.button.start'))
            .removeClass('btn-danger').addClass('btn-success');
          $('#dpdStopRound').prop('disabled', true);
          // Re-enable the run parameters (Java onFinished, PredistortionWizardDialog:538-539).
          $('#dpdAverages, #dpdTarget').prop('disabled', false);
          $('#dpdSave').prop('disabled', !hasResult);
        },
      });
      try {
        await this.pdEngine.runLoop(base, target);
      } catch (e) {
        this._stopPoll();
        $('#dpdProgress').text('predistortion failed: ' + e.message);
        // Flip #dpdStart back to its Start role on failure too.
        this.running = false;
        $('#dpdStart').prop('disabled', false).text(t('predistortion.button.start'))
          .removeClass('btn-danger').addClass('btn-success');
        $('#dpdStopRound').prop('disabled', true);
        // Re-enable the run parameters on failure too (Java onFinished re-enables them).
        $('#dpdAverages, #dpdTarget').prop('disabled', false);
      }
    });

    $('#dpdStopRound').on('click', () => { if (this.pdEngine) this.pdEngine.stopRound(); });

    $('#dpdSave').on('click', async () => {
      if (!this.pdEngine) return;
      // Second click (button relabelled to Apply, Java doApply): switch the generator to the
      // compensated form, persist the .dpd path, and keep the hot-applied correction on close.
      if (this.savedName) {
        const applyForm = this.pdEngine.dualTone ? GenSignalForm.DUAL_TONE_COMP : GenSignalForm.SINE_COMP;
        this.applied = true;
        this.onApply(applyForm, this.savedName);
        this.modal.hide();
        return;
      }
      const c = this.engine.config, r = this.pdEngine.bestResult;
      const dual = this.pdEngine.dualTone;
      if (!r) { $('#dpdProgress').text('no correction to save.'); return; }
      const sampleRate = this.engine.outSampleRate || c.outRate;
      const fundamentalDbFs = r.fundamentalDbFs;
      const header = this._buildHeader(r, dual);
      try {
        let text;
        const name = this._defaultDpdFileName(r, dual);
        if (dual) {
          if (!this.pdEngine.bestIntermod) { $('#dpdProgress').text('no correction to save.'); return; }
          const f1 = this.engine.snapped, f2 = c.tone2Hz;
          text = writeIntermodDpd(this.pdEngine.bestIntermod, header, f1, f2, fundamentalDbFs,
            sampleRate, this.prefs.outputBitDepth, this.prefs.genAmplitudeVrms.get(), this.pdEngine.calResponseFor(true),
            this.prefs.adcFsVoltageRms.get(), this.pdEngine.dualToneFundamentalVrms());
        } else {
          if (!this.pdEngine.bestApplied) { $('#dpdProgress').text('no correction to save.'); return; }
          text = writeHarmonicDpd(this.pdEngine.bestApplied, header, this.engine.snapped, fundamentalDbFs,
            sampleRate, this.prefs.outputBitDepth, this.prefs.genAmplitudeVrms.get(), this.pdEngine.calResponseFor(true),
            this.prefs.adcFsVoltageRms.get());
        }
        const res = await this.saveFile(text, name, DPD_TYPE);
        if (res.saved) {
          this.savedName = res.name;
          $('#dpdProgress').text(res.viaDownload
            ? t('web.save.handedToDownload', res.name) : t('predistortion.status.saved'));
          $('#dpdSave').text(t('predistortion.button.apply'));   // relabel Save -> Apply
        }
      } catch (e) { $('#dpdProgress').text('save failed: ' + e.message); }
    });

    return this;
  }
}
