/*
 * Phonalyser web — branded launch splash (browser port of gui/StartupSplash).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * Faithful Canvas2D port of the SWT StartupSplash paint routine: a dark
 * gradient backdrop with an audio motif — a glowing composite waveform over an
 * FFT-style spectrum — and the title, version, tagline, copyright and project
 * link overlaid. The Java window is 560×340; the web shows the SAME artwork as
 * a 560×340 panel centred on a dimmed full-viewport backdrop.
 *
 * Shown from page load (a static overlay div in index.html covers the viewport
 * with the backdrop colour before this module even runs), and dismissed once
 * the startup audio-device scan resolves — the same scan the Preferences "Scan
 * devices" button runs, executed at boot.
 * GNU AGPL v3 or later.
 */

// Panel geometry — matches StartupSplash.WIDTH/HEIGHT/MARGIN exactly so the
// pixel layout below (text positions, bar span, waveform baseline) is faithful.
const WIDTH = 560;
const HEIGHT = 340;
const MARGIN = 34;

// Render scale (0.75×): the artwork is drawn in the 560×340 panel space above,
// but presented at 420×255. We scale the canvas backing store + CSS box and
// pre-multiply the ctx transform by SCALE, so the vector paths + text rasterize
// crisply at the target resolution (NOT a CSS-scaled bitmap). Java splash is
// 560×340; the web shows the SAME artwork at 0.75× per user feedback.
const SCALE = 0.75;
export const RENDER_WIDTH = WIDTH * SCALE;
export const RENDER_HEIGHT = HEIGHT * SCALE;

// Footer legal lines — fixed identifiers, not UI prose (mirrors the Java constants).
const COPYRIGHT = '© 2026 Dimitrij Goldstein';
const LICENSE = 'GNU Affero GPL v3 — free software, no warranty';
const REPO_URL = 'https://github.com/dgo42/Phonalyser';

// Palette — the exact RGB triples StartupSplash.allocate() builds.
const BG_TOP = 'rgb(10,14,26)';       // also the index.html static-overlay colour
const BG_BOTTOM = 'rgb(18,40,62)';
const GRID = 'rgb(60,92,134)';
const WAVE = 'rgb(63,208,224)';
const BAR_TOP = 'rgb(79,200,227)';
const BAR_BOTTOM = 'rgb(227,200,79)';
const TEXT_MAIN = 'rgb(234,242,255)';
const TEXT_DIM = 'rgb(138,160,190)';
const ACCENT = 'rgb(99,210,226)';
const BORDER = 'rgb(46,74,107)';

// SWT point sizes → CSS px (1pt ≈ 1.333px at 96 DPI), so the on-screen layout
// the Java pixel coordinates were designed against is reproduced.
const PT = 1.333;
const FONT_TITLE = `bold ${Math.round(30 * PT)}px "Segoe UI", system-ui, sans-serif`;
const FONT_TAGLINE = `${Math.round(11 * PT)}px "Segoe UI", system-ui, sans-serif`;
const FONT_VERSION = `bold ${Math.round(12 * PT)}px "Segoe UI", system-ui, sans-serif`;
const FONT_SMALL = `${Math.round(8 * PT)}px "Segoe UI", system-ui, sans-serif`;

const FADE_MS = 200;
// SAFETY (web adaptation): dismiss no later than this even if the scan never
// settles (e.g. a hung permission prompt), so the overlay can never brick the app.
const HARD_TIMEOUT_MS = 20000;

export class StartupSplash {
  /**
   * @param {object} opts
   * @param {() => string} opts.version  app version string (e.g. "v1.0.3 beta"),
   *        from the same source the About dialog reads (the .menu-ver span).
   * @param {(key: string) => string} opts.t  translator for splash.tagline.
   */
  constructor(opts) {
    this.version = opts.version;
    this.t = opts.t;
    this.overlay = document.getElementById('startupSplash');
    this.canvas = this.overlay ? this.overlay.querySelector('canvas') : null;
    this.dismissed = false;
  }

  /** Draws the full artwork into the overlay's canvas (over the static backdrop
   *  colour already shown by the index.html overlay). No-op if the overlay was
   *  already removed / absent. */
  show() {
    if (!this.canvas || this.dismissed) return;
    this.paintInto(this.canvas);
  }

