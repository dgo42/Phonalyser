package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FftFormat} — magnitude-unit conversion and the
 * fractional-y kernel.  Pins the math that drives "what dBFS does this
 * Volts value sit at?" — a regression here silently shifts every chart
 * harmonic by the conversion error.
 */
class FftFormatTest {

    private static final double FS_DBV = 20 * Math.log10(1.7931);   // typical ADC FS in dBV

    @Test
    void toDbv_fromDbv_roundTripIdentity_forDbUnits() {
        double[] values = {-150, -80, -3, 0, +6};
        for (FftMagnitudeUnit u : new FftMagnitudeUnit[] {FftMagnitudeUnit.DBV, FftMagnitudeUnit.DBFS}) {
            for (double v : values) {
                double dbv = FftFormat.toDbv(v, u, FS_DBV);
                double back = FftFormat.fromDbv(dbv, u, FS_DBV);
                assertEquals(v, back, 1e-9,
                        "round-trip mismatch for unit=" + u + " v=" + v);
            }
        }
    }

    @Test
    void toDbv_dbfs_anchoredByFsDbv() {
        // 0 dBFS → fsDbv.  -20 dBFS → fsDbv - 20.
        assertEquals(FS_DBV,      FftFormat.toDbv(0.0,    FftMagnitudeUnit.DBFS, FS_DBV), 1e-9);
        assertEquals(FS_DBV - 20, FftFormat.toDbv(-20.0,  FftMagnitudeUnit.DBFS, FS_DBV), 1e-9);
    }

    @Test
    void convertMagRange_dbfsToDbv_shiftsByFsDbv() {
        double[] conv = FftFormat.convertMagRange(0.0, -120.0,
                FftMagnitudeUnit.DBFS, FftMagnitudeUnit.DBV, FS_DBV);
        // top: 0 dBFS → fsDbv dBV.  bot: -120 dBFS → fsDbv-120 dBV.
        assertEquals(FS_DBV,        conv[0], 1e-9);
        assertEquals(FS_DBV - 120,  conv[1], 1e-9);
    }

    @Test
    void convertMagRange_sameUnit_returnsInput() {
        double[] conv = FftFormat.convertMagRange(-3.0, -80.0,
                FftMagnitudeUnit.DBFS, FftMagnitudeUnit.DBFS, FS_DBV);
        assertEquals(-3.0,  conv[0], 1e-12);
        assertEquals(-80.0, conv[1], 1e-12);
    }

    @Test
    void magToYFraction_linearAxis_endpointsMapTo0And1() {
        // For dBFS axis with top=0, bot=-100: y(0)=0, y(-100)=1, y(-50)=0.5.
        assertEquals(0.0, FftFormat.magToYFraction(   0,  0, -100, FftMagnitudeUnit.DBFS), 1e-12);
        assertEquals(1.0, FftFormat.magToYFraction(-100, 0, -100, FftMagnitudeUnit.DBFS), 1e-12);
        assertEquals(0.5, FftFormat.magToYFraction( -50, 0, -100, FftMagnitudeUnit.DBFS), 1e-12);
    }

    @Test
    void magToYFraction_logAxis_decadeMapsToConstantFraction() {
        // log-axis (V) from top=1 to bot=1e-6 spans 6 decades.  Each
        // decade is exactly 1/6 of the canvas.
        double frac1 = FftFormat.magToYFraction(0.1,  1.0, 1e-6, FftMagnitudeUnit.V);
        double frac2 = FftFormat.magToYFraction(0.01, 1.0, 1e-6, FftMagnitudeUnit.V);
        assertEquals(1.0 / 6, frac1, 1e-9);
        assertEquals(2.0 / 6, frac2, 1e-9);
    }

    @Test
    void formatFrequency_switchesToKhzAt1000() {
        // The production formatter uses the default locale's decimal
        // separator, so accept either '.' or ',' here.
        assertTrue(FftFormat.formatFrequency(999.0).matches("999[.,]0 Hz"));
        assertTrue(FftFormat.formatFrequency(1000.0).matches("1[.,]00 kHz"));
        assertTrue(FftFormat.formatFrequency(12340.0).matches("12[.,]34 kHz"));
    }

    @Test
    void formatFrequency_invalidInputs() {
        assertEquals("—", FftFormat.formatFrequency(0.0));
        assertEquals("—", FftFormat.formatFrequency(-1.0));
        assertEquals("—", FftFormat.formatFrequency(Double.NaN));
    }

    @Test
    void formatVoltsSi_pickRightPrefix() {
        // The volts formatter returns "<mantissa> <prefix>" with the
        // prefix letter trailing.  Mantissa uses the default locale's
        // decimal separator so we match the prefix loosely.
        // 1.5 V → "1.5 " (or "1,50 " on locales whose "%.2f" uses comma —
        // the trailing-zero strip is dot-only, see the production code).
        // Accept anything that starts with "1" and has a comma or dot.
        String r15 = FftFormat.formatVoltsSi(1.5);
        assertTrue(r15.matches("1[.,]50?\\s*"),
                "expected '1.5'-ish (locale-tolerant), got '" + r15 + "'");
        // 100 mV → "100 m"
        assertTrue(FftFormat.formatVoltsSi(0.1).contains("m"),
                "100 mV output should contain 'm', got '" + FftFormat.formatVoltsSi(0.1) + "'");
        // 1 µV → "1 µ"
        assertTrue(FftFormat.formatVoltsSi(1e-6).contains("µ"),
                "1 µV output should contain 'µ', got '" + FftFormat.formatVoltsSi(1e-6) + "'");
        assertEquals("0 ", FftFormat.formatVoltsSi(0.0));
    }

    @Test
    void magMaxFor_dbfsIsZero() {
        assertEquals(0.0, FftFormat.magMaxFor(FftMagnitudeUnit.DBFS, 1.7931), 1e-9);
    }

    @Test
    void magMinFor_dbfsIs_300() {
        assertEquals(-300.0, FftFormat.magMinFor(FftMagnitudeUnit.DBFS, 1.7931), 1e-9);
    }
}
