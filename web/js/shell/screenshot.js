/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Phonalyser web - generic pane->Canvas screenshot compositor + the screenshot dialog.
// Mirrors gui/common/ScreenshotDialog + ScreenshotOverlay. The compositor is pane-agnostic;
// the scope-specific glue (prep/compose/render) is injected by the caller.

// Guarded: fft-tab-control.js imports this module for the renderer registry, and the headless
// node e2e tests import fft-tab-control - under node there is no window at module load.
const $ = typeof window !== 'undefined' ? window.jQuery : undefined;

// Java ScreenshotDialog.PRESETS - quick-fill output resolutions.
export const SHOT_PRESETS = [
  [1024, 768], [1280, 720], [1280, 1024], [1366, 768],
  [1600, 900], [1920, 1080], [1920, 1200], [1920, 1280],
];

// Offscreen but ATTACHED host for the clone (the clone-and-repaint algorithm requires
// the clone be laid out - getComputedStyle/clientHeight only resolve for an attached subtree).
// Positioned far off-screen (left:-10000px), behind everything (z-index:-1) and
// inert (pointer-events:none) so it never flashes. It must NOT use visibility:hidden:
// that inherits into the clone and paintCloneToCanvas skips visibility:hidden elements,
// which would render an all-black screenshot.
let shotCloneHost = null;
export function ensureShotCloneHost() {
  if (shotCloneHost) return shotCloneHost;
  shotCloneHost = document.createElement('div');
  shotCloneHost.style.cssText =
    'position:fixed;left:-10000px;top:0;z-index:-1;pointer-events:none;background:#000;';
  document.body.appendChild(shotCloneHost);
  return shotCloneHost;
}

// Per-canvas offscreen RE-RENDERERS. A pane whose canvas bakes its text into the
// bitmap (the FFT #spec: axis tick labels, harmonic labels, the readout/THD table)
// registers a renderer here; paintCloneToCanvas then re-renders that canvas FROM
// SCRATCH at the reflowed rect's size instead of blitting (= scaling/distorting) the
// live bitmap. Mirrors Java AbstractPane.renderOffscreen (:214-254), which lays a
// FRESH pane clone out at the target size in a hidden Shell and prints it at native
// pixels - no bitmap scaling (FftPane.createSnapshotClone :495-500 rebuilds a fresh
// FftView for it). The scope needs none of this: its text lives in the DOM overlay
// and its trace canvas is resolution-free lines.
const shotCanvasRenderers = new Map();

/** Registers `render(cssW, cssH) => canvas` as the offscreen re-renderer for the clone
 *  canvas with DOM id {@code id}. The renderer returns a canvas whose BACKING bitmap is
 *  the finished image for a cssW×cssH CSS-px box (the renderer applies its own
 *  devicePixelRatio backing exactly like its live paint, so the bitmap lands 1:1 in
 *  output pixels under paintCloneToCanvas's dpr paint scale). */
export function registerShotCanvasRenderer(id, render) { shotCanvasRenderers.set(id, render); }

// Persistent ATTACHED offscreen canvas for the re-renderers: FftView.render sizes
// itself off clientWidth/Height, which only resolve for an attached, laid-out element
// (the same constraint as the clone host above - but a SEPARATE host, because
// clonePaneForShot clears the clone host each capture). One shared canvas suffices:
// renders are synchronous and sequential (paintCloneToCanvas walks one element at a time).
let shotRenderCanvas = null;
export function ensureShotRenderCanvas(cssW, cssH) {
  if (!shotRenderCanvas) {
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:-10000px;top:0;z-index:-1;pointer-events:none;';
    shotRenderCanvas = document.createElement('canvas');
    host.appendChild(shotRenderCanvas);
    document.body.appendChild(host);
  }
  shotRenderCanvas.style.width = cssW + 'px';
  shotRenderCanvas.style.height = cssH + 'px';
  return shotRenderCanvas;
}

/** GENERIC (pane-agnostic): deep-clones `paneEl` into the offscreen ATTACHED host
 *  laid out at the TARGET `w`×`h` (any aspect), then runs the per-pane callback
 *  `prep(clone)` for pane-specific tweaks before the final reflow. The clone keeps
 *  its CSS classes, so the live stylesheet lays it out exactly like the real pane -
 *  paintCloneToCanvas reads each element's getComputedStyle + rect off the attached
 *  clone (no per-element style inlining needed). The clone's flex column reflows to
 *  the target box so the canvas-wrap grows/shrinks naturally - nothing is squashed in
 *  one direction. The SAME function will serve the FFT and FreqResp panes (each with
 *  its own `prep`). Returns the laid-out clone (still attached). */
