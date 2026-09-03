/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Mirror of org.edgo.audio.measure.sound.qa40x.Qa40xDuplexEngineTest - the
// session model against doc/QA40X-PROTOCOL.md §5/§7/§10: the exact start
// register order + 100 ms settle, priming past the 1024-frame threshold, silence
// on an unattached lane, live source swap with no register writes, teardown
// order (cancelAll before reg8=0), little-endian DAC packing with L/R swap, the
// read-clocked / bounded / debt-repaying write pacing, and restart on a rate
// change. Driven entirely through the FakeTransport double - NO hardware, NO
// browser.
//
// Config under test: input 0 dBV (code 0), output 18 dBV (code 3), 48 kHz
// (code 0). Registers are decimal here to pin the wire independently of the
// protocol constants: 8 = run, 5 = input FS, 6 = output FS, 9 = rate,
// 10 = front-panel I2S, 11 = I2S frame width.
//
// The web engine's attach / detach / change methods return promises where Java
// returns void (a browser cannot block on a bulk transfer), so every call that
// drives register traffic is awaited; the completion drivers stay synchronous.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  Qa40xDuplexEngine, IN_FLIGHT_TRANSFERS, MAX_WRITE_DEBT, STEADY_CHUNK_BYTES, STEADY_FRAMES,
} from '../js/qa40x/qa40x-duplex-engine.js';
import { FakeTransport } from './fake-qa40x-transport.mjs';

const INPUT_DBV = 0;
const OUTPUT_DBV = 18;
const RATE_HZ = 48_000;
const CHUNK_BYTES = 16_384;
const LEFT_WORD = 0x01020304;
const RIGHT_WORD = 0x05060708;
/** The engine's per-direction double-buffer depth - two reads, at most two outstanding writes. */
const IN_FLIGHT = 2;

/** Recording Sleeper (Java's RecordingSleeper). */
function recordingSleeper(sleeps) {
  return (millis) => {
    sleeps.push(millis);
  };
}

function engine(fake, sleeper) {
  return new Qa40xDuplexEngine(fake, sleeper, INPUT_DBV, OUTPUT_DBV, RATE_HZ);
}

/** @returns {(destination: Int32Array, frames: number) => void} */
function patternSource() {
  return (destination, frames) => {
    for (let f = 0; f < frames; f++) {
      destination[2 * f] = LEFT_WORD;
      destination[2 * f + 1] = RIGHT_WORD;
    }
  };
}

/**
 * Buffer IDENTITY assertion (===), NOT contents: the pools exist to hand the same
 * 16 KB buffers back out. assert.ok keeps a failure from diffing two 16 KB arrays.
 */
function assertSameBuffer(actual, expected, message) {
  assert.ok(actual === expected, message);
}

/** Asserts ONE frame of `chunk`: device slot 0 = R, slot 1 = L, each little-endian (§5). */
function assertFrameAt(chunk, frame, left, right) {
  const offset = frame * 8;
  assert.equal(chunk[offset], right & 0xFF, `frame ${frame} R byte 0`);
  assert.equal(chunk[offset + 1], (right >> 8) & 0xFF, `frame ${frame} R byte 1`);
  assert.equal(chunk[offset + 2], (right >> 16) & 0xFF, `frame ${frame} R byte 2`);
  assert.equal(chunk[offset + 3], (right >>> 24) & 0xFF, `frame ${frame} R byte 3`);
  assert.equal(chunk[offset + 4], left & 0xFF, `frame ${frame} L byte 0`);
  assert.equal(chunk[offset + 5], (left >> 8) & 0xFF, `frame ${frame} L byte 1`);
  assert.equal(chunk[offset + 6], (left >> 16) & 0xFF, `frame ${frame} L byte 2`);
  assert.equal(chunk[offset + 7], (left >>> 24) & 0xFF, `frame ${frame} L byte 3`);
}

/** The whole 2048-frame chunk a constant L,R source must produce. */
function swappedLeChunk(left, right) {
  const expected = new Uint8Array(CHUNK_BYTES);
  const view = new DataView(expected.buffer);
  for (let f = 0; f < STEADY_FRAMES; f++) {
    view.setInt32(8 * f, right, true);       // device slot 0 = R
    view.setInt32(8 * f + 4, left, true);    // device slot 1 = L
  }
  return expected;
}

