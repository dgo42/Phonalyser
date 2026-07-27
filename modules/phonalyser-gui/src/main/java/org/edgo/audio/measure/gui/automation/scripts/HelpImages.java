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

package org.edgo.audio.measure.gui.automation.scripts;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.GuiMain;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.automation.AbstractAutomationScript;

/**
 * Documentation-screenshot run: starts the generator, the oscilloscope
 * and the FFT, lets the measurement settle, then captures the three panes
 * once per UI language — all from ONE measurement, so the plotted curves
 * are pixel-identical across languages (the engines survive the
 * language-switch rebuild).
 *
 * <p>Launch {@link GuiMain} from a working directory holding the prepared
 * {@code preferences.yaml}, with {@code -Dswt.autoScale=100} and
 * {@code --automation=} naming this class.
 */
public final class HelpImages extends AbstractAutomationScript {

    /** Root folder for the produced images, one subfolder per language —
     *  under Maven's build dir, so the output is git-ignored and swept by
     *  {@code mvn clean}.  Resolved against the launch working directory
     *  (the workspace root when started from the launch.json config). */
    private static final String OUT_DIR = "target/help-images";
    /** Languages to capture — extend as bundles get translated help. */
    private static final String[] LANGUAGES = { "en", "de" };
    /** Settling time before the first snapshot: enough FFT averages for a
     *  smooth noise floor at the configured FFT length. */
    private static final double SETTLE_SECONDS = 30;
    /** Pane snapshot size (px) — matches the help pages' image width. */
    private static final int PANE_WIDTH_PX  = 900;
    private static final int PANE_HEIGHT_PX = 620;

    public HelpImages(Display display, MainWindow window) {
        super(display, window);
    }

    @Override
    protected void run() throws Exception {
        startGenerator();
        startScope();
        startFft();
        waitSeconds(SETTLE_SECONDS);
        for (String lang : LANGUAGES) {
            language(lang);
            String dir = OUT_DIR + "/" + lang + "/";
            snapshot(genPane().getGroup(), dir + "generator.png");
            snapshotScopePane(PANE_WIDTH_PX, PANE_HEIGHT_PX, dir + "scope.png");
            snapshotFftPane(PANE_WIDTH_PX, PANE_HEIGHT_PX, dir + "fft.png");
        }
    }
}
