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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void identicalIsZero() {
        assertEquals(0, Versions.compare("1.0", "1.0"));
        assertEquals(0, Versions.compare("v1.0", "1.0"));
        assertEquals(0, Versions.compare("1.0-RC1", "1.0-RC1"));
    }

    @Test
    void numericSegmentsCompareNumerically() {
        assertLt("1.9", "1.10");
        assertLt("0.9", "1.0");
        assertLt("1.0", "2.0");
    }

    @Test
    void missingTrailingZeroIsZero() {
        assertEquals(0, Versions.compare("1.0", "1.0.0"));
        assertEquals(0, Versions.compare("1", "1.0"));
    }

    @Test
    void releaseBeatsPreRelease() {
        assertLt("1.0-RC1", "1.0");
        assertLt("1.0-SNAPSHOT", "1.0");
        assertLt("1.0-MS1", "1.0");
    }

    @Test
    void preReleaseSuffixesCompareLexicographically() {
        assertLt("1.0-RC1", "1.0-RC2");
        assertLt("1.0-MS1", "1.0-RC1");
    }

    @Test
    void snapshotIsOlderThanAnyOtherSuffix() {
        assertLt("1.0-SNAPSHOT", "1.0-RC1");
        assertLt("1.0-SNAPSHOT", "1.0-MS1");
        assertLt("1.0-SNAPSHOT", "1.0");
    }

    @Test
    void vPrefixStripped() {
        assertEquals(0, Versions.compare("v1.0-RC1", "1.0-RC1"));
        assertLt("v1.0-RC1", "v1.0");
    }

    @Test
    void devVersionIsOlderThanAnyRelease() {
        assertLt("dev", "1.0");
        assertLt("dev", "1.0-RC1");
    }

    @Test
    void nullOrEmptyIsOlderThanAnyRelease() {
        assertLt("", "1.0");
        assertLt(null, "1.0");
    }

    @Test
    void caseInsensitiveSuffix() {
        assertEquals(0, Versions.compare("1.0-rc1", "1.0-RC1"));
    }

    private static void assertLt(String older, String newer) {
        int forward  = Versions.compare(older, newer);
        int backward = Versions.compare(newer, older);
        assertTrue(forward < 0,
                () -> "expected " + older + " < " + newer + " but compare returned " + forward);
        assertTrue(backward > 0,
                () -> "expected " + newer + " > " + older + " but compare returned " + backward);
    }
}
