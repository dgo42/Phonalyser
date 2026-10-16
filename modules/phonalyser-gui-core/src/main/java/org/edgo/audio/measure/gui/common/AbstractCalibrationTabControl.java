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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.preferences.CalibrationEntry;
import org.edgo.audio.measure.preferences.CalibrationList;

import lombok.extern.log4j.Log4j2;

/**
 * A pane's toolbar tab control with a calibration tab: one row per item of a
 * {@link CalibrationList} (path, Active, load, clear, add, remove), the
 * {@code .frc} each row references loaded from disk, and the
 * {@link CorrectionStore} the pane's view divides by, re-synced from the
 * active, loaded rows.  The list is the source of truth: a user action on a
 * row only changes the list, and every list change - a row action, a wizard's
 * Apply, a saved file an item references - reaches the one row it names
 * through the list's change, in the same call, and re-syncs the store.  The
 * rows stay parallel to the list: row {@code i} shows item {@code i}.
 *
 * <p>The subclass builds the calibration tab in its own tab order
 * ({@link #buildCalibrationTab}), starts following the list once every tab
 * exists ({@link #followCalibrations}), and re-derives what divides by the
 * store after each re-sync ({@link #onCalibrationsSynced}).  The tab's texts
 * come from the subclass's message-key prefix ({@code <prefix>.calibration.*}).
 * An optional With-noise column (the FFT pane's) marks an entry whose
 * correction applies to every bin, noise floor included.
 *
 * @param <P> the type of the owning pane
 */
@Log4j2
public abstract class AbstractCalibrationTabControl<P extends AbstractPane> extends AbstractTabControl<P> {

    private static final class CalRow {
        Composite                composite;
        Text                     pathField;
        /** "Active" toggle - two-way bound to {@code entry.active()}; the
         *  calibration is only pushed into the store when this is checked AND
         *  a file is loaded. */
        Button                   activeCheck;
        /** "With noise" toggle - two-way bound to {@code entry.withNoise()};
         *  {@code null} without the With-noise column.  Only meaningful while
         *  a file is loaded AND the row is active; disabled otherwise. */
        Button                   noiseCheck;
        /** Hidden on row 0, which is never removed; its cell stays reserved. */
        Button                   removeBtn;
        StereoFreqRespCalibration calibration;
        /** The path {@link #calibration} was read from; {@code null} while
         *  the row holds none. */
        String                   loadedPath;
        /** Source of truth for path + Active + With-noise: an item of the
         *  calibration list. */
        CalibrationEntry         entry;

        private CalRow() {
        }
    }

    /** The store the pane's view divides by - written from
     *  {@link #syncStoreFromRows} only, and shared with that view. */
    private final CorrectionStore correctionStore;
    /** The calibration items the rows show and follow. */
    private final CalibrationList calibrations;
    /** The folder a row's load dialog opens in, remembered after a load. */
    private final Property<String> loadFolder;
    /** Message-key prefix of the calibration tab's texts. */
    private final String keyPrefix;
    /** Whether each row shows the With-noise toggle and the store honours
     *  it. */
    private final boolean withNoiseColumn;

    /** Container Composite for the dynamic calibration-row list. */
    private Composite calRowsContainer;
    /** Scrolled wrapper around {@link #calRowsContainer} so a long list
     *  of rows scrolls vertically instead of overflowing the tab. */
    private ScrolledComposite calRowsScroll;
    /** One row per list item, at the item's index. */
    private final List<CalRow> calRows = new ArrayList<>();

    protected AbstractCalibrationTabControl(Composite parent, int style, P owner,
                                            CorrectionStore correctionStore,
                                            CalibrationList calibrations,
                                            Property<String> loadFolder,
                                            String keyPrefix, boolean withNoiseColumn) {
        super(parent, style, owner);
        this.correctionStore = correctionStore;
        this.calibrations    = calibrations;
        this.loadFolder      = loadFolder;
        this.keyPrefix       = keyPrefix;
        this.withNoiseColumn = withNoiseColumn;
    }

    /** Called after every re-sync of the store from the rows.  The store is
     *  written from here only and publishes nothing, so the subclass
     *  re-derives what divides by it (the view's curves) and refreshes what
     *  shows it (the calibration tile). */
    protected abstract void onCalibrationsSynced();

