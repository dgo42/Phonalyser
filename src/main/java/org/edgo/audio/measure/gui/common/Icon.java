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

package org.edgo.audio.measure.gui.common;

import lombok.Getter;

/**
 * Every bundled UI icon — one constant per transparent PNG under {@code /icons}.
 * The {@code -dark}/{@code -lit} pairs are the two states of a toggle (normal vs
 * filled background), {@code -big}/{@code -small}/{@code -mid} are fixed pixel
 * sizes, and {@code signal-*} are the generator signal-form pictograms.  Load an
 * image for a value via {@link IconUtils#icon}.
 */
@Getter
public enum Icon {

    RECORD_DARK("record-dark.png"),
    RECORD_LIT("record-lit.png"),

    PLAY_DARK_BIG("play-dark-big.png"),
    PLAY_LIT_BIG("play-lit-big.png"),
    PLAY_DARK_SMALL("play-dark-small.png"),
    PLAY_LIT_SMALL("play-lit-small.png"),

    ARROWS_FROM_CIRCLE_DARK("arrows-from-circle-dark.png"),
    ARROWS_FROM_CIRCLE_LIT("arrows-from-circle-lit.png"),
    ARROWS_TO_CIRCLE_DARK("arrows-to-circle-dark.png"),
    ARROWS_TO_CIRCLE_LIT("arrows-to-circle-lit.png"),

    CHART_DARK("chart-dark.png"),
    CHART_LIT("chart-lit.png"),
    GAUGE_HIGH_DARK("gauge-high-dark.png"),
    GAUGE_HIGH_LIT("gauge-high-lit.png"),
    WINDOW_RESTORE_DARK("window-restore-dark.png"),
    WINDOW_RESTORE_LIT("window-restore-lit.png"),
    ROTATE_LEFT_DARK("rotate-left-dark.png"),
    ROTATE_LEFT_RED("rotate-left-red.png"),
    PHASE_SINE("phase-sine.png"),

    CAMERA("camera.png"),
    CROSSHAIR_BIG("crosshair-big.png"),
    CROSSHAIR_SMALL("crosshair-small.png"),
    FLOPPY_DISK("floppy-disk.png"),
    FOLDER_OPEN("folder-open.png"),
    WAND("wand.png"),
    PLUS("plus.png"),
    MINUS("minus.png"),
    RECTANGLE_XMARK("rectangle-xmark.png"),

    DROPDOWN("dropdown.png"),
    UP_BIG("up-big.png"),
    UP_SMALL("up-small.png"),
    DOWN_BIG("down-big.png"),
    DOWN_SMALL("down-small.png"),

    RIAA_BIG("riaa-iec-curve-big.png"),
    RIAA_MID("riaa-iec-curve-mid.png"),
    RIAA_SMALL("riaa-iec-curve-small.png"),
    SWISS_BIG("swiss-army-knife-big.png"),
    SWISS_MID("swiss-army-knife-mid.png"),
    SWISS_SMALL("swiss-army-knife-small.png"),

    SIGNAL_SINE("signal-sine.png"),
    SIGNAL_SINE_COMP("signal-sine-comp.png"),
    SIGNAL_DUAL_TONE("signal-dual-tone.png"),
    SIGNAL_DUAL_TONE_COMP("signal-dual-tone-comp.png"),
    SIGNAL_TRIANGLE("signal-triangle.png"),
    SIGNAL_RECTANGLE("signal-rectangle.png"),
    SIGNAL_WHITE_NOISE("signal-white-noise.png"),
    SIGNAL_PINK_NOISE("signal-pink-noise.png"),
    SIGNAL_PINK_NOISE_LINEAR("signal-pink-noise-linear.png"),
    SIGNAL_LINEAR_SWEEP("signal-linear-sweep.png"),
    SIGNAL_LOG_SWEEP("signal-log-sweep.png");

    /** PNG file name under {@code /icons}. */
    private final String fileName;

    private Icon(String fileName) {
        this.fileName = fileName;
    }
}
