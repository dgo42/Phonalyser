/*
 * Phonalyser web - the card choices a Phonalyser server offers, and the operator's pick.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.sound.BenchCards - the remote half of the rule that
 * the card a device uses (QA402 vs QA403, or any other device) is the operator's own selection,
 * and that the device<->card binding is persisted.
 *
 * THE BINDING LIVES WHERE THE DEVICE LIVES, for the same reason its calibration does (spec 4.3):
 * the card decides which calibration is in force, so a choice kept only on this client would leave
 * the bench answering a different card to everyone else - including this client's next session. It
 * is mirrored locally under the server's id as well, which is
 * what lets the chooser show the remembered pick before, or without, an answer from the bench.
 *
 * Distinct from a calibration WRITE on purpose: this only chooses WHICH card is used. The protocol
 * draws the same line - device.setCard is accepted for a calibrationFromDevice card, which
 * device.setCalibration is not (the analyzer reports its own values; they are not a client's to
 * overwrite).
 */
import { MessageType, NetFields } from './net-proto.js';
import { refInto } from './net-device-ref.js';

export class BenchCards {

  /**
   * What a propagation managed to put on the bench (Java BenchCards.Copied). Every value but
   * {@link #NOTHING} is a settled answer the operator can be told and the offer need not be
   * repeated for.
   */
  static Copied = Object.freeze({
    /** The whole card, and the device is bound to it. */
    CARD: 'CARD',
    /** The card is on the bench but the device is NOT bound to it - the binding was refused. */
    CARD_UNBOUND: 'CARD_UNBOUND',
    /** The bench would not take the card, but its full scales landed. */
    VALUES_ONLY: 'VALUES_ONLY',
    /** Nothing reached the bench - the only outcome worth offering again. */
    NOTHING: 'NOTHING',
    /** This machine had nothing worth sending: no card for the name, one whose row was never
     *  calibrated, or one whose values are the DEVICE's own. */
    NOTHING_TO_COPY: 'NOTHING_TO_COPY',
  });

  /**
   * @param {Object} deps
   * @param {{call: Function, callLocked: Function}} deps.bench the remote-backend request seam
   *        (NetDeviceManager) - injected, because the session is the shell's
   * @param {import('../store/device-profiles.js').DeviceProfileStore} deps.store the local mirror
   */
  constructor({ bench, store }) {
    this._bench = bench;
    this._store = store;
    /**
     * Picks made in the dialog but not yet committed, keyed by bench, direction and device name.
     * NOTHING here has reached the bench: the Preferences dialog applies on OK and only on OK,
     * so Cancel has to mean that nothing happened anywhere - including on another
     * machine, where it could not be taken back. The direction is in the key because a duplex
     * device is listed under one name in both, and they are two separate choices.
     * @type {Map<string, string>}
     */
    this._staged = new Map();
  }

  /**
   * Spec 4.3's cards.list, filtered to the cards that can serve `input` - a card with no row for a
   * direction cannot calibrate anything there, and offering it would only invite a binding that
   * measures nothing. Empty when the bench cannot be reached, which leaves the chooser showing
   * nothing rather than a stale list from another server.
   *
   * @param {string} backendValue the selected "net:<backend>" value
   * @param {boolean} input which direction the chooser is for
   * @returns {Promise<string[]>} the card names offered
   */
  async list(backendValue, input) {
    const answer = await this._bench.call(backendValue, MessageType.CARDS_LIST, {});
    const cards = answer == null ? null : answer[NetFields.CARDS];
    if (!Array.isArray(cards)) {
      console.warn(`Card binding: ${backendValue} did not answer its card list`);
      return [];
    }
    return cards
      .filter((card) => card != null && typeof card[NetFields.NAME] === 'string'
        && card[input ? NetFields.INPUT : NetFields.OUTPUT] === true)
      .map((card) => card[NetFields.NAME]);
  }

  /** Records a pick without sending anything - see {@link #_staged}. */
  stage(backendValue, input, deviceName, cardName) {
    this._staged.set(this._key(backendValue, input, deviceName), cardName);
  }

  /** The pick staged for this device, or null when the operator has not chosen one in this dialog
   *  - what the chooser shows while it is open, in front of whatever is actually in force. */
  stagedCard(backendValue, input, deviceName) {
    const staged = this._staged.get(this._key(backendValue, input, deviceName));
    return staged === undefined ? null : staged;
  }

  /** Every pick staged for one bench and direction, device name -> card name - what the OK commit
   *  walks. Empty when nothing was chosen, which is the usual case and the one where OK must send
   *  nothing at all. */
  stagedFor(backendValue, input) {
    const prefix = this._key(backendValue, input, '');
    const out = new Map();
    for (const [key, card] of this._staged) {
      if (key.startsWith(prefix)) out.set(key.substring(prefix.length), card);
    }
    return out;
  }