/**
 * Asserts the ENTIRE chunk is the swapped little-endian pattern - all 2048
 * frames, not just frame 0. A fill loop that stops early (or a recycled buffer
 * only partly rewritten) leaves frame 0 perfect and every frame behind it stale,
 * which is exactly the DAC glitch this pins.
 */
function assertFrameSwappedLe(chunk, left, right) {
  assert.equal(chunk.length, CHUNK_BYTES, 'a steady chunk is 2048 stereo int32 frames');
  // Spot-checks first, so a failure names the frame and byte instead of dumping 16 KB.
  for (const frame of [0, 1, STEADY_FRAMES >> 1, STEADY_FRAMES - 1]) {
    assertFrameAt(chunk, frame, left, right);
  }
  // Then the full scan - every one of the 2048 frames, reported by first mismatch
  // rather than a 16 KB deep diff.
  const expected = swappedLeChunk(left, right);
  const mismatch = chunk.findIndex((byte, i) => byte !== expected[i]);
  assert.equal(mismatch, -1, mismatch < 0 ? ''
    : `chunk byte ${mismatch} (frame ${mismatch >> 3}) is ${chunk[mismatch]}, want ${expected[mismatch]}`);
}

/** Builds interleaved little-endian int32 stereo ADC bytes from logical L,R pairs. */
function adcPayload(frames) {
  const bytes = new Uint8Array(frames.length * 8);
  const view = new DataView(bytes.buffer);
  frames.forEach(([left, right], f) => {
    view.setInt32(8 * f, left, true);
    view.setInt32(8 * f + 4, right, true);
  });
  return bytes;
}

/** Decodes `count` interleaved little-endian int32 stereo frames back to [L,R] pairs. */
function decodeStereo(bytes, count) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const frames = [];
  for (let f = 0; f < count; f++) {
    frames.push([view.getInt32(8 * f, true), view.getInt32(8 * f + 4, true)]);
  }
  return frames;
}

test('first attach starts with the exact register order and settle', async () => {
  const fake = new FakeTransport();
  const sleeps = [];

  await engine(fake, recordingSleeper(sleeps)).attachCapture(() => {});

  assert.deepEqual(fake.registerWrites, [
    { reg: 8, value: 0 },     // reg8 = 0 (recover)
    { reg: 5, value: 0 },     // input FS code 0
    { reg: 6, value: 3 },     // output FS code 3
    { reg: 9, value: 0 },     // rate code 0
    { reg: 11, value: 0 },    // reg 0x0B = 0 (frame width first)
    { reg: 10, value: 0 },    // reg 0x0A = 0 (front-panel I2S off)
    { reg: 8, value: 5 },     // reg8 = 5 (start)
  ]);
  assert.deepEqual(sleeps, [100]);
});

test('the output queue is primed immediately after RUN_START, with no register write between', async () => {
  // Ordering pin. The Java engine writes reg8 = RUN_START and THEN primes the
  // output past the 1024-frame threshold (Qa40xDuplexEngine#startStream: the
  // registerWrite(REG_RUN, RUN_START) precedes primeStream()), which is what
  // this port reproduces. FakeTransport's op log records register traffic only,
  // so audio submissions are folded in here to pin their position relative to
  // the run register.
  class OrderRecordingTransport extends FakeTransport {
    submitAudioWrite(data, length) {
      this.ops.push('audio-write');
      super.submitAudioWrite(data, length);
    }

    submitAudioRead(buffer) {
      this.ops.push('audio-read');
      super.submitAudioRead(buffer);
    }
  }
  const fake = new OrderRecordingTransport();

  await engine(fake, () => {}).attachCapture(() => {});

  assert.deepEqual(fake.ops, [
    'reg=8:0', 'reg=5:0', 'reg=6:3', 'reg=9:0', 'reg=11:0', 'reg=10:0', 'reg=8:5',
    'audio-read', 'audio-read', 'audio-write', 'audio-write',
  ]);
});