    /** The calibration tab's header tile: how many calibrations the store
     *  holds, {@code null} while it holds none. */
    protected TileTabFolder.Tile getCalibrationTile() {
        int n = correctionStore.getEntries().size();
        if (n == 1) {
            return TileTabFolder.Tile.text(I18n.t("calibration.tile.loaded"),
                    I18n.t("calibration.tile.loaded.tooltip"));
        }
        if (n > 1) {
            return TileTabFolder.Tile.text(I18n.t("calibration.tile.loadedN", n),
                    I18n.t("calibration.tile.loadedN.tooltip", n));
        }
        return null;
    }

    /** Builds the calibration tab into {@link #toolbarTabs} - the subclass
     *  calls it in its own tab order.  The rows show the list as it is; the
     *  store follows once {@link #followCalibrations} runs. */
    protected void buildCalibrationTab() {
        // ScrolledComposite wraps the rows so the tab can grow vertically
        // beyond the available height - a long list of calibrations
        // scrolls instead of overflowing.  Created (and set as the item's
        // control) before the item itself, same ordering rule as groupCell -
        // a stray folder child corrupts the collapsed strip height.
        calRowsScroll = new ScrolledComposite(toolbarTabs, SWT.V_SCROLL);
        calRowsScroll.setExpandHorizontal(true);
        calRowsScroll.setExpandVertical(true);
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(text("tab.calibration"));
        item.setControl(calRowsScroll);

        calRowsContainer = new Composite(calRowsScroll, SWT.NONE);
        calRowsScroll.setContent(calRowsContainer);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.verticalSpacing = 4;
        calRowsContainer.setLayout(gl);

        // Row 0 is always present - added before the list is followed.
        if (calibrations.isEmpty()) {
            calibrations.add(new CalibrationEntry());
        }
        for (int i = 0; i < calibrations.size(); i++) {
            insertRow(i, calibrations.get(i));
        }
    }

    /** Starts following the calibration list and pushes the initially loaded
     *  active rows into the store - the subclass calls it at the end of its
     *  construction, once every tab exists. */
    protected void followCalibrations() {
        Consumer<CalibrationList.Change> listListener = this::onCalibrationsChanged;
        calibrations.addListener(listListener);
        addDisposeListener(e -> calibrations.removeListener(listListener));
        syncStoreFromRows();
    }

    /** The calibration list's listener: applies the change to the row it
     *  names, in the same call, then re-syncs the store.  No row is disposed
     *  but a removed or replaced one, so a row widget's own event - an Active
     *  tick, Load, Clear, Add - may cause the change; Remove disposes only its
     *  own row. */
    private void onCalibrationsChanged(CalibrationList.Change change) {
        if (isDisposed()) return;
        int i = change.index();
        switch (change.kind()) {
            case ADDED -> insertRow(i, change.entry());
            case REMOVED -> removeRow(i);
            case REPLACED -> {
                removeRow(i);
                insertRow(i, change.entry());
            }
            case CLEARED -> {
                for (CalRow r : calRows) r.composite.dispose();
                calRows.clear();
                // Row 0 is always present; its add arrives as a change of its own.
                calibrations.add(new CalibrationEntry());
            }
            case PATH -> showFile(calRows.get(i), false);
            case FILE_SAVED -> showFile(calRows.get(i), true);
            case ACTIVE, WITH_NOISE -> updateCalRowEnable(calRows.get(i));
        }
        syncStoreFromRows();
    }

    /** Creates the row of {@code entry} at index {@code i} and loads the
     *  file it references. */
    private void insertRow(int i, CalibrationEntry entry) {
        CalRow r = createRowUi(entry);
        if (i < calRows.size()) {
            r.composite.moveAbove(calRows.get(i).composite);
        }
        calRows.add(i, r);
        showFile(r, true);
        relayoutCalRows();
    }

    private void removeRow(int i) {
        calRows.remove(i).composite.dispose();
        relayoutCalRows();
    }

