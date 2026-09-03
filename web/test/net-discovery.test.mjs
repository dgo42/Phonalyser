/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The web's whole discovery story (spec 2.2 - a browser cannot hear the beacon of 2.1): one
 * typed address, probed, yields the server's identity and the peer table of every server it
 * has heard. Plus the remembered-server block: keyed by serverId, never by address, with the
 * edit/commit copies that make the dialog's Cancel possible.
 *
 * The HTTP seam is injected, so nothing here opens a socket.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { ServerProber, parseAddress, wsUrlOf } from '../js/net/server-prober.js';
import { NetPreferences, makeServerEntry } from '../js/net/net-preferences.js';
import { NetProto } from '../js/net/net-proto.js';

/** A fetch that answers from a scripted table of url -> body (or a status). */
function fakeFetch(routes) {
  return async (url) => {
    const answer = routes[url];
    if (answer === undefined) return { ok: false, status: 404, json: async () => ({}) };
    if (answer instanceof Error) throw answer;
    return { ok: true, status: 200, json: async () => answer };
  };
}

test('a typed address defaults to the ONE port, and keeps an explicit one', () => {
  assert.deepEqual(parseAddress('192.168.1.40'), { host: '192.168.1.40', port: NetProto.DEFAULT_PORT });
  assert.deepEqual(parseAddress('  bench.local:9000 '), { host: 'bench.local', port: 9000 });
  assert.deepEqual(parseAddress('[fe80::1]:8377'), { host: 'fe80::1', port: 8377 });
  assert.deepEqual(parseAddress('fe80::1'), { host: 'fe80::1', port: NetProto.DEFAULT_PORT },
    'a bare IPv6 literal keeps its colons - only a TRAILING numeric one is a port');
  assert.equal(parseAddress('   '), null);
  assert.equal(parseAddress('bench.local:0'), null, 'port 0 is not a port anything is reached on');
});

test('the ws url dials the same one port, at /', () => {
  assert.equal(wsUrlOf('192.168.1.40', 8377), 'ws://192.168.1.40:8377/');
  assert.equal(wsUrlOf('fe80::1', 8377), 'ws://[fe80::1]:8377/', 'IPv6 needs its brackets back');
});

test('/info identifies the server, and its OWN port answer wins', async () => {
  const prober = new ServerProber({ fetch: fakeFetch({
    'http://192.168.1.40:8377/info': { serverId: 'uuid-1', name: 'Bench QA403', app: '1.1.1', proto: 1, port: 9001 },
  }) });

  const info = await prober.probeInfo('192.168.1.40', 8377);

  assert.equal(info.serverId, 'uuid-1');
  assert.equal(info.name, 'Bench QA403');
  assert.equal(info.port, 9001, 'a server behind a proxy answers with the number it really serves');
});

test('a host that is not a Phonalyser server, or does not answer, probes null', async () => {
  const prober = new ServerProber({ fetch: fakeFetch({
    'http://10.0.0.9:8377/info': { hello: 'I am a router' },
    'http://10.0.0.8:8377/info': new Error('ECONNREFUSED'),
  }) });

  assert.equal(await prober.probeInfo('10.0.0.9', 8377), null, 'no serverId ⇒ not our server');
  assert.equal(await prober.probeInfo('10.0.0.8', 8377), null, 'a refused connect is ordinary');
  assert.equal(await prober.probeInfo('10.0.0.7', 8377), null, 'and so is a 404');
});

