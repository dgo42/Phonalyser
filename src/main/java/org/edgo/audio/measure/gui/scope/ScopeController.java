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

package org.edgo.audio.measure.gui.scope;

import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Controller of the oscilloscope pane: owns the scope's share of the
 * capture-device lifecycle — the Record state, the live buffer reference
 * and the {@link Events#CAPTURE_ACQUIRE} / {@link Events#CAPTURE_RELEASE}
 * handshake with {@code SharedCapture} (the scope and the FFT pane share
 * the same device via its refcount).
 *
 * <p>The pane orchestrates the VIEW side around these calls — attaching
 * the returned live buffer to its canvases, starting the measurement
 * thread and redraw timer on acquire, re-attaching the frozen snapshot on
 * release.  The save / open-signal / auto-setup features stay with their
 * widgets: their engines ({@code ScopeFileSaver}, {@code ScopeOpenSignal},
 * {@code StereoPcmIo}, {@code ScopeMeasurementWorker}) are already
 * separate classes, and their orchestration reads displayed view state.
 */
@Log4j2
public final class ScopeController {

    /** True while the scope's own Record state is on.  Does NOT reflect
     *  the shared capture device — the FFT pane can hold it open via
     *  {@code SharedCapture} while this stays {@code false}. */
    @Getter
    private volatile boolean capturing;
    /** Live capture buffer held while {@link #capturing}; snapshotted by
     *  {@link #releaseCapture()} before the release. */
    private SignalBufferReader currentBuffer;

    /** Requests the shared capture via the bus and holds the returned live
     *  buffer.  Returns {@code null} when the device fails to open — the
     *  reason is then available via {@link #getLastStartError()}.  Already
     *  capturing: returns the held buffer (idempotent). */
    public synchronized SignalBufferReader acquireCapture() {
        if (capturing) return currentBuffer;
        SignalBufferReader buf = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
        if (buf == null) return null;
        capturing = true;
        currentBuffer = buf;
        return buf;
    }

    /** Drops the scope's capture reference and returns a frozen snapshot
     *  of the last captured frame for the views to keep showing —
     *  {@code null} when not capturing or no buffer was held.  Publishes
     *  {@link Events#CAPTURE_RELEASE} so {@code SharedCapture} can close
     *  the device once every holder is gone. */
    public synchronized SignalBufferReader releaseCapture() {
        if (!capturing) return null;
        capturing = false;
        SignalBufferReader frozen =
                (currentBuffer != null) ? currentBuffer.frozenSnapshot() : null;
        currentBuffer = null;
        MessageBus.instance().publish(Events.CAPTURE_RELEASE);
        log.info("Oscilloscope stopped.");
        return frozen;
    }

    /** Human-readable description of the last {@link #acquireCapture()}
     *  failure (or {@code null} if it succeeded / wasn't attempted).
     *  Forwarded from {@code SharedCapture}. */
    public String getLastStartError() {
        return SharedCapture.instance().getLastStartError();
    }

    /** Releases a still-held capture — called from the pane's dispose
     *  listener. */
    public void shutdown() {
        releaseCapture();
    }
}
