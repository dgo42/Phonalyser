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
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Minimal JNA binding to the PortAudio shared library.  Only exposes the
 * functions needed by {@link WdmksRecorder} and {@link WdmksGenerator}.
 *
 * <p>The library file name is platform-dependent — Windows looks for
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

    /** Sample formats — bitfield values from PortAudio's {@code pa_common.h}. */
    public static final NativeLong paFloat32 = new NativeLong(0x00000001L);
    public static final NativeLong paInt32   = new NativeLong(0x00000002L);
    public static final NativeLong paInt24   = new NativeLong(0x00000004L);
    public static final NativeLong paInt16   = new NativeLong(0x00000008L);
    public static final NativeLong paInt8    = new NativeLong(0x00000010L);
    public static final NativeLong paUInt8   = new NativeLong(0x00000020L);

    /** Host API type IDs — from {@code PaHostApiTypeId} in {@code portaudio.h}. */
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
     * PortAudio stream callback. Invoked on PortAudio's realtime audio thread —
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

    private PortAudio() {}

    /** Library names to try in order.  JNA prepends {@code lib} and appends
     *  the platform extension automatically, so we only need the bare core
     *  here.  The {@code _x64}-suffixed name matches the historical Windows
     *  build of PortAudio; standard distributions on Linux/macOS use plain
     *  {@code portaudio}. */
    private static String[] candidateLibraryNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new String[] { "portaudio_x64", "portaudio" };
        }
        return new String[] { "portaudio" };
    }

    public static synchronized Lib lib() {
        if (LIB == null) {
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
                                loaded != null ? loaded.getAbsolutePath() : "(OS resolver — no file path)");
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
            // diagnosable (exclusive-mode conflict vs. unsupported format vs. …).
            if (code == -9999) {
                PaHostErrorInfo info = lib().Pa_GetLastHostErrorInfo();
                if (info != null) {
                    msg.append("  host-error: ")
                       .append(info.errorText)
                       .append(" (code=").append(info.errorCode).append(")");
                }
            }
            throw new IllegalStateException(msg.toString());
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
