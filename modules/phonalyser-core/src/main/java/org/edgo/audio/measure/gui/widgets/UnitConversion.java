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

package org.edgo.audio.measure.gui.widgets;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.dsp.DitherMath;
import org.edgo.audio.measure.gui.widgets.UnitFamily.Unit;

import lombok.experimental.UtilityClass;

/**
 * Converts a number between two units of ONE {@link UnitFamily}, against a live
 * DAC full scale - the arithmetic behind a preference that stores what the
 * operator ENTERED (a number plus its unit) instead of a canonical value alone.
 *
 * <p>A canonical value cannot say what was entered: {@code 0 dBFS} is one
 * voltage on one DAC calibration and another on the next, and a dither stated
 * in dBV is a different bit count under every full scale.  So the pair is
 * stored as typed and resolved HERE at use, which is what keeps a backend,
 * device or recalibration from silently redefining a value already stored.
 *
 * <p>Units travel as their TOKEN: the {@code unit.*} i18n key without its
 * prefix ({@code v}, {@code dbv}, {@code dbfs}, {@code bits}).  That is a
 * locale-independent name the {@link UnitFamily} already carries, so no second
 * vocabulary exists to drift from it.  Only the factor-1 units of a family have
 * a token: a scaled linear unit (mV, uV, nV) never sticks - the display
 * auto-ranges through it - so a value entered in one is stored in the base
 * unit, exactly as the field itself treats it.
 *
 * <p>The conversions are the families' own: {@link Unit#toCanonical} and
 * {@link Unit#fromCanonical} for the linear and the logarithmic amplitude
 * units, the RMS full-scale anchor for dBFS ({@code 0 dBFS} is a full-scale
 * SINE, so the reference is {@code fsAmpl/sqrt(2)}), and {@link DitherMath} for
 * the dither family, whose dBV is a TPDF noise level against the PEAK full
 * scale rather than a plain logarithm.
 */
@UtilityClass
public class UnitConversion {

    /** Separates the {@code unit} namespace from the token in a unit's i18n
     *  key, so {@code unit.dbv} yields the token {@code dbv}. */
    private final char TOKEN_SEPARATOR = '.';
    /** dB per factor-of-10 amplitude - the dBFS exponent scale, as for dBV. */
    private final double DB_PER_DECADE = 20.0;
    /** A dither stated logarithmically resolves to a real depth: 0 (Off) is
     *  only ever entered and stored in bits. */
    private final double DITHER_MIN_BITS = 1.0;
    /** Deepest dither the sample formats can carry (32-bit PCM). */
    private final double DITHER_MAX_BITS = 32.0;

    /**
     * {@code value}, read in {@code sourceUnit}, expressed in
     * {@code targetUnit} within {@code family}.  An unknown token is read as
     * the family's base unit - a stored pair whose token no longer names a unit
     * still yields its number rather than a NaN.
     *
     * @param fsAmpl DAC PEAK full-scale amplitude (Vpeak); consulted only where
     *               a unit is defined against it (dBFS, and the dither dBV)
     */
    public double convert(UnitFamily family, double value,
                          String sourceUnit, String targetUnit, double fsAmpl) {
        Unit source = unitOf(family, sourceUnit);
        Unit target = unitOf(family, targetUnit);
        if (source == target) {
            return value;
        }
        return fromCanonical(family, toCanonical(family, value, source, fsAmpl), target, fsAmpl);
    }

    /** The token stored for {@code unit}: its i18n key without the {@code
     *  unit.} namespace.  Empty for a suffix-less unit (no family in a stored
     *  pair has one). */
    public String token(Unit unit) {
        String key = unit.i18nKey();
        return key == null ? "" : key.substring(key.indexOf(TOKEN_SEPARATOR) + 1);
    }

    /** Token of {@code family}'s base unit - what a pair carries when the
     *  number is already the canonical quantity (V RMS, dither bits). */
    public String baseToken(UnitFamily family) {
        return token(baseUnit(family));
    }

    /** Token of {@code family}'s logarithmic unit (dBV), or the base token for
     *  a family without one. */
    public String logToken(UnitFamily family) {
        Unit log = family.logUnit();
        return log == null ? baseToken(family) : token(log);
    }

    /** Whether {@code unitToken} names {@code family}'s logarithmic unit - the
     *  one display choice a value alone cannot reveal. */
    public boolean isLogToken(UnitFamily family, String unitToken) {
        Unit log = family.logUnit();
        return log != null && token(log).equals(unitToken);
    }

    /** The unit {@code token} names within {@code family}, defaulting to the
     *  base unit.  Matching is on the token, NOT on {@link Unit#matches},
     *  because a stored pair must resolve identically in every locale and
     *  without loading the translations at all. */
    private Unit unitOf(UnitFamily family, String token) {
        Unit log = family.logUnit();
        if (log != null && token(log).equals(token)) {
            return log;
        }
        Unit fsRelative = family.fsRelativeUnit();
        if (fsRelative != null && token(fsRelative).equals(token)) {
            return fsRelative;
        }
        return baseUnit(family);
    }

    /** The family's base unit: the one applied to suffix-less input, which is
     *  also the unit its canonical quantity is expressed in. */
    private Unit baseUnit(UnitFamily family) {
        return family.defaultUnit(0);
    }

    private double toCanonical(UnitFamily family, double value, Unit source, double fsAmpl) {
        if (family == UnitFamily.DITHER) {
            // The dither dBV is a TPDF noise level against the peak full scale,
            // not a plain logarithm of the bit count - see DitherMath.
            return source.log()
                    ? clampBits(DitherMath.bitsForDbv(value, fsAmpl))
                    : value;
        }
        return source.fsRelative()
                ? fsAmpl / Constants.SQRT2 * Math.pow(10.0, value / DB_PER_DECADE)
                : source.toCanonical(value);
    }

    private double fromCanonical(UnitFamily family, double canonical, Unit target, double fsAmpl) {
        if (family == UnitFamily.DITHER) {
            return target.log() ? DitherMath.dbvForBits(canonical, fsAmpl) : canonical;
        }
        return target.fsRelative()
                ? DB_PER_DECADE * Math.log10(canonical * Constants.SQRT2 / fsAmpl)
                : target.fromCanonical(canonical);
    }

    private double clampBits(double bits) {
        return Math.max(DITHER_MIN_BITS, Math.min(DITHER_MAX_BITS, bits));
    }
}
