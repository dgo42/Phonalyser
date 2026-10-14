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

package org.edgo.audio.measure.gui.fft;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.sound.DoubleSignalBuffer;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator frequency lock inside the FFT analyser worker: which analysis
 * cycles feed the loop after a capture re-sync, and what a lock reset does
 * while no worker thread runs.
 *
 * <p>The re-sync is the worker's own discontinuity path, driven over a real
 * {@link SignalBufferReader}.  The cycles are the worker's loop step itself,
 * handed synthetic results: the step's inputs are the cycle's result and three
 * capture positions, while a full analysis cycle would need a filled capture
 * ring, a real transform and the whole preference set of the cycle's pipeline.
 * The step, the re-sync path and the state they share are private - a
 * productive class is not widened to let a test in - so the harness goes
 * through reflection.  What the loop did is read off the real
 * {@link FrequencyFll}: its correction, and the capture position of its last
 * update, which it records on every update call.
 *
 * <p>The worker is headless (no display), so nothing it posts to the UI thread
 * is delivered: the trims and the reset event are not asserted here.
 */
class FftFrequencyLockGatingTest {

    private static final int    SAMPLE_RATE    = 48_000;
    private static final int    FFT_SIZE       = 4096;
    /** The generator's tone sits on this bin of the grid, so the snap leaves
     *  the target where it is. */
    private static final int    TARGET_BIN     = 100;
    private static final double TARGET_HZ      = TARGET_BIN * SAMPLE_RATE / (double) FFT_SIZE;
    /** The mistune every analysis cycle measures: a hundred times the loop's
     *  lock band, and five hundred times inside its plausibility bound, so
     *  every cycle that reaches the loop corrects. */
    private static final double MISTUNE_PPM    = 1.0;
    private static final double MEASURED_HZ    = TARGET_HZ * (1.0 + MISTUNE_PPM * 1.0e-6);
    private static final double FUND_DBFS      = -20.0;
    private static final double BUFFER_SECONDS = 1.0;
    /** Window starts, samples.  An analysis cycle's reading reaches the loop
     *  on the cycle after it, so the loop's first update is the second
     *  cycle's, under the first cycle's window start.  The readings fed after
     *  the re-sync stand past the point from which that update's correction
     *  counts as arrived - the second cycle's write position plus the loop's
     *  0.7 s drain guard, 38 720 samples here - so a reading that reaches the
     *  loop is a full correction, not a hold in transit. */
    private static final long   FIRST_CYCLE    = 0L;
    private static final long   SECOND_CYCLE   = 1_024L;
    private static final long   STORED_CYCLE   = 30_000L;
    private static final long   SKIPPED_CYCLE  = 40_000L;
    private static final long   FED_CYCLE      = 50_000L;
    /** A last-published correction no loop would produce: survives a reset
     *  that returns early, turns into NaN on one that goes through. */
    private static final double MARKER_HZ      = 0.5;

    /** The worker members this harness drives, by name because that is what
     *  reflection takes. */
    private static final String STEP      = "applyFrequencyLock";
    private static final String RESYNC    = "onSignalDiscontinuity";
    private static final String LOOP      = "fll";
    private static final String HOLD      = "fllResyncHold";
    private static final String PUBLISHED = "fllPublishedCorrection";
    private static final String ARMED     = "alignWatchArmed";
    /** The loop's record of its last update, by name for the same reason. */
    private static final String LAST_UPDATE = "lastUpdateAbsPos";
    /** The worker's memory of the analysed channel's tones, and that
     *  memory's last reading of the first tone - where the loop takes the
     *  analysed channel's measurement from. */
    private static final String ANALYSED_TONES = "analysedTones";
    private static final String TONE1_HZ       = "hz1";

    private boolean        previousSnap;
    private boolean        previousFundFromGen;
    private AlignGenerator previousAlign;
    private GenSignalForm  previousForm;
    private double         previousFrequencyHz;
    private int            previousFftLength;

