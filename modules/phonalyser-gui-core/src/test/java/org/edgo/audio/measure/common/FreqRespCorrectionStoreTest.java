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

package org.edgo.audio.measure.common;

// CorrectionStore moved to gui.common with the shared GUI infrastructure; this
// test used to reach it as a same-package neighbour.
import org.edgo.audio.measure.gui.common.CorrectionStore;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link CorrectionStore}.  Verifies the
 * entries / direct slots' independence, the change-notifier semantics, and
 * the snapshot / restore pair used by the wizard's cancel path.
 *
 * <p>Each test subscribes a counter to the store's configured {@code MessageBus}
 * event, so the change count is asserted directly.
 */
class FreqRespCorrectionStoreTest {

    private static final String TEST_EVENT = "test.calibration.changed";

    /** The loaded row's own frequency grid for the band-refresh cases - points
     *  inside and outside the probe band, so a refresh that reached too far is
     *  visible. */
    private static final double[] GRID_HZ = { 100, 500, 900, 950, 1000, 1050, 1100, 5000 };
    /** The magnitude every point of a freshly built row carries. */
    private static final double FLAT_MAG_LIN = 1.0;
    /** The refreshed band - the ends of the probe's own grid. */
    private static final double BAND_LO_HZ = 900;
    private static final double BAND_HI_HZ = 1100;
    /** The probe's response: constant across the band, so every rewritten point
     *  must read exactly these two numbers whatever the grid spacing. */
    private static final double PROBE_MAG_LIN   = 0.5;
    private static final double PROBE_PHASE_RAD = 0.25;

    private final AtomicInteger  changes = new AtomicInteger();
    private final Consumer<Void> counter = ignored -> changes.incrementAndGet();
    private CorrectionStore store;

    @BeforeEach
    void setup() {
        changes.set(0);
        MessageBus.instance().subscribe(TEST_EVENT, counter);
        store = new CorrectionStore("test", TEST_EVENT);
    }

    @AfterEach
    void teardown() {
        MessageBus.instance().unsubscribe(TEST_EVENT, counter);
    }

    @Test
    void setCurrentNotifiesAndRetainsValues() {
        StereoFreqRespCalibration cal = sampleStereo();
        store.setCurrent(cal, "/tmp/cal.csv");
        assertSame(cal, store.getCurrent());
        assertEquals("/tmp/cal.csv", store.getCurrentPath());
        assertEquals(1, changes.get(), "setCurrent should fire one notification");
    }

    @Test
    void clearCurrentNotifiesOnlyWhenSomethingChanges() {
        store.setCurrent(sampleStereo(), "/tmp/a.csv");
        changes.set(0);

        store.clearCurrent();
        assertNull(store.getCurrent());
        assertNull(store.getCurrentPath());
        assertEquals(1, changes.get());

        // Second clear is a no-op - no extra notification.
        store.clearCurrent();
        assertEquals(1, changes.get());
    }

    @Test
    void directSlotNotifiesButDoesNotLeakIntoEntries() {
        StereoFreqRespCalibration direct = sampleStereo();
        store.setDirect(direct);
        assertSame(direct, store.getDirect());
        assertNull(store.getCurrent(),
                "setDirect must not leak into the entries list");
        assertEquals(1, changes.get(),
                "setDirect notifies so the view re-applies");
    }

    @Test
    void snapshotRestoreReturnsBothSlotsAndOneNotification() {
        StereoFreqRespCalibration originalCurrent = sampleStereo();
        StereoFreqRespCalibration originalDirect  = sampleStereo();
        store.setCurrent(originalCurrent, "/tmp/orig.csv");
        store.setDirect(originalDirect);

        CorrectionStore.Snapshot snap = store.snapshot();

        // Mutate both slots.
        StereoFreqRespCalibration tempCurrent = sampleStereo();
        store.setCurrent(tempCurrent, "/tmp/temp.csv");
        store.setDirect(sampleStereo());
        assertSame(tempCurrent, store.getCurrent());

        changes.set(0);
        store.restore(snap);

        assertSame(originalCurrent, store.getCurrent(), "current restored");
        assertEquals("/tmp/orig.csv", store.getCurrentPath(), "path restored");
        assertSame(originalDirect,  store.getDirect(),  "direct restored");
        assertEquals(1, changes.get(),
                "restore should fire exactly one notification when current moved");
    }

    @Test
    void snapshotRestoreIsSilentWhenCurrentDidNotMove() {
        store.setCurrent(sampleStereo(), "/tmp/x.csv");
        CorrectionStore.Snapshot snap = store.snapshot();
        store.setDirect(sampleStereo());
        changes.set(0);

        store.restore(snap);
        assertEquals(0, changes.get(),
                "restore that doesn't change the current slot must not fire");
    }

