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

package org.edgo.audio.measure.preferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend selection as it is persisted and as the Preferences dialog stages
 * it: a STRING key ({@code WASAPI}, {@code net:<serverId>:<QA40X>}) both for the
 * chosen backend and for every {@code perBackend} block, so one backend on two
 * different benches keeps two sets of devices, rates and sample widths.
 *
 * <p>The legacy case is the one that must never break: a preferences.yaml
 * written before remote benches existed holds bare enum names, and loading it
 * has to leave the user on exactly the backend and settings they had.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes); the
 * selection and the blocks it touches are restored afterwards so other tests in
 * the same JVM see the state they started with.
 */
class BackendSelectionPrefsTest {

    private static final String SERVER_A   = "b7e0-bench-a";
    private static final String SERVER_B   = "b7e0-bench-b";
    private static final String REMOTE_A   = "net:" + SERVER_A + ":QA40X";
    private static final String LEGACY_KEY = "WDMKS";
    private static final String MIC_A      = "Bench A analyzer in";
    private static final String MIC_B      = "Bench B analyzer in";
    private static final String LEGACY_MIC = "Line In (Legacy card)";
    private static final int RATE_96K      = 96_000;
    private static final int RATE_192K     = 192_000;

    private BackendKey savedSelection;
    /** The per-backend blocks the singleton held before this test ran; anything
     *  this test adds to it is dropped again afterwards. */
    private Set<?> savedBlocks;

