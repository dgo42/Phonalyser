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

package org.edgo.audio.measure.sound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Backend-agnostic stereo PCM playback line. Implementations:
 * {@link JavaSoundGenerator} (default WASAPI playback path) and
 * {@link WdmksGenerator} (PortAudio WDM-KS).
 */
public interface AudioPlayback extends AutoCloseable {

    void open() throws Exception;

    /** Plays a fixed duration, then drains and stops. */
    void play(SignalGenerator generator, int durationSeconds);

    /**
     * Plays continuously until {@code stopFlag} is raised. {@code readyLatch}
     * is counted down after the hardware buffer is fully pre-filled.
     */
    void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch);

    /**
     * Live-updates the TPDF dither resolution.  No-op when the
     * implementation doesn't support live dither changes (the
     * default).  Honoured by the in-process JavaSoundGenerator /
     * WdmksGenerator wired to the GUI.
     */
    default void setDitherBits(int bits) {}

    @Override
    void close();
}
