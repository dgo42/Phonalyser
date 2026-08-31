/*
 * Phonalyser web - switchable debug logging.
 *
 * Off by default. Enable from the browser console with `phonalyserDebug(true)` (persists via
 * localStorage) or by adding `?debug=1` to the URL; turn it back off with `phonalyserDebug(false)`.
 * Use debug(...) instead of console.log for diagnostic output so it can be silenced.
 * GNU AGPL v3 or later.
 */
let enabled = false;
try {
  enabled = localStorage.getItem('phonalyser.debug') === '1'
    || /[?&]debug=1\b/.test(location.search || '');
} catch (e) { /* no localStorage / location */ }

/** Logs to the console ONLY when debug logging is enabled. Same signature as console.log. */
export function debug(...args) { if (enabled) console.log(...args); }

/** True while debug logging is on. */
export function isDebug() { return enabled; }

/**
 * Everything a worker failure carries, as one line: what it said, and WHERE. A worker whose
 * script - or a module that script imports - cannot be loaded reports through the same event as
 * an uncaught throw inside it, so a bare "error" leaves the two indistinguishable and neither of
 * them locatable. The event's filename / lineno / colno are what separate them, and they are the
 * only address of a load failure that a caller can act on.
 *
 * @param ev an ErrorEvent, a plain Error, a string, or nothing.
 * @returns {string} the message, followed by "at file:line:col" when the event carries a location.
 */
export function describeErrorEvent(ev) {
  if (ev == null) return 'no detail';
  if (typeof ev === 'string') return ev;
  const msg = ev.message || (ev.error && ev.error.message) || String(ev.type || 'error');
  if (!ev.filename) return msg;
  return msg + ' at ' + ev.filename + ':' + (ev.lineno || 0) + ':' + (ev.colno || 0);
}

/** Milliseconds as the diagnostic logs state them: one decimal below 10 ms, whole numbers above.
 *  The stages that decide a frame are tens to hundreds of milliseconds, so their fractions are
 *  noise; a sub-millisecond stage only has to be visibly small. */
export function formatMs(v) {
  return v < 10 ? v.toFixed(1) : String(Math.round(v));
}

/** Turns debug logging on/off (persisted). Exposed globally as window.phonalyserDebug(on). */
export function setDebug(on) {
  enabled = !!on;
  try { localStorage.setItem('phonalyser.debug', enabled ? '1' : '0'); } catch (e) { /* ignore */ }
  console.log('[phonalyser] debug logging ' + (enabled ? 'ON' : 'OFF'));
}

if (typeof window !== 'undefined') window.phonalyserDebug = setDebug;
