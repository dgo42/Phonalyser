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

package org.edgo.audio.measure.common;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Behaviour tests for {@link FreqRespCorrectionStore}.  Verifies the
 * entries / direct slots' independence, the change-notifier semantics, and
 * the snapshot / restore pair used by the wizard's cancel path.
 *
 * <p>Each test subscribes a counter to the store's configured {@code MessageBus}
 * event, so the change count is asserted directly.
 */
class FreqRespCorrectionStoreTest {

    private static final String TEST_EVENT = "test.calibration.changed";

    private final AtomicInteger  changes = new AtomicInteger();
    private final Consumer<Void> counter = ignored -> changes.incrementAndGet();
    private FreqRespCorrectionStore store;

    @BeforeEach
    void setup() {
        changes.set(0);
        MessageBus.instance().subscribe(TEST_EVENT, counter);
        store = new FreqRespCorrectionStore("test", TEST_EVENT);
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

        // Second clear is a no-op — no extra notification.
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

        FreqRespCorrectionStore.Snapshot snap = store.snapshot();

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
        FreqRespCorrectionStore.Snapshot snap = store.snapshot();
        store.setDirect(sampleStereo());
        changes.set(0);

        store.restore(snap);
        assertEquals(0, changes.get(),
                "restore that doesn't change the current slot must not fire");
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
