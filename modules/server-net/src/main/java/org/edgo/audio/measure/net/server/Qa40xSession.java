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

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xControl;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceInfo;

import lombok.RequiredArgsConstructor;

/**
 * The QA40x extension of spec 4.6: everything about the analyzer that is not its
 * audio stream - identity and telemetry, the two full-scale range selectors, the
 * calibration page and the front-panel I2S port - turned into the payloads the
 * control channel carries.
 *
 * <p><b>What it is not.</b>  It holds no state of its own.  The analyzer's shared
 * facts live where they belong: the one reg-9 clock and the safe state with
 * {@link Qa40xGuard}, the ranges and the calibration page on the device (reached
 * through {@link Qa40xControl}, the contract the driver declares beside itself).
 * This
 * type is the translation between that surface and the wire, which is why one
 * instance serves the whole server - the analyzer is one, whoever is asking.
 *
 * <p><b>Locks are the caller's business.</b>  Spec 4.6 requires the QA40x lock
 * "unless marked read-only", and {@link ClientSession} is where a connection's
 * locks are known - the same split every other device call already follows
 * (the session checks, the collaborator does).
 *
 * <p><b>The driver is reached by capability, never by name.</b>  A server whose
 * build ships no QA40x module, or a host where {@code libusb} never loaded,
 * answers {@code UNSUPPORTED} rather than failing to link: the headless server
 * must not depend on any one backend module, which is also why the surface is a
 * core interface and not the driver class.
 */
@RequiredArgsConstructor
public final class Qa40xSession {

    private final AudioBackend audio;
    private final JsonCodec codec;
    /** The backends this server offers - the SAME list the catalog enumerates and
     *  answers {@code backend.select} from, handed in by the composition root.
     *  Asking it (rather than only "is a QA40x module on the class path?") is what
     *  keeps the {@code qa40x} capability token a promise: a build that ships the
     *  module on a host where {@code libusb} never loaded serves no QA40x, so it
     *  must not advertise one either. */
    private final List<AudioBackendType> served;

    /** Spec 4.6 {@code qa40x.info} (read-only): the identity and telemetry
     *  registers, as the strings the server formatted them into. */
    public JsonNode info() {
        Qa40xDeviceInfo device = control().readDeviceInfo();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.FIRMWARE_VERSION, device.firmwareVersion());
        data.put(NetFields.USB_VOLTAGE, device.usbVoltage());
        data.put(NetFields.USB_CURRENT, device.usbCurrent());
        data.put(NetFields.ISO_CURRENT, device.isoCurrent());
        data.put(NetFields.TEMPERATURE, device.temperature());
        data.put(NetFields.CAPABILITY, device.capability());
        data.put(NetFields.CAPABILITY2, device.capability2());
        data.put(NetFields.SERIAL_NUMBER, device.serialNumber());
        return codec.toNode(data);
    }

    /** Spec 4.6 {@code qa40x.ranges} (read-only): what the operator may choose
     *  from, and what is in force now. */
    public JsonNode ranges() {
        Qa40xControl device = control();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.INPUT_DBV, device.inputRangesDbv());
        data.put(NetFields.OUTPUT_DBV, device.outputRangesDbv());
        data.put(NetFields.ACTIVE_INPUT_DBV, device.activeInputRangeDbv());
        data.put(NetFields.ACTIVE_OUTPUT_DBV, device.activeOutputRangeDbv());
        return codec.toNode(data);
    }

    /**
     * Spec 4.6 {@code qa40x.setInputRange} / {@code qa40x.setOutputRange}: the
     * attenuator (or the output gain) moves, which restarts a running session on
     * the device itself.
     *
     * @throws NetException {@code BAD_REQUEST} when the field is missing or names
     *         a position this analyzer does not have - the client's mistake, and
     *         the code spec 4.2 gives it
     */
    public void setRange(boolean input, Integer dbv) {
        if (dbv == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a range change needs the " + NetFields.DBV + " field");
        }
        Qa40xControl device = control();
        try {
            if (input) {
                device.setInputRange(dbv);
            } else {
                device.setOutputRange(dbv);
            }
        } catch (IllegalArgumentException e) {
            throw new NetException(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new NetException(ErrorCode.DEVICE_ERROR, e.getMessage());
        }
    }

    /** Spec 4.6 {@code qa40x.calibration} (read-only): the device's own linear
     *  factors, so the client's {@code calibrationFromDevice} card pipeline works
     *  on a remote analyzer exactly as on an attached one. */
    public JsonNode calibration() {
        Qa40xControl device = control();
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put(NetFields.ADC, rows(device.calibration(true)));
            data.put(NetFields.DAC, rows(device.calibration(false)));
        } catch (IllegalStateException e) {
            throw new NetException(ErrorCode.DEVICE_ERROR, e.getMessage());
        }
        return codec.toNode(data);
    }

    /**
     * Spec 4.6 {@code qa40x.settings}: get when the request carries no
     * {@code i2sEnabled}, set when it does - and either way the answer is the
     * state that is now in force, so a client never has to guess whether its
     * write took.
     */
    public JsonNode settings(Boolean i2sEnabled) {
        Qa40xControl device = control();
        if (i2sEnabled != null) {
            device.setI2sEnabled(i2sEnabled);
        }
        return codec.toNode(Map.of(NetFields.I2S_ENABLED, device.isI2sEnabled()));
    }

    private List<Map<String, Object>> rows(List<Qa40xControl.CalibrationRow> page) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Qa40xControl.CalibrationRow row : page) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(NetFields.DBV, row.dbv());
            map.put(NetFields.LEFT, row.left());
            map.put(NetFields.RIGHT, row.right());
            maps.add(map);
        }
        return maps;
    }

    /**
     * The analyzer this server drives.
     *
     * @throws NetException {@code UNSUPPORTED} when this build serves no QA40x -
     *         the code spec 1 names for a message the peer does not implement,
     *         and the honest answer to a client that sent {@code qa40x.*} to a
     *         server that never advertised the {@code qa40x} capability
     */
    private Qa40xControl control() {
        AudioBackendType type = AudioBackendType.QA40X;
        if (!served.contains(type) || !audio.isAvailable(type)) {
            throw new NetException(ErrorCode.UNSUPPORTED,
                    "this server has no QA40x backend");
        }
        AudioDeviceManager manager = audio.manager(type);
        if (!(manager instanceof Qa40xControl device)) {
            throw new NetException(ErrorCode.UNSUPPORTED,
                    "this server's QA40x backend offers no analyzer control surface");
        }
        return device;
    }

    /**
     * Whether this server can serve spec 4.6 at all - what the {@code hello}
     * capability list is built from, so the {@code qa40x} token is a promise
     * about THIS server rather than about the protocol version.
     *
     * <p>It asks the same question {@link #control()} does and swallows the
     * refusal, because a capability list is not a place to fail: a server with no
     * analyzer backend simply does not claim the token, and the client then never
     * sends a message that would be refused.
     */
    public boolean isServed() {
        try {
            control();
            return true;
        } catch (NetException e) {
            return false;
        }
    }
}
