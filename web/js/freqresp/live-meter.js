/*
 * Phonalyser web - the live per-sweep input-level meter (level-vs-time chart shown
 * in the busy/modal while a frequency-response sweep runs).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.freqresp.FreqRespLiveMeter - a compact
 * RMS-level-over-time chart (REW's per-sweep level monitor). Axes: X = elapsed time
 * 0..totalDurationSec, Y = RMS dBFS 0 dB (top) .. −100 dB (bottom), labelled every
 * 20 dB. appendSample(timeSec, rmsLin) runs the incoming linear RMS through an EMA
 * whose time-constant tracks the sweep's instantaneous frequency (SMOOTHING_PERIODS
 * periods, collapsing above SMOOTHING_HF_CORNER_HZ) so the low-frequency "teeth" (a
 * block holding a partial cycle) are smoothed while the envelope stays crisp once each
 * block spans several periods. MAX_POINTS-capped (decimate-by-2 on overflow). The
 * trace uses the FFT line colour (the same signal, different lens).
 *
 * DEVIATION FROM THE DESKTOP: the web FreqResp sweep records the loopback in ONE shot
 * (FreqRespHost.runSweep) rather than streaming per-block RMS from the analyzer, so the
 * desktop's per-block captureProgress cadence isn't available live. The meter math here
 * is byte-for-byte the desktop's appendSample/instantaneousHz; the WIZARD drives it
 * post-hoc from the recorded buffer (a windowed RMS walk at a fixed block size), so the
 * EMA still sees a faithful (time, rmsLin) stream - only the cadence differs, never the
 * math. The chart is otherwise identical to the desktop's.
 */

const DB_MAX = 0.0;
const DB_MIN = -100.0;
const DB_LABEL_STEP = 20.0;

const MARGIN_LEFT = 36;
const MARGIN_RIGHT = 6;
const MARGIN_TOP = 4;
const MARGIN_BOTTOM = 18;

/** EMA smoothing time constant in PERIODS of the sweep's instantaneous frequency. */
const SMOOTHING_PERIODS = 5.0;
/** Above this frequency the period count shrinks with (corner/f)² - smoothing fades. */
const SMOOTHING_HF_CORNER_HZ = 100.0;
/** Upper cap for the per-block EMA factor so the trace never freezes entirely. */
const ALPHA_MAX = 0.999;

const MAX_POINTS = 4096;

/** packed-int colour -> CSS hex (matches the fft/freqresp view helpers). */
const colorHex = (c) => '#' + (c & 0xffffff).toString(16).padStart(6, '0');

export class FreqRespLiveMeter {
  /**
   * @param {HTMLCanvasElement} canvas the meter canvas (sized by its CSS box)
   * @param {object} prefs Preferences.instance() (the FFT line colour feeds the trace)
   * @param {number} totalDurationSec expected total capture time (leadIn + sweep + tail)
   * @param {number} leadInSec silent lead-in before the sweep
   * @param {number} sweepSec the sweep span
   * @param {number} startHz sweep start frequency
   * @param {number} stopHz sweep stop frequency
   */
  constructor(canvas, prefs, totalDurationSec, leadInSec, sweepSec, startHz, stopHz) {
    this.cv = canvas;
    this.g = canvas.getContext('2d');
    this.totalDurationSec = Math.max(0.001, totalDurationSec);
    this.leadInSec = Math.max(0.0, leadInSec);
    this.sweepSec = Math.max(1e-9, sweepSec);
    this.startHz = Math.max(0.001, startHz);
    this.stopHz = Math.max(this.startHz, stopHz);
    // Same colour as the FFT spectrum trace (user-configurable pref).
    this.traceColor = colorHex(prefs.fftLineColor ? prefs.fftLineColor.get() : 0x0064c8);

    // Two parallel arrays grown together - avoids boxing (FreqRespLiveMeter).
    this.timesSec = new Float32Array(MAX_POINTS);
    this.levelsDb = new Float32Array(MAX_POINTS);
    this.pointCount = 0;

    // Smoothed RMS EMA state; seeded on the first sample.
    this.emaRmsLin = 0.0;
    this.emaSeeded = false;
    this.lastTimeSec = 0.0;
  }

  /** Pushes one (time, RMS) sample into the chart. The incoming linear RMS runs through
   *  an EMA whose time-constant tracks the sweep's instantaneous frequency
   *  (SMOOTHING_PERIODS periods). Faithful to FreqRespLiveMeter.appendSample. */
  appendSample(timeSec, rmsLin) {
    if (rmsLin < 0.0 || !Number.isFinite(rmsLin)) rmsLin = 0.0;
    if (!this.emaSeeded) {
      this.emaRmsLin = rmsLin;
      this.emaSeeded = true;
    } else {
      const dt = Math.max(0.0, timeSec - this.lastTimeSec);
      const f = this._instantaneousHz(timeSec);
      const hfRatio = Math.min(1.0, SMOOTHING_HF_CORNER_HZ / f);
      const periods = SMOOTHING_PERIODS * hfRatio * hfRatio;
      const alpha = Math.min(ALPHA_MAX, Math.exp(-dt * f / periods));
      this.emaRmsLin = alpha * this.emaRmsLin + (1.0 - alpha) * rmsLin;
    }
    this.lastTimeSec = timeSec;
    const smoothed = this.emaRmsLin;
    let db = (smoothed > 0.0) ? 20.0 * Math.log10(smoothed) : DB_MIN;
    if (db < DB_MIN) db = DB_MIN;
    if (db > DB_MAX) db = DB_MAX;
    if (this.pointCount < MAX_POINTS) {
      this.timesSec[this.pointCount] = timeSec;
      this.levelsDb[this.pointCount] = db;
      this.pointCount++;
    } else {
      // Compact: drop every other point so the chart still shows history at half-resolution
      // and stays responsive on very long captures (doubles the time-density limit).
      let w = 0;
      for (let r = 0; r < MAX_POINTS; r += 2) {
        this.timesSec[w] = this.timesSec[r];
        this.levelsDb[w] = this.levelsDb[r];
        w++;
      }
      this.pointCount = w;
      this.timesSec[this.pointCount] = timeSec;
      this.levelsDb[this.pointCount] = db;
      this.pointCount++;
    }
    this.render();
  }

