/*
 * Phonalyser web - which copy-calibration offers this RUN has already put to the operator.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.sound.CalibrationCopyOffers: the offer is made once,
 * a calibration is never copied silently, and a decline is remembered for the session.
 *
 * Session means the APPLICATION's, not the dialog's: an operator who said no must not be asked
 * again the next time they open Preferences, which is exactly when they would be looking at that
 * device. It is deliberately NOT persisted - a decline is about this sitting, not about the
 * installation, and a bench that gets calibrated properly next week should be offered again.
 *
 * Keyed per bench, DIRECTION and device. A duplex device is listed under one name in both
 * directions and is two separate uncalibrated endpoints; without the direction in the key, copying
 * the input's calibration would silently spend the one offer the output was owed and leave it
 * uncalibrated for the session.
 */
export class CalibrationCopyOffers {

  constructor() {
    /** @type {Set<string>} */
    this._settled = new Set();
  }

  /**
   * Whether this bench + direction + device may still be put to the operator - true until the
   * question has been SETTLED (Java CalibrationCopyOffers.mayAsk).
   *
   * Asking is not settling. A wire glitch, a bench that went away between the confirm and the
   * write, a device another client took a second earlier - none of those is an answer, and
   * burning the offer on one would cost the operator that device for the whole run, since this
   * memory is deliberately not persisted. The caller records the outcome with {@link #settle}
   * once it HAS one: a decline, or an attempt that reached the bench.
   */
  mayAsk(backendValue, input, deviceName) {
    return !this._settled.has(this._key(backendValue, input, deviceName));
  }

  /** Records that the question has been answered - the operator declined, or something actually
   *  landed on the bench. Idempotent (Java CalibrationCopyOffers.settle). */
  settle(backendValue, input, deviceName) {
    this._settled.add(this._key(backendValue, input, deviceName));
  }

  _key(backendValue, input, deviceName) {
    return `${backendValue}|${input ? 'in' : 'out'}|${deviceName}`;
  }
}
