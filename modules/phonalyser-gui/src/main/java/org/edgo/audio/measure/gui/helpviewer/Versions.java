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

package org.edgo.audio.measure.gui.helpviewer;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
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

    /** Resolved once and cached — see {@link #appVersion()}. */
    private String resolvedVersion;

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

    /** The running app's version.  Single source of truth for the splash,
     *  the About dialog and the update checker.  Resolved in order:
     *  <ol>
     *    <li>{@code /version.properties} — filled with {@code project.version}
     *        by Maven resource filtering at build time, so it is correct for
     *        both dev runs and packaged builds;</li>
     *    <li>the JAR manifest's {@code Implementation-Version};</li>
     *    <li>{@code "dev"} when neither is present (e.g. an IDE run that did
     *        not filter resources).</li>
     *  </ol>
     *  Resolved once and cached. */
    public String appVersion() {
        if (resolvedVersion == null) resolvedVersion = resolveVersion();
        return resolvedVersion;
    }

    private String resolveVersion() {
        try (InputStream in = Versions.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("app.version", "").trim();
                // Unfiltered (IDE) copies still hold the literal ${...} token.
                if (!v.isEmpty() && !v.startsWith("${")) return v;
            }
        } catch (IOException ignored) {
            // Fall through to the manifest / "dev".
        }
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
