/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The server list's MODEL - what the dialog draws and which rows are grey. Time is an
 * argument here exactly as it is in the Java original, so the offline rule is proved without
 * sleeping through it.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetServerList, SERVER_EXPIRY_MS, SERVER_REFRESH_MS } from '../js/net/net-server-list.js';
import { NetPreferences, makeServerEntry } from '../js/net/net-preferences.js';

// The dialog reads window.jQuery in its constructor; headless it never touches the DOM (the
// two rendering methods are stubbed per test), so a no-op stands in.
globalThis.window = globalThis.window || {};
const noopNode = { on: () => noopNode, val: () => '', text: () => noopNode, prop: () => noopNode,
  addClass: () => noopNode, removeClass: () => noopNode, toggleClass: () => noopNode,
  empty: () => noopNode, append: () => noopNode, attr: () => null, eq: () => noopNode, length: 0 };
globalThis.window.jQuery = globalThis.window.jQuery || (() => noopNode);
const { NetServerListDialog } = await import('../js/net/net-server-list-dialog.js');

function listWith(...servers) {
  const prefs = new NetPreferences();
  for (const s of servers) prefs.putServer(s);
  prefs.beginEdit();
  return { prefs, list: new NetServerList(prefs) };
}

test('a remembered server starts OFFLINE - nothing has answered yet this session', () => {
  const { list } = listWith(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));

  const rows = list.rows(1_000_000);

  assert.equal(rows.length, 1);
  assert.equal(rows[0].online, false, 'a manual row on a routed subnet is normally offline');
  assert.equal(rows[0].server.name, 'Bench');
});

