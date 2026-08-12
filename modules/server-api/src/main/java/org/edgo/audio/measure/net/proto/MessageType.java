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

package org.edgo.audio.measure.net.proto;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Every {@code t} discriminator the net control channel knows (spec 4.1 - 4.6),
 * with the exact wire text each one carries.  The Java name is the wire name in
 * upper snake case; the mapping is explicit rather than derived so a rename here
 * can never silently change the protocol.
 *
 * <p>{@link #UNKNOWN} is NOT a wire value: it is what {@link #fromWire} answers
 * for a type this build does not know, which lets a dispatcher switch over the
 * enum and reject the message with {@code UNSUPPORTED} in its default branch -
 * the forward-compatibility rule of spec 1.
 */
public enum MessageType {

    /* Session - spec 4.1. */
    HELLO("hello"),
    PING("ping"),
    BYE("bye"),

    /* Response envelope - spec 4.0. */
    RESP("resp"),

    /* Devices and locks - spec 4.3. */
    BACKEND_LIST("backend.list"),
    BACKEND_SELECT("backend.select"),
    DEVICES_LIST("devices.list"),
    DEVICE_ACQUIRE("device.acquire"),
    DEVICE_RELEASE("device.release"),
    DEVICE_SET_CALIBRATION("device.setCalibration"),
    DEVICE_SET_CARD("device.setCard"),
    DEVICE_SET_ACTIVE_RANGE("device.setActiveRange"),
    CARDS_LIST("cards.list"),
    CARDS_PUT("cards.put"),
    EV_DEVICES_CHANGED("ev.devices.changed"),
    EV_DEVICE_ERROR("ev.device.error"),

    /* Capture streaming - spec 4.4. */
    CAPTURE_OPEN("capture.open"),
    CAPTURE_START("capture.start"),
    CAPTURE_STOP("capture.stop"),
    CAPTURE_CLOSE("capture.close"),

    /** The only message a DATA connection ever sends (spec 4.7): it is that
     *  connection's first message, which is what MAKES it a data connection,
     *  and its last.  Everything else on that socket is audio going the other
     *  way. */
    CAPTURE_ATTACH("capture.attach"),

    /* Remote generator - spec 4.5. */
    GEN_OPEN("gen.open"),
    GEN_CONFIG("gen.config"),
    GEN_START("gen.start"),
    GEN_STOP("gen.stop"),
    GEN_FFT_GRID("gen.fftGrid"),
    GEN_TRIM("gen.trim"),
    GEN_TRIM2("gen.trim2"),
    GEN_TRIM_RESET("gen.trimReset"),
    GEN_STATE("gen.state"),
    GEN_PLAY_FILE("gen.playFile"),
    GEN_STOP_FILE("gen.stopFile"),
    GEN_CLOSE("gen.close"),
    EV_GEN_STATE("ev.gen.state"),

    /* QA40x extension - spec 4.6. */
    QA40X_INFO("qa40x.info"),
    QA40X_RANGES("qa40x.ranges"),
    QA40X_SET_INPUT_RANGE("qa40x.setInputRange"),
    QA40X_SET_OUTPUT_RANGE("qa40x.setOutputRange"),
    QA40X_CALIBRATION("qa40x.calibration"),
    QA40X_SETTINGS("qa40x.settings"),

    /** Not a wire value - the answer for an unrecognised {@code t}. */
    UNKNOWN("");

    private static final Map<String, MessageType> BY_WIRE = new HashMap<>();

    static {
        for (MessageType type : values()) {
            BY_WIRE.put(type.wire, type);
        }
    }

    /** The exact {@code t} text on the wire. */
    @Getter
    private final String wire;

    private MessageType(String wire) {
        this.wire = wire;
    }

    /** Never throws: an unrecognised or missing {@code t} maps to
     *  {@link #UNKNOWN}, which the dispatcher answers with {@code UNSUPPORTED}. */
    public static MessageType fromWire(String wire) {
        MessageType type = wire == null ? null : BY_WIRE.get(wire);
        return type == null ? UNKNOWN : type;
    }
}
