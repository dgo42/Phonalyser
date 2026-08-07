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

import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.DeviceFailureReason;

/**
 * Common contract of the per-backend device managers (WASAPI, WDM-KS,
 * JavaSound, CoreAudio, QA40x): device discovery, format probing, and the
 * capture / playback stream factories - plus the app-exit lifecycle.  One
 * manager exists per backend, lazily constructed and owned by
 * {@link AudioBackend}, which dispatches every call on the active (or
 * caller-supplied) backend type so the rest of the code stays
 * backend-agnostic behind {@link DeviceRef}, {@link AudioCapture} and
 * {@link AudioPlayback}.
 *
 * <p>Enumeration must degrade gracefully: no device attached, or a missing
 * native binding, yields empty lists - never a throw.
 */
public interface AudioDeviceManager {

    // --- backend lifecycle ---------------------------------------------------
    // The pair below brackets the application's use of a backend: setup() when
    // the host takes the backend up, shutdown() when it lets it go.  Both are
    // default no-ops, because most backends have nothing to do at either end -
    // a host API that owns no state beyond an open line is left in a known state
    // by closing the lines, which the streams already do.  A backend implements
    // one only when its HARDWARE (or its driver) keeps state that outlives the
    // stream, or the process, and that state has a safe value.

    /**
     * Brings this backend to a known, safe, idle state as the application takes
     * it up.
     *
     * <p>A device can outlive the process that was driving it, and some carry
     * settings that survive being handed from one host to the next - so what a
     * backend enumerates at start-up is not necessarily in any state this
     * application chose.  This is where a backend that has such state puts it
     * right, BEFORE anything offers the device to a user or a client.
     *
     * <p><b>Guarantees every implementation owes its caller.</b>
     * <ul>
     *   <li><b>Idempotent</b> - calling it twice is the same as calling it once,
     *       and calling it on a backend that is already idle changes nothing.</li>
     *   <li><b>Safe with no hardware</b> - no device attached, or no native
     *       binding on this host, is a silent no-op that answers false.  It never
     *       throws and it never loads a driver that is not there.</li>
     *   <li><b>Leaves nothing claimed</b> - whatever it had to open in order to
     *       do its work, it gives back.  A host that held a device from boot
     *       would stop that device being used anywhere else.</li>
     *   <li><b>Catches {@link Throwable} at its own boundary</b> - it sits on the
     *       host's start-up path, where a failure to reach one device must not
     *       stop the application coming up.</li>
     * </ul>
     *
     * <p>It does NOT ask whether something is already USING the device;
     * establishing that belongs to the caller, which is the only side that knows
     * who else it has handed the hardware to.
     *
     * @return true when this backend found hardware and set it up, so the caller
     *         can say so in its own log; false when there was nothing to do
     */
    default boolean setup() {
        return false;
    }

    /**
     * Leaves this backend in a known, safe, idle state as the application lets it
     * go - the closing half of {@link #setup()}.
     *
     * <p>Default no-op: the OS reclaims ordinary handles on process death, so a
     * backend whose devices keep no state of their own has nothing to do here.  A
     * backend overrides it when its hardware would otherwise be LEFT somewhere -
     * a setting that survives the process, a claim another host then cannot take.
     *
     * <p>It owes the same guarantees {@link #setup()} lists, and one more: it
     * must never block.  Called explicitly from the exit path, never from a JVM
     * shutdown hook on the desktop (this app skips those - see {@code GuiMain});
     * the headless server does call it from its stop path, which its service
     * wrapper drives with a graceful stop for exactly this reason.
     */
    default void shutdown() { }

    /** Capture-capable devices of this backend, in stable slot order - a
     *  {@link DeviceRef#index()} is the slot in THIS list, not any global
     *  index; empty when none. */
    List<DeviceRef> listInputDevices();

    /** Playback-capable devices of this backend; same contract as
     *  {@link #listInputDevices()}. */
    List<DeviceRef> listOutputDevices();