test('priming reaches the start threshold with steady chunks', async () => {
  const fake = new FakeTransport();

  await engine(fake, () => {}).attachCapture(() => {});

  // 1024-frame threshold = 8192 bytes; steady chunks are 2048 frames = 16 KB each.
  assert.ok(fake.totalWriteBytesSubmitted() >= 1024 * 8,
    'priming must queue at least the 1024-frame start threshold');
  for (const chunk of fake.submittedWrites) {
    assert.equal(chunk.length, CHUNK_BYTES);
  }
  assert.equal(STEADY_CHUNK_BYTES, CHUNK_BYTES);
  assert.equal(fake.pendingReadBuffers.length, 2, 'two reads kept in flight');
  assert.equal(IN_FLIGHT_TRANSFERS, IN_FLIGHT);
});

test('an unattached generator lane sends silence', async () => {
  const fake = new FakeTransport();

  await engine(fake, () => {}).attachCapture(() => {});

  for (const b of fake.submittedWrites[0]) {
    assert.equal(b, 0, 'silence lane must emit zeros');
  }
});

test('the silence source WRITES its zeros - over a dirty recycled buffer and a dirty scratch', async () => {
  // The test above alone is VACUOUS: a fresh engine's scratch and its first write
  // buffers are already zero, so a silence source that writes nothing at all
  // ("SILENCE = () => {}") still produces a zero chunk. Here BOTH sources of
  // staleness are pre-dirtied through the engine's own recycling path before the
  // silence write happens:
  //   - the generator lane runs first, so #scratch holds LEFT_WORD/RIGHT_WORD;
  //   - its pattern-filled chunk is completed, so that very buffer goes back into
  //     the free pool and is handed out again for the silence write.
  // A silence source that does not actively zero-fill therefore re-sends the
  // previous tone - the audible failure this pins (a detached generator that keeps
  // playing).
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});                 // starts the stream, silence-primed
  const [firstBuffer, secondBuffer] = fake.pendingWriteBuffers;
  await eng.attachGenerator(patternSource());        // live swap, no restart

  // One paced pattern write: it reuses `firstBuffer` and dirties #scratch.
  fake.completeNextWrite();                          // frees a slot AND recycles firstBuffer
  fake.completeNextRead();                           // read-clocked write, now the pattern
  assertSameBuffer(fake.pendingWriteBuffers.at(-1), firstBuffer, 'the pattern write reused the pool buffer');
  assertFrameSwappedLe(fake.submittedWrites.at(-1), LEFT_WORD, RIGHT_WORD);

  await eng.detachGenerator();                       // capture still attached: no teardown
  assert.equal(fake.cancelAllCount, 0, 'one lane still attached - the stream keeps running');

  // Drain both outstanding writes so the pool holds the DIRTY pattern buffer, then
  // let two read-clocked silence writes go out - the second borrows it back.
  fake.completeNextWrite();                          // recycles secondBuffer
  fake.completeNextWrite();                          // recycles firstBuffer (still pattern-filled)
  assert.notEqual(firstBuffer[0], 0, 'precondition: the recycled buffer is dirty');
  fake.completeNextRead();
  fake.completeNextRead();

  assert.equal(fake.pendingWriteBuffers.length, IN_FLIGHT);
  assertSameBuffer(fake.pendingWriteBuffers[0], secondBuffer,
    'both silence writes came out of the pool, oldest buffer first');
  assertSameBuffer(fake.pendingWriteBuffers[1], firstBuffer,
    'so the last silence write landed in the recycled DIRTY buffer');
  for (const b of fake.submittedWrites.at(-1)) {
    assert.equal(b, 0, 'the silence source must WRITE zeros over the stale samples');
  }
  assert.equal(firstBuffer.every((b) => b === 0), true, 'and the buffer itself is zeroed in place');
});

test('the DAC frame is little-endian and L/R swapped', async () => {
  const fake = new FakeTransport();

  await engine(fake, () => {}).attachGenerator(patternSource());

  assertFrameSwappedLe(fake.submittedWrites[0], LEFT_WORD, RIGHT_WORD);
});

