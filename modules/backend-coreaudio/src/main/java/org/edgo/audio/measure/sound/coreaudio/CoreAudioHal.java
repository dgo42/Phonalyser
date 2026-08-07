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

package org.edgo.audio.measure.sound.coreaudio;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import lombok.extern.log4j.Log4j2;

/**
 * What the CoreAudio HAL says a device can REALLY do - the macOS sibling of the
 * Linux {@code ProcAsound} reader.
 *
 * <p>Neither PortAudio's {@code Pa_IsFormatSupported} nor JavaSound's
 * open-and-test can answer this on macOS: both go through a converting layer
 * (AUHAL) that resamples silently, so a probe says "yes" to nearly every rate
 * and the published list is the converter's, not the hardware's.  The HAL
 * itself, however, exposes exactly what the device enumerated:
 *
 * <ul>
 *   <li>{@code kAudioStreamPropertyAvailablePhysicalFormats} on each of the
 *       device's streams - the (rate, bit width) pairs on the WIRE, per
 *       direction.  This is the primary source: for a USB interface these are
 *       the discrete formats from its own descriptors.</li>
 *   <li>{@code kAudioDevicePropertyAvailableNominalSampleRates} on the device -
 *       the clock rates the driver declares.  Used to resolve a physical
 *       format published with {@code kAudioStreamAnyRate}: such a format
 *       carries a min..max range instead of one rate, and the nominal list
 *       says which discrete rates inside that range exist.</li>
 * </ul>
 *
 * <p><b>"CANNOT ANSWER" IS EMPTY, NEVER A GUESS.</b>  A device the HAL does not
 * know under the asked name, a host without the framework, or a failed property
 * read all answer an empty list - the same deliberate honesty as the Linux
 * legacy-card case.  No candidate list, no probe through the converter: what
 * this class reports is device truth or nothing.
 *
 * <p>Answers are read fresh on every call - the HAL query is a handful of
 * in-process property reads, no device open - so a re-plugged or reconfigured
 * device is never answered from a stale snapshot.
 *
 * <p>The parsing is separated from the native calls (package-private methods
 * taking raw property buffers) so tests can drive it with fabricated buffers on
 * any OS.
 */
@Log4j2
public final class CoreAudioHal {

    /** One (rate, bit width) pair a device stream declares on the wire. */
    public record PhysicalFormat(int rate, int bits) implements Comparable<PhysicalFormat> {
        @Override
        public int compareTo(PhysicalFormat other) {
            int byRate = Integer.compare(rate, other.rate);
            return byRate != 0 ? byRate : Integer.compare(bits, other.bits);
        }
    }

    /** {@code AudioObjectPropertyAddress} - selector/scope/element triple every
     *  HAL property read is keyed on. */
    public static class PropertyAddress extends Structure {
        public int selector;
        public int scope;
        public int element;

        public PropertyAddress(int selector, int scope, int element) {
            this.selector = selector;
            this.scope    = scope;
            this.element  = element;
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("selector", "scope", "element");
        }
    }

    /** The {@code AudioObject} property calls everything here is built on
     *  (CoreAudio.framework, in-process, no device open).  Two reads and the one
     *  write - the volume pin of {@link #pinVolumeToUnity}, which is the only
     *  thing this class ever changes about a device. */
    public interface Lib extends Library {
        int AudioObjectGetPropertyDataSize(int objectId, PropertyAddress address,
                int qualifierSize, Pointer qualifier, IntByReference size);
        int AudioObjectGetPropertyData(int objectId, PropertyAddress address,
                int qualifierSize, Pointer qualifier, IntByReference size, Pointer data);
        int AudioObjectSetPropertyData(int objectId, PropertyAddress address,
                int qualifierSize, Pointer qualifier, int dataSize, Pointer data);
    }