  /** Sizes {@code canvas} to the 0.75× render box (DPR-aware) and draws the
   *  full artwork into it crisply. Shared by the startup splash and the About
   *  presentation (Java: both reuse StartupSplash.render). */
  paintInto(canvas) {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(WIDTH * SCALE * dpr);
    canvas.height = Math.round(HEIGHT * SCALE * dpr);
    canvas.style.width = RENDER_WIDTH + 'px';
    canvas.style.height = RENDER_HEIGHT + 'px';
    const ctx = canvas.getContext('2d');
    // DPR × SCALE: the artwork below still uses 560×340 panel coordinates; this
    // transform rasterizes them into the 420×255 box at full device resolution.
    ctx.setTransform(dpr * SCALE, 0, 0, dpr * SCALE, 0, 0);
    this.render(ctx);
  }

  /** Renders the SAME artwork into the About dialog's canvas and makes the repo
   *  URL clickable (Java StartupSplash.showAsAbout: same artwork, hit-testable
   *  URL that opens the browser, Esc / click-elsewhere dismiss — the last two are
   *  Bootstrap-modal behaviour here). The canvas carries the version/tagline/
   *  copyright/license already, exactly like the splash. Idempotent: re-opening
   *  re-paints but wires the URL handler only once (guarded by a dataset flag). */
  showAsAbout(canvas) {
    if (!canvas) return;
    this.paintInto(canvas);
    if (canvas.dataset.aboutWired === '1') return;
    canvas.dataset.aboutWired = '1';
    // Map a CSS-px pointer position (relative to the canvas box) into panel space,
    // then hit-test the stored URL rect. The box is RENDER_WIDTH×RENDER_HEIGHT
    // (0.75× panel), so divide out SCALE to get panel coordinates.
    const overUrl = (ev) => {
      const r = canvas.getBoundingClientRect();
      const px = (ev.clientX - r.left) / SCALE;
      const py = (ev.clientY - r.top) / SCALE;
      const b = this.urlBounds;
      return b && px >= b.x && px <= b.x + b.w && py >= b.y && py <= b.y + b.h;
    };
    canvas.addEventListener('mousemove', (ev) => { canvas.style.cursor = overUrl(ev) ? 'pointer' : 'default'; });
    canvas.addEventListener('click', (ev) => { if (overUrl(ev)) window.open(REPO_URL, '_blank', 'noopener'); });
  }

  /** Dismisses the splash exactly once: fade the overlay out over ~200 ms, then
   *  remove it from the DOM so it is gone for good (never re-shown). */
  dismiss() {
    if (this.dismissed) return;
    this.dismissed = true;
    if (!this.overlay) return;
    this.overlay.style.transition = `opacity ${FADE_MS}ms ease`;
    this.overlay.style.opacity = '0';
    setTimeout(() => { if (this.overlay) this.overlay.remove(); }, FADE_MS);
  }

  /** Ties dismissal to the startup device scan: hide the splash when the scan
   *  settles (resolve OR reject), and unconditionally after a hard timeout.
   *  @param {Promise<*>} scanPromise the promise returned by prefsDialog.scan(). */
  dismissOnScan(scanPromise) {
    const done = () => this.dismiss();
    Promise.resolve(scanPromise).then(done, done);
    setTimeout(done, HARD_TIMEOUT_MS);
  }

  // -------------------------------------------------------------------------
  // Artwork — a line-for-line port of StartupSplash.render / drawGrid /
  // drawSpectrum / drawWaveform. All coordinates are in the 560×340 panel space.
  // -------------------------------------------------------------------------

  /** Draws the whole splash onto {@code ctx} at WIDTH×HEIGHT. */
  render(ctx) {
    const w = WIDTH;
    const h = HEIGHT;

    // Backdrop gradient (top → bottom).
    const bg = ctx.createLinearGradient(0, 0, 0, h);
    bg.addColorStop(0, BG_TOP);
    bg.addColorStop(1, BG_BOTTOM);
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, w, h);

    this.drawGrid(ctx, w, h);
    this.drawSpectrum(ctx, w, h);
    this.drawWaveform(ctx, w, h);

    // Text overlay. SWT drawText anchors at the glyph BOX top-left, so we use
    // textBaseline 'top' and the same x/y the Java render() passes.
    ctx.textBaseline = 'top';
    ctx.textAlign = 'left';

    ctx.font = FONT_TITLE;
    ctx.fillStyle = TEXT_MAIN;
    ctx.fillText('Phonalyser', MARGIN, 38);

    ctx.font = FONT_TAGLINE;
    ctx.fillStyle = TEXT_DIM;
    ctx.fillText(this.t('splash.tagline'), MARGIN + 2, 102);

    ctx.font = FONT_VERSION;
    ctx.fillStyle = ACCENT;
    const version = this.version();
    ctx.fillText(version, w - MARGIN - ctx.measureText(version).width, 46);

