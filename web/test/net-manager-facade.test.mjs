/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * F25: the facade the audio layer holds must not drift from the manager it forwards to.
 *
 * A hand-written forward fails at RUNTIME, in a browser, and quietly: the scan's catch turns
 * "manager.lockedBy is not a function" into a status line and leaves the device combo empty.
 * Every other test in this suite passes the REAL manager and therefore cannot see it - which is
 * how exactly that shipped for one chunk of bench round 3.
 *
 * So this test does not check members one by one. It ENUMERATES both sides and compares them:
 * every facade member must exist on NetDeviceManager with the SAME arity, and every one must
 * survive being called with no session behind it. A member added to one side and not the other,
 * or forwarded with the wrong number of parameters, fails here.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { netManagerFacade } from '../js/net/net-manager-facade.js';
import { NetDeviceManager } from '../js/net/net-device-manager.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';

/** The facade's own members, split into the accessors and the forwarded methods. */
function facadeMembers(facade) {
  const methods = [], accessors = [];
  for (const [name, descriptor] of Object.entries(Object.getOwnPropertyDescriptors(facade))) {
    if (typeof descriptor.get === 'function') accessors.push(name);
    else if (typeof descriptor.value === 'function') methods.push(name);
  }
  return { methods: methods.sort(), accessors: accessors.sort() };
}

/** The manager's own descriptor for `name`, from its prototype chain. */
function managerDescriptor(name) {
  for (let proto = NetDeviceManager.prototype; proto != null; proto = Object.getPrototypeOf(proto)) {
    const descriptor = Object.getOwnPropertyDescriptor(proto, name);
    if (descriptor) return descriptor;
  }
  return null;
}

test('F25: every facade member exists on the manager, with the SAME arity', () => {
  const { methods, accessors } = facadeMembers(netManagerFacade(() => null));
  assert.ok(methods.length >= 12, `the forward looks truncated: ${methods.join(', ')}`);

  for (const name of methods) {
    const descriptor = managerDescriptor(name);
    assert.ok(descriptor != null,
      `the facade forwards '${name}', which NetDeviceManager does not have - a forward to nothing`);
    assert.equal(typeof descriptor.value, 'function', `${name} is not a manager METHOD`);
    assert.equal(netManagerFacade(() => null)[name].length, descriptor.value.length,
      `'${name}' forwards ${netManagerFacade(() => null)[name].length} argument(s) but the manager `
      + `takes ${descriptor.value.length} - the caller's extra argument is silently dropped by `
      + 'one side and not the other, which is how a direction flag goes missing');
  }

  for (const name of accessors) {
    const descriptor = managerDescriptor(name);
    assert.ok(descriptor != null && typeof descriptor.get === 'function',
      `the facade exposes '${name}' as an accessor; the manager does not`);
  }
});

test('F25: with no session behind it, every member answers - none of them throws', () => {
  const facade = netManagerFacade(() => null);
  const { methods, accessors } = facadeMembers(facade);
  // A UI path really does reach these before a connect: a scan on a remembered backend, a combo
  // build, a failed open on a lane whose session has already gone.
  for (const name of accessors) {
    assert.doesNotThrow(() => facade[name], `reading '${name}' with no session threw`);
  }
  for (const name of methods) {
    let answer;
    assert.doesNotThrow(() => { answer = facade[name](null, false); },
      `'${name}' threw with no session behind it`);
    if (answer instanceof Promise) answer.catch(() => { /* settled below */ });
  }
  assert.deepEqual(facade.listInputDevices(), [], 'no bench lists no devices');
  assert.deepEqual(facade.listSupportedFormats(null), [], 'and offers no formats');
  assert.equal(facade.lockedBy(null), null);
  assert.equal(facade.connection, null);
  assert.equal(facade.classifyFailure(new Error('x')), DeviceFailureReason.UNKNOWN,
    'the manager\'s answer is never null, so neither is the facade\'s');
  assert.equal(facade.refusalText(new Error('x')), null);
});

test('F25: with a session, every member forwards to the manager unchanged', async () => {
  const calls = [];
  const manager = {};
  const { methods } = facadeMembers(netManagerFacade(() => null));
  for (const name of methods) manager[name] = (...args) => { calls.push([name, args.length]); return name; };
  manager.connection = { open: true };
  const facade = netManagerFacade(() => manager);

  assert.equal(facade.connection, manager.connection, 'the session is read per call, not captured');
  for (const name of methods) {
    const answer = await facade[name]('a', 'b');
    // gap() is the one VOID member - the manager announces the loss on the bus and answers
    // nothing, so a forward that invented a value would be forwarding more than it was given.
    if (name === 'gap') { assert.equal(answer, undefined, 'gap answers nothing, as the manager does'); continue; }
    assert.equal(answer, name, `'${name}' did not hand back the manager's answer`);
  }
  assert.deepEqual(calls.map(([name]) => name).sort(), methods,
    'every member reached the manager exactly once');
  const arity = Object.fromEntries(calls);
  assert.equal(arity.listSupportedFormats, 1,
    'the direction is the REF\'s own - the manager takes one argument and so does the forward');
  assert.equal(arity.getDeviceByName, 2, 'name AND direction');
});
