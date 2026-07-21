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

package org.edgo.audio.measure.gui.bus;

import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Payload of {@link Events#PREFS_SAMPLE_RATE_CHANGED} and
 * {@link Events#PREFS_SAMPLE_RATE_SET}: one direction's sample rate as the
 * Preferences dialog is being edited, tagged with BOTH the backend and the
 * resolved card it belongs to so a subscriber can key off whichever it
 * constrains — a whole backend, or one specific card.  Both ride ON the payload
 * because these events fire against the dialog's UNCOMMITTED working copy: the
 * subscriber cannot read the change off live Preferences yet, so it gates on
 * these fields rather than on current state.
 *
 * @param input        {@code true} for the input (capture) direction, {@code false} for output
 * @param sampleRateHz the direction's sample rate in hertz
 * @param backend      the audio backend the edited direction belongs to
 * @param card         the resolved card name for the edited direction's device,
 *                     or {@code null} when the device maps to no card
 */
public record SampleRateChange(boolean input, int sampleRateHz, AudioBackendType backend, String card) {
}
