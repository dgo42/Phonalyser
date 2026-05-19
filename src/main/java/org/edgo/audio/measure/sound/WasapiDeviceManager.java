package org.edgo.audio.measure.sound;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.edgo.audio.measure.sound.WasapiNative.*;

/**
 * Discovery for the {@link AudioBackendType#WASAPI} backend.  Constructed
 * and owned by {@link AudioBackend}; do not instantiate directly.
 *
 * <p>Enumerates active render/capture endpoints via {@code IMMDeviceEnumerator}
 * and stores them as {@link WasapiDeviceRef}.  The endpoint ID
 * (the wide-string returned by {@code IMMDevice::GetId}) is the durable
 * handle — opening a stream re-fetches the {@code IMMDevice} from the
 * enumerator with that ID, so {@link WasapiDeviceRef} stays
 * disposable-friendly even after the underlying COM objects are released.
 */
@Log4j2
public class WasapiDeviceManager {

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

    WasapiDeviceManager() {}

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
     * accepts.  Cached by endpoint ID — repeat probes for the same
     * device come back from memory.
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof WasapiDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        return cache.computeIfAbsent(d.endpointId(), k -> probeFormats(d));
    }

    private List<AudioFormat> probeFormats(WasapiDeviceRef d) {
        List<AudioFormat> result = new ArrayList<>();
        int[] rates  = {44100, 48000, 88200, 96000, 176400, 192000,
                        352800, 384000, 705600, 768000};
        int[] depths = {16, 24, 32};

        Pointer dev = openDevice(d.endpointId());
        if (dev == null) return result;
        try {
            for (int rate : rates) {
                for (int bits : depths) {
                    if (isExclusiveFormatSupported(dev, rate, bits)) {
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

    private boolean isExclusiveFormatSupported(Pointer dev, int rate, int bits) {
        PointerByReference ppClient = new PointerByReference();
        int hr = callHR(dev, VT_DEVICE_ACTIVATE,
                IID_IAudioClient, CLSCTX_ALL, null, ppClient);
        if (hr != S_OK || ppClient.getValue() == null) return false;
        Pointer client = ppClient.getValue();
        try {
            Memory wfx = buildWaveFormatExtensible(rate, bits);
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
     * {@code null} on any failure — callers fall back to the endpoint
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

    // -------------------------------------------------------------------------
    // Suppress unused-warnings for fields/helpers Native needs but the IDE
    // can't see through reflective JNA wiring.
    // -------------------------------------------------------------------------
    static {
        if (Native.POINTER_SIZE != 8) {
            throw new IllegalStateException("WASAPI binding assumes 64-bit JVM");
        }
    }
}
