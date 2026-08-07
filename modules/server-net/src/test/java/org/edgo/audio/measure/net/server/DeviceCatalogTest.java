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

package org.edgo.audio.measure.net.server;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code devices.list} payload of spec 4.3, read off a bench that is not
 * there: {@link StubDeviceManager} is reached through the real
 * {@code AudioBackend}, registered like any backend module, so what is asserted
 * here is the production enumeration path with a known answer at the end of it.
 */
class DeviceCatalogTest {

    private static final String CLIENT_NAME = "Developer's laptop";
    private static final int HELLO_ID = 1;
    private static final int FIRST = 0;
    private static final int SECOND = 1;
    private static final int THIRD = 2;
    /** One backend, two input devices and one output - see the stub. */
    private static final int DEVICE_COUNT = 3;
    private static final int INPUT_FORMATS = 2;
    private static final int OUTPUT_FORMATS = 4;
    /** The card a calibration test binds to the first input device.  Its two
     *  full scales are deliberately unequal, so a payload that crossed the
     *  channels fails on the values rather than passing on symmetry. */
    private static final String CARD_NAME = "Bench ADC";
    private static final double ADC_FS_LEFT = 1.234;
    private static final double ADC_FS_RIGHT = 2.345;
    private static final double EPS = 1e-9;
    /** A SECOND card recognising the same device - the two-analyzer bench a
     *  binding exists to disambiguate.  Its full scale is nowhere near the first
     *  card's, so which one is in force is unmistakable. */
    private static final String OTHER_CARD_NAME = "Bench ADC (the other one)";
    private static final double OTHER_FS = 8.75;
    /** The two rows of a multi-range card - an attenuator's positions, which is
     *  what a card's CONTENT has to carry for a client to draw them. */
    private static final String ACTIVE_RANGE = "default";
    private static final String OTHER_RANGE = "attenuated";

    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    /** This bench's device cards - empty unless a test binds one, so {@code cal}
     *  is exactly what the test put there and never the developer's own store. */
    private final StubCardStore cards = new StubCardStore();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), cards.getPrefs());

    @Test
    void everyServedBackendIsListedWithItsDevicesAndTheirFormatsInlined() {
        JsonNode backends = catalog.scan();

        assertEquals(1, backends.size());
        assertEquals(AudioBackendType.QA40X.name(),
                backends.get(FIRST).path(NetFields.BACKEND).asText(),
                "spec 4.3: the backend is the server-side enum NAME");
        JsonNode devices = devices();
        assertEquals(DEVICE_COUNT, devices.size());

        JsonNode first = devices.get(FIRST);
        assertEquals(StubDeviceManager.FIRST_INPUT, first.path(NetFields.NAME).asText());
        assertEquals(0, first.path(NetFields.INDEX).asInt());
        assertTrue(first.path(NetFields.DESCRIPTION).isTextual());
        assertTrue(first.path(NetFields.VENDOR).isTextual());
        assertEquals(INPUT_FORMATS, first.path(NetFields.FORMATS).size(),
                "the formats are inlined, so a client needs no per-device round-trip");
        JsonNode format = first.path(NetFields.FORMATS).get(FIRST);
        assertEquals(StubDeviceManager.RATE_48K, format.path(NetFields.RATE).asInt());
        assertEquals(StubDeviceManager.BITS_24, format.path(NetFields.BITS).asInt());
        assertEquals(2, format.path(NetFields.CHANNELS).asInt());
    }

    @Test
    void theFirstHotPlugLookNoticesTheBenchAndTheNextOneNoticesNothing() {
        assertTrue(catalog.rescan(),
                "the first look is a change by definition - before it the server had "
                        + "told nobody anything about the bench");

        assertFalse(catalog.rescan(),
                "spec 4.3 sends ev.devices.changed ON hot-plug, so a rescan that "
                        + "found the same devices must stay quiet: a broadcast every "
                        + "tick would have every client resetting its combos twice a "
                        + "second for nothing");
        assertFalse(catalog.rescan());
    }

    /**
     * The hot-plug nobody was told about.
     *
     * <p>An analyzer is unplugged and plugged back in.  It comes back at index 0,
     * under the same model name, in the same direction - every field a device ref
     * carries is what it was - so a comparison drawn on those alone reported "no
     * change", and the Windows server logged nothing at all across a detach while
     * Linux and macOS broadcast it.  (Windows is where it matters most: the finder
     * deliberately keeps a device that answers ACCESS/BUSY, which is this
     * process's own stale claim, so the analyzer does not even leave the list.)
     * What DOES move is the USB address, which the ref now carries as its
     * identity - and one broadcast is all it takes for every client to re-read a
     * list its refs have gone stale against.
     */
    @Test
    void anAnalyzerThatCameBackAtAnotherUsbAddressIsAHotPlug() {
        catalog.rescan();                       // the first look is a change by definition
        assertFalse(catalog.rescan());

        manager().reattachAtAnotherAddress();   // same index, same name, new address

        assertTrue(catalog.rescan(),
                "the bench moved and every client has to hear about it - a device "
                        + "ref that survived the unplug names hardware that is no "
                        + "longer the hardware it was opened on");
        assertFalse(catalog.rescan(), "and it settles again at the new address");
    }

    /**
     * The macOS bench bug: PortAudio enumerates ONCE, at start-up, so the list the
     * server rescans is the snapshot taken then - an unplugged interface is still
     * in it, nothing looks changed, and no client is ever told.  (The bench log
     * said it plainly: the CoreAudio HAL warned per tick that it knows no device
     * of that name while the served list kept offering it.)  The tick now asks the
     * backend whether its list went stale and rebuilds it when it did, which is
     * the only way the device can leave the list at all.
     */
    @Test
    void aDeviceThatLeftTheBenchIsRebuiltOutOfTheListAndBroadcast() {
        catalog.rescan();                       // the first look is a change by definition
        assertFalse(catalog.rescan());
        int rebuilds = manager().getRefreshCount();

        manager().unplug(StubDeviceManager.SECOND_INPUT);

        assertTrue(catalog.rescan(),
                "the device is gone and every client has to hear about it - a "
                        + "capture still streaming from it is ending itself in the "
                        + "backend meanwhile, which is the other half of the loss");
        assertEquals(rebuilds + 1, manager().getRefreshCount(),
                "the stale list is what hid the unplug, so exactly one rebuild "
                        + "answers it");
        assertEquals(DEVICE_COUNT - 1, devices().size());
        assertNull(deviceNamed(StubDeviceManager.SECOND_INPUT),
                "and the device the bench no longer has is no longer offered");
        assertFalse(catalog.rescan(), "and the bench settles again");
    }

    @Test
    void aDeviceThatCameBackIsServableAgainWithoutARestart() {
        manager().unplug(StubDeviceManager.SECOND_INPUT);
        catalog.rescan();

        manager().replugAll();

        assertTrue(catalog.rescan(), "it is back, and that is a hot-plug too");
        assertNotNull(deviceNamed(StubDeviceManager.SECOND_INPUT));
        assertNotNull(catalog.resolve(new DeviceLock(AudioBackendType.QA40X, SECOND, true),
                StubDeviceManager.SECOND_INPUT),
                "and it resolves - which is what an open on it needs, and what "
                        + "used to take a server restart");
    }

    @Test
    void aHotPlugLookThatFoundNothingWrongRebuildsNothing() {
        catalog.rescan();
        int rebuilds = manager().getRefreshCount();

        catalog.rescan();
        catalog.rescan();

        assertEquals(rebuilds, manager().getRefreshCount(),
                "a rebuild re-initialises a native library and refuses while any "
                        + "stream is open - spending one twice a second on a bench "
                        + "where nothing moved is not a hot-plug look, it is a "
                        + "hazard");
    }

    @Test
    void aDeviceIsListedPerDirectionWithTheIndexThatDirectionUses() {
        JsonNode devices = devices();

        assertTrue(devices.get(FIRST).path(NetFields.INPUT).asBoolean());
        assertFalse(devices.get(FIRST).path(NetFields.OUTPUT).asBoolean());
        assertEquals(1, devices.get(SECOND).path(NetFields.INDEX).asInt(),
                "the second capture device is slot 1 of the INPUT list");
        assertTrue(devices.get(THIRD).path(NetFields.OUTPUT).asBoolean());
        assertEquals(0, devices.get(THIRD).path(NetFields.INDEX).asInt(),
                "and the playback device is slot 0 of the OUTPUT list - the index "
                        + "spaces are per direction, which is why the entry says "
                        + "which list its index belongs to");
    }

    @Test
    void onlyADirectionThatOffersAChoiceOfSampleWidthsClaimsOne() {
        JsonNode devices = devices();

        assertFalse(devices.get(FIRST).path(NetFields.HAS_BIT_DEPTH).asBoolean(),
                "the capture path delivers one width, so there is nothing to pick");
        assertEquals(OUTPUT_FORMATS, devices.get(THIRD).path(NetFields.FORMATS).size());
        assertTrue(devices.get(THIRD).path(NetFields.HAS_BIT_DEPTH).asBoolean(),
                "the output offers two widths - that IS the selectable depth");
    }

    @Test
    void aFreeDeviceCarriesAJsonNullAndALockedOneNamesItsHolder() {
        assertTrue(devices().get(FIRST).path(NetFields.LOCK).isNull(),
                "spec 4.3: lock is null when the device is free");

        ClientSession holder = greetedSession();
        locks.acquire(new DeviceLock(AudioBackendType.QA40X, 0, true), holder);

        assertEquals(CLIENT_NAME,
                devices().get(FIRST).path(NetFields.LOCK).path(NetFields.BY).asText());
    }

    @Test
    void theLockOverlayIsRebuiltWithoutReEnumeratingTheHardware() {
        catalog.scan();
        ClientSession holder = greetedSession();

        locks.acquire(new DeviceLock(AudioBackendType.QA40X, 0, true), holder);

        JsonNode devices = catalog.lastScan().get(FIRST).path(NetFields.DEVICES);
        assertEquals(DEVICE_COUNT, devices.size(), "the devices are the ones last enumerated");
        assertEquals(CLIENT_NAME, devices.get(FIRST).path(NetFields.LOCK)
                .path(NetFields.BY).asText(), "but the lock state is current");
    }

    @Test
    void aLockChangeBeforeAnyEnumerationTellsNothingRatherThanScanning() {
        JsonNode backends = catalog.lastScan();

        assertEquals(0, backends.size(),
                "the broadcast lands on threads no connection owns - the keepalive "
                        + "scheduler declaring a client dead, the transport's close "
                        + "callback - so an empty list says 'nothing about the bench "
                        + "yet' where a full scan would stall every other session");
    }

    @Test
    void aDeviceThisServerHasNoCardForCarriesANullCalibration() {
        assertTrue(devices().get(FIRST).path(NetFields.CAL).isNull(),
                "spec 4.3: cal is null when the server has no card, and the client "
                        + "then falls back to its own defaults - a missing field or a "
                        + "zero would both read as a full scale of nothing");
    }

    @Test
    void aCardedDeviceCarriesTheServersOwnFullScalesPerDirection() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);

        JsonNode cal = devices().get(FIRST).path(NetFields.CAL);

        assertEquals(ADC_FS_LEFT, cal.path(NetFields.FS_RMS_LEFT).asDouble(), EPS);
        assertEquals(ADC_FS_RIGHT, cal.path(NetFields.FS_RMS_RIGHT).asDouble(), EPS,
                "the two channels come from the active row, unswapped");
        assertTrue(devices().get(THIRD).path(NetFields.CAL).isNull(),
                "an INPUT card says nothing about the output direction - spec 4.3 "
                        + "keys cal on the device AND the direction");
    }

    /**
     * A card the operator calibrated, and a device it does NOT recognise: the
     * bench answers {@code cal: null} and every client falls back to a default of
     * its own, so the same device reads a different level on each of them.
     *
     * <p>The resolution is a substring test AGAINST THE DEVICE NAME
     * ({@code AudioDeviceProfile.matchStrength}), and a sound card's name is what
     * its host API calls it - on Linux {@code "CB5 [plughw:1,1]"}.  The
     * manufacturer's own words for the same box ({@code "CUBILUX CB5"}) live in
     * the DESCRIPTION and nowhere else, so a card recognised by them matches
     * nothing, silently: nothing fails, the wire simply carries no calibration.
     *
     * <p>This test states the rule rather than asserting a preference - what a
     * card is allowed to be recognised by is a decision, not a guess.
     */
    @Test
    void aCardRecognisedByWordsTheDeviceNameDoesNotCarryCalibratesNothing() {
        cards.card(CARD_NAME, "CUBILUX CB5", true, ADC_FS_LEFT, ADC_FS_RIGHT, false);

        assertNull(catalog.calibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                "CB5 [plughw:1,1]"),
                "the card names the box, the device names the ALSA endpoint, and the "
                        + "resolution only ever looks inside the latter");
        assertNull(catalog.boundCard("CB5 [plughw:1,1]"),
                "so the client is told of no card either - the chooser shows nothing "
                        + "for a device this bench is in fact calibrated for");
    }

    /** The same card, recognised by something the device name DOES carry: the
     *  chain works end to end, which is what makes the case above a matching
     *  fault and not a calibration one. */
    @Test
    void aCardRecognisedByWordsInTheDeviceNameCalibratesIt() {
        cards.card(CARD_NAME, "CB5", true, ADC_FS_LEFT, ADC_FS_RIGHT, false);

        assertEquals(ADC_FS_LEFT,
                catalog.calibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                        "CB5 [plughw:1,1]").fsRmsLeft(), EPS);
        assertEquals(CARD_NAME, catalog.boundCard("CB5 [plughw:1,1]"));
    }

    @Test
    void aCardWhoseRowWasNeverCalibratedIsNoCalibrationAtAll() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, 0.0, 0.0, false);

        assertTrue(devices().get(FIRST).path(NetFields.CAL).isNull(),
                "a full scale is the divisor every level is computed against, so a "
                        + "zero is not a quiet device - publishing it would hand every "
                        + "client an infinity to measure with.  An uncalibrated row is "
                        + "an uncalibrated device, which is exactly the cal:null an "
                        + "unbound device gets");
        assertNull(catalog.calibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT),
                "and the lane opener reads the same answer, from the same resolution");
    }

    @Test
    void oneUncalibratedChannelIsEnoughToRefuseThePair() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT, 0.0, false);

        assertTrue(devices().get(FIRST).path(NetFields.CAL).isNull(),
                "the right lane's scale is fsLeft/fsRight - a zero there is an "
                        + "infinite ratio, which drives that side into the clipper");
    }

    @Test
    void theCalibrationOverlayIsRebuiltWithoutReEnumeratingTheHardware() {
        catalog.scan();

        catalog.storeCalibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, ADC_FS_LEFT, ADC_FS_RIGHT);

        JsonNode cal = catalog.lastScan().get(FIRST).path(NetFields.DEVICES).get(FIRST)
                .path(NetFields.CAL);
        assertEquals(ADC_FS_LEFT, cal.path(NetFields.FS_RMS_LEFT).asDouble(), EPS,
                "the ev.devices.changed payload a calibration write broadcasts must "
                        + "carry what was just written - it re-reads the card at build "
                        + "time, exactly as it re-reads the lock overlay, and touches "
                        + "no hardware to do it");
        assertEquals(ADC_FS_RIGHT, cal.path(NetFields.FS_RMS_RIGHT).asDouble(), EPS);
    }

    // -------------------------------------------------------------------------
    // The card BINDING - spec 4.3 `card` / `device.setCard` (which card a
    // device uses is the user's choice, kept where the device lives).
    // -------------------------------------------------------------------------

    @Test
    void aDeviceNobodyHasChosenACardForCarriesANullBinding() {
        assertTrue(devices().get(FIRST).path(NetFields.CARD).isNull(),
                "spec 4.3: card is null when nothing is bound, and the server falls "
                        + "back to its name-match rule");
    }

    @Test
    void anAcceptedBindingIsWhatEveryLaterPayloadReports() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);

        assertTrue(catalog.storeCard(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, CARD_NAME));

        assertEquals(CARD_NAME, devices().get(FIRST).path(NetFields.CARD).asText(),
                "the choice is the server's from then on, and every client reads it "
                        + "off the same payload the calibration comes on");
        assertEquals(CARD_NAME, catalog.boundCard(StubDeviceManager.FIRST_INPUT));
    }

    @Test
    void aBindingMayOnlyNameACardTheServerHas() {
        assertFalse(catalog.storeCard(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, "No Such Card"),
                "spec 4.3 answers BAD_REQUEST - a typo that bound a device to a card "
                        + "that does not exist would quietly uncalibrate it");
        assertNull(catalog.boundCard(StubDeviceManager.FIRST_INPUT),
                "and nothing is recorded");
    }

    @Test
    void anEmptyCardUnbindsTheDevice() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);
        DeviceLock device = new DeviceLock(AudioBackendType.QA40X, 0, true);
        catalog.storeCard(device, StubDeviceManager.FIRST_INPUT, CARD_NAME);

        assertTrue(catalog.storeCard(device, StubDeviceManager.FIRST_INPUT, null));

        assertNull(cards.getPrefs().boundCardName(StubDeviceManager.FIRST_INPUT),
                "null unbinds - the BINDING is gone");
        assertEquals(CARD_NAME, catalog.boundCard(StubDeviceManager.FIRST_INPUT),
                "and the device is back under the name-match rule, which recognises "
                        + "the card again - the wire reports the card IN FORCE, so a "
                        + "chooser shows the truth instead of an empty combo");
        assertEquals(CARD_NAME, devices().get(FIRST).path(NetFields.CARD).asText());
    }

    /** The case the message exists for.  A bench that has had both analyzers
     *  plugged into it holds a card for each, both of them device-calibrated, and
     *  only the operator knows which is on the table - so unlike a calibration
     *  WRITE, a binding is accepted here. */
    @Test
    void aDeviceCalibratedCardIsStillBindable() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, true);

        assertTrue(catalog.storeCard(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, CARD_NAME),
                "binding CHOOSES a card, it writes no values into one - the refusal "
                        + "device.setCalibration makes is about a different act");
    }

    /** The binding is authoritative over the match rule, which is the whole point:
     *  two analyzer cards can both recognise the same device name, and the longest
     *  match is a coin toss the operator cannot influence. */
    @Test
    void theBoundCardWinsOverACardThatMerelyMatchesTheName() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);
        cards.card(OTHER_CARD_NAME, StubDeviceManager.FIRST_INPUT, true, OTHER_FS,
                OTHER_FS, false);

        catalog.storeCard(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, CARD_NAME);

        JsonNode cal = devices().get(FIRST).path(NetFields.CAL);
        assertEquals(ADC_FS_LEFT, cal.path(NetFields.FS_RMS_LEFT).asDouble(), EPS,
                "the cal in force follows the BOUND card, not the one the name "
                        + "resolution happens to land on");
    }

    /** Reading the chooser is not choosing.  A client fills its card combo from
     *  {@code cards.list} every time the dialog shows a bench device, and an
     *  operator who then cancels - or never touches the combo - must leave the
     *  bench exactly as they found it; only {@code device.setCard} binds. */
    @Test
    void listingTheCardsBindsNothing() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);

        catalog.cards();

        assertNull(cards.getPrefs().boundCardName(StubDeviceManager.FIRST_INPUT),
                "no BINDING appeared from a read");
        assertEquals(CARD_NAME, catalog.boundCard(StubDeviceManager.FIRST_INPUT),
                "and the card in force is still the match rule's answer, exactly as "
                        + "before the read");
    }

    @Test
    void theCardListOffersEveryCardThatCanServeTheDirection() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);
        cards.card(OTHER_CARD_NAME, "some output", false, OTHER_FS, OTHER_FS, false);

        List<Map<String, Object>> offered = catalog.cards();

        assertEquals(2, offered.size(), "cards.list is the whole store by name");
        assertEquals(CARD_NAME, offered.get(FIRST).get(NetFields.NAME));
        assertEquals(Boolean.TRUE, offered.get(FIRST).get(NetFields.INPUT));
        assertEquals(Boolean.FALSE, offered.get(FIRST).get(NetFields.OUTPUT),
                "an input-only card cannot calibrate an output, so the chooser must "
                        + "not offer it there");
        assertEquals(Boolean.TRUE, offered.get(SECOND).get(NetFields.OUTPUT));
    }

    /**
     * Every device says whether its full scales are the DEVICE's own, so
     * a client can show them read-only instead of offering an edit this server
     * could only answer {@code BAD_REQUEST} to.
     *
     * <p>Per direction, like everything else about a card: the same analyzer can
     * own its input calibration and leave the output to the operator.
     */
    @Test
    void everyDeviceSaysWhetherItsFullScalesAreItsOwn() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, true);

        JsonNode devices = devices();

        assertTrue(devices.get(FIRST).path(NetFields.CAL_FROM_DEVICE).asBoolean(),
                "the card says this INPUT's values came from the device");
        assertFalse(devices.get(THIRD).path(NetFields.CAL_FROM_DEVICE).asBoolean(),
                "while the same card's OUTPUT endpoint is an ordinary one - the "
                        + "flag is per direction, so an analyzer may own its input "
                        + "calibration and leave the output to the operator");
    }

    @Test
    void anOrdinaryCardLeavesItsDeviceCalibratable() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, false);

        JsonNode entry = devices().get(FIRST);

        assertTrue(entry.has(NetFields.CAL_FROM_DEVICE),
                "plain false, never an omission: 'editable' must be what the server "
                        + "SAYS, not what a missing field is read as");
        assertFalse(entry.path(NetFields.CAL_FROM_DEVICE).asBoolean());
    }

    /** v1.1: each listed card carries its CONTENT, which is what lets a client
     *  draw the ranges table behind a choice and name a row for
     *  {@code device.setActiveRange} - a name and two booleans could do neither. */
    @Test
    void eachListedCardCarriesItsWholeContent() {
        cards.rangedCard(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ACTIVE_RANGE,
                ADC_FS_LEFT, OTHER_RANGE, OTHER_FS);

        Object content = catalog.cards().get(FIRST).get(NetFields.CONTENT);

        assertTrue(content instanceof Map, "the card, in the store's own vocabulary");
        AudioDeviceProfile read = cards.getPrefs().cardFromMap((Map<?, ?>) content);
        assertNotNull(read);
        assertEquals(CARD_NAME, read.getName());
        assertEquals(2, read.getInput().getRanges().size(), "both rows");
        assertEquals(ACTIVE_RANGE, read.getInput().getActiveRange(),
                "and which of them is in force");
        assertEquals(ADC_FS_LEFT, read.getInput().getRanges().get(FIRST).getFsLeft(), EPS);
    }

    /**
     * The chooser's direction booleans and the {@code cal} predicate are NOT the
     * same test, deliberately: a direction is OFFERED when it has a row at all,
     * while {@code cal} additionally requires both full scales to be above zero.
     *
     * <p>So a card can honestly be offered for a direction and still leave it
     * {@code cal: null} once bound - the spec's "a bound card that lacks usable
     * rows for one direction leaves that direction uncalibrated" case.  Hiding
     * every uncalibrated card from the chooser would hide exactly the cards an
     * operator opens the chooser to calibrate.
     */
    @Test
    void aCardMayBeOfferedForADirectionAndStillCalibrateNothingThere() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, 0.0, 0.0, false);

        assertEquals(Boolean.TRUE, catalog.cards().get(FIRST).get(NetFields.INPUT),
                "it has an input row, so the chooser offers it there");
        assertNull(catalog.calibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT),
                "and that row is uncalibrated, which is no calibration at all");
    }

    @Test
    void aCardWhoseCalibrationComesFromTheDeviceRefusesToBeWritten() {
        cards.card(CARD_NAME, StubDeviceManager.FIRST_INPUT, true, ADC_FS_LEFT,
                ADC_FS_RIGHT, true);

        assertFalse(catalog.storeCalibration(new DeviceLock(AudioBackendType.QA40X, 0, true),
                StubDeviceManager.FIRST_INPUT, 9.0, 9.0),
                "a QA40x reads its full scales out of its own EEPROM: they are not a "
                        + "client's to write (spec 4.3)");
        JsonNode cal = devices().get(FIRST).path(NetFields.CAL);
        assertEquals(ADC_FS_LEFT, cal.path(NetFields.FS_RMS_LEFT).asDouble(), EPS,
                "and the card is left exactly as the device said");
    }

    @Test
    void aNameThatNoLongerBelongsToThatIndexIsStale() {
        NetException refused = assertThrows(NetException.class, () -> catalog.resolve(
                new DeviceLock(AudioBackendType.QA40X, 0, true), "Some other card"));

        assertEquals(ErrorCode.DEVICE_STALE, refused.getCode(),
                "spec 4.3: the index moved, so the client must re-list");
        assertTrue(refused.getMessage().contains(StubDeviceManager.FIRST_INPUT),
                "and the message says what is there instead");
    }

    @Test
    void anIndexPastTheEndOfTheListIsStale() {
        NetException refused = assertThrows(NetException.class, () -> catalog.resolve(
                new DeviceLock(AudioBackendType.QA40X, 7, true), StubDeviceManager.FIRST_INPUT));

        assertEquals(ErrorCode.DEVICE_STALE, refused.getCode());
    }

    @Test
    void aBackendThisServerDoesNotServeIsStale() {
        NetException refused = assertThrows(NetException.class, () -> catalog.resolve(
                new DeviceLock(AudioBackendType.JAVASOUND, 0, true),
                StubDeviceManager.FIRST_INPUT));

        assertEquals(ErrorCode.DEVICE_STALE, refused.getCode(),
                "asking a backend that is not on this host must not reach its manager");
    }

    @Test
    void aRefWithoutANameCannotBeValidatedAndIsABadRequest() {
        NetException refused = assertThrows(NetException.class, () -> catalog.resolve(
                new DeviceLock(AudioBackendType.QA40X, 0, true), null));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    /** The device array of the one served backend, freshly enumerated. */
    private JsonNode devices() {
        return catalog.scan().get(FIRST).path(NetFields.DEVICES);
    }

    /** The served entry for one device name, or null when the bench no longer
     *  offers it. */
    private JsonNode deviceNamed(String name) {
        for (JsonNode device : devices()) {
            if (name.equals(device.path(NetFields.NAME).asText())) {
                return device;
            }
        }
        return null;
    }

    /** The bench is the process-wide one every test class shares, so whatever a
     *  test unplugged goes back on it here - and the list with it. */
    @AfterEach
    void replugTheBench() {
        manager().replugAll();
    }

    /** This bench, as the real backend registry hands it out. */
    private StubDeviceManager manager() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }

    /** A session far enough into its handshake to have the name a lock overlay
     *  quotes. */
    private ClientSession greetedSession() {
        FakeChannel channel = new FakeChannel();
        Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
        CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(), catalog,
                codec, channel, new FakeWorker(), qa40x);
        GeneratorSession generator = new GeneratorSession(AudioBackend.instance(), catalog,
                captures, new FileStore(), qa40x, codec, channel, new FakeWorker(),
                new FakeTicker(), () -> 0L);
        ClientSession session = new ClientSession(new ServerConfig(new String[0]), locks,
                qa40x, catalog, captures, generator,
                new Qa40xSession(AudioBackend.instance(), codec,
                        List.of(AudioBackendType.QA40X)), codec, channel,
                new FakeTicker(), new FakeWorker());
        session.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.NAME, CLIENT_NAME));
        return session;
    }
}
