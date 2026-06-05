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

package org.edgo.audio.measure.cli.util;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import java.util.Locale;

/**
 * Logs the DAC↔ADC clock mismatch deduced from generator vs measured fundamental.
 * Maps the relative drift back to the master oscillator (22.5792 MHz for the
 * 44.1k sample-rate family, 24.576 MHz for the 48k family).
 */
@Log4j2
@UtilityClass
public class ClockMismatch {

    public void log(int iter, double genFreqHz, double measuredHz, int sampleRate) {
        if (genFreqHz <= 0.0 || measuredHz <= 0.0) return;
        double delta = measuredHz - genFreqHz;
        double ppm   = 1e6 * delta / genFreqHz;
        double osc;
        String oscName;
        if (sampleRate % 44100 == 0) {
            osc = 22.5792e6; oscName = "22.5792 MHz";
        } else if (sampleRate % 48000 == 0) {
            osc = 24.576e6;  oscName = "24.576 MHz";
        } else {
            log.info("Iter {} clock: ΔF={} Hz ({} ppm) — sample rate {} matches no standard oscillator family",
                    iter,
                    String.format(Locale.US, "%+.6f", delta),
                    String.format(Locale.US, "%+.2f", ppm),
                    sampleRate);
            return;
        }
        double oscDelta = osc * delta / genFreqHz;
        log.info("Iter {} clock: gen={} Hz, meas={} Hz, ΔF={} Hz ({} ppm), Δosc={} Hz @ {}",
                iter,
                String.format(Locale.US, "%.6f", genFreqHz),
                String.format(Locale.US, "%.6f", measuredHz),
                String.format(Locale.US, "%+.6f", delta),
                String.format(Locale.US, "%+.2f", ppm),
                String.format(Locale.US, "%+.2f", oscDelta),
                oscName);
    }
}
