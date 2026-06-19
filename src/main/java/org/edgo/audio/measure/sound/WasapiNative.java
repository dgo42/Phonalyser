/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.sound;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Low-level JNA glue for Windows WASAPI. Exposes the {@code ole32},
 * {@code kernel32} and {@code mmdevapi}-related entry points used by
 * {@link WasapiDeviceManager}, {@link WasapiRecorder} and
 * {@link WasapiGenerator}, plus the COM interface GUIDs and constants
 * those classes need.
 *
 * <p>WASAPI is a pure COM API and there's no auto-generated binding
 * here — every interface method is invoked through its vtable slot via
 * {@link #callHR(Pointer, int, Object...)}. The vtable indices in
 * {@code Vt*} match the order of methods in the public Windows SDK
 * headers ({@code mmdeviceapi.h}, {@code audioclient.h}).
 */
@Log4j2
@UtilityClass
public class WasapiNative {

    public static final int S_OK                     = 0x00000000;
    public static final int S_FALSE                  = 0x00000001;
    public static final int RPC_E_CHANGED_MODE       = 0x80010106;

    public static final int COINIT_MULTITHREADED     = 0x0;
    public static final int CLSCTX_ALL               = 0x17;

    public static final int E_DATAFLOW_RENDER        = 0;
    public static final int E_DATAFLOW_CAPTURE       = 1;
    public static final int E_ROLE_CONSOLE           = 0;
    public static final int DEVICE_STATE_ACTIVE      = 0x00000001;

    public static final int AUDCLNT_SHAREMODE_SHARED    = 0;
    public static final int AUDCLNT_SHAREMODE_EXCLUSIVE = 1;

    public static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    public static final int AUDCLNT_STREAMFLAGS_NOPERSIST     = 0x00080000;

    public static final int AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY = 0x01;
    public static final int AUDCLNT_BUFFERFLAGS_SILENT             = 0x02;

    // Audio-client HRESULTs we care about for fallback decisions.
    public static final int AUDCLNT_E_UNSUPPORTED_FORMAT         = 0x88890008;
    public static final int AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED = 0x8889000A;
    public static final int AUDCLNT_E_DEVICE_IN_USE              = 0x8889000B;
    public static final int AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED    = 0x88890019;
    public static final int AUDCLNT_E_DEVICE_INVALIDATED         = 0x88890004;

    public static final int WAVE_FORMAT_PCM         = 0x0001;
    public static final int WAVE_FORMAT_EXTENSIBLE  = 0xFFFE;
    public static final int SPEAKER_STEREO_MASK     = 0x00000003;
    public static final int SPEAKER_FRONT_CENTER    = 0x00000004;

    public static final int WAIT_OBJECT_0           = 0x00000000;
    public static final int WAIT_TIMEOUT            = 0x00000102;
    public static final int INFINITE                = 0xFFFFFFFF;

    public static final long REF_TIME_PER_SEC       = 10_000_000L;   // 100-ns units in 1 s
    public static final long REF_TIME_PER_MILLISEC  = 10_000L;

    // GUID for KSDATAFORMAT_SUBTYPE_PCM (used in WAVEFORMATEXTENSIBLE).
    public static final byte[] KSDATAFORMAT_SUBTYPE_PCM =
            guid("00000001-0000-0010-8000-00aa00389b71");

    public static final byte[] CLSID_MMDeviceEnumerator =
            guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
    public static final byte[] IID_IMMDeviceEnumerator  =
            guid("A95664D2-9614-4F35-A746-DE8DB63617E6");
    public static final byte[] IID_IAudioClient         =
            guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    public static final byte[] IID_IAudioCaptureClient  =
            guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
    public static final byte[] IID_IAudioRenderClient   =
            guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");

    // PKEY_Device_FriendlyName = (fmtid {a45c254e-df1c-4efd-8020-67d146a850e0}, pid 14)
    public static final byte[] PKEY_Device_FriendlyName_FMTID =
            guid("A45C254E-DF1C-4EFD-8020-67D146A850E0");
    public static final int    PKEY_Device_FriendlyName_PID = 14;

    /** IUnknown vtable: slots 0..2 are QueryInterface/AddRef/Release. */
    public static final int VT_RELEASE                = 2;

    /** IMMDeviceEnumerator vtable layout (after IUnknown). */
    public static final int VT_ENUM_AUDIO_ENDPOINTS       = 3;
    public static final int VT_GET_DEVICE                 = 5;

    /** IMMDeviceCollection. */
    public static final int VT_COLLECTION_GET_COUNT       = 3;
    public static final int VT_COLLECTION_ITEM            = 4;

    /** IMMDevice. */
    public static final int VT_DEVICE_ACTIVATE            = 3;
    public static final int VT_DEVICE_OPEN_PROPERTY_STORE = 4;
    public static final int VT_DEVICE_GET_ID              = 5;
    public static final int VT_DEVICE_GET_STATE           = 6;

    /** IPropertyStore. */
    public static final int VT_PROPSTORE_GET_VALUE        = 5;

    /** IAudioClient. */
    public static final int VT_AC_INITIALIZE              = 3;
    public static final int VT_AC_GET_BUFFER_SIZE         = 4;
    public static final int VT_AC_GET_CURRENT_PADDING     = 6;
    public static final int VT_AC_IS_FORMAT_SUPPORTED     = 7;
    public static final int VT_AC_GET_MIX_FORMAT          = 8;
    public static final int VT_AC_GET_DEVICE_PERIOD       = 9;
    public static final int VT_AC_START                   = 10;
    public static final int VT_AC_STOP                    = 11;
    public static final int VT_AC_RESET                   = 12;
    public static final int VT_AC_SET_EVENT_HANDLE        = 13;
    public static final int VT_AC_GET_SERVICE             = 14;

    /** IAudioCaptureClient. */
    public static final int VT_CC_GET_BUFFER              = 3;
    public static final int VT_CC_RELEASE_BUFFER          = 4;
    public static final int VT_CC_GET_NEXT_PACKET_SIZE    = 5;

    /** IAudioRenderClient. */
    public static final int VT_RC_GET_BUFFER              = 3;
    public static final int VT_RC_RELEASE_BUFFER          = 4;

    public interface Ole32 extends Library {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        int  CoInitializeEx(Pointer pvReserved, int dwCoInit);
        void CoUninitialize();
        int  CoCreateInstance(byte[] rclsid, Pointer pUnkOuter,
                              int dwClsContext, byte[] riid, PointerByReference ppv);
        void CoTaskMemFree(Pointer pv);
        int  PropVariantClear(Pointer pvar);
    }

    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        Pointer CreateEventW(Pointer attrs, int manualReset, int initState, Pointer name);
        int     WaitForSingleObject(Pointer handle, int timeoutMs);
        int     CloseHandle(Pointer handle);
    }

    /**
     * Per-thread COM initialisation guard.  WASAPI requires every thread
     * that touches a COM interface pointer to have called
     * {@code CoInitializeEx}; calling it more than once on the same
     * thread is fine ({@code S_FALSE} just bumps a refcount), but we
     * track it anyway so we don't bother repeating the syscall.
     *
     * <p>If the thread is already in a different apartment (typically
     * SWT's STA), {@code CoInitializeEx} returns
     * {@code RPC_E_CHANGED_MODE} — we treat that as "already
     * initialised, just go" because COM marshalling will handle the
     * cross-apartment calls for us.
     */
    private static final ThreadLocal<Boolean> COM_READY = ThreadLocal.withInitial(() -> false);

    public static void ensureComInit() {
        if (COM_READY.get()) return;
        int hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED);
        if (hr != S_OK && hr != S_FALSE && hr != RPC_E_CHANGED_MODE) {
            throw new IllegalStateException(
                    "CoInitializeEx failed: 0x" + Integer.toHexString(hr));
        }
        COM_READY.set(true);
    }

    /**
     * Invokes vtable slot {@code methodIdx} on the COM interface
     * pointed to by {@code iface}, returning the HRESULT.  Slot 0/1/2
     * are {@code IUnknown}; the first real method is slot 3 for every
     * COM-derived interface.
     */
    public static int callHR(Pointer iface, int methodIdx, Object... args) {
        Pointer vtbl   = iface.getPointer(0);
        Pointer method = vtbl.getPointer((long) methodIdx * Native.POINTER_SIZE);
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = iface;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        return Function.getFunction(method).invokeInt(callArgs);
    }

    /** Convenience: invoke {@code IUnknown::Release} on a COM pointer. */
    public static void release(Pointer iface) {
        if (iface == null) return;
        try { callHR(iface, VT_RELEASE); } catch (Throwable t) {
            log.warn("Release threw: {}", t.getMessage());
        }
    }

    /**
     * Builds a {@code WAVEFORMATEXTENSIBLE} (40 bytes) describing a PCM stream
     * at the given rate / bit depth / channel count (1 = mono, 2 = stereo).
     * Allocated as native {@link Memory} so it can be passed straight to
     * {@code IAudioClient::Initialize} / {@code IsFormatSupported}.
     */
    public static Memory buildWaveFormatExtensible(int sampleRate, int bitDepth, int channels) {
        int blockAlign  = (bitDepth / 8) * channels;
        int channelMask = channels == 1 ? SPEAKER_FRONT_CENTER : SPEAKER_STEREO_MASK;
        Memory wfx = new Memory(40);
        wfx.clear();
        wfx.setShort(0,  (short) WAVE_FORMAT_EXTENSIBLE);
        wfx.setShort(2,  (short) channels);
        wfx.setInt  (4,  sampleRate);
        wfx.setInt  (8,  sampleRate * blockAlign);
        wfx.setShort(12, (short) blockAlign);
        wfx.setShort(14, (short) bitDepth);
        wfx.setShort(16, (short) 22);              // cbSize = sizeof(extension)
        wfx.setShort(18, (short) bitDepth);        // wValidBitsPerSample
        wfx.setInt  (20, channelMask);
        wfx.write   (24, KSDATAFORMAT_SUBTYPE_PCM, 0, 16);
        return wfx;
    }

    /**
     * Parses a {@code 8-4-4-4-12} GUID string into the 16-byte
     * little-endian / mixed-endian on-wire layout used by COM
     * ({@code Data1/Data2/Data3} are little-endian, {@code Data4} is a
     * raw 8-byte tail).
     */
    public static byte[] guid(String s) {
        UUID u = UUID.fromString(s.replace("{", "").replace("}", ""));
        // COM on-wire layout: Data1/2/3 little-endian, Data4 (the low 8 bytes)
        // raw big-endian — exactly the split the UUID class already exposes as
        // most/least-significant bits.
        return ByteBuffer.allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt  ((int)   (u.getMostSignificantBits() >>> 32))  // Data1
                .putShort((short) (u.getMostSignificantBits() >>> 16))  // Data2
                .putShort((short)  u.getMostSignificantBits())          // Data3
                .order(ByteOrder.BIG_ENDIAN)
                .putLong (u.getLeastSignificantBits())                  // Data4
                .array();
    }

    /**
     * Reads an LPWSTR from {@code COM_TASK_MEM} (NUL-terminated wide
     * string) and frees the underlying allocation via
     * {@code CoTaskMemFree}.  Used for {@code IMMDevice::GetId} and the
     * {@code PROPVARIANT.pwszVal} payload from {@code GetValue}.
     */
    public static String readAndFreeLpwstr(Pointer pwsz) {
        if (pwsz == null) return null;
        String s = pwsz.getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(pwsz);
        return s;
    }
}