test('a generator attach mid-stream swaps the payload without register writes', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const registerWritesAfterStart = fake.registerWrites.length;

  fake.completeNextRead();                     // writes already at the in-flight cap - none added
  await eng.attachGenerator(patternSource());  // live swap, no restart

  assert.equal(fake.registerWrites.length, registerWritesAfterStart,
    'attaching a lane mid-stream must not write any register');

  const writesBefore = fake.submittedWrites.length;
  fake.completeNextWrite();                    // free one in-flight write slot
  fake.completeNextRead();                     // the read-clocked, bounded write now carries the real source
  assertFrameSwappedLe(fake.submittedWrites[writesBefore], LEFT_WORD, RIGHT_WORD);
});

test('an ADC read passes through to the consumer unswapped', async () => {
  const fake = new FakeTransport();
  let received = null;
  let receivedLength = 0;

  await engine(fake, () => {}).attachCapture((buffer, length) => {
    received = buffer.slice(0, buffer.length);
    receivedLength = length;
  });

  const payload = new Uint8Array(CHUNK_BYTES);
  payload[0] = 1;
  payload[1] = 2;
  payload[7] = 0xFF;
  fake.completeNextRead(payload);

  assert.equal(receivedLength, CHUNK_BYTES);
  assert.equal(received[0], 1);
  assert.equal(received[1], 2);
  assert.equal(received[7], 0xFF);
});

test('captured bytes decode as interleaved little-endian int32 stereo', async () => {
  const fake = new FakeTransport();
  let received = null;
  let receivedLength = 0;

  await engine(fake, () => {}).attachCapture((buffer, length) => {
    received = buffer.slice(0, length);
    receivedLength = length;
  });

  // Logical L,R pairs, NOT swapped and NOT inverted on the capture side (§9 item 6).
  const expected = [[1, -1], [0x7FFFFFFF, -0x80000000], [0x00010203, -123456789]];
  fake.completeNextRead(adcPayload(expected));

  assert.equal(receivedLength, expected.length * 8);
  assert.deepEqual(decodeStereo(received, expected.length), expected);
});

test('a write completion recycles the buffer without disruption', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const writesBefore = fake.submittedWrites.length;

  fake.completeNextWrite();   // exercises writeCompleted -> buffer recycled
  fake.completeNextRead();    // read-driven pacing submits one more write

  assert.equal(fake.cancelAllCount, 0, 'completions must not tear the stream down');
  assert.equal(fake.submittedWrites.length, writesBefore + 1);
});

test('a completed transfer\'s buffer is RE-USED, not replaced - both pools', async () => {
  // "Recycles the buffer" above only counts submissions, so an engine whose
  // #returnWriteBuffer / #returnReadBuffer were both empty (allocating a fresh
  // 16 KB buffer per transfer, garbage by the second) passes it unchanged. Buffer
  // IDENTITY is the thing the pools exist for, so that is what is asserted here.
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const [firstWrite] = fake.pendingWriteBuffers;
  const [firstRead, secondRead] = fake.pendingReadBuffers;

  // Playback: the drained buffer goes back to the pool and out again.
  fake.completeNextWrite();
  fake.completeNextRead();
  assertSameBuffer(fake.pendingWriteBuffers.at(-1), firstWrite,
    'the paced write must re-use the completed buffer, not allocate a new one');

  // Capture: readCompleted re-arms BEFORE returning the buffer, so the completed
  // buffer is handed out one read later - the SECOND completion re-uses the first
  // buffer.
  const afterFirstRead = [...fake.pendingReadBuffers];
  assert.equal(afterFirstRead.includes(firstRead), false,
    'the re-armed read cannot be the buffer still being consumed');
  assertSameBuffer(afterFirstRead[0], secondRead, 'the priming read is still in flight');
  fake.completeNextRead();
  assertSameBuffer(fake.pendingReadBuffers.at(-1), firstRead,
    'the recycled capture buffer must come back out of the pool');
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT, 'reads held at the in-flight depth');
});

