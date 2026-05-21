package org.edgo.audio.measure.gui.helpviewer;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Lenient version-string comparator tuned for the tag conventions this
 * project uses on GitHub releases — bare versions ({@code 1.0}),
 * dotted versions ({@code 1.2.3}), pre-release suffixes ({@code 1.0-RC1},
 * {@code 1.0-MS1}, {@code 1.0-SNAPSHOT}) and optional {@code v} prefixes.
 *
 * <p>Comparison rules, applied per dash-/dot-separated segment from left
 * to right:
 *
 * <ul>
 *   <li>A leading {@code v} or {@code V} is stripped.</li>
 *   <li>Two numeric segments compare numerically ({@code 9 < 10}).</li>
 *   <li>A missing numeric segment counts as 0 ({@code 1.0 == 1.0.0}).</li>
 *   <li>A numeric segment beats any textual one ({@code 1 > "dev"}).</li>
 *   <li>An empty (release) segment beats any textual pre-release suffix
 *       ({@code 1.0 > 1.0-RC1}).</li>
 *   <li>{@code SNAPSHOT} is always older than any other text in the same
 *       position ({@code 1.0-SNAPSHOT < 1.0-RC1}).</li>
 *   <li>Otherwise textual segments compare case-insensitively
 *       ({@code RC1 < RC2}, {@code MS1 < RC1}).</li>
 * </ul>
 */
@UtilityClass
public class Versions {

    private static final Pattern NUM = Pattern.compile("^\\d+$");

    /**
     * @return negative if {@code a} is older than {@code b}, positive if
     *         newer, zero if equivalent.
     */
    public int compare(String a, String b) {
        String[] ap = split(a);
        String[] bp = split(b);
        int n = Math.max(ap.length, bp.length);
        for (int i = 0; i < n; i++) {
            String sa = i < ap.length ? ap[i] : "";
            String sb = i < bp.length ? bp[i] : "";
            int c = compareSegment(sa, sb);
            if (c != 0) return c;
        }
        return 0;
    }

    private String[] split(String v) {
        if (v == null || v.isEmpty()) return new String[0];
        String s = (v.startsWith("v") || v.startsWith("V")) ? v.substring(1) : v;
        return s.split("[.\\-_]");
    }

    /** Reads the running app's version from its manifest, falling back to
     *  {@code "dev"} when invoked outside a packaged JAR.  Single source of
     *  truth for the About dialog and the update checker. */
    public String appVersion() {
        String v = Versions.class.getPackage().getImplementationVersion();
        return (v != null && !v.isEmpty()) ? v : "dev";
    }

    private int compareSegment(String a, String b) {
        if (a.equalsIgnoreCase(b)) return 0;

        boolean aSnap = "SNAPSHOT".equalsIgnoreCase(a);
        boolean bSnap = "SNAPSHOT".equalsIgnoreCase(b);
        if (aSnap && bSnap) return 0;
        if (aSnap) return -1;
        if (bSnap) return 1;

        boolean aNum = NUM.matcher(a).matches();
        boolean bNum = NUM.matcher(b).matches();
        if (aNum && bNum) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
        if (a.isEmpty() && bNum) return Integer.compare(0, Integer.parseInt(b));
        if (aNum && b.isEmpty()) return Integer.compare(Integer.parseInt(a), 0);
        if (aNum) return 1;
        if (bNum) return -1;
        if (a.isEmpty()) return 1;
        if (b.isEmpty()) return -1;
        return a.compareToIgnoreCase(b);
    }
}
