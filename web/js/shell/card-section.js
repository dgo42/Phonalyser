/*
 * Phonalyser web — the Audio-tab per-card profile section (Java PreferencesDialog.CardSection).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The card combo (profiles for this direction + "New card…") + edit-card button + the range table
 * (active-range radio(s), editable label, add / remove) for ONE direction (input or output). A
 * faithful port of PreferencesDialog.CardSection, adapted to the web's LIVE device store: the
 * desktop edits a detached Preferences copy and commits on OK; the web store applies profile edits
 * immediately (exactly as the web already applies a device selection live), so a change here
 * persists (putAudioDeviceProfile) and re-applies the resolved per-channel full-scale at once.
 *
 * The full-scale value is deliberately NOT editable in the table — the crosshair Calibrate flows
 * own it; the row only names the range and marks the active one(s) (Java createRangeRowUi).
 */
import { t } from '../i18n/i18n.js';
import { AudioDeviceProfile, DeviceRange } from '../store/device-profiles.js';
import { DeviceChannelMode } from '../store/device-enums.js';
import { Capability, capabilityOf } from '../store/card-editor-logic.js';

const SQRT2 = Math.sqrt(2.0);
/** Full-scale seeded into a hand-added range with no prior row to copy (mirrors RANGE_FS_MIN_V). */
const RANGE_FS_MIN_V = 1e-9;

export class CardSection {
  /**
   * @param prefs Preferences (the seed full-scales for a newly enabled range).
   * @param deviceStore the LIVE DeviceProfileStore.
   * @param cardEditorDialog the CardEditorDialog (create / edit).
   * @param deps {input, comboSel, editSel, rangesSel, deviceLabel, showConfirm, onChanged}
   *   - input: true for the input direction, false for output.
   *   - comboSel / editSel / rangesSel: the card combo, edit button and range container selectors.
   *   - deviceLabel: () => the current device LABEL for this direction (recognition patterns match it).
   *   - showConfirm: (title, message) => Promise<boolean> — the shared confirm modal.
   *   - onChanged: () => refresh the per-channel FS readout after a store write.
   *   - isStaging: () => true while the Preferences dialog is open. The active-range radios then
   *     only STAGE their pick (Java edits a detached copy); commitStagedActive() writes it on OK
   *     and discardStagedActive() drops it on Cancel. Absent → writes apply immediately, which is
   *     what the standalone (non-dialog) use wants.
   */
  constructor(prefs, deviceStore, cardEditorDialog,
    { input, comboSel, editSel, rangesSel, deviceLabel, showConfirm, onChanged, isStaging }) {
    this.prefs = prefs;
    this.store = deviceStore;
    this.cardEditor = cardEditorDialog;
    this.input = input;
    this._comboSel = comboSel;
    this._editSel = editSel;
    this._rangesSel = rangesSel;
    this._rangesLabelSel = rangesSel + 'Label';   // #inRanges → #inRangesLabel (the "Ranges" caption)
    this._deviceLabel = deviceLabel;
    this._showConfirm = showConfirm;
    this._onChanged = onChanged || (() => {});
    this._isStaging = isStaging || (() => false);
    /** The active-range pick made while the dialog is open, not yet written to the card:
     *  { cardName, activeRange, activeRangeRight } — null when nothing is staged. */
    this._stagedActive = null;
    /** Combo index → profile; the last index (null) is "New card…". */
    this._comboProfiles = [];
    this._selectedName = null;
    /** Device the "no card assigned" prompt was last shown for (once per device per session). */
    this._lastPromptedDevice = null;
    this._bound = false;
  }

  bind() {
    if (this._bound) return this;
    $(this._comboSel).on('change', () => this._onCardSelected());
    $(this._editSel).on('click', () => this._editSelectedCard());
    this._bound = true;
    return this;
  }

  _endpointOf(p) {
    return this.input ? p.input : p.output;
  }

  /** Full-scale (V RMS) a freshly enabled range is seeded with (inputSeedFs / outputSeedFs). */
  _seedFs() {
    return this.input ? this.prefs.getAdcFsVoltageRms() : (this.prefs.getDacFsVoltageAmpl() / SQRT2);
  }

  _currentCardNames() {
    return this.store.getAudioDeviceProfiles().map((p) => p.name);
  }

