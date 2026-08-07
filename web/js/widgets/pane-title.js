/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.widgets.PaneTitle (DOM variant).
//
// Wraps a pane's `.pane-header` and turns a title-bar click into a paneTitleClick(paneId)
// bus publish (Java PaneTitle.onClick -> Events.paneTitleClick(id)). The LAYOUT OWNER
// (MainTab, Java MultifunctionalTab) subscribes by id and performs the actual collapse -
// on the web the collapse STATE + the osc/fft mutual-exclusion live centrally in MainTab
// (a documented web divergence from Java's per-widget collapsed flag), so the widget only
// signals the click and repaints its own caret glyph via setCollapsed().
//
// A STATIC pane (FreqResp) gets no PaneTitle - its header carries no data-collapse and its
// caret is hidden by CSS (Java PaneTitle.setStaticMode).

import { MessageBus } from '../bus/message-bus.js';
import { paneTitleClick } from '../bus/events.js';

export class PaneTitle {
  /**
   * @param {HTMLElement} headerEl the pane's `.pane-header` element (may be null -> no-op).
   * @param {number} paneId        a PaneId - routes the bus event to the layout owner.
   */
  constructor(headerEl, paneId) {
    this.paneId = paneId;
    this.caret = headerEl ? headerEl.querySelector('.pane-caret') : null;
    if (headerEl) {
      headerEl.addEventListener('mousedown',
        () => MessageBus.instance().publish(paneTitleClick(paneId)));
    }
  }

  /** Repaints the caret glyph for the given collapsed state (Java PaneTitle arrow ▼/▶). */
  setCollapsed(collapsed) {
    if (this.caret) this.caret.textContent = collapsed ? '▶' : '▼';
  }
}
