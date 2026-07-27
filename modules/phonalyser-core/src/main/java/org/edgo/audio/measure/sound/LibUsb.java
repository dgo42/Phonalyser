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

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JNA binding to the {@code libusb-1.0} shared library — a common
 * building block for any backend that talks to a USB instrument directly:
 * init/exit, VID/PID enumeration, open / close / reset / claim, synchronous
 * bulk transfers (control-style register traffic), and the asynchronous
 * transfer API (streaming).  Nothing device-specific lives here; each
 * consumer (today the QA40x backend) layers its own identity, endpoints and
 * framing on top.
 *
 * <p>The library file name is platform-dependent — Windows tries the
 * JVM-arch-suffixed name first ({@code libusb-1.0_x64.dll} on a 64-bit JVM,
 * {@code libusb-1.0_x86.dll} on the legacy 32-bit fat jar) and falls back to a
 * plain {@code libusb-1.0.dll}; both arch variants can therefore coexist in
 * {@code lib/windows/}, exactly like {@code portaudio_x64} / {@code portaudio_x86}.
 * Linux looks for {@code libusb-1.0.so.0} (with fall-back to the {@code usb-1.0}
 * SONAME), macOS for {@code libusb-1.0.dylib}.  Drop the file into the matching
 * {@code lib/<os>/} directory (which the launch config / installer adds to
 * {@code java.library.path}).
 *
 * <p>Absence is <b>graceful</b>: {@link #available()} probes for the library and
 * returns {@code false} without throwing, so a machine without {@code libusb}
 * simply reports the dependent backends as unavailable instead of erroring on
 * start-up.  {@link #lib()} throws only when a caller commits to using the
 * binding.
 *
 * <p>Setting {@code -Dlibusb.path=<dir>} (or the library file itself — a file
 * path resolves to its parent directory) pins where {@code libusb-1.0} loads
 * from: the directory is registered as a per-library JNA search path, which JNA
 * consults before {@code jna.library.path} and the system path, so it wins
 * without disturbing where the other native libraries (portaudio, csjsound)
 * resolve.  This is how a run points the binding at the mock under
 * {@code src/test/lib/libusb}.  Absent, the load behaviour is unchanged.
 *
 * <p>{@code libusb} uses C {@code ssize_t} for {@code get_device_list} and C
 * {@code long} for the {@code timeval} fields; this binding uses {@link NativeLong}
 * (platform {@code long}) for those — device counts and timeouts are small enough
 * that the low word carries the value on every supported platform.
 */
@Log4j2
public final class LibUsb {

    /** JNA library names to try, in order, per OS + JVM arch (see {@link #candidateLibraryNames}). */
    private static final String WIN_LIB      = "libusb-1.0";      // plain fall-back → libusb-1.0.dll
    private static final String WIN_LIB_X64  = "libusb-1.0_x64";  // 64-bit JVM, house arch-suffix convention
    private static final String WIN_LIB_X86  = "libusb-1.0_x86";  // legacy 32-bit fat-jar JVM
    private static final String UNIX_LIB     = "usb-1.0";         // → libusb-1.0.so / libusb-1.0.dylib
    private static final String LINUX_SONAME = "libusb-1.0.so.0"; // versioned SONAME fall-back

    /** System property that pins the directory {@code libusb-1.0} loads from (per-library JNA search path). */
    private static final String LIBUSB_PATH_PROPERTY = "libusb.path";

    // --- libusb_transfer_type ------------------------------------------------
    public static final byte TRANSFER_TYPE_BULK = 2;

    // --- libusb_transfer_status ---------------------------------------------
    public static final int TRANSFER_COMPLETED = 0;
    public static final int TRANSFER_ERROR     = 1;
    public static final int TRANSFER_TIMED_OUT = 2;
    public static final int TRANSFER_CANCELLED = 3;
    public static final int TRANSFER_STALL     = 4;
    public static final int TRANSFER_NO_DEVICE = 5;
    public static final int TRANSFER_OVERFLOW  = 6;

    // --- libusb_error (subset used for diagnostics) --------------------------
    public static final int ERROR_INTERRUPTED = -10;

    private static volatile Lib     lib;
    private static volatile boolean loadAttempted;
    private static volatile boolean initialized;

    private LibUsb() {}

    /**
     * {@code struct libusb_device_descriptor} — callers typically read only
     * {@code idVendor}/{@code idProduct}, but the full layout is mapped so the
     * struct size and field offsets match the native header.
     */
    public static class LibUsbDeviceDescriptor extends Structure {
        public byte  bLength;
        public byte  bDescriptorType;
        public short bcdUSB;
        public byte  bDeviceClass;
        public byte  bDeviceSubClass;
        public byte  bDeviceProtocol;
        public byte  bMaxPacketSize0;
        public short idVendor;
        public short idProduct;
        public short bcdDevice;
        public byte  iManufacturer;
        public byte  iProduct;
        public byte  iSerialNumber;
        public byte  bNumConfigurations;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bLength", "bDescriptorType", "bcdUSB",
                    "bDeviceClass", "bDeviceSubClass", "bDeviceProtocol",
                    "bMaxPacketSize0", "idVendor", "idProduct", "bcdDevice",
                    "iManufacturer", "iProduct", "iSerialNumber", "bNumConfigurations");
        }
    }

    /**
     * {@code struct timeval} for {@link Lib#libusb_handle_events_timeout_completed}.
     * Both fields are C {@code long} ({@code time_t} / {@code suseconds_t}), so
     * {@link NativeLong} tracks the platform width (8 bytes on 64-bit Unix, 4 on
     * Windows).
     */
    public static class Timeval extends Structure {
        public NativeLong tvSec;
        public NativeLong tvUsec;

        public Timeval() {
            tvSec  = new NativeLong(0);
            tvUsec = new NativeLong(0);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("tvSec", "tvUsec");
        }
    }

    /**
     * {@code struct libusb_transfer} — the async transfer control block.  A
     * consumer allocates it via {@link Lib#libusb_alloc_transfer}, wraps the
     * returned pointer with {@link #LibUsbTransfer(Pointer)}, fills the fields,
     * {@link #write()}s and submits it.  The trailing iso-packet flexible array
     * is omitted (bulk transfers use {@code numIsoPackets == 0}).
     */
    public static class LibUsbTransfer extends Structure {
        public Pointer          devHandle;
        public byte             flags;
        public byte             endpoint;
        public byte             type;
        public int              timeout;
        public int              status;
        public int              length;
        public int              actualLength;
        public TransferCallback callback;
        public Pointer          userData;
        public Pointer          buffer;
        public int              numIsoPackets;

        public LibUsbTransfer() { super(); }
        public LibUsbTransfer(Pointer p) { super(p); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("devHandle", "flags", "endpoint", "type",
                    "timeout", "status", "length", "actualLength", "callback",
                    "userData", "buffer", "numIsoPackets");
        }
    }

    /**
     * {@code libusb_transfer_cb_fn} — invoked by {@code libusb} on the thread
     * that runs {@link Lib#libusb_handle_events_timeout_completed} when a transfer
     * completes, fails or is cancelled.  The argument points at the same
     * {@link LibUsbTransfer} memory that was submitted.  Must not throw.
     *
     * <p>Register it through {@link #stdcallSafe} — on 32-bit Windows libusb calls
     * the callback {@code WINAPI} (stdcall), and a plain cdecl JNA thunk there
     * corrupts the stack (bench: instant {@code c0000374} heap-corruption crash
     * on Win7 x86 the moment streaming starts).
     */
    public interface TransferCallback extends Callback {
        void invoke(Pointer transfer);
    }

    /** win32-x86 variant of {@link TransferCallback}: the {@code StdCallCallback}
     *  marker makes JNA emit a stdcall thunk, matching {@code LIBUSB_CALL}
     *  ({@code WINAPI}) on 32-bit Windows.  Never used on other platforms. */
    public interface TransferCallbackStdCall extends TransferCallback, StdCallLibrary.StdCallCallback {
        @Override
        void invoke(Pointer transfer);
    }

    /** Whether libusb's public API uses stdcall here: {@code LIBUSB_CALL} is
     *  {@code WINAPI}, which differs from cdecl only on 32-bit Windows. */
    private static boolean isWin32StdCall() { // static-ok: pure platform predicate, native-interop
        return Platform.isWindows() && !Platform.is64Bit();
    }

    /** Returns {@code cb} marked with the calling convention libusb expects on
     *  THIS platform: on 32-bit Windows a stdcall-marked delegate (see
     *  {@link TransferCallbackStdCall}), elsewhere {@code cb} unchanged. */
    public static TransferCallback stdcallSafe(TransferCallback cb) { // static-ok: native-interop helper, mirrors lib()
        if (isWin32StdCall()) {
            return (TransferCallbackStdCall) cb::invoke;
        }
        return cb;
    }

    /** The bound {@code libusb-1.0} entry points — the subset its consumers use. */
    public interface Lib extends Library {
        int    libusb_init(PointerByReference context);
        void   libusb_exit(Pointer context);
        String libusb_error_name(int errcode);

        NativeLong libusb_get_device_list(Pointer context, PointerByReference list);
        void       libusb_free_device_list(Pointer list, int unrefDevices);
        int        libusb_get_device_descriptor(Pointer device, LibUsbDeviceDescriptor descriptor);
        byte       libusb_get_bus_number(Pointer device);
        byte       libusb_get_device_address(Pointer device);

        int  libusb_open(Pointer device, PointerByReference handle);
        void libusb_close(Pointer handle);
        int  libusb_reset_device(Pointer handle);
        int  libusb_get_configuration(Pointer handle, IntByReference configuration);
        int  libusb_set_configuration(Pointer handle, int configuration);
        int  libusb_claim_interface(Pointer handle, int interfaceNumber);
        int  libusb_release_interface(Pointer handle, int interfaceNumber);

        int  libusb_bulk_transfer(Pointer handle, byte endpoint, byte[] data,
                                  int length, IntByReference transferred, int timeout);

        Pointer libusb_alloc_transfer(int isoPackets);
        void    libusb_free_transfer(Pointer transfer);
        int     libusb_submit_transfer(Pointer transfer);
        int     libusb_cancel_transfer(Pointer transfer);
        int     libusb_handle_events_timeout_completed(Pointer context, Timeval tv, IntByReference completed);
    }

    /**
     * The JNA names to hand to {@code Native.load}, in preference order, for the
     * given {@code os.name} and {@code os.arch}.  Windows tries the JVM-arch-
     * suffixed name first so both arch DLLs can coexist in {@code lib/windows/}
     * (the {@code portaudio_x64} / {@code portaudio_x86} convention), then the
     * plain upstream name.  JNA maps a bare core ({@code usb-1.0}) to
     * {@code libusb-1.0.so} / {@code libusb-1.0.dylib} and keeps a
     * {@code lib}-prefixed Windows name as {@code libusb-1.0.dll}; the versioned
     * {@code libusb-1.0.so.0} is tried last on Linux, where the unversioned
     * {@code .so} symlink ships only with the {@code -dev} package.
     */
    static String[] candidateLibraryNames(String osName, String osArch) { // static-ok: pure OS→libname map, headless-testable
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
            boolean is32 = arch.equals("x86") || arch.contains("i386") || arch.contains("i686");
            return new String[] { is32 ? WIN_LIB_X86 : WIN_LIB_X64, WIN_LIB };
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[] { UNIX_LIB };
        }
        return new String[] { UNIX_LIB, LINUX_SONAME };
    }

    /** Attempts to load the native library exactly once; leaves {@link #lib} null on failure. */
    private static synchronized void ensureLoaded() { // static-ok: native-interop loader, mirrors PortAudio.lib()
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        String[] candidates = candidateLibraryNames(System.getProperty("os.name", ""),
                                                    System.getProperty("os.arch", ""));
        String injectedPath = System.getProperty(LIBUSB_PATH_PROPERTY);
        if (injectedPath != null && !injectedPath.isBlank()) {
            // Accept the search directory or the library file itself — JNA
            // appends the mapped file name, so a file path needs its parent.
            File injected = new File(injectedPath);
            String searchDir = injected.isFile() ? injected.getParent() : injectedPath;
            // JNA searches per-library paths first, so this pins libusb-1.0's
            // load directory ahead of jna.library.path and the system path.
            for (String name : candidates) {
                NativeLibrary.addSearchPath(name, searchDir);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("Loading libusb-1.0: candidates={}, libusb.path={}, jna.library.path={}, java.library.path={}",
                    Arrays.toString(candidates),
                    injectedPath,
                    System.getProperty("jna.library.path"),
                    System.getProperty("java.library.path"));
        }
        UnsatisfiedLinkError last = null;
        // libusb's public API is LIBUSB_CALL = WINAPI: stdcall on 32-bit Windows,
        // identical to cdecl everywhere else.  JNA defaults to cdecl, which on
        // win32-x86 unbalances the stack on EVERY call — heap corruption
        // (c0000374) the moment streaming starts.  Select the convention per
        // platform at the one load site (callbacks are handled by stdcallSafe).
        Map<String, Object> options = isWin32StdCall()
                ? Map.of(Library.OPTION_CALLING_CONVENTION, Function.ALT_CONVENTION)
                : Map.of();
        for (String name : candidates) {
            try {
                lib = Native.load(name, Lib.class, options);
                log.info("libusb-1.0 loaded as '{}'", name);
                break;
            } catch (UnsatisfiedLinkError ule) {
                last = ule;
            }
        }
        if (lib == null) {
            // WARN, not INFO: the packaged log config caps this logger at WARN, and
            // an invisible load failure cost a field round-trip (Win7 x86 bench) —
            // the QA40x backend silently showing no devices with no trace of why.
            // One line once per process; on Windows/macOS the library ships bundled,
            // so failing to load it is genuinely anomalous.
            log.warn("libusb-1.0 not available (tried {}): {}",
                    Arrays.toString(candidates), last != null ? last.getMessage() : "no candidate");
        }
    }

    /** Probes for the native library without throwing — {@code true} iff it loaded. */
    public static boolean available() { // static-ok: graceful native-absence probe
        ensureLoaded();
        return lib != null;
    }

    /**
     * Returns the initialised binding, loading {@code libusb-1.0} and calling
     * {@code libusb_init} on first use.  Throws only here — a caller reaching for
     * {@link #lib()} has committed to using the device; use {@link #available()}
     * for a non-throwing probe.
     */
    public static synchronized Lib lib() { // static-ok: native-interop accessor, mirrors PortAudio.lib()
        ensureLoaded();
        if (lib == null) {
            throw new IllegalStateException(
                    "libusb-1.0 native library not available (tried "
                            + Arrays.toString(candidateLibraryNames(System.getProperty("os.name", ""),
                                                                    System.getProperty("os.arch", "")))
                            + " on jna.library.path=" + System.getProperty("jna.library.path")
                            + ", java.library.path=" + System.getProperty("java.library.path") + ")");
        }
        if (!initialized) {
            int rc = lib.libusb_init(null);
            if (rc < 0) {
                throw new IllegalStateException("libusb_init failed: " + errorName(rc));
            }
            initialized = true;
            Native.setCallbackExceptionHandler((cb, ex) ->
                    log.error("libusb callback threw (class={})", cb.getClass().getName(), ex));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { lib.libusb_exit(null); } catch (Throwable ignored) { /* JVM shutdown */ }
            }, "libusb-exit"));
        }
        return lib;
    }

    /** Human-readable name for a {@code libusb} return code (e.g. {@code LIBUSB_ERROR_TIMEOUT}). */
    public static String errorName(int code) { // static-ok: native-interop diagnostic, mirrors PortAudio.errorText()
        try {
            return lib().libusb_error_name(code) + " (" + code + ")";
        } catch (Throwable t) {
            return "code " + code;
        }
    }

    /** Human-readable name for a {@code libusb_transfer_status} value. */
    public static String transferStatusName(int status) { // static-ok: native-interop diagnostic
        switch (status) {
            case TRANSFER_COMPLETED: return "COMPLETED";
            case TRANSFER_ERROR:     return "ERROR";
            case TRANSFER_TIMED_OUT: return "TIMED_OUT";
            case TRANSFER_CANCELLED: return "CANCELLED";
            case TRANSFER_STALL:     return "STALL";
            case TRANSFER_NO_DEVICE: return "NO_DEVICE";
            case TRANSFER_OVERFLOW:  return "OVERFLOW";
            default:                 return "status " + status;
        }
    }
}