  /** Re-derives the combo, its preselection, and the range table from the store + the selected
   *  device. The combo lists only profiles whose endpoint for THIS direction has ranges; the
   *  preselection is resolveDeviceProfile, or the "New card…" entry when none matches
   *  (CardSection.refresh). Returns the resolved profile (or null). */
  refresh() {
    const dev = this._deviceLabel();
    const $c = $(this._comboSel);
    $c.empty();
    this._comboProfiles = [];
    for (const p of this.store.getAudioDeviceProfiles()) {
      if (this._endpointOf(p).ranges.length === 0) continue;   // no range this direction — hide
      $c.append(`<option value="${this._comboProfiles.length}">${p.name}</option>`);
      this._comboProfiles.push(p);
    }
    $c.append(`<option value="${this._comboProfiles.length}">${t('preferences.audio.card.new')}</option>`);
    this._comboProfiles.push(null);   // last = New card…

    const pick = dev ? this.store.resolveDeviceProfile(dev) : null;
    this._selectByProfile(pick);
    this._rebuildTable();
    return pick;
  }

  /** A user-driven device change: re-resolve (visibly switching / clearing to "New card…"), and
   *  when no card resolves for a real device, offer to create one (once per device per session)
   *  (CardSection.onDeviceChanged). */
  async onDeviceChanged() {
    const pick = this.refresh();
    const dev = this._deviceLabel();
    if (!dev || pick != null) return;
    if (dev === this._lastPromptedDevice) return;
    this._lastPromptedDevice = dev;
    const yes = await this._showConfirm(
      t('preferences.audio.card.noCardAssigned.title'),
      t('preferences.audio.card.noCardAssigned.message', dev));
    if (yes) await this._createNewCard(dev);
  }

  /** Selects the combo entry for {@code p} silently; a null / absent profile pins the visible
   *  "New card…" entry as the preselection (CardSection.selectByProfile). */
  _selectByProfile(p) {
    let idx = -1;
    if (p != null) {
      for (let i = 0; i < this._comboProfiles.length - 1; i++) {
        if (this._comboProfiles[i] != null && this._comboProfiles[i].name === p.name) { idx = i; break; }
      }
    }
    if (idx < 0) {
      $(this._comboSel).val(String(this._comboProfiles.length - 1));   // New card… entry
      this._selectedName = null;
    } else {
      $(this._comboSel).val(String(idx));
      this._selectedName = this._comboProfiles[idx].name;
    }
  }

  /** The card combo's user-selection handler (CardSection.onCardSelected). */
  async _onCardSelected() {
    const idx = parseInt($(this._comboSel).val(), 10);
    if (!(idx >= 0)) return;
    const dev = this._deviceLabel();
    if (idx === this._comboProfiles.length - 1) { await this._createNewCard(dev); return; }
    const p = this._comboProfiles[idx];
    this._bindAlias(p, dev);
    this._selectedName = p.name;
    this._applyAndReadout(dev);
    this._rebuildTable();
  }

  /** "New card…" — open the create dialog prefilled with the normalized device name + the
   *  triggering device name as the first match entry, then create the returned profile
   *  (CardSection.createNewCard). */
  async _createNewCard(dev) {
    const seed = new AudioDeviceProfile();
    seed.name = dev ? this.store.normalizeDeviceName(dev) : '';
    if (dev) seed.match.push(dev);
    const p = await this.cardEditor.open(seed,
      this.input ? Capability.INPUT_ONLY : Capability.OUTPUT_ONLY,
      this._currentCardNames(), null, this.prefs.getAdcFsVoltageRms(), this.prefs.getDacFsVoltageAmpl() / SQRT2);
    if (p == null) { this.refresh(); return; }   // cancelled — restore
    this.store.putAudioDeviceProfile(p);
    this.refresh();
    this._selectByProfile(this.store.findAudioDeviceProfile(p.name));
    this._applyAndReadout(dev);
    this._rebuildTable();
  }

