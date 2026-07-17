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

package org.edgo.audio.measure.sound.qa40x;

import lombok.experimental.UtilityClass;

/**
 * QA402/QA403 raw ↔ volts conversions and per-range full-scale voltages (see
 * {@code doc/QA40X-PROTOCOL.md} §6).  Pure and stateless — the caller supplies
 * the selected range dBV and the matching on-device linear cal factor
 * ({@code 10^(dB/20)} from {@link Qa40xCalibration}).
 *
 * <h2>Range semantics (maintainer ruling, mock bench 2026-07-17)</h2>
 * <b>The range label IS the usable RMS full scale, on BOTH directions:</b> a sine
 * of exactly {@code maxDbv} dBV (RMS) reaches digital full scale, input and output
 * alike.  This DELIBERATELY DEVIATES from the vendor PyQa40x input math, which
 * carries an extra {@code -6} dB "differential-ADC" term placing the input clip
 * point 9 dB below the label — bench-rejected (output measured label-exact, input
 * clipped a +7.9 dBV tone on the 12 dBV range and read −0.94 dBFS on the 18 dBV
 * range where −10 dBFS is correct).  Both paths therefore share one {@code +3} dB
 * peak-vs-RMS term; re-verify against real hardware in Phase B.
 *
 * <h2>Load-bearing conventions</h2>
 * <ul>
 *   <li><b>ADC:</b> {@code adc_volts = raw/MAXINT · cal · 10^((maxInputDbv+3)/20)}.
 *       {@code raw/MAXINT} is the sample normalised to [-1, 1], so the result is
 *       the instantaneous voltage; the {@code +3} makes a label-dBV RMS sine span
 *       exactly full scale.</li>
 *   <li><b>DAC input is PEAK volts, not RMS</b> (vendor math, unchanged):
 *       {@code dac_int32 = peakVolts · cal · 10^(-(maxOutputDbv+3)/20) · MAXINT}.
 *       The {@code +3} closes the dBFS-peak vs dBV-RMS gap ONLY because the
 *       incoming amplitude is already peak (RMS·√2 ≈ +3.01 dB).  Feeding an
 *       RMS-scaled value here lands ~3 dB low (§9 item 12).</li>
 * </ul>
 *
 * <h2>Per-range effective full-scale RMS</h2>
 * Full scale is the sample reaching {@code ±MAXINT}; a full-scale sine's RMS is
 * {@code peak/√2}, and {@code 10^(3/20) ≈ √2}, so both directions land at
 * ≈ {@code 10^(maxDbv/20)} — the label as an RMS voltage, cal-corrected:
 * <ul>
 *   <li><b>Input:</b> {@code inputFullScaleRmsVolts = cal · 10^((maxInputDbv+3)/20) / √2}.</li>
 *   <li><b>Output:</b> {@code outputFullScaleRmsVolts = 10^((maxOutputDbv+3)/20) / (cal · √2)}.</li>
 * </ul>
 * These feed the QA40x device card's per-range full-scale voltages; keep the
 * on-device cal factor and any Phonalyser {@code .frc} correction as separate,
 * composable multipliers (§10).
 */
@UtilityClass
public class Qa40xLevels {

    /** Full-scale magnitude of the 32-bit samples: {@code 2^31 - 1} (§6). */
    public static final int MAXINT = Integer.MAX_VALUE;

    /** dBFS-peak vs dBV-RMS term, shared by both directions (maintainer ruling —
     *  the label is the RMS full scale; see the class note). */
    private static final double PEAK_TO_RMS_DB = 3.0;
    /** Volts-to-dB divisor (20·log10). */
    private static final double DB_DIVISOR          = 20.0;
    private static final double SQRT2               = Math.sqrt(2.0);

    /**
     * Converts a raw ADC sample to instantaneous volts:
     * {@code raw/MAXINT · adcCal · 10^((maxInputDbv+3)/20)} — a label-dBV RMS sine
     * spans exactly full scale (maintainer ruling; see the class note).
     */
    public double adcVolts(long raw, int maxInputDbv, double adcCal) {
        return (raw / (double) MAXINT) * adcCal * dbToLinear(maxInputDbv + PEAK_TO_RMS_DB);
    }

    /**
     * Converts a PEAK output voltage to a 32-bit DAC sample:
     * {@code round(peakVolts · dacCal · 10^(-(maxOutputDbv+3)/20) · MAXINT)},
     * saturated to {@code ±MAXINT} (§6).  {@code peakVolts} MUST be peak
     * amplitude (RMS·√2) — see the class note.
     */
    public int dacInt32(double peakVolts, int maxOutputDbv, double dacCal) {
        double scaled = peakVolts * dacCal * dbToLinear(-(maxOutputDbv + PEAK_TO_RMS_DB)) * MAXINT;
        long rounded = Math.round(scaled);
        if (rounded > MAXINT) {
            return MAXINT;
        }
        if (rounded < -MAXINT) {
            return -MAXINT;
        }
        return (int) rounded;
    }

    /**
     * Effective full-scale RMS input voltage for a range:
     * {@code adcCal · 10^((maxInputDbv+3)/20) / √2} ≈ the label as an RMS voltage,
     * cal-corrected (see class note).
     */
    public double inputFullScaleRmsVolts(int maxInputDbv, double adcCal) {
        return adcCal * dbToLinear(maxInputDbv + PEAK_TO_RMS_DB) / SQRT2;
    }

    /**
     * Effective full-scale RMS output voltage for a range:
     * {@code 10^((maxOutputDbv+3)/20) / (dacCal · √2)} ≈ the label as an RMS
     * voltage, cal-corrected (see class note).
     */
    public double outputFullScaleRmsVolts(int maxOutputDbv, double dacCal) {
        return dbToLinear(maxOutputDbv + PEAK_TO_RMS_DB) / (dacCal * SQRT2);
    }

    private double dbToLinear(double db) {
        return Math.pow(10.0, db / DB_DIVISOR);
    }
}
