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

package org.edgo.audio.measure.gui.sound;

import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card chooser's staging rule: a pick in the Preferences dialog reaches the
 * bench on OK and at no other moment - and, since v1.1, what else may reach it
 * there: a whole card ({@code cards.put}) and a moved active range
 * ({@code device.setActiveRange}).
 *
 * <p>Nothing edited in the Preferences dialog takes effect before OK.  That rule
 * binds hardest here of anywhere in that dialog - every other staged edit lives
 * in a working copy this process drops on Cancel, but a card binding is a write
 * to ANOTHER machine, where it changes which calibration is in force for every
 * client and cannot be taken back by closing a window.
 *
 * <p>Which is why these tests drive a RECORDING bench
 * ({@link RecordingRemoteBackendUi}): "nothing was sent" is the assertion that
 * matters, and it can only be made against something that would have noticed.
 */
class BenchCardsTest {

    private static final String SERVER_ID = "bench-1";
    private static final String DEVICE_NAME = "QA403";
    private static final String CHOSEN_CARD = "QA403";
    private static final String OTHER_CARD = "QA402";
    private static final String RANGE_LABEL = "default";
    private static final String OTHER_RANGE = "0 dBV";
    private static final int DEVICE_INDEX = 2;
    private static final double FS_LEFT = 1.9;
    private static final double FS_RIGHT = 2.1;

    private final BackendKey bench = BackendKey.of(SERVER_ID, AudioBackendType.QA40X);
    private final RecordingRemoteBackendUi remote =
            (RecordingRemoteBackendUi) RemoteBackendRegistry.instance().getUi();

    @BeforeEach
    void armTheBench() {
        remote.reset();
    }

    @AfterEach
    void disarmTheBench() {
        remote.reset();
    }

    // -- staging -------------------------------------------------------------

