/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.common.CorrectionStore — the
// owner of one pane's loaded frequency-response correction state: an ordered list
// of loaded .frc Entry calibrations plus the calibration wizard's direct-loopback
// buffer. The consumer chains the divides through all entries (in order) plus the
// transient `direct` at draw / measurement time, so multiple files compose into a
// single correction.
//
// Change notification is config, not a callback chain on the Java side (the
// constructor takes a MessageBus event name); the web has no such bus here, so the
// change-event is an injected callback fired after every mutation (null = silent,
// e.g. an offscreen screenshot clone). The wizard captures a snapshot() on open and
// calls restore() on cancel so a cancelled wizard doesn't leave half-measured state.
//
// All access is on the single UI thread, so no internal synchronisation is needed.

/**
 * One loaded calibration plus the file path it came from. Mirrors
 * CorrectionStore.Entry. `withNoise` is FFT-only (the FreqResp instance
 * always divides the whole trace and leaves it false).
 *
 * @typedef {Object} CorrectionEntry
 * @property {{left:object,right:object}} calibration the StereoFreqRespCalibration {left,right}
 * @property {string}  path
 * @property {boolean} withNoise
 */

export class CorrectionStore {
  /**
   * @param {string} label             short identifier for log lines (e.g. "FreqResp")
   * @param {?(()=>void)} changedEvent  callback fired after every mutation, or null for a silent store
   */
  constructor(label, changedEvent = null) {
    this.label = label;
    this._changed = changedEvent;
    /** @type {CorrectionEntry[]} */
    this._entries = [];
    /** The wizard's page-1 loopback measurement, or null when the wizard hasn't
     *  run since the last clear. */
    this._direct = null;
  }

  // ---------------------------------------------------------------------------
  // Entries list
  // ---------------------------------------------------------------------------

  /** A copy of every loaded calibration in the order the user added them. Empty
   *  when none loaded. */
  getEntries() {
    return this._entries.slice();
  }

  /** The wizard's page-1 transient calibration, or null. */
  getDirect() {
    return this._direct;
  }

  /** Appends a calibration to the end of the entries list and fires one change
   *  notification. `withNoise` is honoured only by an FFT instance. */
  addEntry(calibration, path, withNoise = false) {
    if (!calibration || path == null) {
      throw new Error('calibration and path must be non-null');
    }
    this._entries.push({ calibration, path, withNoise });
    this._fire();
  }

  /** Clears every entry and fires one change notification (no-op when already empty). */
  clearAll() {
    if (this._entries.length === 0) return;
    this._entries.length = 0;
    this._fire();
  }

  // ---------------------------------------------------------------------------
  // Calibration-wizard compatibility shims (map setCurrent / getCurrent onto the
  // multi-row model exactly as the Java store does).
  // ---------------------------------------------------------------------------

  /** Calibration in row 0, or null when no entries are loaded. */
  getCurrent() {
    return this._entries.length === 0 ? null : this._entries[0].calibration;
  }

  /** Path of row 0, or null when no entries are loaded. */
  getCurrentPath() {
    return this._entries.length === 0 ? null : this._entries[0].path;
  }

  /** Clears every loaded entry, seeds row 0 with this calibration, and drops any
   *  stale wizard transient so the consumer doesn't double-correct after Apply. */
  setCurrent(calibration, path) {
    if (!calibration || path == null) {
      throw new Error('setCurrent requires non-null calibration and path');
    }
    this._entries.length = 0;
    this._entries.push({ calibration, path, withNoise: false });
    this._direct = null;
    this._fire();
  }

  /** Alias for clearAll() — wizard / older callers. */
  clearCurrent() {
    this.clearAll();
  }

  /** Replaces the direct (wizard page-1) calibration buffer. Pass null to clear.
   *  Fires a change notification so the consumer applies the new transient on top
   *  of the entries list. */
  setDirect(directCal) {
    if (this._direct === directCal) return;
    this._direct = directCal;
    this._fire();
  }

  // ---------------------------------------------------------------------------
  // Snapshot / restore (wizard cancel)
  // ---------------------------------------------------------------------------

  /** Captures every entry + direct so the wizard can restore them on cancel. */
  snapshot() {
    return { entries: this._entries.slice(), direct: this._direct };
  }

  /** Restores from a prior snapshot() and fires one change notification when the
   *  entries list actually moved (element-wise identity comparison, mirroring the
   *  Java Entry equals on calibration record identity + path + withNoise). */
  restore(s) {
    const changed = !this._entriesEqual(this._entries, s.entries);
    this._entries = s.entries.slice();
    this._direct = s.direct;
    if (changed) this._fire();
  }

  _entriesEqual(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      if (a[i].calibration !== b[i].calibration
          || a[i].path !== b[i].path
          || a[i].withNoise !== b[i].withNoise) return false;
    }
    return true;
  }

  _fire() {
    if (this._changed) this._changed();
  }
}
