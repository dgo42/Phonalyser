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

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + resolution tests for the per-card device profiles: the model
 * serialises to YAML and back on a fresh detached {@link Preferences} without
 * value loss, every range writes a {@code {left, right}} pair on disk in inline
 * bracket flow style (matching the bundled seed), the device-name normalisation
 * folds the backend wrappers together, and a first calibrate auto-creates a bound
 * profile while still driving the global full-scale.
 *
 * <p>Every instance operates on a DETACHED copy ({@link Preferences#copyForDialog()},
 * additionally pinned to transient mode) so nothing here touches the user's
 * {@code preferences.yaml} on disk.  {@code toMap} / {@code fromMap} are the
 * private serialisation seam, invoked by reflection so the test exercises the
 * exact production marshalling; the YAML dump/load in between reproduces the
 * SnakeYAML numeric-type quirks the readers must tolerate.
 */
class DeviceProfileRoundTripTest {

    private static final double EPS = 1e-12;

    /** A detached, transient (never-persisting, never-loading) Preferences to
     *  mutate freely - the same construction the dialog uses.  Any profiles the
     *  live singleton carried into the copy are cleared so each test starts from
     *  a known-empty profile list. */
    private Preferences detached() {
        Preferences p = Preferences.instance().copyForDialog();
        p.setTransientMode(true);
        for (AudioDeviceProfile inherited : p.getAudioDeviceProfiles()) {
            p.removeAudioDeviceProfile(inherited.getName());
        }
        return p;
    }

    /** Invokes the private {@code writeDevicesTo(Path)} file mechanism - the
     *  profile store's serialisation seam, since the store moved out of
     *  preferences.yaml into its own devices.yaml. */
    /** Stamps {@code p}'s recorded content version with the live bundle's
     *  {@code contentVersion}, so a store it writes models one already merged by
     *  this catalog version and the load skips the once-per-content-version seed
     *  merge. */
    private void stampCurrentContentVersion(Preferences p) {
        try {
            Method m = Preferences.class.getDeclaredMethod("readSeed");
            m.setAccessible(true);
            Object bundle = m.invoke(p);
            var cvField = bundle.getClass().getDeclaredField("contentVersion");
            cvField.setAccessible(true);
            int cv = (int) cvField.get(bundle);
            var f = Preferences.class.getDeclaredField("recordedContentVersion");
            f.setAccessible(true);
            f.setInt(p, cv);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("content-version stamp failed", e);
        }
    }

    private void writeDevicesTo(Preferences p, Path path) {
        try {
            Method m = Preferences.class.getDeclaredMethod("writeDevicesTo", Path.class);
            m.setAccessible(true);
            m.invoke(p, path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("writeDevicesTo invocation failed", e);
        }
    }

    /** Invokes the private {@code loadDevicesFrom(Path)} store-establish
     *  mechanism (seed-if-absent -> read -> migration-merge -> rewrite). */
    private void loadDevicesFrom(Preferences p, Path path) {
        try {
            Method m = Preferences.class.getDeclaredMethod("loadDevicesFrom", Path.class);
            m.setAccessible(true);
            m.invoke(p, path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("loadDevicesFrom invocation failed", e);
        }
    }

    /** Loads a written devices.yaml back as its raw YAML map - reproduces the
     *  on-disk numeric-type coercions the readers must tolerate. */
    private Map<?, ?> readYaml(Path path) {
        try (Reader r = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(r);
            assertTrue(loaded instanceof Map, "YAML round-trip must yield a map");
            return (Map<?, ?>) loaded;
        } catch (Exception e) {
            throw new AssertionError("reading devices.yaml failed", e);
        }
    }

    /** A two-range profile: one shared-FS row, one per-channel row; three match
     *  entries (the backend device-name forms); INDEPENDENT input + LINKED output. */
    private AudioDeviceProfile sampleProfile() {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName("Cosmos ADC");
        p.getMatch().add("Line (E1DA Cosmos ADC)");
        p.getMatch().add("E1DA Cosmos ADC");
        p.getMatch().add("Speakers (E1DA Cosmos ADC)");

        DeviceRange perChannel = new DeviceRange();
        perChannel.setLabel("DIP 1.7 Vrms");
        perChannel.setFsLeft(1.7931);
        perChannel.setFsRight(1.7902);
        DeviceRange shared = new DeviceRange();
        shared.setLabel("DIP 4.5 Vrms");
        shared.setFsLeft(4.4812);
        shared.setFsRight(4.4812);

        p.getInput().setChannels(DeviceChannelMode.INDEPENDENT);
        p.getInput().getRanges().add(perChannel);
        p.getInput().getRanges().add(shared);
        p.getInput().setActiveRange("DIP 1.7 Vrms");
        p.getInput().setActiveRangeRight("DIP 4.5 Vrms");

        DeviceRange out = new DeviceRange();
        out.setLabel("direct");
        out.setFsLeft(1.975);
        out.setFsRight(1.975);
        p.getOutput().setChannels(DeviceChannelMode.LINKED);
        p.getOutput().getRanges().add(out);
        p.getOutput().setActiveRange("direct");
        return p;
    }

    @Test
    void profile_survivesYamlRoundTrip(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");
        Preferences src = detached();
        src.putAudioDeviceProfile(sampleProfile());
        // Stamp the current bundle content version so the written store models one
        // already merged by this catalog version - the load below then round-trips
        // the sample card alone instead of also merging the bundled seed cards in.
        stampCurrentContentVersion(src);
        writeDevicesTo(src, store);

        // A file present on load is taken as-is (no re-seed), so exactly the one
        // sample card comes back through devices.yaml without value loss.
        Preferences dst = detached();
        loadDevicesFrom(dst, store);

        List<AudioDeviceProfile> back = dst.getAudioDeviceProfiles();
        assertEquals(1, back.size());
        AudioDeviceProfile p = back.get(0);
        assertEquals("Cosmos ADC", p.getName());

        assertEquals(List.of("Line (E1DA Cosmos ADC)", "E1DA Cosmos ADC", "Speakers (E1DA Cosmos ADC)"),
                p.getMatch());

        DeviceEndpointConfig in = p.getInput();
        assertEquals(DeviceChannelMode.INDEPENDENT, in.getChannels());
        assertEquals("DIP 1.7 Vrms", in.getActiveRange());
        assertEquals("DIP 4.5 Vrms", in.getActiveRangeRight());
        assertEquals(2, in.getRanges().size());
        DeviceRange perCh = in.getRanges().get(0);
        assertEquals("DIP 1.7 Vrms", perCh.getLabel());
        assertEquals(1.7931, perCh.getFsLeft(),  EPS);
        assertEquals(1.7902, perCh.getFsRight(), EPS);
        DeviceRange sh = in.getRanges().get(1);
        assertEquals(4.4812, sh.getFsLeft(),  EPS);
        assertEquals(4.4812, sh.getFsRight(), EPS);

        DeviceEndpointConfig out = p.getOutput();
        assertEquals(DeviceChannelMode.LINKED, out.getChannels());
        assertEquals("direct", out.getActiveRange());
        assertEquals(1, out.getRanges().size());
        assertEquals(1.975, out.getRanges().get(0).getFsLeft(), EPS);
    }

    @Test
    void everyRange_writesLeftRightPair_asOneSeedStyleLine(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("devices.yaml");
        Preferences src = detached();
        src.putAudioDeviceProfile(sampleProfile());
        writeDevicesTo(src, store);

        Map<?, ?> root = readYaml(store);
        Object devsObj = root.get("audioDevices");
        assertTrue(devsObj instanceof List, "audioDevices must be a list");
        Map<?, ?> profile = (Map<?, ?>) ((List<?>) devsObj).get(0);
        Map<?, ?> input   = (Map<?, ?>) profile.get("input");
        List<?> ranges    = (List<?>) input.get("ranges");

        // Per-channel row -> a {left,right} map.
        Object perChFs = ((Map<?, ?>) ranges.get(0)).get("fsVrms");
        assertTrue(perChFs instanceof Map, "per-channel FS must be a {left,right} map");
        assertEquals(1.7931, ((Number) ((Map<?, ?>) perChFs).get("left")).doubleValue(),  EPS);
        assertEquals(1.7902, ((Number) ((Map<?, ?>) perChFs).get("right")).doubleValue(), EPS);

        // Equal-channel row -> ALSO a {left,right} map now, never a scalar shorthand.
        Object sharedFs = ((Map<?, ?>) ranges.get(1)).get("fsVrms");
        assertTrue(sharedFs instanceof Map, "an equal-channel FS is still a {left,right} map, never a scalar");
        assertEquals(4.4812, ((Number) ((Map<?, ?>) sharedFs).get("left")).doubleValue(),  EPS);
        assertEquals(4.4812, ((Number) ((Map<?, ?>) sharedFs).get("right")).doubleValue(), EPS);

        // On disk each range row is EXACTLY one line in the seed's style: bracket
        // flow, spaces inside the braces, the label double-quoted - never wrapped.
        String yaml = Files.readString(store);
        assertTrue(yaml.contains("- { label: \""), "rows open in the seed's quoted-label bracket style");
        assertTrue(yaml.contains(" fsVrms: { left: "), "fsVrms is the inline { left, right } pair");
        assertTrue(yaml.contains(
                "- { label: \"DIP 1.7 Vrms\", fsVrms: { left: 1.7931, right: 1.7902 } }"),
                "a per-channel row is one exact seed-style line");
        assertTrue(yaml.contains(
                "- { label: \"DIP 4.5 Vrms\", fsVrms: { left: 4.4812, right: 4.4812 } }"),
                "an equal-channel row is one exact seed-style line in pair form");
        assertTrue(yaml.contains("ranges:") && yaml.contains("channels:"),
                "the surrounding endpoint block stays block style");
    }

    @Test
    void activeRange_independentWritesMapForm_linkedStaysScalar(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("devices.yaml");
        Preferences src = detached();
        src.putAudioDeviceProfile(sampleProfile());
        stampCurrentContentVersion(src);
        writeDevicesTo(src, store);

        String yaml = Files.readString(store);
        assertTrue(yaml.contains("activeRange: { left: \"DIP 1.7 Vrms\", right: \"DIP 4.5 Vrms\" }"),
                "INDEPENDENT writes the ONE { left, right } active-range map on one line");
        assertTrue(yaml.contains("activeRange: \"direct\""),
                "LINKED keeps the scalar active-range label");
        assertFalse(yaml.contains("activeRangeRight:"),
                "the legacy split activeRangeRight key is never written again");

        // The map form round-trips through the ordinary loader.
        Preferences dst = detached();
        loadDevicesFrom(dst, store);
        DeviceEndpointConfig in = dst.findAudioDeviceProfile("Cosmos ADC").getInput();
        assertEquals("DIP 1.7 Vrms", in.getActiveRange(), "map left -> activeRange field");
        assertEquals("DIP 4.5 Vrms", in.getActiveRangeRight(), "map right -> activeRangeRight field");
        assertEquals("direct", dst.findAudioDeviceProfile("Cosmos ADC").getOutput().getActiveRange());
    }

    @Test
    void writtenStore_startsWithSeedHeaderComment(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("devices.yaml");
        Preferences src = detached();
        src.putAudioDeviceProfile(sampleProfile());
        stampCurrentContentVersion(src);
        writeDevicesTo(src, store);

        // The user store opens with the bundled seed's first comment line, copied
        // dynamically at write time (vocabulary + upgrade documentation).
        String yaml = Files.readString(store);
        assertTrue(yaml.startsWith("# Phonalyser bundled well-known device profiles."),
                "the written store carries the seed's leading header comment");

        // And the commented file still parses and round-trips.
        Preferences dst = detached();
        loadDevicesFrom(dst, store);
        assertNotNull(dst.findAudioDeviceProfile("Cosmos ADC"),
                "the header comment does not disturb the YAML parse");
    }

    @Test
    void calibrationFromDevice_roundTrips_andEmitsOnlyWhenTrue(@TempDir Path dir) {
        Path store = dir.resolve("devices.yaml");
        Preferences src = detached();

        // Input is device-provided (a QA40x); output is an ordinary user endpoint.
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName("QA40x");
        p.getInput().setChannels(DeviceChannelMode.LINKED);
        p.getInput().setCalibrationFromDevice(true);
        DeviceRange in = new DeviceRange();
        in.setLabel("0 dB");
        in.setFsLeft(1.0);
        in.setFsRight(1.0);
        p.getInput().getRanges().add(in);
        p.getInput().setActiveRange("0 dB");
        p.getOutput().setChannels(DeviceChannelMode.LINKED);
        DeviceRange out = new DeviceRange();
        out.setLabel("default");
        out.setFsLeft(2.0);
        out.setFsRight(2.0);
        p.getOutput().getRanges().add(out);
        p.getOutput().setActiveRange("default");
        src.putAudioDeviceProfile(p);
        stampCurrentContentVersion(src);
        writeDevicesTo(src, store);

        // The flag is emitted ONLY on the device-provided (input) endpoint.
        Map<?, ?> root    = readYaml(store);
        Map<?, ?> profile = (Map<?, ?>) ((List<?>) root.get("audioDevices")).get(0);
        Map<?, ?> input   = (Map<?, ?>) profile.get("input");
        Map<?, ?> output  = (Map<?, ?>) profile.get("output");
        assertEquals(Boolean.TRUE, input.get("calibrationFromDevice"),
                "the device-provided endpoint serialises the flag");
        assertFalse(output.containsKey("calibrationFromDevice"),
                "an ordinary endpoint stays clean (no flag key)");

        // And it round-trips back through the store loader.
        Preferences dst = detached();
        loadDevicesFrom(dst, store);
        AudioDeviceProfile back = dst.findAudioDeviceProfile("QA40x");
        assertNotNull(back);
        assertTrue(back.getInput().isCalibrationFromDevice(),  "input flag round-trips true");
        assertFalse(back.getOutput().isCalibrationFromDevice(), "output flag round-trips false");
    }

    /**
     * The WIRE form of a card (net spec 4.3 {@code cards.put} / the content of a
     * {@code cards.list} entry) is the STORE's form: one vocabulary, one reader.
     *
     * <p>That is the whole reason a card can be handed to the machine the device
     * is plugged into and come back describing the same box - a second shape for
     * the wire would drift from this one silently, and the drift would show up as
     * a bench measuring against a range table it never received.
     */
    @Test
    void cardToMap_roundTripsThroughTheSameVocabularyTheFileUses() {
        Preferences p = detached();
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName("Cosmos ADC");
        card.getMatch().add("Cosmos ADC");
        card.getMatch().add("Line (Cosmos ADC)");
        card.getInput().setChannels(DeviceChannelMode.INDEPENDENT);
        card.getInput().getRanges().add(range("default", 1.0, 1.1, true));
        card.getInput().getRanges().add(range("attenuated", 10.0, 11.0, false));
        card.getInput().setActiveRange("default");
        card.getInput().setActiveRangeRight("attenuated");
        card.getOutput().setChannels(DeviceChannelMode.LINKED);
        card.getOutput().getRanges().add(range("default", 2.0, 2.0, true));
        card.getOutput().setActiveRange("default");

        AudioDeviceProfile back = p.cardFromMap(p.cardToMap(card));

        assertNotNull(back);
        assertEquals("Cosmos ADC", back.getName());
        assertEquals(List.of("Cosmos ADC", "Line (Cosmos ADC)"), back.getMatch());
        assertEquals(DeviceChannelMode.INDEPENDENT, back.getInput().getChannels());
        assertEquals(2, back.getInput().getRanges().size());
        assertEquals(1.0, back.getInput().getRanges().get(0).getFsLeft());
        assertEquals(1.1, back.getInput().getRanges().get(0).getFsRight());
        assertTrue(back.getInput().getRanges().get(0).isCalibrated());
        assertFalse(back.getInput().getRanges().get(1).isCalibrated());
        assertEquals("default", back.getInput().getActiveRange());
        assertEquals("attenuated", back.getInput().getActiveRangeRight(),
                "an INDEPENDENT endpoint's two markers travel as the file's own "
                        + "{left,right} pair");
        assertEquals("default", back.getOutput().getActiveRange());
    }

    /** The device-authored display text (the QA40x verbose range labels, quotes
     *  and all) survives BOTH round trips - the file and the wire - and a row
     *  without one stays plain.  Before this travelled, a bench's card showed its
     *  raw range keys on every client, and a start without the analyzer attached
     *  lost the verbose labels locally too. */
    @Test
    void displayLabel_survivesFileAndWireRoundTrips(@TempDir Path dir) {
        String verbose = "0 \"dBV\" real 0 dBFS or -9 dBV";
        AudioDeviceProfile card = sampleProfile();
        card.getInput().getRanges().get(0).setDisplayLabel(verbose);

        Preferences src = detached();
        src.putAudioDeviceProfile(card);
        stampCurrentContentVersion(src);
        Path store = dir.resolve("devices.yaml");
        writeDevicesTo(src, store);
        Preferences dst = detached();
        loadDevicesFrom(dst, store);
        DeviceEndpointConfig in = dst.getAudioDeviceProfiles().get(0).getInput();
        assertEquals(verbose, in.getRanges().get(0).getDisplayLabel(),
                "the file keeps the device-authored text, embedded quotes intact");
        assertNull(in.getRanges().get(1).getDisplayLabel(),
                "a row that never had display text must not grow one");

        Preferences p = detached();
        AudioDeviceProfile back = p.cardFromMap(p.cardToMap(card));
        assertEquals(verbose, back.getInput().getRanges().get(0).getDisplayLabel(),
                "the wire form carries the same field the file does");
        assertNull(back.getInput().getRanges().get(1).getDisplayLabel());
    }

    /** An endpoint with no range row calibrates nothing, so it is left out of the
     *  wire form exactly as the file leaves it out - and a card with a nameless
     *  shape is no card at all. */
    @Test
    void cardToMap_leavesOutAnEndpointWithNoRows() {
        Preferences p = detached();
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName("Input only");
        card.getInput().getRanges().add(range("default", 1.0, 1.0, true));
        card.getInput().setActiveRange("default");

        Map<String, Object> wire = p.cardToMap(card);

        assertTrue(wire.containsKey("input"));
        assertFalse(wire.containsKey("output"), "nothing to say about that direction");
        assertTrue(p.cardFromMap(wire).getOutput().getRanges().isEmpty());
        assertNull(p.cardFromMap(Map.of("match", List.of("x"))),
                "a card with no usable name is refused, as it is on disk");
    }

    private DeviceRange range(String label, double fsLeft, double fsRight,
            boolean calibrated) {
        DeviceRange row = new DeviceRange();
        row.setLabel(label);
        row.setFsLeft(fsLeft);
        row.setFsRight(fsRight);
        row.setCalibrated(calibrated);
        return row;
    }

    @Test
    void normalizeDeviceName_foldsBackendWrappers() {
        Preferences p = detached();
        assertEquals("E1DA Cosmos ADC", p.normalizeDeviceName("Line (E1DA Cosmos ADC)"));
        assertEquals("E1DA Cosmos ADC", p.normalizeDeviceName("Speakers (E1DA Cosmos ADC)"));
        assertEquals("E1DA Cosmos ADC", p.normalizeDeviceName("Microphone (E1DA Cosmos ADC)"));
        assertEquals("E1DA Cosmos ADC", p.normalizeDeviceName("E1DA Cosmos ADC"));
        assertEquals("Cosmos ADC",      p.normalizeDeviceName("Cosmos ADC [plughw:1,0]"));
        assertEquals("Cosmos ADC",      p.normalizeDeviceName("  Cosmos ADC  "));
        assertNull(p.normalizeDeviceName(null));
    }

    @Test
    void resolveDeviceProfile_matchesBySubstring() {
        Preferences p = detached();
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName("E1DA Cosmos ADC Interface");
        profile.getMatch().add("Cosmos ADC");
        p.putAudioDeviceProfile(profile);

        // A match entry that is a case-insensitive substring of the live device
        // name binds it - across the backend name forms.
        assertEquals("E1DA Cosmos ADC Interface",
                p.resolveDeviceProfile("Line (E1DA Cosmos ADC Interface)").getName());
        assertEquals("E1DA Cosmos ADC Interface",
                p.resolveDeviceProfile("Cosmos ADC [plughw:1,0]").getName());

        // A device name that contains no match entry is not resolved.
        assertNull(p.resolveDeviceProfile("Focusrite Scarlett 2i2"));
        // The logical NAME is not a match entry: a name-only overlap never binds.
        assertNull(p.resolveDeviceProfile("Interface"));
    }

    @Test
    void resolveDeviceProfile_longestMatchWins() {
        Preferences p = detached();
        AudioDeviceProfile generic = new AudioDeviceProfile();
        generic.setName("Generic");
        generic.getMatch().add("USB");
        p.putAudioDeviceProfile(generic);
        AudioDeviceProfile specific = new AudioDeviceProfile();
        specific.setName("Cosmos");
        specific.getMatch().add("Cosmos USB Audio");
        p.putAudioDeviceProfile(specific);

        // Both "USB" and "Cosmos USB Audio" are substrings of the device name; the
        // longest (most specific) match entry wins.
        assertEquals("Cosmos", p.resolveDeviceProfile("Line (Cosmos USB Audio)").getName());
        // A device only the generic entry matches still resolves to the generic card.
        assertEquals("Generic", p.resolveDeviceProfile("Some USB Widget").getName());
    }

    @Test
    void storeAdcCalibration_autoCreatesBoundProfile_andSetsGlobalFs() {
        Preferences p = detached();
        p.setBackend(AudioBackendType.WDMKS);
        // A device recognised by no seeded card's match patterns, so this
        // exercises the bare "default"-row fallback (the recognised-card seeding
        // path is covered by DeviceStoreTest).
        p.current().setInputDeviceName("Generic USB Codec");

        p.storeAdcCalibration(2.5);

        // Global scalar took the value.
        assertEquals(2.5, p.getAdcFsVoltageRms(), EPS);

        // A card was auto-created, recognising the exact device name via match.
        AudioDeviceProfile created = p.resolveDeviceProfile("Generic USB Codec");
        assertNotNull(created, "first calibrate must auto-create a bound card");
        assertEquals("Generic USB Codec", created.getName());
        assertEquals(List.of("Generic USB Codec"), created.getMatch());

        DeviceEndpointConfig in = created.getInput();
        assertEquals(DeviceChannelMode.LINKED, in.getChannels());
        assertEquals(1, in.getRanges().size());
        DeviceRange row = in.getRanges().get(0);
        assertEquals("default", row.getLabel());
        assertEquals("default", in.getActiveRange());
        assertEquals(2.5, row.getFsLeft(),  EPS);
        assertEquals(2.5, row.getFsRight(), EPS);

        // A second calibrate on the same device updates the same profile's row.
        p.storeAdcCalibration(3.1);
        assertEquals(1, p.getAudioDeviceProfiles().size(), "no duplicate profile on recalibrate");
        assertEquals(3.1, p.resolveDeviceProfile("Generic USB Codec")
                .getInput().getRanges().get(0).getFsLeft(), EPS);
    }

    @Test
    void storeDacCalibration_storesRmsOnDisk_andSetsGlobalAmpl() {
        Preferences p = detached();
        p.setBackend(AudioBackendType.WDMKS);
        p.current().setOutputDeviceName("E1DA Cosmos DAC");

        double fsAmpl = 2.79351;
        p.storeDacCalibration(fsAmpl);

        // Global scalar took the peak-amplitude value.
        assertEquals(fsAmpl, p.getDacFsVoltageAmpl(), EPS);

        // The stored row is the RMS form (ampl / √2).
        AudioDeviceProfile created = p.resolveDeviceProfile("E1DA Cosmos DAC");
        assertNotNull(created);
        DeviceRange row = created.getOutput().getRanges().get(0);
        assertEquals(fsAmpl / Constants.SQRT2, row.getFsLeft(), EPS);

        // applyOutputDeviceProfile round-trips RMS->ampl back onto the global scalar.
        p.setDacFsVoltageAmpl(1.0);
        p.applyOutputDeviceProfile("E1DA Cosmos DAC");
        assertEquals(fsAmpl, p.getDacFsVoltageAmpl(), EPS);
    }

    @Test
    void applyInputDeviceProfile_unresolved_isLegacyNoOp() {
        Preferences p = detached();
        p.setAdcFsVoltageRms(1.234);
        p.applyInputDeviceProfile("Some Unbound Card");
        assertEquals(1.234, p.getAdcFsVoltageRms(), EPS, "unresolved device must not touch the global FS");
    }

    @Test
    void findAudioDeviceProfile_returnsLiveObject() {
        Preferences p = detached();
        p.putAudioDeviceProfile(sampleProfile());
        AudioDeviceProfile a = p.findAudioDeviceProfile("cosmos adc");  // case-insensitive
        assertNotNull(a);
        AudioDeviceProfile b = p.findAudioDeviceProfile("Cosmos ADC");
        assertSame(a, b, "findAudioDeviceProfile returns the live object");
    }
}
