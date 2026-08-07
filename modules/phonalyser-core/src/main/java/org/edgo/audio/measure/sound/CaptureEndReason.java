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

/**
 * WHY a capture ended on its own - the machine-readable value the bottom layer
 * hands up through {@link AudioCapture.PcmBatchListener#captureEnded}.  The UI
 * localizes the ENUM (i18n lives in the GUI and nowhere below it); the English
 * template here is for the LOG only, never for the operator - no layer below
 * the GUI ever hands a display string upward.
 */
public enum CaptureEndReason {

    /** The device was unplugged, invalidated, or its stream failed in a way
     *  the backend cannot recover from. */
    DEVICE_LOST("the capture device was unplugged or its stream failed"),

    /** A started stream delivered nothing for longer than its deadline - the
     *  loss signal for backends whose device tells them nothing. */
    DELIVERY_STALLED("the capture delivered nothing within its deadline");

    /** English, log-only.  Details (an HRESULT, a millisecond count) go to the
     *  log beside it - they never reach the operator. */
    private final String logText;

    private CaptureEndReason(String logText) {
        this.logText = logText;
    }

    public String logText() {
        return logText;
    }
}
