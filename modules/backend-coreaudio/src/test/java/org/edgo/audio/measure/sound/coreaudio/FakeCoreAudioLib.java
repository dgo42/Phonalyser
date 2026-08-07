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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * A macOS that is not there: the {@code AudioObject} property calls answered out
 * of a list of devices and their volume controls, with nothing native behind it.
 *
 * <p>It speaks the HAL's own wire layout - an {@code AudioDeviceID} array for the
 * system object's device list, a NUL-terminated C string for a device's name, a
 * Float32 for a volume scalar - so what the tests drive is the production reader,
 * on any OS, with no framework and no bench.
 *
 * <p><b>A device's id is never re-used.</b>  Plugging one back in makes a NEW
 * audio object under the same name, exactly as the HAL does, because that is the
 * difference a hot-plug check has to be able to see.
 *
 * <p><b>A volume control exists only where a test puts one.</b>  Most
 * professional interfaces publish the property nowhere at all - their gain is
 * fixed - and answering "no such property" is how that case reaches the code
 * under test.  A control's dB-to-scalar translation is likewise its own entry:
 * a control may have a scalar and no dB scale, which is exactly the case the
 * full-scale fallback exists for.
 */
final class FakeCoreAudioLib implements CoreAudioHal.Lib {

    private static final int SYSTEM_OBJECT = 1;
    private static final int PROP_DEVICES = 0x64657623;       // 'dev#'
    private static final int PROP_DEVICE_NAME = 0x6E616D65;   // 'name'
    private static final int PROP_VOLUME_SCALAR = 0x766F6C6D; // 'volm'
    private static final int PROP_VOLUME_DB_TO_SCALAR = 0x64623276; // 'db2v'
    private static final int PROP_ACTIVE_SUB_DEVICES = 0x61677270; // 'agrp'
    private static final int SCOPE_INPUT = 0x696E7074;        // 'inpt'
    private static final int SCOPE_OUTPUT = 0x6F757470;       // 'outp'
    private static final int OK = 0;
    /** Any non-zero: the object has no such property, which is how the reader
     *  learns to answer its honest nothing. */
    private static final int NO_SUCH_PROPERTY = -1;
    /** What a device that will not be told answers - the driver said no. */
    private static final int REFUSED = -2;

    /** One device on this machine. */
    private record Device(int id, String name) {}

    /** One volume control: whose, which direction, which element. */
    private record Control(int device, int scope, int element) {}

    private final List<Device> devices = new ArrayList<>();
    private final Map<Control, Float> volumes = new LinkedHashMap<>();
    /** Where each control's own 0 dB sits on its scalar - absent for a control
     *  that publishes no dB scale. */
    private final Map<Control, Float> zeroDbScalars = new LinkedHashMap<>();
    /** How many ACTIVE sub-devices each aggregate device answers - absent for
     *  an ordinary device, which does not answer the property at all. */
    private final Map<Integer, Integer> activeSubDevices = new LinkedHashMap<>();
    /** The REAL member devices of an aggregate, for the tests that pin and
     *  restore volumes through it - takes precedence over the bare count. */
    private final Map<Integer, int[]> aggregateMembers = new LinkedHashMap<>();
    /** Ids start at 1, so 0 - which the reader treats as "no such device" - is
     *  never a real one. */
    private int nextId = 1;
    private int volumeWrites;
    private boolean refuseVolumeWrites;

    FakeCoreAudioLib(String... names) {
        for (String name : names) {
            plug(name);
        }
    }

    void plug(String name) {
        devices.add(new Device(nextId++, name));
    }

    /** Pulls the device out: its audio object is gone, and so is every control
     *  on it. */
    void unplug(String name) {
        int device = idOf(name);
        devices.removeIf(candidate -> candidate.name().equals(name));
        volumes.keySet().removeIf(control -> control.device() == device);
        zeroDbScalars.keySet().removeIf(control -> control.device() == device);
    }

    /** Gives a device a volume control at {@code scalar} - a device with none is
     *  fixed in hardware and answers no such property. */
    void volumeControl(String deviceName, boolean output, int element, float scalar) {
        volumes.put(new Control(idOf(deviceName), scope(output), element), scalar);
    }

    /** Declares where the control's own 0 dB sits on its scalar - the dB-to-
     *  scalar translation the pin asks for.  A control without this entry
     *  publishes no dB scale at all. */
    void zeroDbAt(String deviceName, boolean output, int element, float scalar) {
        zeroDbScalars.put(new Control(idOf(deviceName), scope(output), element), scalar);
    }

    /** Makes the device an AGGREGATE answering this many active sub-devices -
     *  zero being the aggregate whose one card was pulled: the name survives,
     *  nobody is left behind it. */
    void activeSubDevices(String deviceName, int count) {
        activeSubDevices.put(idOf(deviceName), count);
    }