test('banked write debt is capped at MAX_WRITE_DEBT', async () => {
  // The cap is a real ceiling, not decoration: with it raised to
  // Number.MAX_SAFE_INTEGER every one of the reads below is repaid, so the engine
  // keeps pushing stale audio long after the output unwedges. Pinned twice - the
  // constant, and the behaviour it produces.
  assert.equal(MAX_WRITE_DEBT, 32, "Java's Qa40xDuplexEngine.MAX_WRITE_DEBT");

  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const primedWrites = fake.submittedWrites.length;

  // Writes never drain: every read completion banks one write, saturating the cap.
  const reads = MAX_WRITE_DEBT + 25;
  for (let i = 0; i < reads; i++) {
    fake.completeNextRead();
  }
  assert.equal(fake.submittedWrites.length, primedWrites,
    'nothing goes out while both in-flight slots are busy');

  // Now let the output drain completely: each completion repays exactly one owed
  // write, so the total repaid is the CAP - never the full read count.
  while (fake.pendingWriteBuffers.length > 0) {
    fake.completeNextWrite();
  }

  assert.equal(fake.submittedWrites.length, primedWrites + MAX_WRITE_DEBT,
    `debt is forfeited beyond the cap: ${reads} reads may repay at most ${MAX_WRITE_DEBT} writes`);
});

test('the last detach cancels before the stop register, then parks the safe ranges', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  await eng.detachCapture();

  assert.equal(fake.cancelAllCount, 1);
  // Teardown tail: cancelAll strictly BEFORE reg8 = 0 (§7 step 7), then the idle
  // analyzer parks at the protected ranges (§7 step 8): input +42 dBV (code 7,
  // attenuator relay engaged) and output −12 dBV (code 0) - so a sensitive range
  // never sits live between measurements - and finally the front-panel I2S port
  // is stopped (reg 0x0A = 0), leaving it as a fresh connect finds it.
  assert.deepEqual(fake.ops.slice(-6),
    ['cancelAll', 'reg=8:0', 'reg=5:7', 'reg=6:0', 'reg=10:0', 'reg=11:0']);
});

test('a second attach does not restart', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachGenerator(patternSource());
  const afterFirst = fake.registerWrites.length;

  await eng.attachCapture(() => {});

  assert.equal(fake.registerWrites.length, afterFirst, 'second attach must not restart');
  assert.equal(fake.cancelAllCount, 0);
});

test('detaching one of two lanes keeps streaming', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  await eng.attachGenerator(patternSource());
  const registerWrites = fake.registerWrites.length;

  await eng.detachGenerator();

  assert.equal(fake.cancelAllCount, 0, 'one lane still attached - no teardown');
  assert.equal(fake.registerWrites.length, registerWrites);
});

test('writes stay 1:1 with reads when the transport accepts writes instantly', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const primedWrites = fake.submittedWrites.length;

  // Model a transport that completes writes the instant they are accepted:
  // before every paced read, drain all outstanding writes. A correct engine must
  // then submit EXACTLY one write per read completion (read-clocked, 1:1) -
  // never a self-sustaining write loop off writeCompleted.
  const reads = 200;
  for (let i = 0; i < reads; i++) {
    while (fake.pendingWriteBuffers.length > 0) {
      fake.completeNextWrite();
    }
    fake.completeNextRead();
  }

  assert.equal(fake.submittedWrites.length, primedWrites + reads,
    'exactly one paced write per completed read once priming is done');
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT, 'reads held at the in-flight depth');
});

test('the write queue stays bounded when writes never drain', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});

  // Writes are never completed - models the output endpoint draining slower than
  // read completions arrive (a scheduling-jitter / backgrounded-tab burst).
  // Outstanding writes MUST stay bounded at the in-flight depth.
  const reads = 1_000;
  for (let i = 0; i < reads; i++) {
    fake.completeNextRead();
  }

  assert.ok(fake.pendingWriteBuffers.length <= IN_FLIGHT,
    `outstanding writes must stay bounded at the in-flight depth, was ${fake.pendingWriteBuffers.length}`);
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT, 'reads held at the in-flight depth');
});

test('writes skipped at the cap are repaid when a slot frees', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const primedWrites = fake.submittedWrites.length;

  // The 2026-07-17 bench race: reads and writes complete at the SAME average
  // rate, but bursty - each cycle two reads land while both write slots are
  // still draining, THEN the two writes complete. Skipping the paced write at
  // the cap without repaying it loses half the writes here (the periodic
  // underrun "meander"); debt-repaying pacing must stay exactly 1:1 long-run and
  // still bounded.
  const cycles = 100;
  for (let i = 0; i < cycles; i++) {
    fake.completeNextRead();
    fake.completeNextRead();
    fake.completeNextWrite();
    fake.completeNextWrite();
  }

  assert.equal(fake.submittedWrites.length, primedWrites + 2 * cycles,
    'every read completion\'s paced write is eventually submitted - skipped ones repaid');
  assert.ok(fake.pendingWriteBuffers.length <= IN_FLIGHT,
    `repayment never exceeds the in-flight bound, was ${fake.pendingWriteBuffers.length}`);
});

