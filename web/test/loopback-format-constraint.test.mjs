/*
 * Phonalyser web - the loopback format constraint: one rate AND one depth, both directions.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The rule this file pins: the loopback's playback lane's samples ARE its capture lane's samples,
 * so the two directions can differ in neither sample rate NOR bit depth. What has to hold:
 *
 *   1. BOTH FIELDS, BOTH DIRECTIONS. An edit on either side corrects the other in rate and depth.
 *   2. IT TERMINATES. The correction updates the constraint's OWN record of the corrected
 *      direction, so the follow-up change for that direction finds the pair equal and publishes
 *      nothing - the round-trip ends by construction, not by relying on a dialog behaviour.
 *   3. FOREIGN BACKENDS ARE IGNORED, both directions alike, and the recorded pair is dropped with
 *      them so a stale one cannot mis-fire once the loopback is chosen again.
 *   4. A PAYLOAD WITHOUT A DEPTH answers without one, so the dialog leaves the width combos alone.
 *
 * Driven through the real MessageBus, with a captor on PREFS_SAMPLE_RATE_SET: the subscriber's
 * own answer and the bus message are asserted to be the same object, so a green test cannot mean
 * "it decided correctly but published nothing".
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { Events } from '../js/bus/events.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { LoopbackFormatConstraint } from '../js/loopback/loopback-format-constraint.js';
import { LOOPBACK_BACKEND } from '../js/loopback/loopback-device-ref.js';

const CARD = 'Phonalyser Loopback';
const QA40X_BACKEND = 'QA40X';
const WASAPI_BACKEND = 'WASAPI';

/** The singleton with a captor on its answers, reset to a known baseline (nothing recorded) -
 *  a change on another backend is the subscriber's own reset primitive. */
function fixture() {
  const constraint = LoopbackFormatConstraint.instance();
  const bus = MessageBus.instance();
  const emitted = [];
  const captor = (set) => emitted.push(set);
  bus.subscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
  const f = {
    constraint,
    emitted,
    /** Feeds one edited-direction change and returns the answer the subscriber gave. */
    changed(input, hz, bits, backend = LOOPBACK_BACKEND) {
      return constraint.onFormatChanged({
        input, sampleRateHz: hz, bitDepth: bits, backend, card: CARD,
      });
    },
    release() {
      bus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
    },
  };
  f.changed(true, 0, 0, WASAPI_BACKEND);
  emitted.length = 0;
  return f;
}

test('an input change corrects BOTH fields of the other direction, then the pair is a no-op', () => {
  const f = fixture();
  try {
    // Establish an equal pair (the first change syncs both directions).
    f.changed(false, 48000, 24);
    f.emitted.length = 0;

    // The input jumps to 96 kHz / 16 bits - the output must follow in BOTH.
    const answer = f.changed(true, 96000, 16);
    assert.deepEqual(answer, {
      input: false, sampleRateHz: 96000, bitDepth: 16, backend: LOOPBACK_BACKEND, card: CARD,
    }, 'the output takes the input\'s rate AND depth, card echoed');
    assert.equal(f.emitted.length, 1, 'exactly one correction reached the bus');
    assert.equal(f.emitted[0], answer, 'and it is the very object the caller was handed');

    // Both directions now read 96 kHz / 16 bits. Even a redundant output change finds the recorded
    // pair already equal, so nothing is published - the round-trip terminates by construction.
    f.emitted.length = 0;
    assert.equal(f.changed(false, 96000, 16), null);
    assert.equal(f.emitted.length, 0, 'equal recorded formats => no further correction');
  } finally {
    f.release();
  }
});

test('a DEPTH-only change still corrects the other direction', () => {
  const f = fixture();
  try {
    f.changed(true, 48000, 24);
    f.emitted.length = 0;

    // The samples ARE shared, so 16 in one direction cannot be 24 in the other.
    const answer = f.changed(true, 48000, 16);
    assert.deepEqual(answer, {
      input: false, sampleRateHz: 48000, bitDepth: 16, backend: LOOPBACK_BACKEND, card: CARD,
    });
    assert.equal(f.emitted.length, 1);
  } finally {
    f.release();
  }
});

test('an OUTPUT change corrects the input, mirrored', () => {
  const f = fixture();
  try {
    const answer = f.changed(false, 192000, 32);
    assert.deepEqual(answer, {
      input: true, sampleRateHz: 192000, bitDepth: 32, backend: LOOPBACK_BACKEND, card: CARD,
    });
    assert.equal(f.emitted.length, 1);
  } finally {
    f.release();
  }
});

test('the first-ever change syncs the still-unset direction', () => {
  const f = fixture();
  try {
    const answer = f.changed(true, 44100, 20);
    assert.equal(answer.input, false, 'unset counts as "differs"');
    assert.equal(answer.sampleRateHz, 44100);
    assert.equal(answer.bitDepth, 20);
  } finally {
    f.release();
  }
});

test('a foreign backend is ignored on BOTH directions, and the tracking is reset', () => {
  const f = fixture();
  try {
    f.changed(true, 48000, 24);     // seed an equal loopback pair
    f.emitted.length = 0;

    // The analyzer's own constraint owns these; this one must not answer them, whichever
    // direction they name.
    assert.equal(f.changed(true, 96000, 24, QA40X_BACKEND), null);
    assert.equal(f.changed(false, 96000, 24, QA40X_BACKEND), null);
    assert.equal(f.changed(true, 44100, 16, WASAPI_BACKEND), null);
    assert.equal(f.changed(false, 44100, 16, WASAPI_BACKEND), null);
    assert.equal(f.emitted.length, 0, 'a change on another backend publishes nothing');

    // Proof the tracking was reset: with the pair unset again, the next loopback change behaves
    // like a first-ever change and syncs the other direction.
    const answer = f.changed(true, 192000, 32);
    assert.equal(f.emitted.length, 1, 'tracking was reset - the next change syncs the other');
    assert.deepEqual(answer, {
      input: false, sampleRateHz: 192000, bitDepth: 32, backend: LOOPBACK_BACKEND, card: CARD,
    });
  } finally {
    f.release();
  }
});

test('a payload without a depth answers without one', () => {
  const f = fixture();
  try {
    // The rate-only payload shape: a dialog whose width combos are hidden carries no depth, and
    // the correction says nothing about the depth either, so the combos are left alone.
    const answer = f.constraint.onFormatChanged({
      input: true, sampleRateHz: 48000, backend: LOOPBACK_BACKEND, card: CARD,
    });
    assert.equal(answer.bitDepth, 0, 'no depth in, no depth out');
    assert.equal(answer.sampleRateHz, 48000);
    assert.equal(answer.input, false);
  } finally {
    f.release();
  }
});

test('a null payload is answered with null', () => {
  const f = fixture();
  try {
    assert.equal(f.constraint.onFormatChanged(null), null);
    assert.equal(f.emitted.length, 0);
  } finally {
    f.release();
  }
});
