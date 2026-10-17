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
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.GuiUtil;

/**
 * The delay dialog's controller: runs one {@link MeasureDelayWorker} at a time
 * on its own thread, carries the worker's reports onto the UI thread, and holds
 * the audio devices for the measurement through the same exclusive-measurement
 * events the frequency-response wizards use
 * ({@link Events#FREQRESP_MEASUREMENT_STARTED} /
 * {@link Events#FREQRESP_MEASUREMENT_STOPPED}), which make the other panes stop
 * and give the devices back - published, not waited on, exactly as those
 * wizards do.  UI thread, except where stated.
 */
public final class MeasureDelayController {

    /** Why a measurement produced no delay. */
    public enum Failure {
        /** No active output or input device. */
        NO_DEVICE,
        /** The backend measured nothing: a line did not open, or an edge did
         *  not arrive. */
        NO_RESULT
    }

    /** The dialog's view of a measurement - every call on the UI thread. */
    public interface Listener {
        void measured(double delayMs);
        void failed(Failure failure);
    }

    /** How long a running measurement is given to end on close. */
    private static final long THREAD_JOIN_TIMEOUT_MS = 3_000;

    private final Listener listener;
    private Thread thread;
    /** Whether the exclusive-measurement start has been announced and its
     *  stop is still owed. */
    private boolean exclusive;

    public MeasureDelayController(Listener listener) {
        this.listener = listener;
    }

    /** Starts one measurement; ignored while one is running. */
    public void measure(double amplitudeVrms, OutputChannels output, Channel input) {
        if (thread != null && thread.isAlive()) return;
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
        exclusive = true;
        MeasureDelayWorker worker = new MeasureDelayWorker(amplitudeVrms, output, input,
                new MeasureDelayWorker.Listener() {
                    @Override
                    public void measured(double delayMs) {
                        GuiUtil.marshal(() -> {
                            releaseExclusive();
                            listener.measured(delayMs);
                        });
                    }

                    @Override
                    public void failed(Failure failure) {
                        GuiUtil.marshal(() -> {
                            releaseExclusive();
                            listener.failed(failure);
                        });
                    }
                });
        thread = new Thread(worker, "measure-delay");
        thread.setDaemon(true);
        thread.start();
    }

    /** Waits for a running measurement to end and gives the devices back -
     *  the dialog's close. */
    public void close() {
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        releaseExclusive();
    }

    /** Announces the measurement's end once per start - the other panes may
     *  take the devices again. */
    private void releaseExclusive() {
        if (!exclusive) return;
        exclusive = false;
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
    }
}