test('a failed transfer is NOT auto-recovered - Java parity', async () => {
  // Pins Qa40xDuplexEngine#transferFailed (Java): "No auto-recovery (§5); a
  // transfer cancelled during stop also lands here (benign)." It frees a failed
  // WRITE's in-flight slot and logs - and submits NOTHING. Java restores the read
  // depth only from readCompleted (submitRead is called from primeStream and
  // readCompleted, nowhere else), so a failed capture transfer lowers the depth on
  // the desktop exactly as it does here.
  //
  // Re-arming here would also resurrect the read pipe during a stop: cancelAll
  // reports EVERY in-flight transfer through transferFailed, and a device that
  // keeps failing immediately would turn that into an unbounded submit/fail loop.
  // If the read depth is ever to be restored on failure, that is a deliberate
  // deviation from the desktop and belongs in BOTH engines - this assertion is the
  // one to flip.
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT);

  fake.failNextRead('cancelled');           // the transport reports the abort, as libusb does

  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT - 1,
    'the engine must not submit a replacement read on failure (Java Qa40xDuplexEngine#transferFailed)');

  // And a cancelled READ must not free a WRITE slot - the `!read` half of the
  // guard. Both write slots are still busy, so the next read completion may only
  // bank debt. Freeing a write slot per failed read is exactly what a cancelAll
  // would do (it reports EVERY in-flight transfer, reads included): the write
  // accounting would drop to zero and the pacing would then push writes straight
  // past IN_FLIGHT_TRANSFERS, the open-loop output queue this engine exists to
  // prevent.
  const writesAfterFailedRead = fake.submittedWrites.length;
  fake.completeNextRead();
  assert.equal(fake.submittedWrites.length, writesAfterFailedRead,
    'a cancelled read must not free a write slot');

  // A failed WRITE, by contrast, DOES free its in-flight slot, so the next
  // read-clocked write goes out instead of the pacing wedging at the cap.
  const writesBefore = fake.submittedWrites.length;
  fake.failNextWrite('cancelled');
  fake.completeNextRead();

  assert.equal(fake.submittedWrites.length, writesBefore + 1,
    'a failed write frees its in-flight slot for the next paced write');
});

test('a failed write frees exactly ONE slot, and the pacing stays bounded afterwards', async () => {
  // Drives the failure path through the transport double (failNextWrite) instead of
  // poking the engine's callback, and pins the accounting on BOTH sides of
  // transferFailed's write branch: too few frees and the pacing wedges at the cap
  // (silent DAC), too many and the in-flight bound is broken (the open-loop output
  // queue this engine exists to prevent).
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  const primedWrites = fake.submittedWrites.length;
  assert.equal(fake.pendingWriteBuffers.length, IN_FLIGHT);

  // Both outstanding writes fail (what cancelAll reports for every transfer in
  // flight), then two reads land: exactly two paced writes may go out.
  fake.failNextWrite('cancelled');
  fake.failNextWrite('cancelled');
  fake.completeNextRead();
  fake.completeNextRead();

  assert.equal(fake.submittedWrites.length, primedWrites + 2,
    'each failed write freed one slot for one paced write');
  assert.equal(fake.pendingWriteBuffers.length, IN_FLIGHT,
    'and the in-flight bound still holds');

  // A third read finds both slots busy again: it banks debt, submits nothing.
  fake.completeNextRead();
  assert.equal(fake.submittedWrites.length, primedWrites + 2, 'the cap still holds after a failure');
});

