/*
 * Phonalyser - precision audio measurement workbench.
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

package org.edgo.audio.measure.net.server;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture plane of spec 4.4 and the binary frames of spec 5, driven without
 * a socket, without a device and without a thread: {@link StubCapture} says when
 * a batch arrives, {@link FakeWorker} says when it is drained, and
 * {@link FakeChannel} keeps what went out.
 *
 * <p>That is what makes the two properties the protocol actually rests on
 * assertable: the payload must reach the client byte for byte (the client
 * decodes it with the same code a local device feeds), and the packet numbers
 * must have no holes (spec 5: "a {@code packetCounter} jump means transport-layer
 * loss ... the client treats it as a protocol error"), GAP frames included.
 */
class CaptureStreamerTest {

    private static final int RATE_HZ = StubDeviceManager.RATE_48K;
    private static final int BITS = StubDeviceManager.BITS_24;
    /** 24-bit stereo: three bytes a sample, two samples a frame. */
    private static final int FRAME_BYTES = 6;
    private static final int FRAMES_PER_BATCH = 4;
    private static final int BATCH_BYTES = FRAME_BYTES * FRAMES_PER_BATCH;
    private static final int FIRST = 0;
    private static final int SECOND = 1;
    private static final int OVERFLOW_BATCHES = 3;
    /** Batches queued behind a held drain, so ONE pass has several to send. */
    private static final int PASS_BATCHES = 3;
    /** How long the fake transport stays behind - long enough that the drain has
     *  to ask more than once, short enough to cost the run nothing. */
    private static final int BACKLOGGED_ANSWERS = 2;
    /** The u16 the binary header carries the stream id in (spec 5). */
    private static final int MAX_STREAM_ID = 0xFFFF;
    /** A handle no open ever handed out - what an attach naming a capture this
     *  session does not have is refused for (spec 4.7). */
    private static final int UNKNOWN_CAPTURE_ID = 4_242;
    /** The card the names pin binds to the first input - the value the
     *  streaming lines' "(card ...)" slot must carry. */
    private static final String CARD_NAME = "Bench ADC";
    private static final double CARD_FS_LEFT = 1.0;
    private static final double CARD_FS_RIGHT = 1.5;

    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    /** This bench's device cards - empty unless a test binds one, so the
     *  card-in-force half of the names pin is the test's own doing. */
    private final StubCardStore cards = new StubCardStore();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), cards.getPrefs());
    /** The session's CONTROL connection (spec 4.7): what a lane's
     *  {@code ev.device.error} leaves on, and what must never carry a frame. */
    private final FakeChannel channel = new FakeChannel();
    /** The capture's DATA connection - every binary frame of spec 5 is asserted
     *  on THIS one, which is what proves the planes really are apart. */
    private final FakeChannel data = new FakeChannel();
    private final FakeWorker sender = new FakeWorker();
    private final Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
    private final CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(),
            catalog, codec, channel, sender, qa40x);

    private final DeviceLock firstInput = new DeviceLock(AudioBackendType.QA40X, 0, true);
    private final DeviceLock secondInput = new DeviceLock(AudioBackendType.QA40X, 1, true);
    private final DeviceLock output = new DeviceLock(AudioBackendType.QA40X, 0, false);

    @Test
    void openAnswersTheGrantedFormatAndAHandleForIt() {
        JsonNode data = captures.open(firstInput, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS);

        assertEquals(1, data.path(NetFields.CAPTURE_ID).asInt());
        assertEquals(RATE_HZ, data.path(NetFields.RATE).asInt(),
                "spec 4.4: the client re-pins to what the device granted");
        assertEquals(BITS, data.path(NetFields.BITS).asInt());
        assertEquals(2, data.path(NetFields.CHANNELS).asInt());
        assertEquals(FRAME_BYTES, data.path(NetFields.FRAME_BYTES).asInt());
        assertTrue(capture().isOpened());
        assertEquals(0, capture().getStartCount(),
                "capture.open only opens - the frames begin at capture.start");
    }

    /**
     * The replug wound, and the belt that answers it.
     *
     * <p>A snapshot backend keeps listing a device at the place it had before it
     * was unplugged, so every open on it fails with the driver's own internal
     * error although the device is sitting right there - on the macOS bench, for
     * the rest of the server's life.  An open that failed while the backend says
     * its list is stale therefore earns ONE rebuild and ONE more attempt.
     */
    @Test
    void anOpenThatFailedOnAStaleDeviceListIsRetriedOnceAfterOneRebuild() {
        manager().replugBehindAStaleList();
        int rebuilds = manager().getRefreshCount();

        JsonNode data = captures.open(firstInput, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS);

        assertEquals(RATE_HZ, data.path(NetFields.RATE).asInt(),
                "the second attempt opened the device the first could not reach");
        assertEquals(rebuilds + 1, manager().getRefreshCount(),
                "exactly one rebuild - it either made the list right or the device "
                        + "is genuinely not openable, and a loop would re-initialise "
                        + "a native library over and over on a device that is gone");
    }

    @Test
    void anOpenOnADeviceThatIsGoneIsRefusedRatherThanRetriedForEver() {
        manager().unplug(StubDeviceManager.SECOND_INPUT);
        int rebuilds = manager().getRefreshCount();

        NetException refused = assertThrows(NetException.class, () -> captures.open(
                secondInput, StubDeviceManager.SECOND_INPUT, RATE_HZ, BITS));

        assertEquals(ErrorCode.DEVICE_ERROR, refused.getCode());
        assertEquals(rebuilds + 1, manager().getRefreshCount(),
                "the list WAS stale, so it is rebuilt once - and the rebuild is "
                        + "what proves there is nothing to retry with: the device "
                        + "is not in the new list either");
    }

    @Test
    void theStreamCarriesTheNamesTheStreamingLinesPrint() {
        // Bound to the LONGER name on purpose: "QA403" is a substring of
        // "QA403 second unit", so a card on the first input would correlate
        // with both devices (the match rule working as designed) and leave no
        // uncarded device to pin the "none" branch with.
        cards.card(CARD_NAME, StubDeviceManager.SECOND_INPUT, true, CARD_FS_LEFT,
                CARD_FS_RIGHT, false);

        int withCard = captures.open(secondInput, StubDeviceManager.SECOND_INPUT,
                RATE_HZ, BITS).path(NetFields.CAPTURE_ID).asInt();
        int without = openFirstInput();

        assertEquals(StubDeviceManager.SECOND_INPUT,
                captures.stream(withCard).getDeviceName(),
                "the streaming lines print the device's human name - the lock only "
                        + "knows backend and index");
        assertEquals(CARD_NAME, captures.stream(withCard).getCardName(),
                "the card IN FORCE at open time is what 'started/stopped (card ...)' "
                        + "names");
        assertEquals(StubDeviceManager.FIRST_INPUT,
                captures.stream(without).getDeviceName());
        assertNull(captures.stream(without).getCardName(),
                "no card correlates -> the line renders 'none', never a null crash");
    }

    @Test
    void everyBatchIsOnePcmFrameCarryingTheBytesUntouchedAndTheCounterSteps() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        byte[] first = batch(1);

        capture.feed(first);
        // The backend recycles its buffer the moment the listener returns, which
        // is the contract a streamer that queued the reference would break.
        Arrays.fill(first, (byte) 0);
        capture.feed(batch(2));

        List<BinaryFrame> frames = data.getFrames();
        assertEquals(2, frames.size());
        assertEquals(FrameType.PCM, frames.get(FIRST).type());
        assertEquals(captureId, frames.get(FIRST).streamId(),
                "spec 5: the frame's streamId IS the captureId");
        assertEquals(BATCH_BYTES, frames.get(FIRST).n(), "PCM n is the payload byte count");
        assertArrayEquals(batch(1), frames.get(FIRST).payload(),
                "the client decodes the native PCM with the same code a local device feeds");
        assertArrayEquals(batch(2), frames.get(SECOND).payload());
        assertEquals(0, frames.get(FIRST).packetCounter(), "spec 5: per stream, starts at 0");
        assertEquals(1, frames.get(SECOND).packetCounter());
    }

    @Test
    void aSaturatedQueueDropsTheOldestBatchesAndConfessesThemAsOneGapFrame() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        sender.hold();

        // The allowance is a DURATION of audio, so how many batches fit depends on
        // the granted format - which is the whole point: 32 fixed batches were a
        // third of a second at 48 kHz and a sixth at 384 kHz.
        int capacity = (int) ((long) RATE_HZ * FRAME_BYTES * CaptureStream.QUEUED_AUDIO_MS
                / 1000 / BATCH_BYTES);
        int fed = capacity + OVERFLOW_BATCHES;
        for (int i = 0; i < fed; i++) {
            capture.feed(batch(i));
        }
        assertEquals(0, data.getFrames().size(), "nothing leaves while the drain is stalled");
        sender.release();

        List<BinaryFrame> frames = data.getFrames();
        assertEquals(capacity + 1, frames.size(),
                "what the queue held - one second of THIS format's audio - plus the "
                        + "one frame that admits the rest is gone");
        assertEquals(FrameType.GAP, frames.get(FIRST).type());
        assertEquals((long) OVERFLOW_BATCHES * FRAMES_PER_BATCH, frames.get(FIRST).n(),
                "spec 5: GAP n is LOST STEREO FRAMES, not bytes and not batches");
        assertArrayEquals(batch(OVERFLOW_BATCHES), frames.get(SECOND).payload(),
                "the oldest survivor follows the confession, so the gap marks "
                        + "exactly where the data was lost");
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).packetCounter(),
                    "spec 5: +1 per frame of ANY type - a hole would read as transport loss");
        }
    }

    @Test
    void aPassWhosePeerWasAlreadyBehindHoldsItsBatchesBack() {
        int captureId = openFirstInput();
        captures.start(captureId);
        data.backlogFor(BACKLOGGED_ANSWERS);

        capture().feed(batch(1));

        assertEquals(1, data.getFrames().size(), "the batch is held back, not lost");
        assertTrue(data.getBacklogQuestions() > BACKLOGGED_ANSWERS,
                "send() neither blocks nor refuses and the library's outgoing queue "
                        + "is unbounded, so ASKING the transport is the only "
                        + "backpressure this lane has - without it a client that "
                        + "stopped reading grows the server's heap without limit and "
                        + "the drop-oldest below is never reached at all");
    }

    /**
     * And the other half of that: the question is asked ONCE per drain pass,
     * before the pass writes anything.
     *
     * <p>Asked per batch it is a question about this drain's OWN last write - the
     * transport says "something is still buffered" because we just buffered it -
     * and the drain then waits on itself.  At 384 kHz, where the analyzer hands
     * over a chunk every 5.3 ms, that cost the bench a sweep in five: the drain
     * fell to a handful of batches a second on a link that was keeping up, the
     * queue overflowed, and the client was told it was not keeping up.
     */
    @Test
    void theTransportIsAskedOncePerPassAndNeverAboutTheDrainsOwnWrites() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        sender.hold();
        for (int i = 0; i < PASS_BATCHES; i++) {
            capture.feed(batch(i));
        }

        sender.release();

        assertEquals(PASS_BATCHES, data.getFrames().size(), "everything queued went out");
        assertEquals(1, data.getBacklogQuestions(),
                "one question for the whole pass: a drain that asked per batch would "
                        + "be answering for the write it had just made");
    }

    @Test
    void aStreamThatFailedGivesItsDeviceLineBackWithTheConfession() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        data.failFrames();

        capture.feed(batch(1));

        assertTrue(capture.isClosed(),
                "the honest-loss rule is ev.device.error AND a close: a stream left "
                        + "registered would hold the line open with nothing reading "
                        + "it and refuse the client's next capture.open on it");
        assertThrows(NetException.class, () -> captures.stop(captureId),
                "and the handle is spent");
    }

    @Test
    void startingACaptureThatHasNoDataConnectionIsRefusedNotAttached() {
        int captureId = openOnly();

        NetException refused = assertThrows(NetException.class,
                () -> captures.start(captureId));

        assertEquals(ErrorCode.NOT_ATTACHED, refused.getCode(),
                "spec 4.4: the frames of a started stream would have nowhere to go, "
                        + "and a measurement the client waits for and never gets is "
                        + "worse than a refusal it can act on");
        assertEquals(0, capture().getStartCount(),
                "and the device was never started either");
    }

    @Test
    void aCaptureTakesOneDataConnectionAndOnlyOne() {
        int captureId = openOnly();
        captures.attach(captureId, data);

        NetException refused = assertThrows(NetException.class,
                () -> captures.attach(captureId, new FakeChannel()));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "spec 4.7: a second connection on one capture would send this "
                        + "stream's frames to a socket its client is not reading");
        captures.start(captureId);
        capture().feed(batch(1));
        assertEquals(1, data.getFrames().size(), "and the first one still has the audio");
    }

    @Test
    void attachingToACaptureThisSessionDoesNotHaveIsRefused() {
        NetException refused = assertThrows(NetException.class,
                () -> captures.attach(UNKNOWN_CAPTURE_ID, data));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "spec 4.7: the server answers the refusal and closes that socket");
    }

    @Test
    void theAudioIsOnTheDataConnectionAndTheDeviceErrorOnTheControlOne() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();

        capture.feed(batch(1));

        assertEquals(1, data.getFrames().size(), "spec 4.7: the frames are the data lane's");
        assertEquals(0, channel.getFrames().size(),
                "and a binary frame on the control connection is a protocol error - "
                        + "the whole point of the split is that a ping answer never "
                        + "queues behind a megabyte of PCM");

        data.failFrames();
        capture.feed(batch(2));

        assertEquals(1, channel.events(MessageType.EV_DEVICE_ERROR).size(),
                "while the one CONTROL message a capture can produce still goes out "
                        + "where the control plane is");
        assertEquals(0, data.events(MessageType.EV_DEVICE_ERROR).size());
    }

    @Test
    void closingACaptureClosesItsDataConnection() {
        int captureId = openFirstInput();
        captures.start(captureId);

        captures.close(captureId);

        assertEquals(1, data.getCloseCount(),
                "spec 4.7: the socket exists to carry THIS stream, so the stream "
                        + "ending is what ends it");
        assertEquals(0, channel.getCloseCount(),
                "and the control connection outlives every capture on it");
    }

    @Test
    void theTeardownClosesTheDataConnectionsToo() {
        int captureId = openFirstInput();
        captures.start(captureId);

        captures.closeAll();

        assertEquals(1, data.getCloseCount(),
                "spec 4.1: the session's teardown takes its data connections with it, "
                        + "marked as this end's so the client reads the ordinary end of "
                        + "a stream and not a second death report");
    }

    @Test
    void aSecondCaptureOnTheSameDeviceIsRefused() {
        openFirstInput();

        NetException refused = assertThrows(NetException.class, () -> captures.open(
                firstInput, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "spec 4.4: one open capture per device");
    }

    @Test
    void aCaptureOnAnOutputRefIsRefused() {
        NetException refused = assertThrows(NetException.class, () -> captures.open(
                output, StubDeviceManager.OUTPUT, RATE_HZ, BITS));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    @Test
    void theQa40xClockIsTheServersNotOneConnections() {
        openFirstInput();
        CaptureStreamer otherConnection = new CaptureStreamer(AudioBackend.instance(),
                catalog, codec, new FakeChannel(), new FakeWorker(), qa40x);

        NetException refused = assertThrows(NetException.class, () -> otherConnection.open(
                secondInput, StubDeviceManager.SECOND_INPUT, StubDeviceManager.RATE_96K, BITS));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "the two attached units are two locks, so two clients can hold one "
                        + "each - and one register-9 divider clocks both, so a guard "
                        + "that saw only its own connection would let the second "
                        + "client re-clock the first one's running measurement");
    }

    @Test
    void theTeardownGivesEveryLineBackBeforeItReturnsEvenWithTheAudioLaneStalled() {
        int captureId = openFirstInput();
        captures.start(captureId);
        sender.hold();

        captures.closeAll();

        assertTrue(capture().isClosed(),
                "the session's teardown closes the generator and parks the analyzer "
                        + "the moment this returns (spec 4.1's order), so an input line "
                        + "left for another thread to close would be closed on hardware "
                        + "the park had already released");
        assertTrue(sender.isShutdown());
    }

    @Test
    void aFailureOnTheAudioLaneIsConfessedOnceAsADeviceError() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        data.failFrames();

        capture.feed(batch(1));
        capture.feed(batch(2));

        List<NetMessage> errors = channel.events(MessageType.EV_DEVICE_ERROR);
        assertEquals(1, errors.size(),
                "spec 4.3: the event is a state the client acts on once - it stops "
                        + "the affected modules - not one message per failed batch");
        assertEquals(NetFields.INPUT, errors.get(FIRST).optString(NetFields.DIRECTION));
        assertTrue(errors.get(FIRST).optString(NetFields.DETAIL).contains(
                String.valueOf(firstInput)),
                "and it says which device failed - a stream that stops producing "
                        + "queues nothing, so there is no GAP to confess instead");
    }

    @Test
    void aCaptureIdNeverOutgrowsTheStreamIdTheFramesCarry() {
        int last = 0;
        for (int i = 0; i < MAX_STREAM_ID; i++) {
            last = openOnly();
            captures.close(last);
        }
        assertEquals(MAX_STREAM_ID, last, "the last id of the range is still handed out");

        assertEquals(1, openOnly(),
                "and then it wraps: an id past 65 535 would be answered on the "
                        + "control channel but stamped as 0 in every frame, and the "
                        + "client could no longer route them");
    }

    @Test
    void theQa40xRefusesASecondStreamAtAnotherRateAndAcceptsOneAtItsOwn() {
        openFirstInput();

        NetException refused = assertThrows(NetException.class, () -> captures.open(
                secondInput, StubDeviceManager.SECOND_INPUT, StubDeviceManager.RATE_96K, BITS));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "spec 4.4: one register-9 clock, so a mismatched rate would "
                        + "silently re-clock the stream already running");
        assertTrue(refused.getMessage().contains(String.valueOf(StubDeviceManager.RATE_96K)));
        assertEquals(2, captures.open(secondInput, StubDeviceManager.SECOND_INPUT,
                RATE_HZ, BITS).path(NetFields.CAPTURE_ID).asInt(),
                "the same rate is fine - that is the whole rule");
    }

    @Test
    void aDeviceNameThatMovedIsRefusedBeforeAnythingIsOpened() {
        NetException refused = assertThrows(NetException.class, () -> captures.open(
                firstInput, "Some other card", RATE_HZ, BITS));

        assertEquals(ErrorCode.DEVICE_STALE, refused.getCode());
        assertEquals(0, data.getFrames().size());
    }

    @Test
    void openWithoutARateOrADepthIsABadRequest() {
        NetException refused = assertThrows(NetException.class, () -> captures.open(
                firstInput, StubDeviceManager.FIRST_INPUT, null, BITS));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    @Test
    void aPauseKeepsTheCountersAndTheCloseGivesTheLineBack() {
        int captureId = openFirstInput();
        captures.start(captureId);
        StubCapture capture = capture();
        capture.feed(batch(1));

        captures.stop(captureId);
        assertEquals(1, capture.getStopCount());
        captures.start(captureId);
        capture.feed(batch(2));

        assertEquals(1, data.getFrames().get(SECOND).packetCounter(),
                "spec 4.4: a stopped stream keeps its counters");
        captures.close(captureId);
        assertTrue(capture.isClosed());
        assertThrows(NetException.class, () -> captures.start(captureId),
                "the handle is spent");
    }

    @Test
    void theTeardownStopsEveryStreamAndEndsTheAudioThread() {
        int captureId = openFirstInput();
        captures.start(captureId);

        captures.closeAll();

        assertTrue(capture().isClosed(), "spec 4.1: the device line goes back");
        assertTrue(sender.isShutdown());
    }

    @Test
    void releasingTheDeviceClosesTheStreamOpenOnIt() {
        int captureId = openFirstInput();
        captures.start(captureId);

        captures.closeDevice(firstInput);

        assertTrue(capture().isClosed(), "spec 4.3: device.release also closes the stream");
        assertThrows(NetException.class, () -> captures.stop(captureId));
    }

    /** Spec 4.7's sequence, as every streaming test needs it: open, then attach
     *  the capture's data connection - {@code capture.start} is refused until
     *  that has happened, so an "open" that stopped short of it could not
     *  stream at all. */
    private int openFirstInput() {
        int captureId = openOnly();
        captures.attach(captureId, data);
        return captureId;
    }

    /** {@code capture.open} alone - the half-open state spec 4.7 leaves a capture
     *  in until its data connection attaches, and all a test that only wants a
     *  HANDLE needs. */
    private int openOnly() {
        return captures.open(firstInput, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS)
                .path(NetFields.CAPTURE_ID).asInt();
    }

    /** The capture the stub bench most recently handed out. */
    private StubCapture capture() {
        return manager().getLastCapture();
    }

    /** The stub bench itself - the process-wide manager the real
     *  {@code AudioBackend} hands out for this backend. */
    private StubDeviceManager manager() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }

    /** The bench is shared with every other test class, so whatever a test
     *  unplugged goes back on it here - and the list with it. */
    @AfterEach
    void replugTheBench() {
        manager().replugAll();
    }

    /** One batch of recognisable PCM: every batch differs from every other, so a
     *  reordered or dropped one is visible in the assertion, not just a count. */
    private byte[] batch(int seed) {
        byte[] pcm = new byte[BATCH_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (seed * BATCH_BYTES + i);
        }
        return pcm;
    }
}
