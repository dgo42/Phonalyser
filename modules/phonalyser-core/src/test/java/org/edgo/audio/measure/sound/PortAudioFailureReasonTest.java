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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.junit.jupiter.api.Test;

/**
 * {@link PortAudio#classifyFailure(Throwable)} - the reading of the failure
 * text {@link PortAudio#check(int, String)} itself writes, shared by the WDM-KS
 * and CoreAudio managers.
 *
 * <p>No hardware and no native library: the classification is a pure read of an
 * exception's message, which is precisely why it can be pinned here.  The
 * messages below are the real shapes - {@code op + " failed: " + errorText +
 * " (" + code + ")"}, with the two long tails {@code check} appends for
 * {@code paInvalidDevice} and {@code paUnanticipatedHostError}.
 */
class PortAudioFailureReasonTest {

    /** A real driver line, tail included. */
    private static final String INVALID_DEVICE =
            "Pa_OpenStream(input) failed: Invalid device (-9996)"
            + "  - PortAudio enumerates devices once, at start-up, and this device is "
            + "no longer at the position it had then (unplugged / replugged since). "
            + "The device list has now been rescanned; try again.";
    private static final String HOST_ERROR =
            "Pa_OpenStream(output) failed: Unanticipated host error (-9999)"
            + "  host-error: Exclusive mode not allowed (code=-2004287480)";
    private static final String FORMAT_REFUSED =
            "Pa_IsFormatSupported(output 384000 Hz / 32 bit) failed: Invalid sample rate (-9997)";
    private static final String SAMPLE_FORMAT_REFUSED =
            "Pa_OpenStream(output) failed: Sample format not supported (-9994)";
    private static final String DEVICE_UNAVAILABLE =
            "Pa_OpenStream(output) failed: Device unavailable (-9985)";
    private static final String TIMED_OUT =
            "Pa_StartStream(output) failed: Wait timed out (-9987)";
    private static final String NO_LIBRARY =
            "Could not load PortAudio native library (tried [portaudio_x64, portaudio] "
            + "on jna.library.path=null, java.library.path=lib/win)";

    @Test
    void aDeviceThatMovedUnderTheSnapshotReadsAsDisconnected() {
        assertEquals(DeviceFailureReason.DEVICE_DISCONNECTED, reasonOf(INVALID_DEVICE));
    }

    @Test
    void aRefusedFormatReadsAsFormatUnsupported() {
        assertEquals(DeviceFailureReason.FORMAT_UNSUPPORTED, reasonOf(FORMAT_REFUSED));
        assertEquals(DeviceFailureReason.FORMAT_UNSUPPORTED, reasonOf(SAMPLE_FORMAT_REFUSED));
    }

    @Test
    void aDeviceSomebodyElseHoldsReadsAsInUse() {
        assertEquals(DeviceFailureReason.DEVICE_IN_USE, reasonOf(DEVICE_UNAVAILABLE));
    }

    @Test
    void anOpenThatNeverCameBackReadsAsNotAnswering() {
        assertEquals(DeviceFailureReason.DEVICE_NOT_ANSWERING, reasonOf(TIMED_OUT));
    }

    @Test
    void noLibraryAtAllReadsAsNotFound() {
        assertEquals(DeviceFailureReason.DEVICE_NOT_FOUND, reasonOf(NO_LIBRARY));
    }

    /**
     * The host error is deliberately NOT guessed at: its real cause is a
     * host-specific code this class does not speak, and the parenthesised
     * "(code=...)" must not be mistaken for a PortAudio code either.
     */
    @Test
    void aHostSpecificErrorStaysUnknown() {
        assertEquals(DeviceFailureReason.UNKNOWN, reasonOf(HOST_ERROR));
    }

    @Test
    void aFailureWithNothingToReadIsUnknownAndNeverThrows() {
        assertEquals(DeviceFailureReason.UNKNOWN, PortAudio.classifyFailure(null));
        assertEquals(DeviceFailureReason.UNKNOWN,
                PortAudio.classifyFailure(new IllegalStateException()));
        assertEquals(DeviceFailureReason.UNKNOWN,
                reasonOf("WDM-KS host API not available in this PortAudio build (rc=-9979)"));
    }

    private DeviceFailureReason reasonOf(String message) {
        return PortAudio.classifyFailure(new IllegalStateException(message));
    }
}