  /** Drops every staged pick - a new dialog session, or one that was cancelled. */
  clearStaged() {
    this._staged.clear();
  }

  _key(backendValue, input, deviceName) {
    return `${backendValue}|${input ? 'in' : 'out'}|${deviceName}`;
  }

  /**
   * Spec 4.3's device.setCard: tells the bench which of its cards the operator chose for `device`,
   * then mirrors the choice locally under the server's id. A null / blank `cardName` unbinds.
   *
   * Reached from the dialog's OK and from nowhere else - the pick itself only {@link #stage}s.
   *
   * Sent under the device lock the bench requires for it: the binding changes what every
   * measurement on that device means, so it fails honestly while another client is measuring on it
   * rather than moving the ground under them. The mirror is written ONLY after the bench accepted
   * - a local record of a choice the bench never took would show the operator a pick that is not
   * in force anywhere.
   *
   * @param {string} backendValue the selected "net:<backend>" value
   * @param {string} serverId the bench's id - the mirror's key half
   * @param {Object} device the catalogue's own ref
   * @param {?string} cardName the chosen card, or null / '' to unbind
   * @returns {Promise<boolean>} whether the bench stored the binding
   */
  async bind(backendValue, serverId, device, cardName) {
    if (device == null) return false;
    const answer = await this._bench.callLocked(backendValue, MessageType.DEVICE_SET_CARD,
      refInto(device, { [NetFields.CARD]: (cardName == null || cardName === '') ? null : cardName }));
    if (answer == null) {
      console.warn(`Card binding: ${backendValue} did not bind '${device.name}' to card `
        + `'${cardName}'`);
      return false;
    }
    this._store.bindDeviceToCard(this._store.deviceBindingKey(serverId, device.name), cardName);
    console.info(`Card binding: ${backendValue} bound '${device.name}' to card '${cardName}'`);
    return true;
  }

  /**
   * Spec 4.3's device.setCalibration: writes the two full-scale RMS volts of a SERVER-owned device
   * into the card the bench has for it. Locked on exactly that device+direction, because it
   * changes what every measurement on it means.
   *
   * NOT offered for a calibrationFromDevice card (the QA40x): its values come from the analyzer
   * itself and are not a client's to write - the bench answers BAD_REQUEST, which arrives here as
   * a plain false, since a refusal of a write the UI should not have offered is not an error the
   * operator can act on.
   *
   * @returns {Promise<boolean>} whether the bench stored the values
   */
  async calibrate(backendValue, device, fsRmsLeft, fsRmsRight) {
    if (device == null) return false;
    const answer = await this._bench.callLocked(backendValue, MessageType.DEVICE_SET_CALIBRATION,
      refInto(device, { [NetFields.FS_RMS_LEFT]: fsRmsLeft, [NetFields.FS_RMS_RIGHT]: fsRmsRight }));
    if (answer == null) {
      console.warn(`Calibration: ${backendValue} did not store the full scale of '${device.name}'`);
      return false;
    }
    return true;
  }

  /**
   * Spec 4.3's cards.put: hands `card` to the bench, which stores it in the devices storage of
   * the machine the device is plugged into (Java BenchCards.create).
   *
   * No lock and none needed: a card nothing is bound to is in force nowhere, so it cannot move a
   * measurement under anybody. The {@link #bind} that follows is the write that does.
   *
   * @returns {Promise<boolean>} whether the bench stored it - false covers every refusal alike
   *          (a name it already has, a card it will not take, an unreachable bench), because a
   *          refusal reaches this client as a plain "no"
   */
  async create(backendValue, card) {
    if (card == null || card.name == null || card.name === '') return false;
    const answer = await this._bench.call(backendValue, MessageType.CARDS_PUT,
      { [NetFields.CONTENT]: this._store.cardToMap(card) });
    if (answer == null) {
      console.warn(`Card: ${backendValue} would not take a card named '${card.name}'`);
      return false;
    }
    console.info(`Card: '${card.name}' stored on ${backendValue}`);
    return true;
  }

  /**
   * {@link #create} then {@link #bind}: the card exists on the bench AND the device uses it,
   * which is the only state that changes what the operator measures (Java createAndBind).
   *
   * The half-done case is answered as itself. A create that lands and a bind that does not
   * (another client holds the device) leaves a real card on the bench that nothing uses -
   * reporting that as "nothing happened" would send the operator round the same flow, where the
   * create now fails on the name it made itself. The card is deliberately NOT unwound: it is
   * correct, it is theirs, and binding it is one retry away.
   *
   * @returns {Promise<string>} a {@link BenchCards.Copied} outcome
   */
  async createAndBind(backendValue, serverId, device, card) {
    if (!await this.create(backendValue, card)) return BenchCards.Copied.NOTHING;
    return await this.bind(backendValue, serverId, device, card.name)
      ? BenchCards.Copied.CARD : BenchCards.Copied.CARD_UNBOUND;
  }

