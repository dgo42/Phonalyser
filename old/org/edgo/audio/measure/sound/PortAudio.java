package org.edgo.audio.measure.sound;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal JNA binding to {@code portaudio_x64.dll}. Only exposes the
 * functions needed by {@link WdmksRecorder} and {@link WdmksGenerator}.
 *
 * <p>The DLL must be on {@code java.library.path} (the launch.json sets
 * this to {@code lib/}; drop {@code portaudio_x64.dll} there).
 *
 * <p>PortAudio uses C {@code unsigned long} for {@code framesPerBuffer},
 * {@code PaStreamFlags} and {@code PaSampleFormat}. On Windows that is
 * 32-bit, so this binding uses {@link NativeLong} (platform {@code long})
 * for those fields.
 */
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

    public static synchronized Lib lib() {
        if (LIB == null) {
            LIB = Native.load("portaudio_x64", Lib.class);
            // Surface any exception thrown from a callback so it isn't silently dropped.
            Native.setCallbackExceptionHandler((cb, ex) -> {
                System.err.println("JNA callback threw (class=" + cb.getClass().getName() + "): " + ex);
                ex.printStackTrace(System.err);
            });
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

    private PortAudio() {}
}
