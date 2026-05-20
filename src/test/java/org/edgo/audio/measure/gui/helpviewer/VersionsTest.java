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