    /** Builds a fresh, empty row (no path, no calibration) at the end of the
     *  calibration tab.  Every row has 6 grid cells (path, active, load,
     *  clear, add, remove), 7 with the With-noise column after Active - row
     *  0's remove button is invisible so its grid cell stays reserved,
     *  keeping the load/clear/add columns vertically aligned across all
     *  rows. */
    private CalRow createRowUi(CalibrationEntry entry) {
        Composite row = new Composite(calRowsContainer, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout rl = new GridLayout(withNoiseColumn ? 7 : 6, false);
        rl.marginWidth = 0; rl.marginHeight = 0;
        rl.horizontalSpacing = 6;
        row.setLayout(rl);

        Text pathField = new Text(row, SWT.BORDER | SWT.READ_ONLY);
        GridData pgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pgd.widthHint = 320;
        pathField.setLayoutData(pgd);
        pathField.setText(text("calibration.path.none"));
        pathField.setToolTipText(text("calibration.path.tooltip"));

        Button activeCheck = new Button(row, SWT.CHECK);
        activeCheck.setText(text("calibration.active"));
        activeCheck.setToolTipText(text("calibration.active.tooltip"));

        Button noiseCheck = null;
        if (withNoiseColumn) {
            noiseCheck = new Button(row, SWT.CHECK);
            noiseCheck.setText(text("calibration.withNoise"));
            noiseCheck.setToolTipText(text("calibration.withNoise.tooltip"));
        }

        Image folderIcon = IconUtils.icon(row.getDisplay(), Icon.FOLDER_OPEN);
        Button loadBtn = new Button(row, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        GridData loadGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        loadGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        loadBtn.setLayoutData(loadGd);
        loadBtn.setToolTipText(text("calibration.load.tooltip"));

        Image xmark = IconUtils.icon(row.getDisplay(), Icon.RECTANGLE_XMARK);
        Button clearBtn = new Button(row, SWT.PUSH);
        if (xmark != null) clearBtn.setImage(xmark);
        GridData clearGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        clearGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        clearBtn.setLayoutData(clearGd);
        clearBtn.setToolTipText(text("calibration.clear.tooltip"));

        Image plus = IconUtils.icon(row.getDisplay(), Icon.PLUS);
        Button addBtn = new Button(row, SWT.PUSH);
        if (plus != null) addBtn.setImage(plus);
        GridData addGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        addGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        addBtn.setLayoutData(addGd);
        addBtn.setToolTipText(text("calibration.add.tooltip"));

        Image minus = IconUtils.icon(row.getDisplay(), Icon.MINUS);
        Button removeBtn = new Button(row, SWT.PUSH);
        if (minus != null) removeBtn.setImage(minus);
        GridData removeGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        removeGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        removeBtn.setLayoutData(removeGd);
        removeBtn.setToolTipText(text("calibration.remove.tooltip"));

        CalRow r = new CalRow();
        r.composite   = row;
        r.pathField   = pathField;
        r.activeCheck = activeCheck;
        r.noiseCheck  = noiseCheck;
        r.removeBtn   = removeBtn;
        r.entry       = entry;
        updateCalRowEnable(r);

        // A toggle reaches the store through the calibration list's change.
        Bindings.check(activeCheck, entry.active());
        if (noiseCheck != null) Bindings.check(noiseCheck, entry.withNoise());

        loadBtn.addListener(SWT.Selection, e -> userLoadInRow(r));
        clearBtn.addListener(SWT.Selection, e -> userClearRow(r));
        addBtn.addListener(SWT.Selection, e -> userAddRow());
        removeBtn.addListener(SWT.Selection, e -> userRemoveRow(r));
        return r;
    }

    /** Shows the file {@code r}'s entry references: none for an empty path;
     *  the calibration already held when the row loaded that same path,
     *  unless {@code reread}; else a fresh read - silent, so a missing file
     *  leaves the row unloaded instead of blocking the pane. */
    private void showFile(CalRow r, boolean reread) {
        String path = r.entry.getPath();
        if (path == null || path.isEmpty()) {
            showLoaded(r, null, null);
            return;
        }
        if (!reread && path.equals(r.loadedPath)) return;
        showLoaded(r, path, readFile(path, false));
    }

    /** Puts a read calibration into the row - path field, held calibration,
     *  checkbox state; a {@code null} calibration shows the row unloaded. */
    private void showLoaded(CalRow r, String path, StereoFreqRespCalibration cal) {
        r.calibration = cal;
        r.loadedPath  = cal != null ? path : null;
        if (cal != null) {
            r.pathField.setText(path);
            r.pathField.setToolTipText(path);
        } else {
            r.pathField.setText(text("calibration.path.none"));
            r.pathField.setToolTipText(text("calibration.path.tooltip"));
        }
        updateCalRowEnable(r);
    }

    /** Keeps the row's checkboxes' enabled state in sync with the row - Active
     *  needs a loaded file, With-noise a loaded file AND an active row. */
    private void updateCalRowEnable(CalRow r) {
        if (r.activeCheck == null || r.activeCheck.isDisposed()) return;
        boolean fileLoaded = r.entry.getPath() != null && r.calibration != null;
        r.activeCheck.setEnabled(fileLoaded);
        if (r.noiseCheck != null && !r.noiseCheck.isDisposed()) {
            r.noiseCheck.setEnabled(fileLoaded && r.entry.active().get());
        }
    }

    /** Lays the rows out after a row came or went: every row but row 0 shows
     *  its Remove button, the container is laid out again and the surrounding
     *  ScrolledComposite recomputes its scroll extent. */
    private void relayoutCalRows() {
        for (int i = 0; i < calRows.size(); i++) {
            calRows.get(i).removeBtn.setVisible(i > 0);
        }
        if (calRowsContainer != null && !calRowsContainer.isDisposed()) {
            calRowsContainer.layout(true, true);
        }
        if (calRowsScroll != null && !calRowsScroll.isDisposed() && calRowsContainer != null) {
            calRowsScroll.setMinSize(calRowsContainer.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    private void userLoadInRow(CalRow r) {
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
        fd.setText(text("calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = loadFolder.get();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null || r.composite.isDisposed()) return;
        // A file that does not load is reported and never recorded.
        StereoFreqRespCalibration cal = readFile(picked, true);
        if (cal == null) return;
        loadFolder.set(new File(picked).getParent());
        // The row takes the calibration just read, so the path change finds
        // the path loaded and reads nothing again.  Re-picking the row's own
        // file changes nothing in the list, yet the file on disk may be new:
        // the store is re-synced here.
        boolean samePath = picked.equals(r.entry.getPath());
        showLoaded(r, picked, cal);
        if (samePath) {
            syncStoreFromRows();
        } else {
            r.entry.setPath(picked);
        }
    }

    private void userClearRow(CalRow r) {
        // Clearing the file disables Active in the UI but we keep the
        // entry's Active flag so re-loading a file re-engages the row
        // without the user having to re-tick the box.
        r.entry.setPath(null);
    }

    private void userAddRow() {
        calibrations.add(new CalibrationEntry());
    }

    private void userRemoveRow(CalRow r) {
        int idx = calRows.indexOf(r);
        if (idx <= 0) return; // never remove row 0
        calibrations.remove(idx);
    }

    /** Reads a calibration file from disk; {@code null} when it does not
     *  load.  When {@code showErrors} is true, a failure pops a modal dialog;
     *  otherwise it is logged only (a row following its entry, so a missing
     *  file doesn't block the whole pane). */
    private StereoFreqRespCalibration readFile(String path, boolean showErrors) {
        try {
            return FreqRespCalHelper.loadFrc(path);
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("Calibration load failed: {}", path, ex);
            }
            if (showErrors) {
                Dialogs.error(getShell(),
                        text("calibration.dialog"),
                        text("error.calibration.load").replace("{0}",
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            }
            return null;
        }
    }

    /** Pushes the current row state into the {@link CorrectionStore}.
     *  Unloaded and inactive rows are skipped so the store holds only
     *  entries the view divides by; then {@link #onCalibrationsSynced}. */
    private void syncStoreFromRows() {
        correctionStore.clearAll();
        for (CalRow r : calRows) {
            // Only push rows the user has explicitly activated; an
            // unticked row is a "loaded but parked" calibration the
            // user can re-engage with one click without re-browsing.
            if (r.calibration != null && r.entry.getPath() != null && r.entry.active().get()) {
                correctionStore.addEntry(r.calibration, r.entry.getPath(),
                        withNoiseColumn && r.entry.withNoise().get());
            }
        }
        onCalibrationsSynced();
    }

    /** The text of {@code key} under this control's message-key prefix. */
    private String text(String key) {
        return I18n.t(keyPrefix + "." + key);
    }
}
