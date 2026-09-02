/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// The Preferences dialog's card resolution for a REMOTE selection (Java PreferencesDialog's
// bench-card branch, spec 4.3): where the card combo's entries come from while a server's backend
// is selected, where a pick is staged, sent and read back, and the once-per-run copy offer. On a
// bench, the card a remote device uses is the operator's choice, made from the SERVER's cards and
// stored there (cards.list / device.setCard), mirrored locally under the server's id. Every LOCAL
// selection answers null / nothing here, and the dialog falls back to this machine's card store.
//
// Which bench and which device the shown name refers to is the CalibrationStore's one answer; this
// type never resolves it a second way.

import { BenchCards } from './bench-cards.js';

export class BenchCardSource {
  /**
   * @param calibrationStore the CalibrationStore - the bench selection (remote / ref / serverId).
   * @param benchCards       the BenchCards seam (spec 4.3 cards.list / device.setCard / cards.put).
   * @param copyOffers       the CalibrationCopyOffers register - held for the app's lifetime, not
   *                         the dialog's, and never persisted.
   */
  constructor(calibrationStore, benchCards, copyOffers) {
    this._store = calibrationStore;
    this._benchCards = benchCards;
    this._copyOffers = copyOffers;
  }

  /** The bench's card names + the card bound to the device, or null for a LOCAL selection (this
   *  machine's cards, unchanged). A staged pick wins over the bound card until it is committed. */
  async cards(input, device) {
    const value = this._store.remote();
    if (value == null) return null;
    const ref = this._store.ref(input, device);
    const name = ref ? ref.name : device;
    const staged = name ? this._benchCards.stagedCard(value, input, name) : null;
    return {
      names: await this._benchCards.list(value, input),
      bound: staged != null ? staged : this._benchCards.boundCard(this._store.serverId(), ref, name),
    };
  }

  /** Stages a card pick for the device; sent on commit. Nothing for a local selection. */
  stage(input, device, cardName) {
    const value = this._store.remote();
    if (value == null) return;
    const ref = this._store.ref(input, device);
    this._benchCards.stage(value, input, ref ? ref.name : device, cardName);
  }

  /** Spec 4.3's `cal`: what the SERVER stores for the device, which is what a measurement on it
   *  is scaled by - null when it has none, and only then does the local card store get a say.
   *  UNDEFINED when this bench does not offer the device at all (or none is remote): there is
   *  then nothing to copy TO, which is a different answer from "it has none" and the copy offer
   *  has to tell them apart (Java tests `ref == null` and `ref.calibration() != null`
   *  separately). */
  calibration(input, device) {
    const ref = this._store.remote() == null ? null : this._store.ref(input, device);
    return ref ? ref.calibration : undefined;
  }

  /** Whether the copy offer may still be put to the operator for this bench + direction + device
   *  - once per run, but only a SETTLED question is remembered, so a wire glitch does not cost
   *  the operator the offer. */
  mayCopyAsk(input, device) {
    const value = this._store.remote();
    if (value == null) return false;
    const ref = this._store.ref(input, device);
    return this._copyOffers.mayAsk(value, input, ref ? ref.name : device);
  }

  /** Remembers that the copy question was answered for this bench + direction + device. */
  settleCopyAsk(input, device) {
    const value = this._store.remote();
    if (value == null) return;
    const ref = this._store.ref(input, device);
    this._copyOffers.settle(value, input, ref ? ref.name : device);
  }

  /** The YES branch: this machine's WHOLE card becomes the bench's - cards.put + the binding -
   *  with its values alone as the fallback when the bench will not take the card. Answers a
   *  BenchCards.Copied outcome, which the dialog turns into what it tells the operator; nothing
   *  of this is written into this machine's storage. */
  async copyCalibration(input, device) {
    const value = this._store.remote();
    if (value == null) return BenchCards.Copied.NOTHING;
    const ref = this._store.ref(input, device);
    return this._benchCards.propagateLocalCard(value, this._store.serverId(), ref, device, input);
  }

  /** Sends every staged pick of the direction to the bench (device.setCard). Answers the card
   *  names the bench refused, for the dialog's report. */
  async commit(input) {
    const value = this._store.remote();
    if (value == null) return [];
    const refused = [];
    for (const [name, cardName] of this._benchCards.stagedFor(value, input)) {
      const bound = await this._benchCards.bind(value, this._store.serverId(),
        this._store.ref(input, name), cardName);
      if (!bound) refused.push(cardName);
    }
    return refused;
  }

  /** Drops every staged pick - the dialog clears them on every open. */
  clearStaged() { this._benchCards.clearStaged(); }
}