    ctx.font = FONT_SMALL;
    ctx.fillStyle = TEXT_DIM;
    ctx.fillText(COPYRIGHT, MARGIN, h - 50);
    ctx.fillText(LICENSE, MARGIN, h - 34);
    // Repo URL on the upper footer line (right), clear of the license line.
    // Store its panel-space rect (like Java StartupSplash.urlBounds) so the
    // About presentation can hit-test clicks against it.
    ctx.fillStyle = ACCENT;
    const uw = ctx.measureText(REPO_URL).width;
    const ux = w - MARGIN - uw;
    const uy = h - 50;
    ctx.fillText(REPO_URL, ux, uy);
    // FONT_SMALL is 8pt → its px line height; the emHeight metrics aren't uniform
    // across engines, so use the font size in px as the hit height (generous enough).
    this.urlBounds = { x: ux, y: uy, w: uw, h: Math.round(8 * PT) };

    ctx.strokeStyle = BORDER;
    ctx.lineWidth = 1;
    ctx.strokeRect(0.5, 0.5, w - 1, h - 1);
  }

  /** Faint instrument grid behind the trace. */
  drawGrid(ctx, w, h) {
    ctx.save();
    ctx.strokeStyle = GRID;
    ctx.lineWidth = 1;
    ctx.globalAlpha = 26 / 255;
    ctx.beginPath();
    for (let x = MARGIN; x < w - MARGIN; x += 26) { ctx.moveTo(x + 0.5, 120); ctx.lineTo(x + 0.5, h - 60); }
    for (let y = 120; y < h - 60; y += 26) { ctx.moveTo(MARGIN, y + 0.5); ctx.lineTo(w - MARGIN, y + 0.5); }
    ctx.stroke();
    ctx.restore();
  }

  /** FFT-style magnitude bars: a broadband floor with two resonant peaks,
   *  fully deterministic so the splash looks the same every launch. */
  drawSpectrum(ctx, w, h) {
    const left = MARGIN;
    const right = w - MARGIN;
    const baseline = h - 64;
    const span = right - left;
    const bars = 60;
    const gap = 2;
    const bw = Math.max(2, Math.trunc(span / bars) - gap);
    const maxBar = 90;
    for (let i = 0; i < bars; i++) {
      const f = i / (bars - 1);
      const peak1 = Math.exp(-Math.pow((f - 0.22) / 0.06, 2));
      const peak2 = 0.7 * Math.exp(-Math.pow((f - 0.55) / 0.05, 2));
      const floor = 0.12 + 0.10 * (1 - f);
      const ripple = 0.05 * (0.5 + 0.5 * Math.sin(i * 0.9));
      const mag = Math.min(1.0, floor + peak1 + peak2 + ripple);
      const barH = Math.round(mag * maxBar);
      const x = left + i * (bw + gap);
      const grad = ctx.createLinearGradient(0, baseline - barH, 0, baseline);
      grad.addColorStop(0, BAR_TOP);
      grad.addColorStop(1, BAR_BOTTOM);
      ctx.fillStyle = grad;
      ctx.fillRect(x, baseline - barH, bw, barH);
    }
    ctx.save();
    ctx.strokeStyle = BORDER;
    ctx.globalAlpha = 120 / 255;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(left, baseline + 0.5);
    ctx.lineTo(right, baseline + 0.5);
    ctx.stroke();
    ctx.restore();
  }

  /** Glowing composite waveform (sum of three harmonics) across the panel,
   *  drawn as widening, fading passes for a soft bloom. */
  drawWaveform(ctx, w, h) {
    const left = MARGIN;
    const right = w - MARGIN;
    const mid = 152;
    const amp = 34;
    const n = right - left;
    const pts = new Array(n + 1);
    for (let i = 0; i <= n; i++) {
      const t = (i / n) * Math.PI * 2 * 3;
      let v = Math.sin(t) + 0.45 * Math.sin(2 * t + 0.6) + 0.22 * Math.sin(3 * t + 1.2);
      v /= 1.67;
      pts[i] = [left + i, Math.round(mid - v * amp)];
    }
    ctx.save();
    ctx.strokeStyle = WAVE;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    const widths = [7, 4, 2];
    const alphas = [40, 90, 255];
    for (let p = 0; p < widths.length; p++) {
      ctx.lineWidth = widths[p];
      ctx.globalAlpha = alphas[p] / 255;
      ctx.beginPath();
      ctx.moveTo(pts[0][0], pts[0][1]);
      for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
      ctx.stroke();
    }
    ctx.restore();
  }
}