    /** Makes the device an AGGREGATE of these already-plugged member devices -
     *  the members are real audio objects whose controls a pin can reach. */
    void aggregateOf(String deviceName, String... memberNames) {
        int[] ids = new int[memberNames.length];
        for (int i = 0; i < memberNames.length; i++) {
            ids[i] = idOf(memberNames[i]);
        }
        aggregateMembers.put(idOf(deviceName), ids);
    }

    /** What the control stands at now - what a test reads back after a pin. */
    float volume(String deviceName, boolean output, int element) {
        return volumes.get(new Control(idOf(deviceName), scope(output), element));
    }

    /** How many volume writes reached the machine, over everything. */
    int volumeWrites() {
        return volumeWrites;
    }

    /** From here on the machine refuses every volume write. */
    void refuseVolumeWrites() {
        refuseVolumeWrites = true;
    }

    @Override
    public int AudioObjectGetPropertyDataSize(int objectId, CoreAudioHal.PropertyAddress address,
            int qualifierSize, Pointer qualifier, IntByReference size) {
        byte[] payload = payload(objectId, address);
        if (payload == null) {
            return NO_SUCH_PROPERTY;
        }
        size.setValue(payload.length);
        return OK;
    }

    @Override
    public int AudioObjectGetPropertyData(int objectId, CoreAudioHal.PropertyAddress address,
            int qualifierSize, Pointer qualifier, IntByReference size, Pointer data) {
        byte[] payload = payload(objectId, address);
        if (payload == null) {
            return NO_SUCH_PROPERTY;
        }
        data.write(0, payload, 0, payload.length);
        return OK;
    }

    @Override
    public int AudioObjectSetPropertyData(int objectId, CoreAudioHal.PropertyAddress address,
            int qualifierSize, Pointer qualifier, int dataSize, Pointer data) {
        Control control = new Control(objectId, address.scope, address.element);
        if (address.selector != PROP_VOLUME_SCALAR || !volumes.containsKey(control)) {
            return NO_SUCH_PROPERTY;
        }
        if (refuseVolumeWrites) {
            return REFUSED;
        }
        volumes.put(control, data.getFloat(0));
        volumeWrites++;
        return OK;
    }

    /** What one property read answers, or null when this object has no such
     *  property. */
    private byte[] payload(int objectId, CoreAudioHal.PropertyAddress address) {
        if (objectId == SYSTEM_OBJECT && address.selector == PROP_DEVICES) {
            ByteBuffer ids = ByteBuffer.allocate(devices.size() * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            for (Device device : devices) {
                ids.putInt(device.id());
            }
            return ids.array();
        }
        if (address.selector == PROP_DEVICE_NAME) {
            for (Device device : devices) {
                if (device.id() == objectId) {
                    return cString(device.name());
                }
            }
            return null;
        }
        if (address.selector == PROP_VOLUME_SCALAR) {
            Float scalar = volumes.get(new Control(objectId, address.scope, address.element));
            return scalar == null ? null : floatBytes(scalar);
        }
        if (address.selector == PROP_VOLUME_DB_TO_SCALAR) {
            Float scalar = zeroDbScalars.get(new Control(objectId, address.scope, address.element));
            return scalar == null ? null : floatBytes(scalar);
        }
        if (address.selector == PROP_ACTIVE_SUB_DEVICES) {
            int[] members = aggregateMembers.get(objectId);
            if (members != null) {
                ByteBuffer ids = ByteBuffer.allocate(members.length * Integer.BYTES)
                        .order(ByteOrder.nativeOrder());
                for (int member : members) {
                    ids.putInt(member);
                }
                return ids.array();
            }
            Integer count = activeSubDevices.get(objectId);
            if (count == null) {
                return null;          // an ordinary device: no such property
            }
            ByteBuffer ids = ByteBuffer.allocate(count * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                ids.putInt(1000 + i);
            }
            return ids.array();
        }
        return null;
    }

    private byte[] floatBytes(float value) {
        return ByteBuffer.allocate(Float.BYTES).order(ByteOrder.nativeOrder())
                .putFloat(value).array();
    }

    private int idOf(String deviceName) {
        for (Device device : devices) {
            if (device.name().equals(deviceName)) {
                return device.id();
            }
        }
        throw new IllegalArgumentException("this machine has no device named " + deviceName);
    }

    private int scope(boolean output) {
        return output ? SCOPE_OUTPUT : SCOPE_INPUT;
    }

    private byte[] cString(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] terminated = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, terminated, 0, bytes.length);
        return terminated;
    }
}
