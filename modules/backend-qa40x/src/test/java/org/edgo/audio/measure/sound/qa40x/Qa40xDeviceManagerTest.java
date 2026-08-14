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

package org.edgo.audio.measure.sound.qa40x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Qa40xDeviceManager} integration: the fixed format set, graceful empty
 * enumeration, the device-card builder (per-channel full-scale derived from
 * {@link Qa40xLevels} + cal-page factors, active-range survival across refresh),
 * and the one-duplex-engine session model against {@link FakeTransport} - the
 * engine survives a capture close/reopen while the generator stays attached and
 * stops only when the last client detaches (doc §10).
 */
class Qa40xDeviceManagerTest {

    private static final int RATE_HZ      = 48_000;
    /** Depth passed through the {@code AudioDeviceManager} open signatures -
     *  the QA40x path is fixed 24-bit and ignores the requested value. */
    private static final int BITS         = 24;
    private static final double TOL       = 1e-9;
    private static final int RECORD_BYTES = 6;
    private static final int ADC_BASE     = 24;
    private static final int DAC_BASE     = 120;
    private static final int RANGE_STRIDE = 12;

    private static final Qa40xDuplexEngine.Sleeper INSTANT = millis -> { };

    /** What {@code DeviceRef.identity()} answers for a ref built by hand here -
     *  the finder's "model @ bus/address", which nothing in this class compares. */
    private static final String QA403_IDENTITY = "QA403 @ bus 1 addr 5";
    private static final String QA402_IDENTITY = "QA402 @ bus 1 addr 5";
    /** Where {@link #analyzerOn} says its analyzer sits. */
    private static final int ATTACHED_BUS = 1;
    private static final int ATTACHED_ADDRESS = 5;
    /** The parked positions, as register codes on the wire: input code 7 is
     *  +42 dBV (the attenuator relay engaged) and output code 0 is −12 dBV. */
    private static final FakeTransport.RegWrite PARKED_INPUT = new FakeTransport.RegWrite(5, 7);
    private static final FakeTransport.RegWrite PARKED_OUTPUT = new FakeTransport.RegWrite(6, 0);

    /** The card name a QA403 session writes: the model name (see
     *  {@code refreshDeviceCard}), which is also what a range change looks the
     *  card up by. */
    private static final String CARD_NAME = "QA403";
    /** A valid input range that is NOT the default the card comes up on, so a
     *  full scale that failed to follow the attenuator is visible. */
    private static final int MOVED_INPUT_DBV = 24;
    private static final int DEFAULT_INPUT_DBV = 0;

    /** Whether the store was already in transient mode before this test - the
     *  process-wide singleton is restored to it afterwards. */
    private boolean storeWasTransient;
    /** The analyzer cards this installation already had, taken out for the
     *  duration and put back afterwards - see {@link #keepTheStoreOffDisk()}. */
    private final List<AudioDeviceProfile> inheritedCards = new ArrayList<>();
    /** The analyzer card CHOICES this installation already had, lifted for the
     *  duration alongside the cards themselves. */
    private final Map<String, String> inheritedBindings = new LinkedHashMap<>();

    /**
     * Keeps the real {@code devices.yaml} out of this suite, and the suite
     * out of it.
     *
     * <p>A range change records the range in force as the card's active row and
     * calls {@link Preferences#saveDevices()}, which is a no-op only in transient
     * mode - and the manager reaches the process-wide store, not a copy.  With the
     * analyzer CLOSED the card is resolved by scanning the store for a QA40x-model
     * card, so a developer who owns a QA402 would have these tests write to that
     * one instead of the QA403 they seed.  Both model cards are therefore lifted
     * out and put back, leaving the singleton exactly as it was found.
     */
    @BeforeEach
    void keepTheStoreOffDisk() {
        Preferences prefs = Preferences.instance();
        storeWasTransient = prefs.isTransientMode();
        prefs.setTransientMode(true);
        inheritedCards.clear();
        inheritedBindings.clear();
        for (Qa40xModel model : Qa40xModel.values()) {
            AudioDeviceProfile inherited = prefs.findAudioDeviceProfile(model.name());
            if (inherited != null) {
                inheritedCards.add(inherited);
                prefs.removeAudioDeviceProfile(model.name());
            }
            // A saved card CHOICE for an analyzer would decide which card a
            // closed-analyzer range write lands in, so it is lifted out with
            // the cards.
            String bound = prefs.boundCardName(model.name());
            if (bound != null) {
                inheritedBindings.put(model.name(), bound);
                prefs.bindDeviceToCard(model.name(), null);
            }
        }
    }

