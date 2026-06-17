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
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.automation.AbstractAutomationScript;

/**
 * Full help-screenshot run for the localized bundles: drives the live app and
 * recreates EVERY documentation image — the three measurement panes, the
 * generator's dual-tone / sweep variants, the frequency-response pane, and all
 * settings-tab panels (oscilloscope, FFT, frequency response) plus the
 * Preferences dialog tabs — once per UI language.  Each image is written
 * straight into {@code src/main/resources/help/<lang>/img/} with the same file
 * name the English bundle uses, so a localized bundle ends up with screenshots
 * whose chrome matches its translated text.
 *
 * <p>Run from a working directory holding a prepared {@code preferences.yaml}
 * (devices, FFT length), two levels under the repo root (e.g.
 * {@code target/agent-run}), with {@code -Dswt.autoScale=100}.  The images path
 * is resolved relative to that directory ({@code ../../src/main/resources/…}),
 * matching the {@code HelpCapture.body} convention.
 *
 * <p>English is intentionally NOT recaptured here (its images are hand-tuned);
 * {@link #LANGUAGES} lists only the translated bundles.
 */
public final class HelpImagesFull extends AbstractAutomationScript {

    /** Bundles to (re)capture — English is left as-is. */
    private static final String[] LANGUAGES = { "de", "uk" };

    /** Help image folder, relative to the launch dir (two levels under root). */
    private static final String HELP = "../../src/main/resources/help/";

    private static final double SETTLE_SECONDS = 25;
    private static final double SETTLE_TAB      = 0.4;
    private static final double SETTLE_REBUILD  = 1.2;

    // Window size (height tall enough to avoid a vertical scrollbar, which would
    // steal 16 px of width) and the 330 px generator pane come from the launch
    // preferences.yaml — forcing them at runtime (sizeMainWindow) re-lays the
    // multifunctional SashForm badly, so the bundle is captured at launch size.

    private static final int PANE_W = 1040;
    private static final int PANE_H = 660;

    /** {tab slug, output file name (no extension)} for each module's tabs. */
    private static final String[][] SCOPE_TABS = {
            { "left",       "Oscilloscope - Left" },
            { "right",      "Oscilloscope - Right" },
            { "horizontal", "Oscilloscope - Horizontal" },
            { "trigger",    "Oscilloscope - Trigger" },
            { "presets",    "Oscilloscope - Presets" },
            { "utility",    "Oscilloscope - Utility" },
            { "save",       "Oscilloscope - Save to" },
            { "load",       "Oscilloscope - Load signal" } };
    private static final String[][] FFT_TABS = {
            { "settings",    "FFT - FFT settings" },
            { "thd",         "FFT - THD settings" },
            { "presets",     "FFT - Presets" },
            { "utility",     "FFT - Utility" },
            { "calibration", "FFT - Load calibration" },
            { "save",        "FFT - Save to" },
            { "load",        "FFT - Load from" } };
    private static final String[][] FR_TABS = {
            { "settings",    "FreqResp - Settings" },
            { "riaa",        "FreqResp - RIAA IEC" },
            { "presets",     "FreqResp - Presets" },
            { "utility",     "FreqResp - Utility" },
            { "calibration", "FreqResp - Load calibration" },
            { "save",        "FreqResp - Save to" },
            { "load",        "FreqResp - Load from" } };
    private static final String[][] PREF_TABS = {
            { "lookfeel",     "Preferences Look and Feel" },
            { "audio",        "Preferences Audio" },
            { "oscilloscope", "Preferences Oscilloscope" },
            { "fft",          "Preferences FFT" },
            { "freqresp",     "Preferences Frequency response" } };

    public HelpImagesFull(Display display, MainWindow window) {
        super(display, window);
    }

    @Override
    protected void run() throws Exception {
        startGenerator();
        startScope();
        startFft();
        waitSeconds(SETTLE_SECONDS);
        for (String lang : LANGUAGES) {
            captureLanguage(lang, HELP + lang + "/img/");
        }
    }

    private void captureLanguage(String lang, String dir) throws Exception {
        // --- multifunctional tab: panes (generator in plain SINE) ---
        setGeneratorForm("SINE");
        language(lang);
        activate("multifunctional");          // bring its top tab to front (else background = width 0)
        collapseScopeAndFftTabs();
        waitSeconds(SETTLE_REBUILD);
        captureMultifunctional(dir + "multifunctional.png");
        captureGeneratorCropped(dir + "generator-pane.png");
        snapshotScopePane(PANE_W, PANE_H, dir + "oscilloscope-pane.png");
        snapshotFftPane(PANE_W, PANE_H, dir + "fft-pane.png");

        // --- oscilloscope + FFT settings tabs ---
        captureTabs("multifunctional/scope/tabs", SCOPE_TABS, dir);
        captureTabs("multifunctional/fft/tabs", FFT_TABS, dir);

        // --- generator variants (set form, rebuild, crop).  The rebuild resets
        //     the top tab to the prefs default, so re-front the multifunctional
        //     tab each time or the generator pane captures at width 0. ---
        setGeneratorForm("DUAL_TONE");
        language(lang);
        activate("multifunctional");
        waitSeconds(SETTLE_REBUILD);
        captureGeneratorCropped(dir + "Generator - dual tone.png");
        setGeneratorForm("LOG_SWEEP");
        language(lang);
        activate("multifunctional");
        waitSeconds(SETTLE_REBUILD);
        captureGeneratorCropped(dir + "Generator - sweep mode.png");

        // --- frequency-response pane + its settings tabs (separate top tab) ---
        activate("frequencyResponse");
        activate("frequencyResponse/tabs/settings");
        waitSeconds(SETTLE_REBUILD);
        screenshot("frequencyResponse", dir + "freqresp-pane.png");
        captureTabs("frequencyResponse/tabs", FR_TABS, dir);

        // --- Preferences dialog tabs (modal): each per-tab path carries only an
        //     onActivate, so the screenshottable control is the tab folder at
        //     "preferences" — activate the tab, then snapshot that base path. ---
        openPreferences();
        waitSeconds(SETTLE_REBUILD);
        for (String[] tab : PREF_TABS) {
            activate("preferences/tabs/" + tab[0]);
            waitSeconds(SETTLE_TAB);
            screenshot("preferences", dir + tab[1] + ".png");
        }
        closePreferences();
    }

    /** Activates each tab by registry path and snapshots its panel to
     *  {@code dir + name + ".png"}. */
    private void captureTabs(String prefix, String[][] tabs, String dir) throws Exception {
        for (String[] tab : tabs) {
            String path = prefix + "/" + tab[0];
            activate(path);
            waitSeconds(SETTLE_TAB);
            screenshot(path, dir + tab[1] + ".png");
        }
    }
}