test('an answer makes a row live, and it goes grey again on the expiry clock', () => {
  const { list } = listWith(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
  const t0 = 5_000_000;
  // The expiry is the WINDOW's rule: it decays a vouch only while rounds are recurring, which
  // is what the open server window does. See "a vouch decays ONLY while..." below.
  list.beginLiveRefresh();

  list.alive('uuid-1', t0);

  assert.equal(list.rows(t0).length, 1);
  assert.equal(list.rows(t0)[0].online, true);
  assert.equal(list.rows(t0 + SERVER_EXPIRY_MS - 1)[0].online, true, 'still inside the window');
  assert.equal(list.rows(t0 + SERVER_EXPIRY_MS)[0].online, false, 'four missed intervals = grey');
});

test('a discovered server is remembered; a TYPED one stays manual when discovery finds it', () => {
  const { prefs, list } = listWith(makeServerEntry('uuid-1', 'typed', '10.0.0.1', 8377, true));

  // The peer table names it under a fresher address and a new name.
  list.heard(makeServerEntry('uuid-1', 'Bench QA403', '10.0.0.9', 8377, false), 1000);

  const row = prefs.getEditServers().get('uuid-1');
  assert.equal(row.host, '10.0.0.9', 'the same bench at a new address is the same entry');
  assert.equal(row.name, 'Bench QA403');
  assert.equal(row.manual, true,
    'finding it does not make it disposable - only the operator who typed it may remove it');
  assert.equal(list.rows(1000)[0].online, true);

  // ...and a server nobody typed arrives as discovered.
  list.heard(makeServerEntry('uuid-2', 'Lab rig', '10.0.0.2', 8377, false), 1000);
  assert.equal(prefs.getEditServers().get('uuid-2').manual, false);
});

test('a row with no id is not a row: there is nothing to key it by', () => {
  const { prefs, list } = listWith();

  list.heard({ serverId: '', name: 'x', host: '10.0.0.1', port: 8377 }, 1000);
  list.heard(null, 1000);

  assert.equal(prefs.getEditServers().size, 0);
});

test('connectable: something must be selected, and not the server already connected', () => {
  const { list } = listWith();
  const a = makeServerEntry('uuid-1', 'A', '10.0.0.1', 8377, true);
  const b = makeServerEntry('uuid-2', 'B', '10.0.0.2', 8377, true);

  assert.equal(list.connectable(null, null), false, 'nothing selected');
  assert.equal(list.connectable(a, null), true, 'nothing connected yet');
  assert.equal(list.connectable(a, b), true, 'another bench is connectable');
  assert.equal(list.connectable(a, a), false, 'the live one is not re-connectable');
  assert.equal(list.connectable(a, makeServerEntry('uuid-1', 'A', '10.0.0.9', 9000, false)), false,
    'the id decides, never the address');
});

// ---------------------------------------------------------------------------
// S3: opening the dialog rescans every stored server for its live state.
// The dialog is driven headless here - the model + the prober seam are what the
// order is about; the modal and the table are jQuery's business.
// ---------------------------------------------------------------------------

/** A dialog over `servers`, with a prober that answers for the ids in `answering`. */
function dialogWith(servers, answering = []) {
  const prefs = new NetPreferences();
  for (const s of servers) prefs.putServer(s);
  prefs.beginEdit();
  const probed = [];
  const prober = {
    async probeInfo(host, port) {
      probed.push(`${host}:${port}`);
      const hit = servers.find((s) => s.host === host && s.port === port);
      return hit && answering.includes(hit.serverId)
        ? { serverId: hit.serverId, name: hit.name, app: '1.2.0', proto: 1, port }
        : null;
    },
    async probePeers() { return []; },
  };
  const bench = {
    getPreferences: () => prefs,
    getConnectedServer: () => null,
    connect: async () => null,
    disconnect: async () => {},
  };
  const dialog = new NetServerListDialog({ bench, prober, now: () => NOW });
  // Headless: no modal, and the repaint/gating touch the DOM, so both are stubbed out -
  // what is under test is which servers were asked, and what the model says afterwards.
  dialog.refresh = () => { dialog.refreshes = (dialog.refreshes || 0) + 1; };
  dialog.startRefresh = () => {};
  return { dialog, probed, list: dialog.servers };
}

const NOW = 9_000_000;

test('opening the dialog probes EVERY stored server and marks the live ones', async () => {
  const { dialog, probed, list } = dialogWith([
    makeServerEntry('uuid-1', 'Bench A', '10.0.0.1', 8377, true),
    makeServerEntry('uuid-2', 'Bench B', '10.0.0.2', 8377, true),
    makeServerEntry('uuid-3', 'Bench C', '10.0.0.3', 9000, false),
  ], ['uuid-1', 'uuid-3']);
  assert.equal(list.rows(NOW).every((r) => !r.online), true, 'all grey before the round');

  dialog.open();
  await new Promise((resolve) => setImmediate(resolve));   // the round is not awaited by open()

  assert.deepEqual(probed, ['10.0.0.1:8377', '10.0.0.2:8377', '10.0.0.3:9000'],
    'every remembered server, at its own address - discovered rows included');
  const online = Object.fromEntries(list.rows(NOW).map((r) => [r.server.serverId, r.online]));
  assert.deepEqual(online, { 'uuid-1': true, 'uuid-2': false, 'uuid-3': true },
    'an answering server is live at the address it answered on; a silent one stays grey');
});

/** The vouch is the desktop's: a row that answers as a DIFFERENT installation is a
 *  reinstallation, and marking the old id live would vouch for a ghost. */
test('a server answering with another id does not make the remembered row live', async () => {
  const entry = makeServerEntry('uuid-old', 'Bench', '10.0.0.1', 8377, true);
  const { dialog, list } = dialogWith([entry]);
  dialog._prober.probeInfo = async () => ({ serverId: 'uuid-new', name: 'Bench', port: 8377 });

  dialog.open();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(list.rows(NOW)[0].online, false);
});

/** A prober that throws must not take the dialog down with it - open() is a UI action. */
test('a probe round that fails leaves the dialog open and the rows grey', async () => {
  const { dialog, list } = dialogWith([makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true)]);
  dialog._prober.probeInfo = async () => { throw new Error('network down'); };

  assert.doesNotThrow(() => dialog.open());
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(list.rows(NOW)[0].online, false);
});