export function clonePaneForShot(paneEl, w, h, prep) {
  const host = ensureShotCloneHost();
  host.style.width = w + 'px';
  host.style.height = h + 'px';
  const clone = paneEl.cloneNode(true);
  // Pane-specific edits (collapse tabs, strip chrome, swap scrollbars, ...) live in the
  // caller's prep callback - this function makes NO assumptions about the pane.
  if (prep) prep(clone);
  // Lay the clone out at the TARGET box so its flex column reflows to that aspect.
  clone.removeAttribute('id');
  clone.style.width = w + 'px';
  clone.style.height = h + 'px';
  clone.style.display = 'flex';
  clone.style.flexDirection = 'column';
  host.textContent = '';
  host.appendChild(clone);
  return clone;
}

// A computed colour is "absent" (transparent) when empty or fully transparent - such
// a background/border must NOT be painted (else every transparent box stamps a black
// rectangle over the trace).
export function shotColorIsTransparent(c) {
  if (!c) return true;
  const s = c.trim();
  if (s === 'transparent' || s === 'none') return true;
  // rgba(...) with a 0 alpha - the 4th component after the last comma.
  const m = /^rgba?\(([^)]+)\)$/i.exec(s);
  if (m) {
    const parts = m[1].split(',');
    if (parts.length >= 4 && parseFloat(parts[3]) === 0) return true;
  }
  return false;
}

// First radius value (px) of a `border-radius` computed string (e.g. "4px" or
// "4px 4px 0 0" -> 4). 0 when none.
export function shotFirstRadiusPx(br) {
  if (!br) return 0;
  const v = parseFloat(br);
  return v > 0 ? v : 0;
}

// Traces the rect r (left/top/width/height) as a (rounded, when rad>0) path on ctx,
// ready for fill or stroke. Uses ctx.roundRect when available.
export function shotRectPath(ctx, r, rad) {
  ctx.beginPath();
  if (rad > 0 && typeof ctx.roundRect === 'function') {
    ctx.roundRect(r.left, r.top, r.width, r.height, Math.min(rad, r.width / 2, r.height / 2));
  } else {
    ctx.rect(r.left, r.top, r.width, r.height);
  }
}

// The DIRECT, non-empty text of an element (its own text nodes only, not descendants'
// - those are visited and drawn on their own as the walk recurses). '' when none.
export function shotDirectText(el) {
  let t = '';
  for (const n of el.childNodes) {
    if (n.nodeType === Node.TEXT_NODE) t += n.nodeValue;
  }
  return t.replace(/\s+/g, ' ').trim();
}

/** Preloads every unique external <img> src in the clone (the .lr-tools SVG icons) into
 *  a decoded Image so paintCloneToCanvas can drawImage them synchronously. Returns a
 *  Map<src, Image> of the ones that loaded (failed loads are simply omitted). */
export async function preloadCloneIcons(clone) {
  const srcs = new Set();
  clone.querySelectorAll('img').forEach((im) => { const s = im.getAttribute('src') || ''; if (s) srcs.add(s); });
  const map = new Map();
  await Promise.all(Array.from(srcs).map((src) => new Promise((resolve) => {
    const img = new Image();
    img.onload = () => { map.set(src, img); resolve(); };
    img.onerror = () => resolve();   // skip an icon that won't load
    img.src = src;
  })));
  return map;
}

/** GENERIC (pane-agnostic): paints the laid-out clone tree onto a fresh `outW`×`outH`
 *  2d canvas by walking it in DOM order (so z-order is correct) and drawing each
 *  element off its own rect (its getBoundingClientRect minus the clone root's) and
 *  getComputedStyle. Chrome elements (backgrounds, borders, single-line text, SVG
 *  icons) are drawn at their OWN css-pixel size - nothing here is multiplied by the
 *  target or by the display pixel density. Only <canvas> elements SCALE: for each, the
 *  matching LIVE on-screen bitmap returned by `liveCanvasFor(cloneCanvas)` is blitted
 *  into the reflowed rect. `icons` is the preloaded Map<src,Image>. The SAME function
 *  will serve the FFT and FreqResp panes (each with its own `liveCanvasFor`). */
