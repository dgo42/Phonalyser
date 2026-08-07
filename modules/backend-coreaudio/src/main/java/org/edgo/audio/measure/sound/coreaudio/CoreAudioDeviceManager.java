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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.PortAudio;
import org.edgo.audio.measure.sound.wdmks.WdmksDeviceManager;

import lombok.extern.log4j.Log4j2;

/**
 * Device discovery + HAL-read format capabilities for the {@link AudioBackendType#COREAUDIO}
 * backend (macOS only).  Constructed and owned by {@link AudioBackend}; do not
 * instantiate directly.
 *
 * <p>Enumerates devices belonging to PortAudio's {@code paCoreAudio} host API
 * and routes capture/playback through {@link CoreAudioRecorder} /
 * {@link CoreAudioGenerator}.  It is the macOS counterpart of the Windows
 * {@link WdmksDeviceManager} (same PortAudio binding, different host API) but is
 * kept entirely separate so the Windows path is never touched.  Requires
 * {@code libportaudio.dylib} on {@code java.library.path} (bundled in
 * {@code lib/macos/}).
 *
 * <p>The {@code index()} of the returned {@link CoreAudioDeviceRef} is the slot
 * inside the CoreAudio device list (so {@code --device 0} is the first CoreAudio
 * device, not the global PortAudio index).
 */
@Log4j2
public class CoreAudioDeviceManager implements AudioDeviceManager {