// ---------------------------------------------------------------------------
// The liveness WATCH: app-wide from boot, not dialog-scoped. "Servers still offline as I open
// servers dialog. only about after 2-3s servers state is changed. Check if server are online by
// loading app, before dialog is opened!" (maintainer bench).
// ---------------------------------------------------------------------------

/** A prober that answers for the ids in `answering` and counts what it was asked. */
function fakeProber(servers, answering = []) {
  const probed = [];
  return {
    probed,
    rounds() { return probed.length / Math.max(servers.length, 1); },
    async probeInfo(host, port) {
      probed.push(`${host}:${port}`);
      const hit = servers.find((s) => s.host === host && s.port === port);
      return hit && answering.includes(hit.serverId)
        ? { serverId: hit.serverId, name: hit.name, app: '1.2.0', proto: 1, port } : null;
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

/** What app.js does as the app loads: install the seam, run ONE round, never a timer. */
function bootProbe(list, prober) {
  list.useProber({ prober, now: () => NOW });
  return list.pollNow();
}

test('the app load probes the known servers ONCE, with no dialog and no timer', async () => {
  const servers = [
    makeServerEntry('uuid-1', 'Bench A', '10.0.0.1', 8377, true),
    makeServerEntry('uuid-2', 'Bench B', '10.0.0.2', 8377, false),
  ];
  const { list } = listWith(...servers);
  const prober = fakeProber(servers, ['uuid-1']);

  await bootProbe(list, prober);
  await settle();

  assert.deepEqual(prober.probed, ['10.0.0.1:8377', '10.0.0.2:8377'],
    'every known server, at its own address - no window was ever opened');
  assert.equal(list._timer, undefined,
    'and NOTHING recurs: the app does not poll the LAN in the background');
  const online = Object.fromEntries(list.rows(NOW).map((r) => [r.server.serverId, r.online]));
  assert.deepEqual(online, { 'uuid-1': true, 'uuid-2': false });
});

/** The point of the load-time round: the answer STICKS, so the window opens on real state.
 *  With no rounds recurring, a decaying vouch would have greyed it seconds later. */
test('the window\'s first paint shows the load-probed state, however long ago it landed',
  async () => {
    const servers = [makeServerEntry('uuid-1', 'Bench A', '10.0.0.1', 8377, true)];
    const { prefs, list } = listWith(...servers);
    await bootProbe(list, fakeProber(servers, ['uuid-1']));
    await settle();

    const painted = [];
    const dialog = new NetServerListDialog({
      bench: { getPreferences: () => prefs, getConnectedServer: () => null,
        connect: async () => null, disconnect: async () => {} },
      prober: fakeProber(servers, ['uuid-1']), now: () => NOW, servers: list,
    });
    dialog.refresh = () => painted.push(list.rows(NOW + 10 * SERVER_EXPIRY_MS).map((r) => r.online));
    dialog.open();
    dialog.stopRefresh();

    assert.deepEqual(painted[0], [true],
      'ten expiry windows after the round and still live: nothing has asked since, so nothing '
      + 'has learnt otherwise');
  });

/** ...and the expiry is not gone, it is scoped: while the window refreshes itself, a server that
 *  stops answering goes grey on the clock, which is what that refresh is for. */
test('a vouch decays ONLY while the window is refreshing itself', () => {
  const { list } = listWith(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
  const t0 = 5_000_000;
  list.alive('uuid-1', t0);
  list._lastRoundOnline = new Set(['uuid-1']);

  assert.equal(list.rows(t0 + 10 * SERVER_EXPIRY_MS)[0].online, true,
    'nothing is asking - the last answer stands');

  list.beginLiveRefresh();

  assert.equal(list.rows(t0 + SERVER_REFRESH_MS)[0].online, true, 'one period later, still live');
  assert.equal(list.rows(t0 + SERVER_EXPIRY_MS)[0].online, false,
    'and grey once the window has passed, because the window IS asking again');

  list.endLiveRefresh();
  assert.equal(list.rows(t0 + 10 * SERVER_EXPIRY_MS)[0].online, true,
    'closing the window puts it back to what the last round found');
});

test('with NOTHING known the load-time round asks nobody', async () => {
  const { list } = listWith();
  const prober = fakeProber([]);

  await bootProbe(list, prober);

  assert.deepEqual(prober.probed, [], 'a page that knows no server bothers nobody');
});

/**
 * N7: a round asks the COMMITTED list. A host the operator typed in and then CANCELLED was
 * never committed - and the dialog reverts its edit copy lazily, at the NEXT beginEdit - so
 * reading the edit copy outside the window would keep asking about a server they refused.
 */
test('a host staged and then CANCELLED is not probed once the window closes', async () => {
  const committed = makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true);
  const { prefs, list } = listWith(committed);
  const typed = makeServerEntry('uuid-typed', 'Typo', '10.0.0.9', 8377, true);
  const prober = fakeProber([committed, typed], ['uuid-1', 'uuid-typed']);
  const window = () => {};
  list.addChangeListener(window);            // the dialog is up
  list.heard(typed, NOW);                    // ...and the operator adds a host

  await bootProbe(list, prober);
  assert.ok(prober.probed.includes('10.0.0.9:8377'), 'staged: probed while the window is up');

  list.removeChangeListener(window);         // Cancel - the edit copy is NOT reverted yet
  prober.probed.length = 0;
  await list.pollNow();

  assert.deepEqual(prober.probed, ['10.0.0.1:8377'],
    'only the committed server: a cancelled add is not this app\'s to keep asking about');
  assert.ok(prefs.getEditServers().has('uuid-typed'),
    'and the edit copy still holds it - the revert is the dialog\'s, at its next beginEdit');
});

/** The other half: staging the add is what lets the row flip online BEFORE the operator
 *  commits, which is the whole point of adding it in the dialog. */
test('a staged host is probed, and shows online, while the window is open', async () => {
  const { list } = listWith();
  const typed = makeServerEntry('uuid-typed', 'Bench', '10.0.0.9', 8377, true);
  const prober = fakeProber([typed], ['uuid-typed']);
  list.addChangeListener(() => {});
  list.heard(typed, NOW);

  await bootProbe(list, prober);

  assert.deepEqual(prober.probed, ['10.0.0.9:8377']);
  assert.equal(list.rows(NOW)[0].online, true, 'live before it is committed anywhere');
});

/** A staged host is asked ONCE per round even though it is in both copies - one round over the
 *  union, deduped by id, is what the single in-flight guard can honestly cover. */
test('a server in BOTH copies is asked once per round, not twice', async () => {
  const both = makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true);
  const { list } = listWith(both);
  const prober = fakeProber([both], ['uuid-1']);
  list.addChangeListener(() => {});          // window open: committed ∪ staged

  await bootProbe(list, prober);

  assert.deepEqual(prober.probed, ['10.0.0.1:8377'], 'one address, one ask');
});

test('a change listener is what repaints a window - and it is dropped with it', async () => {
  const servers = [makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true)];
  const { list } = listWith(...servers);
  let paints = 0;
  const listener = () => { paints++; };
  list.addChangeListener(listener);

  await bootProbe(list, fakeProber(servers, ['uuid-1']));
  const afterRound = paints;

  assert.ok(afterRound > 0, 'a landed round tells whoever is drawing');
  list.removeChangeListener(listener);
  list.alive('uuid-1', NOW);
  assert.equal(paints, afterRound, 'and a closed window is told nothing');
});

test('forget is STAGED - the Preferences Cancel brings the row back', () => {
  const { prefs, list } = listWith(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
  list.alive('uuid-1', 1000);

  list.forget('uuid-1');

  assert.equal(list.rows(1000).length, 0);
  assert.equal(prefs.getServers().size, 1, 'nothing live moved');
  prefs.beginEdit();                                   // = the dialog's Cancel
  assert.equal(list.rows(1000).length, 1, 'and the row is back');
  assert.equal(list.rows(1000)[0].online, false, 'though its liveness stamp is not');
});
