/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xDuplexEngine} session model against {@code doc/QA40X-PROTOCOL.md}
 * §5/§7/§10: the exact start register order + 100 ms settle, priming past the
 * 1024-frame threshold, silence on an unattached lane, live source swap with no
 * register writes, teardown order (cancelAll before reg8=0), little-endian DAC
 * packing with L/R swap, and restart on a rate change.
 *
 * <p>Config under test: input 0 dBV (code 0), output 18 dBV (code 3), 48 kHz
 * (code 0).  Registers are decimal here to pin the wire independently of the
 * protocol constants: 8 = run, 5 = input FS, 6 = output FS, 9 = rate.
 */
class Qa40xDuplexEngineTest {

    private static final int INPUT_DBV   = 0;
    private static final int OUTPUT_DBV  = 18;
    private static final int RATE_HZ     = 48_000;
    private static final int CHUNK_BYTES = 16_384;
    private static final int LEFT_WORD   = 0x01020304;
    private static final int RIGHT_WORD  = 0x05060708;
    /** The engine's per-direction double-buffer depth — two reads (see
     *  {@link #priming_reachesStartThresholdWithSteadyChunks}) and, after this fix,
     *  at most two outstanding writes. */
    private static final int IN_FLIGHT   = 2;

    /** Recording {@link Qa40xDuplexEngine.Sleeper}. */
    static final class RecordingSleeper implements Qa40xDuplexEngine.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    private Qa40xDuplexEngine engine(FakeTransport fake, Qa40xDuplexEngine.Sleeper sleeper) {
        return new Qa40xDuplexEngine(fake, sleeper, INPUT_DBV, OUTPUT_DBV, RATE_HZ);
    }

    private Qa40xDuplexEngine.SampleSource patternSource() {
        return (destination, frames) -> {
            for (int f = 0; f < frames; f++) {
                destination[2 * f]     = LEFT_WORD;
                destination[2 * f + 1] = RIGHT_WORD;
            }
        };
    }

    private void assertFrameSwappedLe(byte[] chunk, int left, int right) {
        // DAC L/R swapped (§5): device slot 0 = R, slot 1 = L; each little-endian.
        assertEquals((byte) right,         chunk[0]);
        assertEquals((byte) (right >> 8),  chunk[1]);
        assertEquals((byte) (right >> 16), chunk[2]);
        assertEquals((byte) (right >> 24), chunk[3]);
        assertEquals((byte) left,          chunk[4]);
        assertEquals((byte) (left >> 8),   chunk[5]);
        assertEquals((byte) (left >> 16),  chunk[6]);
        assertEquals((byte) (left >> 24),  chunk[7]);
    }

    @Test
    void firstAttach_startsWithExactRegisterOrderAndSettle() {
        FakeTransport fake = new FakeTransport();
        RecordingSleeper sleeper = new RecordingSleeper();

        engine(fake, sleeper).attachCapture((buffer, length) -> { });

        assertEquals(List.of(
                new FakeTransport.RegWrite(8, 0),   // reg8 = 0 (recover)
                new FakeTransport.RegWrite(5, 0),   // input FS code 0
                new FakeTransport.RegWrite(6, 3),   // output FS code 3
                new FakeTransport.RegWrite(9, 0),   // rate code 0
                new FakeTransport.RegWrite(11, 0),  // reg 0x0B = 0 (frame width first)
                new FakeTransport.RegWrite(10, 0),  // reg 0x0A = 0 (front-panel I2S off)
                new FakeTransport.RegWrite(8, 5)),  // reg8 = 5 (start)
                fake.registerWrites);
        assertEquals(List.of(100L), sleeper.sleeps);
    }

    @Test
    void priming_reachesStartThresholdWithSteadyChunks() {
        FakeTransport fake = new FakeTransport();

        engine(fake, millis -> { }).attachCapture((buffer, length) -> { });

        // 1024-frame threshold = 8192 bytes; steady chunks are 2048 frames = 16 KB each.
        assertTrue(fake.totalWriteBytesSubmitted() >= 1024 * 8,
                "priming must queue at least the 1024-frame start threshold");
        for (byte[] chunk : fake.submittedWrites) {
            assertEquals(CHUNK_BYTES, chunk.length);
        }
        assertEquals(2, fake.pendingReadBuffers.size(), "two reads kept in flight");
    }

