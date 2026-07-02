/*
 * Phonalyser web — switchable debug logging.
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

/** Turns debug logging on/off (persisted). Exposed globally as window.phonalyserDebug(on). */
export function setDebug(on) {
  enabled = !!on;
  try { localStorage.setItem('phonalyser.debug', enabled ? '1' : '0'); } catch (e) { /* ignore */ }
  console.log('[phonalyser] debug logging ' + (enabled ? 'ON' : 'OFF'));
}

if (typeof window !== 'undefined') window.phonalyserDebug = setDebug;
