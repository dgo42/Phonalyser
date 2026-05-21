package org.edgo.audio.measure.gui.freqresp;

import java.util.concurrent.atomic.AtomicInteger;

import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Behaviour tests for {@link FreqRespCalibrationStore}.  Verifies the two
 * slots' independence, the bus-event semantics, and the snapshot / restore
 * pair used by the wizard's cancel path.
 *
 * <p>The store is a process-wide singleton; each test resets both slots in
 * {@link #cleanup()} so cross-test interference can't accumulate.
 */
class FreqRespCalibrationStoreTest {

    private final FreqRespCalibrationStore store = FreqRespCalibrationStore.instance();
    private final MessageBus bus = MessageBus.instance();

    private AtomicInteger eventCounter;
    private Runnable handler;

    @BeforeEach
    void setup() {
        store.clearCurrent();
        store.setDirect(null);
        eventCounter = new AtomicInteger();
        handler = eventCounter::incrementAndGet;
        bus.subscribe(Events.FREQRESP_CALIBRATION_CHANGED, handler);
        eventCounter.set(0);
    }

    @AfterEach
    void cleanup() {
        bus.unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED, handler);
        store.clearCurrent();
        store.setDirect(null);
    }

    @Test
    void singletonReturnsSameInstance() {
        assertSame(store, FreqRespCalibrationStore.instance());
    }

    @Test
    void setCurrentPublishesEventAndRetainsValues() {
        StereoFreqRespCalibration cal = sampleStereo();
        store.setCurrent(cal, "/tmp/cal.csv");
        assertSame(cal, store.getCurrent());
        assertEquals("/tmp/cal.csv", store.getCurrentPath());
        assertEquals(1, eventCounter.get(), "setCurrent should fire one event");
    }

    @Test
    void clearCurrentPublishesEventOnlyWhenSomethingChanges() {
        store.setCurrent(sampleStereo(), "/tmp/a.csv");
        eventCounter.set(0);

        store.clearCurrent();
        assertNull(store.getCurrent());
        assertNull(store.getCurrentPath());
        assertEquals(1, eventCounter.get());

        // Second clear is a no-op — no extra event.
        store.clearCurrent();
        assertEquals(1, eventCounter.get());
    }

    @Test
    void directSlotIsIndependentAndDoesNotFireEvent() {
        StereoFreqRespCalibration direct = sampleStereo();
        store.setDirect(direct);
        assertSame(direct, store.getDirect());
        assertNull(store.getCurrent(),
                "setDirect must not leak into the current slot");
        assertEquals(0, eventCounter.get(),
                "setDirect must not publish FREQRESP_CALIBRATION_CHANGED");
    }

    @Test
    void snapshotRestoreReturnsBothSlotsAndOnlyOneEvent() {
        StereoFreqRespCalibration originalCurrent = sampleStereo();
        StereoFreqRespCalibration originalDirect  = sampleStereo();
        store.setCurrent(originalCurrent, "/tmp/orig.csv");
        store.setDirect(originalDirect);

        FreqRespCalibrationStore.Snapshot snap = store.snapshot();

        // Mutate both slots.
        StereoFreqRespCalibration tempCurrent = sampleStereo();
        store.setCurrent(tempCurrent, "/tmp/temp.csv");
        store.setDirect(sampleStereo());
        assertSame(tempCurrent, store.getCurrent());

        eventCounter.set(0);
        store.restore(snap);

        assertSame(originalCurrent, store.getCurrent(), "current restored");
        assertEquals("/tmp/orig.csv", store.getCurrentPath(),       "path restored");
        assertSame(originalDirect,  store.getDirect(),  "direct restored");
        assertEquals(1, eventCounter.get(),
                "restore should fire exactly one calibration-changed event when current moved");
    }

    @Test
    void snapshotRestoreIsSilentWhenCurrentDidNotMove() {
        store.setCurrent(sampleStereo(), "/tmp/x.csv");
        FreqRespCalibrationStore.Snapshot snap = store.snapshot();
        store.setDirect(sampleStereo());
        eventCounter.set(0);

        store.restore(snap);
        assertEquals(0, eventCounter.get(),
                "restore that doesn't change the current slot must not fire");
    }

    private StereoFreqRespCalibration sampleStereo() {
        double[] f = { 20, 200, 2000, 20000 };
        double[] m = { 1.0, 1.0, 1.0, 1.0 };
        double[] p = { 0.0, 0.0, 0.0, 0.0 };
        FreqRespCalibration left  = new FreqRespCalibration(f, m, p);
        FreqRespCalibration right = new FreqRespCalibration(f, m, p);
        assertNotNull(left);
        return new StereoFreqRespCalibration(left, right);
    }
}
