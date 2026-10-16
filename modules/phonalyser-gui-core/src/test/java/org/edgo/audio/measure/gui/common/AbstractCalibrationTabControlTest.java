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

package org.edgo.audio.measure.gui.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.preferences.CalibrationEntry;
import org.edgo.audio.measure.preferences.CalibrationList;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calibration tab follows its {@link CalibrationList} change by change:
 * each change touches the one row it names, in the same call - an Active tick
 * replaces no row widget, a path change reads only its row's file, a removal
 * disposes only its row, an addition inserts one row at its index, a saved
 * file is read again only by the rows that reference it - and the store is
 * re-synced after each.  A row's file is read again exactly when the store
 * holds a new calibration object for it.
 *
 * <p>The tab control runs on a real display on the test thread; a host
 * without a display skips the cases.
 */
class AbstractCalibrationTabControlTest {

    private static final String KEY_PREFIX  = "test";
    private static final String STORE_LABEL = "test";
    private static final String FRC_HEADER  =
            "frequency_hz,mag_left_dB,mag_right_dB,phase_left_deg,phase_right_deg";

    /** The pane the tab control belongs to; nothing the cases look at. */
    private static final class TestPane extends AbstractPane {

        TestPane(Composite parent) {
            super(parent);
        }

        @Override
        protected void onTabCollapse() {
        }
    }

    /** The calibration tab alone, built and followed the way a pane's tab
     *  control builds it. */
    private static final class TestTabControl extends AbstractCalibrationTabControl<TestPane> {

        TestTabControl(Composite parent, CorrectionStore store, CalibrationList list) {
            super(parent, SWT.NONE, new TestPane(parent), store, list, new Property<>(null),
                    KEY_PREFIX, true);
            toolbarTabs = new TileTabFolder(this, SWT.NONE);
            buildCalibrationTab();
            followCalibrations();
        }

        @Override
        protected void onCalibrationsSynced() {
        }

        /** The row widgets, in display order. */
        Control[] rows() {
            ScrolledComposite scroll = (ScrolledComposite) toolbarTabs.getItem(0).getControl();
            return ((Composite) scroll.getContent()).getChildren();
        }
    }

    @TempDir
    Path dir;

    private Display display;
    private Shell   shell;

    @BeforeEach
    void shellCreated() {
        try {
            display = new Display();
        } catch (SWTError | UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "SWT display unavailable: " + e.getMessage());
        }
        shell = new Shell(display);
    }

    @AfterEach
    void shellDisposed() {
        // The icon cache holds images of this display; the next case's
        // display needs its own.
        IconUtils.clear();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
    }

    /** Writes a two-point calibration file whose upper point stands at
     *  {@code gainDb} on both channels; returns its path. */
    private String frc(String name, double gainDb) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, FRC_HEADER + "\n20.0,0.0,0.0,0.0,0.0\n20000.0,"
                + gainDb + "," + gainDb + ",0.0,0.0\n");
        return p.toString();
    }

    /** The calibrations the store holds, in store order. */
    private List<StereoFreqRespCalibration> held(CorrectionStore store) {
        return store.getEntries().stream().map(CorrectionStore.Entry::getCalibration).toList();
    }

    @Test
    void anActiveTickReplacesNoRowWidget() throws IOException {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry(frc("a.frc", 1.0), true, false));
        list.add(new CalibrationEntry(frc("b.frc", 2.0), false, false));
        CorrectionStore store = new CorrectionStore(STORE_LABEL);
        TestTabControl tabs = new TestTabControl(shell, store, list);
        Control[] before = tabs.rows();
        List<StereoFreqRespCalibration> heldBefore = held(store);

        list.get(1).active().set(true);

        assertArrayEquals(before, tabs.rows(), "the same row widgets, in the same order");
        for (Control row : before) {
            assertFalse(row.isDisposed());
        }
        List<StereoFreqRespCalibration> heldAfter = held(store);
        assertEquals(2, heldAfter.size(), "the ticked row reached the store");
        assertSame(heldBefore.get(0), heldAfter.get(0), "no file was read again");
    }

    @Test
    void aPathChangeReadsOnlyItsRowsFile() throws IOException {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry(frc("a.frc", 1.0), true, false));
        list.add(new CalibrationEntry(frc("b.frc", 2.0), true, false));
        CorrectionStore store = new CorrectionStore(STORE_LABEL);
        TestTabControl tabs = new TestTabControl(shell, store, list);
        Control[] before = tabs.rows();
        List<StereoFreqRespCalibration> heldBefore = held(store);
        String c = frc("c.frc", 3.0);

        list.get(1).setPath(c);

        List<CorrectionStore.Entry> after = store.getEntries();
        assertSame(heldBefore.get(0), after.get(0).getCalibration(), "row 0 was not read again");
        assertNotSame(heldBefore.get(1), after.get(1).getCalibration(), "row 1 read its new file");
        assertEquals(c, after.get(1).getPath());
        assertArrayEquals(before, tabs.rows(), "the same row widgets, in the same order");
    }

    @Test
    void aRemovalDisposesOnlyItsRow() {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry());
        list.add(new CalibrationEntry());
        list.add(new CalibrationEntry());
        TestTabControl tabs = new TestTabControl(shell, new CorrectionStore(STORE_LABEL), list);
        Control[] before = tabs.rows();

        list.remove(1);

        Control[] after = tabs.rows();
        assertEquals(2, after.length);
        assertSame(before[0], after[0]);
        assertSame(before[2], after[1]);
        assertTrue(before[1].isDisposed());
    }

    @Test
    void anAdditionInsertsOneRowAtItsIndex() throws IOException {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry());
        list.add(new CalibrationEntry());
        CorrectionStore store = new CorrectionStore(STORE_LABEL);
        TestTabControl tabs = new TestTabControl(shell, store, list);
        Control[] before = tabs.rows();
        String a = frc("a.frc", 1.0);

        list.add(1, new CalibrationEntry(a, true, false));

        Control[] after = tabs.rows();
        assertEquals(3, after.length);
        assertSame(before[0], after[0]);
        assertSame(before[1], after[2], "the row behind moved down by one");
        assertEquals(a, store.getEntries().get(0).getPath(), "the added row reached the store");
    }

    @Test
    void aSavedFileIsReadAgainOnlyByTheRowsReferencingIt() throws IOException {
        Preferences prefs = Preferences.instance().copyForDialog();
        CalibrationList list = prefs.getFftCalibrations();
        String a = frc("a.frc", 1.0);
        list.add(new CalibrationEntry(a, true, false));
        list.add(new CalibrationEntry(frc("b.frc", 2.0), true, false));
        CorrectionStore store = new CorrectionStore(STORE_LABEL);
        new TestTabControl(shell, store, list);
        List<StereoFreqRespCalibration> before = held(store);

        prefs.broadcastSavedFile(a);

        List<StereoFreqRespCalibration> after = held(store);
        assertNotSame(before.get(0), after.get(0), "the saved file was read again");
        assertSame(before.get(1), after.get(1), "the other row was not read again");
    }

    @Test
    void clearingTheListLeavesOneEmptyRow() throws IOException {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry(frc("a.frc", 1.0), true, false));
        list.add(new CalibrationEntry(frc("b.frc", 2.0), true, false));
        CorrectionStore store = new CorrectionStore(STORE_LABEL);
        TestTabControl tabs = new TestTabControl(shell, store, list);

        list.clear();

        assertEquals(1, list.size(), "row 0 is always present");
        assertEquals(1, tabs.rows().length);
        assertTrue(store.getEntries().isEmpty());
    }
}
