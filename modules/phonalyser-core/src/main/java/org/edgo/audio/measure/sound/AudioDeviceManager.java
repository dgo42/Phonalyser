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

import java.util.List;

import javax.sound.sampled.AudioFormat;

/**
 * Common contract of the per-backend device managers (WASAPI, WDM-KS,
 * JavaSound, CoreAudio, QA40x): device discovery, format probing, and the
 * capture / playback stream factories — plus the app-exit lifecycle.  One
 * manager exists per backend, lazily constructed and owned by
 * {@link AudioBackend}, which dispatches every call on the active (or
 * caller-supplied) backend type so the rest of the code stays
 * backend-agnostic behind {@link DeviceRef}, {@link AudioCapture} and
 * {@link AudioPlayback}.
 *
 * <p>Enumeration must degrade gracefully: no device attached, or a missing
 * native binding, yields empty lists — never a throw.
 */
public interface AudioDeviceManager {

    /** App-exit teardown; default no-op — the OS reclaims ordinary handles on
     *  process death.  A backend whose DEVICE carries state across process
     *  death overrides it (the QA40x leaves its ranges at maximum
     *  attenuation, see {@code Qa40xDeviceManager}).  Called explicitly from
     *  the exit path, never from a JVM shutdown hook — this app skips those
     *  (see {@code GuiMain}) — and it must never throw or block: exit cannot
     *  be held up by audio teardown. */
    default void shutdown() { }

    /** Capture-capable devices of this backend, in stable slot order — a
     *  {@link DeviceRef#index()} is the slot in THIS list, not any global
     *  index; empty when none. */
    List<DeviceRef> listInputDevices();

    /** Playback-capable devices of this backend; same contract as
     *  {@link #listInputDevices()}. */
    List<DeviceRef> listOutputDevices();

    /** The device at {@code index} of the input or output list; throws
     *  {@link IllegalArgumentException} when out of range. */
    DeviceRef getDeviceByIndex(int index, boolean isOutput);

    /** The formats the device reports usable for capture ({@code output} =
     *  {@code false}) or playback ({@code true}); empty for a
     *  {@link DeviceRef} belonging to another backend. */
    List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output);

    /** Creates an UNOPENED capture stream on {@code device} — the caller
     *  drives the open / start / stop / close lifecycle. */
    AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth);

    /** Creates an UNOPENED playback stream on {@code device};
     *  {@code ditherBits} is the TPDF depth applied at quantisation
     *  ({@code 0} = off). */
    AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits);

    // A backend with settings of its own (the QA40x expansion port, say) used to
    // declare hasCustomPreferences() / openCustomPreferences(Shell) here, which
    // put SWT in the signature of the core audio contract and made a headless
    // build impossible.  Those settings UIs are now discovered from the GUI side
    // through their own service contract, keyed by backend type, so this
    // interface no longer knows a toolkit exists.
}
