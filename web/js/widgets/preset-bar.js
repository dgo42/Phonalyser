/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.widgets.PresetBar<P> + its Store<P> interface.
//
// A named save / load / delete bar over a preset map, reused by the FFT, scope and FreqResp
// settings tabs (Java has one generic PresetBar<P>; the web previously triplicated it). The
// per-pane specifics come in through the `store` adapter (Java Store<P>: presets / put /
// remove / captureCurrent / apply / onChanged) plus the element ids, the confirm dialog and
// the i18n key prefix - everything else (the editable-name combo behaviour, the dropdown of
// saved names, and the Save/Load/Delete enablement rule) is shared here.
//
// Enablement (Java PresetBar.refreshButtons): empty name -> all disabled; a NEW name -> Save
// only; an EXISTING name -> Load/Delete always, and Save ONLY when the current settings differ
// from the saved snapshot (JSON equality stands in for Java P.equals - the presets are plain
// POJOs of primitives + strings).

import { t } from '../i18n/i18n.js';

export class PresetBar {
  /**
   * @param {object} cfg
   *   - ids: { name, save, load, delete, menu, menuBtn } jQuery selectors.
   *   - store: { presets():Map<string,P>, put(name,P), remove(name), captureCurrent():P, apply(P) }.
   *   - confirm: (title, message) => Promise<boolean>  (the shared Bootstrap confirm).
   *   - i18nPrefix: string, e.g. 'fft.presets' -> '<prefix>.overwrite.title/.message', '.delete.*'.
   *   - onChanged?: (names:string[]) => void  - repaint the pane's "N saved" tile chip.
   */
  constructor(cfg) {
    this.$ = window.jQuery;
    this.cfg = cfg;
  }

  /** Wires the name field, dropdown, and Save/Load/Delete buttons; seeds the list. Call once. */
  bind() {
    const $ = this.$, c = this.cfg, id = c.ids;
    // Re-evaluate enablement as the name is typed (Java combo SWT.Modify).
    $(id.name).on('input', () => this.refreshButtons());
    // Picking a saved name from the dropdown fills the field so Load/Delete act on it
    // (Java PresetBar editable combo: selecting a name populates the combo).
    $(id.menu).on('click', '.dropdown-item', (ev) => {
      $(id.name).val($(ev.currentTarget).text());
      this.refreshButtons();
    });
    $(id.save).on('click', async () => {
      const name = ($(id.name).val() || '').trim();
      if (!name) return;
      // Confirm before overwriting (Java PresetBar.onSave -> Dialogs.confirm); Cancel aborts.
      if (c.store.presets().has(name)
          && !await c.confirm(t(c.i18nPrefix + '.overwrite.title'), t(c.i18nPrefix + '.overwrite.message', name))) return;
      c.store.put(name, c.store.captureCurrent());
      $(id.name).val(name);   // reflect the trimmed name (Java combo.setText)
      this.refreshList();
    });
    $(id.load).on('click', () => {
      const p = c.store.presets().get(($(id.name).val() || '').trim());
      if (p) { c.store.apply(p); this.refreshButtons(); }
    });
    $(id.delete).on('click', async () => {
      const name = ($(id.name).val() || '').trim();
      if (!c.store.presets().has(name)) return;
      // Confirm before deleting (Java PresetBar.onDelete -> Dialogs.confirm); Cancel aborts.
      if (!await c.confirm(t(c.i18nPrefix + '.delete.title'), t(c.i18nPrefix + '.delete.message', name))) return;
      c.store.remove(name);
      this.refreshList();
    });
    // NOTE: bind() only wires handlers - it does NOT seed the list. Each pane calls
    // refreshList() when its controls are ready (FFT/scope from app.js init, FreqResp at the
    // end of its setup), matching the pre-extraction timing.
  }

  /** Repopulates the dropdown + toggle-disabled + the "N saved" chip, then button enablement. */
  refreshList() {
    const $ = this.$, c = this.cfg, id = c.ids;
    const $menu = $(id.menu).empty();
    const names = [...c.store.presets().keys()];
    for (const name of names) {
      $('<li>').append($('<button type="button" class="dropdown-item">').text(name)).appendTo($menu);
    }
    $(id.menuBtn).prop('disabled', names.length === 0);
    if (c.onChanged) c.onChanged(names);
    this.refreshButtons();
  }

  /** Save/Load/Delete enablement - see the class comment (Java PresetBar.refreshButtons). */
  refreshButtons() {
    const $ = this.$, c = this.cfg, id = c.ids;
    const name = ($(id.name).val() || '').trim();
    const $s = $(id.save), $l = $(id.load), $d = $(id.delete);
    if (!name) { $s.prop('disabled', true); $l.prop('disabled', true); $d.prop('disabled', true); return; }
    const existing = c.store.presets().get(name);
    if (!existing) { $s.prop('disabled', false); $l.prop('disabled', true); $d.prop('disabled', true); }
    else {
      $s.prop('disabled', JSON.stringify(existing) === JSON.stringify(c.store.captureCurrent()));
      $l.prop('disabled', false); $d.prop('disabled', false);
    }
  }
}