  /** Opens the edit dialog on the selected card and replaces it, re-keying on a rename
   *  (CardSection.editSelectedCard). */
  async _editSelectedCard() {
    if (this._selectedName == null) return;
    const live = this.store.findAudioDeviceProfile(this._selectedName);
    if (live == null) return;
    const p = await this.cardEditor.open(live, capabilityOf(live),
      this._currentCardNames(), live.name, this.prefs.getAdcFsVoltageRms(), this.prefs.getDacFsVoltageAmpl() / SQRT2);
    if (p == null) return;   // cancelled
    if (p.name.toLowerCase() !== this._selectedName.toLowerCase()) {
      this.store.removeAudioDeviceProfile(this._selectedName);
    }
    this.store.putAudioDeviceProfile(p);
    this.refresh();
    this._selectByProfile(this.store.findAudioDeviceProfile(p.name));
    this._applyAndReadout(this._deviceLabel());
    this._rebuildTable();
  }

  /** Binds card {@code p} to {@code dev} by appending it to match (only when nothing already
   *  matches) and persists (CardSection.bindAlias). */
  _bindAlias(p, dev) {
    if (!dev) return;
    const live = this.store.findAudioDeviceProfile(p.name);
    if (live != null && live.bindDeviceName(dev)) this.store.putAudioDeviceProfile(live);
  }

  /** Persists the selected card back into the store (replace-by-name) — the shared commit step
   *  (Java CardSection.commit). */
  _commitSelected() {
    if (this._selectedName == null) return;
    const p = this.store.findAudioDeviceProfile(this._selectedName);
    if (p != null) this.store.putAudioDeviceProfile(p);
  }

  /** The selected card, or null for "New card…". */
  _selectedProfile() {
    return this._selectedName != null ? this.store.findAudioDeviceProfile(this._selectedName) : null;
  }

  /** The active-range label one channel SHOWS: the staged pick while the Preferences dialog holds
   *  one, else the card's own. Every render and every staging write reads through here, so the
   *  radios track the user's pick without it having been applied anywhere. */
  _activeLabel(ep, right) {
    const staged = this._stagedActive;
    if (staged != null) return right ? staged.activeRangeRight : staged.activeRange;
    return right ? ep.activeRangeRight : ep.activeRange;
  }

  /** Writes a staged active-range pick into the card and applies it — the Preferences OK path.
   *  No-op when nothing was staged, so an OK that never touched a radio changes nothing. */
  commitStagedActive() {
    const staged = this._stagedActive;
    this._stagedActive = null;
    if (staged == null || staged.cardName == null) return;
    const card = this.store.findAudioDeviceProfile(staged.cardName);
    if (card == null) return;
    const ep = this._endpointOf(card);
    if (ep == null) return;
    ep.activeRange = staged.activeRange;
    ep.activeRangeRight = staged.activeRangeRight;
    // Same tail the immediate (non-staged) path runs: persist the card, push the range's
    // per-channel full-scale for the current device, refresh the readout.
    if (ep.channels === DeviceChannelMode.INDEPENDENT) {
      this._commitSelected();
      const dev = this._deviceLabel();
      if (this.input) this.store.applyInputDeviceProfileChannel(dev, 'L');
      else this.store.applyOutputDeviceProfileChannel(dev, 'L');
      if (this.input) this.store.applyInputDeviceProfileChannel(dev, 'R');
      else this.store.applyOutputDeviceProfileChannel(dev, 'R');
      this._onChanged();
    } else {
      this._applyAndReadout(this._deviceLabel());
    }
    this._rebuildTable();
  }

  /** Drops a staged active-range pick — the Preferences Cancel path. Nothing was written, so this
   *  only has to forget it and repaint the radios from the card. */
  discardStagedActive() {
    if (this._stagedActive == null) return;
    this._stagedActive = null;
    this._rebuildTable();
  }

  /** Persists the selected card and re-applies its per-channel full-scale for the current device,
   *  then refreshes the FS readout. */
  _applyAndReadout(dev) {
    this._commitSelected();
    if (this.input) this.store.applyInputDeviceProfile(dev);
    else this.store.applyOutputDeviceProfile(dev);
    this._onChanged();
  }

  /** Rebuilds the range table from the selected card's endpoint (hidden when no card selected)
   *  (CardSection.rebuildTable). */
  _rebuildTable() {
    const $r = $(this._rangesSel).empty();
    const p = this._selectedName != null ? this.store.findAudioDeviceProfile(this._selectedName) : null;
    const show = p != null;
    $(this._editSel).prop('disabled', !show);
    $(this._rangesSel).toggleClass('d-none', !show);
    $(this._rangesLabelSel).toggleClass('d-none', !show);   // the "Ranges" caption
    if (!show) return;
    const ep = this._endpointOf(p);
    ep.ranges.forEach((range, i) => this._createRangeRow($r, ep, range, i === 0));
  }

