/*
 * Phonalyser web - the page asking whether the place it came from is a bench.
 *
 * THE BUG THIS PINS (maintainer's bench): the probe hardcoded `http://` and rebuilt the address
 * from host + port, so a server behind a TLS reverse proxy was asked `http://host:443/info` -
 * not where it lives. It answered nothing, the page concluded it had been served by nobody, and
 * offered the local backends instead of the bench's.
 *
 * The fix is to resolve `info` against the DOCUMENT BASE, inheriting scheme, host, port and base
 * path. That drags two more things with it, and they are the ones worth pinning, because getting
 * the probe right while leaving them wrong would detect the bench and then refuse to talk to it:
 *   - the session must be dialled wss:// when the page is https (ws:// would be blocked), and
 *   - the mixed-content explanation must NOT fire for that server, or the one bench an https
 *     page CAN reach would be reported as unreachable.
 */
import test from 'node:test';
import { strict as assert } from 'node:assert';

const { ServerProber, wsUrlOf, isPageOrigin } = await import('../js/net/server-prober.js');
const { isMixedContentBlock } = await import('../js/net/net-device-manager.js');

/** Pretends the page was loaded from `href`. The modules read `location` per call, not at
 *  import, so this can change between assertions. */
function pageAt(href) {
  const u = new URL(href);
  globalThis.location = {
    href, protocol: u.protocol, hostname: u.hostname, port: u.port, host: u.host,
  };
  return href;
}

/** A prober whose only I/O is recorded, answering as a Phonalyser server would. */
function recordingProber(answer = { srv: 'bench-1', name: 'Bench', port: 8377 }) {
  const urls = [];
  const prober = new ServerProber({
    fetch: async (url) => {
      urls.push(String(url));
      return { ok: true, json: async () => answer };
    },
  });
  return { prober, urls };
}

// ---------------------------------------------------------------------------
// the probe address
// ---------------------------------------------------------------------------

test('the origin probe inherits the page scheme, host, port AND base path', async () => {
  pageAt('https://bench.example/app/index.html');
  const { prober, urls } = recordingProber();
  await prober.probeOrigin('https://bench.example/app/');
  assert.deepEqual(urls, ['https://bench.example/app/info'],
    'a TLS-fronted bench serving the app under /app/ must be asked https://bench.example/app/info '
    + '- the old code asked http://bench.example:443/info, which is nowhere');
});

test('the plain-http embedded case still asks the same place it always did', async () => {
  pageAt('http://192.168.1.9:8377/');
  const { prober, urls } = recordingProber();
  await prober.probeOrigin('http://192.168.1.9:8377/');
  assert.deepEqual(urls, ['http://192.168.1.9:8377/info'],
    'the embedded page a server serves at / must keep working exactly as before');
});

test('an origin that is not a Phonalyser server is null, not a guess', async () => {
  pageAt('https://dgo42.github.io/Phonalyser/web/');
  const prober = new ServerProber({ fetch: async () => ({ ok: false, status: 404 }) });
  assert.equal(await prober.probeOrigin('https://dgo42.github.io/Phonalyser/web/'), null);
});

test('a base URI that cannot be parsed is null rather than a throw', async () => {
  pageAt('http://localhost:8080/');
  const { prober } = recordingProber();
  assert.equal(await prober.probeOrigin('not a url'), null);
});

// ---------------------------------------------------------------------------
// the session scheme, and the guard that must not fire on it
// ---------------------------------------------------------------------------

test('the server that served an https page is dialled wss://', () => {
  pageAt('https://bench.example/app/');
  assert.ok(isPageOrigin('bench.example', 443), 'default https port is the page port');
  assert.equal(wsUrlOf('bench.example', 443), 'wss://bench.example:443/');
});

test('a LAN bench typed in by address stays ws:// even from an https page', () => {
  pageAt('https://bench.example/app/');
  assert.equal(wsUrlOf('192.168.1.9', 8377), 'ws://192.168.1.9:8377/',
    'a plain LAN host has no certificate - wss:// there would fail for a different reason');
});

test('a plain-http page dials ws:// even to its own origin', () => {
  pageAt('http://192.168.1.9:8377/');
  assert.equal(wsUrlOf('192.168.1.9', 8377), 'ws://192.168.1.9:8377/');
});

test('the mixed-content explanation does NOT fire for the serving origin', () => {
  pageAt('https://bench.example/app/');
  assert.equal(isMixedContentBlock({ host: 'bench.example', port: 443 }), false,
    'this is the one bench an https page can reach - reporting it as blocked would be the fix '
    + 'detecting the server and then refusing to talk to it');
});

test('it still fires for a LAN bench from an https page, and never off https', () => {
  pageAt('https://bench.example/app/');
  assert.equal(isMixedContentBlock({ host: '192.168.1.9', port: 8377 }), true);
  assert.equal(isMixedContentBlock({ host: 'localhost', port: 8377 }), false, 'loopback is exempt');
  pageAt('http://192.168.1.9:8377/');
  assert.equal(isMixedContentBlock({ host: '10.0.0.5', port: 8377 }), false,
    'an http page has no mixed-content rule to trip');
});

test('isPageOrigin is false with no document to compare against', () => {
  delete globalThis.location;
  assert.equal(isPageOrigin('bench.example', 443), false);
  assert.equal(wsUrlOf('bench.example', 443), 'ws://bench.example:443/');
});
