/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The load-time server probe, driven through the REAL preferences path - the gap that let a
 * boot bug hide: every other test of the server list hand-builds NetPreferences and commits its
 * servers directly, so none of them could have caught a replay that put the stored servers
 * somewhere the probe does not look.
 *
 * What app.js actually does, in this order:
 *   Preferences.instance()                     - reads the stored document (load() in the ctor)
 *   new NetPreferences() + registerCustomPreferences(...)  - replays the `custom.net` block
 *   new NetServerList(netPreferences) -> useProber(...) -> pollNow()   - ONE round, unawaited
 *
 * The maintainer's rule for this round: "Only after load probe known server" - one round, no
 * timer, and nothing recurring outside the server window.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

/** A stored preferences document with a remembered server in the net block, exactly the shape
 *  NetPreferences.toMap writes. */
const STORED = {
  custom: {
    net: {
      servers: [
        { serverId: 'uuid-boot', name: 'Bench', host: '10.0.0.7', port: 8377, manual: true },
      ],
    },
  },
};

const backing = new Map();
globalThis.localStorage = {
  getItem: (k) => (backing.has(k) ? backing.get(k) : null),
  setItem: (k, v) => { backing.set(k, String(v)); },
  removeItem: (k) => { backing.delete(k); },
  clear: () => { backing.clear(); },
};
// The document has to be in storage BEFORE preferences.js is imported: the singleton loads in
// its constructor, which is exactly what makes this the boot path and not a hand-built model.
for (const key of ['phonalyser.prefs', 'phonalyser.preferences', 'phonalyser']) {
  backing.set(key, JSON.stringify(STORED));
}

const { Preferences } = await import('../js/store/preferences.js');
const { NetPreferences } = await import('../js/net/net-preferences.js');
const { NetServerList } = await import('../js/net/net-server-list.js');

const NOW = 4_000_000;

/** Everything app.js builds for the net block, in app.js's order. */
function bootTheApp() {
  const prefs = Preferences.instance();
  const netPreferences = new NetPreferences();
  prefs.registerCustomPreferences(netPreferences);       // the replay
  const probed = [];
  const prober = {
    async probeInfo(host, port) {
      probed.push(`${host}:${port}`);
      return host === '10.0.0.7'
        ? { serverId: 'uuid-boot', name: 'Bench', app: '1.2.0', proto: 1, port } : null;
    },
  };
  const servers = new NetServerList(netPreferences);
  servers.useProber({ prober, now: () => NOW });
  return { netPreferences, servers, probed, round: servers.pollNow() };
}

/**
 * The regression: the round enumerates the COMMITTED servers (N7), and the replay has to have
 * put the stored ones there. If registration only staged them - or if the probe read a copy the
 * replay does not fill - this asks nobody, and the operator sees every row grey until the
 * window has been open for a couple of seconds. Which is the bench report.
 */
test('the stored servers are probed at load, through the real registration replay', async () => {
  const { netPreferences, probed, round } = bootTheApp();

  assert.equal(netPreferences.getServers().size, 1,
    'the replay fills the COMMITTED map - what a round enumerates');
  await round;

  assert.deepEqual(probed, ['10.0.0.7:8377'],
    'and the load-time round asked it, with no dialog and no commit of our own');
});

test('the load-time answer is what the first paint reads, with nothing recurring', async () => {
  const { servers, round } = bootTheApp();
  await round;

  const rows = servers.rows(NOW + 60 * 60 * 1000);       // an hour later: nothing has asked since
  assert.equal(rows.length, 1);
  assert.equal(rows[0].online, true,
    'the last completed round found it - and no vouch decays while nothing is refreshing');
});

/**
 * The second moment the app asks (maintainer, this round): every opening of the PREFERENCES
 * dialog. app.js hangs one `pollNow()` on `show.bs.modal`, and what that relies on is exactly
 * this - a round is repeatable, so the answer an operator sees when they go looking for a bench
 * is current rather than however old the load-time round is. There is still no timer.
 */
test('a later round asks again - the load-time one is not a once-only latch', async () => {
  const { servers, probed, round } = bootTheApp();
  await round;
  assert.deepEqual(probed, ['10.0.0.7:8377']);

  await servers.pollNow();                 // what opening Preferences spends

  assert.deepEqual(probed, ['10.0.0.7:8377', '10.0.0.7:8377'],
    'the same server, asked again - a bench switched on since load answers this time');
});

test('two rounds at once cost one - the model refuses the overlap', async () => {
  // Opening Preferences while the load-time round is still in flight must not double-probe:
  // the round already running is asking the same question.
  const { servers, probed, round } = bootTheApp();

  await Promise.all([round, servers.pollNow()]);

  assert.deepEqual(probed, ['10.0.0.7:8377']);
});

test('the load probe leaves NO timer behind', async () => {
  const { servers, round } = bootTheApp();
  await round;

  assert.equal(servers._timer, undefined, 'one round at load, and then silence');
  assert.equal(typeof servers.watch, 'undefined', 'there is no background watch to start');
});