export function paintCloneToCanvas(clone, outW, outH, liveCanvasFor, icons) {
  const out = document.createElement('canvas');
  out.width = outW; out.height = outH;
  const ctx = out.getContext('2d');
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, outW, outH);   // pane background

  const root = clone.getBoundingClientRect();
  // The clone is laid out at root.width×root.height; the output canvas is outW×outH. The
  // "enlarge elements" dpr fix lays the clone out at outW/dpr, so scaling the paint to
  // fill the canvas draws every element (read at its CSS rect) ×dpr = its device size
  // while the file stays outW×outH. scale == 1 when clone == canvas (dpr 1, or FFT later
  // with no reduction). The opaque background was already filled at full canvas size.
  const sx = root.width > 0 ? outW / root.width : 1;
  const sy = root.height > 0 ? outH / root.height : 1;
  if (sx !== 1 || sy !== 1) ctx.scale(sx, sy);

  // Paints an element's children in CSS-STACKING order, not raw DOM order: a
  // positioned child with a positive z-index (the overlays - .lr-tools z-index:3,
  // the measurement table, the slider overlay) must paint AFTER the in-flow #scope
  // canvas, whose opaque grid bitmap would otherwise occlude it. Within each group
  // DOM order is preserved (stable).
  const paintChildren = (el) => {
    const kids = Array.from(el.children);
    const z = (c) => { const s = window.getComputedStyle(c); const zi = parseInt(s.zIndex, 10);
      return (s.position !== 'static' && Number.isFinite(zi) && zi > 0) ? zi : 0; };
    for (const c of kids) if (z(c) === 0) paint(c);
    for (const c of kids) if (z(c) > 0) paint(c);
  };

  const paint = (el) => {
    if (el.nodeType !== Node.ELEMENT_NODE) return;
    const cs = window.getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return;
    const b = el.getBoundingClientRect();
    const r = { left: b.left - root.left, top: b.top - root.top, width: b.width, height: b.height };
    if (r.width <= 0 || r.height <= 0) {
      // No box of its own (e.g. a wrapper collapsed to 0) - still recurse for children.
      paintChildren(el);
      return;
    }
    const op = parseFloat(cs.opacity);
    ctx.save();
    if (op >= 0 && op < 1) ctx.globalAlpha = op;
    const rad = shotFirstRadiusPx(cs.borderRadius);

    // Background fill (honouring border-radius).
    const bg = cs.backgroundColor;
    if (!shotColorIsTransparent(bg)) {
      shotRectPath(ctx, r, rad);
      ctx.fillStyle = bg;
      ctx.fill();
    }
    // Borders. Three shapes share the CSS border box here:
    //  1. A NORMAL element with 4 equal opaque borders -> one rounded stroke (cheap, honours
    //     border-radius). 2. A 1-D chrome line - a horizontal element with only border-TOP
    //     (dashed channel-offset / trigger-LEVEL lines, the tab-strip top border) or a
    //     VERTICAL element with only border-LEFT (the dashed trigger-POSITION line); each
    //     edge is stroked individually so a single-side border is never dropped, dashed
    //     where the CSS is. 3. A CSS border-TRIANGLE handle: a ~0-content-box element whose
    //     one opaque side + two transparent adjacent sides form a triangle (slider handles).
    const side = (name) => ({
      w: parseFloat(cs['border' + name + 'Width']) || 0,
      style: cs['border' + name + 'Style'],
      color: cs['border' + name + 'Color'],
    });
    const sides = { Top: side('Top'), Right: side('Right'), Bottom: side('Bottom'), Left: side('Left') };
    const opaque = (s) => s.w > 0 && s.style !== 'none' && !shotColorIsTransparent(s.color);
    const opaqueSides = ['Top', 'Right', 'Bottom', 'Left'].filter((n) => opaque(sides[n]));

    // CSS triangle: ~0 content box + exactly one opaque side with both adjacent sides
    // present-but-transparent. Fill the triangle the opaque + transparent widths form.
    const triangle = (cs.boxSizing !== 'border-box')
      ? (parseFloat(cs.width) <= 2 || parseFloat(cs.height) <= 2)
      : (r.width - sides.Left.w - sides.Right.w <= 2 || r.height - sides.Top.w - sides.Bottom.w <= 2);
    let drewTriangle = false;
    if (triangle && opaqueSides.length === 1) {
      const solid = opaqueSides[0];
      const adj = (solid === 'Top' || solid === 'Bottom') ? ['Left', 'Right'] : ['Top', 'Bottom'];
      const aw0 = sides[adj[0]].w, aw1 = sides[adj[1]].w;
      // A border-triangle points AWAY from its opaque side: the opaque side is the BASE,
      // the apex is on the opposite edge between the two transparent borders.
      if ((aw0 > 0 || aw1 > 0)) {
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        ctx.beginPath();
        if (solid === 'Top') { ctx.moveTo(r.left, r.top); ctx.lineTo(r.left + r.width, r.top); ctx.lineTo(cx, r.top + sides.Top.w); }
        else if (solid === 'Bottom') { ctx.moveTo(r.left, r.top + r.height); ctx.lineTo(r.left + r.width, r.top + r.height); ctx.lineTo(cx, r.top + r.height - sides.Bottom.w); }
        else if (solid === 'Left') { ctx.moveTo(r.left, r.top); ctx.lineTo(r.left, r.top + r.height); ctx.lineTo(r.left + sides.Left.w, cy); }
        else { ctx.moveTo(r.left + r.width, r.top); ctx.lineTo(r.left + r.width, r.top + r.height); ctx.lineTo(r.left + r.width - sides.Right.w, cy); }
        ctx.closePath();
        ctx.fillStyle = sides[solid].color;
        ctx.fill();
        drewTriangle = true;
      }
    }

    if (!drewTriangle && opaqueSides.length === 4
        && sides.Top.w === sides.Right.w && sides.Top.w === sides.Bottom.w && sides.Top.w === sides.Left.w
        && sides.Top.style !== 'dashed' && sides.Top.style !== 'dotted') {
      // Uniform box: one inset rounded stroke (the original cheap path).
      const bw = sides.Top.w;
      const ir = { left: r.left + bw / 2, top: r.top + bw / 2, width: r.width - bw, height: r.height - bw };
      shotRectPath(ctx, ir, Math.max(0, rad - bw / 2));
      ctx.lineWidth = bw;
      ctx.strokeStyle = sides.Top.color;
      ctx.stroke();
    } else if (!drewTriangle) {
      // Stroke each opaque edge as a line along the middle of that edge's width. Covers a
      // lone border-top (horizontal dashed lines, tab top) AND a lone border-left
      // (vertical dashed trigger-position line) AND mixed boxes.
      for (const n of opaqueSides) {
        const s = sides[n];
        ctx.beginPath();
        const off = s.w / 2;
        if (n === 'Top') { ctx.moveTo(r.left, r.top + off); ctx.lineTo(r.left + r.width, r.top + off); }
        else if (n === 'Bottom') { ctx.moveTo(r.left, r.top + r.height - off); ctx.lineTo(r.left + r.width, r.top + r.height - off); }
        else if (n === 'Left') { ctx.moveTo(r.left + off, r.top); ctx.lineTo(r.left + off, r.top + r.height); }
        else { ctx.moveTo(r.left + r.width - off, r.top); ctx.lineTo(r.left + r.width - off, r.top + r.height); }
        ctx.lineWidth = s.w;
        ctx.strokeStyle = s.color;
        if (s.style === 'dashed') ctx.setLineDash([s.w * 2, s.w * 2]);
        else if (s.style === 'dotted') ctx.setLineDash([s.w, s.w]);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }

    const tag = el.tagName;
    if (tag === 'IMG') {
      const ico = icons.get(el.getAttribute('src') || '');
      if (ico) {
        try { ctx.drawImage(ico, r.left, r.top, r.width, r.height); } catch (e) { /* undrawable */ }
      }
    } else if (tag === 'CANVAS') {
      // The trace/grid - the ONLY thing that scales with the target. A canvas with a
      // REGISTERED re-renderer (the FFT #spec, whose text is baked into the bitmap) is
      // re-rendered from scratch at this reflowed rect's size so NOTHING in it scales
      // (Java AbstractPane.renderOffscreen); every other canvas blits the matching
      // LIVE on-screen bitmap (resolved by the caller's mapper) into its reflowed rect.
      const rerender = shotCanvasRenderers.get(el.id);
      const live = rerender ? rerender(r.width, r.height) : liveCanvasFor(el);
      if (live && live.width > 0 && live.height > 0) {
        try { ctx.drawImage(live, r.left, r.top, r.width, r.height); } catch (e) { /* tainted/empty */ }
      }
      // Record the main #scope canvas's OUTPUT-pixel rect (its clone-CSS rect × the paint
      // scale) so callers can centre the watermark on the live scope view, not the pane.
      if (el.id === 'scope') {
        out.scopeRect = { left: r.left * sx, top: r.top * sy, width: r.width * sx, height: r.height * sy };
      }
    } else {
      // Text: only an element's OWN direct text (descendant text is drawn as the walk
      // reaches those descendants).
      const text = shotDirectText(el);
      if (text) {
        const style = cs.fontStyle && cs.fontStyle !== 'normal' ? cs.fontStyle + ' ' : '';
        const weight = cs.fontWeight && cs.fontWeight !== 'normal' ? cs.fontWeight + ' ' : '';
        ctx.font = `${style}${weight}${cs.fontSize} ${cs.fontFamily}`;
        ctx.fillStyle = cs.color;
        ctx.textBaseline = 'middle';
        const cy = r.top + r.height / 2;
        const padL = parseFloat(cs.paddingLeft) || 0;
        const padR = parseFloat(cs.paddingRight) || 0;
        const align = cs.textAlign;
        let x;
        if (align === 'right' || align === 'end') { ctx.textAlign = 'right'; x = r.left + r.width - padR; }
        else if (align === 'center') { ctx.textAlign = 'center'; x = r.left + r.width / 2; }
        else { ctx.textAlign = 'left'; x = r.left + padL; }
        const ls = parseFloat(cs.letterSpacing);
        if (ls > 0 && 'letterSpacing' in ctx) { try { ctx.letterSpacing = cs.letterSpacing; } catch (e) { /* unsupported */ } }
        ctx.fillText(text, x, cy);
        if ('letterSpacing' in ctx) { try { ctx.letterSpacing = '0px'; } catch (e) { /* unsupported */ } }
      }
    }
    // CSS pseudo-elements (::before / ::after) - the walk only visits real nodes, so a
    // pseudo (e.g. the channel-active LED `.led-sm::before`: a red border-radius:50%
    // circle) would be missing. Draw any pseudo that has a sized box + a non-transparent
    // background. Static/inline pseudos sit centred in the parent (the LED's inline-flex
    // parent centres it); absolute pseudos honour top/left/right.
    for (const sel of ['::before', '::after']) {
      const ps = window.getComputedStyle(el, sel);
      if (!ps || ps.content === 'none' || ps.content === 'normal' || ps.content === '') continue;
      const pw = parseFloat(ps.width) || 0, ph = parseFloat(ps.height) || 0;
      const hasBg = pw > 0 && ph > 0 && !shotColorIsTransparent(ps.backgroundColor);
      const bw = (s) => parseFloat(ps['border' + s + 'Width']) || 0;
      const op = (s) => bw(s) > 0 && ps['border' + s + 'Style'] !== 'none' && !shotColorIsTransparent(ps['border' + s + 'Color']);
      const hasBorder = op('Top') || op('Right') || op('Bottom') || op('Left');
      if (!hasBg && !hasBorder) continue;   // nothing visible (e.g. a content-only pseudo)
      let px, py;
      if (ps.position === 'absolute') {
        const l = parseFloat(ps.left), t = parseFloat(ps.top), rt = parseFloat(ps.right);
        px = r.left + (Number.isFinite(l) ? l : (Number.isFinite(rt) ? r.width - rt - pw : 0));
        py = r.top + (Number.isFinite(t) ? t : 0);
      } else {
        px = r.left + (r.width - pw) / 2;
        py = r.top + (r.height - ph) / 2;
      }
      ctx.save();
      // Replicate the pseudo's CSS transform (the slanted tab separators are skewX'd) about
      // its transform-origin, so a skewed border line draws at the same angle as on screen.
      if (ps.transform && ps.transform !== 'none') {
        const mm = /matrix\(([^)]+)\)/.exec(ps.transform);
        if (mm) {
          const m = mm[1].split(',').map(parseFloat);
          const o = (ps.transformOrigin || '0px 0px').split(' ').map(parseFloat);
          const ox = px + (Number.isFinite(o[0]) ? o[0] : 0), oy = py + (Number.isFinite(o[1]) ? o[1] : 0);
          ctx.translate(ox, oy); ctx.transform(m[0], m[1], m[2], m[3], m[4], m[5]); ctx.translate(-ox, -oy);
        }
      }
      if (hasBg) {   // filled box (e.g. the channel-active LED circle)
        const prr = ps.borderRadius && ps.borderRadius.includes('%')
          ? Math.min(pw, ph) * (parseFloat(ps.borderRadius) / 100)
          : shotFirstRadiusPx(ps.borderRadius);
        shotRectPath(ctx, { left: px, top: py, width: pw, height: ph }, prr);
        ctx.fillStyle = ps.backgroundColor;
        ctx.fill();
      }
      if (hasBorder) {   // each opaque edge as a line - captures 0-width/0-height separator lines
        const x2 = px + pw, y2 = py + ph;
        const line = (ax, ay, bx, by, w, col) => { ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.lineWidth = w; ctx.strokeStyle = col; ctx.stroke(); };
        if (op('Top')) line(px, py, x2, py, bw('Top'), ps.borderTopColor);
        if (op('Bottom')) line(px, y2, x2, y2, bw('Bottom'), ps.borderBottomColor);
        if (op('Left')) line(px, py, px, y2, bw('Left'), ps.borderLeftColor);
        if (op('Right')) line(x2, py, x2, y2, bw('Right'), ps.borderRightColor);
      }
      ctx.restore();
    }
    ctx.restore();
    paintChildren(el);
  };

  paint(clone);
  return out;
}