    // FourCC selectors and scopes from CoreAudio/AudioHardware.h.
    private static final int SYSTEM_OBJECT       = 1;          // kAudioObjectSystemObject
    private static final int PROP_DEVICES        = 0x64657623; // 'dev#' kAudioHardwarePropertyDevices
    private static final int PROP_DEVICE_NAME    = 0x6E616D65; // 'name' kAudioDevicePropertyDeviceName
    private static final int PROP_NOMINAL_RATES  = 0x6E737223; // 'nsr#' kAudioDevicePropertyAvailableNominalSampleRates
    private static final int PROP_STREAMS        = 0x73746D23; // 'stm#' kAudioDevicePropertyStreams
    private static final int PROP_PHYSICAL_FORMATS = 0x70667423; // 'pft#' kAudioStreamPropertyAvailablePhysicalFormats
    private static final int PROP_VOLUME_SCALAR  = 0x766F6C6D; // 'volm' kAudioDevicePropertyVolumeScalar
    private static final int PROP_VOLUME_DB_TO_SCALAR = 0x64623276; // 'db2v' kAudioDevicePropertyVolumeDecibelsToScalar
    private static final int PROP_ACTIVE_SUB_DEVICES = 0x61677270; // 'agrp' kAudioAggregateDevicePropertyActiveSubDeviceList
    private static final int SCOPE_GLOBAL        = 0x676C6F62; // 'glob'
    private static final int SCOPE_INPUT         = 0x696E7074; // 'inpt'
    private static final int SCOPE_OUTPUT        = 0x6F757470; // 'outp'
    private static final int FORMAT_LINEAR_PCM   = 0x6C70636D; // 'lpcm'
    private static final int ELEMENT_MAIN        = 0;
    private static final int NO_ERROR            = 0;

    /** {@code AudioValueRange} - two Float64, min then max. */
    private static final int VALUE_RANGE_BYTES = 16;
    /** {@code AudioStreamRangedDescription} - a 40-byte
     *  {@code AudioStreamBasicDescription} followed by an
     *  {@code AudioValueRange}. */
    private static final int RANGED_DESC_BYTES = 56;
    private static final int DESC_SAMPLE_RATE  = 0;   // Float64
    private static final int DESC_FORMAT_ID    = 8;   // UInt32
    private static final int DESC_BITS         = 32;  // UInt32 mBitsPerChannel
    private static final int DESC_RANGE_MIN    = 40;  // Float64 (the trailing AudioValueRange)
    private static final int DESC_RANGE_MAX    = 48;  // Float64
    /** {@code kAudioStreamAnyRate}: the format's own rate field is 0 and the
     *  trailing range says which rates apply. */
    private static final double ANY_RATE = 0.0;

    private static final int NAME_BYTES = 256;

    /** Where a device's volume scalar can live: the device's MAIN control first,
     *  then the two channels of the stereo lane this application opens.  A device
     *  publishes the property on some of these and not others - many
     *  professional interfaces publish it nowhere at all, which is a fixed unity
     *  gain and exactly what is wanted. */
    private static final int[] VOLUME_ELEMENTS = {ELEMENT_MAIN, 1, 2};
    /** The one level a calibrated measurement chain may run at: the device's own
     *  0 dB point.  NOT the scalar maximum - on a capture side the scalar
     *  ceiling is the top of the device's GAIN range (a bench interface's input
     *  read +12 dB there), and only the dB scale says where unity really is. */
    private static final float TARGET_DB = 0.0f;
    /** What a control with NO dB translation is set to instead: full scale,
     *  which on the devices that publish no scale is a fixed output level. */
    private static final float FULL_SCALE_VOLUME = 1.0f;
    /** Two Float32 scalars closer than this are the same volume - a driver's own
     *  arithmetic does not land on the exact target every time. */
    private static final float VOLUME_EPSILON = 1e-4f;

    /** Two nominal doubles closer than this are one rate - covers the 44100.0
     *  vs 44099.99... a driver's Float64 arithmetic can produce. */
    private static final double RATE_EPSILON = 1.0;