test('the peer table is the web\'s LAN discovery - one address yields the rest', async () => {
  const prober = new ServerProber({ fetch: fakeFetch({
    'http://192.168.1.40:8377/servers': { servers: [
      { serverId: 'uuid-1', name: 'Bench QA403', host: '10.9.9.9', port: 8377, app: '1.1.1', proto: 1, self: true },
      { serverId: 'uuid-2', name: 'Lab rig', host: '192.168.1.41', port: 8377, app: '1.1.1', proto: 1 },
      { serverId: '', name: 'nameless', host: '192.168.1.42', port: 8377 },
      { serverId: 'uuid-4', name: 'no port', host: '192.168.1.43' },
    ] },
  }) });

  const peers = await prober.probePeers('192.168.1.40', 8377);

  assert.equal(peers.length, 2, 'a row with no id or no port could never be reached again');
  assert.deepEqual(peers[0], makeServerEntry('uuid-1', 'Bench QA403', '192.168.1.40', 8377, false),
    'the ANSWERING server is reached back where we reached it - a multi-homed host lies about its own address');
  assert.deepEqual(peers[1], makeServerEntry('uuid-2', 'Lab rig', '192.168.1.41', 8377, false));
  assert.equal(peers[1].manual, false, 'a peer was discovered, not typed');
});

test('remembered servers key on serverId, and survive a save/load round trip', () => {
  const prefs = new NetPreferences();
  prefs.putServer(makeServerEntry('uuid-1', 'Bench QA403', '192.168.1.40', 8377, true));
  prefs.setLastConnected('uuid-1');

  const restored = new NetPreferences();
  restored.fromMap(JSON.parse(JSON.stringify(prefs.toMap())));

  assert.deepEqual([...restored.getServers().values()], [...prefs.getServers().values()]);
  assert.equal(restored.getLastConnected(), 'uuid-1');

  // The same bench at a new address is the SAME entry with fresher contents.
  restored.putServer(makeServerEntry('uuid-1', 'Bench QA403', '192.168.1.55', 8377, true));
  assert.equal(restored.getServers().size, 1);
  assert.equal(restored.getServers().get('uuid-1').host, '192.168.1.55');
});

test('a stored row that could never be reached again is dropped, not shown', () => {
  const prefs = new NetPreferences();
  prefs.fromMap({ servers: [
    { serverId: 'uuid-1', name: 'ok', host: '10.0.0.1', port: 8377, manual: true },
    { serverId: '', name: 'no id', host: '10.0.0.2', port: 8377 },
    { serverId: 'uuid-3', name: 'no host', port: 8377 },
    { serverId: 'uuid-4', name: 'no port', host: '10.0.0.4' },
    'not an object',
  ] });

  assert.deepEqual([...prefs.getServers().keys()], ['uuid-1']);
});

test('the edit copy is what Cancel discards - but a LIVE add survives it', () => {
  const prefs = new NetPreferences();
  prefs.putServer(makeServerEntry('uuid-1', 'kept', '10.0.0.1', 8377, true));
  prefs.beginEdit();

  prefs.putEditServer(makeServerEntry('uuid-2', 'typed but abandoned', '10.0.0.2', 8377, true));
  prefs.removeEditServer('uuid-1');
  assert.equal(prefs.getServers().size, 1, 'nothing live moved while the dialog was open');
  assert.deepEqual([...prefs.getEditServers().keys()], ['uuid-2']);

  prefs.beginEdit();   // = Cancel: the next open re-seeds from live
  assert.deepEqual([...prefs.getEditServers().keys()], ['uuid-1']);

  // ...and a server that ANSWERED while the dialog was open is written to both copies: a Cancel
  // that made the bench being measured on vanish would be a lie about the machine's state.
  prefs.putServer(makeServerEntry('uuid-9', 'connected now', '10.0.0.9', 8377, false));
  prefs.beginEdit();
  assert.equal(prefs.getEditServers().has('uuid-9'), true);
  assert.equal(prefs.getServers().has('uuid-9'), true);
});

test('commitEdit is what OK does', () => {
  const prefs = new NetPreferences();
  prefs.putServer(makeServerEntry('uuid-1', 'kept', '10.0.0.1', 8377, true));
  prefs.beginEdit();
  prefs.putEditServer(makeServerEntry('uuid-2', 'added', '10.0.0.2', 8377, true));
  prefs.removeEditServer('uuid-1');

  prefs.commitEdit();

  assert.deepEqual([...prefs.getServers().keys()], ['uuid-2']);
});
