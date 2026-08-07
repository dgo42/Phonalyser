/*
 * Phonalyser web - the Audio-tab per-card profile section (Java PreferencesDialog.CardSection).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The card combo (profiles for this direction + "New card...") + edit-card button + the range table
 * (active-range radio(s), editable label, add / remove) for ONE direction (input or output). A
 * faithful port of PreferencesDialog.CardSection, adapted to the web's LIVE device store: the
 * desktop edits a detached Preferences copy and commits on OK; the web store applies profile edits
 * immediately (exactly as the web already applies a device selection live), so a change here
 * persists (putAudioDeviceProfile) and re-applies the resolved per-channel full-scale at once.
 *
 * The full-scale value is deliberately NOT editable in the table - the crosshair Calibrate flows
 * own it; the row only names the range and marks the active one(s) (Java createRangeRowUi).
 *
 * With a `cardSource` that answers for the selected backend, the combo shows a BENCH's cards
 * instead of this machine's (Java refreshBenchCards): the card a device uses is the operator's
 * own choice, and for a device on a Phonalyser server the cards to choose from are that
 * server's - its store owns the calibration of everything plugged into it. No "New card..." there:
 * a card is created and calibrated where the device is.
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
   *   - showConfirm: (title, message) => Promise<boolean> - the shared confirm modal.
   *   - onChanged: () => refresh the per-channel FS readout after a store write.
   *   - isStaging: () => true while the Preferences dialog is open. The active-range radios then
   *     only STAGE their pick (Java edits a detached copy); commitStagedActive() writes it on OK
   *     and discardStagedActive() drops it on Cancel. Absent -> writes apply immediately, which is
   *     what the standalone (non-dialog) use wants.
   *   - showAlert: (title, message) => the shared alert modal - the one failure this section
   *     raises is a refused calibration copy (Java Dialogs.error). Absent -> console.
   *   - cardSource: the BENCH's card list for the selected backend (Java's benchCards + the
   *     dialog's BackendKey.remote() test, behind one seam):
   *       cards(input, deviceLabel) -> {names, bound} for a remote selection, null for a local one
   *         (null -> everything below behaves exactly as it did before this seam existed);
   *       stage(input, deviceLabel, cardName) -> record the pick WITHOUT sending it;
   *       commit(input) -> Promise of the card names the bench refused (the dialog's OK);
   *       calibration(input, deviceLabel) -> the bench's stored full scale, null when it has NONE
   *         and undefined when it does not offer that device at all (the two are different
   *         answers: only the first is something an operator can fix from here);
   *       mayCopyAsk(input, deviceLabel) / settleCopyAsk(input, deviceLabel) -> the once-per-run
   *         offer register, spent only on a SETTLED question;
   *       copyCalibration(input, deviceLabel) -> its YES branch: the whole local card goes up
   *         (cards.put + device.setCard), its values alone as the fallback, answering a
   *         BenchCards.Copied outcome for the section to report.
   *     Absent -> local cards only, which is what a build with no net client shows.
   */
  constructor(prefs, deviceStore, cardEditorDialog,
    { input, comboSel, editSel, rangesSel, deviceLabel, showConfirm, showAlert, onChanged, isStaging, cardSource }) {
    this.prefs = prefs;
    this.store = deviceStore;
    this.cardEditor = cardEditorDialog;
    this.input = input;
    this._comboSel = comboSel;
    this._editSel = editSel;
    this._rangesSel = rangesSel;
    this._rangesLabelSel = rangesSel + 'Label';   // #inRanges -> #inRangesLabel (the "Ranges" caption)
    this._deviceLabel = deviceLabel;
    this._showConfirm = showConfirm;
    this._showAlert = showAlert || ((title, message) => console.warn(title, message));
    this._onChanged = onChanged || (() => {});
    this._isStaging = isStaging || (() => false);
    this._cardSource = cardSource || null;
    /** The active-range pick made while the dialog is open, not yet written to the card:
     *  { cardName, activeRange, activeRangeRight } - null when nothing is staged. */
    this._stagedActive = null;
    /** Combo index -> profile; the last index (null) is "New card...". EMPTY while
     *  {@link #_remoteCards} - a bench's cards have no local profile behind them, and resolving
     *  one by name is the collision the calibration-follows-the-device rule forbids. */
    this._comboProfiles = [];
    /** Combo index -> card name, whichever store the list came from - what the selection handler
     *  reads. Parallel to the combo's own options. */
    this._comboNames = [];
    /** True when the combo is showing the BENCH's cards rather than this machine's. */
    this._remoteCards = false;
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
   *  preselection is resolveDeviceProfile, or the "New card..." entry when none matches
   *  (CardSection.refresh). A remote selection takes {@link #_refreshBenchCards} instead.
   *  Returns the resolved profile (or null). */
  async refresh() {
    const dev = this._deviceLabel();
    const $c = $(this._comboSel);
    $c.empty();
    this._comboProfiles = [];
    this._comboNames = [];
    const bench = this._cardSource ? await this._cardSource.cards(this.input, dev) : null;
    this._remoteCards = bench != null;
    if (this._remoteCards) return this._refreshBenchCards(bench);
    for (const p of this.store.getAudioDeviceProfiles()) {
      if (this._endpointOf(p).ranges.length === 0) continue;   // no range this direction - hide
      $c.append(`<option value="${this._comboProfiles.length}">${p.name}</option>`);
      this._comboProfiles.push(p);
      this._comboNames.push(p.name);
    }
    $c.append(`<option value="${this._comboProfiles.length}">${t('preferences.audio.card.new')}</option>`);
    this._comboProfiles.push(null);   // last = New card...
    this._comboNames.push(null);

    const pick = dev ? this.store.resolveDeviceProfile(dev) : null;
    this._selectByProfile(pick);
    this._rebuildTable();
    return pick;
  }

  /**
   * The same combo over the BENCH's cards (spec 4.3 cards.list) - Java CardSection.refreshBenchCards.
   *
   * No "New card...": a card is created and calibrated where the device is. The range table below is
   * NOT keyed on this combo for the same reason - showing a local card's ranges under a bench card
   * that merely shares its name is the collision this dialog is careful not to make anywhere else;
   * it is keyed on the DEVICE and shows only what the device itself supplied ({@link #_rangeCard}).
   *
   * @returns null always - a bench device resolves to no local card, which is also what stops
   *          {@link #onDeviceChanged} offering to create one.
   */
  _refreshBenchCards(bench) {
    const $c = $(this._comboSel);
    for (const name of bench.names) {
      $c.append(`<option value="${this._comboNames.length}">${name}</option>`);
      this._comboNames.push(name);
    }
    // `bound` is the STAGED pick first: while the dialog lives, the combo shows what the operator
    // chose, even though nothing has been sent yet (the seam resolves staged-before-bound).
    this._selectByName(bench.bound);
    this._rebuildTable();
    return null;
  }

  /** A user-driven device change: re-resolve (visibly switching / clearing to "New card..."), and
   *  when no card resolves for a real device, offer to create one (once per device per session)
   *  (CardSection.onDeviceChanged). Never for a device on a BENCH, whose card is created and
   *  calibrated THERE - a local one made here could only ever be found again by a name collision. */
  async onDeviceChanged() {
    const pick = await this.refresh();
    const dev = this._deviceLabel();
    if (!dev || pick != null || this._remoteCards) {
      // Unbound, a card correlated - or a device on a bench, whose card is created and calibrated
      // THERE; a local one made here could only ever be found again by a name collision.
      await this._offerCalibrationCopy();
      return;
    }
    if (dev === this._lastPromptedDevice) return;
    this._lastPromptedDevice = dev;
    const yes = await this._showConfirm(
      t('preferences.audio.card.noCardAssigned.title'),
      t('preferences.audio.card.noCardAssigned.message', dev));
    if (yes) await this._createNewCard(dev);
  }

  /**
   * A device on a BENCH that has no calibration of its own, whose name this machine
   * does have a calibrated card for, is offered - once, explicitly - to be copied to the bench.
   * The operator is ASKED; the values are never copied silently
   * (Java CardSection.offerCalibrationCopy).
   *
   * Only from a user-driven selection, never from {@link #refresh}: a passive rebuild popping a
   * dialog is the thing this section's card prompt already refuses to do. The offer is silent
   * about a device the bench has calibrated itself, about one this machine cannot calibrate
   * either, and about a calibrationFromDevice card - a QA40x's values are the analyzer's and
   * neither side may overwrite them.
   *
   * Accepting sends spec 4.3's device.setCalibration, which is the bench's store from then on;
   * declining is remembered for the run, so neither switching device and back nor reopening this
   * dialog asks again.
   */
  async _offerCalibrationCopy() {
    if (this._cardSource == null || !this._remoteCards) return;
    const dev = this._deviceLabel();
    if (!dev) return;
    // null and only null: an object is the bench's OWN calibration (nothing to offer), undefined
    // is a device this bench does not offer at all (nothing to copy TO).
    if (this._cardSource.calibration(this.input, dev) !== null) return;
    const local = this.store.resolveDeviceProfile(dev);
    // A card whose values are the DEVICE's own is never pushed: a QA40x generates its card from
    // the calibration it reads out of itself, so there is nothing for a client to copy to a
    // server. The flow refuses it again at the wire, and the bench would refuse it a third
    // time; the offer must not be made in the first place.
    if (local == null || this._endpointOf(local).calibrationFromDevice) return;
    if (this.store.deviceCalibration(dev, this.input) == null) return;   // nothing calibrated to copy
    if (!this._cardSource.mayCopyAsk(this.input, dev)) return;
    const yes = await this._showConfirm(
      t('preferences.audio.card.copyCalibration.title'),
      t('preferences.audio.card.copyCalibration.message', dev, local.name));
    if (!yes) {
      this._cardSource.settleCopyAsk(this.input, dev);   // declined - asked and answered
      return;
    }
    // The WHOLE card goes up (cards.put + device.setCard), its values alone as the fallback.
    // Every outcome is REPORTED, because they are not the same thing to an operator who
    // confirmed "copy card X": the card and its binding, or only the numbers, or a card that is
    // there but bound to nothing. Nothing about the copy happens silently, in either direction.
    const title = t('preferences.audio.card.copyCalibration.title');
    const copied = await this._cardSource.copyCalibration(this.input, dev);
    if (copied === 'VALUES_ONLY') {
      this._showAlert(title, t('preferences.audio.card.copyCalibration.valuesOnly', local.name));
    } else if (copied === 'CARD_UNBOUND') {
      this._showAlert(title, t('preferences.audio.card.bindFailed', local.name));
    } else if (copied !== 'CARD') {
      // NOTHING (or NOTHING_TO_COPY, which the gates above have already excluded): a transport
      // failure is not an answer, so the offer is NOT spent - the next selection may ask again.
      this._showAlert(title, t('preferences.audio.card.copyCalibration.failed', dev));
      return;
    }
    this._cardSource.settleCopyAsk(this.input, dev);
    this.refresh();   // the bench's card list - and its binding - just moved
  }

  /** Selects the combo entry for {@code p} silently; a null / absent profile pins the visible
   *  "New card..." entry as the preselection (CardSection.selectByProfile). */
  _selectByProfile(p) {
    let idx = -1;
    if (p != null) {
      for (let i = 0; i < this._comboProfiles.length - 1; i++) {
        if (this._comboProfiles[i] != null && this._comboProfiles[i].name === p.name) { idx = i; break; }
      }
    }
    if (idx < 0) {
      $(this._comboSel).val(String(this._comboProfiles.length - 1));   // New card... entry
      this._selectedName = null;
    } else {
      $(this._comboSel).val(String(idx));
      this._selectedName = this._comboProfiles[idx].name;
    }
  }

  /** {@link #_selectByProfile} by card NAME - what a bench card list has to select by, since there
   *  is no local profile to match against. A name the list does not offer (a binding to a card the
   *  bench has since dropped) leaves the combo empty, which is the truth (CardSection.selectByName). */
  _selectByName(cardName) {
    const idx = cardName == null ? -1 : this._comboNames.indexOf(cardName);
    if (idx < 0) {
      $(this._comboSel).val('');   // no option carries this value -> nothing selected (deselectAll)
      this._selectedName = null;
    } else {
      $(this._comboSel).val(String(idx));
      this._selectedName = cardName;
    }
  }

  /** The card combo's user-selection handler (CardSection.onCardSelected). */
  async _onCardSelected() {
    const idx = parseInt($(this._comboSel).val(), 10);
    if (!(idx >= 0)) return;
    const dev = this._deviceLabel();
    if (this._remoteCards) { this._bindBenchCard(dev, this._comboNames[idx]); return; }
    if (idx === this._comboProfiles.length - 1) { await this._createNewCard(dev); return; }
    const p = this._comboProfiles[idx];
    this._bindAlias(p, dev);
    // ...and record the CHOICE itself: the alias makes the match rule recognise this
    // device, the binding says the user decided, and the binding is what resolveDeviceProfile
    // consults first - so a second card whose alias also matches can never take it back.
    this.store.bindDeviceToCard(dev, p.name);
    this._selectedName = p.name;
    this._applyAndReadout(dev);
    this._rebuildTable();
  }

  /** The same choice for a device on a BENCH: STAGED, like every other edit in this dialog -
   *  nothing takes effect until OK is clicked. The binding is
   *  stored on the server (spec 4.3 device.setCard) by {@link #commitStagedBindings} on OK; Cancel
   *  sends nothing - which matters more here than anywhere else in this dialog, because a write
   *  that had already left for another machine could not be taken back (CardSection.bindBenchCard). */
  _bindBenchCard(dev, cardName) {
    if (!dev || this._cardSource == null) return;
    this._cardSource.stage(this.input, dev, cardName);
    this._selectedName = cardName;
  }

  /** Sends this direction's staged card choices to the bench - the OK half of
   *  {@link #_bindBenchCard}. Returns the cards the bench would not take, for the dialog's ONE
   *  error dialog; a refusal writes no local mirror either, so what this machine remembers is only
   *  ever what the bench accepted (CardSection.commitStagedBindings). */
  async commitStagedBindings() {
    return this._cardSource ? this._cardSource.commit(this.input) : [];
  }

  /** "New card..." - open the create dialog prefilled with the normalized device name + the
   *  triggering device name as the first match entry, then create the returned profile
   *  (CardSection.createNewCard). */
  async _createNewCard(dev) {
    const seed = new AudioDeviceProfile();
    seed.name = dev ? this.store.normalizeDeviceName(dev) : '';
    if (dev) seed.match.push(dev);
    const p = await this.cardEditor.open(seed,
      this.input ? Capability.INPUT_ONLY : Capability.OUTPUT_ONLY,
      this._currentCardNames(), null, this.prefs.getAdcFsVoltageRms(), this.prefs.getDacFsVoltageAmpl() / SQRT2);
    if (p == null) { await this.refresh(); return; }   // cancelled - restore
    this.store.putAudioDeviceProfile(p);
    if (dev) this.store.bindDeviceToCard(dev, p.name);   // creating a card for a device IS choosing it
    await this.refresh();
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
    await this.refresh();
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

  /** Persists the card the range table is editing back into the store (replace-by-name) - the
   *  shared commit step (Java CardSection.commit). It is {@link #_rangeCard}, not the combo's
   *  selection: on a bench the combo names a card that lives on the SERVER, while the ranges on
   *  screen belong to the local device-provided card the selected device resolves to. */
  _commitSelected() {
    const p = this._rangeCard();
    if (p != null) this.store.putAudioDeviceProfile(p);
  }

  /** The active-range label one channel SHOWS: the staged pick while the Preferences dialog holds
   *  one, else the card's own. Every render and every staging write reads through here, so the
   *  radios track the user's pick without it having been applied anywhere. */
  _activeLabel(ep, right) {
    const staged = this._stagedActive;
    if (staged != null) return right ? staged.activeRangeRight : staged.activeRange;
    return right ? ep.activeRangeRight : ep.activeRange;
  }

  /** Writes a staged active-range pick into the card and applies it - the Preferences OK path.
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

  /** Drops a staged active-range pick - the Preferences Cancel path. Nothing was written, so this
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

  /**
   * The card whose ranges this direction may show, or null when there are none to show
   * (CardSection.rangeCard).
   *
   * THE QUESTION IS WHAT THE CARD CARRIES, NOT WHETHER THE BENCH IS REMOTE. A QA403 on a
   * Phonalyser server does have its attenuator positions here - spec 4.6's qa40x.ranges +
   * qa40x.calibration, rendered into a calibrationFromDevice card by the QA40x settings sync the
   * moment the selection is shown. What the remote branch refuses is the COLLISION: a
   * bench card is looked up by the DEVICE it belongs to, never by the bench card's NAME, and only
   * a calibrationFromDevice endpoint is shown - a card the device itself supplied. An ordinary
   * local card that merely shares a name with one of the server's can therefore never put its own
   * ranges, and its own full scales, under a device calibrated on another machine.
   */
  _rangeCard() {
    let p;
    if (this._remoteCards) {
      const dev = this._deviceLabel();
      p = dev ? this.store.resolveDeviceProfile(dev) : null;
      if (p != null && !this._endpointOf(p).calibrationFromDevice) return null;
    } else {
      p = this._selectedName != null ? this.store.findAudioDeviceProfile(this._selectedName) : null;
    }
    return (p != null && this._endpointOf(p).ranges.length > 0) ? p : null;
  }

  /** Rebuilds the range table from the card that carries this direction's ranges (hidden when
   *  there is none - see {@link #_rangeCard}) (CardSection.rebuildTable). */
  _rebuildTable() {
    const $r = $(this._rangesSel).empty();
    const p = this._rangeCard();
    const show = p != null;
    // Edit only a real, selected LOCAL card: a bench's card is created and calibrated where the
    // device is, and the pencil edits this machine's store.
    $(this._editSel).prop('disabled', !show || this._remoteCards);
    $(this._rangesSel).toggleClass('d-none', !show);
    $(this._rangesLabelSel).toggleClass('d-none', !show);   // the "Ranges" caption
    if (!show) return;
    const ep = this._endpointOf(p);
    ep.ranges.forEach((range, i) => this._createRangeRow($r, ep, range, i === 0));
  }

  /** Builds one range row to the Java CardSection.createRangeRowUi shape: the active radio(s),
   *  editable unique label field(s), and add / remove ICON buttons. INDEPENDENT is TWO mirrored
   *  groups sharing one range label - [Left] caption + radio + label field, [Right] caption + radio
   *  + mirrored label field - with the Left/Right captions carried by every row but visible only on
   *  row 0 (an invisible caption still reserves its grid cell so the columns line up across rows).
   *  Row 0's remove is hidden but reserves its space. The full-scale value is NOT editable here -
   *  the crosshair Calibrate flows own it; the row only names + marks the active one(s). */
  _createRangeRow($container, ep, range, isRow0) {
    const independent = ep.channels === DeviceChannelMode.INDEPENDENT;
    const $row = $('<div class="card-range-row"></div>').toggleClass('independent', independent);

    const activeTip = t('preferences.audio.range.active.tooltip');
    const labelTip = t('preferences.audio.range.label.tooltip');
    // An invisible caption keeps its cell (visibility:hidden, not display:none) so columns align.
    const caption = (key) => $(`<span class="range-caption small">${t(key)}</span>`).css('visibility', isRow0 ? '' : 'hidden');
    // SHOWN text, not the key: a QA40x input row reads `N "dBV" real N dBFS or (N−9) dBV`
    // while its key stays the plain `N dBV` the protocol decodes back into a register
    // value (DeviceRange.displayLabelOrKey - the desktop dialog does exactly this).
    // Everything functional below keeps the KEY: the radios compare range.label, and the
    // rename flow edits it. An editable row has no display label, so the field still
    // round-trips its key; a device-owned or bench row is read-only.
    const mkLabel = () => $(`<input type="text" class="form-control form-control-sm range-label" title="${labelTip}">`).val(range.displayLabelOrKey());

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
    // "N dBV" -> reg 0x05 / 0x06). Adding, removing or renaming a row would either invent a position
    // the hardware does not have or break that decode, so the whole row is read-only except the
    // active radio. The values come from the device's own calibration page, hence
    // calibrationFromDevice - the same flag that makes the Calibrate flows refuse this endpoint.
    // A BENCH row is the same rule for a different reason (Java CardSection: deviceProvided =
    // isCalibrationFromDevice() || remoteCards) - a bench card's shape is edited where the device is.
    const deviceProvided = ep.calibrationFromDevice || this._remoteCards;
    if (deviceProvided) {
      $label.prop('readonly', true);
      if ($labelRight) $labelRight.prop('readonly', true);
      $add.prop('disabled', true).css('visibility', 'hidden');
      $rem.prop('disabled', true).css('visibility', 'hidden');
    }
    $row.append($add, $rem);
    $container.append($row);

    $active.on('change', () => { if ($active.is(':checked')) this._userSetActive(ep, range, false); });
    if ($activeRight) $activeRight.on('change', () => { if ($activeRight.is(':checked')) this._userSetActive(ep, range, true); });
    // Rename / add / remove are BOUND ONLY on a row the operator may edit - the Java dialog wraps
    // exactly these listeners in `if (!deviceProvided)` (CardSection.createRangeRowUi).
    //
    // Not a tidiness: `readonly` still focuses and still fires blur (only `disabled` suppresses
    // events, which is why the ± buttons use it), so a bound rename would commit whatever the field
    // SHOWS the moment the operator clicked in to read it and clicked away - and what it shows on a
    // QA40x input row is the verbose display text. That would land in the row's protocol KEY and be
    // persisted, breaking the register decode for that position.
    if (!deviceProvided) {
      const rename = ($edited) => this._userRenameRange(ep, range, $edited, $label, $labelRight);
      $label.on('change blur', () => rename($label));
      if ($labelRight) $labelRight.on('change blur', () => rename($labelRight));
      $add.on('click', () => this._userAddRange(ep));
      if (!isRow0) $rem.on('click', () => this._userRemoveRange(ep, range));
    }
  }

  _userSetActive(ep, range, right) {
    // While the Preferences dialog is open the pick is STAGED, not applied: it must not reach the
    // card, the store, the full-scale prefs or the device until OK. (It used to write straight
    // through, so the new range was live the instant the radio moved and only a Cancel undid it.)
    if (this._isStaging()) {
      const card = this._rangeCard();
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
      // Apply ONLY the changed channel: a right-radio click must not
      // re-derive the untouched left scalar (nor fall back to row 0 on a dangling label). Persist
      // the card, then push just that channel's full-scale.
      this._commitSelected();
      const ch = right ? 'R' : 'L';
      if (this.input) this.store.applyInputDeviceProfileChannel(dev, ch);
      else this.store.applyOutputDeviceProfileChannel(dev, ch);
      this._onChanged();
    } else {
      // LINKED / MONO: one row drives both scalars -> the full apply.
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
   *  mirror in LINKED / MONO is ignored) - CardSection.syncLabelFields. */
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