    @AfterEach
    void restoreTheStore() {
        Preferences prefs = Preferences.instance();
        for (Qa40xModel model : Qa40xModel.values()) {
            prefs.removeAudioDeviceProfile(model.name());
            prefs.bindDeviceToCard(model.name(), null);
        }
        // Put the originals back BEFORE transient mode is, so no restore step of
        // this teardown can reach the file.
        for (AudioDeviceProfile inherited : inheritedCards) {
            prefs.putAudioDeviceProfile(inherited);
        }
        for (Map.Entry<String, String> binding : inheritedBindings.entrySet()) {
            prefs.bindDeviceToCard(binding.getKey(), binding.getValue());
        }
        prefs.setTransientMode(storeWasTransient);
    }

    /** A finder that reports no analyzer - the production seam with the device
     *  CLOSED: no {@code libusb}, no transport, and no model. */
    private Qa40xDeviceFinder noAnalyzer() {
        return new Qa40xDeviceFinder() {
            @Override
            public List<Qa40xDevice> list() {
                return List.of();
            }
        };
    }

    /** The other production seam: ONE analyzer attached and CLOSED - the finder
     *  reports it and hands out {@code transport} when something opens it.  What
     *  a host that has just enumerated a bench is looking at. */
    private Qa40xDeviceFinder analyzerOn(FakeTransport transport) {
        return new Qa40xDeviceFinder() {
            @Override
            public List<Qa40xDevice> list() {
                return List.of(new Qa40xDevice(Qa40xModel.QA403, ATTACHED_BUS, ATTACHED_ADDRESS));
            }

            @Override
            public Qa40xTransport open() {
                return transport;
            }
        };
    }

    // --- formats + enumeration -----------------------------------------------

    @Test
    void supportedFormats_areThePerModelRatesStereo24BitLittleEndian() {
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        // The QA403 carries reg-9 code 3, so it adds 384 kHz (doc §4).
        DeviceRef ref = new Qa40xDeviceManager.Qa40xDeviceRef(0, "QA403", Qa40xModel.QA403,
                QA403_IDENTITY);
        for (boolean output : new boolean[] {false, true}) {
            List<AudioFormat> formats = mgr.listSupportedFormats(ref, output);
            assertEquals(4, formats.size());
            int[] expectedRates = {48_000, 96_000, 192_000, 384_000};
            for (int i = 0; i < expectedRates.length; i++) {
                AudioFormat f = formats.get(i);
                assertEquals(expectedRates[i], (int) f.getSampleRate());
                // Advertised depth is the EFFECTIVE 24 bits the recorder delivers
                // (it drops the int32 pad byte - doc §5), so the frame is
                // 24-bit stereo = 6 bytes.
                assertEquals(24, f.getSampleSizeInBits());
                assertEquals(6, f.getFrameSize());
                assertEquals(2, f.getChannels());
                assertFalse(f.isBigEndian(), "QA402/QA403 samples are little-endian (doc §5)");
            }
        }
    }

    @Test
    void supportedFormats_omit384kOnTheQa402() {
        // The QA402 has no reg-9 code 3, so the rate list stops at 192 kHz (doc §4).
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        DeviceRef ref = new Qa40xDeviceManager.Qa40xDeviceRef(0, "QA402", Qa40xModel.QA402,
                QA402_IDENTITY);
        for (boolean output : new boolean[] {false, true}) {
            List<AudioFormat> formats = mgr.listSupportedFormats(ref, output);
            assertEquals(3, formats.size());
            assertEquals(192_000, (int) formats.get(formats.size() - 1).getSampleRate());
        }
    }