    @Test
    void refreshBandRewritesOnlyTheBandOfTheTopmostWithNoiseRow() {
        StereoFreqRespCalibration toneOnly  = flatStereo();
        StereoFreqRespCalibration withNoise = flatStereo();
        store.addEntry(toneOnly,  "/tmp/tone-only.frc", false);
        store.addEntry(withNoise, "/tmp/with-noise.frc", true);
        int entriesBefore = store.getEntries().size();
        CorrectionStore.Entry firstRowBefore = store.getEntries().get(0);
        changes.set(0);

        assertTrue(store.refreshBand(Channel.L, BAND_LO_HZ, BAND_HI_HZ, flatProbe()),
                "a loaded with-noise row is refreshed");

        assertEquals(1, changes.get(),
                "the refresh publishes the store's change event, or the cached "
                        + "correction never picks the new response up");
        assertEquals(entriesBefore, store.getEntries().size(), "no entry added or removed");
        assertSame(firstRowBefore, store.getEntries().get(0), "the rows themselves are kept");

        FreqRespCalibration refreshed = withNoise.left();
        for (int i = 0; i < GRID_HZ.length; i++) {
            double f = GRID_HZ[i];
            if (f >= BAND_LO_HZ && f <= BAND_HI_HZ) {
                assertEquals(PROBE_MAG_LIN, refreshed.magLin[i], 1e-12,
                        "in-band magnitude at " + f + " Hz comes from the probe");
                assertEquals(PROBE_PHASE_RAD, refreshed.phaseRad[i], 1e-12,
                        "in-band phase at " + f + " Hz comes from the probe");
            } else {
                assertEquals(FLAT_MAG_LIN, refreshed.magLin[i], 0.0,
                        "out-of-band magnitude at " + f + " Hz is untouched");
                assertEquals(0.0, refreshed.phaseRad[i], 0.0,
                        "out-of-band phase at " + f + " Hz is untouched");
            }
        }
        assertArrayEquals(flatMagnitudes(), withNoise.right().magLin, 0.0,
                "the opposite channel of the refreshed row is untouched");
        assertArrayEquals(flatMagnitudes(), toneOnly.left().magLin, 0.0,
                "a tone-only row above the with-noise row is not the one refreshed");
    }

    @Test
    void refreshBandWithoutAWithNoiseRowChangesNothing() {
        StereoFreqRespCalibration toneOnly = flatStereo();
        store.addEntry(toneOnly, "/tmp/tone-only.frc", false);
        changes.set(0);

        assertFalse(store.refreshBand(Channel.L, BAND_LO_HZ, BAND_HI_HZ, flatProbe()),
                "with no with-noise row loaded there is nothing to refresh");
        assertEquals(0, changes.get(), "a refresh that did nothing publishes nothing");
        assertArrayEquals(flatMagnitudes(), toneOnly.left().magLin, 0.0);
    }

    @Test
    void refreshBandStopsAtTheEndsOfTheMeasuredGrid() {
        StereoFreqRespCalibration withNoise = flatStereo();
        store.addEntry(withNoise, "/tmp/with-noise.frc", true);

        // A band far wider than the probe measured: beyond the probe's own ends
        // the interpolation would hold its end point and flatten the row there.
        assertTrue(store.refreshBand(Channel.R, GRID_HZ[0], GRID_HZ[GRID_HZ.length - 1],
                flatProbe()));

        FreqRespCalibration refreshed = withNoise.right();
        for (int i = 0; i < GRID_HZ.length; i++) {
            double f = GRID_HZ[i];
            if (f >= BAND_LO_HZ && f <= BAND_HI_HZ) continue;
            assertEquals(FLAT_MAG_LIN, refreshed.magLin[i], 0.0,
                    "point at " + f + " Hz lies outside the measured band and is untouched");
        }
        assertArrayEquals(flatMagnitudes(), withNoise.left().magLin, 0.0,
                "the opposite channel of the refreshed row is untouched");
    }

    /** A stereo row on {@link #GRID_HZ} whose two sides own SEPARATE arrays, so
     *  a refresh of one side can be seen not to reach the other. */
    private StereoFreqRespCalibration flatStereo() {
        return new StereoFreqRespCalibration(flatSide(), flatSide());
    }

    private FreqRespCalibration flatSide() {
        return new FreqRespCalibration(GRID_HZ.clone(), flatMagnitudes(),
                new double[GRID_HZ.length]);
    }

    private double[] flatMagnitudes() {
        double[] mag = new double[GRID_HZ.length];
        Arrays.fill(mag, FLAT_MAG_LIN);
        return mag;
    }

    /** Three points spanning the band at one constant magnitude and phase. */
    private FreqRespCalibration flatProbe() {
        return new FreqRespCalibration(
                new double[] { BAND_LO_HZ, 1000, BAND_HI_HZ },
                new double[] { PROBE_MAG_LIN, PROBE_MAG_LIN, PROBE_MAG_LIN },
                new double[] { PROBE_PHASE_RAD, PROBE_PHASE_RAD, PROBE_PHASE_RAD });
    }

    private StereoFreqRespCalibration sampleStereo() {
        double[] f = { 20, 200, 2000, 20000 };
        double[] m = { 1.0, 1.0, 1.0, 1.0 };
        double[] p = { 0.0, 0.0, 0.0, 0.0 };
        FreqRespCalibration left  = new FreqRespCalibration(f, m, p);
        FreqRespCalibration right = new FreqRespCalibration(f, m, p);
        return new StereoFreqRespCalibration(left, right);
    }
}
