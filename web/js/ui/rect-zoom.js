/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.common.AbstractMeasurementView's
// rectangular-zoom section (the ZoomState memento + installRectZoom machinery,
// commit 73f6c1f): a button-1 drag on the plot selects a rectangle (min 8 px on
// both axes, clamped to the zoomable area) that is stretched to the full view on
// release, with the PREVIOUS pan/zoom pushed on a 32-deep per-view undo stack —
// exact-modifier Ctrl+Z pops it until empty. The Ctrl+Z target is the FOCUSED
// view (click-to-focus), else the HOVERED one — unless the focus sits on another
// view's host or a text-editing widget (whose own Ctrl+Z wins; Java's
// Text/Combo/Spinner exception → input/select/textarea/contenteditable here).
// The target view shows a 1-px accent border; a drag shows the accent rubber
// band. Both are canvas-drawn by drawOverlay(), called LAST in the owning view's
// paint. The view owns the semantics through the injected captureState /
// applyState / stateForRect callbacks, exchanging the uniform zoom-state
// memento ({xMin, xMax, yMin[], yMax[]} — units are the view's own; this module
// never interprets the numbers, it only stacks and returns them).
//
// Intended divergence from Java: there is no GL overlay-composite routing (the
// web renders on Canvas2D) — an overlay-only repaint is a plain full redraw via
// the injected repaintOverlay callback.

/** Rect-zoom rubber band + focused-view border colour (Java ColorRole.ACCENT
 *  0x8CFF00 — bright green, visible on light AND dark plots). */
export const RECT_ZOOM_ACCENT = '#8cff00';

// Undo-stack depth bound — beyond it the OLDEST zoom states are dropped
// (Java ZOOM_UNDO_LIMIT).
const ZOOM_UNDO_LIMIT = 32;
// Minimum selection edge (px) for a drag to count as a zoom — anything smaller
// is a plain focus click (Java ZOOM_MIN_SELECTION_PX).
const ZOOM_MIN_SELECTION_PX = 8;
// Dataset key marking an element as a measurement view's interaction host, so
// views recognise each other's hosts during focus targeting without any global
// registry (Java ZOOM_HOST_DATA_KEY widget-data key).
const HOST_DATASET_KEY = 'rectZoomHost';

/**
 * The shared rectangular-zoom machinery for one measurement view (Java
 * AbstractMeasurementView.installRectZoom + the drag/undo/overlay methods).
 * The scope / FFT / freq-resp views each own one instance and inject their
 * pixel↔value semantics.
 */