  /**
   * The bench has no calibration for a device it owns and THIS machine has
   * a card that recognises it - so the card goes UP, whole (Java BenchCards.propagateLocalCard).
   *
   * The whole card, not the pair of numbers it happens to hold at this moment: the range table,
   * the channel mode and the recognition list are what make the values mean something, and a
   * bench told only "1.25 / 1.26 Vrms" would store them on a bare one-row card that no longer
   * describes the box. The device's own name is added to the COPY's match list on the way, so the
   * bench recognises the device even before the binding lands.
   *
   * A card whose values are the DEVICE's own (a QA40x reads them from its own EEPROM) is
   * never pushed: the analyzer generates that card from what it reads out of itself, so there is
   * nothing for a client to copy to a server. The bench would refuse the content anyway
   * (createCard answers BAD_REQUEST for a calibrationFromDevice card), but the offer must not be
   * made in the first place - those numbers belong to an analyzer, not to a machine.
   *
   * <p>The name goes as it is. A suffixed retry would put a second card describing one box on the
   * bench under a name the operator never chose; instead a refusal falls back to the values alone
   * through device.setCalibration, which still leaves the device calibrated.
   *
   * NOTHING is written to this machine's storage: the local card is the SOURCE, and
   * the values live on the server from here on.
   *
   * @returns {Promise<string>} a {@link BenchCards.Copied} outcome, for the caller to tell the
   *          operator what actually reached the bench
   */
  async propagateLocalCard(backendValue, serverId, device, deviceName, input) {
    const local = this._localCopy(deviceName);
    if (local == null) return BenchCards.Copied.NOTHING_TO_COPY;
    const endpoint = input ? local.input : local.output;
    if (endpoint == null || endpoint.calibrationFromDevice) return BenchCards.Copied.NOTHING_TO_COPY;
    if (this._store.deviceCalibration(deviceName, input) == null) {
      return BenchCards.Copied.NOTHING_TO_COPY;
    }
    local.bindDeviceName(deviceName);
    const put = await this.createAndBind(backendValue, serverId, device, local);
    if (put !== BenchCards.Copied.NOTHING) {
      // CARD, or CARD_UNBOUND - either way the card IS on the bench with its values in it, so
      // there is nothing left for the values-only fallback to add; what is missing is a binding.
      return put;
    }
    return await this.copyCardCalibrationToBench(backendValue, device, deviceName, input)
      ? BenchCards.Copied.VALUES_ONLY : BenchCards.Copied.NOTHING;
  }

  /** A DETACHED copy of the local card that recognises `deviceName` - getAudioDeviceProfiles
   *  hands out deep copies, so binding the device name into the one we SEND cannot touch this
   *  machine's store (Java BenchCards.localCopy). */
  _localCopy(deviceName) {
    const live = this._store.resolveDeviceProfile(deviceName);
    if (live == null) return null;
    for (const copy of this._store.getAudioDeviceProfiles()) {
      if (copy.name != null && live.name != null
          && copy.name.toLowerCase() === live.name.toLowerCase()) {
        return copy;
      }
    }
    return null;
  }

  /**
   * Sends the calibration THIS machine's card store holds for `deviceName` to the bench that owns
   * the device - the operator-confirmed copy: when the server has no calibration and this machine
   * has a local card of that name, the operator is ASKED whether to copy its values across; the
   * copy is never silent (Java CalibrationStore.copyCardCalibrationToBench; the web has no separate
   * calibration store, and this type already owns both the local card store and the wire half).
   *
   * No measurement happens here and no scalar moves: the values are already this installation's,
   * and the bench is simply told what they are so it can answer them to every client from then on
   * (spec 4.3 `cal`). The caller asks first - this is the YES branch, never a fallback.
   *
   * @returns {Promise<boolean>} true when the bench stored the pair; false when this machine has no
   *          calibrated card for the name, or the bench would not take it
   */
  async copyCardCalibrationToBench(backendValue, device, deviceName, input) {
    const cal = this._store.deviceCalibration(deviceName, input);
    if (cal == null) return false;
    // The card holds RMS in BOTH directions (the DAC's peak amplitude is derived from it on the
    // way to the scalar, not stored), and spec 4.3 carries RMS - so the pair goes out unconverted.
    return this.calibrate(backendValue, device, cal.fsLeft, cal.fsRight);
  }

  /**
   * Which card the chooser should show as bound: the BENCH's own answer (spec 4.3's `card` field,
   * which every ev.devices.changed refreshes), else the local mirror of the last pick made from
   * this installation.
   *
   * The bench wins because it is authoritative - another operator may have re-bound the device
   * since - and the mirror only covers the moment before the catalogue has been read, or a bench
   * too old to send the field.
   */
  boundCard(serverId, device, deviceName) {
    if (device != null && device.boundCard != null) return device.boundCard;
    return this._store.boundCardName(this._store.deviceBindingKey(serverId, deviceName));
  }
}
