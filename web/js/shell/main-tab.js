/*
 * Phonalyser web — the MAIN TAB (the rAF render-frame driver + the 3-pane collapsible /
 * sash-split layout).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/MultifunctionalTab + gui/MainTab. Owns the MAIN rAF render loop
 * (which drives the scope + FFT pane render() each frame, each pane self-gating on its OWN
 * record state) and the workspace LAYOUT — the collapsible generator / scope / FFT panes,
 * the draggable horizontal + vertical SASH splitters, and the pane-weight / collapse-state
 * persistence. The per-pane render branches (live trace + measurement + scrollbars / idle
 * grid, and the dirty-checked spectrum + readout) live in scope/scope-pane.js + fft/fft-pane.js;
 * this tab just sequences them. It owns NO lifecycle flags (genRunning / scopeRec / fftRec /
 * busy) — those stay the single source of truth in app.js and each pane.render() self-gates
 * on its own record state through the closures app.js injected into the panes. The panes are
 * injected (start() drives their render()); prefs is injected for the pane-weight / collapse
 * persistence; the workspace DOM ids are reached directly via the global `$` / getElementById.
 */

// ============================ Pane layout: collapse + sash ============================
// Port of MultifunctionalTab's collapsible panes + draggable SASH splitters.
// Generator is a fixed-pixel column (min 200px); scope/fft share the right column
// via flex-grow ratios (the SashForm-weight analogue). Mutual exclusion: only one
// of scope/fft may be collapsed at a time.
const MIN_WIDTH_PX = 200, COLLAPSED_PANE_SIZE = 28;

export class MainTab {
  /**
   * @param deps {scopePane, fftPane, prefs}
   *   - scopePane: the ScopePane (its render() is the scope branch of the rAF loop, self-gated
   *       on the scope record state).
   *   - fftPane: the FftPane (its render() is the FFT branch of the rAF loop, self-gated on the
   *       FFT record state).
   *   - prefs: Preferences (the pane widths / split weights / collapse state the layout persists).
   */
  constructor({ scopePane, fftPane, prefs }) {
    this.scopePane = scopePane;
    this.fftPane = fftPane;
    this.prefs = prefs;
  }

  // ----- the MAIN rAF render loop -----
  // start() kicks the requestAnimationFrame driver; the per-frame body renders both panes.
  start() {
    const renderLoop = () => {
      // Gate each view on ITS OWN record state (Java MultifunctionalTab.renderRealtimeFrame
      // renders the scope while oscPane is capturing and the FFT while fftPane records,
      // independently). When a record stops, that view freezes on its last frame.
      // The scope branch (live trace + measurement + scrollbars, or the idle grid) is
      // scopePane.render() (Java ScopePane) and the FFT branch (dirty-checked spectrum + readout)
      // is fftPane.render() (Java FftPane); the MAIN rAF loop lives here and calls them.
      if (this.scopePane) this.scopePane.render();
      if (this.fftPane) this.fftPane.render();
      requestAnimationFrame(renderLoop);
    };
    requestAnimationFrame(renderLoop);
    return this;
  }

