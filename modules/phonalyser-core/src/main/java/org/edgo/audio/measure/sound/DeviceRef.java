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

import org.edgo.audio.measure.enums.AudioBackendType;
/**
 * Backend-agnostic handle to an audio device. Used by {@link AudioBackend}
 * to open capture / playback streams.
 *
 * <p>For {@link AudioBackendType#CSJSOUND} the implementation wraps a
 * {@code javax.sound.sampled.Mixer.Info}; for {@link AudioBackendType#WDMKS}
 * it wraps a PortAudio device index plus the host-API descriptor.
 */
public interface DeviceRef {
    /** Index within its backend's listing (used by {@code --device <i>}). */
    int index();

    String name();

    String description();

    String vendor();

    /** What this device IS - the TRUE backend type, local or remote alike: a
     *  QA403 answers {@link AudioBackendType#QA40X} whether it hangs on this
     *  machine's USB or on a bench across the network.  Type-specific control
     *  (the QA40x equal-rates rule, a settings panel, a custom-command
     *  analyzer) keys on THIS, never on {@link #carrier()}. */
    AudioBackendType backend();

    /** How this device is REACHED - the manager {@code AudioBackend} must
     *  dispatch at: {@link #backend()} itself for a local device, the
     *  {@link AudioBackendType#isDualLevel() dual-level} carrier for a remote
     *  one (a remote ref overrides this with {@link AudioBackendType#NET}).
     *  Every {@code manager(...)} lookup goes through the carrier; asking
     *  {@code manager(backend())} for a remote device would open the LOCAL
     *  driver of the same name. */
    default AudioBackendType carrier() {
        return backend();
    }

    /** True when this device is plugged into ANOTHER machine - reached through a
     *  {@link AudioBackendType#isDualLevel() dual-level} carrier.  Such a device
     *  is calibrated where it lives (net spec 4.3 {@code cal}) and by nothing
     *  else: this installation's card store knows nothing about that bench and
     *  could only ever match it by a name collision. */
    default boolean remote() {
        return carrier().isDualLevel();
    }

    /**
     * What makes this the SAME physical device between two enumerations - a
     * comparison key, never a display string.
     *
     * <p>The name is enough for a sound card: its host API renames nothing, and
     * a card that was pulled out is simply absent from the next list.  It is NOT
     * enough for a QA40x on Windows, where the analyzer answers at the same
     * index under the same model name across an unplug and a re-attach - so a
     * hot-plug comparison drawn on the ref's own four fields sees no change at
     * all and no client is ever told the device moved.  A backend that can say
     * more overrides this: the QA40x knows its USB bus and address, which is
     * exactly what a re-enumeration changes.
     */
    default String identity() {
        return name();
    }

    /** True if this handle is suitable for capture (input). */
    boolean isInput();

    /** True if this handle is suitable for playback (output). */
    boolean isOutput();

    /**
     * The full-scale RMS volts stored for this device AND this direction by the
     * machine the device is plugged into, or {@code null} when that machine has
     * no card for it.
     *
     * <p>Calibration lives where the device is connected: a device on a
     * Phonalyser server is calibrated in the SERVER's {@code devices.yaml} and
     * arrives already calibrated, so a client applies these values instead of
     * looking the device up in its own card store - which knows nothing about
     * that bench and could only match it by a name collision.  A device on this
     * machine answers {@code null} and the caller falls back to the legacy path
     * ({@code Preferences.applyInputDeviceProfile} /
     * {@code applyOutputDeviceProfile} keyed on the device name).
     */
    default DeviceCalibration calibration() {
        return null;
    }

    /**
     * The logical name of the card the machine this device is plugged into has
     * BOUND to it, or {@code null} when nothing is bound there.
     *
     * <p>The user's saved card choice (net spec 4.3 {@code card} /
     * {@code device.setCard}), which is what makes a QA402-vs-QA403 pick a
     * decision rather than a name-match guess.  It travels with the device for
     * the same reason the calibration does: the choice belongs where the device
     * is, so every client sees the one the operator made.  A device on this
     * machine answers {@code null} - its binding is read straight out of the
     * local store instead ({@code Preferences.boundCardName}).
     */
    default String boundCard() {
        return null;
    }

    /**
     * Whether the full scales of this device AND direction are the DEVICE's own -
     * a QA40x reads them from its own EEPROM, and neither the operator nor any
     * client may write them (net spec 4.3 {@code calFromDevice}).
     *
     * <p>It travels with a remote device so a client can show the analyzer's
     * numbers read-only instead of offering an edit the bench would refuse after
     * the operator had already typed a value.  A device on this machine answers
     * {@code false} and the question is asked of the local card store instead
     * ({@code Preferences.isAdcCalibrationFromDevice}).
     */
    default boolean calFromDevice() {
        return false;
    }

    /** The operator-facing line.  A device whose name already says everything
     *  carries no description, and an empty pair of parentheses would only
     *  decorate it with noise. */
    default String displayName() {
        String description = description();
        return description == null || description.isBlank()
                ? String.format("[%d] %s - %s", index(), name(), vendor())
                : String.format("[%d] %s (%s) - %s", index(), name(), description, vendor());
    }
}