    @Test
    void aPickSendsNothing() {
        Preferences prefs = detached();
        BenchCards cards = cards(prefs);

        cards.stage(bench, true, DEVICE_NAME, CHOSEN_CARD);

        assertTrue(remote.getSent().isEmpty(),
                "the pick is staged - the bench must not hear about it until OK");
        assertEquals(CHOSEN_CARD, cards.stagedCard(bench, DEVICE_NAME),
                "though the chooser shows it while the dialog lives");
        assertNull(prefs.boundCardName(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME)),
                "and nothing is recorded locally either - a mirror of a binding the "
                        + "bench never took would show a choice in force nowhere");
    }

    /** Cancel: the working copy - and this staging with it - is dropped.  What has
     *  to be true is that dropping it is ENOUGH, i.e. that no request ever left
     *  the process on its own. */
    @Test
    void aPickThenCancelLeavesTheBenchUntouched() {
        Preferences prefs = detached();
        BenchCards cancelled = cards(prefs);
        cancelled.stage(bench, true, DEVICE_NAME, CHOSEN_CARD);

        // The dialog closes; the next open builds a fresh chooser over the store.
        BenchCards reopened = cards(prefs);

        assertTrue(remote.getSent().isEmpty(), "no request was ever sent");
        assertNull(reopened.stagedCard(bench, DEVICE_NAME));
        assertTrue(reopened.commitStagedBindings(bench, List.of(ref()), List.of()).isEmpty(),
                "nothing is left to commit, so the next OK sends nothing either");
        assertTrue(remote.getSent().isEmpty());
        assertNull(prefs.boundCardName(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME)));
    }

    /**
     * Spec 4.3's "one box, one card": a device listed under ONE name in
     * both directions - the QA40x, the very device {@code device.setCard} exists
     * for - has ONE binding on the server, keyed on that name.  Two combos must
     * therefore stage one pick between them, or the commit would send two writes
     * and the second would silently win.
     */
    @Test
    void oneBoxStagesOneCardHoweverManyCombosPickIt() {
        BenchCards cards = cards(detached());
        cards.stage(bench, true, DEVICE_NAME, CHOSEN_CARD);
        cards.stage(bench, false, DEVICE_NAME, OTHER_CARD);
        cards.stage(BackendKey.of("bench-2", AudioBackendType.QA40X), true, DEVICE_NAME,
                OTHER_CARD);

        assertEquals(OTHER_CARD, cards.stagedCard(bench, DEVICE_NAME),
                "the later pick replaces the earlier one - it is the same choice, "
                        + "made twice");
        List<String> refused =
                cards.commitStagedBindings(bench, List.of(ref()), List.of(outputRef()));

        assertTrue(refused.isEmpty());
        assertEquals(1, remote.sentOf(MessageType.DEVICE_SET_CARD.getWire()).size(),
                "ONE device.setCard for the box, not one per direction - and the "
                        + "other bench's pick is not this bench's to send");
        assertEquals(OTHER_CARD,
                remote.onlySent().fields().get(NetFields.CARD));
    }

    // -- the commit ----------------------------------------------------------

    @Test
    void anAcceptedBindingIsSentAsSetCardUnderTheLockAndThenMirrored() {
        Preferences prefs = detached();

        assertTrue(cards(prefs).bind(bench, ref(), CHOSEN_CARD));

        RecordingRemoteBackendUi.Sent sent = remote.onlySent();
        assertEquals(MessageType.DEVICE_SET_CARD.getWire(), sent.request());
        assertTrue(sent.locked(), "spec 4.3 requires the device lock for it");
        assertEquals(AudioBackendType.QA40X.name(), sent.fields().get(NetFields.BACKEND));
        assertEquals(DEVICE_INDEX, sent.fields().get(NetFields.INDEX));
        assertEquals(Boolean.TRUE, sent.fields().get(NetFields.INPUT));
        assertEquals(DEVICE_NAME, sent.fields().get(NetFields.NAME));
        assertEquals(CHOSEN_CARD, sent.fields().get(NetFields.CARD));
        assertEquals(CHOSEN_CARD,
                prefs.boundCardName(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME)),
                "the local mirror follows the bench's acceptance");
    }

    /**
     * A refusal that REACHES the bench and comes back "no": the request went out,
     * and the mirror still must not be written.  The order is the whole point - a
     * mirror written before the answer would remember a choice that is in force on
     * no machine, and the chooser would show it as the bound card for ever.
     */
    @Test
    void aRefusedBindingIsSentAndLeavesNoLocalRecord() {
        Preferences prefs = detached();
        remote.setAnswer(null);                       // the bench refuses

        assertFalse(cards(prefs).bind(bench, ref(), CHOSEN_CARD));

        assertEquals(MessageType.DEVICE_SET_CARD.getWire(), remote.onlySent().request(),
                "it really was attempted - this is not the no-bench shortcut");
        assertNull(prefs.boundCardName(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME)),
                "and the mirror is written only after acceptance");
    }

    @Test
    void unbindingSendsANullCard() {
        assertTrue(cards(detached()).bind(bench, ref(), null));

        assertNull(remote.onlySent().fields().get(NetFields.CARD),
                "spec 4.3: null unbinds, back to the bench's name-match rule");
    }

    // -- handing a whole card up (spec 4.3 cards.put) ------------------------

    /** The create half: the card the operator authored goes up WHOLE and
     *  the device is bound to it - two messages, in that order, because a card
     *  nothing is bound to is in force nowhere. */
    @Test
    void aCardIsPutOnTheBenchWithoutALockAndThenBound() {
        assertEquals(BenchCards.Copied.CARD,
                cards(detached()).createAndBind(bench, ref(), card(CHOSEN_CARD)));

        List<RecordingRemoteBackendUi.Sent> sent = remote.getSent();
        assertEquals(2, sent.size());
        assertEquals(MessageType.CARDS_PUT.getWire(), sent.get(0).request());
        assertFalse(sent.get(0).locked(),
                "spec 4.3: nothing is bound to the new card, so nothing it says is "
                        + "in force and no lock is needed");
        assertEquals(MessageType.DEVICE_SET_CARD.getWire(), sent.get(1).request());
        assertTrue(sent.get(1).locked(), "the BINDING is what changes a measurement");
    }

    /** The card travels whole - the range table, the channel mode and the match
     *  list - in the same vocabulary the bench's own store uses, so what arrives
     *  is the card that left and not a pair of numbers on a bare row. */
    @Test
    void theWholeCardTravelsInTheStoresOwnVocabulary() {
        Preferences prefs = detached();
        assertTrue(cards(prefs).create(bench, card(CHOSEN_CARD)));

        Object content = remote.onlySent().fields().get(NetFields.CONTENT);
        assertTrue(content instanceof Map, "cards.put carries the card as its content");
        AudioDeviceProfile arrived = prefs.cardFromMap((Map<?, ?>) content);
        assertNotNull(arrived);
        assertEquals(CHOSEN_CARD, arrived.getName());
        assertEquals(List.of(DEVICE_NAME), arrived.getMatch());
        assertEquals(DeviceChannelMode.LINKED, arrived.getInput().getChannels());
        assertEquals(2, arrived.getInput().getRanges().size(),
                "both range rows - a multi-range card that arrived with one row "
                        + "would describe a different box");
        assertEquals(RANGE_LABEL, arrived.getInput().getActiveRange());
        assertEquals(FS_LEFT, arrived.getInput().getRanges().get(0).getFsLeft());
        assertEquals(FS_RIGHT, arrived.getInput().getRanges().get(0).getFsRight());
    }

    /** The local card goes up whole and the device is bound to it, and
     *  the device's own name is added to what the bench recognises it by. */
    @Test
    void propagatingALocalCardSendsItWholeAndBindsIt() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(OTHER_CARD));

        assertEquals(BenchCards.Copied.CARD,
                cards(prefs).propagateLocalCard(bench, ref(), DEVICE_NAME, true));

        assertEquals(MessageType.CARDS_PUT.getWire(), remote.getSent().get(0).request());
        assertEquals(OTHER_CARD,
                remote.getSent().get(1).fields().get(NetFields.CARD),
                "and the device is bound to the card that just arrived");
        assertNotNull(prefs.findAudioDeviceProfile(OTHER_CARD),
                "the local card stays exactly where it was - this is a copy UP");
    }

    /** The bench already has a card of that name (a create-only verb refuses it),
     *  so the values go up on their own - the device ends up calibrated either
     *  way, which is the point of the fallback. */
    @Test
    void aBenchThatAlreadyHasTheNameGetsTheValuesInstead() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(OTHER_CARD));
        remote.refuse(MessageType.CARDS_PUT.getWire());

        assertEquals(BenchCards.Copied.VALUES_ONLY,
                cards(prefs).propagateLocalCard(bench, ref(), DEVICE_NAME, true));

        assertEquals(1, remote.sentOf(MessageType.CARDS_PUT.getWire()).size());
        List<RecordingRemoteBackendUi.Sent> values =
                remote.sentOf(MessageType.DEVICE_SET_CALIBRATION.getWire());
        assertEquals(1, values.size(), "the pre-v1.1 path is the fallback, not a "
                + "second card under a name the operator never chose");
        assertEquals(FS_LEFT, values.get(0).fields().get(NetFields.FS_RMS_LEFT));
        assertEquals(FS_RIGHT, values.get(0).fields().get(NetFields.FS_RMS_RIGHT));
        assertTrue(values.get(0).locked(), "spec 4.3 requires the device lock for it");
    }

    /**
     * The create lands and the BINDING is refused (another client holds the
     * device): a real card is now on the bench that nothing uses, and saying
     * "nothing was copied" would send the operator round the same flow - where
     * the create then fails on the name it made itself.
     */
    @Test
    void aCardThatLandedButCouldNotBeBoundIsReportedAsItself() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(OTHER_CARD));
        remote.refuse(MessageType.DEVICE_SET_CARD.getWire());

        assertEquals(BenchCards.Copied.CARD_UNBOUND,
                cards(prefs).propagateLocalCard(bench, ref(), DEVICE_NAME, true));

        assertEquals(1, remote.sentOf(MessageType.CARDS_PUT.getWire()).size());
        assertEquals(1, remote.sentOf(MessageType.DEVICE_SET_CARD.getWire()).size());
        assertTrue(remote.sentOf(MessageType.DEVICE_SET_CALIBRATION.getWire()).isEmpty(),
                "the card IS on the bench with its values in it - there is nothing "
                        + "left for the values-only fallback to add");
        assertNull(prefs.boundCardName(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME)),
                "and no local mirror of a binding that did not happen");
    }

    /** Nothing worth sending: no local card recognises the device.  The bench must
     *  not be handed an invented calibration. */
    @Test
    void nothingIsSentWhenThisMachineHasNoCardForTheDevice() {
        assertEquals(BenchCards.Copied.NOTHING_TO_COPY,
                cards(detached()).propagateLocalCard(bench, ref(), DEVICE_NAME, true));

        assertTrue(remote.getSent().isEmpty());
    }

    // -- the active range of a bench card (spec 4.3 device.setActiveRange) ----

    @Test
    void aStagedRangeMoveReachesTheBenchOnlyOnTheCommit() {
        BenchCards cards = cards(detached());
        cards.stageActiveRange(bench, true, DEVICE_NAME, OTHER_RANGE, null);

        assertTrue(remote.getSent().isEmpty(), "staged, like every other edit");

        assertTrue(cards.commitStagedRanges(bench, List.of(ref()), List.of()).isEmpty());

        RecordingRemoteBackendUi.Sent sent = remote.onlySent();
        assertEquals(MessageType.DEVICE_SET_ACTIVE_RANGE.getWire(), sent.request());
        assertTrue(sent.locked(), "the active row IS the calibration in force");
        assertEquals(DEVICE_NAME, sent.fields().get(NetFields.NAME));
        assertEquals(OTHER_RANGE, sent.fields().get(NetFields.RANGE));
        assertEquals(NetFields.BOTH, sent.fields().get(NetFields.CHANNEL),
                "a card whose channels share one marker moves both at once");
    }

    @Test
    void anIndependentCardsRightChannelMovesOnItsOwn() {
        BenchCards cards = cards(detached());
        cards.stageActiveRange(bench, true, DEVICE_NAME, OTHER_RANGE, Channel.R);
        cards.commitStagedRanges(bench, List.of(ref()), List.of());

        assertEquals(NetFields.RIGHT, remote.onlySent().fields().get(NetFields.CHANNEL));
    }

    // -- the chooser's list --------------------------------------------------

    @Test
    void theCardListKeepsOnlyTheCardsThatServeTheDirection() {
        remote.setAnswer(Map.of(NetFields.CARDS, List.of(
                Map.of(NetFields.NAME, CHOSEN_CARD, NetFields.INPUT, true,
                        NetFields.OUTPUT, false),
                Map.of(NetFields.NAME, OTHER_CARD, NetFields.INPUT, false,
                        NetFields.OUTPUT, true))));
        BenchCards cards = cards(detached());

        assertEquals(List.of(CHOSEN_CARD), cards.list(bench, true),
                "a card with no row for the direction cannot calibrate anything "
                        + "there, so the chooser must not offer it");
        assertEquals(MessageType.CARDS_LIST.getWire(), remote.onlySent().request());
        assertFalse(remote.onlySent().locked(), "cards.list takes no lock");
    }

    /** The content the list carries is what the ranges table draws from - the
     *  BENCH's own card, rows and active marker included. */
    @Test
    void theListedCardsContentIsKeptForTheRangesTable() {
        Preferences prefs = detached();
        remote.setAnswer(Map.of(NetFields.CARDS, List.of(
                Map.of(NetFields.NAME, CHOSEN_CARD, NetFields.INPUT, true,
                        NetFields.OUTPUT, false,
                        NetFields.CONTENT, prefs.cardToMap(card(CHOSEN_CARD))))));
        BenchCards cards = cards(prefs);
        cards.list(bench, true);

        AudioDeviceProfile listed = cards.card(bench, CHOSEN_CARD);
        assertNotNull(listed, "the card the bench described, by name");
        assertEquals(2, listed.getInput().getRanges().size());
        assertEquals(RANGE_LABEL, listed.getInput().getActiveRange());
        assertNull(cards.card(bench, "a card this bench never listed"));
    }

    @Test
    void aBenchThatWillNotAnswerOffersNoCards() {
        remote.setAnswer(null);

        assertTrue(cards(detached()).list(bench, true).isEmpty(),
                "showing a stale list from another server would be worse than none");
    }

    // -- which binding the chooser shows -------------------------------------

    @Test
    void theBenchsOwnAnswerBeatsTheLocalMirror() {
        Preferences prefs = detached();
        prefs.bindDeviceToCard(prefs.deviceBindingKey(SERVER_ID, DEVICE_NAME), OTHER_CARD);

        assertEquals(CHOSEN_CARD,
                cards(prefs).boundCard(bench, ref(CHOSEN_CARD), DEVICE_NAME),
                "another operator may have re-bound the device since; the bench is "
                        + "authoritative and the mirror only covers the gap before its "
                        + "catalogue has been read");
        assertEquals(OTHER_CARD, cards(prefs).boundCard(bench, ref(), DEVICE_NAME),
                "and the mirror is what answers when the bench sent no card");
    }

    private BenchCards cards(Preferences prefs) {
        return new BenchCards(prefs, new CalibrationStore(prefs));
    }

    private DeviceRef ref() {
        return ref(null);
    }

    private DeviceRef ref(String boundCard) {
        return new StubRef(DEVICE_INDEX, DEVICE_NAME, boundCard, true);
    }

    private DeviceRef outputRef() {
        return new StubRef(DEVICE_INDEX, DEVICE_NAME, null, false);
    }

    /** A two-row LINKED card that recognises the device - the shape a bare
     *  values-only copy could not describe. */
    private AudioDeviceProfile card(String name) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(name);
        profile.getMatch().add(DEVICE_NAME);
        for (DeviceEndpointConfig endpoint
                : List.of(profile.getInput(), profile.getOutput())) {
            endpoint.setChannels(DeviceChannelMode.LINKED);
            endpoint.getRanges().add(row(RANGE_LABEL, FS_LEFT, FS_RIGHT));
            endpoint.getRanges().add(row(OTHER_RANGE, FS_LEFT * 10, FS_RIGHT * 10));
            endpoint.setActiveRange(RANGE_LABEL);
        }
        return profile;
    }

    private DeviceRange row(String label, double fsLeft, double fsRight) {
        DeviceRange row = new DeviceRange();
        row.setLabel(label);
        row.setFsLeft(fsLeft);
        row.setFsRight(fsRight);
        row.setCalibrated(true);
        return row;
    }

    /** A detached, transient Preferences with the inherited profiles AND card
     *  choices cleared - {@code copyForDialog} carries both, and a developer's own
     *  binding would decide what these tests resolve. */
    private Preferences detached() {
        Preferences prefs = Preferences.instance().copyForDialog();
        prefs.setTransientMode(true);
        for (AudioDeviceProfile inherited : prefs.getAudioDeviceProfiles()) {
            prefs.removeAudioDeviceProfile(inherited.getName());
        }
        for (String key : prefs.getDeviceCardBindings().keySet()) {
            prefs.bindDeviceToCard(key, null);
        }
        return prefs;
    }

    /** A device handle with what the binding seam reads: index, name, direction,
     *  and the card the bench says is bound to it. */
    private record StubRef(int index, String name, String boundCard, boolean isInput)
            implements DeviceRef {

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public String vendor() {
            return "stub";
        }

        @Override
        public AudioBackendType backend() {
            return AudioBackendType.NET;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