    /** {@link DeviceRef} backed by a PortAudio CoreAudio device index. */
    public record CoreAudioDeviceRef(int index, String name, String description, String vendor,
                                     boolean isInput, boolean isOutput,
                                     int paDeviceIndex, double defaultSampleRate,
                                     int maxInputChannels)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.COREAUDIO;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }

    /** The HAL capability reader every format answer comes from. */
    private final CoreAudioHal hal;

    /** Per-device format answers, cached until a REAL device-list rebuild - the
     *  only event on this backend after which a device's declared formats can
     *  differ, because the PortAudio snapshot (and the device ids behind it)
     *  only changes then.  Without this cache every enumeration walks HAL
     *  property reads per device and direction, and a server enumerates every
     *  two seconds UNDER A LIVE CAPTURE - continuous native load inside
     *  coreaudiod's domain for answers that cannot have changed. */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    /** The HAL device set the PortAudio snapshot this manager lists from was
     *  built against - what {@link #deviceListStale()} compares today's HAL truth
     *  with.  Taken at construction, which is as close to {@code Pa_Initialize}
     *  as this class can get (the manager is built lazily, immediately before the
     *  first enumeration, and that enumeration is what initialises PortAudio),
     *  and re-taken on every rebuild that really happened.  Volatile: written on
     *  the thread that rebuilds, read on a server's rescan lane. */
    private volatile Set<String> snapshotDevices;

    public CoreAudioDeviceManager() {
        this(new CoreAudioHal());
    }

    /** Injected so a test can drive the capability reader without the framework. */
    CoreAudioDeviceManager(CoreAudioHal hal) {
        this.hal = hal;
        this.snapshotDevices = hal.deviceIdentities();
    }

    private int coreAudioHostApiIndex() {
        int idx = PortAudio.lib().Pa_HostApiTypeIdToHostApiIndex(PortAudio.paCoreAudio);
        if (idx < 0) {
            throw new IllegalStateException(
                    "CoreAudio host API not available in this PortAudio build (rc=" + idx + ")");
        }
        return idx;
    }

    public List<DeviceRef> listInputDevices() {
        return list(true);
    }

    public List<DeviceRef> listOutputDevices() {
        return list(false);
    }

    /** PortAudio's enumeration is a process-lifetime snapshot, so this is one of
     *  the two backends (with WDM-KS) where a rebuild means something.  The
     *  shared library refuses while any PortAudio stream is open - an open
     *  {@code PaStream*} would be freed under its owner. */
    @Override
    public boolean refreshDeviceList() {
        boolean rebuilt = PortAudio.refreshDevices();
        if (rebuilt) {
            // The new snapshot describes THIS device set.  Only a rebuild that
            // really happened may say so: a refusal (another PortAudio stream is
            // open, so terminating the library would free it under its owner)
            // has to leave the old truth standing, or the next poll would call a
            // list that is still stale current and never try again.
            snapshotDevices = hal.deviceIdentities();
            // A replugged device is a new audio object: the formats read under
            // its old identity may no longer describe it.
            inputFormatsCache.clear();
            outputFormatsCache.clear();
        }
        return rebuilt;
    }

    /**
     * Whether the PortAudio snapshot this manager lists from still describes the
     * machine - the guard a poll puts in front of {@link #refreshDeviceList()}.
     *
     * <p>PortAudio enumerates once and never rescans, so its own list can never
     * report a device pulled out or plugged back in; the HAL is read fresh every
     * time and does.  The comparison is therefore HAL against HAL: the device set
     * the current snapshot was built against, versus the set right now.
     *
     * <p><b>Never PortAudio's list against the HAL's.</b>  The two name and filter
     * devices by their own rules - the HAL lists every audio object, while this
     * manager publishes only what has capture or playback channels - so a
     * permanent difference between them is normal on a healthy machine, and a
     * check built on it would ask for a rebuild on every tick for ever.  A
     * DIFFERENCE OVER TIME in one source cannot say that.
     *
     * <p>False while the HAL cannot answer (no framework, a failed read): an
     * empty answer is "do not know", and reading it as "every device vanished"
     * would re-initialise the library on a host that merely failed to be asked.
     */
    @Override
    public boolean deviceListStale() {
        Set<String> live = hal.deviceIdentities();
        return !live.isEmpty() && !live.equals(snapshotDevices);
    }

    private List<DeviceRef> list(boolean input) {
        List<DeviceRef> out = new ArrayList<>();
        PortAudio.Lib lib = PortAudio.lib();
        int hostApi = coreAudioHostApiIndex();
        PortAudio.PaHostApiInfo apiInfo = lib.Pa_GetHostApiInfo(hostApi);
        if (apiInfo == null) return out;

        int slot = 0;
        for (int i = 0; i < apiInfo.deviceCount; i++) {
            int paDev = lib.Pa_HostApiDeviceIndexToDeviceIndex(hostApi, i);
            if (paDev < 0) continue;
            PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(paDev);
            if (info == null) continue;
            if (log.isDebugEnabled()) {
                log.debug("CoreAudio device [{}] '{}': inCh={} outCh={} defRate={}",
                        paDev, info.name, info.maxInputChannels, info.maxOutputChannels, info.defaultSampleRate);
            }
            // Inputs: accept mono (>=1) too - CoreAudioRecorder upmixes a
            // single channel to stereo.  Outputs still require >=2.
            boolean canIn  = info.maxInputChannels  >= 1;
            boolean canOut = info.maxOutputChannels >= 2;
            if (input ? !canIn : !canOut) continue;
            out.add(new CoreAudioDeviceRef(slot++, info.name,
                    "CoreAudio", apiInfo.name,
                    canIn, canOut, paDev, info.defaultSampleRate,
                    info.maxInputChannels));
        }
        return out;
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        List<DeviceRef> all = isOutput ? listOutputDevices() : listInputDevices();
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("CoreAudio device index out of range: " + index
                    + " (have " + all.size() + " " + (isOutput ? "output" : "input") + " devices)");
        }
        return all.get(index);
    }

    /**
     * What the DEVICE declares, read from the CoreAudio HAL - the real (rate,
     * bit width) pairs of its physical stream formats, never a probe and never
     * a canned list.  {@code Pa_IsFormatSupported} cannot answer this: it asks
     * through AUHAL's converter, which resamples silently and says yes to
     * nearly everything - that is how a bench came to see every rate from
     * 8 kHz to 768 kHz offered on a device.
     *
     * <p>Empty when the HAL cannot answer for this device - the honest
     * nothing, same rule as the Linux legacy-card case ({@link CoreAudioHal}).
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof CoreAudioDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        return cache.computeIfAbsent(d.name(), name -> readFormats(name, output));
    }

    private List<AudioFormat> readFormats(String deviceName, boolean output) {
        List<AudioFormat> result = new ArrayList<>();
        for (CoreAudioHal.PhysicalFormat format : hal.physicalFormats(deviceName, output)) {
            int bytesPerSample = (format.bits() + 7) / 8;
            result.add(new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.rate(), format.bits(), 2, bytesPerSample * 2, format.rate(), false));
        }
        return result;
    }

    /** The HAL travels with the stream: the line pins the device's own volume
     *  to its 0 dB as it opens, and the reader that does it is this manager's. */
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return new CoreAudioRecorder((CoreAudioDeviceManager.CoreAudioDeviceRef) device, sampleRate, bitDepth, hal);
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        return new CoreAudioGenerator((CoreAudioDeviceManager.CoreAudioDeviceRef) device, sampleRate, bitDepth, ditherBits, hal);
    }

    /** CoreAudio speaks the same PortAudio error vocabulary as WDM-KS - one
     *  table, in the class that writes those messages, not a copy here. */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        return PortAudio.classifyFailure(failure);
    }

}
