/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.widgets.TileTabFolder.
//
// A tiled tab strip where each tile owns a collapsible drop-down panel: clicking a
// tile shows exactly its panel (hiding the folder's others) and marks it active;
// clicking the ALREADY-active tile collapses the folder (hides all, no active tile).
// This is the web equivalent of the SWT CTabFolder "one selected tab shows its
// composite" behaviour, with the extra "click-again-to-collapse" the tiled panes use.
//
// A tile declares the id of the panel it owns in `data-panel` (empty/absent for a
// non-panel tile, which then only sets active). The folder's owned panel set is
// DERIVED from its tiles' data-panel targets, so Save / Load / Calibration panels
// close along with the rest — the earlier per-pane hand-maintained ID lists drifted
// (one omitted Save/Load and they stacked open). Replaces the three duplicated
// `$('#…Tabs .tab').on('click', …)` handlers (fft / scope / freqresp).

export class TileTabs {
  /**
   * @param {string} tabsSelector jQuery selector for the folder, e.g. '#fftTabs'.
   */
  constructor(tabsSelector) {
    this.$ = window.jQuery;
    this.sel = tabsSelector;
  }

  /** Wires the click toggle on the folder's tiles. Call once at pane setup. */
  bind() {
    const $ = this.$;
    const root = this.sel;
    // The panels this folder owns = the distinct non-empty data-panel targets of its tiles.
    const ownedPanelIds = () => $(root + ' .tab').map(function () {
      return $(this).data('panel') || null;
    }).get().filter(Boolean);
    $(root + ' .tab').on('click', function () {
      const panel = $(this).data('panel');   // '' / undefined for a non-panel tile
      const wasOpen = $(this).hasClass('active') && panel && $('#' + panel).hasClass('show');
      $(root + ' .tab').removeClass('active');
      $(this).addClass('active');
      // Hide EVERY panel this folder owns so exactly one shows at a time (Java CTabFolder:
      // selecting a tab hides the rest).
      for (const id of ownedPanelIds()) $('#' + id).removeClass('show');
      if (panel && !wasOpen) $('#' + panel).addClass('show');
    });
  }
}
