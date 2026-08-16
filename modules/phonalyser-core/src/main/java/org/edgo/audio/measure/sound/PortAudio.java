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

package org.edgo.audio.measure.sound;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.edgo.audio.measure.enums.DeviceFailureReason;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Minimal JNA binding to the PortAudio shared library.  Only exposes the
 * functions needed by {@link WdmksRecorder} and {@link WdmksGenerator}.
 *
 * <p>The library file name is platform-dependent - Windows looks for
 * {@code portaudio_x64.dll}, Linux for {@code libportaudio.so} (with
 * fall-back to {@code libportaudio.so.2}), macOS for
 * {@code libportaudio.dylib} (with fall-back to {@code libportaudio.2.dylib}).
 * Drop the file into the matching {@code lib/<os>/} directory (which the
 * launch config / installer adds to {@code java.library.path}).
 *
 * <p>PortAudio uses C {@code unsigned long} for {@code framesPerBuffer},
 * {@code PaStreamFlags} and {@code PaSampleFormat}. On Windows that is
 * 32-bit, so this binding uses {@link NativeLong} (platform {@code long})
 * for those fields.
 */
@Log4j2
public final class PortAudio {

    public static final int paNoError                  = 0;
    public static final int paFormatIsSupported        = 0;
    /** {@code paInvalidDevice} - a device index that is not in PortAudio's
     *  device list.  On a hot-plugged machine this does NOT mean the operator
     *  picked something silly; see {@link #refreshDevices()}. */
    public static final int paInvalidDevice            = -9996;
    /** The refusals {@link #classifyFailure(Throwable)} can read as a reason -
     *  the rest of {@code PaErrorCode} stays unmapped on purpose. */
    public static final int paInvalidChannelCount      = -9998;
    public static final int paInvalidSampleRate        = -9997;
    public static final int paSampleFormatNotSupported = -9994;
    public static final int paTimedOut                 = -9987;
    public static final int paDeviceUnavailable        = -9985;

    /** Stream-callback return values (from {@code PaStreamCallbackResult}). */
    public static final int paContinue = 0;
    public static final int paComplete = 1;
    public static final int paAbort    = 2;

    public static final NativeLong paFramesPerBufferUnspecified = new NativeLong(0L);

    public static final NativeLong paClipOff   = new NativeLong(0x00000001L);
    public static final NativeLong paDitherOff = new NativeLong(0x00000002L);

    /** Stream-callback status flags reported via the callback's {@code statusFlags} arg. */
    public static final long paInputUnderflow  = 0x00000001L;
    public static final long paInputOverflow   = 0x00000002L;
    public static final long paOutputUnderflow = 0x00000004L;
    public static final long paOutputOverflow  = 0x00000008L;
    public static final long paPrimingOutput   = 0x00000010L;

    /** Sample formats - bitfield values from PortAudio's {@code pa_common.h}. */
    public static final NativeLong paFloat32 = new NativeLong(0x00000001L);
    public static final NativeLong paInt32   = new NativeLong(0x00000002L);
    public static final NativeLong paInt24   = new NativeLong(0x00000004L);
    public static final NativeLong paInt16   = new NativeLong(0x00000008L);
    public static final NativeLong paInt8    = new NativeLong(0x00000010L);
    public static final NativeLong paUInt8   = new NativeLong(0x00000020L);

    /** Host API type IDs - from {@code PaHostApiTypeId} in {@code portaudio.h}. */
    public static final int paInDevelopment   = 0;
    public static final int paDirectSound     = 1;
    public static final int paMME             = 2;
    public static final int paASIO            = 3;
    public static final int paSoundManager    = 4;
    public static final int paCoreAudio       = 5;
    public static final int paOSS             = 7;
    public static final int paALSA            = 8;
    public static final int paAL              = 9;
    public static final int paBeOS            = 10;
    public static final int paWDMKS           = 11;
    public static final int paJACK            = 12;
    public static final int paWASAPI          = 13;
    public static final int paAudioScienceHPI = 14;