/** The screenshot dialog (Java gui/common/ScreenshotDialog). Pane-agnostic: the
 *  scope/FFT/FreqResp specifics (render, native size, persisted size) are INJECTED
 *  via the constructor config so the SAME dialog serves every pane. bind() wires the
 *  six jQuery handlers on the shared #shot* DOM ids. */
export class ScreenshotDialog {
  /**
   * @param {object} cfg
   * @param {object} cfg.modal the bootstrap Modal instance for #shotModal
   * @param {string} cfg.openBtn selector for the camera button (e.g. '#scopeShot')
   * @param {(comment:string,w:number,h:number,mime:string)=>Promise<Blob>} cfg.renderShot renders the pane to a Blob
   * @param {()=>{w:number,h:number}} cfg.nativeSize on-screen pane size (aspect + fallback seed)
   * @param {()=>{w:number,h:number}} cfg.seedSize persisted last size (0 => fall back to nativeSize)
   * @param {(w:number,h:number)=>void} cfg.persistSize remembers the chosen size per pane
   * @param {Function} cfg.saveFile the io saveFile fn
   * @param {(msg:string)=>void} cfg.status status-line writer
   */
  constructor({ modal, openBtn, renderShot, nativeSize, seedSize, persistSize, saveFile, status }) {
    this.modal = modal;
    this.openBtn = openBtn;
    this.renderShot = renderShot;
    this.nativeSize = nativeSize;
    this.seedSize = seedSize;
    this.persistSize = persistSize;
    // One shared modal can serve MULTIPLE panes (addPane); keep the initial (scope) pane config
    // so its camera button re-activates it after another pane (FFT/FreqResp) was opened.
    this._initialPane = { openBtn, renderShot, nativeSize, seedSize, persistSize };
    this.saveFile = saveFile;
    this.status = status;
    // Aspect-coupling guard (Java suppressUpdate[]) so writing one field's partner
    // doesn't re-trigger the other field's listener.
    this.shotSuppressAspect = false;
  }

