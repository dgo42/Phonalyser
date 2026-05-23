package org.edgo.audio.measure.gui;

/**
 * Converts the user-facing labels from the V/div and t/div pick lists back
 * into numeric values (volts and seconds) for layout / drawing math.
 *
 * <p>The labels themselves live as constant arrays inside {@code MainWindow}
 * (see {@code VOLT_PER_DIV} / {@code TIME_PER_DIV}), and the indices into
 * those arrays are stored in {@link Preferences}.  These helpers exist so
 * the {@code OscilloscopeView} / {@code CondensedView} renderers don't have
 * to duplicate the lookup tables.
 */
final class OscParse {

    /** Same content as {@code MainWindow.VOLT_PER_DIV}.  Kept in sync manually. */
    private static final String[] VOLT_PER_DIV = {
            "1 μV/div", "2 μV/div", "5 μV/div",
            "10 μV/div", "20 μV/div", "50 μV/div",
            "100 μV/div", "200 μV/div", "500 μV/div",
            "1 mV/div", "2 mV/div", "5 mV/div",
            "10 mV/div", "20 mV/div", "50 mV/div",
            "100 mV/div", "200 mV/div", "500 mV/div",
            "1 V/div", "2 V/div", "5 V/div",
            "10 V/div", "20 V/div", "50 V/div",
            "100 V/div", "200 V/div", "500 V/div"
    };
    /** Same content as {@code MainWindow.TIME_PER_DIV}. */
    private static final String[] TIME_PER_DIV = {
            "10 μs/div", "20 μs/div", "50 μs/div",
            "100 μs/div", "200 μs/div", "500 μs/div",
            "1 ms/div", "2 ms/div", "5 ms/div",
            "10 ms/div", "20 ms/div", "50 ms/div",
            "100 ms/div", "200 ms/div", "500 ms/div",
            "1 s/div"
    };

    private OscParse() {}

    /** Returns the volts-per-division for the given index, in volts. */
    static double voltsPerDiv(int index) {
        if (index < 0 || index >= VOLT_PER_DIV.length) index = 15; // "100 mV/div"
        return parseUnit(VOLT_PER_DIV[index], "V");
    }

    /** Returns the seconds-per-division for the given index, in seconds. */
    static double timePerDiv(int index) {
        if (index < 0 || index >= TIME_PER_DIV.length) index = 6;  // "1 ms/div"
        return parseUnit(TIME_PER_DIV[index], "s");
    }

    /**
     * Parses a label like {@code "100 mV/div"} or {@code "10 μs/div"} into
     * the corresponding numeric value in base units (volts or seconds).
     */
    private static double parseUnit(String label, String baseUnit) {
        String stripped = label.replace("/div", "").trim();
        int spaceIdx = stripped.indexOf(' ');
        if (spaceIdx <= 0) return 1.0;
        double value = Double.parseDouble(stripped.substring(0, spaceIdx));
        String unit  = stripped.substring(spaceIdx + 1);
        double mult = 1.0;
        if (unit.startsWith("μ") || unit.startsWith("u")) mult = 1e-6;
        else if (unit.startsWith("m"))                    mult = 1e-3;
        // bare "V" / "s" → mult = 1.0
        return value * mult;
    }
}