    @BeforeEach
    void snapshotSelection() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        savedSelection = prefs.getSelectedBackend();
        savedBlocks = new LinkedHashSet<>(perBackend(prefs).keySet());
    }

    @AfterEach
    void restoreSelection() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setSelectedBackend(savedSelection);
        // The live-selection test really does write bench blocks into the
        // singleton - that is what it is proving.  Leaving them there would hand
        // every later test class in this JVM two invented benches, so they go.
        perBackend(prefs).keySet().retainAll(savedBlocks);
    }

    @Test
    void stringKeysRoundTripThroughTheYamlMap() throws Exception {
        Preferences prefs = Preferences.instance().copyForDialog();
        prefs.setSelectedBackend(BackendKey.of(SERVER_A, AudioBackendType.QA40X));
        prefs.prefsFor(BackendKey.of(SERVER_A, AudioBackendType.QA40X))
                .setInputDeviceName(MIC_A);
        prefs.prefsFor(BackendKey.of(SERVER_B, AudioBackendType.QA40X))
                .setInputDeviceName(MIC_B);

        Map<?, ?> written = invokeToMap(prefs);
        assertEquals(REMOTE_A, written.get("backend"),
                "the chosen backend is stored as its key, server and all");
        Map<?, ?> perBackend = (Map<?, ?>) written.get("perBackend");
        assertTrue(perBackend.containsKey(REMOTE_A), "one block per bench");
        assertTrue(perBackend.containsKey("net:" + SERVER_B + ":QA40X"));

        // Read it back into a fresh copy: both benches come back, each with its own.
        Preferences reloaded = Preferences.instance().copyForDialog();
        invokeFromMap(reloaded, written);
        assertEquals(BackendKey.of(SERVER_A, AudioBackendType.QA40X),
                reloaded.getSelectedBackend());
        assertEquals(MIC_A, reloaded.current().getInputDeviceName());
        assertEquals(MIC_B, reloaded.prefsFor(BackendKey.of(SERVER_B, AudioBackendType.QA40X))
                .getInputDeviceName());
        assertNotSame(reloaded.current(),
                reloaded.prefsFor(BackendKey.of(SERVER_B, AudioBackendType.QA40X)),
                "the same backend on two benches is two blocks, not one");
    }

    @Test
    void aLegacyFileLoadsUnchanged() throws Exception {
        // Exactly what a pre-net-bridge preferences.yaml holds: bare enum names.
        Map<String, Object> legacyBlock = new LinkedHashMap<>();
        legacyBlock.put("inputDeviceName", LEGACY_MIC);
        legacyBlock.put("inputSampleRate", RATE_96K);
        Map<String, Object> legacyPerBackend = new LinkedHashMap<>();
        legacyPerBackend.put(LEGACY_KEY, legacyBlock);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("backend", LEGACY_KEY);
        root.put("perBackend", legacyPerBackend);

        Preferences prefs = Preferences.instance().copyForDialog();
        invokeFromMap(prefs, root);

        assertEquals(BackendKey.of(AudioBackendType.WDMKS), prefs.getSelectedBackend(),
                "a bare enum name is the LOCAL backend it always was");
        assertEquals(AudioBackendType.WDMKS, prefs.getSelectedBackend().carrier(),
                "and it is still reached as itself, not through the net carrier");
        assertEquals(LEGACY_MIC, prefs.current().getInputDeviceName());
        assertEquals(RATE_96K, prefs.current().getInputSampleRate());
        assertEquals(LEGACY_MIC, prefs.prefsFor(AudioBackendType.WDMKS).getInputDeviceName(),
                "the enum overload finds the very same block");
        assertEquals(LEGACY_KEY, invokeToMap(prefs).get("backend"),
                "and it is written back in the same form, not rewritten");
    }

    @Test
    void anUnknownBlockSurvivesASave() throws Exception {
        // A backend (or a bench) this build cannot interpret must not lose its
        // settings just because it was loaded once by an older release.
        Map<String, Object> alienBlock = new LinkedHashMap<>();
        alienBlock.put("inputSampleRate", RATE_192K);
        Map<String, Object> perBackend = new LinkedHashMap<>();
        perBackend.put("SOMETHING_ELSE", alienBlock);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("perBackend", perBackend);

        Preferences prefs = Preferences.instance().copyForDialog();
        invokeFromMap(prefs, root);
        Map<?, ?> written = (Map<?, ?>) invokeToMap(prefs).get("perBackend");
        assertTrue(written.containsKey("SOMETHING_ELSE"), "an unreadable key is kept verbatim");
    }

    @Test
    void aDialogEditReachesTheLiveSelectionOnlyOnApply() {
        Preferences prefs = Preferences.instance();
        BackendKey benchA = BackendKey.of(SERVER_A, AudioBackendType.QA40X);
        BackendKey benchB = BackendKey.of(SERVER_B, AudioBackendType.QA40X);
        prefs.setSelectedBackend(benchA);
        prefs.prefsFor(benchA).setInputSampleRate(RATE_96K);

        // The dialog edits a detached copy: another bench, another rate.
        Preferences edit = prefs.copyForDialog();
        assertEquals(benchA, edit.getSelectedBackend(), "the copy opens on the live selection");
        assertEquals(RATE_96K, edit.current().getInputSampleRate());
        edit.setSelectedBackend(benchB);
        edit.current().setInputSampleRate(RATE_192K);
        edit.current().setInputDeviceName(MIC_B);

        // ... and nothing of it is live until OK.
        assertEquals(benchA, prefs.getSelectedBackend(), "Cancel would leave this untouched");
        assertEquals(RATE_96K, prefs.current().getInputSampleRate());

        prefs.applyFromDialog(edit);
        assertEquals(benchB, prefs.getSelectedBackend(), "OK commits the whole selection");
        assertEquals(RATE_192K, prefs.current().getInputSampleRate());
        assertEquals(MIC_B, prefs.current().getInputDeviceName());
        assertEquals(RATE_96K, prefs.prefsFor(benchA).getInputSampleRate(),
                "the bench that was left keeps its own settings");
    }

    // --- helpers -------------------------------------------------------------

    private Map<?, ?> invokeToMap(Preferences prefs) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("toMap");
        m.setAccessible(true);
        return (Map<?, ?>) m.invoke(prefs);
    }

    private void invokeFromMap(Preferences prefs, Map<?, ?> root) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("fromMap", Map.class);
        m.setAccessible(true);
        m.invoke(prefs, root);
    }

    /** The singleton's own per-backend map - reached the same way this test reads
     *  the YAML round-trip, because a block is created by asking for one and there
     *  is no un-asking for it. */
    private Map<?, ?> perBackend(Preferences prefs) throws Exception {
        Field f = Preferences.class.getDeclaredField("perBackend");
        f.setAccessible(true);
        return (Map<?, ?>) f.get(prefs);
    }
}
