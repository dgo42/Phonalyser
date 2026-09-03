/*
 * Phonalyser web - SharedCapture's acquire/release mutual exclusion.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/shared-capture-serial.test.mjs`.
 *
 * The Java SharedCapture makes acquire() and release() synchronized, so a second consumer
 * arriving during a slow device open blocks on the monitor and then takes the refcount fast
 * path. The web's acquire awaits the source's open() - which can span a browser permission
 * prompt - and without serialization a second first-acquire inside that window reads
 * refCount 0, buffer null, and opens the SAME source again: a leaked stream, two contexts,
 * and two readers over rings only one of which is fed. What is asked here is that the promise
 * chain restores the monitor's guarantees: one open however many concurrent first acquires,
 * ordered release-after-acquire, and a chain that survives a failing body.
 */
import { SharedCapture } from '../js/audio/shared-capture.js';
import { testPrefs } from './_test-prefs.mjs';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** A capture source whose open() resolves only when the test releases it - the stand-in for a
 *  device open held up by the browser's permission prompt. */
function slowSource() {
  const s = {
    opens: 0, starts: 0, stops: 0, closes: 0,
    sampleRate: 48000, isOpen: false,
    _release: null,
    open() { s.opens++; return new Promise((res) => { s._release = () => { s.isOpen = true; res(); }; }); },
    start() { s.starts++; return Promise.resolve(); },
    stop() { s.stops++; return Promise.resolve(); },
    close() { s.closes++; s.isOpen = false; return Promise.resolve(); },
  };
  return s;
}

function capture(source) {
  return new SharedCapture({
    prefs: testPrefs({ inputDeviceName: 'dev', inputSampleRate: 48000 }),
    computeAnalysisFreqs: () => {},
    publishBatch: false,
    captureSource: source,
  });
}

// --- 1. TWO CONCURRENT FIRST ACQUIRES OPEN THE DEVICE ONCE. The second caller waits out the
// first one's open and then takes the refcount fast path - the Java monitor's behaviour.
{
  const src = slowSource();
  const cap = capture(src);
  const p1 = cap.acquire();
  const p2 = cap.acquire();
  // The serialized bodies start on a microtask - give them one macrotask turn, so the first
  // open is genuinely IN FLIGHT (held by the stub) when the assertion looks.
  await new Promise((r) => setTimeout(r, 0));
  ok(src.opens === 1, 'the second acquire does not start a second open while the first is in flight');
  src._release();
  const r1 = await p1;
  // The second body runs after the first completes; its open (had it raced) would hang forever
  // on the unreleased stub, so reaching this await at all proves the fast path was taken.
  const r2 = await p2;
  ok(src.opens === 1, 'one device open serves both first acquires');
  ok(src.starts === 1, 'and one start installs the one batch sink');
  ok(r1 != null && r2 != null, 'both consumers hold readers');
  ok(r1 !== r2, 'each consumer holds its OWN cursor over the shared ring');

  // --- 2. RELEASES COUNT BACK DOWN IN ORDER; the device closes exactly once, on the last one.
  await cap.release();
  ok(src.closes === 0, 'the first release keeps the device open for the remaining holder');
  await cap.release();
  ok(src.closes === 1, 'the last release closes the device exactly once');
}

// --- 3. A RELEASE ISSUED DURING A PENDING ACQUIRE RUNS AFTER IT - the ordered-monitor property
// that keeps a teardown from running under a half-built lane.
{
  const src = slowSource();
  const cap = capture(src);
  const pa = cap.acquire();
  const pr = cap.release();          // queued behind the acquire still waiting on open
  await new Promise((r) => setTimeout(r, 0));
  ok(src.closes === 0, 'the queued release has not torn anything down yet');
  src._release();
  await pa;
  await pr;
  ok(src.closes === 1, 'after the acquire lands, the queued release closes its reference');
}

// --- 4. A FAILING BODY DOES NOT WEDGE THE CHAIN. An open that rejects fails ITS caller and
// leaves the next acquire able to run (and succeed) normally.
{
  const src = slowSource();
  let first = true;
  const failingOpen = src.open.bind(src);
  src.open = () => {
    if (first) { first = false; src.opens++; return Promise.reject(new Error('denied')); }
    return failingOpen();
  };
  const cap = capture(src);
  const r1 = await cap.acquire();
  ok(r1 == null, 'the failing open answers ITS caller with the null-reader failure');
  const p2 = cap.acquire();
  await new Promise((r) => setTimeout(r, 0));
  src._release();
  const r2 = await p2;
  ok(r2 != null, 'and the next acquire runs on an intact chain and succeeds');
}

console.log(`shared-capture-serial.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
