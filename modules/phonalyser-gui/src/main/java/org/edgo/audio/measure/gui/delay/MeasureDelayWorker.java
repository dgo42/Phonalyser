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

package org.edgo.audio.measure.gui.delay;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.sound.AudioBackend;

/**
 * One delay measurement on its own thread: asks the audio backend to measure
 * between the active output and input devices and reports the answer.  The
 * measurement itself - the lines, the pulse, the edge - is the backend's
 * ({@link AudioBackend#measureDelay}); this thread exists so the UI thread
 * does not wait the seconds it takes.  Owned by {@link MeasureDelayController}.
 */
final class MeasureDelayWorker implements Runnable {

    /** What the measurement reports back - called on the worker's thread. */
    interface Listener {
        void measured(double delayMs);
        void failed(MeasureDelayController.Failure failure);
    }

    private final double amplitudeVrms;
    private final OutputChannels output;
    private final Channel input;
    private final Listener listener;

    MeasureDelayWorker(double amplitudeVrms, OutputChannels output, Channel input,
                       Listener listener) {
        this.amplitudeVrms = amplitudeVrms;
        this.output        = output;
        this.input         = input;
        this.listener      = listener;
    }

    @Override
    public void run() {
        AudioBackend audio = AudioBackend.instance();
        if (audio.getActiveOutputDevice() == null || audio.getActiveInputDevice() == null) {
            listener.failed(MeasureDelayController.Failure.NO_DEVICE);
            return;
        }
        float delayMs;
        try {
            delayMs = audio.measureDelay(amplitudeVrms, output, input);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        if (Float.isNaN(delayMs)) {
            listener.failed(MeasureDelayController.Failure.NO_RESULT);
        } else {
            listener.measured(delayMs);
        }
    }
}