    /**
     * Rebuilds this backend's device enumeration, when it is a snapshot that can
     * go stale.  Some host APIs enumerate live on every list call; others
     * (PortAudio) enumerate ONCE and answer from that snapshot for the process's
     * life, so a card unplugged or replugged after it was taken simply does not
     * exist to them until the snapshot is rebuilt.
     *
     * <p>Called on the two occasions the application has reason to distrust the
     * list: a saved device name that no longer resolves against it, and the
     * operator's explicit device scan.  Never called on a hot path.
     *
     * <p>Default {@code false}: the list is always current (or cannot be rebuilt),
     * and a re-list would return what the last one did.  An implementation answers
     * {@code true} only when the snapshot was actually rebuilt, so the caller
     * knows a re-list is worth making.  It must refuse (answer {@code false})
     * rather than endanger open streams, and it never throws.
     */
    default boolean refreshDeviceList() {
        return false;
    }

    /**
     * Whether this backend's device list has gone STALE under it - the machine's
     * hardware moved and the enumeration this manager answers from has not caught
     * up.  The guard that belongs in front of {@link #refreshDeviceList()}
     * wherever the rebuild is not an operator's explicit gesture but a POLL.
     *
     * <p>Only a snapshot backend can ever answer true, and only one that has a
     * SECOND, live source to check its snapshot against: PortAudio's enumeration
     * is taken once at start-up, so a card pulled out stays in it and a card
     * plugged in never enters it - and nothing in PortAudio itself will say so.
     * On macOS the CoreAudio HAL is that second source.
     *
     * <p>Asked on a timer, so it must be cheap, and it must never throw - a
     * backend that cannot tell answers false rather than have every tick rebuild
     * a native library for nothing.
     *
     * <p>Default {@code false}: a list that is enumerated live is never stale.
     */
    default boolean deviceListStale() {
        return false;
    }

    /** The device at {@code index} of the input or output list; throws
     *  {@link IllegalArgumentException} when out of range. */
    DeviceRef getDeviceByIndex(int index, boolean isOutput);

    /** The formats the device reports usable for capture ({@code output} =
     *  {@code false}) or playback ({@code true}); empty for a
     *  {@link DeviceRef} belonging to another backend. */
    List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output);

    /** Creates an UNOPENED capture stream on {@code device} - the caller
     *  drives the open / start / stop / close lifecycle. */
    AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth);

    /** Creates an UNOPENED playback stream on {@code device};
     *  {@code ditherBits} is the TPDF depth applied at quantisation
     *  ({@code 0} = off). */
    AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits);

    /**
     * WHY an open or start on this backend failed, as something an operator can
     * be told - the backend's own reading of its own error.
     *
     * <p>Each backend knows its vocabulary and nothing else does: a PortAudio
     * code, an HRESULT, a {@code LineUnavailableException} and a libusb status
     * mean different things and are recognised by different code.  That is why
     * this is asked of the manager instead of parsed centrally - a backend
     * added later brings its own mapping with it, and the layers above it never
     * learn a single driver code.
     *
     * <p>The reason type lives in the WIRE module: the same classification has
     * to reach the operator whether the device is local or on a bench across
     * the network, so both sides share one vocabulary.
     *
     * <p>The default answers {@link DeviceFailureReason#UNKNOWN}, which is
     * always legal: a backend that cannot tell says so, the operator gets the
     * honest "reason unknown", and the raw throwable is in the log either way.
     * Never throws - it runs on a failure path that is already handling one.
     */
    default DeviceFailureReason classifyFailure(Throwable failure) {
        return DeviceFailureReason.UNKNOWN;
    }

    // A backend with settings of its own (the QA40x expansion port, say) used to
    // declare hasCustomPreferences() / openCustomPreferences(Shell) here, which
    // put SWT in the signature of the core audio contract and made a headless
    // build impossible.  Those settings UIs are now discovered from the GUI side
    // through their own service contract, keyed by backend type, so this
    // interface no longer knows a toolkit exists.
}
