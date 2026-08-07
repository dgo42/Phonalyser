/*
 * Phonalyser web - the passage of time, injected.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.Ticker + ScheduledTicker.
 *
 * The connection's keepalive is a periodic task and a test must be able to advance it
 * instantly and deterministically - so NetConnection never reads a clock and never sleeps: it
 * is handed a ticker and asks it to call back. One ticker drives one task and belongs to
 * whoever owns that task, so stop() needs no handle: closing the owner stops its ticker.
 *
 * The Ticker CONTRACT is two methods:
 *   start(periodMs, task) - call `task` every periodMs, first call one period from now;
 *                           calling it twice replaces the previous task.
 *   stop()                - stop the callbacks; idempotent, teardown paths call it blindly.
 */

/** The production Ticker: a fixed-rate task on the platform timer (Java ScheduledTicker,
 *  whose scheduler the caller likewise owns). */
export class IntervalTicker {

  #handle = null;

  /** @param {number} periodMs @param {() => void} task */
  start(periodMs, task) {
    this.stop();
    this.#handle = setInterval(task, periodMs);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this.#handle && typeof this.#handle.unref === 'function') this.#handle.unref();
  }

  stop() {
    if (this.#handle != null) { clearInterval(this.#handle); this.#handle = null; }
  }
}
