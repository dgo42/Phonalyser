/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Canvas-drawn scrollbar — faithful port of the Java
// org.edgo.audio.measure.gui.widgets.FlatScrollbar. A constant-thickness flat
// dark track + lighter rounded thumb + always-on end arrows, sized to
// ARROW_SIZE px along the scrolling axis. Mirrors the Slider API the scope
// pane drives (minimum / maximum / selection / thumb / increment /
// pageIncrement + an onChange callback): arrow click = ±increment, track click
// = ±pageIncrement, thumb drag = continuous, hold-on-arrow = 10 Hz auto-repeat
// after a 300 ms delay, wheel = ±increment, double-click on the thumb recentres.
// The host passes a <canvas>; the widget owns its painting + mouse wiring.

const ARROW_SIZE = 18;        // px of each end-arrow along the scrolling axis
const REPEAT_DELAY_MS = 300;  // initial hold delay before auto-repeat
const ARROW_REPEAT_MS = 100;  // auto-repeat interval while an arrow is held (10 Hz)
const TRACK_REPEAT_MS = 50;   // faster page-repeat while the free track is held
const MIN_THUMB_PX = 20;      // floor so the thumb stays grabbable at any zoom

export class FlatScrollbar {
  /** @param {HTMLCanvasElement} canvas
   *  @param {{vertical?: boolean, onChange?: (sel:number)=>void}} opts */
  constructor(canvas, { vertical = false, onChange = null } = {}) {
    this.cv = canvas;
    this.g = canvas.getContext('2d');
    this.vertical = vertical;
    this.onChange = onChange;

    this.minimum = 0;
    this.maximum = 100;
    this.selection = 0;
    this.thumb = 10;
    this.increment = 1;
    this.pageIncrement = 10;

    this.dragging = false;
    this.dragOffset = 0;
    this.thumbHovered = false;
    this._repeatTimer = null;

    canvas.addEventListener('mousedown', (e) => this._onMouseDown(e));
    canvas.addEventListener('dblclick', (e) => this._onDoubleClick(e));
    canvas.addEventListener('mousemove', (e) => this._onMouseMove(e));
    canvas.addEventListener('mouseleave', () => { this.thumbHovered = false; this.redraw(); });
    canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
    window.addEventListener('mouseup', () => this._onMouseUp());
    window.addEventListener('mousemove', (e) => { if (this.dragging) this._onDragMove(e); });
  }

  // ----- API (mirrors Java FlatScrollbar / SWT.Slider) -----
  setMinimum(v) { if (v < this.maximum) { this.minimum = v; this._clamp(); this.redraw(); } }
  setMaximum(v) { if (v > this.minimum) { this.maximum = v; this._clamp(); this.redraw(); } }
  setThumb(v) { this.thumb = Math.max(1, Math.min(this.maximum - this.minimum, v)); this._clamp(); this.redraw(); }
  setIncrement(v) { this.increment = Math.max(1, v); }
  setPageIncrement(v) { this.pageIncrement = Math.max(1, v); }
  getSelection() { return this.selection; }
  getThumb() { return this.thumb; }
  getMaximum() { return this.maximum; }

  setSelection(v) {
    const clamped = this._clampVal(v, this.minimum, this.maximum - this.thumb);
    if (clamped === this.selection) return;
    this.selection = clamped;
    this.redraw();
  }

  // ----- geometry -----
  _size() {
    // The backing-store px must equal the CONTENT-box px the bitmap actually
    // renders into — clientWidth/clientHeight INCLUDE padding, so a padded axis
    // (the horizontal scrollbar carries padding-right so it never underlaps the
    // vertical gutter) would size the bitmap to the border box and the browser
    // would compress it into the narrower content box. That compression shifts the
    // drawn track/thumb/arrows left of the raw mouse coordinate, so the hit-test
    // (which reads the same content box origin in _axisPx) lands ~one arrow-width
    // off on the horizontal bar. Subtract the padding to keep draw == hit space.
    const cs = getComputedStyle(this.cv);
    const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
    const padY = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
    const w = Math.max(0, (this.cv.clientWidth || this.cv.width) - padX);
    const h = Math.max(0, (this.cv.clientHeight || this.cv.height) - padY);
    return { w, h };
  }

  _trackPixels() {
    const { w, h } = this._size();
    return (this.vertical ? h : w) - 2 * ARROW_SIZE;
  }

