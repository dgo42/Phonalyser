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

package org.edgo.audio.measure.gui.widgets;

import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.i18n.I18n;

/**
 * Reusable named-preset bar: an editable combo plus Save / Load / Delete,
 * shared verbatim by the FFT, oscilloscope and frequency-response toolbars.
 *
 * <p>Each pane differs only in (a) a message-key {@code prefix}, (b) the backing
 * preset map, and (c) how it captures / applies the current settings — all
 * supplied through {@link Store}.  The bar itself owns the widget wiring, the
 * Save-button enablement logic, the overwrite / delete confirmations and a
 * low-frequency poll that keeps Save in sync when settings change elsewhere.
 *
 * <p>Save enables only for a new name or when the current settings differ from
 * the stored preset; Load / Delete enable only for an existing name.
 *
 * @param <P> the pane's preset record (must implement {@code equals} so Save
 *            can detect "no change")
 */
public final class PresetBar<P> extends Composite {

    /** Pane-specific backing: the named-preset map plus capture / apply of the
     *  current settings. */
    public interface Store<P> {
        /** The live name → preset map (read for listing, lookup, containment). */
        Map<String, P> presets();
        /** Saves {@code preset} under {@code name}. */
        void put(String name, P preset);
        /** Removes the preset named {@code name}. */
        void remove(String name);
        /** Captures the pane's current settings as a preset. */
        P captureCurrent();
        /** Applies {@code preset} to the pane. */
        void apply(P preset);
        /** Called after a Save or Delete mutated the map — e.g. to refresh a
         *  tab tile showing the saved-preset count.  Default no-op. */
        default void onChanged() { }
    }

    private static final int POLL_MS     = 500;
    private static final int COMBO_WIDTH = 180;

    private final String   prefix;
    private final Store<P> store;
    private final Combo    combo;
    private final Button   saveBtn;
    private final Button   loadBtn;
    private final Button   deleteBtn;

    /**
     * @param parent     container — the bar lays itself out in a 4-column grid
     * @param i18nPrefix message-key prefix, e.g. {@code "fft.presets"}; the bar
     *                   reads {@code <prefix>.combo.tooltip}, {@code .save},
     *                   {@code .save.tooltip}, {@code .load}, {@code .load.tooltip},
     *                   {@code .delete}, {@code .delete.tooltip},
     *                   {@code .overwrite.title}/{@code .message} and
     *                   {@code .delete.title}/{@code .message}
     * @param helpAnchor Ctrl+F1 anchor set on every widget (may be {@code null})
     * @param store      pane-specific preset map + capture / apply
     */
    public PresetBar(Composite parent, String i18nPrefix, String helpAnchor, Store<P> store) {
        super(parent, SWT.NONE);
        this.prefix = i18nPrefix;
        this.store  = store;
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        setLayout(gl);

        combo = new Combo(this, SWT.DROP_DOWN);   // editable so a new name can be typed
        combo.setToolTipText(I18n.t(prefix + ".combo.tooltip"));
        GridData comboGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboGd.widthHint = COMBO_WIDTH;
        combo.setLayoutData(comboGd);
        for (String name : store.presets().keySet()) combo.add(name);
        combo.addListener(SWT.Modify,    e -> refreshButtons());
        combo.addListener(SWT.Selection, e -> refreshButtons());

        saveBtn   = button(prefix + ".save",   this::onSave);
        loadBtn   = button(prefix + ".load",   this::onLoad);
        deleteBtn = button(prefix + ".delete", this::onDelete);

        if (helpAnchor != null) {
            combo    .setData("helpAnchor", helpAnchor);
            saveBtn  .setData("helpAnchor", helpAnchor);
            loadBtn  .setData("helpAnchor", helpAnchor);
            deleteBtn.setData("helpAnchor", helpAnchor);
        }

        refreshButtons();
        // Low-frequency poll so Save reacts to setting changes made on the
        // canvas / other tabs while this tab is visible.  timerExec is a no-op
        // once the widget is disposed, so the loop stops itself.
        Display display = getDisplay();
        Runnable[] tick = { null };
        tick[0] = () -> {
            if (isDisposed()) return;
            refreshButtons();
            display.timerExec(POLL_MS, tick[0]);
        };
        display.timerExec(POLL_MS, tick[0]);
    }

    private Button button(String key, Runnable action) {
        Button b = new Button(this, SWT.PUSH);
        b.setText(I18n.t(key));
        b.setToolTipText(I18n.t(key + ".tooltip"));
        b.addListener(SWT.Selection, e -> action.run());
        return b;
    }

    private void onSave() {
        String name = combo.getText().trim();
        if (name.isEmpty()) return;
        if (store.presets().containsKey(name) && !confirm("overwrite", name)) return;
        store.put(name, store.captureCurrent());
        if (combo.indexOf(name) < 0) combo.add(name);
        combo.setText(name);
        refreshButtons();
        store.onChanged();
    }

    private void onLoad() {
        String name = combo.getText().trim();
        if (name.isEmpty()) return;
        P p = store.presets().get(name);
        if (p != null) {
            store.apply(p);
            refreshButtons();
        }
    }

    private void onDelete() {
        String name = combo.getText().trim();
        if (name.isEmpty() || !store.presets().containsKey(name)) return;
        if (!confirm("delete", name)) return;
        store.remove(name);
        int idx = combo.indexOf(name);
        if (idx >= 0) combo.remove(idx);
        combo.setText("");
        refreshButtons();
        store.onChanged();
    }

    private boolean confirm(String which, String name) {
        return Dialogs.confirm(getShell(),
                I18n.t(prefix + "." + which + ".title"),
                I18n.t(prefix + "." + which + ".message", name)) == SWT.YES;
    }

    private void refreshButtons() {
        if (isDisposed()) return;
        String name = combo.getText().trim();
        if (name.isEmpty()) {
            saveBtn.setEnabled(false);
            loadBtn.setEnabled(false);
            deleteBtn.setEnabled(false);
            return;
        }
        P existing = store.presets().get(name);
        if (existing == null) {
            saveBtn.setEnabled(true);     // new name — Save creates it
            loadBtn.setEnabled(false);
            deleteBtn.setEnabled(false);
        } else {
            // Existing — Save only if the current settings differ; Load / Delete always.
            saveBtn.setEnabled(!existing.equals(store.captureCurrent()));
            loadBtn.setEnabled(true);
            deleteBtn.setEnabled(true);
        }
    }
}