    /** Loaded on first use; a host without the framework (every non-mac) fails
     *  once, is remembered, and answers empty from then on. */
    private volatile Lib lib;
    private volatile boolean loadFailed;

    /** What each open's pin actually moved, per device and direction, so the
     *  close can put it back: the volume belongs to the operator between opens,
     *  and a measurement that borrowed it returns it as found. */
    private final Map<String, List<SavedVolume>> restorable = new ConcurrentHashMap<>();

    /** One control as it stood before the pin moved it - on the device that
     *  really carries it, which for an aggregate is a MEMBER, not the
     *  aggregate itself. */
    private record SavedVolume(int device, int element, float scalar) {}

    public CoreAudioHal() {}

    /** The property calls injected, so a test can drive this reader with
     *  fabricated buffers on a host that has no CoreAudio at all - the same
     *  reason the parsing below is separated from the native calls. */
    CoreAudioHal(Lib lib) {
        this.lib = lib;
    }

    /**
     * The (rate, bits) pairs the named device declares for one direction, in
     * ascending (rate, bits) order - or empty when the HAL cannot answer.
     */
    public List<PhysicalFormat> physicalFormats(String deviceName, boolean output) {
        Lib hal = lib();
        if (hal == null) {
            return List.of();
        }
        try {
            int device = deviceByName(hal, deviceName, output);
            if (device == 0) {
                if (log.isWarnEnabled()) {
                    log.warn("CoreAudio HAL knows no {} device named '{}' - reporting no formats",
                            output ? "output" : "input", deviceName);
                }
                return List.of();
            }
            int[] nominal = nominalRates(hal, device);
            TreeSet<PhysicalFormat> found = new TreeSet<>();
            for (int stream : intArray(read(hal, device, PROP_STREAMS, scope(output)))) {
                Prop formats = read(hal, stream, PROP_PHYSICAL_FORMATS, SCOPE_GLOBAL);
                if (formats != null) {
                    addFormats(formats.data(), formats.bytes(), nominal, found);
                }
            }
            if (found.isEmpty() && log.isWarnEnabled()) {
                log.warn("CoreAudio HAL lists no linear-PCM physical formats for {} device '{}'",
                        output ? "output" : "input", deviceName);
            }
            return List.copyOf(found);
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("CoreAudio HAL read failed for device '{}': {}", deviceName, e.toString());
            }
            return List.of();
        }
    }

    /**
     * Every device the HAL knows RIGHT NOW, as {@code <AudioDeviceID> <name>} -
     * the live truth a PortAudio device snapshot can be checked against.
     *
     * <p>PortAudio enumerates ONCE, inside {@code Pa_Initialize}, and answers
     * from that snapshot for the rest of the process's life; the HAL is read
     * fresh on every call.  So this set changing under a snapshot is the signal -
     * and on a headless macOS host the only signal there is - that a device was
     * plugged in or pulled out since the snapshot was taken.
     *
     * <p><b>The object ID is in there, not just the name.</b>  A device that is
     * unplugged and plugged back in comes back under exactly the name it had, so
     * a set of names alone is unchanged across a quick replug - while PortAudio's
     * entry for it is dead all the same, because the HAL destroyed that audio
     * object and built a NEW one.  The ID is what says so.
     *
     * <p>Empty is "cannot answer", never "no devices", exactly as it is for
     * {@link #physicalFormats}: a host without the framework, or a failed
     * property read, says the honest nothing.  A caller must not read it as an
     * empty machine.
     */
    public Set<String> deviceIdentities() {
        Lib hal = lib();
        if (hal == null) {
            return Set.of();
        }
        try {
            Set<String> devices = new LinkedHashSet<>();
            for (int device : intArray(read(hal, SYSTEM_OBJECT, PROP_DEVICES, SCOPE_GLOBAL))) {
                String name = nameOf(hal, device);
                if (name != null && !name.isEmpty()) {
                    devices.add(device + " " + name);
                }
            }
            return devices;
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("CoreAudio HAL device list read failed: {}", e.toString());
            }
            return Set.of();
        }
    }

    /**
     * Pins one device's volume to the device's own 0 dB for one direction, as
     * that device is opened.
     *
     * <p><b>Why a measurement host does this at all.</b>  A mixer control sitting
     * anywhere but unity multiplies everything that passes through it, and it is
     * INVISIBLE to the calibration: the full scale a card was calibrated at was
     * measured through whatever the control stood at then, so a slider somebody
     * moved (or an OS that restored it from a previous session, or a keyboard
     * volume key) silently rescales every volt this application reports.  The
     * only honest position in a calibrated chain is the device's 0 dB, and the
     * moment to make sure of it is the open.
     *
     * <p><b>0 dB is found through the device's own dB scale</b>
     * ({@code kAudioDevicePropertyVolumeDecibelsToScalar}), never assumed to be
     * the scalar maximum: on a capture side the scalar ceiling is the top of
     * the GAIN range - a bench interface's input read +12 dB at scalar 1.0 -
     * and only the translation says where unity really is.  A control that
     * publishes no translation is set to full scale, which is where an output's
     * 0 dB conventionally sits.
     *
     * <p><b>No control is not a failure.</b>  Most professional interfaces
     * publish no volume property at all - their gain is fixed in hardware, which
     * is precisely the wanted state - so a device that does not answer the
     * property is passed over in silence.  A control that is ALREADY at the
     * target is likewise left alone and says nothing: only a volume actually
     * moved is worth a line in the log, and that line is at INFO because it
     * changed the machine.
     *
     * <p><b>It never stops an open.</b>  A device that refuses the write is
     * reported at WARN - with what it stayed at, because that number is now a
     * factor in every level measured through it - and the open goes ahead: a
     * measurement at a wrong level is worth more to an operator than no
     * measurement and an exception.
     *
     * <p>Strictly this device and this direction.  The volume of anything else
     * on the machine is somebody else's business, and a host that walked the
     * machine's devices "to be safe" would be changing the state of hardware
     * nobody asked it to touch.
     */
    public void pinVolumeToUnity(String deviceName, boolean output) {
        Lib hal = lib();
        if (hal == null) {
            return;
        }
        try {
            int device = deviceByName(hal, deviceName, output);
            if (device == 0) {
                return;               // the HAL has no such device - nothing to pin
            }
            List<SavedVolume> moved = new ArrayList<>();
            int[] members = aggregateMembers(hal, device);
            if (members.length > 0) {
                // An aggregate publishes no volume controls of its own - the
                // real controls live on its members, so that is where the
                // calibrated chain's 0 dB has to be written.
                for (int member : members) {
                    for (int element : VOLUME_ELEMENTS) {
                        pinElementToZeroDb(hal, member,
                                deviceName + " member " + member, output, element, moved);
                    }
                }
            } else {
                for (int element : VOLUME_ELEMENTS) {
                    pinElementToZeroDb(hal, device, deviceName, output, element, moved);
                }
            }
            if (!moved.isEmpty()) {
                restorable.put(volumeKey(deviceName, output), moved);
            }
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("CoreAudio volume pin failed for device '{}': {} - the open goes "
                        + "ahead, but the device's own volume is whatever it was",
                        deviceName, e.toString());
            }
        }
    }

    /**
     * Puts back what this device and direction's pin moved, as the stream that
     * borrowed the volume closes - however it closes.  The volume belongs to
     * the operator between opens; only what the pin itself changed is written,
     * so a slider the operator moved while the stream ran keeps the operator's
     * value.  Nothing was moved (or the pin never ran): nothing is written and
     * nothing is said.  A device already unplugged has nothing to restore on -
     * the remembered values are dropped without a word.
     */
    public void restoreVolume(String deviceName, boolean output) {
        Lib hal = lib();
        if (hal == null) {
            return;
        }
        List<SavedVolume> moved = restorable.remove(volumeKey(deviceName, output));
        if (moved == null) {
            return;
        }
        try {
            int device = deviceByName(hal, deviceName, output);
            if (device == 0) {
                return;               // unplugged: the values died with the device
            }
            for (SavedVolume control : moved) {
                Memory value = new Memory(Float.BYTES);
                value.setFloat(0, control.scalar());
                PropertyAddress address =
                        new PropertyAddress(PROP_VOLUME_SCALAR, scope(output), control.element());
                int rc = hal.AudioObjectSetPropertyData(control.device(), address, 0, Pointer.NULL,
                        Float.BYTES, value);
                if (rc != NO_ERROR) {
                    if (log.isWarnEnabled()) {
                        log.warn("CoreAudio {} device '{}' refused the volume restore on {} "
                                + "(rc={})", output ? "output" : "input", deviceName,
                                control(control.element()), rc);
                    }
                    continue;
                }
                if (log.isInfoEnabled()) {
                    log.info("CoreAudio {} device '{}': volume on {} restored to {}",
                            output ? "output" : "input", deviceName,
                            control(control.element()), control.scalar());
                }
            }
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("CoreAudio volume restore failed for device '{}': {}",
                        deviceName, e.toString());
            }
        }
    }

    private String volumeKey(String deviceName, boolean output) {
        return deviceName + (output ? "|output" : "|input");
    }

    /** The ACTIVE member devices of an aggregate, or an empty array for an
     *  ordinary device (which does not answer the property) and for an
     *  aggregate nobody is left behind. */
    private int[] aggregateMembers(Lib hal, int device) {
        return intArray(read(hal, device, PROP_ACTIVE_SUB_DEVICES, SCOPE_GLOBAL));
    }

    /**
     * Whether a device of this name, with streams in this direction, is still
     * on the machine - by the live HAL, which sees an unplug the PortAudio
     * snapshot never can.  {@code true} whenever the HAL cannot answer (no
     * framework, an empty or failed device-list read): "do not know" must
     * never kill a healthy stream.
     *
     * <p><b>An aggregate device is present only through its members.</b>  The
     * aggregate itself survives its sub-devices at OS level - pull the one
     * card behind it and the name stays on the machine while nothing is left
     * to move samples - so a device that answers the active-sub-device-list
     * property with an empty list is gone, whatever its name says.
     */
    public boolean devicePresent(String deviceName, boolean output) {
        Lib hal = lib();
        if (hal == null) {
            return true;
        }
        try {
            int[] devices = intArray(read(hal, SYSTEM_OBJECT, PROP_DEVICES, SCOPE_GLOBAL));
            if (devices.length == 0) {
                return true;          // cannot answer - not "everything vanished"
            }
            int device = deviceByName(hal, deviceName, output);
            if (device == 0) {
                return false;
            }
            // Asked as a SIZE probe, not through read(): an aggregate whose
            // members all left answers the property with zero bytes, and the
            // generic reader folds that into "no such property" - which is
            // what an ordinary device answers, and means present.
            PropertyAddress address =
                    new PropertyAddress(PROP_ACTIVE_SUB_DEVICES, SCOPE_GLOBAL, ELEMENT_MAIN);
            IntByReference size = new IntByReference();
            int rc = hal.AudioObjectGetPropertyDataSize(device, address, 0, Pointer.NULL, size);
            if (rc == NO_ERROR && size.getValue() < Integer.BYTES) {
                return false;         // an aggregate nobody is left behind
            }
            return true;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** One volume control, pinned to the device's 0 dB - see
     *  {@link #pinVolumeToUnity}.  A write that lands is remembered in
     *  {@code moved}, with the scalar the control stood at, so the close can
     *  put it back. */
    private void pinElementToZeroDb(Lib hal, int device, String deviceName, boolean output,
            int element, List<SavedVolume> moved) {
        Prop current = read(hal, device, PROP_VOLUME_SCALAR, scope(output), element);
        if (current == null || current.bytes() < Float.BYTES) {
            return;                   // no control here: fixed gain already
        }
        float was = current.data().getFloat(0);
        Float zeroDb = zeroDbScalar(hal, device, output, element);
        float target = (zeroDb != null) ? zeroDb : FULL_SCALE_VOLUME;
        if (Math.abs(was - target) <= VOLUME_EPSILON) {
            return;                   // already there: nothing moved, nothing to say
        }
        Memory value = new Memory(Float.BYTES);
        value.setFloat(0, target);
        PropertyAddress address =
                new PropertyAddress(PROP_VOLUME_SCALAR, scope(output), element);
        int rc = hal.AudioObjectSetPropertyData(device, address, 0, Pointer.NULL,
                Float.BYTES, value);
        if (rc != NO_ERROR) {
            if (log.isWarnEnabled()) {
                log.warn("CoreAudio {} device '{}' refused the 0 dB volume on {} (rc={}) - it "
                        + "stays at {}, and every level measured through it is scaled by "
                        + "that", output ? "output" : "input", deviceName, control(element),
                        rc, was);
            }
            return;
        }
        moved.add(new SavedVolume(device, element, was));
        if (log.isInfoEnabled()) {
            log.info("CoreAudio {} device '{}': volume on {} pinned {} -> {} ({}) for the "
                    + "measurement chain", output ? "output" : "input", deviceName,
                    control(element), was, target,
                    zeroDb != null ? "the device's 0 dB" : "full scale, no dB translation");
        }
    }

    /** The scalar position of the device's own 0 dB for one control, read
     *  through the HAL's dB-to-scalar translation - null when the control
     *  publishes none, which is what separates "0 dB lives at this position"
     *  from "this control has no dB scale at all". */
    private Float zeroDbScalar(Lib hal, int device, boolean output, int element) {
        PropertyAddress address =
                new PropertyAddress(PROP_VOLUME_DB_TO_SCALAR, scope(output), element);
        Memory value = new Memory(Float.BYTES);
        value.setFloat(0, TARGET_DB);
        IntByReference size = new IntByReference(Float.BYTES);
        int rc = hal.AudioObjectGetPropertyData(device, address, 0, Pointer.NULL, size, value);
        if (rc != NO_ERROR || size.getValue() < Float.BYTES) {
            return null;
        }
        return value.getFloat(0);
    }

    private String control(int element) {
        return element == ELEMENT_MAIN ? "the main control" : "channel " + element;
    }

    /**
     * The discrete nominal clock rates the device declares, ascending.  A
     * ranged entry (min &lt; max) contributes its two endpoints - they are the
     * two rates the driver actually wrote down; nothing in between is
     * invented.
     */
    int[] discreteRates(Memory ranges, int bytes) {
        TreeSet<Integer> rates = new TreeSet<>();
        for (int off = 0; off + VALUE_RANGE_BYTES <= bytes; off += VALUE_RANGE_BYTES) {
            double min = ranges.getDouble(off);
            double max = ranges.getDouble(off + 8);
            if (min <= 0) {
                continue;
            }
            rates.add((int) Math.round(min));
            if (max - min >= RATE_EPSILON) {
                rates.add((int) Math.round(max));
            }
        }
        return rates.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Reads one stream's {@code AudioStreamRangedDescription} array into
     * {@code into}: linear-PCM entries only, one pair per discrete rate.  An
     * any-rate entry is resolved against {@code nominalRates} - the device's
     * own clock list - restricted to the entry's declared min..max range.
     */
    void addFormats(Memory formats, int bytes, int[] nominalRates, TreeSet<PhysicalFormat> into) {
        for (int off = 0; off + RANGED_DESC_BYTES <= bytes; off += RANGED_DESC_BYTES) {
            if (formats.getInt(off + DESC_FORMAT_ID) != FORMAT_LINEAR_PCM) {
                continue;
            }
            int bits = formats.getInt(off + DESC_BITS);
            if (bits <= 0) {
                continue;
            }
            double rate = formats.getDouble(off + DESC_SAMPLE_RATE);
            if (rate > ANY_RATE) {
                into.add(new PhysicalFormat((int) Math.round(rate), bits));
                continue;
            }
            double min = formats.getDouble(off + DESC_RANGE_MIN);
            double max = formats.getDouble(off + DESC_RANGE_MAX);
            for (int nominal : nominalRates) {
                if (nominal >= min - RATE_EPSILON && nominal <= max + RATE_EPSILON) {
                    into.add(new PhysicalFormat(nominal, bits));
                }
            }
        }
    }

    /**
     * The {@code AudioDeviceID} published under {@code deviceName}, preferring
     * a device that has streams in the asked direction (macOS lists a USB
     * interface's input and output halves as separate devices under one name);
     * 0 when no device of that name exists.
     */
    private int deviceByName(Lib hal, String deviceName, boolean output) {
        int nameOnly = 0;
        for (int device : intArray(read(hal, SYSTEM_OBJECT, PROP_DEVICES, SCOPE_GLOBAL))) {
            if (!deviceName.equals(nameOf(hal, device))) {
                continue;
            }
            if (intArray(read(hal, device, PROP_STREAMS, scope(output))).length > 0) {
                return device;
            }
            if (nameOnly == 0) {
                nameOnly = device;
            }
        }
        return nameOnly;
    }

    /** One device's published name, trimmed - null when it publishes none.  Read
     *  by both the by-name lookup and the whole-list read, so the two can never
     *  disagree on what a device is CALLED. */
    private String nameOf(Lib hal, int device) {
        Prop name = read(hal, device, PROP_DEVICE_NAME, SCOPE_GLOBAL);
        return name == null ? null : name.data().getString(0).trim();
    }

    private int[] nominalRates(Lib hal, int device) {
        Prop ranges = read(hal, device, PROP_NOMINAL_RATES, SCOPE_GLOBAL);
        return ranges == null ? new int[0] : discreteRates(ranges.data(), ranges.bytes());
    }

    private int scope(boolean output) {
        return output ? SCOPE_OUTPUT : SCOPE_INPUT;
    }

    /** One property of the object's MAIN element - the element everything but
     *  the per-channel volume controls lives on. */
    private Prop read(Lib hal, int objectId, int selector, int scope) {
        return read(hal, objectId, selector, scope, ELEMENT_MAIN);
    }

    /** One property, read whole - null when the object refuses or has none. */
    private Prop read(Lib hal, int objectId, int selector, int scope, int element) {
        PropertyAddress address = new PropertyAddress(selector, scope, element);
        IntByReference size = new IntByReference();
        int rc = hal.AudioObjectGetPropertyDataSize(objectId, address, 0, Pointer.NULL, size);
        if (rc != NO_ERROR || size.getValue() <= 0) {
            return null;
        }
        Memory data = new Memory(Math.max(size.getValue(), NAME_BYTES));
        rc = hal.AudioObjectGetPropertyData(objectId, address, 0, Pointer.NULL, size, data);
        if (rc != NO_ERROR) {
            return null;
        }
        return new Prop(data, size.getValue());
    }

    private int[] intArray(Prop prop) {
        if (prop == null) {
            return new int[0];
        }
        return prop.data().getIntArray(0, prop.bytes() / 4);
    }

    private record Prop(Memory data, int bytes) {}

    private Lib lib() {
        Lib loaded = lib;
        if (loaded != null || loadFailed) {
            return loaded;
        }
        synchronized (this) {
            if (lib == null && !loadFailed) {
                try {
                    lib = Native.load("CoreAudio", Lib.class);
                } catch (UnsatisfiedLinkError e) {
                    loadFailed = true;
                    if (log.isWarnEnabled()) {
                        log.warn("CoreAudio framework not loadable ({}) - "
                                + "device capabilities unavailable", e.getMessage());
                    }
                }
            }
            return lib;
        }
    }
}