    /** The loop runs only with snap-to-bin, fundamental-from-generator and a
     *  lock mode on, and with the generator emitting - which the responder
     *  states, whatever else this JVM has registered. */
    @BeforeEach
    void lockEngaged() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);   // the settings are written through the live singleton
        previousSnap        = prefs.isGenSnapToFftBin();
        previousFundFromGen = prefs.isFftFundFromGenerator();
        previousAlign       = prefs.getFftAlignGenerator();
        previousForm        = prefs.getGenSignalForm();
        previousFrequencyHz = prefs.getGenFrequencyHz();
        previousFftLength   = prefs.getFftLength();
        prefs.setGenSnapToFftBin(true);
        prefs.setFftFundFromGenerator(true);
        prefs.setFftAlignGenerator(AlignGenerator.FLL);
        prefs.setGenSignalForm(GenSignalForm.SINE);
        prefs.setGenFrequencyHz(TARGET_HZ);
        prefs.setFftLength(FFT_SIZE);
        MessageBus.instance().registerResponder(Events.GENERATOR_RUNNING, () -> Boolean.TRUE);
    }

    @AfterEach
    void settingsRestored() {
        MessageBus.instance().unregisterResponder(Events.GENERATOR_RUNNING);
        Preferences prefs = Preferences.instance();
        prefs.setGenSnapToFftBin(previousSnap);
        prefs.setFftFundFromGenerator(previousFundFromGen);
        prefs.setFftAlignGenerator(previousAlign);
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequencyHz);
        prefs.setFftLength(previousFftLength);
    }

    /**
     * A re-sync holds the loop for exactly one feed.  An analysis cycle's
     * reading reaches the loop on the cycle after it, and the re-sync drops
     * the reading in hand: the first cycle after it has nothing to feed, and
     * the cycle after that - the first that would feed the loop, every gate
     * passed - is skipped and uses the hold up; the next one updates the loop
     * again.  The loop itself is not reset by the re-sync: its correction
     * stands through the skipped cycle.
     */
    @Test
    void theFirstCycleThatWouldFeedTheLoopAfterAReSyncIsSkipped() throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);   // headless

        cycle(worker, FIRST_CYCLE);
        FrequencyAligner fll = loop(worker);
        assertNotNull(fll, "an analysis cycle that passes every gate engages the loop");
        assertEquals(-1L, lastUpdate(fll), "its reading is kept for the next analysis cycle");
        cycle(worker, SECOND_CYCLE);
        assertEquals(FIRST_CYCLE, lastUpdate(fll), "which updates the loop with it");
        double corrected = fll.getCorrection();
        assertNotEquals(0.0, corrected, "a 1 ppm mistune is corrected in one step");

        call(worker, RESYNC, new SignalBufferReader(new DoubleSignalBuffer(SAMPLE_RATE, BUFFER_SECONDS)));
        assertTrue((Boolean) FieldUtils.readField(worker, HOLD, true), "the re-sync holds the loop");

        cycle(worker, STORED_CYCLE);
        assertEquals(FIRST_CYCLE, lastUpdate(fll),
                "the first analysis cycle after the re-sync has no reading to feed");
        assertTrue((Boolean) FieldUtils.readField(worker, HOLD, true), "and leaves the hold armed");

        cycle(worker, SKIPPED_CYCLE);
        assertEquals(FIRST_CYCLE, lastUpdate(fll),
                "the first analysis cycle that would feed the loop does not update it");
        assertEquals(corrected, fll.getCorrection(), "nor move its correction");
        assertFalse((Boolean) FieldUtils.readField(worker, HOLD, true), "and it uses the hold up");

        cycle(worker, FED_CYCLE);
        assertEquals(SKIPPED_CYCLE, lastUpdate(fll), "the next analysis cycle updates the loop");
        assertNotEquals(corrected, fll.getCorrection(),
                "and the mistune it still measures moves the correction");
    }

    /**
     * A lock reset asked for while no worker thread runs is performed on the
     * caller's thread.  With no loop ever engaged it re-arms the magnitude
     * watch and stops there: the re-send marker, written by the line just
     * ahead of the reset event, survives - so the event is not posted.  With a
     * loop engaged the same reset zeroes the loop and goes through to the
     * re-send and the event.
     */
    @Test
    void aResetWhileStoppedPostsNothingWhenNoLoopWasEngaged() throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);   // never started: no worker thread

        FieldUtils.writeField(worker, PUBLISHED, MARKER_HZ, true);
        worker.resetFrequencyLock();
        assertTrue((Boolean) FieldUtils.readField(worker, ARMED, true),
                "the magnitude watch is re-armed");
        assertEquals(MARKER_HZ, published(worker),
                "no loop engaged: the reset returns ahead of the re-send and the reset event");

        cycle(worker, FIRST_CYCLE);
        FieldUtils.writeField(worker, PUBLISHED, MARKER_HZ, true);
        worker.resetFrequencyLock();
        assertTrue(Double.isNaN(published(worker)),
                "a loop engaged: the reset reaches the re-send, the line ahead of the reset event");
        assertEquals(0.0, loop(worker).getCorrection(), "and zeroes the loop");
    }

    // ── Drivers ────────────────────────────────────────────────────────────

    /** One analysis cycle of the loop step with the analysis mode NONE, the
     *  analysed channel measuring {@link #MEASURED_HZ} - the reading the
     *  worker's analysed-tone memory holds for the cycle - and the write
     *  position one transform past the window's start. */
    private void cycle(FftAnalyzerWorker worker, long absStart) throws IllegalAccessException {
        FftResult r = new FftResult();
        r.sampleRate           = SAMPLE_RATE;
        r.fftSize              = FFT_SIZE;
        r.freqResolution       = SAMPLE_RATE / (double) FFT_SIZE;
        r.fundamentalHzRefined = MEASURED_HZ;
        r.fundamentalDbFs      = FUND_DBFS;
        FieldUtils.writeField(FieldUtils.readField(worker, ANALYSED_TONES, true), TONE1_HZ, MEASURED_HZ, true);
        call(worker, STEP, r, FundamentalPhaseAlignment.NONE, absStart, absStart + FFT_SIZE,
                worker.currentResetEpoch());
    }

    /** The one place this class calls into the worker's private members, so a
     *  renamed member fails here and not at every call site. */
    private void call(FftAnalyzerWorker worker, String method, Object... args) {
        try {
            MethodUtils.invokeMethod(worker, true, method, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the worker's " + method + " is not reachable", ex);
        }
    }

    private FrequencyAligner loop(FftAnalyzerWorker worker) throws IllegalAccessException {
        return (FrequencyAligner) FieldUtils.readField(worker, LOOP, true);
    }

    private long lastUpdate(FrequencyAligner fll) throws IllegalAccessException {
        return (Long) FieldUtils.readField(fll, LAST_UPDATE, true);
    }

    private double published(FftAnalyzerWorker worker) throws IllegalAccessException {
        return (Double) FieldUtils.readField(worker, PUBLISHED, true);
    }
}