export class RectZoom {
  /**
   * @param {HTMLElement} host the interaction element (the view's canvas).
   * @param {object} cfg
   * @param {() => (object|null)} cfg.captureState the current pan/zoom as a
   *   uniform memento {xMin, xMax, yMin:number[], yMax:number[]} — pushed
   *   before every rect zoom, replayed by Ctrl+Z; null = nothing to remember
   *   (Java captureZoomState).
   * @param {(s: object) => boolean} cfg.applyState applies a memento — the
   *   single mutation gateway shared by zoom and undo. Returns whether the
   *   state was actually applied: a restore may clamp degenerate after the
   *   environment changed, and undo then skips the dead entry (Java
   *   applyZoomState).
   * @param {(sel: {x:number,y:number,w:number,h:number}) => (object|null)}
   *   cfg.stateForRect maps the committed selection rectangle (host px) to the
   *   new zoom state; null cancels the zoom (Java zoomStateForRect).
   * @param {() => ({x:number,y:number,w:number,h:number}|null)}
   *   cfg.zoomableArea the region a selection may start in and is clamped to,
   *   in host px (Java zoomableArea).
   * @param {(x:number,y:number) => boolean} [cfg.isBlockedAt] whether a
   *   selection drag may NOT start at (x, y) — views veto their interactive
   *   hit zones (Java isRectZoomBlockedAt; default: never blocked).
   * @param {() => void} cfg.repaintOverlay repaint request for a change that
   *   only affects the rect-zoom overlay (rubber band, focus border) — on
   *   Canvas2D a plain full redraw (Java requestZoomOverlayRepaint).
   * @param {boolean} [cfg.hookMouse=true] with true the machinery wires the
   *   drag to the host's own mouse events (FFT / freq-resp); the scope passes
   *   false and forwards from its pointer funnel instead (Java installRectZoom
   *   hookMouse).
   */
  constructor(host, { captureState, applyState, stateForRect, zoomableArea,
    isBlockedAt = null, repaintOverlay, hookMouse = true }) {
    this._host = host;
    this._captureState = captureState;
    this._applyState = applyState;
    this._stateForRect = stateForRect;
    this._zoomableArea = zoomableArea;
    this._isBlockedAt = isBlockedAt;
    this._repaintOverlay = repaintOverlay;
    // Undo stack of pre-zoom states; newest LAST (push/pop at the end,
    // oldest dropped from the front at the depth bound).
    this._undoStack = [];
    this._dragActive = false;
    this._dragStartX = 0;
    this._dragStartY = 0;
    this._dragCurX = 0;
    this._dragCurY = 0;
    this._hovered = false;
    if (!host || typeof document === 'undefined') return;
    // Click-to-focus host: programmatic-focus-only (no tab traversal), the
    // native focus ring replaced by the canvas-drawn 1-px accent border.
    host.tabIndex = -1;
    host.style.outline = 'none';
    host.dataset[HOST_DATASET_KEY] = '1';
    // Focus / hover tracking → overlay repaint (Java FocusIn/Out +
    // MouseEnter/Exit listeners).
    host.addEventListener('focus', () => this._repaintOverlay());
    host.addEventListener('blur', () => this._repaintOverlay());
    host.addEventListener('mouseenter', () => { this._hovered = true; this._repaintOverlay(); });
    host.addEventListener('mouseleave', () => { this._hovered = false; this._repaintOverlay(); });
    if (hookMouse) {
      // Drag + release live on the window so a fast drag that leaves the
      // canvas keeps tracking (the SWT canvas captures the pointer during a
      // button-down drag — same emulation as the scope's slider drags).
      host.addEventListener('mousedown', (e) => {
        if (e.button !== 0) return;
        const { x, y } = this._hostXY(e);
        if (this.pointerDown(x, y)) e.preventDefault();
      });
      window.addEventListener('mousemove', (e) => {
        if (!this._dragActive) return;
        const { x, y } = this._hostXY(e);
        this.pointerMove(x, y);
      });
      window.addEventListener('mouseup', () => this.pointerUp());
    }
    // Display-wide EXACT-Ctrl Ctrl+Z filter (Java zoomKeyFilter: keyCode 'z'
    // with stateMask == MOD1 exactly — Ctrl+Shift+Z and other combos pass
    // through). Never fires while a text-editing widget is the target
    // (isUndoTarget's text-widget exception).
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'z' && e.key !== 'Z') return;
      if (!e.ctrlKey || e.shiftKey || e.altKey || e.metaKey) return;
      if (!this.isUndoTarget()) return;
      if (this.undo()) e.preventDefault();
    });
    // Border / undo eligibility follows the window focus, which can change
    // with none of the host focus/hover events firing (Alt+Tab with the
    // pointer resting on the plot) — repaint on the flips so a stopped view
    // never keeps a stale border (Java zoomShellActivationListener).
    window.addEventListener('focus', () => this._repaintOverlay());
    window.addEventListener('blur', () => this._repaintOverlay());
    // A focus move between widgets (e.g. plot → text field) changes the
    // hovered view's border eligibility without any host event.
    document.addEventListener('focusin', () => { if (this._hovered) this._repaintOverlay(); });
  }

  /** Host-local pixel coordinates of a window mouse event. */
  _hostXY(e) {
    const rect = this._host.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  /** Button-1 press at host pixel (x, y): focuses the view and, outside the
   *  blocked zones, starts a selection drag. Returns whether a drag began
   *  (Java rectZoomPointerDown). */
  pointerDown(x, y) {
    if (!this._host) return false;
    this._host.focus();   // click-to-focus even when the drag can't start
    const area = this._zoomableArea();
    if (!area || !this._contains(area, x, y)
        || (this._isBlockedAt && this._isBlockedAt(x, y))) return false;
    this._dragActive = true;
    this._dragStartX = x;
    this._dragStartY = y;
    this._dragCurX = x;
    this._dragCurY = y;
    return true;
  }

  /** Drag update — repaints the rubber band (Java rectZoomPointerMove). */
  pointerMove(x, y) {
    if (!this._dragActive) return;
    this._dragCurX = x;
    this._dragCurY = y;
    this._repaintOverlay();
  }

  /** Whether a selection drag is currently in progress. */
  isDragActive() {
    return this._dragActive;
  }

  /** Release: commits the selection when it spans at least 8 px on both axes
   *  — pushes the pre-zoom state and applies the stretched one. Returns
   *  whether a zoom happened (Java rectZoomPointerUp). */
  pointerUp() {
    if (!this._dragActive) return false;
    this._dragActive = false;
    let sel = this._normalizedSelection();
    // Erasing the band is an overlay-only repaint — a plain click or an
    // aborted drag (the committed path gets its full redraw from applyState).
    this._repaintOverlay();
    // Clamp BEFORE the minimum-size gate: a drag ending outside the plot must
    // not shrink to a sliver that then stretches to the full view.
    const area = this._zoomableArea();
    if (area) sel = this._intersection(sel, area);
    if (sel.w < ZOOM_MIN_SELECTION_PX || sel.h < ZOOM_MIN_SELECTION_PX) return false;
    const next = this._stateForRect(sel);
    if (!next) return false;
    const prev = this._captureState();
    if (!this._applyState(next)) return false;
    if (prev) {
      while (this._undoStack.length >= ZOOM_UNDO_LIMIT) this._undoStack.shift();
      this._undoStack.push(prev);
    }
    return true;
  }

  /** Re-applies the newest restorable zoom state, dropping entries whose
   *  restore no-ops; false when the stack runs out (Java undoZoom — the
   *  applyState implementations repaint, so no extra redraw here). */
  undo() {
    let prev;
    while ((prev = this._undoStack.pop()) !== undefined) {
      if (this._applyState(prev)) return true;
    }
    return false;
  }

  /** Drops the whole undo history — a mode switch (record ↔ file on the
   *  scope) makes the stacked states meaningless (Java clearZoomHistory). */
  clearHistory() {
    this._undoStack.length = 0;
  }

  /** The current selection with positive width/height (Java normalizedSelection). */
  _normalizedSelection() {
    return {
      x: Math.min(this._dragStartX, this._dragCurX),
      y: Math.min(this._dragStartY, this._dragCurY),
      w: Math.abs(this._dragCurX - this._dragStartX),
      h: Math.abs(this._dragCurY - this._dragStartY),
    };
  }

  _contains(r, x, y) {
    return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
  }

  /** Rectangle intersection with non-negative width/height. */
  _intersection(a, b) {
    const x = Math.max(a.x, b.x), y = Math.max(a.y, b.y);
    const x2 = Math.min(a.x + a.w, b.x + b.w), y2 = Math.min(a.y + a.h, b.y + b.h);
    return { x, y, w: Math.max(0, x2 - x), h: Math.max(0, y2 - y) };
  }

  /**
   * Whether this view is the Ctrl+Z target (and shows the accent border): its
   * host is focused, or it is hovered while the focus sits on neither another
   * view's host nor a text-editing widget (whose own Ctrl+Z wins). A
   * backgrounded page (document not focused) shows no border (Java
   * isZoomUndoTarget's active-shell check).
   * @returns {boolean}
   */
  isUndoTarget() {
    if (!this._host || typeof document === 'undefined') return false;
    if (!document.hasFocus()) return false;
    const focus = document.activeElement;
    if (focus === this._host) return true;
    if (!this._hovered) return false;
    if (!focus || focus === document.body) return true;   // Java: focus == null → true
    if (focus.dataset && focus.dataset[HOST_DATASET_KEY]) return false;   // another view owns it
    const tag = focus.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA'
        || focus.isContentEditable) return false;
    return true;
  }

  /**
   * Draws the rect-zoom layer — call LAST in the owning view's paint: the
   * rubber band while dragging, and the 1-px accent border when this view is
   * the Ctrl+Z target. Both are drawn inside the canvas edge — no layout
   * impact (Java drawRectZoomOverlay).
   * @param {CanvasRenderingContext2D} g
   * @param {number} w canvas width (CSS px)
   * @param {number} h canvas height (CSS px)
   */
  drawOverlay(g, w, h) {
    const band = this._dragActive;
    const border = this.isUndoTarget();
    if (!band && !border) return;
    g.save();
    g.lineWidth = 1;
    g.strokeStyle = RECT_ZOOM_ACCENT;
    if (g.setLineDash) g.setLineDash([]);
    if (border) g.strokeRect(0.5, 0.5, w - 1, h - 1);
    if (band) {
      const sel = this._normalizedSelection();
      if (sel.w > 0 && sel.h > 0) g.strokeRect(sel.x + 0.5, sel.y + 0.5, sel.w, sel.h);
    }
    g.restore();
  }
}
