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

package org.edgo.audio.measure.sound.wasapi;

import static org.edgo.audio.measure.sound.wasapi.WasapiNative.AUDCLNT_E_DEVICE_IN_USE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.AUDCLNT_E_DEVICE_INVALIDATED;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.AUDCLNT_E_UNSUPPORTED_FORMAT;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.AUDCLNT_SHAREMODE_EXCLUSIVE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.CLSCTX_ALL;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.CLSID_MMDeviceEnumerator;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.DEVICE_STATE_ACTIVE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.E_DATAFLOW_CAPTURE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.E_DATAFLOW_RENDER;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.IID_IAudioClient;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.IID_IMMDeviceEnumerator;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.PKEY_Device_FriendlyName_FMTID;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.PKEY_Device_FriendlyName_PID;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.S_OK;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.CONTAINER_BITS_32;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VALID_BITS_24;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_AC_IS_FORMAT_SUPPORTED;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_COLLECTION_GET_COUNT;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_COLLECTION_ITEM;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_DEVICE_ACTIVATE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_DEVICE_GET_ID;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_DEVICE_OPEN_PROPERTY_STORE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_ENUM_AUDIO_ENDPOINTS;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_GET_DEVICE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.VT_PROPSTORE_GET_VALUE;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.buildWaveFormatExtensible;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.callHR;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.ensureComInit;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.readAndFreeLpwstr;
import static org.edgo.audio.measure.sound.wasapi.WasapiNative.release;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.wasapi.WasapiNative.Ole32;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Discovery for the {@link AudioBackendType#WASAPI} backend.  Constructed
 * and owned by {@link AudioBackend}; do not instantiate directly.
 *
 * <p>Enumerates active render/capture endpoints via {@code IMMDeviceEnumerator}
 * and stores them as {@link WasapiDeviceRef}.  The endpoint ID
 * (the wide-string returned by {@code IMMDevice::GetId}) is the durable
 * handle - opening a stream re-fetches the {@code IMMDevice} from the
 * enumerator with that ID, so {@link WasapiDeviceRef} stays
 * disposable-friendly even after the underlying COM objects are released.
 */
@Log4j2
public class WasapiDeviceManager implements AudioDeviceManager {

    /** The HRESULT this module prints into a failure message - "failed: 0x88890004". */
    private static final Pattern HRESULT = Pattern.compile("0x([0-9a-fA-F]{1,8})");
    /** The one refusal that names no HRESULT: the endpoint was gone before the
     *  client was even activated (see the recorder's and the generator's open). */
    private static final String DEVICE_GONE_TEXT = "WASAPI device disappeared";

    /** {@link DeviceRef} backed by a WASAPI endpoint ID (LPWSTR). */
    public record WasapiDeviceRef(int index, String name, String description, String vendor,
                                  boolean isInput, boolean isOutput,
                                  String endpointId)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.WASAPI;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }

    /**
     * Cached exclusive-mode format probes per endpoint ID.  IAudioClient
     * round-trips for IsFormatSupported are cheap individually but ~3
     * activations per device adds up across a 6-rate × 3-depth probe
     * grid, so we cache by the durable endpoint ID.
     */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    /**
     * Cached process-lifetime {@code IMMDeviceEnumerator}.  Created on
     * first use; released only via the JVM shutdown hook.  Re-using the
     * same enumerator across calls avoids the {@code CoCreateInstance}
     * cost on every list refresh.
     */
    private volatile Pointer enumerator;

    public WasapiDeviceManager() {}

    private Pointer enumerator() {
        Pointer local = enumerator;
        if (local != null) return local;
        synchronized (this) {
            local = enumerator;
            if (local != null) return local;
            ensureComInit();
            PointerByReference pp = new PointerByReference();
            int hr = Ole32.INSTANCE.CoCreateInstance(
                    CLSID_MMDeviceEnumerator, null,
                    CLSCTX_ALL, IID_IMMDeviceEnumerator, pp);
            if (hr != S_OK || pp.getValue() == null) {
                throw new IllegalStateException(
                        "CoCreateInstance(MMDeviceEnumerator) failed: 0x"
                                + Integer.toHexString(hr));
            }
            final Pointer fresh = pp.getValue();
            enumerator = fresh;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { release(fresh); } catch (Throwable ignored) {}
            }, "wasapi-enum-release"));
            return fresh;
        }
    }

    public List<DeviceRef> listInputDevices()  { return list(true);  }
    public List<DeviceRef> listOutputDevices() { return list(false); }

    /**
     * Drops every cached format probe, so the operator's scan re-asks the
     * endpoints.
     *
     * <p>The endpoint enumeration itself is live, so the device LIST was never
     * the stale part - the exclusive-mode answers behind it were.  The
     * per-enumeration eviction in {@link #list} only drops endpoints that left
     * the active state; an endpoint that survived kept its old answer for the
     * process's life even after the driver was changed under it, and no gesture
     * could refresh it.  Answers {@code true} because the next list can now
     * differ from the last.
     *
     * <p>The {@code IMMDeviceEnumerator} is deliberately NOT recreated: it is a
     * COM handle to the endpoint service, not a snapshot, and it enumerates the
     * current truth on every call.
     */
    @Override
    public boolean refreshDeviceList() {
        inputFormatsCache.clear();
        outputFormatsCache.clear();
        return true;
    }

    private List<DeviceRef> list(boolean input) {
        ensureComInit();
        List<DeviceRef> out = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Pointer enumPtr = enumerator();
        int dataFlow = input ? E_DATAFLOW_CAPTURE : E_DATAFLOW_RENDER;

        PointerByReference ppColl = new PointerByReference();
        int hr = callHR(enumPtr, VT_ENUM_AUDIO_ENDPOINTS, dataFlow,
                DEVICE_STATE_ACTIVE, ppColl);
        if (hr != S_OK || ppColl.getValue() == null) {
            log.warn("EnumAudioEndpoints failed: 0x{}", Integer.toHexString(hr));
            return out;
        }
        Pointer coll = ppColl.getValue();
        try {
            IntByReference count = new IntByReference();
            hr = callHR(coll, VT_COLLECTION_GET_COUNT, count);
            if (hr != S_OK) {
                log.warn("Collection.GetCount failed: 0x{}", Integer.toHexString(hr));
                return out;
            }
            int n = count.getValue();
            int slot = 0;
            for (int i = 0; i < n; i++) {
                PointerByReference ppDev = new PointerByReference();
                if (callHR(coll, VT_COLLECTION_ITEM, i, ppDev) != S_OK
                        || ppDev.getValue() == null) {
                    continue;
                }
                Pointer dev = ppDev.getValue();
                try {
                    String id   = getDeviceId(dev);
                    String name = getDeviceFriendlyName(dev);
                    if (id == null) continue;
                    out.add(new WasapiDeviceRef(
                            slot++,
                            name != null ? name : id,
                            input ? "WASAPI capture" : "WASAPI render",
                            "WASAPI",
                            input, !input,
                            id));
                    seenIds.add(id);
                } finally {
                    release(dev);
                }
            }
        } finally {
            release(coll);
        }
        (input ? inputFormatsCache : outputFormatsCache).keySet().retainAll(seenIds);
        return out;
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        List<DeviceRef> all = isOutput ? listOutputDevices() : listInputDevices();
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("WASAPI device index out of range: " + index
                    + " (have " + all.size() + " " + (isOutput ? "output" : "input") + " devices)");
        }
        return all.get(index);
    }

    /**
     * Probes a small set of standard rates × bit depths in WASAPI
     * exclusive mode and returns the {@link AudioFormat}s the device
     * accepts.  Cached by endpoint ID - repeat probes for the same
     * device come back from memory.
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof WasapiDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        return cache.computeIfAbsent(d.endpointId(), k -> probeFormats(d));
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return new WasapiRecorder(this, (WasapiDeviceManager.WasapiDeviceRef) device, sampleRate, bitDepth);
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        return new WasapiGenerator(this, (WasapiDeviceManager.WasapiDeviceRef) device, sampleRate, bitDepth, ditherBits);
    }

    /**
     * This backend's own reading of its own failures.  Every WASAPI refusal
     * carries its HRESULT as "0x..." in the message (that is how the recorder and
     * the generator report a failed {@code Initialize}), so the hex is what is
     * read back and compared against the four codes this module already names.
     *
     * <p>It serves CAPTURE: the GUI's playback goes through JavaSound even for a
     * WASAPI card ({@code AudioBackend.playbackManager} reroutes the render
     * side), so a WASAPI card's OUTPUT failure is classified there, not here.
     * This is still the right owner of the vocabulary - the capture lane is
     * WASAPI's own, and a later render path would find the table already here.
     */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        String text = (failure == null) ? null : failure.getMessage();
        if (text == null) {
            return DeviceFailureReason.UNKNOWN;
        }
        if (text.contains(DEVICE_GONE_TEXT)) {
            return DeviceFailureReason.DEVICE_DISCONNECTED;
        }
        Matcher matcher = HRESULT.matcher(text);
        if (!matcher.find()) {
            return DeviceFailureReason.UNKNOWN;
        }
        int hr = Integer.parseUnsignedInt(matcher.group(1), 16);
        if (hr == AUDCLNT_E_DEVICE_INVALIDATED) {
            return DeviceFailureReason.DEVICE_DISCONNECTED;
        }
        if (hr == AUDCLNT_E_DEVICE_IN_USE || hr == AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED) {
            return DeviceFailureReason.DEVICE_IN_USE;
        }
        if (hr == AUDCLNT_E_UNSUPPORTED_FORMAT) {
            return DeviceFailureReason.FORMAT_UNSUPPORTED;
        }
        return DeviceFailureReason.UNKNOWN;
    }

    private List<AudioFormat> probeFormats(WasapiDeviceRef d) {
        List<AudioFormat> result = new ArrayList<>();
        int[] rates  = {8000, 11025, 16000, 22050, 44100, 48000, 88200, 
                        96000, 176400, 192000, 352800, 384000, 705600, 768000};
        int[] depths = {16, 24, 32};

        Pointer dev = openDevice(d.endpointId());
        if (dev == null) return result;
        try {
            for (int rate : rates) {
                for (int bits : depths) {
                    // The reported depth is the VALID bits - the exact
                    // capability.  24 is genuinely 24 whether the device takes
                    // it 3-byte packed or only inside a 32-bit container (the
                    // open resolves that transport detail); 32 means 32 valid
                    // bits, never a folded 24-in-32.
                    // A mono-only capture device won't pass the stereo probe;
                    // WasapiRecorder captures it mono and upmixes, so accept it.
                    if (isExclusiveFormatSupported(dev, rate, bits, bits, 2)
                            || isExclusiveFormatSupported(dev, rate, bits, bits, 1)
                            || (bits == VALID_BITS_24
                                && (isExclusiveFormatSupported(dev, rate, CONTAINER_BITS_32, VALID_BITS_24, 2)
                                    || isExclusiveFormatSupported(dev, rate, CONTAINER_BITS_32, VALID_BITS_24, 1)))) {
                        result.add(new AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED,
                                rate, bits, 2, (bits / 8) * 2, rate, false));
                    }
                }
            }
        } finally {
            release(dev);
        }
        return result;
    }

    private boolean isExclusiveFormatSupported(Pointer dev, int rate, int storeBits,
                                               int validBits, int channels) {
        PointerByReference ppClient = new PointerByReference();
        int hr = callHR(dev, VT_DEVICE_ACTIVATE,
                IID_IAudioClient, CLSCTX_ALL, null, ppClient);
        if (hr != S_OK || ppClient.getValue() == null) return false;
        Pointer client = ppClient.getValue();
        try {
            Memory wfx = buildWaveFormatExtensible(rate, storeBits, validBits, channels);
            PointerByReference closest = new PointerByReference();
            int rc = callHR(client, VT_AC_IS_FORMAT_SUPPORTED,
                    AUDCLNT_SHAREMODE_EXCLUSIVE, wfx, closest);
            // Exclusive mode: S_OK = supported, anything else (including
            // AUDCLNT_E_UNSUPPORTED_FORMAT) = not supported.
            return rc == S_OK;
        } finally {
            release(client);
        }
    }

    /**
     * Re-acquires an {@code IMMDevice} by endpoint ID via
     * {@code IMMDeviceEnumerator::GetDevice}.  Caller owns the returned
     * pointer and must call {@link WasapiNative#release(Pointer)}.
     */
    public Pointer openDevice(String endpointId) {
        ensureComInit();
        PointerByReference ppDev = new PointerByReference();
        int hr = callHR(enumerator(), VT_GET_DEVICE,
                new WString(endpointId), ppDev);
        if (hr != S_OK || ppDev.getValue() == null) {
            log.warn("GetDevice('{}') failed: 0x{}", endpointId, Integer.toHexString(hr));
            return null;
        }
        return ppDev.getValue();
    }

    private static String getDeviceId(Pointer dev) {
        PointerByReference ppId = new PointerByReference();
        if (callHR(dev, VT_DEVICE_GET_ID, ppId) != S_OK) return null;
        return readAndFreeLpwstr(ppId.getValue());
    }

    /**
     * Pulls {@code PKEY_Device_FriendlyName} (a {@code VT_LPWSTR}
     * PROPVARIANT) out of the endpoint's property store.  Returns
     * {@code null} on any failure - callers fall back to the endpoint
     * ID for display purposes.
     */
    private static String getDeviceFriendlyName(Pointer dev) {
        PointerByReference ppStore = new PointerByReference();
        // STGM_READ = 0
        if (callHR(dev, VT_DEVICE_OPEN_PROPERTY_STORE, 0, ppStore) != S_OK
                || ppStore.getValue() == null) {
            return null;
        }
        Pointer store = ppStore.getValue();
        try {
            // PROPERTYKEY = { GUID fmtid; DWORD pid; } => 20 bytes.
            Memory pkey = new Memory(20);
            pkey.write(0, PKEY_Device_FriendlyName_FMTID, 0, 16);
            pkey.setInt(16, PKEY_Device_FriendlyName_PID);

            // PROPVARIANT is 24 bytes on x64; allocate a bit more for safety.
            Memory pv = new Memory(32);
            pv.clear();
            int hr = callHR(store, VT_PROPSTORE_GET_VALUE, pkey, pv);
            if (hr != S_OK) return null;
            try {
                short vt = pv.getShort(0);
                // VT_LPWSTR = 0x001F.  The string pointer lives at
                // offset 8 (after vt/wReserved1..3, with x64 alignment).
                if (vt != 0x001F) return null;
                Pointer pwsz = pv.getPointer(8);
                return pwsz == null ? null : pwsz.getWideString(0);
            } finally {
                Ole32.INSTANCE.PropVariantClear(pv);
            }
        } finally {
            release(store);
        }
    }
}
