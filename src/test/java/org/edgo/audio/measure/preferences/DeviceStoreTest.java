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

package org.edgo.audio.measure.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the per-card profile STORE that replaced the bundled device catalog: the
 * bundled {@code devices.yaml} seeds a missing store as ordinary profiles (with
 * their {@code match} entries), {@link Preferences#resolveDeviceProfile} folds
 * the backend name forms onto a seeded card by matching any {@code match} entry
 * as a substring of the device name, a first calibrate on a recognised-but-unbound
 * device binds the seeded profile in place (rather than forking a bare card), the
 * store round-trips through its own file, and the once-per-content-version seed
 * merge folds a newer bundle into an existing store while keeping every user-owned
 * choice (channel mode, match list, active range, hand-added ranges, calibration).
 *
 * <p>Every instance operates on a DETACHED, transient copy (as in
 * {@link DeviceProfileRoundTripTest}) so nothing here touches the live
 * singleton's on-disk store; the file-touching tests drive the private store
 * mechanism ({@code loadDevicesFrom} / {@code writeDevicesTo}) by reflection
 * against a JUnit {@link TempDir}, exactly as {@link DeviceProfileRoundTripTest}
 * reaches the private {@code toMap} seam.
 */
class DeviceStoreTest {

    private static final double EPS = 1e-12;

    /** A detached, transient Preferences with the inherited profile list cleared
     *  — the same construction {@link DeviceProfileRoundTripTest} uses. */
    private Preferences detached() {
        Preferences p = Preferences.instance().copyForDialog();
        p.setTransientMode(true);
        for (AudioDeviceProfile inherited : p.getAudioDeviceProfiles()) {
            p.removeAudioDeviceProfile(inherited.getName());
        }
        return p;
    }

    /** Invokes the private {@code loadDevicesFrom(Path)} store-establish mechanism
     *  (seed-if-absent → read → seed-merge → rewrite) against {@code path}. */
    private void loadDevicesFrom(Preferences p, Path path) {
        invoke(p, "loadDevicesFrom", Path.class, path);
    }

    /** Invokes the private {@code writeDevicesTo(Path)} file mechanism. */
    private void writeDevicesTo(Preferences p, Path path) {
        invoke(p, "writeDevicesTo", Path.class, path);
    }

    private void invoke(Preferences p, String name, Class<?> argType, Object arg) {
        try {
            Method m = Preferences.class.getDeclaredMethod(name, argType);
            m.setAccessible(true);
            m.invoke(p, arg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name + " invocation failed", e);
        }
    }

    /** Invokes the private {@code toMap()} and returns its root map. */
    private Map<?, ?> toMap(Preferences p) {
        try {
            Method m = Preferences.class.getDeclaredMethod("toMap");
            m.setAccessible(true);
            return (Map<?, ?>) m.invoke(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("toMap invocation failed", e);
        }
    }

    /** The live bundle's {@code contentVersion} via the private one-parse
     *  {@code readSeed()} snapshot — the catalog generation the
     *  once-per-content-version merge gates on.  A store recording an
     *  equal-or-higher value keeps the merge dormant; a lower (or absent → 0)
     *  value makes it fire once. */
    private int bundleContentVersion(Preferences p) {
        try {
            Method m = Preferences.class.getDeclaredMethod("readSeed");
            m.setAccessible(true);
            Object bundle = m.invoke(p);
            Field f = bundle.getClass().getDeclaredField("contentVersion");
            f.setAccessible(true);
            return (int) f.get(bundle);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("readSeed invocation failed", e);
        }
    }

    /** Stamps {@code p}'s recorded content version so a store it writes carries it
     *  (modelling a store already merged against that catalog version). */
    private void setRecordedContentVersion(Preferences p, int version) {
        try {
            var f = Preferences.class.getDeclaredField("recordedContentVersion");
            f.setAccessible(true);
            f.setInt(p, version);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("recordedContentVersion set failed", e);
        }
    }

    /** The first range labelled {@code label} (case-sensitive test lookup), or
     *  {@code null} — lets a merge assertion address a row by label rather than
     *  by its position in the rebuilt list. */
    private DeviceRange findRow(List<DeviceRange> rows, String label) {
        for (DeviceRange r : rows) {
            if (label.equals(r.getLabel())) return r;
        }
        return null;
    }

    // ── Seed-if-absent ───────────────────────────────────────────────────────

    @Test
    void loadDevices_seedsBundle_whenFileAbsent(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");
        assertFalse(Files.exists(store), "precondition: no store on disk yet");

        Preferences p = detached();
        loadDevicesFrom(p, store);

        assertTrue(Files.exists(store), "an absent store is seeded from the bundle");

        // The two bundled cards land as ordinary profiles carrying match patterns.
        AudioDeviceProfile cosmos = p.findAudioDeviceProfile("E1DA Cosmos ADC");
        assertNotNull(cosmos, "the ADC seed lands as a profile");
        assertEquals(List.of("E1DA Cosmos ADC"), cosmos.getMatch());
        assertEquals(DeviceChannelMode.INDEPENDENT, cosmos.getInput().getChannels());
        assertEquals("1.7V", cosmos.getInput().getActiveRange());
        assertEquals("1.7V", cosmos.getInput().getActiveRangeRight());
        assertEquals(9, cosmos.getInput().getRanges().size());
        assertEquals(1.7, cosmos.getInput().getRanges().get(0).getFsLeft(), EPS);
        assertEquals(1.7, cosmos.getInput().getRanges().get(0).getFsRight(), EPS);
        assertEquals(43.0, cosmos.getInput().getRanges().get(8).getFsLeft(), EPS);
        assertEquals(43.0, cosmos.getInput().getRanges().get(8).getFsRight(), EPS);

        AudioDeviceProfile i2s = p.findAudioDeviceProfile("I2SoverUSB");
        assertNotNull(i2s, "the DAC seed lands as a profile");
        assertEquals(List.of("I2SoverUSB", "JLsounds"), i2s.getMatch());
        assertEquals(2.0, i2s.getOutput().getRanges().get(0).getFsLeft(), EPS);
        assertEquals(2.0, i2s.getOutput().getRanges().get(0).getFsRight(), EPS);
    }

    @Test
    void loadDevices_doesNotReseed_whenFilePresent(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");

        // A pre-existing store with a single user card and no seed cards, already
        // stamped with the current bundle content version (the state any store
        // written by this version carries) so the once-per-content-version merge
        // stays dormant.
        Preferences first = detached();
        AudioDeviceProfile user = new AudioDeviceProfile();
        user.setName("My Card");
        first.putAudioDeviceProfile(user);
        setRecordedContentVersion(first, bundleContentVersion(first));
        writeDevicesTo(first, store);

        // Loading it must NOT re-seed the bundle over the top.
        Preferences p = detached();
        loadDevicesFrom(p, store);

        assertEquals(1, p.getAudioDeviceProfiles().size(),
                "a present store on the current content version is loaded as-is, not re-seeded");
        assertNotNull(p.findAudioDeviceProfile("My Card"));
        assertNull(p.findAudioDeviceProfile("E1DA Cosmos ADC"),
                "the bundle must not overwrite an existing store");
    }

    // ── Recognition via resolveDeviceProfile (match-entry substring) ─────────

    @Test
    void resolveDeviceProfile_recognisesSeededCardsByMatch(@TempDir Path dir) {
        Preferences p = detached();
        loadDevicesFrom(p, dir.resolve("devices.yaml"));

        // Cosmos ADC recognised across the backend name forms that CONTAIN its
        // "E1DA Cosmos ADC" match entry.
        assertEquals("E1DA Cosmos ADC",
                p.resolveDeviceProfile("Line (E1DA Cosmos ADC)").getName());
        assertEquals("E1DA Cosmos ADC",
                p.resolveDeviceProfile("E1DA Cosmos ADC").getName());
        assertEquals("E1DA Cosmos ADC",
                p.resolveDeviceProfile("E1DA Cosmos ADC PCM32/384").getName());

        // I2SoverUSB recognised via either the "I2SoverUSB" or "JLsounds" entry.
        assertEquals("I2SoverUSB", p.resolveDeviceProfile("5- I2SoverUSB").getName());
        assertEquals("I2SoverUSB",
                p.resolveDeviceProfile("Speakers (JLsounds Hi-Rez Audio 2.0)").getName());

        // Negative: a device name containing no match entry is not recognised.
        assertNull(p.resolveDeviceProfile("Focusrite Scarlett 2i2"),
                "an unknown card must not be recognised");
    }

    @Test
    void resolveDeviceProfile_longestMatchWins_acrossCards() {
        Preferences p = detached();
        AudioDeviceProfile generic = new AudioDeviceProfile();
        generic.setName("Generic USB");
        generic.getMatch().add("USB Audio");
        p.putAudioDeviceProfile(generic);
        AudioDeviceProfile specific = new AudioDeviceProfile();
        specific.setName("Cosmos");
        specific.getMatch().add("E1DA Cosmos ADC USB Audio");
        p.putAudioDeviceProfile(specific);

        // The device name contains BOTH entries; the longest (most specific) wins.
        assertEquals("Cosmos",
                p.resolveDeviceProfile("Line (E1DA Cosmos ADC USB Audio)").getName());
        // A device only the generic entry matches still resolves to the generic card.
        assertEquals("Generic USB", p.resolveDeviceProfile("Some USB Audio Widget").getName());
    }

    @Test
    void bindDeviceName_appendsOnlyWhenNoEntryAlreadyMatches() {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.getMatch().add("Cosmos ADC");

        // An existing entry ("Cosmos ADC") is already a substring of this device
        // name → nothing is appended.
        assertFalse(p.bindDeviceName("Line (E1DA Cosmos ADC)"),
                "no append when an existing entry already matches");
        assertEquals(List.of("Cosmos ADC"), p.getMatch());

        // A device name no entry matches → the full name is appended.
        assertTrue(p.bindDeviceName("Focusrite Scarlett 2i2"));
        assertEquals(List.of("Cosmos ADC", "Focusrite Scarlett 2i2"), p.getMatch());

        // A longer device name that still contains an existing entry does not append.
        assertFalse(p.bindDeviceName("Focusrite Scarlett 2i2 (2- USB)"));
        assertEquals(List.of("Cosmos ADC", "Focusrite Scarlett 2i2"), p.getMatch());
    }

    // ── store*Calibration binds a recognised unbound seed in place ───────────

    @Test
    void storeAdcCalibration_onRecognisedDevice_bindsSeededProfileInPlace(@TempDir Path dir) {
        Preferences p = detached();
        loadDevicesFrom(p, dir.resolve("devices.yaml"));
        int before = p.getAudioDeviceProfiles().size();

        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (E1DA Cosmos ADC)");
        p.storeAdcCalibration(1.82);

        assertEquals(1.82, p.getAdcFsVoltageRms(), EPS, "global scalar took the measured value");
        assertEquals(1.82, p.getAdcFsVoltageRmsRight(), EPS,
                "legacy single-arg calibrate keeps both channel scalars equal");
        assertEquals(before, p.getAudioDeviceProfiles().size(),
                "recognition binds the existing seed — no new card is forked");

        // The seeded Cosmos profile got the measured FS in its active row; its
        // match entry already recognised the device, so no name was appended.
        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (E1DA Cosmos ADC)");
        assertNotNull(bound, "the recognised device is now bound");
        assertEquals("E1DA Cosmos ADC", bound.getName());
        assertEquals(List.of("E1DA Cosmos ADC"), bound.getMatch(),
                "the seed's match entry already matched, so nothing is appended");
        DeviceRange active = bound.getInput().getRanges().get(0);
        assertEquals("1.7V", active.getLabel());
        assertEquals(1.82, active.getFsLeft(), EPS);
        assertEquals(1.82, active.getFsRight(), EPS);
        // The non-active row keeps its nominal seed value.
        assertEquals(2.7, bound.getInput().getRanges().get(1).getFsLeft(), EPS);
    }

    // ── devices.yaml round-trip through the file ─────────────────────────────

    @Test
    void store_roundTripsThroughItsOwnFile(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");

        Preferences src = detached();
        AudioDeviceProfile prof = new AudioDeviceProfile();
        prof.setName("Widget DAC");
        prof.getMatch().add("Widget");
        prof.getMatch().add("Widget DAC");
        DeviceRange row = new DeviceRange();
        row.setLabel("default");
        row.setFsLeft(2.0);
        row.setFsRight(2.0);
        prof.getOutput().setChannels(DeviceChannelMode.LINKED);
        prof.getOutput().getRanges().add(row);
        prof.getOutput().setActiveRange("default");
        src.putAudioDeviceProfile(prof);
        // Stamp the current content version so the store is on-version and the
        // once-per-content-version merge stays dormant — this is a pure round-trip.
        setRecordedContentVersion(src, bundleContentVersion(src));
        writeDevicesTo(src, store);

        // A file present on load, already on the current content version, is taken
        // as-is (no re-seed / no merge), so exactly the one card round-trips back
        // with its match pattern + alias + calibration.
        Preferences dst = detached();
        loadDevicesFrom(dst, store);

        List<AudioDeviceProfile> back = dst.getAudioDeviceProfiles();
        assertEquals(1, back.size());
        AudioDeviceProfile r = back.get(0);
        assertEquals("Widget DAC", r.getName());
        assertEquals(List.of("Widget", "Widget DAC"), r.getMatch());
        assertEquals(2.0, r.getOutput().getRanges().get(0).getFsLeft(), EPS);
    }

    @Test
    void userCard_matchOmitted_fromDisk(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("devices.yaml");

        Preferences src = detached();
        AudioDeviceProfile userCard = new AudioDeviceProfile();  // no match patterns
        userCard.setName("Bare Card");
        src.putAudioDeviceProfile(userCard);
        writeDevicesTo(src, store);

        String yaml = Files.readString(store);
        // "match:" (the KEY form) — the seed header comment the writer prepends
        // legitimately mentions the word "match" in prose.
        assertFalse(yaml.contains("match:"),
                "a user card with no patterns must not persist an empty match key");
    }

    // ── toMap no longer writes the audioDevices block ────────────────────────

    @Test
    void migration_prefsStopWritingAudioDevicesBlock() {
        // toMap must no longer emit an audioDevices block — the store moved out
        // to devices.yaml, so the block disappears from preferences.yaml.
        Preferences p = detached();
        AudioDeviceProfile prof = new AudioDeviceProfile();
        prof.setName("Any Card");
        p.putAudioDeviceProfile(prof);

        assertFalse(toMap(p).containsKey("audioDevices"),
                "preferences.yaml must no longer carry the profile store");
    }

    // ── Legacy global full-scale scalars are never written to preferences.yaml ─

    /** The four legacy full-scale keys toMap must never write — the per-card store
     *  (devices.yaml) is the sole calibration carrier. */
    private static final String[] LEGACY_FS_KEYS = {
            "adcFsVoltageRms", "adcFsVoltageRmsRight",
            "dacFsVoltageRms", "dacFsVoltageRmsRight" };

    /** A LINKED input card whose single active row carries a real (calibrated)
     *  full-scale. */
    private AudioDeviceProfile calibratedCard(String name, String deviceName, double fsVrms) {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName(name);
        p.getMatch().add(deviceName);
        DeviceRange row = new DeviceRange();
        row.setLabel("default");
        row.setFsLeft(fsVrms);
        row.setFsRight(fsVrms);
        row.setCalibrated(true);
        p.getInput().setChannels(DeviceChannelMode.LINKED);
        p.getInput().getRanges().add(row);
        p.getInput().setActiveRange("default");
        return p;
    }

    @Test
    void toMap_neverWritesLegacyFsKeys() {
        // The per-card store (devices.yaml) is the sole calibration carrier; the
        // in-memory full-scale scalars are a runtime fallback only and are never
        // persisted to preferences.yaml — all four keys stay out of toMap.
        Preferences p = detached();
        p.putAudioDeviceProfile(calibratedCard("Cosmos", "Line In (Cosmos)", 1.79));

        Map<?, ?> root = toMap(p);
        for (String key : LEGACY_FS_KEYS) {
            assertFalse(root.containsKey(key),
                    "toMap must never write legacy full-scale key " + key);
        }
    }

    // ── calibrated flag: round-trip + set by a real calibration ──────────────

    @Test
    void calibratedFlag_roundTrips_onlyWhenTrue(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("devices.yaml");

        Preferences src = detached();
        AudioDeviceProfile prof = new AudioDeviceProfile();
        prof.setName("Two Row Card");
        prof.getInput().setChannels(DeviceChannelMode.LINKED);
        DeviceRange calibrated = new DeviceRange();
        calibrated.setLabel("A");
        calibrated.setFsLeft(1.5);
        calibrated.setFsRight(1.5);
        calibrated.setCalibrated(true);
        DeviceRange nominal = new DeviceRange();
        nominal.setLabel("B");
        nominal.setFsLeft(2.5);
        nominal.setFsRight(2.5);
        prof.getInput().getRanges().add(calibrated);
        prof.getInput().getRanges().add(nominal);
        prof.getInput().setActiveRange("A");
        src.putAudioDeviceProfile(prof);
        writeDevicesTo(src, store);

        // Only the calibrated row emits the key — the nominal row stays clean.
        String yaml = Files.readString(store);
        int occurrences = yaml.split("calibrated: true", -1).length - 1;
        assertEquals(1, occurrences, "exactly the calibrated row serialises the flag");

        Preferences dst = detached();
        loadDevicesFrom(dst, store);
        AudioDeviceProfile r = dst.findAudioDeviceProfile("Two Row Card");
        assertNotNull(r);
        assertTrue(r.getInput().getRanges().get(0).isCalibrated(), "calibrated row round-trips true");
        assertFalse(r.getInput().getRanges().get(1).isCalibrated(), "nominal row round-trips false");
    }

    @Test
    void storeAdcCalibration_marksActiveRow_linkedPath(@TempDir Path dir) {
        Preferences p = detached();
        loadDevicesFrom(p, dir.resolve("devices.yaml"));

        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (E1DA Cosmos ADC)");
        p.storeAdcCalibration(1.82);

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (E1DA Cosmos ADC)");
        assertNotNull(bound);
        assertTrue(bound.getInput().getRanges().get(0).isCalibrated(),
                "the LINKED calibrate write marks its active row calibrated");
        assertFalse(bound.getInput().getRanges().get(1).isCalibrated(),
                "a non-active row stays uncalibrated");
    }

    @Test
    void storeAdcCalibration_marksActiveRow_perChannelIndependentPath(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");

        // An INDEPENDENT input card with two rows, so the per-channel write path
        // (not the LINKED delegate) marks exactly the row it touches.
        Preferences seed = detached();
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName("Indep Card");
        card.getMatch().add("Indep In");
        card.getInput().setChannels(DeviceChannelMode.INDEPENDENT);
        DeviceRange a = new DeviceRange();
        a.setLabel("A"); a.setFsLeft(1.0); a.setFsRight(1.0);
        card.getInput().getRanges().add(a);
        card.getInput().setActiveRange("A");
        card.getInput().setActiveRangeRight("A");
        seed.putAudioDeviceProfile(card);
        writeDevicesTo(seed, store);

        Preferences p = detached();
        loadDevicesFrom(p, store);
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Indep In");
        p.storeAdcCalibration(Channel.R, 3.3);

        AudioDeviceProfile bound = p.findAudioDeviceProfile("Indep Card");
        assertNotNull(bound);
        DeviceRange row = bound.getInput().getRanges().get(0);
        assertTrue(row.isCalibrated(), "the per-channel calibrate write marks its active row");
        assertEquals(3.3, row.getFsRight(), EPS, "only the right field moved");
        assertEquals(1.0, row.getFsLeft(), EPS, "the left field is untouched by a right-only calibrate");
    }

    // ── Once-per-content-version seed merge ──────────────────────────────────

    /** Writes a custom bundle YAML the merge tests point the seed override at (so
     *  the merge runs against controlled content, not the real catalog), stamped
     *  with the given {@code contentVersion}.  {@code cardsYaml} is the raw
     *  {@code audioDevices} YAML body. */
    private void writeBundle(Path bundle, int contentVersion, String cardsYaml) throws Exception {
        Files.writeString(bundle,
                "formatVersion: 1\n"
              + "contentVersion: " + contentVersion + "\n"
              + "audioDevices:\n" + cardsYaml);
    }

    /** A LEGACY user store already on disk: it carries the old {@code seedFingerprint}
     *  line (which the reader must tolerate and drop) and NO {@code contentVersion}
     *  (so its recorded version reads as 0 and any bundle at version ≥ 1 merges once).
     *  {@code cardsYaml} is the raw {@code audioDevices} YAML body. */
    private void writeStore(Path store, String cardsYaml) throws Exception {
        Files.writeString(store,
                "formatVersion: 1\n"
              + "seedFingerprint: legacy-fingerprint-line-to-be-dropped\n"
              + "audioDevices:\n" + cardsYaml);
    }

    /** A user store already recorded at a specific {@code contentVersion} — the
     *  steady state after a prior merge, used to model dormant / higher-version
     *  gate cases.  {@code cardsYaml} is the raw {@code audioDevices} YAML body. */
    private void writeStore(Path store, int contentVersion, String cardsYaml) throws Exception {
        Files.writeString(store,
                "formatVersion: 1\n"
              + "contentVersion: " + contentVersion + "\n"
              + "audioDevices:\n" + cardsYaml);
    }

    /** Loads {@code store} against the custom {@code bundle} once, returning the
     *  merged Preferences (whether the gate actually fired depends on the two
     *  content versions). */
    private Preferences mergeAgainst(Path store, Path bundle) {
        Preferences p = detached();
        p.setSeedPathOverride(bundle);
        loadDevicesFrom(p, store);
        return p;
    }

    @Test
    void mergeSeed_addsNewCard(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        writeStore(store,
                "  - name: My Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"default\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"default\"\n");
        writeBundle(bundle, 1,
                "  - name: Brand New Card\n"
              + "    match: [ \"Brand New\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1V\", fsVrms: 1.1 }\n"
              + "      activeRange: \"1V\"\n");

        Preferences p = mergeAgainst(store, bundle);

        assertNotNull(p.findAudioDeviceProfile("My Card"), "the user card survives");
        AudioDeviceProfile added = p.findAudioDeviceProfile("Brand New Card");
        assertNotNull(added, "the bundle's new card is added by the merge");
        assertEquals(List.of("Brand New"), added.getMatch(), "the new card keeps its match patterns");
        assertEquals(1.1, added.getInput().getRanges().get(0).getFsLeft(), EPS);
        assertFalse(added.getInput().getRanges().get(0).isCalibrated(), "a seeded row is nominal");
    }

    @Test
    void mergeSeed_unionsNewSeedMatchEntries(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // A released seed added a new recognition entry ("Extra Seed Pattern").  The
        // merge UNIONS it into the store card (add-only): the store's own entries
        // keep their place and order, the seed entry already shared ("Widget") is
        // deduped away, and the genuinely-new one is appended.
        writeStore(store,
                "  - name: Widget\n"
              + "    match: [ \"Widget\", \"Line (Widget Pro)\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 1,
                "  - name: Widget\n"
              + "    match: [ \"Widget\", \"Extra Seed Pattern\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 2.0 }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        assertEquals(List.of("Widget", "Line (Widget Pro)", "Extra Seed Pattern"),
                p.findAudioDeviceProfile("Widget").getMatch(),
                "the seed's new match entry is unioned on (add-only, existing entries kept in order)");
    }

    @Test
    void mergeSeed_addsNewRangeToExistingCard(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        writeStore(store,
                "  - name: E1DA Cosmos ADC\n"
              + "    match: [ \"Cosmos\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: 1.7931, calibrated: true }\n"
              + "      activeRange: \"1.7V\"\n");
        writeBundle(bundle, 1,
                "  - name: E1DA Cosmos ADC\n"
              + "    match: [ \"Cosmos\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: 1.7 }\n"
              + "        - { label: \"43V\", fsVrms: 43 }\n"
              + "      activeRange: \"1.7V\"\n");

        Preferences p = mergeAgainst(store, bundle);

        AudioDeviceProfile c = p.findAudioDeviceProfile("E1DA Cosmos ADC");
        assertNotNull(c);
        assertEquals(2, c.getInput().getRanges().size(), "the bundle's new range is added");
        DeviceRange added = c.getInput().getRanges().get(1);
        assertEquals("43V", added.getLabel());
        assertEquals(43.0, added.getFsLeft(), EPS);
        assertFalse(added.isCalibrated(), "an added range is nominal");
        // The existing calibrated row keeps its measured value (not refreshed to 1.7).
        DeviceRange kept = c.getInput().getRanges().get(0);
        assertEquals("1.7V", kept.getLabel());
        assertEquals(1.7931, kept.getFsLeft(), EPS, "the calibrated row keeps its measured FS");
        assertTrue(kept.isCalibrated());
    }

    @Test
    void mergeSeed_uncalibratedRowUpdated_userAddedRowSurvives(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // Card X carries a stale UNcalibrated R1 plus a hand-added UNcalibrated
        // "Rextra".  The merge refreshes R1 to the release value but NEVER deletes a
        // row the seed no longer lists, so the hand-added "Rextra" survives.
        writeStore(store,
                "  - name: Card X\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 9.99 }\n"
              + "        - { label: \"Rextra\", fsVrms: 5.5 }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 1,
                "  - name: Card X\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 2.22 }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        var ranges = p.findAudioDeviceProfile("Card X").getInput().getRanges();
        assertEquals(2, ranges.size(), "the hand-added row is kept — the merge never deletes ranges");
        DeviceRange r1 = findRow(ranges, "R1");
        assertNotNull(r1);
        assertEquals(2.22, r1.getFsLeft(), EPS, "an uncalibrated row takes the release value");
        assertEquals(2.22, r1.getFsRight(), EPS);
        assertFalse(r1.isCalibrated(), "the seed value never marks the row calibrated");
        DeviceRange rextra = findRow(ranges, "Rextra");
        assertNotNull(rextra, "the hand-added range survives the merge");
        assertEquals(5.5, rextra.getFsLeft(), EPS, "its value is untouched");
    }

    @Test
    void mergeSeed_neverTouchesCalibratedRow(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // The user row is CALIBRATED — the merge must leave its measured FS alone.
        writeStore(store,
                "  - name: Card Y\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.7931, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 1,
                "  - name: Card Y\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.7 }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        DeviceRange row = p.findAudioDeviceProfile("Card Y").getInput().getRanges().get(0);
        assertEquals(1.7931, row.getFsLeft(), EPS, "a calibrated row's measured FS survives the merge");
        assertTrue(row.isCalibrated());
    }

    @Test
    void mergeSeed_reconcilesRangesOnly_keepsUserStructure_maintainerStoreShape(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // THE MAINTAINER STORE SHAPE: a Cosmos card the user calibrated
        // (INDEPENDENT, a "1.7V" row with distinct left/right values, two match
        // entries), a hand-added UNcalibrated "3.4V" row, and a calibrated "9.9V"
        // row whose label the new release drops.  Plus a card the USER created
        // ("My Custom Rig").
        writeStore(store,
                "  - name: E1DA Cosmos ADC\n"
              + "    match: [ \"Line (E1DA Cosmos ADC)\", \"E1DA Cosmos ADC\" ]\n"
              + "    input:\n"
              + "      channels: INDEPENDENT\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: { left: 1.7931, right: 1.7902 }, calibrated: true }\n"
              + "        - { label: \"3.4V\", fsVrms: 3.4 }\n"
              + "        - { label: \"9.9V\", fsVrms: { left: 9.9, right: 9.8 }, calibrated: true }\n"
              + "      activeRange: { left: \"1.7V\", right: \"1.7V\" }\n"
              + "  - name: My Custom Rig\n"
              + "    match: [ \"Line (My Custom Rig)\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"default\", fsVrms: 2.0, calibrated: true }\n"
              + "      activeRange: \"default\"\n");
        // The new release declares Cosmos LINKED and ships a different, larger range
        // set — the merge reconciles RANGES only, so none of the store's structure
        // (channel mode, match list, active range, hand-added rows) is changed.
        writeBundle(bundle, 1,
                "  - name: E1DA Cosmos ADC\n"
              + "    match: [ \"E1DA Cosmos ADC\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: 1.7 }\n"
              + "        - { label: \"2.7V\", fsVrms: 2.7 }\n"
              + "        - { label: \"3.5V\", fsVrms: 3.5 }\n"
              + "        - { label: \"4.5V\", fsVrms: 4.5 }\n"
              + "      activeRange: \"1.7V\"\n");

        Preferences p = mergeAgainst(store, bundle);

        AudioDeviceProfile cosmos = p.findAudioDeviceProfile("E1DA Cosmos ADC");
        assertNotNull(cosmos);

        // The store's own structure is untouched: the channel mode stays exactly as
        // the user had it (the seed's LINKED mode is NOT adopted), and the match list
        // is unchanged because the seed's only entry ("E1DA Cosmos ADC") is already
        // present (the union deduped it away — no user entry is ever dropped).
        assertEquals(DeviceChannelMode.INDEPENDENT, cosmos.getInput().getChannels(),
                "the user's channel mode is untouched (not reshaped to the seed's)");
        assertEquals(List.of("Line (E1DA Cosmos ADC)", "E1DA Cosmos ADC"), cosmos.getMatch(),
                "the seed's only match entry is already present, so the union adds nothing");

        var ranges = cosmos.getInput().getRanges();
        // The three store rows all survive (nothing is ever deleted) + the three
        // seed-only rows are appended = 6.  The hand-added "3.4V" is kept.
        assertEquals(6, ranges.size());
        DeviceRange r34 = findRow(ranges, "3.4V");
        assertNotNull(r34, "the hand-added uncalibrated row survives");
        assertEquals(3.4, r34.getFsLeft(), EPS);

        // The user's "1.7V" calibration (distinct left/right) survives the update.
        DeviceRange r17 = findRow(ranges, "1.7V");
        assertNotNull(r17);
        assertTrue(r17.isCalibrated(), "the calibrated row survives the update");
        assertEquals(1.7931, r17.getFsLeft(),  EPS, "distinct calibrated left survives");
        assertEquals(1.7902, r17.getFsRight(), EPS, "distinct calibrated right survives");

        // Seed-only rows arrive as nominal (uncalibrated).
        DeviceRange r27 = findRow(ranges, "2.7V");
        assertNotNull(r27);
        assertEquals(2.7, r27.getFsLeft(), EPS);
        assertFalse(r27.isCalibrated());

        // The calibrated row the release does not list is left in place (distinct L/R).
        DeviceRange r99 = findRow(ranges, "9.9V");
        assertNotNull(r99, "a calibrated row the seed no longer lists is kept");
        assertTrue(r99.isCalibrated());
        assertEquals(9.9, r99.getFsLeft(),  EPS);
        assertEquals(9.8, r99.getFsRight(), EPS);

        // The user's active-range selection is untouched.
        assertEquals("1.7V", cosmos.getInput().getActiveRange());
        assertEquals("1.7V", cosmos.getInput().getActiveRangeRight());

        // The user-created card is untouched (structure + calibration + match).
        AudioDeviceProfile mine = p.findAudioDeviceProfile("My Custom Rig");
        assertNotNull(mine, "a user-created card survives the merge");
        assertEquals(DeviceChannelMode.LINKED, mine.getInput().getChannels());
        assertEquals(1, mine.getInput().getRanges().size());
        assertEquals(2.0, mine.getInput().getRanges().get(0).getFsLeft(), EPS);
        assertTrue(mine.getInput().getRanges().get(0).isCalibrated());
        assertEquals(List.of("Line (My Custom Rig)"), mine.getMatch(),
                "the user card's match entry survives");
    }

    @Test
    void mergeSeed_findsRenamedCard_byMatchOverlap(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // The user RENAMED the well-known card ("My Renamed ADC") but kept a
        // recognition entry ("Cosmos ADC") that overlaps the seed's match list, so
        // the merge still maps the seed onto it (by match overlap, not name) and
        // adds the seed's new range — without forking a second card.
        writeStore(store,
                "  - name: My Renamed ADC\n"
              + "    match: [ \"Cosmos ADC\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: 1.79, calibrated: true }\n"
              + "      activeRange: \"1.7V\"\n");
        writeBundle(bundle, 1,
                "  - name: E1DA Cosmos ADC\n"
              + "    match: [ \"Cosmos ADC\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"1.7V\", fsVrms: 1.7 }\n"
              + "        - { label: \"43V\", fsVrms: 43 }\n"
              + "      activeRange: \"1.7V\"\n");

        Preferences p = mergeAgainst(store, bundle);

        assertEquals(1, p.getAudioDeviceProfiles().size(),
                "the seed maps onto the renamed card by match overlap — no second card is forked");
        AudioDeviceProfile renamed = p.findAudioDeviceProfile("My Renamed ADC");
        assertNotNull(renamed, "the card keeps its user-given name");
        assertEquals(2, renamed.getInput().getRanges().size(), "the seed's new range is added to it");
        DeviceRange r17 = findRow(renamed.getInput().getRanges(), "1.7V");
        assertEquals(1.79, r17.getFsLeft(), EPS, "the calibrated row keeps its measured value");
        assertTrue(r17.isCalibrated());
        assertNotNull(findRow(renamed.getInput().getRanges(), "43V"), "the seed's new range landed");
    }

    @Test
    void mergeSeed_scalarCalibratedRow_keepsValue_onPairFormatUpdate(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // The user's row was scalar-calibrated (a single fsVrms, both channels
        // equal).  The release ships the same label in {left,right} pair format.
        // The update takes the seed's format but copies the user's calibrated value
        // onto both channels — the measured value is never lost.
        writeStore(store,
                "  - name: Card Z\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.75, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 1,
                "  - name: Card Z\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: { left: 2.0, right: 2.1 } }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        DeviceRange row = p.findAudioDeviceProfile("Card Z").getInput().getRanges().get(0);
        assertTrue(row.isCalibrated(), "the calibrated flag is carried across the format change");
        assertEquals(1.75, row.getFsLeft(),  EPS, "the scalar-calibrated value lands on the left channel");
        assertEquals(1.75, row.getFsRight(), EPS, "and on the right channel (both equal — never the seed's)");

        // The merge rewrote the store (changed=true): even the scalar-input row is
        // re-emitted in the {left, right} pair form, never a scalar shorthand.
        String yaml = Files.readString(store);
        assertTrue(yaml.contains("left:") && yaml.contains("right:"),
                "the rewritten store emits fsVrms in the {left, right} pair form");
    }

    @Test
    void sameContentVersionStartup_doesNotMerge_handEditedNominalSurvives(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        writeBundle(bundle, 1,
                "  - name: Card W\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 3.0 }\n"
              + "      activeRange: \"R1\"\n");

        // First run against the bundle records the bundle's fingerprint.
        Preferences first = detached();
        first.setSeedPathOverride(bundle);
        loadDevicesFrom(first, store);
        assertNotNull(first.findAudioDeviceProfile("Card W"), "first run seeds the bundle card");

        // The user hand-edits the (uncalibrated) nominal in the file.
        String edited = Files.readString(store).replace("3.0", "7.0");
        Files.writeString(store, edited);

        // A restart against the SAME bundle must NOT merge — same content version
        // (the first run seeded the store from the bundle, recording version 1).
        Preferences second = detached();
        second.setSeedPathOverride(bundle);
        loadDevicesFrom(second, store);

        assertEquals(7.0, second.findAudioDeviceProfile("Card W").getInput().getRanges().get(0).getFsLeft(), EPS,
                "same-version startup does not merge, so the hand-edited nominal survives");
    }

    @Test
    void legacyStore_mergesOnce_recordsContentVersion_fingerprintLineDropped(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // A LEGACY store: it carries the old seedFingerprint line and no
        // contentVersion (recorded version 0), so the version-1 bundle merges once.
        writeStore(store,
                "  - name: Only Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 1,
                "  - name: New One\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 2.0 }\n"
              + "      activeRange: \"R1\"\n");

        Preferences merged = mergeAgainst(store, bundle);

        assertNotNull(merged.findAudioDeviceProfile("New One"), "the legacy store merged the bundle card once");
        assertNotNull(merged.findAudioDeviceProfile("Only Card"), "the user card survives");

        // The merge rewrote the store recording the bundle's contentVersion and
        // dropping the legacy seedFingerprint line.
        String yaml = Files.readString(store);
        assertTrue(yaml.contains("contentVersion: 1"),
                "the store records the bundle's contentVersion after merge");
        assertFalse(yaml.contains("seedFingerprint"),
                "the legacy seedFingerprint line is dropped from disk");

        // A second load on the recorded version must NOT merge again — a hand-edited
        // nominal on the now-seeded card survives.
        Files.writeString(store, Files.readString(store).replace("2.0", "7.0"));
        Preferences second = detached();
        second.setSeedPathOverride(bundle);
        loadDevicesFrom(second, store);
        assertEquals(7.0, second.findAudioDeviceProfile("New One").getInput().getRanges().get(0).getFsLeft(), EPS,
                "the recorded version keeps the merge dormant on the next start");
    }

    @Test
    void higherContentVersion_firesMerge(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // The store already recorded contentVersion 1; the app now ships a bundle at
        // contentVersion 2, so the merge fires once and folds the new card in.
        writeStore(store, 1,
                "  - name: My Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        writeBundle(bundle, 2,
                "  - name: New Seed\n"
              + "    match: [ \"New\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 2.0 }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        assertNotNull(p.findAudioDeviceProfile("New Seed"),
                "a higher bundle contentVersion re-fires the merge");
        assertNotNull(p.findAudioDeviceProfile("My Card"), "the user card survives");
    }

    @Test
    void sameContentVersion_doesNotMerge(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        writeBundle(bundle, 1,
                "  - name: Seed Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 3.0 }\n"
              + "      activeRange: \"R1\"\n");

        // The store already records the bundle's contentVersion — the steady state
        // after a prior merge.
        writeStore(store, 1,
                "  - name: My Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");

        Preferences p = mergeAgainst(store, bundle);

        assertNull(p.findAudioDeviceProfile("Seed Card"),
                "same contentVersion → no merge, so the bundle card is not seeded in");
        assertEquals(1, p.getAudioDeviceProfiles().size(), "only the user card is present");
        assertEquals(1.0,
                p.findAudioDeviceProfile("My Card").getInput().getRanges().get(0).getFsLeft(), EPS);
    }

    @Test
    void writtenStore_copiesFormatVersionFromSeed(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // The bundled seed is the single source of truth for formatVersion — the
        // writer copies it verbatim at write time, never a hardwired constant.
        Files.writeString(bundle,
                "formatVersion: 7\n"
              + "contentVersion: 1\n"
              + "audioDevices:\n"
              + "  - name: Seed Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0 }\n"
              + "      activeRange: \"R1\"\n");
        Preferences p = detached();
        p.setSeedPathOverride(bundle);
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName("My Card");
        p.putAudioDeviceProfile(card);

        writeDevicesTo(p, store);
        assertTrue(Files.readString(store).contains("formatVersion: 7"),
                "the written store carries the seed's formatVersion verbatim");

        // A seed reverted to version 1 stamps 1 on the next write — nothing pins it.
        Files.writeString(bundle,
                Files.readString(bundle).replace("formatVersion: 7", "formatVersion: 1"));
        writeDevicesTo(p, store);
        assertTrue(Files.readString(store).contains("formatVersion: 1"),
                "reverting the seed's formatVersion reverts the written store's too");
    }

    @Test
    void writtenStore_formatVersion_fallsBackToLoadedStore_whenSeedUnreadable(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("missing-bundle.yaml");   // never created → seed unreadable
        Files.writeString(store,
                "formatVersion: 5\n"
              + "contentVersion: 1\n"
              + "audioDevices:\n"
              + "  - name: My Card\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      ranges:\n"
              + "        - { label: \"R1\", fsVrms: 1.0, calibrated: true }\n"
              + "      activeRange: \"R1\"\n");
        Preferences p = detached();
        p.setSeedPathOverride(bundle);
        loadDevicesFrom(p, store);

        writeDevicesTo(p, store);
        assertTrue(Files.readString(store).contains("formatVersion: 5"),
                "with the seed unreadable, the loaded store's own formatVersion stands");
    }

    // ── calibrationFromDevice: store guard + seed-authoritative merge ─────────

    /** Captures the WARN (and higher) messages the {@code Preferences} logger emits
     *  while {@code action} runs — so a store-guard test can assert the guard warns
     *  without inspecting the log file. */
    private List<String> capturePreferencesWarnings(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(Preferences.class);
        List<String> messages = new ArrayList<>();
        AbstractAppender appender =
                new AbstractAppender("test-capture", null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                            messages.add(event.getMessage().getFormattedMessage());
                        }
                    }
                };
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return messages;
    }

    /** A QA40x-style card whose INPUT endpoint is device-provided (its full-scale is
     *  loaded from the device), bound to WASAPI, one nominal 0 dB range at 7.0 V. */
    private AudioDeviceProfile deviceProvidedInputCard() {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName("QA40x");
        p.getMatch().add("QA40x Input");
        p.getInput().setChannels(DeviceChannelMode.LINKED);
        p.getInput().setCalibrationFromDevice(true);
        DeviceRange row = new DeviceRange();
        row.setLabel("0 dB");
        row.setFsLeft(7.0);
        row.setFsRight(7.0);
        p.getInput().getRanges().add(row);
        p.getInput().setActiveRange("0 dB");
        return p;
    }

    /** The output mirror of {@link #deviceProvidedInputCard()} — a device-provided
     *  OUTPUT endpoint, one nominal 0 dB range at 3.0 V. */
    private AudioDeviceProfile deviceProvidedOutputCard() {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName("QA40x");
        p.getMatch().add("QA40x Output");
        p.getOutput().setChannels(DeviceChannelMode.LINKED);
        p.getOutput().setCalibrationFromDevice(true);
        DeviceRange row = new DeviceRange();
        row.setLabel("0 dB");
        row.setFsLeft(3.0);
        row.setFsRight(3.0);
        p.getOutput().getRanges().add(row);
        p.getOutput().setActiveRange("0 dB");
        return p;
    }

    @Test
    void storeAdcCalibration_deviceProvidedEndpoint_isNoOpAndWarns() {
        Preferences p = detached();
        p.putAudioDeviceProfile(deviceProvidedInputCard());
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("QA40x Input");
        p.setAdcFsVoltageRms(1.234);
        p.setAdcFsVoltageRmsRight(1.234);

        List<String> warnings = capturePreferencesWarnings(() -> p.storeAdcCalibration(5.0));

        // The device-owned range value and BOTH global scalars are untouched.
        DeviceRange row = p.findAudioDeviceProfile("QA40x").getInput().getRanges().get(0);
        assertEquals(7.0, row.getFsLeft(), EPS, "the device-provided range value is not overwritten");
        assertFalse(row.isCalibrated(), "a device-provided row is never marked calibrated by the guard");
        assertEquals(1.234, p.getAdcFsVoltageRms(), EPS, "the global ADC scalar is left untouched");
        assertEquals(1.234, p.getAdcFsVoltageRmsRight(), EPS, "the right ADC scalar is left untouched");

        assertTrue(warnings.stream().anyMatch(m -> m.contains("device-provided") && m.contains("QA40x")),
                "the guard warns that calibration is device-provided for the card");
    }

    @Test
    void storeAdcCalibration_channelAware_deviceProvidedEndpoint_isNoOp() {
        Preferences p = detached();
        p.putAudioDeviceProfile(deviceProvidedInputCard());
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("QA40x Input");
        p.setAdcFsVoltageRmsRight(1.234);

        p.storeAdcCalibration(Channel.R, 9.0);

        DeviceRange row = p.findAudioDeviceProfile("QA40x").getInput().getRanges().get(0);
        assertEquals(7.0, row.getFsRight(), EPS, "the channel-aware calibrate write is also blocked");
        assertFalse(row.isCalibrated(), "no calibration flag is set");
        assertEquals(1.234, p.getAdcFsVoltageRmsRight(), EPS, "the right scalar is left untouched");
    }

    @Test
    void storeDacCalibration_deviceProvidedEndpoint_isNoOpAndWarns() {
        Preferences p = detached();
        p.putAudioDeviceProfile(deviceProvidedOutputCard());
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setOutputDeviceName("QA40x Output");
        p.setDacFsVoltageAmpl(1.5);
        p.setDacFsVoltageAmplRight(1.5);

        List<String> warnings = capturePreferencesWarnings(() -> p.storeDacCalibration(9.9));

        DeviceRange row = p.findAudioDeviceProfile("QA40x").getOutput().getRanges().get(0);
        assertEquals(3.0, row.getFsLeft(), EPS, "the device-provided output range value is not overwritten");
        assertFalse(row.isCalibrated(), "a device-provided output row is never marked calibrated");
        assertEquals(1.5, p.getDacFsVoltageAmpl(), EPS, "the global DAC scalar is left untouched");
        assertTrue(warnings.stream().anyMatch(m -> m.contains("device-provided") && m.contains("QA40x")),
                "the DAC guard warns that calibration is device-provided for the card");
    }

    @Test
    void mergeSeed_deviceProvidedEndpoint_ignoresUserValues_keepsActiveRange(@TempDir Path dir) throws Exception {
        Path store  = dir.resolve("devices.yaml");
        Path bundle = dir.resolve("bundle.yaml");
        // A device-provided card whose ranges were (hand-)edited with calibrated
        // values and whose active range the user switched to "18dB".  The rebuild
        // must take the device-provided ranges WHOLESALE from the seed (values +
        // calibrated flag ignored) yet keep the switchable active-range choice.
        writeStore(store,
                "  - name: QA40x\n"
              + "    match: [ \"QA40x Input\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      calibrationFromDevice: true\n"
              + "      ranges:\n"
              + "        - { label: \"0dB\", fsVrms: 1.111, calibrated: true }\n"
              + "        - { label: \"18dB\", fsVrms: 2.222, calibrated: true }\n"
              + "      activeRange: \"18dB\"\n");
        writeBundle(bundle, 1,
                "  - name: QA40x\n"
              + "    match: [ \"QA40x\" ]\n"
              + "    input:\n"
              + "      channels: LINKED\n"
              + "      calibrationFromDevice: true\n"
              + "      ranges:\n"
              + "        - { label: \"0dB\", fsVrms: 9.9 }\n"
              + "        - { label: \"18dB\", fsVrms: 8.8 }\n"
              + "      activeRange: \"0dB\"\n");

        Preferences p = mergeAgainst(store, bundle);

        DeviceEndpointConfig in = p.findAudioDeviceProfile("QA40x").getInput();
        assertTrue(in.isCalibrationFromDevice(), "the endpoint stays device-provided after the merge");
        assertEquals(2, in.getRanges().size(), "no user row is appended onto a device-provided endpoint");
        // The device-provided values come wholesale from the seed — the user's
        // hand-edited calibrated values are ignored on every row.
        assertEquals(9.9, findRow(in.getRanges(), "0dB").getFsLeft(), EPS,
                "the seed value stands; the user's device-provided value is ignored");
        assertEquals(8.8, findRow(in.getRanges(), "18dB").getFsLeft(), EPS,
                "the seed value stands on every device-provided row");
        assertFalse(findRow(in.getRanges(), "0dB").isCalibrated(),
                "a device-provided row is never marked calibrated");
        // The switchable active-range selection the user made survives the merge.
        assertEquals("18dB", in.getActiveRange(),
                "the user's active-range choice survives (the ranges stay switchable)");
    }
}
