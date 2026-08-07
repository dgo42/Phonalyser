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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * One connection's open capture streams - the {@code capture.open / start / stop
 * / close} vocabulary of spec 4.4 - and the rules that decide whether an open is
 * allowed at all.
 *
 * <p>It owns the streams, not the permission to have them: the caller checks
 * that this connection holds the device's lock (spec 4.4: "requires input lock")
 * before delegating, because lock ownership is between the session and the
 * {@link LockRegistry} and putting it here would make the two types point at
 * each other.  What IS decided here is everything about the streams themselves:
 *
 * <ul>
 *   <li><b>One open capture per device</b> (spec 4.4) - a second open on a
 *       device this connection already streams is refused rather than silently
 *       replacing the first, which would leave the client holding a captureId
 *       that no longer produces frames.</li>
 *   <li><b>Input only.</b>  A capture on an output ref is a client bug; the
 *       backend would open a capture line on a device the ref does not describe.
 *       </li>
 *   <li><b>The QA40x runs one clock.</b>  Spec 4.4's equal-rates rule is asked
 *       of the {@link Qa40xGuard}, not decided here: the analyzer's clock is the
 *       SERVER's, and a bench with two units attached hands them out as two
 *       locks that two different connections may hold, so a rule that looked
 *       only at this connection's streams would pass an open that re-clocks
 *       somebody else's running measurement.</li>
 * </ul>
 *
 * <p>The audio worker is this connection's SECOND thread, separate from the one
 * that runs its requests: see {@link CaptureStream} for why a device call must
 * not be able to stall the frames of streams that are already running.  It
 * carries the frames alone; the device calls - the opens, the closes and the
 * teardown of {@link #closeAll()} - all happen on the session's own thread, in
 * the order spec 4.1 gives the teardown.
 */
@Log4j2
@RequiredArgsConstructor
public final class CaptureStreamer {

    /** Highest handle spec 5 can carry: the binary header holds the stream id in
     *  a u16, and spec 4.4's {@code captureId} IS that id, so the two must not be
     *  able to disagree. */
    private static final int MAX_CAPTURE_ID = 0xFFFF;

    private final AudioBackend audio;
    private final DeviceCatalog catalog;
    private final JsonCodec codec;
    private final SessionChannel channel;
    private final SessionWorker sender;
    private final Qa40xGuard qa40x;

    private final Map<Integer, CaptureStream> streams = new ConcurrentHashMap<>();
    /** Where the next handle search starts.  Touched only by the session's
     *  request thread, which serialises every open. */
    private int nextCaptureId = 1;

    /**
     * Spec 4.4: opens the device and answers the format actually granted, which
     * may differ from the one asked for - "the granted {@code rate} may differ
     * (device reality), client re-pins".
     *
     * @throws NetException with the spec 4.2 code the refusal deserves
     */
    public JsonNode open(DeviceLock device, String name, Integer rateHz, Integer bits) {
        if (rateHz == null || bits == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "capture.open needs a rate and a bit depth");
        }
        if (!device.input()) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "capture.open needs an input device ref, got " + device);
        }
        if (streamOn(device) != null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a capture is already open on " + device);
        }
        DeviceRef ref = catalog.resolve(device, name);
        int captureId = freeCaptureId();
        qa40x.claimRate(device, rateHz);
        AudioCapture capture;
        // Hoisted out of the try so the refusal - and later the stream's own
        // death - can ask the SAME backend what its error meant.  The text
        // below is the server's language and its driver codes are nobody's;
        // the reason travels beside it for the client to say in the operator's.
        AudioDeviceManager captureManager = audio.manager(ref.carrier());
        try {
            capture = openLine(captureManager, ref, device, name, rateHz, bits);
        } catch (NetException e) {
            qa40x.releaseRate(device);
            throw e;
        }
        streams.put(captureId, new CaptureStream(captureId, device, ref.name(),
                catalog.boundCard(ref.name()), capture, captureManager, channel,
                sender, this::streamFailed));
        nextCaptureId = afterCaptureId(captureId);
        AudioFormat format = capture.getFormat();
        if (log.isInfoEnabled()) {
            log.info("net capture {}: open on {} at {} Hz / {} bit",
                    captureId, device, Math.round(format.getSampleRate()),
                    format.getSampleSizeInBits());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.CAPTURE_ID, captureId);
        data.put(NetFields.RATE, Math.round(format.getSampleRate()));
        data.put(NetFields.BITS, format.getSampleSizeInBits());
        // Spec 4.4 writes the channel count as the constant 2, and every backend
        // does deliver stereo (a mono device is upmixed by the shared capture
        // base) - but what the client re-pins to must be the format actually
        // granted, so this is read from it rather than asserted.
        data.put(NetFields.CHANNELS, format.getChannels());
        data.put(NetFields.FRAME_BYTES, format.getFrameSize());
        return codec.toNode(data);
    }

    /**
     * The capture line, opened - with the ONE further attempt a stale device
     * snapshot earns.
     *
     * <p>A backend whose device list is a start-up snapshot fails every open on a
     * card that was unplugged and plugged back in, because it is still describing
     * where the card USED to be.  The catalog answers whether that is what
     * happened, rebuilds if it is, and hands back the ref as it stands now; a
     * null from it means a retry could not help and the first refusal is the
     * answer.  Exactly one retry, never a loop: the rebuild either made the list
     * right or the device is genuinely not openable.
     */
    private AudioCapture openLine(AudioDeviceManager manager, DeviceRef ref, DeviceLock device,
            String name, int rateHz, int bits) {
        try {
            return openedLine(manager, ref, rateHz, bits);
        } catch (Exception first) {
            DeviceRef fresh = catalog.refreshedForRetry(device, name);
            if (fresh == null) {
                throw refusal(manager, first, device, rateHz, bits);
            }
            if (log.isInfoEnabled()) {
                log.info("net capture: {} failed to open and the device list was stale - "
                        + "rebuilt it, trying once more: {}", device, first.toString());
            }
            try {
                return openedLine(manager, fresh, rateHz, bits);
            } catch (Exception retried) {
                throw refusal(manager, retried, device, rateHz, bits);
            }
        }
    }

    /** ONE attempt at the line, leaving nothing half-open behind a failure.  The
     *  SPI form, not {@code AudioBackend.openCapture(device)}: this open honours
     *  the WIRE-requested rate (spec 4.4), never local prefs. */
    private AudioCapture openedLine(AudioDeviceManager manager, DeviceRef ref, int rateHz,
            int bits) throws Exception {
        AudioCapture capture = manager.openCapture(ref, rateHz, bits);
        try {
            capture.open();
        } catch (Exception e) {
            capture.close();
            throw e;
        }
        return capture;
    }

    /** The one refusal wording both attempts end in - the server's own language,
     *  with the backend's reading of its own error beside it for the client to say
     *  in the operator's. */
    private NetException refusal(AudioDeviceManager manager, Exception failure, DeviceLock device,
            int rateHz, int bits) {
        return new NetException(ErrorCode.DEVICE_ERROR,
                "cannot open " + device + " at " + rateHz + " Hz / " + bits
                        + " bit: " + failure,
                manager.classifyFailure(failure));
    }

    /** Spec 4.4: the binary frames begin.  The streaming lines live HERE, on
     *  the wire-command path, not in the stream - its own stop() also runs
     *  inside every close, and a teardown must not read as an operator stop. */
    public void start(Integer captureId) {
        CaptureStream stream = stream(captureId);
        stream.start();
        if (log.isInfoEnabled()) {
            log.info("net capture {}: streaming started on {} (card {})", captureId,
                    stream.getDeviceName(), cardOrNone(stream.getCardName()));
        }
    }

    /** Spec 4.4: the stream pauses and keeps its counters. */
    public void stop(Integer captureId) {
        CaptureStream stream = stream(captureId);
        stream.stop();
        if (log.isInfoEnabled()) {
            log.info("net capture {}: streaming stopped on {} (card {})", captureId,
                    stream.getDeviceName(), cardOrNone(stream.getCardName()));
        }
    }

    /** The streaming lines' card slot for a device no card is in force for. */
    private String cardOrNone(String cardName) {
        return cardName == null ? "none" : cardName;
    }

    /** Spec 4.4: the stream ends and the device line goes back. */
    public void close(Integer captureId) {
        CaptureStream stream = stream(captureId);
        streams.remove(stream.getCaptureId());
        stream.close();
        qa40x.releaseRate(stream.getDevice());
    }

    /**
     * Spec 4.5: starting a sweep "injects a {@code sweepStart} marker (spec 5)
     * into this connection's open capture streams".
     *
     * <p>The generator asks for it and this answers, because the streams - and
     * the packet counters the marker has to take a number from - are here.  ALL
     * of them get one: the client that started the sweep decides which of its
     * streams the marker matters for, and a server that guessed would have to
     * know which input the operator wired the device under test to.
     */
    public void markSweepStart() {
        for (CaptureStream stream : streams.values()) {
            stream.markSweepStart();
        }
    }

    /** Spec 4.3: {@code device.release} "also closes any open stream ... on it".
     *  Silent when there is none - releasing a device nobody streamed is not an
     *  error. */
    public void closeDevice(DeviceLock device) {
        CaptureStream stream = streamOn(device);
        if (stream != null) {
            streams.remove(stream.getCaptureId());
            stream.close();
            qa40x.releaseRate(device);
        }
    }

    /**
     * A stream that failed, after it confessed {@code ev.device.error}: the
     * honest-loss rule is the error AND the close, so the line goes back here
     * and the handle is spent.
     *
     * <p>Silent for a stream that has already gone - a teardown racing the
     * failure is the ordinary case, and both may not close the same line twice.
     */
    private void streamFailed(CaptureStream stream) {
        if (streams.remove(stream.getCaptureId()) == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable t) {
            // Throwable: the line this is closing is the one that just failed, and
            // the failure it failed with is as often as not an Error out of a
            // native call.  The rate claim below is released either way - held for
            // ever it refuses every later open at another rate.
            if (log.isWarnEnabled()) {
                log.warn("net capture {}: close after a failure failed: {}",
                        stream.getCaptureId(), t.toString());
            }
        }
        qa40x.releaseRate(stream.getDevice());
    }

    /**
     * The first step of the teardown of spec 4.1: every stream stops and every
     * device line goes back - HERE, before this returns.
     *
     * <p>Which is the whole point of doing it inline.  {@link ClientSession}
     * closes the generator and parks the analyzer immediately after, and neither
     * may happen while an input line of this connection is still open: the
     * QA40x's park writes registers and releases the transport, and a device
     * closed by a second thread afterwards would be closed on hardware that is
     * already gone.  The teardown itself now runs on the session's own thread,
     * so a capture that takes as long as the hardware takes delays nothing but
     * the connection it belonged to.
     *
     * <p>The audio lane is shut down last, so a drain that was already queued
     * still puts its frames on the socket, and one stream that fails to stop
     * does not keep the others open.
     */
    public void closeAll() {
        List<CaptureStream> open = new ArrayList<>(streams.values());
        streams.clear();
        try {
            for (CaptureStream stream : open) {
                try {
                    stream.close();
                } catch (Throwable t) {
                    // Throwable, so ONE line that refuses to shut costs only
                    // itself: an Error out of a native close used to end this
                    // whole loop, leaving every other stream of the connection
                    // open and the session's locks unreleased behind it.
                    if (log.isWarnEnabled()) {
                        log.warn("net capture {}: close failed: {}",
                                stream.getCaptureId(), t.toString());
                    }
                }
                // AFTER the close, always: while the claim is gone but the line is
                // not, another session's open at a different rate passes the guard
                // and re-clocks the QA40x under a measurement that is still running.
                qa40x.releaseRate(stream.getDevice());
            }
        } finally {
            sender.shutdown();
        }
    }

    /** Package-private rather than private for exactly one caller: the test
     *  that pins the device/card names the streaming lines print. */
    CaptureStream stream(Integer captureId) {
        CaptureStream stream = captureId == null ? null : streams.get(captureId);
        if (stream == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "no capture is open with id " + captureId);
        }
        return stream;
    }

    private CaptureStream streamOn(DeviceLock device) {
        for (CaptureStream stream : streams.values()) {
            if (stream.getDevice().equals(device)) {
                return stream;
            }
        }
        return null;
    }

    /**
     * The next free handle, searching from where the last successful open left
     * off.  Spec 4.4 answers it as {@code captureId} on the control channel and
     * spec 5 stamps the SAME number into every binary frame as a u16
     * {@code streamId}, so a counter that ran past 65 535 would answer a handle
     * the frames no longer carry and the client could not route them.  Ids
     * therefore wrap inside the range and skip the ones still open.
     *
     * <p>The cursor moves only once the stream exists ({@link #afterCaptureId}),
     * so a refused open costs no handle.
     *
     * @throws NetException {@code INTERNAL} when this connection really does
     *         hold every id at once - refusing the open is the only honest
     *         answer left, and it happens before anything was opened
     */
    private int freeCaptureId() {
        int candidate = nextCaptureId;
        for (int tried = 0; tried < MAX_CAPTURE_ID; tried++) {
            if (!streams.containsKey(candidate)) {
                return candidate;
            }
            candidate = afterCaptureId(candidate);
        }
        throw new NetException(ErrorCode.INTERNAL,
                "this connection has all " + MAX_CAPTURE_ID + " capture ids open");
    }

    /** The handle after {@code captureId}, wrapping at the end of the u16 range
     *  - the one place that rule is written. */
    private int afterCaptureId(int captureId) {
        return captureId == MAX_CAPTURE_ID ? 1 : captureId + 1;
    }
}
