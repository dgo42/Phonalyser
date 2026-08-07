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

import java.util.Objects;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * One device on the far end, as the local SPI sees it: a {@link DeviceRef} whose
 * four wire fields (spec 4.3 - {@code backend, index, input, name}) are exactly
 * what every request naming this device must carry.  The component is called
 * {@code isInput} so the record's own accessor satisfies the SPI, the same shape
 * the other backends' refs use.
 *
 * <p><b>{@link #backend()} answers what the device IS - the TRUE remote type
 * (a QA403 on a bench answers {@code QA40X}); {@link #carrier()} answers
 * {@link AudioBackendType#NET}.</b>  The carrier is what routes every
 * {@code AudioBackend.manager(...)} dispatch at the net manager instead of at
 * the LOCAL driver of the same name - a QA40x on the bench across the room
 * must not be opened over this machine's USB - while the true type is what
 * type-specific control keys on, local or remote alike.  The wire text stays
 * in {@link #remoteBackend()}: it is the server's enum name, and a server may
 * know a backend this build does not - such a device answers
 * {@link AudioBackendType#NET} as its type too, the honest "reachable but not
 * identifiable here".
 *
 * <p>A record, so two refs describing the same device are equal - that is how
 * the manager finds a device's formats again when the SPI hands one back.
 *
 * <p><b>{@link #calibration()}, {@link #boundCard()} and {@link #calFromDevice()}
 * are carried but are NOT part of that identity</b> (hence the hand-written
 * {@code equals}/{@code hashCode} over the six wire fields alone).  They are the server's stored full scale and
 * card choice for this device, which spec 4.3 re-sends with every
 * {@code ev.devices.changed} - including the ones a {@code device.setCalibration}
 * or a {@code device.setCard} triggers.  Held locks and open streams are
 * tracked BY REF across those refreshes, so a ref whose identity moved when a
 * value changed would orphan the lock on a device this session is measuring on:
 * the release would name a ref the register no longer contains, and the device
 * would stay taken for the rest of the session.  Two refs for the same device
 * and direction are the same device however it is calibrated and whatever card
 * it is bound to.
 */
public record NetDeviceRef(int index, String name, String description, String vendor,
        boolean isInput, String remoteBackend, DeviceCalibration calibration,
        String boundCard, boolean calFromDevice)
        implements DeviceRef {

    @Override
    public AudioBackendType backend() {
        AudioBackendType type = AudioBackendType.fromNameOrNull(remoteBackend);
        return type != null ? type : AudioBackendType.NET;
    }

    @Override
    public AudioBackendType carrier() {
        return AudioBackendType.NET;
    }

    @Override
    public boolean isOutput() {
        return !isInput;
    }

    /** The six wire fields of spec 4.3 and nothing else - see the class comment
     *  for why the calibration and the card binding are deliberately absent. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NetDeviceRef ref)) {
            return false;
        }
        return index == ref.index && isInput == ref.isInput
                && Objects.equals(name, ref.name)
                && Objects.equals(description, ref.description)
                && Objects.equals(vendor, ref.vendor)
                && Objects.equals(remoteBackend, ref.remoteBackend);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, name, description, vendor, isInput, remoteBackend);
    }

    /** Stamps the device ref of spec 4.3 into a request - the one place those
     *  four fields are written, so an acquire, an open and a release can never
     *  name the device differently. */
    public NetMessage into(NetMessage message) {
        return message.put(NetFields.BACKEND, remoteBackend)
                .put(NetFields.INDEX, index)
                .put(NetFields.INPUT, isInput)
                .put(NetFields.NAME, name);
    }

    /** How the device reads in an error message or a log line. */
    @Override
    public String toString() {
        return remoteBackend + "[" + index + "] " + (isInput ? "input" : "output")
                + " '" + name + "'";
    }
}
