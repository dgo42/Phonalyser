package org.edgo.audio.measure.gui.freqresp;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;

/**
 * Single owner of the application's Frequency Response calibration state.
 * Two independent slots, both holding a {@link StereoFreqRespCalibration}
 * so cal-L applies to measured-L and cal-R to measured-R:
 *
 * <ul>
 *   <li>{@link #getCurrent() current} — the calibration the analyzer divides
 *       fresh measurements by.  Backed by either a file the user loaded from
 *       disk or the wizard's last-applied calibration.  May be {@code null}
 *       when no calibration is active.</li>
 *   <li>{@link #getDirect() direct} — the loopback DAC→ADC response measured
 *       on page 1 of the calibration wizard.  Used to bootstrap a calibration
 *       so the page-2 measurement of the device-under-test isn't polluted by
 *       the bench's own transfer function.</li>
 * </ul>
 *
 * <p>Every mutation publishes {@link Events#FREQRESP_CALIBRATION_CHANGED} on
 * the {@link MessageBus} so the pane's calibration tab can refresh the file
 * path label and the worker can pick up the new calibration on the next run.
 *
 * <p>The wizard captures a {@link #snapshot()} on open and calls
 * {@link #restore(Snapshot)} on cancel so a cancelled wizard doesn't leave
 * the in-memory state pointing at a half-measured response.
 */
@Log4j2
public final class FreqRespCalibrationStore {

    private static volatile FreqRespCalibrationStore instance;

    public static FreqRespCalibrationStore instance() {
        FreqRespCalibrationStore local = instance;
        if (local != null) return local;
        synchronized (FreqRespCalibrationStore.class) {
            if (instance == null) instance = new FreqRespCalibrationStore();
            return instance;
        }
    }

    private StereoFreqRespCalibration current;
    private String                    currentPath;
    private StereoFreqRespCalibration direct;

    private FreqRespCalibrationStore() {}

    /** Currently-active calibration, or {@code null} when none loaded. */
    public synchronized StereoFreqRespCalibration getCurrent() {
        return current;
    }

    /** Filesystem path the current calibration was loaded from, or
     *  {@code null} when the current calibration is in-memory only
     *  (e.g. set by the wizard before save). */
    public synchronized String getCurrentPath() {
        return currentPath;
    }

    /** The wizard's page-1 loopback measurement, or {@code null} when the
     *  wizard hasn't run since the last clear. */
    public synchronized StereoFreqRespCalibration getDirect() {
        return direct;
    }

    /**
     * Replaces the current calibration and publishes
     * {@link Events#FREQRESP_CALIBRATION_CHANGED}.  Pass {@code path} to
     * record the file the calibration came from (so the calibration tab
     * can show it); pass {@code null} for in-memory calibrations.
     */
    public synchronized void setCurrent(StereoFreqRespCalibration calibration, String path) {
        this.current     = calibration;
        this.currentPath = path;
        log.info("FreqResp current calibration {} ({} points)",
                path != null ? "loaded from " + path : "set in-memory",
                calibration != null ? calibration.left().freqs.length : 0);
        fire();
    }

    /** Convenience overload that clears both the calibration and its
     *  associated file path. */
    public synchronized void clearCurrent() {
        if (current == null && currentPath == null) return;
        this.current     = null;
        this.currentPath = null;
        log.info("FreqResp current calibration cleared");
        fire();
    }

    /**
     * Replaces the direct (wizard page-1) calibration buffer.  Pass
     * {@code null} to clear.  Does NOT publish a calibration-changed event
     * — the wizard controls when the direct buffer is promoted to current.
     */
    public synchronized void setDirect(StereoFreqRespCalibration directCal) {
        this.direct = directCal;
    }

    /** Captures both slots so the wizard can restore them on cancel. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(current, currentPath, direct);
    }

    /** Restores both slots from a prior {@link #snapshot()} and publishes
     *  one change event if the current calibration actually moved. */
    public synchronized void restore(Snapshot s) {
        boolean changed = (current != s.current) || !pathEq(currentPath, s.currentPath);
        this.current     = s.current;
        this.currentPath = s.currentPath;
        this.direct      = s.direct;
        if (changed) fire();
    }

    private boolean pathEq(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    private void fire() {
        MessageBus.instance().publish(Events.FREQRESP_CALIBRATION_CHANGED);
    }

    /** Opaque snapshot of every slot — only created by
     *  {@link FreqRespCalibrationStore#snapshot()}. */
    public static final class Snapshot {
        private final StereoFreqRespCalibration current;
        private final String                    currentPath;
        private final StereoFreqRespCalibration direct;

        private Snapshot(StereoFreqRespCalibration current, String currentPath, StereoFreqRespCalibration direct) {
            this.current     = current;
            this.currentPath = currentPath;
            this.direct      = direct;
        }
    }
}
