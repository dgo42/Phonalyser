/*
 * Phonalyser web - Qa40xRateConstraint compare/emit contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors the assertions of Qa40xRateConstraintTest.java, which drives the subscriber
 * through the real MessageBus and captures PREFS_SAMPLE_RATE_SET. The first case does
 * exactly that, so a missing subscribe() or a mistyped event name fails here. The rest feed
 * the change in directly and read the RETURNED correction (null ⇒ "nothing published"),
 * because MessageBus.publish swallows a throwing subscriber - a bus-driven "nothing was
 * published" assertion would stay green over a crash.
 * Every case starts from a reset baseline (both recorded rates unset) so the shared
 * singleton cannot bleed state between cases.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { Events } from '../js/bus/events.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Qa40xRateConstraint, QA40X_BACKEND } from '../js/qa40x/qa40x-rate-constraint.js';

/** A stand-in resolved card name; the constraint echoes it into every correction. */
const CARD = 'QA403';
const WASAPI = 'WASAPI';

const constraint = Qa40xRateConstraint.instance();

const changed = (input, hz, backend) =>
  constraint.onSampleRateChanged({ input, sampleRateHz: hz, backend, card: CARD });

/** A non-QA40x change is the constraint's reset primitive (see the ignored-and-resets
 *  case): it forgets both recorded rates. Reused to normalise the shared singleton. */
const reset = () => changed(true, 0, WASAPI);

/** The correction payload for `input` at `hz`, as published and as returned. */
const correction = (input, hz) => ({ input, sampleRateHz: hz, backend: QA40X_BACKEND, card: CARD });

test('construction armed the bus: a published CHANGED answers with a published SET', () => {
  reset();
  const bus = MessageBus.instance();
  const emitted = [];
  const captor = (set) => emitted.push(set);
  bus.subscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
  try {
    bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED,
      { input: true, sampleRateHz: 96_000, backend: QA40X_BACKEND, card: CARD });
    assert.deepEqual(emitted, [correction(false, 96_000)],
      'the subscription is live and the correction went out on PREFS_SAMPLE_RATE_SET');
  } finally {
    bus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
  }
});

test('input change with a differing output corrects the output, then an equal change is a no-op', () => {
  reset();
  // Establish an equal pair at 48 kHz (the first change syncs both directions).
  changed(false, 48_000, QA40X_BACKEND);

  // Input jumps to 96 kHz -> the OTHER direction (output) must be pulled up.
  const corrected = changed(true, 96_000, QA40X_BACKEND);
  assert.deepEqual(corrected, correction(false, 96_000),
    'output is corrected to the input\'s new rate, card echoed');

  // Both combos now read 96 kHz. Even a redundant output change to 96 kHz finds the
  // recorded pair already equal, so nothing is emitted - the round-trip terminates by
  // construction (equal records ⇒ no emit).
  assert.equal(changed(false, 96_000, QA40X_BACKEND), null,
    'equal recorded rates ⇒ no further correction (loop terminates)');
});

test('output change with a differing input corrects the input, and a re-announcement emits nothing', () => {
  reset();
  // Mirror image of the case above - the output direction is the one the dialog's own
  // #outRate combo drives, and its correction must name the INPUT.
  assert.deepEqual(changed(false, 192_000, QA40X_BACKEND), correction(true, 192_000),
    'the input is corrected to the output\'s new rate, card echoed');

  // The dialog aligns #inRate with a programmatic select, which fires no change event, so
  // the corrected direction never answers back. What DOES come again is the same output
  // rate (refreshRates publishes one CHANGED on every dialog open / backend switch): the
  // record the correction left on the input direction is what makes that a no-op.
  assert.equal(changed(false, 192_000, QA40X_BACKEND), null,
    'the corrected direction was recorded ⇒ a re-announced rate emits nothing');
});

test('a non-QA40x change is ignored and resets the tracking', () => {
  reset();
  changed(false, 48_000, QA40X_BACKEND);   // seed an equal QA40x pair

  assert.equal(changed(true, 44_100, WASAPI), null, 'a non-QA40x change publishes nothing');

  // The next QA40x change behaves like a first-ever one (the "forgets BOTH" case below pins
  // which record was dropped; this only shows the reset does not break the sync).
  assert.deepEqual(changed(true, 192_000, QA40X_BACKEND), correction(false, 192_000),
    'tracking was reset - the next change syncs the other');
});

test('a non-QA40x change forgets BOTH recorded rates', () => {
  reset();
  changed(true, 48_000, QA40X_BACKEND);   // both records now hold 48 kHz

  // Against a RETAINED record, re-picking the very same 48 kHz would be a no-op. So one
  // probe per record: an input change compares lastOutputHz, an output change compares
  // lastInputHz - each must find its counterpart unset and correct it.
  changed(true, 44_100, WASAPI);
  assert.deepEqual(changed(true, 48_000, QA40X_BACKEND), correction(false, 48_000),
    'the output record was forgotten, so 48 kHz differs again');

  changed(true, 44_100, WASAPI);
  assert.deepEqual(changed(false, 48_000, QA40X_BACKEND), correction(true, 48_000),
    'the input record was forgotten too');
});

test('the first change with the other direction unset syncs the other', () => {
  reset();
  // Fresh from reset(): both directions unset. A single input change must still sync
  // the other direction (unset counts as "differs").
  assert.deepEqual(changed(true, 96_000, QA40X_BACKEND), correction(false, 96_000),
    'first-ever change syncs the still-unset direction');
});

test('instance() returns the same singleton', () => {
  assert.equal(Qa40xRateConstraint.instance(), constraint);
});