  /** Resets the trace. Used when the same meter widget is reused for a fresh capture. */
  clear() {
    this.pointCount = 0;
    this.emaRmsLin = 0.0;
    this.emaSeeded = false;
    this.lastTimeSec = 0.0;
    this.render();
  }

  /** The log sweep's instantaneous frequency at elapsed capture time t: startHz during
   *  the lead-in, the logarithmic interpolation across the sweep, stopHz in the tail
   *  (FreqRespLiveMeter.instantaneousHz). */
  _instantaneousHz(t) {
    const tSweep = t - this.leadInSec;
    if (tSweep <= 0) return this.startHz;
    if (tSweep >= this.sweepSec) return this.stopHz;
    return this.startHz * Math.pow(this.stopHz / this.startHz, tSweep / this.sweepSec);
  }

  render() {
    const g = this.g, cv = this.cv;
    // HiDPI backing store matching the CSS box (same pattern as fft-view): the backing
    // store is CSS-px × devicePixelRatio and a setTransform maps 1 CSS px -> dpr device px, so
    // all drawing below is in CSS-px (W, H). The canvas previously set width = clientWidth with
    // NO dpr and while the modal was still display:none clientWidth read 0 -> a mismatched
    // backing store, which CSS then stretched (the distorted, oversized graph).
    const W = cv.clientWidth || 520, H = cv.clientHeight || 100;
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    const bw = Math.round(W * dpr), bh = Math.round(H * dpr);
    if (cv.width !== bw) cv.width = bw;
    if (cv.height !== bh) cv.height = bh;
    if (g.setTransform) g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = '#ffffff';
    g.fillRect(0, 0, W, H);
    g.font = '11px "Segoe UI", sans-serif';

    const plot = {
      x: MARGIN_LEFT, y: MARGIN_TOP,
      w: Math.max(1, W - MARGIN_LEFT - MARGIN_RIGHT),
      h: Math.max(1, H - MARGIN_TOP - MARGIN_BOTTOM),
    };
    this._drawGrid(g, plot);
    this._drawAxes(g, plot);
    this._drawTrace(g, plot);
  }

  _drawGrid(g, plot) {
    g.strokeStyle = '#d0d0d0'; g.lineWidth = 1;
    for (let db = DB_MAX; db >= DB_MIN - 1e-9; db -= DB_LABEL_STEP) {
      const y = this._dbToY(db, plot);
      g.beginPath(); g.moveTo(plot.x, y); g.lineTo(plot.x + plot.w, y); g.stroke();
    }
  }

  _drawAxes(g, plot) {
    g.strokeStyle = '#606060'; g.lineWidth = 1;
    g.strokeRect(plot.x, plot.y, plot.w, plot.h);

    // dB axis labels, every DB_LABEL_STEP dB.
    g.textBaseline = 'middle'; g.textAlign = 'right';
    for (let db = DB_MAX; db >= DB_MIN - 1e-9; db -= DB_LABEL_STEP) {
      const y = this._dbToY(db, plot);
      g.strokeStyle = '#606060';
      g.beginPath(); g.moveTo(plot.x - 3, y); g.lineTo(plot.x, y); g.stroke();
      g.fillStyle = '#202020';
      g.fillText(db.toFixed(0), plot.x - 5, y);
    }

    // Bottom time-axis: "0" at the left edge and the total duration at the right edge.
    const axisY = plot.y + plot.h;
    g.fillStyle = '#202020'; g.textBaseline = 'top';
    g.textAlign = 'left'; g.fillText('0', plot.x, axisY + 1);
    g.textAlign = 'right'; g.fillText(this.totalDurationSec.toFixed(1) + ' s', plot.x + plot.w, axisY + 1);
  }

  _drawTrace(g, plot) {
    if (this.pointCount < 1) return;
    g.strokeStyle = this.traceColor; g.lineWidth = 2;
    g.beginPath();
    for (let i = 0; i < this.pointCount; i++) {
      const x = this._timeToX(this.timesSec[i], plot);
      const y = this._dbToY(this.levelsDb[i], plot);
      i === 0 ? g.moveTo(x, y) : g.lineTo(x, y);
    }
    g.stroke();
  }

  _dbToY(db, plot) {
    const frac = (DB_MAX - db) / (DB_MAX - DB_MIN);
    return plot.y + Math.round(frac * plot.h);
  }

  _timeToX(t, plot) {
    let frac = t / this.totalDurationSec;
    if (frac < 0) frac = 0;
    if (frac > 1) frac = 1;
    return plot.x + Math.round(frac * plot.w);
  }
}