test('overlapping register sequences are serialized, never interleaved', async () => {
  // The engine's ONE deliberate deviation from Java: Java's ioLock is a monitor
  // held across the blocking register sequence, while here the sequence yields at
  // every await, so #serialize chains each sequence behind the last AND - like the
  // Java, which re-reads `streaming` only after acquiring ioLock - takes the
  // start / stop DECISION inside the chained section.
  //
  // Both attaches below are issued WITHOUT awaiting the first, which is the only
  // way the chain can be observed at all: every other test awaits each call, so
  // dropping the chain entirely leaves them green. Without it, the second attach
  // sees `streaming` still false (the first sequence has not reached its last
  // register yet) and starts a SECOND session: two start sequences interleaved
  // register by register, and the stream primed twice over.
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  const firstAttach = eng.attachGenerator(patternSource());
  const secondAttach = eng.attachCapture(() => {});
  await Promise.all([firstAttach, secondAttach]);

  assert.deepEqual(fake.registerWrites, [
    { reg: 8, value: 0 },
    { reg: 5, value: 0 },
    { reg: 6, value: 3 },
    { reg: 9, value: 0 },
    { reg: 11, value: 0 },
    { reg: 10, value: 0 },
    { reg: 8, value: 5 },
  ], 'exactly ONE start sequence, in order - the two callers never interleave register traffic');
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT, 'and the stream is primed exactly once');
  assert.equal(fake.submittedWrites.length, IN_FLIGHT);
  assert.equal(fake.cancelAllCount, 0);
});

test('an overlapping detach is serialized behind the start it must undo', async () => {
  // The same chain from the teardown side: a detach issued while the start
  // sequence is still in flight must run AFTER it and tear the session down. With
  // the chain gone the detach reads `streaming` as false mid-start, skips the stop
  // altogether and leaves the analyzer streaming with no lane attached - the
  // register log below is then just the bare start sequence.
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  const attach = eng.attachCapture(() => {});
  const detach = eng.detachCapture();
  await Promise.all([attach, detach]);

  assert.equal(fake.cancelAllCount, 1, 'the detach must still stop the session it raced');
  assert.deepEqual(fake.ops, [
    'reg=8:0', 'reg=5:0', 'reg=6:3', 'reg=9:0', 'reg=11:0', 'reg=10:0', 'reg=8:5',
    'cancelAll', 'reg=8:0', 'reg=5:7', 'reg=6:0', 'reg=10:0', 'reg=11:0',
  ], 'start sequence in full, THEN the teardown - no interleaving');
});

test('a FAILED register sequence does not poison the serialization chain', async () => {
  // Java releases ioLock's monitor when a sequence throws, and the very next
  // synchronized (ioLock) block runs normally. The chain must behave the same: a
  // tail chained on the REJECTED promise makes every later .then(action) skip its
  // callback and re-reject the original failure, so ONE stalled register write (a
  // device yanked mid-start, a wedged control transfer) bricks the engine for the
  // page's life - and, worst of all, the detach that still owes #parkSafeRanges()
  // never runs it: the analyzer is left sitting on the session's sensitive input
  // range instead of +42 dBV (desktop commit 8d8ee8d).
  class StallingTransport extends FakeTransport {
    /** Registers whose NEXT write stalls - one shot each, as a wedged transfer is. */
    stalls = new Set();

    registerWrite(reg, value) {
      if (this.stalls.delete(reg)) {
        return Promise.reject(new Error(`QA40x register write stalled: reg ${reg}`));
      }
      return super.registerWrite(reg, value);
    }
  }
  const fake = new StallingTransport();
  fake.stalls.add(9);                           // the rate write wedges, mid-start
  const eng = engine(fake, () => {});

  await assert.rejects(eng.attachCapture(() => {}), /stalled: reg 9/,
    'the failure is reported to ITS caller');

  // Every later action must still REACH the device. With a poisoned chain each of
  // these rejects with the original stall without writing a single register.
  await eng.detachCapture();                    // releases the lane the failed start claimed
  await eng.changeSampleRate(96_000);
  const opsBeforeRetry = fake.ops.length;
  await eng.attachCapture(() => {});            // the retry must actually start the stream
  assert.ok(fake.ops.length > opsBeforeRetry, 'the retried start wrote its registers');
  assert.equal(fake.pendingReadBuffers.length, IN_FLIGHT, 'and primed the stream');

  await eng.detachCapture();

  assert.equal(fake.cancelAllCount, 1, 'the retried session is torn down exactly once');
  assert.deepEqual(fake.ops.slice(-6),
    ['cancelAll', 'reg=8:0', 'reg=5:7', 'reg=6:0', 'reg=10:0', 'reg=11:0'],
    'the last detach still parks the safe ranges after an earlier failure');
});

