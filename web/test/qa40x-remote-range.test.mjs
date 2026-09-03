/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The QA40x attenuator when the analyzer is NOT in this room (doc/NET-PROTOCOL.md §4.6, faithful
 * port of Qa40xRangeController.sendToBench): ONE generic DEVICE_ACTIVE_RANGE_CHANGED event, two
 * destinations - the open local analyzer, or the bench's qa40x.setInputRange/setOutputRange under
 * the analyzer's lock. The wrong destination is the whole defect this guards: a range the operator
 * watched commit while the hardware never moved.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xRangeController } from '../js/qa40x/qa40x-range-controller.js';
import { MessageType, NetFields } from '../js/net/net-proto.js';

/** The local half: the open-device state and the range sink (Qa40xDeviceManager's seam). */
function localManager(backend) {
  return {
    applied: [],
    activeBackend: () => backend,
    cardName: () => 'QA403',
    applyActiveRangeChange(active, card, input, dbv) { this.applied.push({ active, card, input, dbv }); },
  };
}

/** The remote half: NetDeviceManager's callLocked, recorded. */
function benchSeam(answer = {}) {
  return { calls: [], async callLocked(backend, request, fields) {
    this.calls.push({ backend, request, fields });
    return answer;
  } };
}

test('a remote QA40x selection re-ranges the BENCH, and never the local manager', async () => {
  const manager = localManager('net:QA40X');
  const bench = benchSeam();
  const controller = new Qa40xRangeController(manager, bench);

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: '6 dBV' });
  await Promise.resolve();

  assert.equal(bench.calls.length, 1);
  assert.equal(bench.calls[0].request, MessageType.QA40X_SET_INPUT_RANGE);
  assert.equal(bench.calls[0].backend, 'net:QA40X', 'the selection names which analyzer this is');
  assert.equal(bench.calls[0].fields[NetFields.DBV], 6);
  assert.deepEqual(manager.applied, [], 'the analyzer wired to THIS machine was not touched');
});

test('the output direction takes the output command', async () => {
  const manager = localManager('net:QA40X');
  const bench = benchSeam();
  const controller = new Qa40xRangeController(manager, bench);

  controller.onActiveRangeChanged({ input: false, activeRangeLabel: '-12 dBV' });
  await Promise.resolve();

  assert.equal(bench.calls[0].request, MessageType.QA40X_SET_OUTPUT_RANGE);
  assert.equal(bench.calls[0].fields[NetFields.DBV], -12);
});

test('a label that is not a QA40x range never leaves - the event is generic', async () => {
  const manager = localManager('net:QA40X');
  const bench = benchSeam();
  const controller = new Qa40xRangeController(manager, bench);

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: '2 Vrms' });
  await Promise.resolve();

  assert.deepEqual(bench.calls, []);
});

test('the LOCAL analyzer still gets its own re-range - the remote branch is not a takeover', async () => {
  const manager = localManager('QA40X');
  const bench = benchSeam();
  const controller = new Qa40xRangeController(manager, bench);

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: '6 dBV' });
  await Promise.resolve();

  assert.deepEqual(bench.calls, [], 'nothing crossed a wire for hardware on this machine');
  assert.deepEqual(manager.applied, [{ active: 'QA40X', card: 'QA403', input: true, dbv: 6 }]);
});

test('another backend entirely re-ranges nothing at all', async () => {
  const manager = localManager('WEB_AUDIO');
  const bench = benchSeam();
  const controller = new Qa40xRangeController(manager, bench);

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: '6 dBV' });
  await Promise.resolve();

  assert.deepEqual(bench.calls, []);
  assert.deepEqual(manager.applied, []);
});

test('a build with no net client answers the same event without a bench', async () => {
  const manager = localManager('net:QA40X');
  const controller = new Qa40xRangeController(manager);

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: '6 dBV' });
  await Promise.resolve();

  assert.deepEqual(manager.applied, [], 'and the local analyzer is still not the one selected');
});