  /** [x, y, w, h] of the thumb rectangle (Java thumbRect). */
  _thumbRect() {
    const { w, h } = this._size();
    const trackPx = this._trackPixels();
    if (trackPx <= 0) return [0, 0, 0, 0];
    const range = Math.max(1, this.maximum - this.minimum);
    let thumbPx = Math.max(MIN_THUMB_PX, Math.round(this.thumb / range * trackPx));
    thumbPx = Math.min(trackPx, thumbPx);
    const travelPx = trackPx - thumbPx;
    const maxSel = this.maximum - this.thumb - this.minimum;
    const posPx = maxSel <= 0 ? 0 : Math.round((this.selection - this.minimum) / maxSel * travelPx);
    if (this.vertical) return [1, ARROW_SIZE + posPx, w - 2, thumbPx];
    return [ARROW_SIZE + posPx, 1, thumbPx, h - 2];
  }

  // ----- paint -----
  redraw() {
    const { w, h } = this._size();
    if (w <= 0 || h <= 0) return;
    if (this.cv.width !== w) this.cv.width = w;
    if (this.cv.height !== h) this.cv.height = h;
    const g = this.g;
    g.save();
    // Track + 1 px border.
    g.fillStyle = 'rgb(40,40,40)';
    g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgb(20,20,20)';
    g.strokeRect(0.5, 0.5, w - 1, h - 1);
    // Thumb (rounded).
    const tr = this._thumbRect();
    g.fillStyle = (this.thumbHovered || this.dragging) ? 'rgb(200,200,200)' : 'rgb(160,160,160)';
    this._roundRect(g, tr[0], tr[1], tr[2], tr[3], 4);
    g.fill();
    // End arrows.
    const atStart = this.selection <= this.minimum;
    const atEnd = this.selection >= this.maximum - this.thumb;
    g.fillStyle = atStart ? 'rgb(90,90,90)' : 'rgb(200,200,200)';
    this._drawArrow(g, w, h, true);
    g.fillStyle = atEnd ? 'rgb(90,90,90)' : 'rgb(200,200,200)';
    this._drawArrow(g, w, h, false);
    g.restore();
  }

  _roundRect(g, x, y, w, h, r) {
    if (w <= 0 || h <= 0) return;
    const rr = Math.min(r, w / 2, h / 2);
    g.beginPath();
    g.moveTo(x + rr, y);
    g.arcTo(x + w, y, x + w, y + h, rr);
    g.arcTo(x + w, y + h, x, y + h, rr);
    g.arcTo(x, y + h, x, y, rr);
    g.arcTo(x, y, x + w, y, rr);
    g.closePath();
  }

  /** Triangle in the head (start) / tail arrow cell — ▲▼ vertical, ◀▶ horizontal. */
  _drawArrow(g, w, h, start) {
    const half = Math.trunc(ARROW_SIZE / 3);
    let pts;
    if (this.vertical) {
      const cx = Math.trunc(w / 2);
      const cy = start ? Math.trunc(ARROW_SIZE / 2) : h - Math.trunc(ARROW_SIZE / 2);
      pts = start
        ? [cx, cy - half, cx - half, cy + half, cx + half, cy + half]
        : [cx - half, cy - half, cx + half, cy - half, cx, cy + half];
    } else {
      const cx = start ? Math.trunc(ARROW_SIZE / 2) : w - Math.trunc(ARROW_SIZE / 2);
      const cy = Math.trunc(h / 2);
      pts = start
        ? [cx + half, cy - half, cx + half, cy + half, cx - half, cy]
        : [cx - half, cy - half, cx - half, cy + half, cx + half, cy];
    }
    g.beginPath();
    g.moveTo(pts[0], pts[1]);
    g.lineTo(pts[2], pts[3]);
    g.lineTo(pts[4], pts[5]);
    g.closePath();
    g.fill();
  }

  // ----- mouse -----
  _axisPx(e) {
    // Map to CONTENT-box-relative px so it matches the drawn geometry (which _size
    // sizes to the content box). getBoundingClientRect spans the border box, so
    // subtract the leading padding to land in the same space as redraw/_thumbRect.
    const rect = this.cv.getBoundingClientRect();
    const cs = getComputedStyle(this.cv);
    if (this.vertical) return e.clientY - rect.top - (parseFloat(cs.paddingTop) || 0);
    return e.clientX - rect.left - (parseFloat(cs.paddingLeft) || 0);
  }

  _onMouseDown(e) {
    if (e.button !== 0) return;
    const { w, h } = this._size();
    const axisPx = this._axisPx(e);
    const trackEnd = (this.vertical ? h : w) - ARROW_SIZE;
    if (axisPx < ARROW_SIZE) { this._stepBy(-this.increment); this._startArrowRepeat(-this.increment); return; }
    if (axisPx >= trackEnd) { this._stepBy(this.increment); this._startArrowRepeat(this.increment); return; }
    const tr = this._thumbRect();
    const thumbStart = this.vertical ? tr[1] : tr[0];
    const thumbEnd = thumbStart + (this.vertical ? tr[3] : tr[2]);
    if (axisPx >= thumbStart && axisPx < thumbEnd) {
      this.dragging = true;
      this.dragOffset = axisPx - thumbStart;
    } else if (axisPx < thumbStart) {
      this._stepBy(-this.pageIncrement); this._startTrackRepeat(-this.pageIncrement, axisPx);
    } else {
      this._stepBy(this.pageIncrement); this._startTrackRepeat(this.pageIncrement, axisPx);
    }
  }