test('a second capture consumer is refused loudly', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});

  assert.throws(() => eng.attachCapture(() => {}), /capture lane already attached/,
    'a second capture consumer must be refused, not silently split');
  assert.equal(fake.cancelAllCount, 0, 'the refusal must not tear the running stream down');
});

test('capture reattaches after a detach', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  await eng.detachCapture();
  // A fresh acquire after a clean detach is a normal restart, not a split.
  await eng.attachCapture(() => {});

  assert.equal(fake.cancelAllCount, 1, 'exactly one teardown from the single detach');
});

test('a sample-rate change while running restarts the session', async () => {
  const fake = new FakeTransport();
  const eng = engine(fake, () => {});

  await eng.attachCapture(() => {});
  await eng.changeSampleRate(96_000);

  assert.equal(fake.cancelAllCount, 1, 'rate change stops the running session');
  // The FULL op log, not a tail window: a restart must go stopStream -> startStream
  // and nothing else. Asserting only the last seven writes hides whatever the stop
  // added BEFORE them, so a restart that ran the whole #stopSession() teardown
  // (park 5:7 / 6:0 + I2S off 10:0 / 11:0 wedged in between) would still match -
  // the exact behaviour #parkSafeRanges' comment forbids, because it clacks the
  // attenuator relay to +42 dBV and back and interrupts the front-panel I2S tone
  // on every range or rate change.
  assert.deepEqual(fake.ops, [
    'reg=8:0', 'reg=5:0', 'reg=6:3', 'reg=9:0', 'reg=11:0', 'reg=10:0', 'reg=8:5',
    'cancelAll', 'reg=8:0',                      // stop only: no park, no I2S stop
    'reg=8:0', 'reg=5:0', 'reg=6:3',
    'reg=9:1',                                   // 96 kHz -> code 1
    'reg=11:0',                                  // frame width first, cleared here
    'reg=10:0',                                  // then I2S control, off here
    'reg=8:5',
  ]);
});

test('the front-panel I2S port is driven from the injected suppliers', async () => {
  const fake = new FakeTransport();
  // Java's second constructor: i2sEnabled / i2sBits are READ at each session
  // boundary rather than copied, so a preference change between sessions is
  // picked up without any push.
  const eng = new Qa40xDuplexEngine(fake, () => {}, INPUT_DBV, OUTPUT_DBV, RATE_HZ,
    () => true, () => 16);

  await eng.attachCapture(() => {});

  assert.deepEqual(fake.registerWrites.slice(-3), [
    { reg: 11, value: 0x00 },  // 16-bit frame width (bit 6 clear)
    { reg: 10, value: 1 },     // I2S_START
    { reg: 8, value: 5 },
  ]);

  // The 16-bit case ALONE is blind: its width code is 0x00, the same value as
  // I2S_WIDTH_OFF, so it cannot tell "the bit-depth supplier was read" from "a
  // hardcoded 16" or from "the width register was never driven at all". Only a
  // 32-bit session separates the three - bit 6 set.
  const wideFake = new FakeTransport();
  const wide = new Qa40xDuplexEngine(wideFake, () => {}, INPUT_DBV, OUTPUT_DBV, RATE_HZ,
    () => true, () => 32);

  await wide.attachCapture(() => {});

  assert.deepEqual(wideFake.registerWrites.slice(-3), [
    { reg: 11, value: 0x40 },  // 32-bit frame width (bit 6 set)
    { reg: 10, value: 1 },     // I2S_START
    { reg: 8, value: 5 },
  ]);
});

test('an invalid range or rate is refused at construction', () => {
  const fake = new FakeTransport();

  assert.throws(() => new Qa40xDuplexEngine(fake, () => {}, 7, OUTPUT_DBV, RATE_HZ));
  assert.throws(() => new Qa40xDuplexEngine(fake, () => {}, INPUT_DBV, 0, RATE_HZ));
  assert.throws(() => new Qa40xDuplexEngine(fake, () => {}, INPUT_DBV, OUTPUT_DBV, 44_100));
});
