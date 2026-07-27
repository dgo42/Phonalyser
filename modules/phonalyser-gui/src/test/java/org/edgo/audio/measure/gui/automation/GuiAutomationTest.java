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

package org.edgo.audio.measure.gui.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end exercise of the GUI-automation machinery against a CUSTOM
 * configuration: a prepared {@code preferences.yaml} in a temp working
 * directory, a real {@link MainWindow} with the SWT event loop pumped by
 * the test thread, the reflective {@link AutomationRunner} launch path,
 * engine starts, an in-place language switch and all three snapshot
 * helpers of {@link AbstractAutomationScript}.
 *
 * <p>No audio hardware is required: without devices the engine starts
 * degrade to a logged warning and the panes render without data — the
 * automation machinery under test is exercised either way.  Skipped (via
 * assumption) where no SWT display can be created (headless CI).
 *
 * <p>Side effects are contained: the Preferences singleton is switched to
 * transient mode (no YAML write-back) and re-loaded from the temp dir;
 * the UI locale is restored after the run.  Note the singleton keeps the
 * test's loaded values for the remainder of the JVM — no current test
 * depends on them.
 */
@Tag("exploratory")   // slow diagnostic harness — excluded from the normal build (see pom surefire)
class GuiAutomationTest {

    private static final String[] LANGUAGES = { "en", "de" };
    private static final String[] PANE_IMAGES = { "generator.png", "scope.png", "fft.png" };
    /** Generous ceiling for the whole scripted run (two language rebuilds
     *  plus six offscreen pane renders). */
    private static final long   RUN_TIMEOUT_MS = 120_000;
    private static final long   POLL_MS        = 10;
    private static final byte[] PNG_SIGNATURE  = { (byte) 0x89, 'P', 'N', 'G' };
    /** A blank 640×480 pane still compresses to a few KB — anything below
     *  this is a truncated / failed write. */
    private static final int    MIN_PNG_BYTES  = 500;
    private static final int    SNAPSHOT_WIDTH_PX  = 640;
    private static final int    SNAPSHOT_HEIGHT_PX = 480;
    /** Marker value proving the custom YAML (not the defaults) is live. */
    private static final double CUSTOM_GEN_FREQ_HZ = 1234.5;

    /** Snapshot output root, handed to the script class — the runner
     *  instantiates it reflectively, so a static field is the channel. */
    static volatile Path outDir;

    @Test
    void scriptDrivesWindowAndCapturesSnapshotsPerLanguage(@TempDir Path workDir) throws Exception {
        // --- Custom configuration: a prepared preferences.yaml in its own
        // working directory, exactly like a real doc-generation run.
        Files.writeString(workDir.resolve("preferences.yaml"), """
                backend: JAVASOUND
                uiLanguage: en
                genFrequencyHz: 1234.5
                windowWidth: 1280
                windowHeight: 800
                """);
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", workDir.toString());
        Display display = null;
        try {
            Preferences prefs = Preferences.instance();
            prefs.setTransientMode(true);   // never write the test YAML back
            prefs.load();                   // re-read from the temp working dir
            assertEquals(CUSTOM_GEN_FREQ_HZ, prefs.getGenFrequencyHz(),
                    "custom preferences.yaml should be live");

            // Mirror GuiMain's launch sequence: locale + active backend
            // from the (custom) prefs, then window + automation runner.
            I18n.setLocale(Locale.forLanguageTag(prefs.getUiLanguage()));
            AudioBackend.instance().setActive(prefs.getBackend());

            try {
                display = new Display();
            } catch (SWTError | UnsatisfiedLinkError e) {
                Assumptions.assumeTrue(false, "SWT display unavailable: " + e.getMessage());
            }
            MainWindow window = new MainWindow(display);
            window.open();

            outDir = workDir.resolve("snapshots");
            new AutomationRunner(display, window, CapturePanesScript.class.getName()).start();

            // Pump the SWT event loop (the test thread IS the UI thread)
            // until the runner closes the window — or the timeout trips.
            long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
            while (!window.isDisposed() && System.currentTimeMillis() < deadline) {
                if (!display.readAndDispatch()) {
                    Thread.sleep(POLL_MS);
                }
            }

            assertTrue(window.isDisposed(),
                    "runner should close the window when the script finishes");
            assertTrue(CapturePanesScript.completed,
                    "script should run to completion (see log for the failure)");
            assertEquals("de", I18n.getLocale().getLanguage(),
                    "the in-place language switch should have reached the last language");
            for (String lang : LANGUAGES) {
                for (String image : PANE_IMAGES) {
                    assertPng(outDir.resolve(lang).resolve(image));
                }
            }
        } finally {
            if (display != null && !display.isDisposed()) display.dispose();
            System.setProperty("user.dir", originalUserDir);
            I18n.setLocale(Locale.ENGLISH);
        }
    }

    /** Asserts {@code file} exists, is plausibly sized and carries the PNG
     *  magic bytes. */
    private void assertPng(Path file) throws Exception {
        assertTrue(Files.exists(file), "missing snapshot: " + file);
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length >= MIN_PNG_BYTES,
                file + " is suspiciously small: " + bytes.length + " bytes");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            assertEquals(PNG_SIGNATURE[i], bytes[i], file + " is not a PNG");
        }
    }

    /**
     * The script under test — launched reflectively by
     * {@link AutomationRunner} exactly like a production
     * {@code --automation=} run: start all three engines (tolerating
     * absent audio hardware), settle briefly, then snapshot every pane in
     * every language through an in-place language switch.
     */
    public static final class CapturePanesScript extends AbstractAutomationScript {

        static volatile boolean completed;

        public CapturePanesScript(Display display, MainWindow window) {
            super(display, window);
        }

        @Override
        protected void run() throws Exception {
            startGenerator();
            startScope();
            startFft();
            waitSeconds(0.5);
            for (String lang : LANGUAGES) {
                language(lang);
                Path dir = outDir.resolve(lang);
                snapshot(genPane().getGroup(), dir.resolve("generator.png").toString());
                snapshotScopePane(SNAPSHOT_WIDTH_PX, SNAPSHOT_HEIGHT_PX,
                        dir.resolve("scope.png").toString());
                snapshotFftPane(SNAPSHOT_WIDTH_PX, SNAPSHOT_HEIGHT_PX,
                        dir.resolve("fft.png").toString());
            }
            completed = true;
        }
    }
}