  _onMouseUp() {
    this.dragging = false;
    this._stopRepeat();
  }

  _onDoubleClick(e) {
    if (e.button !== 0) return;
    const tr = this._thumbRect();
    const thumbStart = this.vertical ? tr[1] : tr[0];
    const thumbSize = this.vertical ? tr[3] : tr[2];
    const axisPx = this._axisPx(e);
    if (axisPx < thumbStart || axisPx >= thumbStart + thumbSize) return;
    const maxSel = this.maximum - this.thumb - this.minimum;
    if (maxSel <= 0) return;
    const center = this.minimum + Math.trunc(maxSel / 2);
    if (center === this.selection) return;
    this.selection = center;
    this.redraw();
    this._fire();
  }

  _onMouseMove(e) {
    if (this.dragging) return;   // _onDragMove (window-level) handles drags
    const tr = this._thumbRect();
    const thumbStart = this.vertical ? tr[1] : tr[0];
    const thumbSize = this.vertical ? tr[3] : tr[2];
    const axisPx = this._axisPx(e);
    const nowHovered = axisPx >= thumbStart && axisPx < thumbStart + thumbSize;
    if (nowHovered !== this.thumbHovered) { this.thumbHovered = nowHovered; this.redraw(); }
  }

  _onDragMove(e) {
    const trackPx = this._trackPixels();
    if (trackPx <= 0) return;
    const range = Math.max(1, this.maximum - this.minimum);
    let thumbPx = Math.max(MIN_THUMB_PX, Math.round(this.thumb / range * trackPx));
    thumbPx = Math.min(trackPx, thumbPx);
    const travelPx = Math.max(1, trackPx - thumbPx);
    let target = this._axisPx(e) - this.dragOffset - ARROW_SIZE;
    if (target < 0) target = 0;
    if (target > travelPx) target = travelPx;
    const maxSel = this.maximum - this.thumb - this.minimum;
    const newSel = this.minimum + Math.round(target / travelPx * maxSel);
    if (newSel !== this.selection) {
      this.selection = this._clampVal(newSel, this.minimum, this.maximum - this.thumb);
      this.redraw();
      this._fire();
    }
  }

  _onWheel(e) {
    e.preventDefault();
    if (e.deltaY === 0) return;
    const dir = e.deltaY < 0 ? 1 : -1;   // wheel up = selection decreases (Java)
    this._stepBy(-dir * this.increment);
  }

  // ----- helpers -----
  _stepBy(delta) {
    const clamped = this._clampVal(this.selection + delta, this.minimum, this.maximum - this.thumb);
    if (clamped === this.selection) return;
    this.selection = clamped;
    this.redraw();
    this._fire();
  }

  _clamp() { this.selection = this._clampVal(this.selection, this.minimum, this.maximum - this.thumb); }

  _clampVal(v, lo, hi) {
    if (hi < lo) return lo;
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
  }

  _startArrowRepeat(delta) {
    this._stopRepeat();
    const tick = () => { this._stepBy(delta); this._repeatTimer = setTimeout(tick, ARROW_REPEAT_MS); };
    this._repeatTimer = setTimeout(tick, REPEAT_DELAY_MS);
  }

  _startTrackRepeat(delta, axisPx) {
    this._stopRepeat();
    const tick = () => {
      if (this._thumbCovers(axisPx)) { this._stopRepeat(); return; }
      const before = this.selection;
      this._stepBy(delta);
      if (this.selection === before) { this._stopRepeat(); return; }   // hit an end
      this._repeatTimer = setTimeout(tick, TRACK_REPEAT_MS);
    };
    this._repeatTimer = setTimeout(tick, REPEAT_DELAY_MS);
  }

  _thumbCovers(axisPx) {
    const tr = this._thumbRect();
    const start = this.vertical ? tr[1] : tr[0];
    const size = this.vertical ? tr[3] : tr[2];
    return axisPx >= start && axisPx < start + size;
  }

  _stopRepeat() {
    if (this._repeatTimer != null) { clearTimeout(this._repeatTimer); this._repeatTimer = null; }
  }

  _fire() { if (this.onChange) this.onChange(this.selection); }
}
