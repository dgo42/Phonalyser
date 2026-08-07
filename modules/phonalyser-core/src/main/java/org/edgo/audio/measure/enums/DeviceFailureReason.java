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

package org.edgo.audio.measure.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * WHY a device would not play - the machine-readable answer a backend gives
 * when its line refuses to open, so the operator can be told something true
 * instead of a driver code.
 *
 * <p><b>The rule this exists for.</b>  A raw native
 * code says the operator NOTHING.  "-9996" is not a reason; "the output device
 * does not answer" is.  The code itself keeps going to the log, every time -
 * it is what a developer needs and the only place it belongs.
 *
 * <p><b>Why it lives in phonalyser-core.</b>  The same reason has to reach the
 * operator whether the failing device is in this machine or on a bench across
 * the network, so it crosses the wire - and core is the one module every party
 * already shares: the local backends classify into it through the audio SPI,
 * and both wire sides (the desktop client through backend-net, the headless
 * server through server-net) carry the same constants across the network, which
 * is what lets a remote reason render exactly like a local one.
 *
 * <p><b>Who decides.</b>  The backend that owns the native error owns the
 * mapping: each one classifies ITS OWN codes through the audio SPI's
 * {@code AudioDeviceManager.classifyFailure(Throwable)}.  There is deliberately
 * no central parser of every driver's error text - a backend added later brings
 * its own vocabulary, and nothing above it has to learn it.  Anything a backend
 * does not recognise is {@link #UNKNOWN}, which is always a legal answer.
 *
 * <p><b>Why a key on the value.</b>  {@code CaptureEndReason} in the audio SPI
 * deliberately carries LOG English and no key, on the rule that i18n lives in
 * the GUI and nowhere below it.  That rule is about localized TEXT and the
 * machinery that produces it - and neither appears here: this is a constant
 * name, as machine-readable as the enum constant beside it, and nothing in this
 * module or below the GUI ever resolves it.  The sentence is still produced only
 * at the GUI's wording boundaries - {@code GeneratorController.localize} for a
 * lane that would not play, {@code SharedCapture} for one that would not record.
 * Keeping the pairing on the value is what stops those call sites from each
 * inventing their own mapping.
 */
public enum DeviceFailureReason {

    /** The device is gone - unplugged, or removed by the driver while the
     *  application still had it in its list. */
    DEVICE_DISCONNECTED("device.error.reason.disconnected"),

    /** The device is there and does not respond: the open timed out, or the
     *  driver accepted the call and never came back.  The csjsound render
     *  stall lands here. */
    DEVICE_NOT_ANSWERING("device.error.reason.notAnswering"),

    /** Another application (or another lane of this one) holds the device
     *  exclusively. */
    DEVICE_IN_USE("device.error.reason.inUse"),

    /** The configured device is not on this host at all - a card that was
     *  remembered from another machine, or a renamed one. */
    DEVICE_NOT_FOUND("device.error.reason.notFound"),

    /** The device exists and is free, but refuses the asked sample rate, bit
     *  depth or channel count. */
    FORMAT_UNSUPPORTED("device.error.reason.formatUnsupported"),

    /** The backend could not tell - the mandatory fallback, and never an
     *  error in itself.  The log line beside it carries the raw detail. */
    UNKNOWN("device.error.reason.unknown");

    /** The i18n key the GUI renders this value with.  A KEY, not a sentence:
     *  nothing below the GUI resolves it. */
    private final String i18nKey;

    private DeviceFailureReason(String i18nKey) {
        this.i18nKey = i18nKey;
    }

    public String i18nKey() {
        return i18nKey;
    }

    private static Map<String, DeviceFailureReason> valueSet = new HashMap<>();

    static {
        for (DeviceFailureReason reason : values()) {
            valueSet.put(reason.name(), reason);
        }
    }

    /** The value {@code name} names, or {@link #UNKNOWN} when it names nothing
     *  this build knows - the forward-compatible read for a value that arrived
     *  from ANOTHER process, where a newer peer may send a reason this build
     *  has never heard of (spec 1: an unknown value must never break a peer).
     *  Null and blank are UNKNOWN for the same reason. */
    public static DeviceFailureReason fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        if (valueSet.containsKey(name)) {
            return valueSet.get(name);
        }
        return UNKNOWN;
    }
}