    /**
     * The backend reads ITS OWN vocabulary - libusb's error names, its transfer
     * status names and this module's own two prose refusals - into the reason
     * the operator is shown.  Hardware-free by construction: a classification
     * is a pure read of the failure it is handed.
     */
    @Test
    void classifyFailure_readsTheLibusbVocabulary() {
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        assertEquals(DeviceFailureReason.DEVICE_DISCONNECTED, mgr.classifyFailure(
                new IllegalStateException("bulk write failed: LIBUSB_ERROR_NO_DEVICE (-4)")));
        assertEquals(DeviceFailureReason.DEVICE_DISCONNECTED, mgr.classifyFailure(
                new IllegalStateException("the QA40x stopped mid-stream: NO_DEVICE")));
        assertEquals(DeviceFailureReason.DEVICE_NOT_ANSWERING, mgr.classifyFailure(
                new IllegalStateException("the QA40x stopped mid-stream: TIMED_OUT")));
        assertEquals(DeviceFailureReason.DEVICE_NOT_ANSWERING, mgr.classifyFailure(
                new IllegalStateException("registerRead failed: LIBUSB_ERROR_TIMEOUT (-7)")));
        assertEquals(DeviceFailureReason.DEVICE_IN_USE, mgr.classifyFailure(
                new IllegalStateException("libusb_claim_interface failed: LIBUSB_ERROR_BUSY (-6)")));
        assertEquals(DeviceFailureReason.DEVICE_IN_USE, mgr.classifyFailure(
                new IllegalStateException("libusb_open failed: LIBUSB_ERROR_ACCESS (-3)")));
        assertEquals(DeviceFailureReason.DEVICE_NOT_FOUND, mgr.classifyFailure(
                new IllegalStateException("No QA402/QA403 found on USB")));
        assertEquals(DeviceFailureReason.DEVICE_NOT_FOUND, mgr.classifyFailure(
                new IllegalStateException("libusb-1.0 not available - cannot open a QA40x device")));
        // Unmappable stays UNKNOWN, and a missing failure never throws on a
        // path that is already handling one.
        assertEquals(DeviceFailureReason.UNKNOWN, mgr.classifyFailure(
                new IllegalStateException("libusb_alloc_transfer returned null")));
        assertEquals(DeviceFailureReason.UNKNOWN, mgr.classifyFailure(null));
    }

    @Test
    void enumeration_isEmptyWithoutADevice() {
        // Deterministic and hardware-free: the finder is a collaborator, so a
        // stub returning no devices stands in for "nothing attached / no
        // libusb" - the manager must degrade to empty lists, never throwing.
        // Unit tests never touch a physical device; that is what makes this
        // runnable on any CI agent (real USB belongs to the *IT tests).
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(noAnalyzer());
        assertTrue(mgr.listInputDevices().isEmpty());
        assertTrue(mgr.listOutputDevices().isEmpty());
    }

    // --- backend lifecycle ---------------------------------------------------

    /**
     * The host takes the backend up and the analyzer is left safe - even though
     * no session ever opened it.
     *
     * <p>A QA40x's input sensitivity survives the host that set it: the unit can
     * be moved between two machines without losing USB power, so what this
     * process enumerates at start-up may be live at whatever range someone else's
     * session left it at.  {@code shutdown()} cannot answer that case - it parks
     * a session THIS process opened and returns at once when there is none -
     * which is why the lifecycle's opening half exists.
     */
    @Test
    void setup_parksAnAnalyzerNoSessionEverOpened() {
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(analyzerOn(fake));

        assertTrue(mgr.setup(), "an analyzer was there and it was brought up");

        assertTrue(fake.registerWrites.contains(PARKED_INPUT),
                "the input parks at +42 dBV - a bench that comes up at an unknown "
                        + "sensitivity is the hazard this exists for");
        assertTrue(fake.registerWrites.contains(PARKED_OUTPUT), "and the output at −12 dBV");
        assertTrue(fake.ops.contains("close"),
                "and the analyzer is RELEASED again: a host that held it from boot "
                        + "would be what stops it being moved to another machine");
    }

    /** And with no analyzer on the bus it is a silent no-op - the guard rail that
     *  lets every host call this unconditionally, on any machine. */
    @Test
    void setup_isASilentNoOpWithNoAnalyzerAttached() {
        assertFalse(new Qa40xDeviceManager(noAnalyzer()).setup(),
                "nothing attached is not a failure: it answers false and never throws, "
                        + "so a host that always calls it is not a host that always fails");
    }