    public static final class PaStream extends PointerType {
        public PaStream() { super(); }
        public PaStream(Pointer p) { super(p); }
    }

    /**
     * PortAudio stream callback. Invoked on PortAudio's realtime audio thread -
     * must not allocate, log, block or throw. Return {@link #paContinue} to
     * keep the stream running, {@link #paComplete} to stop after the current
     * buffer drains, or {@link #paAbort} for immediate termination.
     */
    public interface PaStreamCallback extends Callback {
        int callback(Pointer input,
                     Pointer output,
                     NativeLong frameCount,
                     Pointer timeInfo,
                     NativeLong statusFlags,
                     Pointer userData);
    }

    public static class PaHostApiInfo extends Structure {
        public int     structVersion;
        public int     type;            // PaHostApiTypeId
        public String  name;
        public int     deviceCount;
        public int     defaultInputDevice;
        public int     defaultOutputDevice;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("structVersion", "type", "name",
                    "deviceCount", "defaultInputDevice", "defaultOutputDevice");
        }
    }

    public static class PaDeviceInfo extends Structure {
        public int     structVersion;
        public String  name;
        public int     hostApi;
        public int     maxInputChannels;
        public int     maxOutputChannels;
        public double  defaultLowInputLatency;
        public double  defaultLowOutputLatency;
        public double  defaultHighInputLatency;
        public double  defaultHighOutputLatency;
        public double  defaultSampleRate;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("structVersion", "name", "hostApi",
                    "maxInputChannels", "maxOutputChannels",
                    "defaultLowInputLatency", "defaultLowOutputLatency",
                    "defaultHighInputLatency", "defaultHighOutputLatency",
                    "defaultSampleRate");
        }
    }

    public static class PaHostErrorInfo extends Structure {
        public int       hostApiType;      // PaHostApiTypeId
        public NativeLong errorCode;        // host-specific error code
        public String    errorText;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hostApiType", "errorCode", "errorText");
        }
    }

    public static class PaStreamParameters extends Structure {
        public int        device;
        public int        channelCount;
        public NativeLong sampleFormat;
        public double     suggestedLatency;
        public Pointer    hostApiSpecificStreamInfo;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("device", "channelCount", "sampleFormat",
                    "suggestedLatency", "hostApiSpecificStreamInfo");
        }
    }

    public interface Lib extends Library {
        int    Pa_Initialize();
        int    Pa_Terminate();
        String Pa_GetErrorText(int errorCode);

        int    Pa_GetHostApiCount();
        int    Pa_HostApiTypeIdToHostApiIndex(int hostApiType);
        PaHostApiInfo Pa_GetHostApiInfo(int hostApi);

        int    Pa_GetDeviceCount();
        PaDeviceInfo Pa_GetDeviceInfo(int device);
        int    Pa_HostApiDeviceIndexToDeviceIndex(int hostApi, int hostApiDeviceIndex);

        int    Pa_IsFormatSupported(PaStreamParameters inputParams,
                                    PaStreamParameters outputParams,
                                    double sampleRate);

        int    Pa_OpenStream(PointerByReference stream,
                             PaStreamParameters inputParams,
                             PaStreamParameters outputParams,
                             double sampleRate,
                             NativeLong framesPerBuffer,
                             NativeLong streamFlags,
                             PaStreamCallback streamCallback,
                             Pointer userData);

        int    Pa_StartStream(Pointer stream);
        int    Pa_StopStream(Pointer stream);
        int    Pa_AbortStream(Pointer stream);
        int    Pa_CloseStream(Pointer stream);

        int    Pa_IsStreamActive(Pointer stream);
        int    Pa_IsStreamStopped(Pointer stream);

        int    Pa_ReadStream(Pointer stream, byte[] buffer, NativeLong frames);
        int    Pa_WriteStream(Pointer stream, byte[] buffer, NativeLong frames);

        NativeLong Pa_GetStreamReadAvailable(Pointer stream);
        NativeLong Pa_GetStreamWriteAvailable(Pointer stream);

        PaHostErrorInfo Pa_GetLastHostErrorInfo();
    }

    private static volatile Lib LIB;
    private static volatile boolean initialized;
    /** PortAudio streams this process currently has OPEN - every one of them
     *  holds a {@code PaStream*} that {@code Pa_Terminate} would invalidate
     *  underneath its owner (a vtable call into a freed stream is a native
     *  crash, not an exception).  Counted so {@link #refreshDevices()} can
     *  refuse rather than take the chance.  Raised BEFORE {@code Pa_OpenStream},
     *  so an open in flight is already protected. */
    private static final AtomicInteger LIVE_STREAMS = new AtomicInteger();
    /** The numeric PortAudio code {@link #check} writes into the message -
     *  "Invalid device (-9996)".  A parenthesised NUMBER only: the host code of
     *  {@code paUnanticipatedHostError} reads "(code=...)" and deliberately does
     *  not match, because it is the host API's vocabulary and not PortAudio's. */
    private static final Pattern ERROR_CODE = Pattern.compile("\\((-?\\d{1,5})\\)");
    /** The opening words of the failure {@link #lib()} throws when no PortAudio
     *  library could be loaded at all. */
    private static final String LOAD_FAILED_TEXT = "Could not load PortAudio native library";

    private PortAudio() {}

    /** Declares that a stream is about to be opened.  Call immediately BEFORE
     *  {@code Pa_OpenStream}, and pair with {@link #streamClosed()} on the close
     *  path AND on a failed open. */
    public static void streamOpening() { // static-ok: process-wide state of the one shared PortAudio library
        LIVE_STREAMS.incrementAndGet();
    }

    /** Declares that a stream is no longer open - after {@code Pa_CloseStream},
     *  or when the open that raised the count failed. */
    public static void streamClosed() { // static-ok: process-wide state of the one shared PortAudio library
        LIVE_STREAMS.decrementAndGet();
    }

    /**
     * Rebuilds PortAudio's device list.
     *
     * <p><b>Why this has to exist at all.</b>  PortAudio enumerates devices ONCE,
     * inside {@code Pa_Initialize}, and never rescans: a device index is a
     * position in that snapshot.  Unplug a USB card and the snapshot still lists
     * it, so the name the operator picked still resolves - to an index that now
     * points at nothing.  Every open on it then fails with
     * {@code paInvalidDevice}, for the rest of the process's life, however many
     * times the card is plugged back in - the observed failure on the bench is
     * {@code Pa_OpenStream(input) failed: Invalid device (-9996)} on a card that
     * was unplugged and replugged.  {@code Pa_Terminate} + {@code Pa_Initialize}
     * is the only refresh PortAudio offers.
     *
     * <p><b>Why it refuses while a stream is open.</b>  There is ONE PortAudio
     * library in this process and WDM-KS and CoreAudio share it.  Terminating it
     * frees every open {@code PaStream*}, including one another backend's audio
     * thread is inside - a native crash, not a catchable failure.  So the count
     * of live streams is the gate, and it is raised before the open rather than
     * after it, which closes the window an open in flight would otherwise have.
     * A refusal is not a failure: the caller's list is simply as stale as it was.
     *
     * <p>The {@code Pa_Terminate} shutdown hook installed by {@link #lib()} is
     * NOT re-registered here - {@link #initialized} deliberately stays true, so
     * the single hook installed on first use still matches the single live
     * initialisation at exit.
     *
     * @return true when the snapshot was rebuilt
     */
    public static synchronized boolean refreshDevices() { // static-ok: process-wide state of the one shared PortAudio library
        if (!initialized || LIB == null) {
            return false;
        }
        int live = LIVE_STREAMS.get();
        if (live > 0) {
            if (log.isWarnEnabled()) {
                log.warn("PortAudio device list NOT refreshed: {} stream(s) still open - "
                        + "Pa_Terminate would free them under their owners", live);
            }
            return false;
        }
        LIB.Pa_Terminate();
        int rc = LIB.Pa_Initialize();
        if (rc != paNoError) {
            initialized = false;
            throw new IllegalStateException(
                    "Pa_Initialize failed while refreshing the device list: " + LIB.Pa_GetErrorText(rc));
        }
        if (log.isInfoEnabled()) {
            log.info("PortAudio device list refreshed ({} devices)", LIB.Pa_GetDeviceCount());
        }
        return true;
    }

    /** Library names to try in order.  JNA prepends {@code lib} and appends
     *  the platform extension automatically, so we only need the bare core
     *  here.  On Windows the arch-suffixed name matches the historical builds -
     *  {@code portaudio_x86} on a 32-bit JVM (the legacy x86 fat jar),
     *  {@code portaudio_x64} otherwise - with a plain {@code portaudio}
     *  fall-back; standard distributions on Linux/macOS use plain
     *  {@code portaudio}. */
    private static String[] candidateLibraryNames() { // static-ok: pure OS/arch->libname map, native-interop
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean is32 = arch.equals("x86") || arch.contains("i386") || arch.contains("i686");
            return is32 ? new String[] { "portaudio_x86", "portaudio" }
                        : new String[] { "portaudio_x64", "portaudio" };
        }
        return new String[] { "portaudio" };
    }

    public static synchronized Lib lib() { // static-ok: process-global native holder, one Pa_Initialize per process
        if (LIB == null) {
            // OS gate BEFORE any load attempt: the PortAudio-backed backends
            // exist on Windows (WDM-KS) and macOS (CoreAudio) only, and the
            // platform fat jars carry no PortAudio binary for anything else -
            // a load attempt elsewhere could only ever fail, with a message
            // blaming a library that was never meant to be there.
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("win") && !os.contains("mac")) {
                throw new IllegalStateException(
                        "PortAudio-backed backends are not available on " + System.getProperty("os.name"));
            }
            if (log.isInfoEnabled()) {
                log.info("Loading PortAudio: candidates={}, jna.library.path={}, java.library.path={}",
                        Arrays.toString(candidateLibraryNames()),
                        System.getProperty("jna.library.path"),
                        System.getProperty("java.library.path"));
            }
            UnsatisfiedLinkError last = null;
            for (String name : candidateLibraryNames()) {
                try {
                    LIB = Native.load(name, Lib.class);
                    if (log.isInfoEnabled()) {
                        File loaded = NativeLibrary.getInstance(name).getFile();
                        log.info("PortAudio loaded '{}' from {}", name,
                                loaded != null ? loaded.getAbsolutePath() : "(OS resolver - no file path)");
                    }
                    break;
                } catch (UnsatisfiedLinkError ule) {
                    last = ule;
                }
            }
            if (LIB == null) {
                throw new IllegalStateException(
                        "Could not load PortAudio native library "
                                + "(tried " + Arrays.toString(candidateLibraryNames())
                                + " on jna.library.path=" + System.getProperty("jna.library.path")
                                + ", java.library.path=" + System.getProperty("java.library.path") + ")",
                        last);
            }
            // Surface any exception thrown from a callback so it isn't silently dropped.
            Native.setCallbackExceptionHandler((cb, ex) ->
                    log.error("JNA callback threw (class={})", cb.getClass().getName(), ex));
        }
        if (!initialized) {
            int rc = LIB.Pa_Initialize();
            if (rc != paNoError) {
                throw new IllegalStateException(
                        "Pa_Initialize failed: " + LIB.Pa_GetErrorText(rc));
            }
            initialized = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { LIB.Pa_Terminate(); } catch (Throwable ignored) {}
            }, "portaudio-terminate"));
        }
        return LIB;
    }

    public static String errorText(int code) {
        return lib().Pa_GetErrorText(code);
    }

    public static void check(int code, String op) {
        if (code != paNoError) {
            StringBuilder msg = new StringBuilder()
                    .append(op).append(" failed: ")
                    .append(errorText(code)).append(" (").append(code).append(')');
            // -9999 = paUnanticipatedHostError: PortAudio forwards host-specific errors
            // here, so pull the underlying WDM-KS / WASAPI code to make the failure
            // diagnosable (exclusive-mode conflict vs. unsupported format vs. ...).
            if (code == -9999) {
                PaHostErrorInfo info = lib().Pa_GetLastHostErrorInfo();
                if (info != null) {
                    msg.append("  host-error: ")
                       .append(info.errorText)
                       .append(" (code=").append(info.errorCode).append(")");
                }
            }
            // paInvalidDevice on a device the operator just picked from OUR OWN
            // list means one thing: the list is PortAudio's start-up snapshot and
            // the hardware has changed under it (see refreshDevices).  This is
            // the one funnel every Pa_OpenStream / Pa_IsFormatSupported result
            // passes through, and the one moment we KNOW our own stream is not
            // open - so it is where the snapshot is rebuilt.  Deliberately no
            // retry here: the caller's DeviceRef still carries the stale index,
            // and the next attempt re-resolves the device by name off the fresh
            // list.  That is what turns a succeeding second attempt from luck
            // into the documented behaviour.
            if (code == paInvalidDevice) {
                msg.append("  - PortAudio enumerates devices once, at start-up, and this device is "
                        + "no longer at the position it had then (unplugged / replugged since). "
                        + "The device list has now been rescanned");
                if (!refreshDevices()) {
                    msg.append(" - no, NOT rescanned: another PortAudio stream is still open. "
                            + "Stop the other measurement and try again");
                }
                msg.append("; try again.");
            }
            throw new IllegalStateException(msg.toString());
        }
    }

    /**
     * What a PortAudio failure MEANT, for the one operator-facing sentence the
     * GUI renders.  The raw code stays in the log; this is the reason beside it.
     *
     * <p>It lives here, next to {@link #check}, because check is what WRITES the
     * text - "Pa_OpenStream(output) failed: Invalid device (-9996)" - and the
     * reader of a format belongs with its writer.  WDM-KS and CoreAudio both
     * fail through the same funnel and would otherwise carry two copies of this
     * table; their managers delegate here instead.
     *
     * <p>The message is read rather than an int, because the code is not
     * preserved: {@code check} throws a plain {@link IllegalStateException} and
     * the number survives only as the text it printed.  Everything unmapped -
     * {@code paUnanticipatedHostError} above all, whose real cause is a
     * host-specific code this class does not speak - is
     * {@link DeviceFailureReason#UNKNOWN}, which is always a legal answer.
     */
    public static DeviceFailureReason classifyFailure(Throwable failure) { // static-ok: reads the text this all-static binding itself writes
        String text = (failure == null) ? null : failure.getMessage();
        if (text == null) {
            return DeviceFailureReason.UNKNOWN;
        }
        if (text.contains(LOAD_FAILED_TEXT)) {
            // No library, no devices: nothing this backend could have opened.
            return DeviceFailureReason.DEVICE_NOT_FOUND;
        }
        Matcher matcher = ERROR_CODE.matcher(text);
        if (!matcher.find()) {
            return DeviceFailureReason.UNKNOWN;
        }
        switch (Integer.parseInt(matcher.group(1))) {
            case paInvalidDevice:
                // The device list is PortAudio's start-up snapshot and the card
                // is no longer where it was - unplugged since (see check()).
                return DeviceFailureReason.DEVICE_DISCONNECTED;
            case paDeviceUnavailable:
                return DeviceFailureReason.DEVICE_IN_USE;
            case paInvalidSampleRate:
            case paSampleFormatNotSupported:
            case paInvalidChannelCount:
                return DeviceFailureReason.FORMAT_UNSUPPORTED;
            case paTimedOut:
                return DeviceFailureReason.DEVICE_NOT_ANSWERING;
            default:
                return DeviceFailureReason.UNKNOWN;
        }
    }

    /** Returns the PortAudio sample-format constant for a signed-PCM bit depth. */
    public static NativeLong paSampleFormatFor(int bitDepth) {
        switch (bitDepth) {
            case 8:  return paInt8;
            case 16: return paInt16;
            case 24: return paInt24;
            case 32: return paInt32;
            default: throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }
    }
}