  initLayout() {
    const prefs = this.prefs;
    const ws = document.querySelector('#tab-multi .workspace');
    const genCol = document.getElementById('genCol');
    const scopePaneEl = document.getElementById('scopePane');
    const fftPaneEl = document.getElementById('fftPane');
    const hSash = document.getElementById('hSash');
    const vSash = document.getElementById('vSash');
    if (!ws || !genCol || !scopePaneEl || !fftPaneEl) return;

    const state = {
      genWidthPx: prefs.genPaneWidth.get() > 0 ? Math.max(MIN_WIDTH_PX, prefs.genPaneWidth.get()) : 330,
      genCollapsed: false, oscCollapsed: false, fftCollapsed: false,
      vGrow: Array.isArray(prefs.multiVSplitWeights) && prefs.multiVSplitWeights.length === 2
        ? prefs.multiVSplitWeights.slice() : [1, 1],
      preCollapseVGrow: null,
    };

    const setCaret = (pane, collapsed) => {
      const c = pane.querySelector('.pane-caret'); if (c) c.textContent = collapsed ? '▶' : '▼';
    };

    function applyGen() {
      genCol.classList.toggle('collapsed', state.genCollapsed);
      setCaret(genCol, state.genCollapsed);
      if (state.genCollapsed) { genCol.style.removeProperty('--gen-w'); return; }
      const avail = ws.clientWidth - 4;
      const w = Math.max(MIN_WIDTH_PX, Math.min(state.genWidthPx, avail - MIN_WIDTH_PX));
      genCol.style.setProperty('--gen-w', w + 'px');
    }

    function applyVertical() {
      scopePaneEl.classList.toggle('collapsed', state.oscCollapsed);
      fftPaneEl.classList.toggle('collapsed', state.fftCollapsed);
      setCaret(scopePaneEl, state.oscCollapsed);
      setCaret(fftPaneEl, state.fftCollapsed);
      if (!state.oscCollapsed && !state.fftCollapsed) {
        scopePaneEl.style.setProperty('--scope-grow', state.vGrow[0]);
        fftPaneEl.style.setProperty('--fft-grow', state.vGrow[1]);
      }
      hSash.classList.toggle('disabled', state.genCollapsed);
      vSash.classList.toggle('disabled', state.oscCollapsed || state.fftCollapsed);
    }

    function toggleGen() { state.genCollapsed = !state.genCollapsed; prefs.genPaneCollapsed.set(state.genCollapsed); applyGen(); applyVertical(); }
    function toggleOsc() {
      const collapsing = !state.oscCollapsed;
      if (collapsing && state.fftCollapsed) { state.fftCollapsed = false; prefs.fftPaneCollapsed.set(false); }
      if (collapsing) state.preCollapseVGrow = state.vGrow.slice();
      state.oscCollapsed = collapsing; prefs.oscPaneCollapsed.set(collapsing);
      if (!collapsing) { state.vGrow = state.preCollapseVGrow || [1, 1]; state.preCollapseVGrow = null; persistVGrow(); }
      applyVertical();
    }
    function toggleFft() {
      const collapsing = !state.fftCollapsed;
      if (collapsing && state.oscCollapsed) { state.oscCollapsed = false; prefs.oscPaneCollapsed.set(false); }
      if (collapsing) state.preCollapseVGrow = state.vGrow.slice();
      state.fftCollapsed = collapsing; prefs.fftPaneCollapsed.set(collapsing);
      if (!collapsing) { state.vGrow = state.preCollapseVGrow || [1, 1]; state.preCollapseVGrow = null; persistVGrow(); }
      applyVertical();
    }
    function persistVGrow() {
      // Saved only when neither pane is collapsed (mirrors the Java dispose guard).
      if (!state.oscCollapsed && !state.fftCollapsed) { prefs.multiVSplitWeights = state.vGrow.slice(); prefs.save(); }
    }

    // Caret/title clicks (FR pane title is static — its caret is hidden by CSS).
    document.querySelectorAll('#tab-multi .pane-header[data-collapse]').forEach((hdr) => {
      hdr.addEventListener('mousedown', () => {
        const which = hdr.getAttribute('data-collapse');
        if (which === 'gen') toggleGen(); else if (which === 'osc') toggleOsc(); else if (which === 'fft') toggleFft();
      });
    });

    // Horizontal sash drag — blocked when the generator is collapsed; clamps the
    // generator width to ≥ MIN_WIDTH_PX and ≤ avail−MIN_WIDTH_PX (Java sashFilter).
    hSash.addEventListener('mousedown', (e) => {
      if (state.genCollapsed) return;
      e.preventDefault();
      const onMove = (ev) => {
        const avail = ws.clientWidth - 4;
        let w = ev.clientX - ws.getBoundingClientRect().left;
        w = Math.max(MIN_WIDTH_PX, Math.min(w, avail - MIN_WIDTH_PX));
        state.genWidthPx = w; genCol.style.setProperty('--gen-w', w + 'px');
      };
      const onUp = () => {
        document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp);
        prefs.genPaneWidth.set(Math.round(state.genWidthPx));
      };
      document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
    });

    // Vertical sash drag — blocked when either pane is collapsed; pointer Y → a
    // scope/fft grow ratio.
    vSash.addEventListener('mousedown', (e) => {
      if (state.oscCollapsed || state.fftCollapsed) return;
      e.preventDefault();
      const rc = document.getElementById('rightCol');
      const onMove = (ev) => {
        const r = rc.getBoundingClientRect();
        const top = Math.max(1, ev.clientY - r.top);
        const bot = Math.max(1, r.bottom - ev.clientY);
        state.vGrow = [top, bot];
        scopePaneEl.style.setProperty('--scope-grow', top);
        fftPaneEl.style.setProperty('--fft-grow', bot);
      };
      const onUp = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); persistVGrow(); };
      document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
    });

    // Keep the generator width clamped on window resize so the right column never
    // vanishes (the controlResized re-pin).
    window.addEventListener('resize', () => { if (!state.genCollapsed) applyGen(); });

    // Restore saved layout: weights → gen → (osc XOR fft); osc wins the exclusion.
    const savedGen = prefs.genPaneCollapsed.get();
    const savedOsc = prefs.oscPaneCollapsed.get();
    const savedFft = prefs.fftPaneCollapsed.get();
    state.genCollapsed = savedGen;
    state.oscCollapsed = savedOsc;
    state.fftCollapsed = savedFft && !savedOsc;
    applyGen(); applyVertical();
    return this;
  }
}