    /** Idempotent, as the contract promises: the second call finds the analyzer
     *  already released and simply does it again, with nothing left claimed. */
    @Test
    void setup_isIdempotent() {
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(analyzerOn(fake));

        assertTrue(mgr.setup());
        assertTrue(mgr.setup());

        assertEquals(2, Collections.frequency(fake.ops, "close"),
                "each call opened and released once - nothing was left claimed");
    }

    // --- device card ---------------------------------------------------------

    @Test
    void buildProfile_derivesPerChannelFullScaleFromLevelsAndCal() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        AudioDeviceProfile card = mgr.buildProfile("QA403", cal, null);

        assertTrue(card.getMatch().contains("QA403"), "match list carries the model name");

        DeviceEndpointConfig in = card.getInput();
        assertEquals(DeviceChannelMode.LINKED, in.getChannels());
        assertTrue(in.isCalibrationFromDevice());
        assertEquals(8, in.getRanges().size(), "8 input ranges (0..42 dBV)");
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            DeviceRange row = rowByLabel(in, dbv + " dBV");
            assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, false)),
                    row.getFsLeft(), TOL, "input fsLeft at " + dbv + " dBV");
            assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, true)),
                    row.getFsRight(), TOL, "input fsRight at " + dbv + " dBV");
            assertNotEquals(row.getFsLeft(), row.getFsRight(),
                    "per-channel cal makes L/R full-scale asymmetric");
            assertTrue(row.isCalibrated(), "device-owned rows are calibrated (survive seed merge)");
        }

        DeviceEndpointConfig out = card.getOutput();
        assertTrue(out.isCalibrationFromDevice());
        assertEquals(4, out.getRanges().size(), "4 output ranges (-12/-2/+8/+18 dBV)");
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            DeviceRange row = rowByLabel(out, dbv + " dBV");
            assertEquals(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, false)),
                    row.getFsLeft(), TOL, "output fsLeft at " + dbv + " dBV");
            assertEquals(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, true)),
                    row.getFsRight(), TOL, "output fsRight at " + dbv + " dBV");
        }

        // Default active ranges when the card is first created (PyQa40x defaults).
        assertEquals("0 dBV", in.getActiveRange());
        assertEquals("18 dBV", out.getActiveRange());
    }

    @Test
    void buildProfile_preservesUserActiveRangeAcrossRefresh() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        AudioDeviceProfile existing = new AudioDeviceProfile();
        existing.setName("QA403");
        existing.getInput().setActiveRange("24 dBV");    // a valid, non-default input range
        existing.getOutput().setActiveRange("-12 dBV");  // a valid, non-default output range

        AudioDeviceProfile refreshed = mgr.buildProfile("QA403", cal, existing);

        assertEquals("24 dBV", refreshed.getInput().getActiveRange(),
                "the user's input active-range selection survives a device refresh");
        assertEquals("-12 dBV", refreshed.getOutput().getActiveRange(),
                "the user's output active-range selection survives a device refresh");
    }

    /**
     * The range IN FORCE has to reach the card, because everything that resolves
     * a full scale - the dBV axis, the generator's amplitude, and the {@code cal}
     * a net server publishes for its own devices (spec 4.3) - reads the card's
     * ACTIVE row.
     *
     * <p>Before this, {@code setInputRange} moved the register and an in-memory
     * field only: the store went on naming the row the card refresh selected, so
     * anything reading it was told the full scale of a range the analyzer had
     * left.  Locally the ranges dialog happened to commit the same label on OK
     * and hid it; a headless server has no dialog, and its store is the answer
     * every client gets.
     */
    @Test
    void aRangeChangeMovesTheCardsActiveRow_soTheFullScaleFollowsTheAttenuator() {
        Preferences prefs = Preferences.instance();
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        prefs.putAudioDeviceProfile(mgr.buildProfile(CARD_NAME, cal, null));
        DeviceCalibration before = prefs.deviceCalibration(CARD_NAME, true);
        assertEquals(Qa40xLevels.inputFullScaleRmsVolts(DEFAULT_INPUT_DBV,
                cal.adcLinearFactor(DEFAULT_INPUT_DBV, false)), before.fsRmsLeft(), TOL,
                "the card comes up on its default input range");

        mgr.setInputRange(MOVED_INPUT_DBV);

        assertEquals(MOVED_INPUT_DBV + " dBV",
                prefs.findAudioDeviceProfile(CARD_NAME).getInput().getActiveRange(),
                "the attenuator moved, so the card's active row moves with it");
        DeviceCalibration after = prefs.deviceCalibration(CARD_NAME, true);
        assertEquals(Qa40xLevels.inputFullScaleRmsVolts(MOVED_INPUT_DBV,
                cal.adcLinearFactor(MOVED_INPUT_DBV, false)), after.fsRmsLeft(), TOL,
                "and the full scale the store answers is the NEW range's");
        assertNotEquals(before.fsRmsLeft(), after.fsRmsLeft(),
                "a range change that left the full scale where it was would be the "
                        + "silent display lie this guards");
    }

    /**
     * The same, with the analyzer CLOSED - which is the normal state when the
     * write arrives: a range is committed from a Preferences OK, local or across
     * the net, and nothing streams at that moment (the session opens lazily, at
     * the first capture).
     *
     * <p>While a closed manager wrote nothing, the operator's choice was answered
     * "ok", broadcast to every client, and then silently discarded - the next
     * open's {@code refreshDeviceCard} reads the ranges back OUT of the card, so
     * the in-memory field went straight back to the row the card still named.  The
     * second half of this test is that read-back: the refreshed card must still
     * name the NEW range, which is what carries it into the registers.
     *
     * <p>The manager here comes from the FINDER seam, not the open-transport one:
     * that constructor pre-sets the model, which is precisely the state under
     * test being absent.
     */
    @Test
    void aRangeChangeWithTheAnalyzerClosedStillMovesTheCard() {
        Preferences prefs = Preferences.instance();
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager builder = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        prefs.putAudioDeviceProfile(builder.buildProfile(CARD_NAME, cal, null));
        Qa40xDeviceManager closed = new Qa40xDeviceManager(noAnalyzer());

        closed.setInputRange(MOVED_INPUT_DBV);

        AudioDeviceProfile card = prefs.findAudioDeviceProfile(CARD_NAME);
        assertEquals(MOVED_INPUT_DBV + " dBV", card.getInput().getActiveRange(),
                "a closed analyzer is the normal state for a range write, and the "
                        + "card is where the choice has to land");

        // What the next ensureOpen() does with it: refreshDeviceCard rebuilds the
        // card from the calibration page and reads the ranges back out of it.
        AudioDeviceProfile refreshed = builder.buildProfile(CARD_NAME, cal, card);
        assertEquals(MOVED_INPUT_DBV + " dBV", refreshed.getInput().getActiveRange(),
                "the refresh preserves it rather than reverting it");
        assertEquals(MOVED_INPUT_DBV, Qa40xProtocol.rangeDbv(
                refreshed.getInput().getActiveRange(),
                Qa40xProtocol.inputRangeDbvValues(), DEFAULT_INPUT_DBV),
                "so the registers the next open writes are the NEW range's");
    }

    /**
     * On the closed-analyzer path, WHICH card a range write lands in is the
     * operator's saved choice, not the first QA40x-model card the store happens
     * to hold.
     *
     * <p>A bench that has had both analyzers plugged into it keeps a card for
     * each, and the scan this replaces would record the attenuator position of the
     * one on the table into whichever card came first - a coin toss that shows up
     * later as a full scale nobody can explain.  Both cards are seeded here, the
     * binding names the SECOND, and the write has to follow the binding.
     */
    @Test
    void aClosedAnalyzerRecordsTheRangeInTheCardTheOperatorChose() {
        Preferences prefs = Preferences.instance();
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager builder = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        prefs.putAudioDeviceProfile(builder.buildProfile(Qa40xModel.QA402.name(), cal, null));
        prefs.putAudioDeviceProfile(builder.buildProfile(CARD_NAME, cal, null));
        prefs.bindDeviceToCard(CARD_NAME, CARD_NAME);

        new Qa40xDeviceManager(noAnalyzer()).setInputRange(MOVED_INPUT_DBV);

        assertEquals(MOVED_INPUT_DBV + " dBV",
                prefs.findAudioDeviceProfile(CARD_NAME).getInput().getActiveRange(),
                "the chosen card is the one the attenuator position belongs to");
        assertEquals(DEFAULT_INPUT_DBV + " dBV",
                prefs.findAudioDeviceProfile(Qa40xModel.QA402.name()).getInput().getActiveRange(),
                "and the analyzer that is NOT on the table keeps its own range - "
                        + "which the first-match scan could not promise");
    }

    @Test
    void aRangeChangeWithNoCardInTheStoreWritesNothing() {
        Preferences prefs = Preferences.instance();
        prefs.removeAudioDeviceProfile(CARD_NAME);
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        mgr.setInputRange(MOVED_INPUT_DBV);

        assertNull(prefs.findAudioDeviceProfile(CARD_NAME),
                "a card is created from the device's own calibration page when the "
                        + "session opens; inventing one here would write a card with "
                        + "no calibration in it");
    }

    // --- session lifecycle ---------------------------------------------------

    @Test
    void engineSurvivesCaptureReopenWhileGeneratorAttached_stopsOnLastDetach() throws Exception {
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);

        Qa40xGenerator gen = (Qa40xGenerator) mgr.openPlayback(
                new Qa40xDeviceManager.Qa40xDeviceRef(0, "QA403", Qa40xModel.QA403, QA403_IDENTITY),
                RATE_HZ, BITS, 0);
        gen.open();
        SignalGenerator sig = new SignalGenerator(GenSignalForm.SINE, 1_000.0, RATE_HZ, 0.1, 1.0);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(1);
        Thread player = new Thread(() -> gen.play(sig, stop, ready), "qa40x-test-play");
        player.setDaemon(true);
        player.start();

        assertTrue(ready.await(2, TimeUnit.SECONDS), "generator primes and signals ready");
        assertEquals(0, fake.cancelAllCount, "stream is up (no teardown yet)");

        // Capture attaches to the RUNNING stream, then closes - the generator is
        // still attached, so the engine must NOT tear down.
        AudioCapture rec = mgr.openCapture(null, RATE_HZ, BITS);
        rec.open();
        rec.startRecording();
        rec.stopRecording();
        rec.close();
        assertEquals(0, fake.cancelAllCount, "capture close leaves the generator's stream running");

        // Re-open capture on the same still-running engine - still no restart.
        AudioCapture rec2 = mgr.openCapture(null, RATE_HZ, BITS);
        rec2.open();
        rec2.startRecording();
        assertEquals(0, fake.cancelAllCount, "reopen attaches live, no restart");
        rec2.stopRecording();
        rec2.close();
        assertEquals(0, fake.cancelAllCount);

        // The generator is the last client - detaching it stops the stream.
        stop.set(true);
        player.join(2_000);
        assertEquals(1, fake.cancelAllCount, "last client detach tears the session down");
    }

    /**
     * A detach that FAULTED leaves the lane to be torn down again.
     *
     * <p>The desktop's play thread ends in {@code Closeables.closeQuietly(lane)}
     * exactly so a stop that died halfway is attempted once more.  That retry
     * reaches {@code Qa40xGenerator.close()}, and the flag it consults used to be
     * cleared BEFORE the detach that faulted - so the retry saw a lane it thought
     * was already down and returned without touching the engine.  The flag has to
     * record what was achieved, or the second attempt is not an attempt at all.
     */
    @Test
    void aDetachThatFaultedLeavesTheLaneForTheOwnersRetry() throws Exception {
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);
        Qa40xGenerator gen = (Qa40xGenerator) mgr.openPlayback(null, RATE_HZ, BITS, 0);
        gen.open();
        SignalGenerator sig = new SignalGenerator(GenSignalForm.SINE, 1_000.0, RATE_HZ, 0.1, 1.0);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> onThePlayThread = new AtomicReference<>();
        Thread player = new Thread(() -> gen.play(sig, stop, ready), "qa40x-test-play");
        player.setUncaughtExceptionHandler((thread, fault) -> onThePlayThread.set(fault));
        player.setDaemon(true);
        player.start();
        assertTrue(ready.await(2, TimeUnit.SECONDS), "generator primes and signals ready");

        // The analyzer is pulled mid-tone: the teardown's stop register faults the
        // way a native call faults, and the stop dies halfway through.
        fake.failNextWithNativeError(1);
        stop.set(true);
        player.join(2_000);

        assertInstanceOf(Error.class, onThePlayThread.get(),
                "the stop really did die on the play thread - the case the desktop's "
                        + "closeQuietly() retry exists for");
        assertTrue(gen.isAttached(),
                "so the lane is still the engine's: cleared on ATTEMPT rather than on "
                        + "achievement, this flag made the retry a silent no-op");

        gen.close();                    // the retry the play thread's finally makes

        assertFalse(gen.isAttached(), "and the retry really does reach the engine");
    }

    @Test
    void rangeChangeWhileStreaming_restartsSession() throws Exception {
        // The card's active-range radios drive the engine range via the manager;
        // a range change on a running session is a full stop + start (doc §10).
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);
        AudioCapture rec = mgr.openCapture(null, RATE_HZ, BITS);
        rec.open();
        rec.startRecording();                 // engine streaming

        mgr.setInputRange(24);
        assertEquals(1, fake.cancelAllCount, "input range change restarts the running session");
        mgr.setOutputRange(-12);
        assertEquals(2, fake.cancelAllCount, "output range change restarts the running session");

        rec.stopRecording();
        rec.close();
    }

    @Test
    void generatorUsesPeakConvention_noExtraSqrt2Factor() {
        // A full-scale-peak sine (Vrms = dacFs/√2 -> peak amplitude 1.0) must reach
        // ~±MAXINT on the wire.  A spurious RMS/√2 factor would cap it near
        // 0.707·MAXINT - this pins the peak convention (doc §6 / Qa40xLevels).
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);
        Qa40xGenerator gen = (Qa40xGenerator) mgr.openPlayback(null, RATE_HZ, BITS, 0);
        gen.open();

        SignalGenerator sig = new SignalGenerator(GenSignalForm.SINE, 1_000.0, RATE_HZ, 1.0, Constants.SQRT2);
        AtomicBoolean alreadyStopped = new AtomicBoolean(true);
        gen.play(sig, alreadyStopped, new CountDownLatch(1));   // attaches, primes, exits immediately

        assertFalse(fake.submittedWrites.isEmpty(), "priming submitted at least one DAC chunk");
        int peak = 0;
        byte[] chunk = fake.submittedWrites.get(0);
        for (int f = 0; f * 8 + 8 <= chunk.length; f++) {
            int left = leInt(chunk, f * 8 + 4);   // frame = [R LE][L LE]; left is the second word
            peak = Math.max(peak, Math.abs(left));
        }
        assertTrue(peak > (int) (0.95 * Qa40xLevels.MAXINT),
                "full-scale peak sine must reach ~MAXINT, not ~0.707·MAXINT (peak != √2·RMS): " + peak);
    }

    // --- helpers -------------------------------------------------------------

    private int leInt(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | b[o + 3] << 24;
    }

    private DeviceRange rowByLabel(DeviceEndpointConfig ep, String label) {
        for (DeviceRange r : ep.getRanges()) {
            if (label.equals(r.getLabel())) {
                return r;
            }
        }
        throw new IllegalArgumentException("no range row labelled " + label);
    }

    /** A 512-byte cal page with a distinct, non-zero dB per range/channel (right =
     *  -left), so parsed L/R factors differ - mirrors {@code Qa40xCalibrationTest}. */
    private byte[] syntheticBlob() {
        byte[] blob = new byte[Qa40xCalibration.CAL_PAGE_BYTES];
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            int leftOffset = ADC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 1.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(1.0f + code));
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            int leftOffset = DAC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 2.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(2.0f + code));
        }
        return blob;
    }

    private void putRecord(byte[] blob, int offset, short level, float db) {
        ByteBuffer record = ByteBuffer.wrap(blob, offset, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.putShort(level);
        record.putFloat(db);
    }
}