  /** Chosen output size from the W/H fields, falling back to native (Java readSize). */
  shotSize() {
    const n = this.nativeSize();
    const w = parseInt($('#shotWidth').val(), 10);
    const h = parseInt($('#shotHeight').val(), 10);
    return { w: w > 0 ? w : n.w, h: h > 0 ? h : n.h };
  }

  /** Attaches the six dialog handlers (open / W / H / preset / save / copy). */
  bind() {
    this._wireOpenBtn(this._initialPane);

    // Aspect-ratio coupling between the W/H fields (Java widthText/heightText Modify
    // listeners) - locks to the native canvas ratio when "Keep aspect" is on.
    $('#shotWidth').on('input', () => {
      if (this.shotSuppressAspect || !$('#shotKeepAspect').is(':checked')) return;
      const n = this.nativeSize(), w = parseInt($('#shotWidth').val(), 10);
      if (!(w > 0) || !(n.w > 0)) return;
      this.shotSuppressAspect = true;
      $('#shotHeight').val(Math.round(w * n.h / n.w));
      this.shotSuppressAspect = false;
    });
    $('#shotHeight').on('input', () => {
      if (this.shotSuppressAspect || !$('#shotKeepAspect').is(':checked')) return;
      const n = this.nativeSize(), h = parseInt($('#shotHeight').val(), 10);
      if (!(h > 0) || !(n.h > 0)) return;
      this.shotSuppressAspect = true;
      $('#shotWidth').val(Math.round(h * n.w / n.h));
      this.shotSuppressAspect = false;
    });
    // Preset pick writes both fields exactly (Java presetCombo Selection: preserves
    // the preset's W×H even with Keep-aspect on, via suppressUpdate).
    $('#shotPreset').on('change', () => {
      const v = $('#shotPreset').val();
      if (!v) return;
      const [w, h] = v.split('x').map((s) => parseInt(s, 10));
      this.shotSuppressAspect = true;
      $('#shotWidth').val(w);
      $('#shotHeight').val(h);
      this.shotSuppressAspect = false;
    });

    $('#shotSave').on('click', async () => {
      const { w, h } = this.shotSize();
      this.persistSize(w, h);   // remember the chosen size per pane
      const fmt = $('#shotFormat').val() === 'jpeg' ? 'jpeg' : 'png';
      const mime = fmt === 'jpeg' ? 'image/jpeg' : 'image/png';
      const blob = await this.renderShot($('#shotComment').val(), w, h, mime);
      if (!blob) { this.status('screenshot failed.'); return; }
      const ext = fmt === 'jpeg' ? '.jpg' : '.png';
      const res = await this.saveFile(blob, 'scope' + ext, [
        { description: fmt.toUpperCase() + ' image', accept: mime, extensions: [ext] },
      ]);
      this.modal.hide();
      if (res.saved) this.status('saved ' + res.name);
    });
    $('#shotCopy').on('click', async () => {
      const { w, h } = this.shotSize();
      this.persistSize(w, h);   // remember the chosen size per pane
      // Clipboard images are always PNG (ClipboardItem image/png is universally supported).
      const blob = await this.renderShot($('#shotComment').val(), w, h, 'image/png');
      if (!blob) return;
      try {
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
        this.modal.hide();
      } catch (e) { /* clipboard write rejected (no permission / unsupported) */ }
    });
  }

