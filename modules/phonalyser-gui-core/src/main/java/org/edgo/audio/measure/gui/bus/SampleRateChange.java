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

package org.edgo.audio.measure.gui.bus;

import org.edgo.audio.measure.preferences.BackendKey;

/**
 * Payload of {@link Events#PREFS_SAMPLE_RATE_CHANGED} and
 * {@link Events#PREFS_SAMPLE_RATE_SET}: one direction's audio FORMAT as the
 * Preferences dialog is being edited, tagged with BOTH the backend and the
 * resolved card it belongs to so a subscriber can key off whichever it
 * constrains - a whole backend, or one specific card.  Both ride ON the payload
 * because these events fire against the dialog's UNCOMMITTED working copy: the
 * subscriber cannot read the change off live Preferences yet, so it gates on
 * these fields rather than on current state.
 *
 * <p>The backend is the whole SELECTION, not just its type: a rule that
 * constrains a backend's rates constrains it wherever it runs (a QA403 has the
 * same one clock on a server as on this machine), while the dialog still has to
 * tell one server's QA40x from another's when it applies the answer.
 *
 * <p><b>The bit depth is optional</b> - {@link #NO_BIT_DEPTH} means "this payload
 * says nothing about the depth", which is what the four-argument constructor
 * produces.  A backend whose two directions share only a clock (the QA40x's one
 * reg-9 register) constrains the rate alone and both publishes and answers
 * without a depth; a backend that is one digital format in both directions (the
 * loopback) carries the depth as well and has its depth combo aligned from the
 * same round-trip.  A subscriber that does not constrain the depth ignores the
 * field, and the dialog leaves a depth combo alone unless the answer names one.
 *
 * @param input        {@code true} for the input (capture) direction, {@code false} for output
 * @param sampleRateHz the direction's sample rate in hertz
 * @param bitDepth     the direction's bit depth, or {@link #NO_BIT_DEPTH} when
 *                     the payload carries no depth
 * @param backend      the selected backend the edited direction belongs to -
 *                     local, or one server's
 * @param card         the resolved card name for the edited direction's device,
 *                     or {@code null} when the device maps to no card
 */
public record SampleRateChange(boolean input, int sampleRateHz, int bitDepth,
                               BackendKey backend, String card) {

    /** {@link #bitDepth()} of a payload that says nothing about the depth. */
    public static final int NO_BIT_DEPTH = 0;

    /** The rate alone - for a constraint whose backend couples only its clock. */
    public SampleRateChange(boolean input, int sampleRateHz, BackendKey backend, String card) {
        this(input, sampleRateHz, NO_BIT_DEPTH, backend, card);
    }
}
