/*
 * Phonalyser web - service-worker registration + "new version available" prompt.
 * Registers sw.js, polls for updates, and shows a reload banner when a newer
 * deploy is detected (sw.js VERSION bump -> new worker waiting). Accepting the
 * banner skips waiting and reloads into the fresh cache.
 * GNU AGPL v3 or later.
 */
const POLL_MS = 5 * 60 * 1000;

// The FIRST visit to a static host is not yet under the service worker, so the document arrives
// without the isolation headers the worker adds and the page is not cross-origin isolated - the
// FFT's divided transform then stays on one thread for the whole session. One reload puts the
// document through the worker and the isolation holds from then on.
//
// This reload is allowed exactly once per tab, and only here - inside the load handler, before
// any device is open. A reload while capture or generation is running tears down worklets,
// workers and both AudioContexts mid-audio and can take the renderer with it, which is why the
// update path below reloads solely on a deliberate click. The key is written BEFORE the reload,
// so a host whose headers never take effect reloads once and then leaves the page alone rather
// than looping.
const COI_RELOAD_KEY = 'pf-coi-reload';

function reloadOnceForIsolation() {
  if (globalThis.crossOriginIsolated) return;                 // already isolated - nothing to do
  if (!navigator.serviceWorker.controller) return;            // not under the worker yet
  try {
    if (sessionStorage.getItem(COI_RELOAD_KEY) === '1') return;
    sessionStorage.setItem(COI_RELOAD_KEY, '1');
  } catch (e) { return; }                                     // storage blocked -> never reload
  location.reload();
}

function banner(onReload) {
  if (document.getElementById('pf-update-banner')) return;
  const bar = document.createElement('div');
  bar.id = 'pf-update-banner';
  bar.style.cssText = 'position:fixed;left:50%;bottom:16px;transform:translateX(-50%);z-index:3000;'
    + 'display:flex;gap:12px;align-items:center;background:#1f6feb;color:#fff;'
    + 'padding:8px 12px 8px 16px;border-radius:6px;box-shadow:0 6px 18px rgba(0,0,0,.35);'
    + 'font:13px/1.3 "Segoe UI",system-ui,sans-serif;';
  bar.innerHTML = '<span>A new version of Phonalyser is available.</span>';
  const reload = document.createElement('button');
  reload.textContent = 'Reload';
  reload.style.cssText = 'background:#fff;color:#1f6feb;border:0;border-radius:4px;padding:4px 12px;font-weight:600;cursor:pointer;';
  reload.onclick = onReload;
  const close = document.createElement('button');
  close.textContent = '×';
  close.style.cssText = 'background:none;border:0;color:#fff;font-size:18px;line-height:1;cursor:pointer;';
  close.onclick = () => bar.remove();
  bar.append(reload, close);
  document.body.appendChild(bar);
}

// Reload ONLY when the user deliberately accepts the update banner. The old code
// reloaded on ANY controllerchange - which ALSO fires on the first-load
// clients.claim() (an unwanted auto-reload) and, critically, could fire WHILE
// capture/generation is running: a mid-audio location.reload() tears the whole
// audio graph (worklets, workers, two AudioContexts) down abruptly and can crash
// the renderer. This flag scopes the reload to an intentional update only.
let updating = false;

// Dev / debugging: do NOT install the service worker - it would serve a CACHED bundle
// so freshly-built source never runs and breakpoints miss. Skip it on localhost, OR on
// ANY host (e.g. an SSL dev box like `albus2`) when the dev flag is set. The flag is
// sticky so it survives reloads - set it once in the console:
//     localStorage.setItem('pf-no-sw', '1')   // then Unregister the SW + reload, once
// and clear it with  localStorage.removeItem('pf-no-sw')  for production behaviour.
// When skipping, unregister any worker left over and purge its caches so the next
// reload runs the latest files directly (native breakpoints hit).
let NO_SW = ['localhost', '127.0.0.1', ''].includes(location.hostname);
try { NO_SW = NO_SW || localStorage.getItem('pf-no-sw') === '1'; } catch (e) { /* storage blocked */ }
if ('serviceWorker' in navigator && NO_SW) {
  navigator.serviceWorker.getRegistrations().then((rs) => rs.forEach((r) => r.unregister())).catch(() => {});
  if (window.caches) caches.keys().then((ks) => ks.forEach((k) => caches.delete(k))).catch(() => {});
} else if ('serviceWorker' in navigator) {
  window.addEventListener('load', async () => {
    try {
      const reg = await navigator.serviceWorker.register('./sw.js');
      const promptFor = (worker) => worker && banner(() => { updating = true; worker.postMessage('SKIP_WAITING'); });
      if (reg.waiting && navigator.serviceWorker.controller) promptFor(reg.waiting);
      reg.addEventListener('updatefound', () => {
        const nw = reg.installing;
        if (nw) nw.addEventListener('statechange', () => {
          if (nw.state === 'installed' && navigator.serviceWorker.controller) promptFor(reg.waiting || nw);
        });
      });
      setInterval(() => reg.update().catch(() => {}), POLL_MS);
      window.addEventListener('focus', () => reg.update().catch(() => {}));
      // Once the worker is active and controlling this page, take the one reload that puts the
      // document through it - see reloadOnceForIsolation. Last, so a failure here cannot cost the
      // update machinery above.
      await navigator.serviceWorker.ready;
      reloadOnceForIsolation();
    } catch (e) { console.warn('SW registration failed', e); }
  });
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (updating) location.reload();   // intentional update only - never the first-load claim
  });
}