  /** Builds one range row to the Java CardSection.createRangeRowUi shape: the active radio(s),
   *  editable unique label field(s), and add / remove ICON buttons. INDEPENDENT is TWO mirrored
   *  groups sharing one range label — [Left] caption + radio + label field, [Right] caption + radio
   *  + mirrored label field — with the Left/Right captions carried by every row but visible only on
   *  row 0 (an invisible caption still reserves its grid cell so the columns line up across rows).
   *  Row 0's remove is hidden but reserves its space. The full-scale value is NOT editable here —
   *  the crosshair Calibrate flows own it; the row only names + marks the active one(s). */
  _createRangeRow($container, ep, range, isRow0) {
    const independent = ep.channels === DeviceChannelMode.INDEPENDENT;
    const $row = $('<div class="card-range-row"></div>').toggleClass('independent', independent);

    const activeTip = t('preferences.audio.range.active.tooltip');
    const labelTip = t('preferences.audio.range.label.tooltip');
    // An invisible caption keeps its cell (visibility:hidden, not display:none) so columns align.
    const caption = (key) => $(`<span class="range-caption small">${t(key)}</span>`).css('visibility', isRow0 ? '' : 'hidden');
    const mkLabel = () => $(`<input type="text" class="form-control form-control-sm range-label" title="${labelTip}">`).val(range.label);

    if (independent) $row.append(caption('scope.tab.left'));
    const $active = $(`<input type="radio" ${range.label === this._activeLabel(ep, false) ? 'checked' : ''} title="${activeTip}">`);
    const $label = mkLabel();
    $row.append($active, $label);

    let $activeRight = null;
    let $labelRight = null;
    if (independent) {
      $row.append(caption('scope.tab.right'));
      $activeRight = $(`<input type="radio" ${range.label === this._activeLabel(ep, true) ? 'checked' : ''} title="${activeTip}">`);
      $labelRight = mkLabel();
      $row.append($activeRight, $labelRight);
    }

    // + / − icon buttons exactly like the FreqResp calibration rows (green/red tint reused from CSS).
    const $add = $(`<button type="button" class="fcal-add btn btn-sm btn-outline-secondary" title="${t('preferences.audio.range.add.tooltip')}"><img src="assets/icons/plus.svg" class="util-svg" alt=""></button>`);
    const $rem = $(`<button type="button" class="fcal-remove btn btn-sm btn-outline-secondary" title="${t('preferences.audio.range.remove.tooltip')}"><img src="assets/icons/minus.svg" class="util-svg" alt=""></button>`);
    if (isRow0) $rem.css('visibility', 'hidden');   // row 0: no remove (space reserved)
    // A DEVICE-OWNED range set is FIXED: the analyzer's attenuator has exactly these positions, and
    // each label is the protocol key the backend decodes back into a register value (QA40x:
    // "N dBV" → reg 0x05 / 0x06). Adding, removing or renaming a row would either invent a position
    // the hardware does not have or break that decode, so the whole row is read-only except the
    // active radio. The values come from the device's own calibration page, hence
    // calibrationFromDevice — the same flag that makes the Calibrate flows refuse this endpoint.
    if (ep.calibrationFromDevice) {
      $label.prop('readonly', true);
      if ($labelRight) $labelRight.prop('readonly', true);
      $add.prop('disabled', true).css('visibility', 'hidden');
      $rem.prop('disabled', true).css('visibility', 'hidden');
    }
    $row.append($add, $rem);
    $container.append($row);

    $active.on('change', () => { if ($active.is(':checked')) this._userSetActive(ep, range, false); });
    if ($activeRight) $activeRight.on('change', () => { if ($activeRight.is(':checked')) this._userSetActive(ep, range, true); });
    const rename = ($edited) => this._userRenameRange(ep, range, $edited, $label, $labelRight);
    $label.on('change blur', () => rename($label));
    if ($labelRight) $labelRight.on('change blur', () => rename($labelRight));
    $add.on('click', () => this._userAddRange(ep));
    if (!isRow0) $rem.on('click', () => this._userRemoveRange(ep, range));
  }

