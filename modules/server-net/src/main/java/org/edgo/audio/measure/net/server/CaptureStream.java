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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One open capture, from {@code capture.open} to {@code capture.close} (spec
 * 4.4): the device's PCM batches on the way out as the binary frames of spec 5,
 * numbered by this stream's own {@code packetCounter}.
 *
 * <p><b>It writes to ONE connection, and it is not the session's.</b>  Spec 4.7
 * gives every open capture a data connection of its own, dialled by the client
 * after {@code capture.open} answered the handle and bound here by
 * {@link #attach(SessionChannel)}.  Until that happens this stream has nowhere
 * to write, which is why {@code capture.start} is refused {@code NOT_ATTACHED}
 * before it (spec 4.4) - and why the closing of that connection is this
 * stream's own {@link #close()}, the orderly end spec 4.7 tells apart from a
 * drop.  Nothing of the control plane goes out from here: a lane that dies
 * hands its fault to the streamer, which owns the control connection and
 * publishes the {@code ev.device.error}.
 *
 * <p><b>Why a queue sits in the middle.</b>  The batch listener runs on the
 * capture thread, and that thread must never wait: a device whose consumer
 * stalls overruns, and an overrun is lost measurement data.  So the listener
 * copies the batch, drops it in a BOUNDED queue and returns; a thread of the
 * connection's own drains it onto the socket.
 *
 * <p><b>The bound only bounds anything if the socket can say no.</b>  It cannot:
 * {@code send} neither blocks nor refuses, and the WebSocket library's own
 * outgoing queue is unbounded, so a drain that emptied this queue on every batch
 * would move a slow client's backlog into the library and grow the heap exactly
 * as if there were no bound at all.  So the drain asks the transport instead:
 * when {@link SessionChannel#isSendBacklogged()} says the peer was ALREADY
 * behind when this pass began, each batch waits up to
 * {@link #MAX_BACKPRESSURE_WAIT_MS} for that to clear.  A client that stopped
 * reading is throttled to a handful of batches a second while the device
 * delivers ten or a hundred - so the backlog piles up HERE, against the bound,
 * which is what makes the drop-oldest below reachable and the loss confessable.
 * (The wait is bounded rather than open-ended because a peer that is not reading
 * is not answering pings either: spec 4.1 has the session declared dead within
 * 2 s, and a drain parked for ever would outlive it.)
 *
 * <p><b>Asked once per pass, and before the pass writes.</b>  The transport's
 * answer is "is something still buffered", and a write this very drain queued a
 * moment ago is still buffered - so asking per batch made the drain wait on
 * ITSELF.  At 384 kHz, where the device hands over a chunk every 5.3 ms, that
 * turned a link that was keeping up perfectly into five batches a second and
 * overflowed the queue in a fifth of a second.  Sampled ahead of this pass's own
 * writes, the question means what it was meant to mean.
 *
 * <p>The core's {@code SpscByteArrayRing} is the wrong tool for this queue
 * despite carrying the same payload: it is strictly one producer and one
 * consumer, and drop-oldest means the PRODUCER takes from the head - a second
 * reader racing the drain on cursors that tolerate none.  A lock-guarded
 * {@link LinkedBlockingQueue} is what makes both ends safe here, and the cost
 * lands on a path that already copies a whole capture batch.
 *
 * <p><b>What a full queue costs, and why it is told.</b>  When the bound is
 * reached the OLDEST batch is dropped - the newest audio is the useful audio -
 * and the stereo frames it carried are counted.  Before the next PCM frame goes
 * out, that count leaves as a GAP frame (spec 5): "the server itself dropped
 * capture data ... an explicit confession - the client resets averaging instead of
 * silently splicing".  The order is what makes it honest: everything still
 * queued came AFTER what was dropped, so a GAP emitted before the next PCM frame
 * marks the loss exactly where it happened.  GAP frames carry a packet number of
 * their own, as spec 5 requires of every frame type - a counter JUMP means
 * transport loss and is a protocol error, which is why the server never leaves
 * one behind.
 *
 * <p><b>Threading.</b>  {@link #onPcm} runs on the capture thread and only ever
 * appends; the drain runs on the injected {@link SessionWorker}, which is
 * single-threaded, so {@link #packetCounter} has exactly one writer and needs no
 * synchronisation.  The audio lane deliberately does NOT share the session's
 * request worker: a {@code capture.open} that waits on hardware for a second
 * would otherwise stall the audio of every other stream on the connection.
 */
@Log4j2
public final class CaptureStream implements AudioCapture.PcmBatchListener {

    /**
     * How much AUDIO may wait for a slow client - a duration, not a batch count.
     *
     * <p>It used to be 32 batches, on the reasoning that spec 5's "~10 - 100 ms"
     * batches make that "a third of a second to a couple of seconds of slack".
     * That reasoning holds only at ordinary rates: a QA40x at 384 kHz hands over
     * its 2048-frame USB chunk every 5.3 ms, so 32 of them are 0.17 s - five
     * times less slack than the design intended, and shrinking as the rate rises.
     * A client-side pause of a couple of hundred milliseconds (a full GC over the
     * tens of megabytes a frequency-response record allocates) then overflowed a
     * queue that was supposed to ride it out, and the bench lost a sweep to a
     * confession it should never have had to make - every fifth to ninth sweep
     * refused at 384 kHz.
     *
     * <p>Derived from the GRANTED format, so the slack is the same second at
     * every rate and width the device can be opened at.  A whole second rather
     * than the half the fix started at, because the pause it has to ride out is a
     * full GC over the client's frequency-response record - tens of megabytes of
     * {@code double[]} at a 2M FFT, which is exactly the size the bench found the
     * failure tracks (a 128k or 256k FFT at the same rate never failed).  It
     * costs 2.3 MB of server heap per stream at 384 kHz / 24-bit, and it is
     * bounded above by spec 4.1 anyway: a peer that is not reading for a second
     * is not answering pings either, and the session dies at two.
     */
    static final int QUEUED_AUDIO_MS = 1_000;
    /** The floor under that duration, in batches: a device whose batch is longer
     *  than the whole allowance must still be able to queue something, or every
     *  batch would be dropped on arrival. */
    static final int MIN_QUEUED_BATCHES = 8;

    /** How long a native line stop/close may run before it is abandoned -
     *  a close on a dead line can block for minutes (csjsound holds the line
     *  monitor across the blocked native write), and close() runs on the
     *  session's ONE command thread. */
    private static final long CLOSE_JOIN_MS = 2_000;

    /** How long one batch may wait for the transport to write out what it
     *  already holds - about two capture batches at the slow end of spec 5's
     *  "~10 - 100 ms", so a healthy socket is never held up and a stalled one
     *  is throttled well below the rate the device delivers at. */
    private static final int MAX_BACKPRESSURE_WAIT_MS = 200;
    /** How often the transport is asked while a batch waits. */
    private static final int BACKPRESSURE_POLL_MS = 5;

    /** The stream's handle: {@code captureId} in the control channel,
     *  {@code streamId} in every binary frame (spec 4.4, 5). */
    @Getter
    private final int captureId;
    /** The device and direction this stream reads, which is also the lock that
     *  had to be held to open it. */
    @Getter
    private final DeviceLock device;
    /** The device's human name and the card in force for it at open time -
     *  what the operator-facing streaming lines print (the lock above only
     *  knows backend and index). */
    @Getter
    private final String deviceName;
    @Getter
    private final String cardName;
    /** The format actually granted - the device's reality, which spec 4.4 lets
     *  differ from what was asked for.  Read here for the frame size a dropped
     *  batch is counted in. */
    private final AudioFormat format;

    private final AudioCapture capture;
    /** The backend this line was opened through - the one type that can say
     *  what a fault on it MEANT, asked when the lane dies.  Read by the streamer
     *  that publishes the failure, which is where the control connection is. */
    @Getter
    private final AudioDeviceManager backend;
    private final SessionWorker sender;
    /** Told once, when this stream has failed.  The honest-loss rule wants an
     *  {@code ev.device.error} AND a close, and neither is this stream's to do:
     *  the event goes out on the CONTROL connection (spec 4.7 - nothing of the
     *  control plane leaves here), and only the streamer that registered this
     *  stream can take it out of its map and give the device's rate claim back.
     *  So it is handed the fault and asked, not reached into. */
    private final BiConsumer<CaptureStream, Throwable> onFailure;

    /** The batches waiting for the socket.  Unbounded as a COLLECTION and bounded
     *  in BYTES by {@link #maxQueuedBytes}: the bound is a duration of audio, and
     *  a device's batch size is not known until its first batch arrives, so the
     *  count a fixed-capacity queue would need cannot be computed at all. */
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    /** How many of {@link #queue}'s bytes are outstanding - the bound's own
     *  measure.  Atomic: the capture thread adds and the drain subtracts. */
    private final AtomicLong queuedBytes = new AtomicLong();
    /** {@link #QUEUED_AUDIO_MS} of the granted format, in bytes. */
    private final long maxQueuedBytes;
    /** Stereo frames dropped and not yet confessed.  Written by the capture
     *  thread, cleared by the drain - hence atomic. */
    private final AtomicLong lostFrames = new AtomicLong();
    /** Whether this stream has already confessed a failure.  Spec 4.3's
     *  {@code ev.device.error} is a STATE the client acts on once - it stops the
     *  affected modules - not a log line, so a device that fails on every batch
     *  must not send one per batch.  Either lane may be the first to see it,
     *  hence atomic. */
    private final AtomicBoolean deviceErrorSent = new AtomicBoolean();
    /** This capture's data connection (spec 4.7), or null while none has
     *  attached - which is every moment between {@code capture.open} and the
     *  {@code capture.attach} that binds one.  Written once by the transport
     *  thread that answers the attach, read by the capture thread and the drain,
     *  hence volatile.  Every write site reads it into a local first: a stream
     *  whose connection went away mid-drain must drop the batch, not fault. */
    private volatile SessionChannel data;
    /** Spec 5: per stream, starts at 0, +1 per frame of ANY type.  Drain thread
     *  only. */
    private long packetCounter;

    public CaptureStream(int captureId, DeviceLock device, String deviceName,
            String cardName, AudioCapture capture, AudioDeviceManager backend,
            SessionWorker sender, BiConsumer<CaptureStream, Throwable> onFailure) {
        this.captureId = captureId;
        this.device = device;
        this.deviceName = deviceName;
        this.cardName = cardName;
        this.capture = capture;
        this.backend = backend;
        this.sender = sender;
        this.onFailure = onFailure;
        this.format = capture.getFormat();
        this.maxQueuedBytes = (long) Math.ceil(format.getFrameRate() * format.getFrameSize()
                * QUEUED_AUDIO_MS / 1000.0);
        // ITSELF, not a method reference: the same seam that carries the batches
        // carries the stream's END (captureEnded below), and a lambda or method
        // reference would register only the default no-op for it - the backend's
        // death report would land nowhere, the server log would show the loss,
        // and the client would never hear it: a WASAPI card going away
        // mid-capture left the client with no notice that it was gone.
        capture.setPcmBatchListener(this);
    }

    /**
     * Spec 4.7: this capture's data connection has attached, so the stream now
     * has somewhere to write.  Called once, from the transport thread that
     * answers the {@code capture.attach}.
     */
    public void attach(SessionChannel connection) {
        this.data = connection;
    }

    /** Whether a data connection has attached (spec 4.7) - what makes
     *  {@code capture.start} answerable rather than {@code NOT_ATTACHED}. */
    public boolean isAttached() {
        return data != null;
    }

    /** Spec 4.4: the binary frames begin.  The listener is registered from the
     *  open, but the device produces nothing until now. */
    public void start() {
        capture.startRecording();
    }

    /** Spec 4.4: "stream pauses; counters keep their values" - so nothing here
     *  touches {@link #packetCounter}. */
    public void stop() {
        try {
            capture.stopRecording();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Spec 4.5: a sweep start "injects a {@code sweepStart} marker (spec 5) into
     * this connection's open capture streams", and spec 5 promises that "the
     * first PCM byte AFTER this frame is aligned with sweep output sample 0".
     *
     * <p>Which is why it goes through the drain lane rather than straight onto
     * the socket: everything already queued was captured BEFORE the sweep
     * started, so a marker that overtook it would place sample 0 earlier than it
     * happened and the analyzer would search the wrong window.  Queueing it also
     * keeps {@link #packetCounter} single-writer - spec 5 counts every frame
     * type, and a second writer could produce the duplicate the client reads as
     * transport loss.
     */
    public void markSweepStart() {
        sender.submit(() -> {
            SessionChannel out = data;
            if (out == null) {
                // No data connection has attached (spec 4.7), so this capture
                // is not streaming at all - a marker into a stream nobody can
                // receive would only spend a packet number.
                return;
            }
            // Everything still queued was captured before the sweep, so it goes
            // out first even if the transport is behind - the queue is bounded,
            // so that flush is bounded too.
            drain(true);
            out.send(new BinaryFrame(FrameType.MARKER, captureId,
                    packetCounter++, BinaryFrame.MARKER_SWEEP_START));
        });
    }

    /**
     * Stops the device and gives the line back.  Idempotent enough for the
     * teardown paths, which call it blindly from any thread.
     *
     * <p>BOUNDED: the native stop/close of a dead line can block for minutes
     * (see {@code CLOSE_JOIN_MS}), and this runs on the session's one command
     * thread - an overrun is logged and abandoned instead of wedging the
     * connection (the teardown callers guard every step anyway, so nothing
     * depended on the fault beyond its log line).
     *
     * <p>The queue is emptied in a finally: a stream left holding a second of
     * captured audio per lane is heap this connection can no longer reach.
     *
     * <p>And the capture's data connection goes with it (spec 4.7): the socket
     * exists to carry THIS stream, so the stream ending is what ends it.  The
     * close is marked as this end's, which is what tells the far side's drop
     * detector - and our own - that this was the orderly end of a stream and
     * not the session dying (spec 4.1).  Last, so a drain that was already
     * queued has still had its socket.
     */
    public void close() {
        try {
            Closeables.tryBounded("net capture " + captureId + ": line close",
                    CLOSE_JOIN_MS, () -> {
                        stop();
                        capture.close();
                    });
        } finally {
            queue.clear();
            queuedBytes.set(0);
            SessionChannel out = data;
            data = null;
            if (out != null) {
                out.close("capture " + captureId + " closed");
            }
            if (log.isInfoEnabled()) {
                log.info("net capture {}: closed on {} after {} frame(s)",
                        captureId, device, packetCounter);
            }
        }
    }

    /**
     * One capture batch, on the capture thread.  The array is the backend's and
     * may be recycled the moment this returns, so the batch is copied before it
     * is queued - and only {@code validBytes} of it are ours.
     */
    @Override
    public void accept(byte[] pcm, int validBytes) {
        try {
            byte[] batch = Arrays.copyOf(pcm, validBytes);
            queue.add(batch);
            queuedBytes.addAndGet(validBytes);
            // Over the allowance: the OLDEST batch goes, and keeps going until
            // the queue is back inside it - the newest audio is the useful audio.
            // The floor is what stops a device whose single batch is longer than
            // the whole allowance from having every batch dropped on arrival.
            while (queuedBytes.get() > maxQueuedBytes && queue.size() > MIN_QUEUED_BATCHES) {
                byte[] dropped = take();
                if (dropped == null) {
                    break;
                }
                lostFrames.addAndGet((long) dropped.length / format.getFrameSize());
            }
            sender.submit(this::drainAtTheSocketsPace);
        } catch (Throwable t) {
            // The shared capture base swallows a listener fault to keep the
            // device thread alive (which is right), and logs it once - so
            // without this the client would simply stop receiving audio with
            // nothing on the wire to say why.  Throwable, because this listener
            // runs on the DEVICE's own thread: catching it here is what turns a
            // fault on that thread into the client's ev.device.error instead of
            // one line in the base's log that nobody on the wire ever sees.
            reportDeviceError(t);
        }
    }

    /** What every arriving batch schedules: hand the socket what it can take,
     *  at the rate it can take it. */
    private void drainAtTheSocketsPace() {
        drain(false);
    }

    /**
     * Empties the queue onto the socket, confessing any loss first (see the
     * class comment on why that order is the honest one).
     *
     * <p>Each batch waits for the transport first, unless {@code flush} says the
     * caller needs the queue emptied whatever the socket is doing - the sweep
     * marker, which may not overtake audio captured before it, and which is
     * bounded by the queue itself.
     */
    private void drain(boolean flush) {
        try {
            SessionChannel out = data;
            if (out == null) {
                // The capture's data connection is gone (closed, or never
                // attached): there is nowhere to write, and holding the batches
                // would only grow the heap of a stream that is over.
                queue.clear();
                queuedBytes.set(0);
                return;
            }
            // Asked ONCE, and BEFORE this pass writes anything.  The transport's
            // answer is "is something still buffered", which is true of a write
            // this very drain queued a moment ago - so asking it per batch made
            // the drain wait on ITSELF: at 384 kHz that turned a healthy 187
            // batches a second into five, and the queue overflowed on a link that
            // was keeping up perfectly.  Sampled ahead of our own writes it means
            // what it is supposed to mean: the PEER was already behind when this
            // pass began.
            boolean asked = false;
            boolean peerWasBehind = false;
            for (byte[] batch = take(); batch != null; batch = take()) {
                if (!flush && !asked) {
                    peerWasBehind = out.isSendBacklogged();
                    asked = true;
                }
                if (peerWasBehind) {
                    // A backlog that CLEARS is a peer that is reading again - a
                    // hiccup, not an overload - so the rest of this pass goes out
                    // flat out.  Waiting on through the recovery is what turned a
                    // 200 ms client pause into a queue overflow: the drain crawled
                    // at one poll per batch exactly while it had a backlog of its
                    // own to work off.  A peer that never clears keeps the
                    // throttle, which is what fills the allowance and makes the
                    // loss confessable.
                    peerWasBehind = !awaitTransport(out);
                }
                long lost = lostFrames.getAndSet(0);
                if (lost > 0) {
                    if (log.isWarnEnabled()) {
                        log.warn("net capture {}: {} stereo frame(s) dropped - the "
                                + "client is not keeping up", captureId, lost);
                    }
                    out.send(new BinaryFrame(FrameType.GAP, captureId, packetCounter++, lost));
                }
                out.send(new BinaryFrame(FrameType.PCM, captureId, packetCounter++, batch));
            }
        } catch (Throwable t) {
            // A drain that dies takes nothing with it but this task: the audio
            // worker would run the next one as if nothing had happened.
            reportDeviceError(t);
        }
    }

    /** Takes the oldest batch and keeps {@link #queuedBytes} true - the ONE place
     *  the outstanding count is decremented, so the producer's drop and the
     *  drain's send cannot disagree about how much is waiting.  Null when the
     *  queue is empty. */
    private byte[] take() {
        byte[] batch = queue.poll();
        if (batch != null) {
            queuedBytes.addAndGet(-batch.length);
        }
        return batch;
    }

    /**
     * Waits for the transport to write out what it already holds - the ONLY
     * backpressure this lane has, because {@link SessionChannel#send} neither
     * blocks nor refuses (see the class comment).
     *
     * <p>Costs nothing at all while the client keeps up: the first question
     * answers "nothing buffered" and the batch goes straight out.
     *
     * @param out this capture's data connection, read once by the caller so a
     *        close racing the drain cannot turn a wait into a fault
     * @return true when the backlog CLEARED - the peer is reading again, so the
     *         caller stops holding batches back; false when it was still there
     *         after {@link #MAX_BACKPRESSURE_WAIT_MS}, which is the peer this
     *         lane must go on throttling until the allowance overflows and the
     *         loss can be confessed
     */
    private boolean awaitTransport(SessionChannel out) {
        for (int waited = 0; waited < MAX_BACKPRESSURE_WAIT_MS; waited += BACKPRESSURE_POLL_MS) {
            if (!out.isSendBacklogged()) {
                return true;
            }
            try {
                Thread.sleep(BACKPRESSURE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !out.isSendBacklogged();
    }

    /**
     * Spec 4.3's {@code ev.device.error}: "server-side capture/playback failure
     * ... the client surfaces it exactly like a local device error (message + stop
     * the affected modules)".  It is the only way a failure AFTER
     * {@code capture.start} can reach the client - the request that started the
     * stream was answered long ago, and a stream that stopped producing queues
     * nothing, so there is no GAP to confess either.
     *
     * <p>And the stream ENDS with it - the honest-loss rule is
     * {@code ev.device.error} AND a close.  A stream left registered would keep
     * the device line open with nothing reading it, hold the QA40x rate claim,
     * and refuse the client's next {@code capture.open} on that device as "a
     * capture is already open".
     *
     * <p>Neither half happens here.  The event is a CONTROL-plane message and
     * this stream owns a data connection only (spec 4.7), and the close has to
     * take this stream out of the streamer's map and give the device's rate
     * claim back - so the fault is handed to the streamer, which owns both.
     * The once-guard stays here, where the failure is seen: a device that fails
     * on every batch must produce one report, not one per batch, and either
     * lane may be the one to notice.
     */
    private void reportDeviceError(Throwable fault) {
        if (!deviceErrorSent.compareAndSet(false, true)) {
            return;
        }
        if (log.isErrorEnabled()) {
            log.error("net capture {}: {} failed - telling the client", captureId, device, fault);
        }
        onFailure.accept(this, fault);
    }

    /** The backend's own end-of-stream, on the seam the batches travelled -
     *  spec 4.3's honest-loss rule ({@code ev.device.error} AND a close).  The
     *  wire detail carries the enum's log text (the server has no i18n; the
     *  client's localization of remote errors comes with the net rework).
     *  Once-guarded like every other path into {@link #reportDeviceError}. */
    @Override
    public void captureEnded(CaptureEndReason reason) {
        reportDeviceError(new IllegalStateException(reason.logText()));
    }
}
