package org.edgo.audio.measure.enums;

import java.util.Locale;

/**
 * The amplitude field's supported display units.  All conversions go
 * through volts-RMS; the {@link #toVrms(double, double)} / {@link
 * #fromVrms(double, double)} pair use the current ADC full-scale RMS
 * voltage when converting to / from dBFS (the only unit that needs it).
 *
 * <p>Parsing the user's unit string is case-insensitive and accepts the
 * short forms requested ({@code m} for mV, {@code v}, {@code dbv},
 * {@code dbfs}).
 */
public enum AmplitudeUnit {

    UV   ("µV"),
    MV   ("mV"),
    V    ("V"),
    DBV  ("dBV"),
    DBFS ("dBFS");

    public final String display;
    private AmplitudeUnit(String display) { this.display = display; }

    /** Parses the unit token at the end of an amplitude string; {@code null} on miss. */
    public static AmplitudeUnit fromString(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "u":
            case "uv":
            case "µ":
            case "µv":
            case "μ":
            case "μv": return UV;
            case "m":
            case "mv":   return MV;
            case "v":    return V;
            case "dbv":  return DBV;
            case "dbfs": return DBFS;
            default:     return null;
        }
    }

    /** Convert {@code value} expressed in this unit into volts-RMS. */
    public double toVrms(double value, double fsVoltageRms) {
        switch (this) {
            case UV:   return value / 1_000_000.0;
            case MV:   return value / 1000.0;
            case V:    return value;
            case DBV:  return Math.pow(10.0, value / 20.0);
            case DBFS: return fsVoltageRms * Math.pow(10.0, value / 20.0);
            default:   return value;
        }
    }

    /** Convert {@code vrms} (volts-RMS) into this unit. */
    public double fromVrms(double vrms, double fsVoltageRms) {
        switch (this) {
            case UV:   return vrms * 1_000_000.0;
            case MV:   return vrms * 1000.0;
            case V:    return vrms;
            case DBV:  return 20.0 * Math.log10(Math.max(vrms, 1e-12));
            case DBFS: return 20.0 * Math.log10(Math.max(vrms, 1e-12) / fsVoltageRms);
            default:   return vrms;
        }
    }

    /** True if this unit is part of the metric (µV / mV / V) auto-rescale group. */
    public boolean isMetric() {
        return this == UV || this == MV || this == V;
    }

    /**
     * Picks the most readable metric unit for {@code absVrms} so amplitudes
     * show up as e.g. "500 mV" rather than "0.5 V" or "500000 µV".  Only
     * relevant when the current unit is part of {@link #isMetric()}; dB
     * units are absolute log scales and should not be rescaled.
     */
    public static AmplitudeUnit bestMetricFor(double absVrms) {
        if (absVrms >= 1.0)  return V;
        if (absVrms >= 1e-3) return MV;
        return UV;
    }
}
