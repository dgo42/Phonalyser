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

import org.edgo.audio.measure.sound.DeviceRef;

/**
 * What goes wrong on a remote bench, in the two shapes the spec tells apart: a
 * loss the server admits to (spec 5) and a failure that ends the measurement
 * (spec 4.3's {@code ev.device.error}, spec 4.1's dead session, spec 5's jumped
 * packet counter).
 *
 * <p><b>Why the seam lives on the manager.</b>  A module holds its capture as an
 * {@code AudioCapture} and never sees this backend's own types, so a listener
 * settable on {@link NetPcmCapture} could only be set by code that already holds
 * that concrete class - which nothing outside this package ever does.  The glue
 * DOES hold {@link NetDeviceManager}: connecting it to a server is what the
 * server-list dialog is for.  So the manager is where a client's faults are
 * subscribed to, once for the whole bench, and every capture it opens reports
 * through it.
 *
 * <p>Both calls arrive on the connection's reader thread and must return without
 * blocking - see {@link NetConnection}'s threading note.
 */
public interface NetFaultListener {

    /**
     * Spec 5: the server dropped {@code lostFrames} stereo frames and said so.
     *
     * <p>Not a failure - the stream carries on.  What must not happen is the
     * silent splice: whoever is averaging across the seam has to reset instead
     * of treating the two sides as one continuous record.
     */
    void gap(long lostFrames);

    /**
     * A failure the operator must be told about and the affected modules must
     * stop on - spec 4.3: "the client surfaces it exactly like a local device
     * error (message + stop the affected modules)".
     *
     * @param direction which lane died - {@code "input"} or {@code "output"},
     *        the values spec 4.3 puts in {@code ev.device.error}
     * @param detail    the failure text shown to the operator
     */
    void deviceError(String direction, String detail);

    /**
     * A device this client is HOLDING has left the bench's catalogue: spec 4.3's
     * {@code ev.devices.changed} no longer lists it, so the analyzer was
     * unplugged, the server dropped it, or its session was discarded there.
     *
     * <p><b>Why it is not simply the catalogue's business.</b>  The event carries
     * the whole new list and used to be swallowed whole - the cache was replaced,
     * an empty array accepted in silence, and nothing asked whether the device
     * the operator is MEASURING on was still in it.  On the bench that produced
     * this method the server logged "QA40x session discarded (the analyzer was
     * detached)" and broadcast the change, and the desktop went on generating and
     * sweeping against a device that no longer existed.
     *
     * <p>It is a device error in every way that matters to the operator, and is
     * surfaced as one - hence a separate call rather than a second channel: only
     * the client knows what it holds, and only the glue knows how to say so.
     *
     * @param device the held device the bench no longer offers
     */
    void deviceGone(DeviceRef device);

    /**
     * The bench changed what one of its devices MEANS: the calibration it stores
     * for {@code device} is not the one the last catalogue carried (spec 4.3's
     * {@code cal}, re-sent whole by every {@code ev.devices.changed}).
     *
     * <p>Not a fault - nothing failed - but it belongs on this seam for the same
     * reason the two above do: it arrives on the connection's reader thread, only
     * the client knows what it is measuring on, and only the glue knows how to
     * make a full scale take effect there.  A second interface for one call would
     * be a second setter and a second registration of the same subscriber.
     *
     * <p>The listener's job is to re-apply it where it is already in force, so a
     * running dBV axis and a running generator amplitude follow the bench instead
     * of scaling by yesterday's number until the next device open.
     *
     * @param device the bench device whose stored calibration moved, carrying the
     *        NEW values ({@code DeviceRef.calibration()})
     */
    void calibrationChanged(DeviceRef device);
}