    @Test
    void unattachedGeneratorLane_sendsSilence() {
        FakeTransport fake = new FakeTransport();

        engine(fake, millis -> { }).attachCapture((buffer, length) -> { });

        for (byte b : fake.submittedWrites.get(0)) {
            assertEquals(0, b, "silence lane must emit zeros");
        }
    }

    @Test
    void dacFrame_isLittleEndianAndLrSwapped() {
        FakeTransport fake = new FakeTransport();

        engine(fake, millis -> { }).attachGenerator(patternSource());

        assertFrameSwappedLe(fake.submittedWrites.get(0), LEFT_WORD, RIGHT_WORD);
    }

    @Test
    void generatorAttachMidStream_swapsPayloadWithoutRegisterWrites() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        int registerWritesAfterStart = fake.registerWrites.size();

        fake.completeNextRead();                 // writes already at the in-flight cap — none added
        engine.attachGenerator(patternSource()); // live swap, no restart

        assertEquals(registerWritesAfterStart, fake.registerWrites.size(),
                "attaching a lane mid-stream must not write any register");

        int writesBefore = fake.submittedWrites.size();
        fake.completeNextWrite();                // free one in-flight write slot
        fake.completeNextRead();                 // the read-clocked, bounded write now carries the real source
        assertFrameSwappedLe(fake.submittedWrites.get(writesBefore), LEFT_WORD, RIGHT_WORD);
    }

    @Test
    void adcRead_passesThroughToConsumerUnswapped() {
        FakeTransport fake = new FakeTransport();
        byte[][] received = new byte[1][];
        int[] receivedLength = new int[1];

        engine(fake, millis -> { }).attachCapture((buffer, length) -> {
            received[0] = buffer.clone();
            receivedLength[0] = length;
        });

        byte[] payload = new byte[CHUNK_BYTES];
        payload[0] = 1;
        payload[1] = 2;
        payload[7] = (byte) 0xFF;
        fake.completeNextRead(payload);

        assertEquals(CHUNK_BYTES, receivedLength[0]);
        assertEquals(1, received[0][0]);
        assertEquals(2, received[0][1]);
        assertEquals((byte) 0xFF, received[0][7]);
    }

    @Test
    void writeCompletion_recyclesBufferWithoutDisruption() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        int writesBefore = fake.submittedWrites.size();

        fake.completeNextWrite();   // exercises writeCompleted → buffer recycled
        fake.completeNextRead();    // read-driven pacing submits one more write

        assertEquals(0, fake.cancelAllCount, "completions must not tear the stream down");
        assertEquals(writesBefore + 1, fake.submittedWrites.size());
    }

    @Test
    void lastDetach_cancelsBeforeStopRegister_thenParksSafeRanges() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        engine.detachCapture();

        assertEquals(1, fake.cancelAllCount);
        // Teardown tail: cancelAll strictly BEFORE reg8 = 0 (§7 step 7), then
        // the idle analyzer parks at the protected ranges (§7 step 8): input
        // +42 dBV (code 7, attenuator relay engaged) and output −12 dBV
        // (code 0) — so a sensitive range never sits live between measurements —
        // and finally the front-panel I2S port is stopped (reg 0x0A = 0), leaving
        // it as a fresh connect finds it.
        assertEquals("cancelAll", fake.ops.get(fake.ops.size() - 6));
        assertEquals("reg=8:0",   fake.ops.get(fake.ops.size() - 5));
        assertEquals("reg=5:7",   fake.ops.get(fake.ops.size() - 4));
        assertEquals("reg=6:0",   fake.ops.get(fake.ops.size() - 3));
        assertEquals("reg=10:0",  fake.ops.get(fake.ops.size() - 2));
        assertEquals("reg=11:0",  fake.ops.get(fake.ops.size() - 1));
    }

    @Test
    void secondAttach_doesNotRestart() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachGenerator(patternSource());
        int afterFirst = fake.registerWrites.size();

        engine.attachCapture((buffer, length) -> { });

        assertEquals(afterFirst, fake.registerWrites.size(), "second attach must not restart");
        assertEquals(0, fake.cancelAllCount);
    }

    @Test
    void detachOneOfTwoLanes_keepsStreaming() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        engine.attachGenerator(patternSource());
        int registerWrites = fake.registerWrites.size();

        engine.detachGenerator();

        assertEquals(0, fake.cancelAllCount, "one lane still attached — no teardown");
        assertEquals(registerWrites, fake.registerWrites.size());
    }

    @Test
    void writesStay1to1WithReads_whenTransportAcceptsWritesInstantly() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        int primedWrites = fake.submittedWrites.size();

        // Model a transport that completes writes the instant they are accepted:
        // before every paced read, drain all outstanding writes.  A correct engine
        // must then submit EXACTLY one write per read completion (read-clocked, 1:1)
        // — never a self-sustaining write loop off writeCompleted.
        int reads = 200;
        for (int i = 0; i < reads; i++) {
            while (!fake.pendingWriteBuffers.isEmpty()) {
                fake.completeNextWrite();
            }
            fake.completeNextRead();
        }

        assertEquals(primedWrites + reads, fake.submittedWrites.size(),
                "exactly one paced write per completed read once priming is done");
        assertEquals(IN_FLIGHT, fake.pendingReadBuffers.size(), "reads held at the in-flight depth");
    }

    @Test
    void writeQueueStaysBounded_whenWritesNeverDrain() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });

        // Writes are never completed — models the output endpoint draining slower
        // than read completions arrive (a scheduling-jitter / unfocused-window
        // burst).  Outstanding writes MUST stay bounded at the in-flight depth; the
        // pre-fix engine submitted one write per read and grew the queue without
        // bound (the tx-vs-rx frame gap / discontinuity storm).
        int reads = 1_000;
        for (int i = 0; i < reads; i++) {
            fake.completeNextRead();
        }

        assertTrue(fake.pendingWriteBuffers.size() <= IN_FLIGHT,
                "outstanding writes must stay bounded at the in-flight depth, was "
                        + fake.pendingWriteBuffers.size());
        assertEquals(IN_FLIGHT, fake.pendingReadBuffers.size(), "reads held at the in-flight depth");
    }

    @Test
    void writesSkippedAtTheCap_areRepaidWhenASlotFrees() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        int primedWrites = fake.submittedWrites.size();

        // The 2026-07-17 bench race: reads and writes complete at the SAME average
        // rate, but bursty — each cycle two reads land while both write slots are
        // still draining, THEN the two writes complete.  Skipping the paced write at
        // the cap without repaying it loses half the writes here (the periodic
        // underrun "meander" seen on the ZoomedView, tx≫rx in the mock counters);
        // debt-repaying pacing must stay exactly 1:1 long-run and still bounded.
        int cycles = 100;
        for (int i = 0; i < cycles; i++) {
            fake.completeNextRead();
            fake.completeNextRead();
            fake.completeNextWrite();
            fake.completeNextWrite();
        }

        assertEquals(primedWrites + 2 * cycles, fake.submittedWrites.size(),
                "every read completion's paced write is eventually submitted — skipped ones repaid");
        assertTrue(fake.pendingWriteBuffers.size() <= IN_FLIGHT,
                "repayment never exceeds the in-flight bound, was " + fake.pendingWriteBuffers.size());
    }

    @Test
    void secondCaptureConsumer_isRefusedLoudly() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });

        assertThrows(IllegalStateException.class,
                () -> engine.attachCapture((buffer, length) -> { }),
                "a second capture consumer must be refused, not silently split");
        assertEquals(0, fake.cancelAllCount, "the refusal must not tear the running stream down");
    }

    @Test
    void captureReattaches_afterDetach() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        engine.detachCapture();
        // A fresh acquire after a clean detach is a normal restart, not a split.
        engine.attachCapture((buffer, length) -> { });

        assertEquals(1, fake.cancelAllCount, "exactly one teardown from the single detach");
    }

    @Test
    void sampleRateChangeWhileRunning_restartsSession() {
        FakeTransport fake = new FakeTransport();
        Qa40xDuplexEngine engine = engine(fake, millis -> { });

        engine.attachCapture((buffer, length) -> { });
        engine.changeSampleRate(96_000);

        assertEquals(1, fake.cancelAllCount, "rate change stops the running session");
        List<FakeTransport.RegWrite> writes = fake.registerWrites;
        List<FakeTransport.RegWrite> restart = writes.subList(writes.size() - 7, writes.size());
        assertEquals(List.of(
                new FakeTransport.RegWrite(8, 0),
                new FakeTransport.RegWrite(5, 0),
                new FakeTransport.RegWrite(6, 3),
                new FakeTransport.RegWrite(9, 1),   // 96 kHz → code 1
                new FakeTransport.RegWrite(11, 0),  // frame width first, cleared here
                new FakeTransport.RegWrite(10, 0),  // then I2S control, off here
                new FakeTransport.RegWrite(8, 5)),
                restart);
    }
}