  _userSetActive(ep, range, right) {
    // While the Preferences dialog is open the pick is STAGED, not applied: it must not reach the
    // card, the store, the full-scale prefs or the device until OK. (It used to write straight
    // through, so the new range was live the instant the radio moved and only a Cancel undid it.)
    if (this._isStaging()) {
      const card = this._selectedProfile();
      this._stagedActive = {
        cardName: card ? card.name : null,
        activeRange: right ? this._activeLabel(ep, false) : range.label,
        activeRangeRight: right ? range.label : this._activeLabel(ep, true),
      };
      this._rebuildTable();   // the radio reflects the staged pick; nothing else moves
      return;
    }
    if (right) ep.activeRangeRight = range.label;
    else ep.activeRange = range.label;
    const dev = this._deviceLabel();
    if (ep.channels === DeviceChannelMode.INDEPENDENT) {
      // Apply ONLY the changed channel (item 6 / maintainer ruling): a right-radio click must not
      // re-derive the untouched left scalar (nor fall back to row 0 on a dangling label). Persist
      // the card, then push just that channel's full-scale.
      this._commitSelected();
      const ch = right ? 'R' : 'L';
      if (this.input) this.store.applyInputDeviceProfileChannel(dev, ch);
      else this.store.applyOutputDeviceProfileChannel(dev, ch);
      this._onChanged();
    } else {
      // LINKED / MONO: one row drives both scalars → the full apply.
      this._applyAndReadout(dev);
    }
    this._rebuildTable();
  }

  /** Commits an edited label (kept unique per endpoint), following the active selection if this
   *  row was active, and syncing the row's OTHER label field (the INDEPENDENT mirror) to the
   *  committed label (CardSection.userRenameRange + syncLabelFields). */
  _userRenameRange(ep, range, $edited, $left, $right) {
    const wanted = String($edited.val()).trim();
    if (wanted === '') { this._syncLabelFields($left, $right, range.label); return; }
    const unique = this._uniqueLabel(ep, wanted, range);
    const wasActive = range.label === ep.activeRange;
    const wasActiveRight = range.label === ep.activeRangeRight;
    range.label = unique;
    this._syncLabelFields($left, $right, unique);
    if (wasActive) ep.activeRange = unique;
    if (wasActiveRight) ep.activeRangeRight = unique;
    this._commitAndApply();
  }

  /** Shows {@code label} in both of the row's label fields (they mirror one range label; a null
   *  mirror in LINKED / MONO is ignored) — CardSection.syncLabelFields. */
  _syncLabelFields($left, $right, label) {
    if ($left && $left.val() !== label) $left.val(label);
    if ($right && $right.val() !== label) $right.val(label);
  }

  _userAddRange(ep) {
    const range = new DeviceRange();
    range.label = this._uniqueLabel(ep, t('preferences.audio.range.default'), null);
    const last = ep.ranges.length === 0 ? RANGE_FS_MIN_V : ep.ranges[ep.ranges.length - 1].fsLeft;
    range.fsLeft = last;
    range.fsRight = last;
    ep.ranges.push(range);
    this._commitAndApply();
    this._rebuildTable();
  }

  _userRemoveRange(ep, range) {
    if (ep.ranges.length <= 1) return;
    const idx = ep.ranges.indexOf(range);
    if (idx <= 0) return;
    ep.ranges.splice(idx, 1);
    if (range.label === ep.activeRange && ep.ranges.length > 0) ep.activeRange = ep.ranges[0].label;
    if (range.label === ep.activeRangeRight && ep.ranges.length > 0) ep.activeRangeRight = ep.ranges[0].label;
    this._commitAndApply();
    this._rebuildTable();
  }

  /** Persists the selected card and re-applies its full-scale (the live-store equivalent of
   *  CardSection.commit + the desktop's on-OK apply). */
  _commitAndApply() {
    this._applyAndReadout(this._deviceLabel());
  }

  _uniqueLabel(ep, wanted, self) {
    let candidate = wanted;
    let n = 2;
    while (this._labelTaken(ep, candidate, self)) candidate = `${wanted} ${n++}`;
    return candidate;
  }

  _labelTaken(ep, label, self) {
    for (const dr of ep.ranges) {
      if (dr !== self && dr.label === label) return true;
    }
    return false;
  }
}