  /** Make {@code p}'s renderShot + size callbacks the ACTIVE ones (Save/Copy read this.*). */
  _activate(p) {
    this.renderShot = p.renderShot;
    this.nativeSize = p.nativeSize;
    this.seedSize = p.seedSize;
    this.persistSize = p.persistSize;
  }

  /** Seed the W/H fields from this pane's last-used (or native) size, (re)build the preset
   *  dropdown, and show the shared modal (Java open). */
  _open() {
    $('#shotComment').val('');
    const n = this.nativeSize();
    const seed = this.seedSize();
    const sw = seed.w, sh = seed.h;
    this.shotSuppressAspect = true;
    $('#shotWidth').val(sw > 0 ? sw : n.w);
    $('#shotHeight').val(sh > 0 ? sh : n.h);
    this.shotSuppressAspect = false;
    const $preset = $('#shotPreset').empty();
    $preset.append('<option value=""></option>');
    for (const [w, h] of SHOT_PRESETS) $preset.append(`<option value="${w}x${h}">${w}×${h}</option>`);
    $preset.val('');
    this.modal.show();
  }

  _wireOpenBtn(p) {
    $(p.openBtn).on('click', () => { this._activate(p); this._open(); });
  }

  /** Register an ADDITIONAL pane camera button on this SAME shared dialog (one dialog serves
   *  every pane - class contract): its openBtn activates that pane's renderShot / sizes. */
  addPane(p) { this._wireOpenBtn(p); }
}
