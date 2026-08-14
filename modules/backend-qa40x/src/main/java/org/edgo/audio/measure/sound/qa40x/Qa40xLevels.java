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

package org.edgo.audio.measure.sound.qa40x;

import org.edgo.audio.measure.common.Constants;

import lombok.experimental.UtilityClass;

/**
 * QA402/QA403 raw ↔ volts conversions and per-range full-scale voltages (see
 * {@code doc/QA40X-PROTOCOL.md} §6).  Pure and stateless - the caller supplies
 * the selected range dBV and the matching on-device linear cal factor
 * ({@code 10^(dB/20)} from {@link Qa40xCalibration}).
 *
 * <h2>Range semantics (see doc §6 "Levels cheat-sheet")</h2>
 * The two directions' "dBV" range labels are NOT the same unit:
 * <ul>
 *   <li><b>Output label = genuine RMS dBV, per single leg</b> - a sine of
 *       {@code maxOutputDbv} dBV RMS on one leg reaches full scale.  The balanced
 *       {@code Out+ − Out−} is +6 dB, but that is the wire, not the sample.</li>
 *   <li><b>Input label is really a dBFS / peak-to-peak reference</b> - the
 *       {@code N} "dBV" input range clips (0 dBFS) at {@code 10^(N/20)} Vpp
 *       <em>differential</em>, so the true RMS full scale is {@code N − 9} dB: a
 *       constant 9 dB below the label = {@code +3} (peak/RMS, √2) {@code + 6}
 *       (differential ×2 - the vendor "−6 dB differential-ADC" term).</li>
 * </ul>
 * This supersedes the earlier mock-derived assumption that the label is RMS full
 * scale in both directions: a digital mock loopback shows no differential
 * doubling, so it could not surface the input's 9 dB offset.
 *
 * <h2>Load-bearing conventions</h2>
 * <ul>
 *   <li><b>ADC:</b> {@code adc_volts = raw/MAXINT · cal · 10^(N/20) / 2} - the
 *       range {@code N} is {@code 20·log₁₀(Vpp clip)}, so {@code 10^(N/20)} is the
 *       peak-to-peak clip and {@code /2} makes it the peak ({@code raw/MAXINT} is
 *       the sample normalised to [-1, 1]).  A full-scale sine then has RMS
 *       {@code = peak/√2 = cal · 10^(N/20)/(2·√2)}, i.e. {@code ≈ N − 9} dBV.</li>
 *   <li><b>DAC input is PEAK volts, not RMS</b> (vendor math, unchanged):
 *       {@code dac_int32 = peakVolts · cal · 10^(-(maxOutputDbv+3)/20) · MAXINT}.
 *       The {@code +3} closes the dBFS-peak vs dBV-RMS gap ONLY because the
 *       incoming amplitude is already peak (RMS·√2 ≈ +3.01 dB).  Feeding an
 *       RMS-scaled value here lands ~3 dB low (§9 item 12).</li>
 * </ul>
 *
 * <h2>Per-range effective full-scale RMS</h2>
 * Full scale is the sample reaching {@code ±MAXINT}.  <b>Output</b> lands at
 * ≈ {@code 10^(maxOutputDbv/20)} - the per-leg label as an RMS voltage;
 * <b>input</b> lands 9 dB lower, at ≈ {@code 10^((maxInputDbv−9)/20)}, because the
 * input label is a Vpp-differential (dBFS) reference, not RMS dBV.  Both
 * cal-corrected:
 * <ul>
 *   <li><b>Input:</b> {@code inputFullScaleRmsVolts = cal · 10^(N/20) / (2·√2) ≈ cal · 10^((maxInputDbv−9)/20)} (Vpp -> peak -> RMS).</li>
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

    /** Peak-vs-RMS (√2) term: 0 dBFS is a peak limit, dBV is RMS.  The output
     *  folds it into the per-leg full scale; the input applies it via {@link #SQRT2}. */
    private static final double PEAK_TO_RMS_DB = 3.0;
    /** Peak-to-peak -> peak divisor for the INPUT.  The "N dBV" input range is a
     *  Vpp-differential (dBFS) reference - {@code 10^(N/20)} is the peak-to-peak
     *  clip - so halving it gives the peak amplitude.  An EXACT factor of 2
     *  (6.02 dB from the balanced {@code In+ − In− = 2×}), not a rounded 6 dB
     *  (doc §6 cheat-sheet). */
    private static final double VPP_TO_PEAK         = 2.0;
    /** Volts-to-dB divisor (20·log10). */
    private static final double DB_DIVISOR          = 20.0;

    /**
     * Converts a raw ADC sample to instantaneous (differential) volts:
     * {@code raw/MAXINT · inputFullScalePeakVolts}.  {@code raw/MAXINT} is the
     * sample normalised to [-1, 1], so full scale (±1) is the peak of the range's
     * Vpp-differential clip; a full-scale sine then reads {@code ≈ (N − 9)} dBV RMS
     * (doc §6 cheat-sheet; see the class note).
     */
    public double adcVolts(long raw, int maxInputDbv, double adcCal) {
        return (raw / (double) MAXINT) * inputFullScalePeakVolts(maxInputDbv, adcCal);
    }

    /**
     * Converts a PEAK output voltage to a 32-bit DAC sample:
     * {@code round(peakVolts · dacCal · 10^(-(maxOutputDbv+3)/20) · MAXINT)},
     * saturated to {@code ±MAXINT} (§6).  {@code peakVolts} MUST be peak
     * amplitude (RMS·√2) - see the class note.
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
     * Effective full-scale RMS input voltage for a range: the peak clip
     * ({@link #inputFullScalePeakVolts}) brought to RMS, {@code peak / √2}.  Lands
     * at {@code adcCal · 10^(N/20) / (2·√2) ≈ adcCal · 10^((maxInputDbv−9)/20)},
     * ≈ 9 dB below the "N dBV" label (see the class note).
     */
    public double inputFullScaleRmsVolts(int maxInputDbv, double adcCal) {
        return inputFullScalePeakVolts(maxInputDbv, adcCal) / Constants.SQRT2;
    }

    /**
     * The peak instantaneous voltage a full-scale sample reaches on an input range,
     * the single source the ADC read and the RMS full scale both build on.  The
     * range {@code N} is {@code 20·log₁₀(Vpp clip differential)}, so {@code 10^(N/20)}
     * is that peak-to-peak clip and {@link #VPP_TO_PEAK halving} it gives the peak:
     * {@code adcCal · 10^(N/20) / 2}.
     */
    private double inputFullScalePeakVolts(int maxInputDbv, double adcCal) {
        return adcCal * dbToLinear(maxInputDbv) / VPP_TO_PEAK;
    }

    /**
     * Effective full-scale RMS output voltage for a range:
     * {@code 10^((maxOutputDbv+3)/20) / (dacCal · √2)} ≈ the label as an RMS
     * voltage, cal-corrected (see class note).
     */
    public double outputFullScaleRmsVolts(int maxOutputDbv, double dacCal) {
        return dbToLinear(maxOutputDbv + PEAK_TO_RMS_DB) / (dacCal * Constants.SQRT2);
    }

    private double dbToLinear(double db) {
        return Math.pow(10.0, db / DB_DIVISOR);
    }
}
