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

package org.edgo.audio.measure.net.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.edgo.audio.measure.sound.MarkedCapture;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * One capture stream on a remote bench: the {@code capture.open / start / stop /
 * close} vocabulary of spec 4.4 on the outside, and the ordinary local capture
 * contract on the inside.
 *
 * <p><b>Why it extends the shared base.</b>  Spec 4.4 puts the server's NATIVE
 * PCM bytes on the wire precisely so the client can decode them with the same
 * code path a local device feeds.  Handing the payload to
 * {@link #dispatch(byte[], int)} is that path: the listeners, the mono upmix and
 * the sample decoder are the ones every backend already uses, and the frames
 * arrive on the connection's reader thread, which plays the part a local
 * backend's consume thread plays.
 *
 * <p><b>The lock travels with the stream.</b>  Spec 4.4 requires the input lock,
 * so {@link #open()} acquires it and {@link #close()} gives it back: a device
 * this client is not capturing from is a device another client may have.  It is
 * the same discipline the local backends follow - a line is never held open
 * across captures.
 *
 * <p><b>Two kinds of loss, told apart.</b>  A GAP frame is the server's honest
 * confession that it dropped capture data (spec 5); it is forwarded to the
 * {@link NetFaultListener} the manager handed in, so the analyzers reset their
 * averaging instead of splicing across it.  A jump in the packet counter is
 * something else entirely: intact TCP cannot lose or reorder, so the stream is
 * broken - the error is surfaced and the stream stops, exactly as a device error
 * would.
 *
 * <p><b>Three ways it can end without being asked to.</b>  The bench's input lane
 * fails (spec 4.3's {@code ev.device.error}), the session dies (spec 4.1), or the
 * packet counter jumps.  All three land in {@link #halt()}: the frames stop being
 * routed here and the server is told to stop sending them.  Only the counter jump
 * is REPORTED from here - the other two are already somebody else's to tell, the
 * device error by the manager that hears the event and the dead session by
 * whoever owns it, each exactly once for the whole client.  Silence is what a
 * frozen scope looks like, so none of them may pass unreported; a failure
 * reported twice is a failure nobody trusts.
 */
@Log4j2
public final class NetPcmCapture extends AbstractPcmCapture implements MarkedCapture {

    /** {@link #captureId} before the open and after the close. */
    private static final int NOT_OPEN = -1;
    /** The pipeline is stereo downstream (spec 4.4 writes the channel count as
     *  the constant 2, and the server upmixes a mono device itself). */
    private static final int STEREO = 2;

    private final NetConnection connection;
    private final NetDeviceRef device;
    /** The manager that opened this stream: where a gap and a failure go - it is
     *  the one place the client's faults can be subscribed to (see
     *  {@link NetFaultListener}) - and where this stream's device lock is
     *  registered, because the whole client has to agree on which devices it
     *  holds (see {@link NetDeviceManager#withDeviceLock}).  Injected, never set
     *  later: a stream whose losses had nowhere to go would splice across them in
     *  silence, and a lock nobody knew about would be released underneath it. */
    private final NetDeviceManager owner;
    /** Held as fields, not as method references at the call site: the same
     *  objects have to be handed back to unsubscribe. */
    private final Consumer<NetMessage> events = this::onEvent;
    private final Consumer<NetCloseReason> sessionEnd = this::onSessionEnd;
    /** Guards the hand-off to the pipeline, so no batch can be in flight when
     *  {@link #stopRecording()} returns - every local backend joins its consume
     *  thread there, and a listener called after that would be a contract this
     *  backend alone breaks. */
    private final Object dispatchLock = new Object();
    /** Whether the stream has already ended by itself; a failure is acted on
     *  exactly once, and either the reader thread or the thread that closed the
     *  session may be the one to see it. */
    private final AtomicBoolean ended = new AtomicBoolean();

    /** The handle of spec 4.4, which spec 5 stamps into every frame as the
     *  {@code streamId} - this stream's identity on the wire, and therefore what
     *  correlates a frame, a log line or a device error back to it.
     *  {@link #NOT_OPEN} before the open and after the close.  Volatile: opened
     *  and closed on the caller's thread, read by the reader thread that routes
     *  the frames. */
    @Getter
    private volatile int captureId = NOT_OPEN;
    /** The format the server GRANTED (spec 4.4: "the granted rate may differ
     *  (device reality), client re-pins"); null until the open succeeded. */
    private volatile AudioFormat granted;
    /** True once the device lock is held, so the close gives back exactly what
     *  the open took. */
    private volatile boolean acquired;

    /** Who is assembling a bounded record out of this stream - the MARKER frames
     *  of spec 5 and the losses of a GAP - or null while nobody is, and then a
     *  marker is counted and dropped, which is what every stream but a
     *  sweep-bounded one wants.  ONE listener, not a list: the mark bounds a
     *  single measurement's record, and two assemblers on one stream would be two
     *  measurements sharing an ADC.  Volatile: registered on the measuring thread
     *  before the sweep starts, read by the reader thread. */
    @Setter
    private volatile MarkedCapture.Listener markerListener;

    /** PCM payload bytes handed to the pipeline so far - the position a marker is
     *  reported at (see {@link MarkedCapture.Listener}).  Touched only inside
     *  {@link #dispatchLock}, which is also what orders it against the batches. */
    private long pcmBytesDelivered;

    /** Spec 4.4: the stream "pauses" - set before {@code capture.stop} goes out
     *  and cleared before {@code capture.start} does, so the batches the server's
     *  audio worker still has queued are counted but no longer delivered.
     *  Guarded by {@link #dispatchLock}. */
    private boolean paused;
    /** The packet counter the NEXT frame must carry (spec 5: per stream, starts
     *  at 0, +1 per frame of ANY type).  Touched only by the reader thread that
     *  delivers the frames. */
    private long nextPacket;

    public NetPcmCapture(NetConnection connection, NetDeviceRef device, int sampleRate,
            int bitDepth, NetDeviceManager owner) {
        super(sampleRate, bitDepth);
        this.connection = connection;
        this.device = device;
        this.owner = owner;
    }

    // -------------------------------------------------------------------------
    // The stream lifecycle of spec 4.4
    // -------------------------------------------------------------------------

    /**
     * Takes the device (spec 4.4: "requires input lock") and opens the stream.
     *
     * <p><b>Byte positions are PER-OPEN.</b>  The delivered-byte counter every
     * marker and every gap is reported against starts at 0 here and counts only
     * this stream's payload, so a position from an earlier open means nothing
     * against a later one - a consumer that cached one across a reopen would cut
     * its record at a boundary belonging to a measurement that is already over.
     *
     * @throws IllegalStateException when the server refuses either step - the
     *         message carries the code of spec 4.2, so a device somebody else is
     *         using reads as such and not as a mystery.  The lock is given back
     *         before the throw: an open that failed must cost nothing
     */
    @Override
    public void open() {
        NetMessage taken = connection.request(
                device.into(connection.newRequest(MessageType.DEVICE_ACQUIRE)));
        if (!taken.isOk()) {
            throw refusal("cannot take " + device, taken);
        }
        acquired = true;
        owner.lockTaken(device);
        NetMessage opened;
        try {
            opened = connection.request(
                    device.into(connection.newRequest(MessageType.CAPTURE_OPEN))
                            .put(NetFields.RATE, sampleRate)
                            .put(NetFields.BITS, bitDepth));
        } catch (RuntimeException e) {
            release();
            throw e;
        }
        if (!opened.isOk()) {
            release();
            throw refusal("cannot open a capture on " + device, opened);
        }
        JsonNode data = opened.getData();
        int grantedBits = data.path(NetFields.BITS).asInt(bitDepth);
        int grantedChannels = data.path(NetFields.CHANNELS).asInt(STEREO);
        int grantedRate = data.path(NetFields.RATE).asInt(sampleRate);
        int handle = data.path(NetFields.CAPTURE_ID).asInt(NOT_OPEN);
        if (grantedBits != bitDepth || grantedChannels != STEREO || handle == NOT_OPEN) {
            // The sample width is baked into the inherited decoder, so a stream
            // of a different width would not be re-pinned but mis-read - every
            // sample silently wrong.  The rate is another matter: it changes
            // nothing about the bytes, and getFormat() reports the granted one.
            closeRemote(handle);
            release();
            throw new IllegalStateException("the server granted " + grantedChannels
                    + " channel(s) at " + grantedBits + " bit on " + device
                    + ", which this stream cannot decode - it asked for " + STEREO
                    + " at " + bitDepth + " bit");
        }
        granted = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, grantedRate, grantedBits,
                grantedChannels, grantedChannels * (grantedBits / 8), grantedRate, false);
        nextPacket = 0;
        // MUTATION 2: the byte position is NOT restarted per open.
        captureId = handle;
        connection.addStreamListener(handle, this::onFrame);
        // The two ways this stream can end without the socket saying anything:
        // the bench's input lane failing (spec 4.3) and the session dying
        // (spec 4.1).  Both look exactly like a quiet bench until they are heard.
        connection.addEventListener(events);
        connection.addCloseListener(sessionEnd);
        if (log.isInfoEnabled()) {
            log.info("net capture {}: open on {} at {} Hz / {} bit", handle, device,
                    grantedRate, grantedBits);
        }
    }

    /** Spec 4.4: the binary frames begin.  The pause is lifted BEFORE the request
     *  goes out, because a frame is not ordered against the response either - the
     *  server answers on its request worker and streams from another thread - and
     *  the first batch of a measurement is measurement data. */
    @Override
    public void startRecording() {
        synchronized (dispatchLock) {
            paused = false;
        }
        command(MessageType.CAPTURE_START);
        recording.set(true);
    }

    /** Spec 4.4: "stream pauses; counters keep their values" - so the packet
     *  counter is deliberately NOT reset here.
     *
     *  <p>The pause is taken BEFORE the request goes out, and taking it waits for
     *  the batch that may be in the pipeline: when this returns, nothing is
     *  reaching the module any more, which is what joining a consume thread buys
     *  a local backend.  The request itself must not be sent while holding the
     *  lock - its answer arrives on the reader thread the lock would be shutting
     *  out. */
    @Override
    public void stopRecording() {
        recording.set(false);
        synchronized (dispatchLock) {
            paused = true;
        }
        command(MessageType.CAPTURE_STOP);
    }

    /**
     * Ends the stream and gives the device back, in that order and whatever went
     * wrong on the way: the SPI's close may not throw, and a lock left behind is
     * a device no other client can ever take again.
     */
    @Override
    public void close() {
        recording.set(false);
        connection.removeEventListener(events);
        connection.removeCloseListener(sessionEnd);
        int open = captureId;
        captureId = NOT_OPEN;
        if (open != NOT_OPEN) {
            connection.removeStreamListener(open);
            closeRemote(open);
        }
        release();
    }

    /**
     * The format the server actually granted (spec 4.4), which is what a client
     * re-pins to - the requested one until the open has answered.
     */
    @Override
    public AudioFormat getFormat() {
        AudioFormat open = granted;
        return open == null ? super.getFormat() : open;
    }

    // -------------------------------------------------------------------------
    // The frames of spec 5
    // -------------------------------------------------------------------------

    /**
     * One binary frame for this stream, on the connection's reader thread.
     *
     * <p>The counter is checked first and for EVERY type: spec 5 increments it
     * per frame of any type, so a GAP or a marker that went missing would
     * otherwise pass unnoticed and the client would splice across real loss.
     */
    private void onFrame(BinaryFrame frame) {
        if (frame.packetCounter() != nextPacket) {
            fail("the packet counter jumped from " + nextPacket + " to "
                    + frame.packetCounter() + " - intact TCP neither loses nor "
                    + "reorders, so this stream is broken");
            return;
        }
        nextPacket++;
        switch (frame.type()) {
            case PCM:
                deliver(frame.payload());
                break;
            case GAP:
                gap(frame.n());
                break;
            case MARKER:
                mark(frame.n());
                break;
            default:
                // Spec 1: an unknown binary frame type is skipped whole.
                if (log.isDebugEnabled()) {
                    log.debug("net capture {}: frame type {} skipped", captureId,
                            frame.typeCode());
                }
                break;
        }
    }

    /**
     * Hands one PCM batch to the pipeline - unless the stream is paused.
     *
     * <p>Spec 4.4's {@code capture.stop} is answered by the server's REQUEST
     * worker while its audio worker is still draining the batches it has queued,
     * so frames really do arrive after {@link #stopRecording()} returned.  They
     * are COUNTED - the counter of spec 5 runs over every frame, and the caller
     * has already checked it - and then dropped, because every local backend
     * joins its consume thread inside its stop and a batch reaching a module
     * after that call returned is a divergence, not a nuance.  Gating on the
     * recording flag instead would have the mirror bug at the START: a frame is
     * not ordered against the {@code capture.start} response either.
     */
    private void deliver(byte[] pcm) {
        synchronized (dispatchLock) {
            if (paused) {
                if (log.isDebugEnabled()) {
                    log.debug("net capture {}: a batch queued before the stop was dropped",
                            captureId);
                }
                return;
            }
            dispatch(pcm, pcm.length);
            // AFTER the dispatch, so the count is what the pipeline has really
            // been given: a marker reported at this boundary says "everything up
            // to here was before the mark", and a batch counted before it was
            // handed over would move the boundary a batch too early.
            pcmBytesDelivered += pcm.length;
        }
    }

    /**
     * Spec 5's MARKER: an in-band position mark, passed on to whoever is
     * assembling a sweep-bounded record.  Nobody listening is the ordinary case -
     * a scope or an FFT has no use for it - and then counting the frame is all
     * this stream owes it.
     *
     * <p>Taken under the same monitor the batches are: the mark's whole meaning
     * is WHERE it falls between them (spec 5 aligns the next PCM byte with sweep
     * sample 0), and reading the count outside the lock would let a batch land
     * between the read and the call and report the boundary one batch late.
     * The listener is therefore called on the reader thread with the dispatch
     * lock held - the same place and the same contract as the batches it is
     * interleaved with, which is why it must not block.
     */
    private void mark(long markerKind) {
        MarkedCapture.Listener listener = markerListener;
        if (listener == null) {
            return;
        }
        synchronized (dispatchLock) {
            if (log.isDebugEnabled()) {
                log.debug("net capture {}: marker {} after {} PCM byte(s)", captureId,
                        markerKind, pcmBytesDelivered);
            }
            try {
                listener.marker((int) markerKind, pcmBytesDelivered);
            } catch (RuntimeException e) {
                if (log.isErrorEnabled()) {
                    log.error("net capture {}: marker listener failed (continuing)",
                            captureId, e);
                }
            }
        }
    }

    /**
     * Spec 5: the server dropped {@code lostFrames} stereo frames and says so,
     * "an explicit confession - the client resets averaging instead of silently
     * splicing".
     *
     * <p>It goes BOTH ways, and the two are different questions.  The manager is
     * the whole client's fault hub: it surfaces the loss to the operator and has
     * the running analyzers reset their averaging, which is what a stream that
     * carries on needs.  A record being assembled out of this same stream cannot
     * carry on - a sweep with a hole in it deconvolves into a result that looks
     * like a measurement - so the assembler is told too, at the byte position the
     * hole falls on, and fails its own measurement honestly.
     */
    private void gap(long lostFrames) {
        if (log.isWarnEnabled()) {
            log.warn("net capture {}: the server lost {} stereo frame(s)", captureId,
                    lostFrames);
        }
        MarkedCapture.Listener assembler = markerListener;
        if (assembler != null) {
            // Under the batch monitor, for the reason mark() gives: the position
            // is only true between two batches.
            synchronized (dispatchLock) {
                try {
                    assembler.gap(lostFrames, pcmBytesDelivered);
                } catch (RuntimeException e) {
                    if (log.isErrorEnabled()) {
                        log.error("net capture {}: record assembler failed on a gap "
                                + "(continuing)", captureId, e);
                    }
                }
            }
        }
        try {
            owner.gap(lostFrames);
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("net capture {}: gap listener failed (continuing)", captureId, e);
            }
        }
    }

    /**
     * Spec 4.3's {@code ev.device.error}: the bench's input lane failed, so this
     * stream is over whatever the socket still says.
     *
     * <p>The end travels the seam the data travelled - {@code endCapture}
     * finishes the ring buffer, every reader answers terminally, and the pane
     * that claims the reason tells the operator.  Never sideways.
     */
    private void onEvent(NetMessage event) {
        if (event.getType() != MessageType.EV_DEVICE_ERROR
                || !NetFields.INPUT.equals(event.optString(NetFields.DIRECTION))) {
            return;
        }
        if (!halt()) {
            return;
        }
        String detail = event.optString(NetFields.DETAIL);
        if (log.isWarnEnabled()) {
            // The bench's own reading of the fault rides along (spec 4.3's
            // optional reason); the ring's terminal state stays the operator's
            // report, so here it is the log that gains the WHY.
            log.warn("net capture {}: the bench's input lane failed ({}) - {}", captureId,
                    DeviceFailureReason.fromName(event.optString(NetFields.REASON)), detail);
        }
        endCapture(CaptureEndReason.DEVICE_LOST, detail);
    }

    /**
     * Spec 4.1: the session is dead, so "stop all modules, show the connection
     * error".  The stream stops for EVERY end - an operator's own
     * {@link NetCloseReason#BYE} as much as a keepalive death: the bench is gone
     * either way, and a pane left feeding on it draws a measurement of nothing.
     *
     * <p>The stream's CONSUMERS learn through the ring buffer ({@code
     * endCapture}), like every other capture death; the loss of the session
     * itself stays the session's to report, once, whether or not a capture
     * happened to be open on it - that report is the net bench UI's.
     */
    private void onSessionEnd(NetCloseReason reason) {
        if (!halt()) {
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("net capture {}: the session ended - {}", captureId,
                    reason.getDetail());
        }
        endCapture(CaptureEndReason.DEVICE_LOST, "the session ended - " + reason.getDetail());
    }

    /** The stream ended by itself - the end travels the data seam ({@code
     *  endCapture} -&gt; ring buffer -&gt; reader -&gt; worker -&gt; pane), so whoever is
     *  measuring is told why without anything going sideways. */
    private void fail(String detail) {
        if (!halt()) {
            return;
        }
        if (log.isErrorEnabled()) {
            log.error("net capture {}: {}", captureId, detail);
        }
        endCapture(CaptureEndReason.DEVICE_LOST, detail);
    }

    /**
     * The stream stops being a stream: no more frames are routed here, and the
     * server is told to stop sending them.  Answers false when it had already
     * ended, so every ending is acted on exactly once.
     *
     * <p>The stop goes out ASYNCHRONOUSLY on purpose - this runs on the
     * connection's reader thread, and a blocking request here would wait for an
     * answer only that same thread can deliver.  The handle is deliberately
     * kept, so the caller's {@link #close()} still closes the remote stream and
     * releases the lock.
     */
    private boolean halt() {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        recording.set(false);
        int open = captureId;
        if (open != NOT_OPEN) {
            connection.removeStreamListener(open);
            connection.send(connection.newRequest(MessageType.CAPTURE_STOP)
                    .put(NetFields.CAPTURE_ID, open));
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Talking to the server
    // -------------------------------------------------------------------------

    /** {@code capture.start} and {@code capture.stop} - the same message shape
     *  with nothing in it but the handle. */
    private void command(MessageType type) {
        int open = captureId;
        if (open == NOT_OPEN) {
            throw new IllegalStateException(type.getWire() + " on a capture that is not "
                    + "open - " + device);
        }
        NetMessage answer = connection.request(connection.newRequest(type)
                .put(NetFields.CAPTURE_ID, open));
        if (!answer.isOk()) {
            throw refusal(type.getWire() + " failed on " + device, answer);
        }
    }

    /** Closes the remote stream, best effort: this is a teardown step, and every
     *  step of a teardown runs whether or not the one before it succeeded. */
    private void closeRemote(int handle) {
        if (handle == NOT_OPEN) {
            return;
        }
        try {
            connection.request(connection.newRequest(MessageType.CAPTURE_CLOSE)
                    .put(NetFields.CAPTURE_ID, handle));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net capture {}: close failed: {}", handle, e.toString());
            }
        }
    }

    /** Gives the device lock back (spec 4.3: the release also closes anything
     *  still open on it), exactly once and through the manager that registered
     *  it, so what the client believes it holds stays true. */
    private void release() {
        if (!acquired) {
            return;
        }
        acquired = false;
        owner.releaseDevice(device);
    }

    /** A refusal turned into the exception the SPI's caller catches - the
     *  session's own wording, shared with every other thing on it that can be
     *  refused (see {@link NetConnection#refusal}). */
    private IllegalStateException refusal(String what, NetMessage answer) {
        return connection.refusal(what, answer);
    }
}
