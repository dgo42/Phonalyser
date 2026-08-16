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

package org.edgo.audio.measure.gui.preferences;

import java.util.List;

import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The rule the Audio tab's card sections skip a rebuild on, proved without a
 * display: two renders that would look the same have the same identity, and
 * every value a row or the combo actually SHOWS moves it.
 *
 * <p>Both halves matter.  A value that is rendered but missing from the identity
 * would leave the section showing a stale table; a value that is NOT rendered but
 * folded in would spend a full teardown + relayout of the range table on
 * something invisible - which is the cost this identity exists to avoid.
 *
 * <p>The dialog is built with no shell: its constructor only stores the parent,
 * and the identity is a pure function of its arguments - no widget, no field, no
 * working copy.
 */
class CardRenderIdentityTest {

    private static final boolean INPUT  = true;
    private static final boolean OUTPUT = false;
    private static final String DEVICE       = "Cosmos ADC (Analog)";
    private static final String OTHER_DEVICE = "I2SoverUSB (Analog)";
    private static final String CARD_A = "Cosmos ADC";
    private static final String CARD_B = "QA403";
    private static final String ROW_1 = "0 dBV";
    private static final String ROW_2 = "-20 dBV";
    private static final double FS_VRMS = 1.9;

    private final PreferencesDialog dialog = new PreferencesDialog(null);

    @Test
    void twoRendersOfTheSameValuesHaveTheSameIdentity() {
        assertEquals(baseline(), baseline(),
                "the same device, the same card list and the same rows - nothing to rebuild");
    }

    @Test
    void thePickIsMatchedByNameRatherThanByObject() {
        // The dialog hands in a LIVE profile as the pick while the list holds the
        // store's defensive COPIES, so a by-object comparison would find no pick
        // at all and drop the whole range table out of the identity.
        String byCopy = dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA(), cardB()), cardA());

        List<AudioDeviceProfile> listed = List.of(cardA(), cardB());
        String byObject = dialog.cardRenderIdentity(INPUT, DEVICE, listed, listed.get(0));

        assertEquals(byObject, byCopy);
    }

    @Test
    void theDeviceMovesIt() {
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, OTHER_DEVICE, List.of(cardA(), cardB()), cardA()));
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, null, List.of(cardA(), cardB()), cardA()));
    }

    @Test
    void theCardListMovesIt() {
        AudioDeviceProfile third = card(CARD_B + " Mk2", ROW_1);

        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA(), cardB(), third), cardA()),
                "a card added to the combo is a card the operator can now choose");
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA()), cardA()),
                "and one that left it is one they cannot");
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardB(), cardA()), cardA()),
                "the combo shows them in list order, so the order is part of the render");
    }

    @Test
    void thePickMovesIt() {
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA(), cardB()), cardB()),
                "another card is preselected, and its rows are the ones the table shows");
        assertNotEquals(baseline(),
                dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA(), cardB()), null),
                "no card resolved at all - the combo clears and the table goes away");
    }

    @Test
    void everyValueARowRendersMovesIt() {
        AudioDeviceProfile renamed = cardA();
        renamed.getInput().getRanges().get(1).setLabel("-10 dBV");
        assertNotEquals(baseline(), identityOf(renamed), "a row's label is on screen");

        AudioDeviceProfile added = cardA();
        added.getInput().getRanges().add(range("-40 dBV"));
        assertNotEquals(baseline(), identityOf(added), "so is a row that was added");

        AudioDeviceProfile moved = cardA();
        moved.getInput().setActiveRange(ROW_2);
        assertNotEquals(baseline(), identityOf(moved), "the active radio sits on another row");

        AudioDeviceProfile movedRight = cardA();
        movedRight.getInput().setActiveRangeRight(ROW_2);
        assertNotEquals(baseline(), identityOf(movedRight),
                "an INDEPENDENT endpoint's right column has its own marker");

        AudioDeviceProfile independent = cardA();
        independent.getInput().setChannels(DeviceChannelMode.INDEPENDENT);
        assertNotEquals(baseline(), identityOf(independent),
                "the channel mode decides whether a row is one group or two");

        AudioDeviceProfile fromDevice = cardA();
        fromDevice.getInput().setCalibrationFromDevice(true);
        assertNotEquals(baseline(), identityOf(fromDevice),
                "a device-provided endpoint renders read-only labels and no add / remove");

        AudioDeviceProfile displayed = cardA();
        displayed.getInput().getRanges().get(0).setDisplayLabel("0 dBV real -9 dBFS");
        assertNotEquals(baseline(), identityOf(displayed),
                "the DISPLAYED label is what the row's field carries");
    }

    @Test
    void aRowOfTheOtherDirectionDoesNotMoveIt() {
        // The input section renders the input endpoint and nothing else, so the
        // output rows of the same card are not its business.
        AudioDeviceProfile outputChanged = cardA();
        outputChanged.getOutput().getRanges().add(range("Line out"));

        assertEquals(baseline(), identityOf(outputChanged));
        assertNotEquals(
                dialog.cardRenderIdentity(OUTPUT, DEVICE, List.of(cardA(), cardB()), cardA()),
                dialog.cardRenderIdentity(OUTPUT, DEVICE,
                        List.of(outputChanged, cardB()), outputChanged),
                "and the output section renders exactly that row");
    }

    @Test
    void aCalibrationWriteDoesNotMoveIt() {
        // No row shows a full scale - the crosshair Calibrate flows own it - so a
        // calibration write must not buy a teardown and relayout of the table.
        AudioDeviceProfile calibrated = cardA();
        DeviceRange row = calibrated.getInput().getRanges().get(0);
        row.setFsLeft(FS_VRMS * 2);
        row.setFsRight(FS_VRMS * 2);
        row.setCalibrated(true);

        assertEquals(baseline(), identityOf(calibrated));
    }

    /** The reference render: two cards in the combo, the first one preselected. */
    private String baseline() {
        return dialog.cardRenderIdentity(INPUT, DEVICE, List.of(cardA(), cardB()), cardA());
    }

    /** The same render with {@code card} in the first slot, list and pick alike -
     *  which is how the section resolves it. */
    private String identityOf(AudioDeviceProfile card) {
        return dialog.cardRenderIdentity(INPUT, DEVICE, List.of(card, cardB()), card);
    }

    private AudioDeviceProfile cardA() {
        return card(CARD_A, ROW_1, ROW_2);
    }

    private AudioDeviceProfile cardB() {
        return card(CARD_B, ROW_1);
    }

    private AudioDeviceProfile card(String name, String... rowLabels) {
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName(name);
        card.getMatch().add(DEVICE);
        DeviceEndpointConfig endpoint = card.getInput();
        for (String label : rowLabels) {
            endpoint.getRanges().add(range(label));
        }
        endpoint.setActiveRange(rowLabels[0]);
        return card;
    }

    private DeviceRange range(String label) {
        DeviceRange row = new DeviceRange();
        row.setLabel(label);
        row.setFsLeft(FS_VRMS);
        row.setFsRight(FS_VRMS);
        return row;
    }
}
