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

package org.edgo.audio.measure.sound.loopback;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.sound.AbstractPcmCapture;

/**
 * The capture lane of the digital loopback - a thin AudioCapture client that
 * {@code attach}es / {@code detach}es the capture lane on the manager's one
 * {@link LoopbackDuplexEngine}.  All the device-agnostic machinery (the
 * offset-binary {@code readSample} decode, the captured AudioFormat, the listener
 * fan-out in {@code dispatch}) is inherited from {@link AbstractPcmCapture}; the
 * engine renders blocks in exactly that encoding, so they pass straight through.
 *
 * <p>This lane produces NOTHING of its own, silence included.  The known noise
 * floor the backend exists for is dither, dither belongs to the playback side, and
 * the engine's unattached generator lane is what emits it - so a capture with
 * nothing playing receives the same dithered blocks it would receive from a
 * playing lane, and there is no second quantiser here to keep in step with the
 * first.
 *
 * <p>The engine hands each block over synchronously on its pacer thread, which is
 * the session's only clock and waits on nobody: there is no queue to cross and no
 * consume thread of this lane's own.  Nor is the base class's delivery deadline
 * armed - it watches for a device that stopped speaking, and a software session
 * has no device to lose.
 */
@Log4j2
public final class LoopbackCapture extends AbstractPcmCapture
        implements LoopbackDuplexEngine.CaptureConsumer {

    private final LoopbackDeviceManager manager;

    private LoopbackDuplexEngine engine;

    LoopbackCapture(LoopbackDeviceManager manager, int sampleRate, int bitDepth) {
        super(sampleRate, bitDepth);
        this.manager = manager;
    }

    @Override
    public void open() {
        engine = manager.acquireEngine(sampleRate, bitDepth);
        if (log.isInfoEnabled()) {
            log.info("Loopback capture opened : {}", getFormat());
        }
    }

    @Override
    public void startRecording() {
        if (engine == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        engine.attachCapture(this);               // starts the session if idle
        if (log.isInfoEnabled()) {
            log.info("Loopback recording started: {} Hz, {} bits", sampleRate, bitDepth);
        }
    }

    @Override
    public void stopRecording() {
        recording.set(false);
        if (engine != null) {
            engine.detachCapture();               // ends the session only if the last lane detaches
        }
        if (log.isInfoEnabled()) {
            log.info("Loopback recording stopped.");
        }
    }

    @Override
    public void close() {
        if (recording.get()) {
            stopRecording();
        }
    }

    /** Engine capture-lane sink, on the pacer thread: the block is already in the
     *  advertised encoding, so it goes straight to the listeners.  Synchronous by
     *  contract - the buffer is reused as soon as this returns. */
    @Override
    public void onAudio(byte[] block, int length) {
        if (!recording.get()) {
            return;
        }
        dispatch(block, length);
    }
}
