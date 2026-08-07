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

package org.edgo.audio.measure.gui.backend.qa40x;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.qa40x.Qa40xProtocol;

import static org.edgo.audio.measure.enums.AudioBackendType.QA40X;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analyzer's own ranges, mirrored from a bench into the card the Preferences
 * dialog is about to draw - and into the copy that dialog is actually reading.
 *
 * <p>The bench failure this pins is one bug on top of another.  The dialog hid the
 * range table for every remote selection, so a QA403 on a server showed a card
 * and not one attenuator position; and underneath that, the sync wrote its card
 * into the LIVE preferences singleton while the dialog reads a snapshot taken
 * when it opened - so even with the table shown, the card would not have been in
 * it - not even after an OK and a reopen of it.  The second half is what
 * this test is about: the working copy is where the card has to land, and the
 * live store must stay untouched until OK commits it.
 */
class Qa40xCardSyncTest {

    /** The analyzer on a server - QA40X by type, reached through the net carrier. */
    private static final BackendKey REMOTE_QA40X = BackendKey.of("b7e0-bench-uuid", QA40X);
    /** Positions the analyzer really has (doc §6), and not the ones a fresh card
     *  starts on, so a sync that never happened cannot pass for one that did. */
    private static final int ACTIVE_INPUT_DBV = 42;
    private static final int ACTIVE_OUTPUT_DBV = -12;
    /** Per-channel cal factors, unequal so a payload that crossed or dropped a
     *  channel fails on the values rather than passing. */
    private static final double LEFT_FACTOR = 1.02;
    private static final double RIGHT_FACTOR = 0.97;

    private final StubRemoteBench bench =
            (StubRemoteBench) RemoteBackendRegistry.instance().getUi();

    private Preferences live;

    @BeforeEach
    void setUp() {
        live = Preferences.instance();
        // The sync used to write through this singleton; transient mode keeps the
        // real devices.yaml out of it either way.
        live.setTransientMode(true);
        bench.clear();
        bench.answer(MessageType.QA40X_CALIBRATION.getWire(),
                Map.of(NetFields.ADC, factorRows(Qa40xProtocol.inputRangeDbvValues()),
                        NetFields.DAC, factorRows(Qa40xProtocol.outputRangeDbvValues())));
        bench.answer(MessageType.QA40X_RANGES.getWire(),
                Map.of(NetFields.ACTIVE_INPUT_DBV, ACTIVE_INPUT_DBV,
                        NetFields.ACTIVE_OUTPUT_DBV, ACTIVE_OUTPUT_DBV));
    }

    @AfterEach
    void tearDown() {
        live.removeAudioDeviceProfile(StubNetBackend.DEVICE_NAME);
        bench.clear();
    }

    @Test
    void theBenchesRangesLandInTheDialogsWorkingCopyAndNotInTheLiveStore() {
        Preferences edit = live.copyForDialog();

        new Qa40xSettingsUi().onSelected(REMOTE_QA40X, edit);

        AudioDeviceProfile card = edit.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME);
        assertNotNull(card, "the bench described its analyzer and no card was built from "
                + "it - the range table has nothing to draw and the full-scale math "
                + "nothing to resolve");
        assertEquals(Qa40xProtocol.inputRangeDbvValues().length,
                card.getInput().getRanges().size(),
                "one row per attenuator position, exactly as the local analyzer's card");
        assertEquals(Qa40xProtocol.outputRangeDbvValues().length,
                card.getOutput().getRanges().size());
        assertTrue(card.getInput().isCalibrationFromDevice(),
                "the values are the ANALYZER's - neither side may overwrite them");
        assertEquals(Qa40xProtocol.rangeLabel(ACTIVE_INPUT_DBV),
                card.getInput().getActiveRange(),
                "the card must say what the bench is actually running under, or the "
                        + "dialog shows an attenuator setting the hardware is not on");
        assertEquals(Qa40xProtocol.rangeLabel(ACTIVE_OUTPUT_DBV),
                card.getOutput().getActiveRange());
        DeviceRange firstInput = card.getInput().getRanges().get(0);
        assertNotEquals(firstInput.getFsLeft(), firstInput.getFsRight(),
                "the bench's per-channel factors travelled - a card that lost them "
                        + "measures both lanes against the same full scale");

        assertNull(live.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME),
                "and nothing reached the live store: the dialog commits its working copy "
                        + "on OK and drops it on Cancel, and a card written straight into "
                        + "the singleton is both invisible to the open dialog and immune "
                        + "to its Cancel");
    }

    /**
     * The rendered card is the dialog's to SHOW and nobody's to
     * KEEP.  It goes into the working copy so the ranges table can draw the
     * analyzer's positions, and it comes back out again when the operator presses
     * OK - because that copy is committed to this installation's
     * {@code devices.yaml}, and a bench analyzer's full-scale table has no
     * business there: the server owns its analyzer's card, and a local one of the
     * same name is exactly the collision every other path refuses.
     */
    @Test
    void theRenderedBenchCardLeavesTheWorkingCopyBeforeOkCommitsIt() {
        Preferences edit = live.copyForDialog();
        Qa40xSettingsUi panel = new Qa40xSettingsUi();
        panel.beginEdit();
        panel.onSelected(REMOTE_QA40X, edit);
        assertNotNull(edit.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME),
                "the table draws from it while the dialog is open");

        panel.commitEdit();

        assertNull(edit.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME),
                "and it is gone before the copy is handed to the live store - the "
                        + "bench's calibration stays on the bench");
    }

    /** The operator's OWN card of that name survives the round trip: the sync
     *  renders over it to show the bench's ranges, and OK puts back exactly what
     *  was there.  Losing it would be this fix eating a local calibration. */
    @Test
    void aLocalCardOfTheSameNameIsRestoredRatherThanDropped() {
        Preferences edit = live.copyForDialog();
        AudioDeviceProfile mine = new AudioDeviceProfile();
        mine.setName(StubNetBackend.DEVICE_NAME);
        mine.getMatch().add(StubNetBackend.DEVICE_NAME);
        edit.putAudioDeviceProfile(mine);
        Qa40xSettingsUi panel = new Qa40xSettingsUi();
        panel.beginEdit();
        panel.onSelected(REMOTE_QA40X, edit);
        assertTrue(edit.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME)
                .getInput().isCalibrationFromDevice(), "the bench's card is showing");

        panel.commitEdit();

        AudioDeviceProfile restored = edit.findAudioDeviceProfile(StubNetBackend.DEVICE_NAME);
        assertNotNull(restored, "the operator's own card was displaced, not deleted");
        assertFalse(restored.getInput().isCalibrationFromDevice(),
                "and what is back is THEIRS, not the analyzer's");
    }

    /** One {@code {dbv,left,right}} row per range, as spec 4.6 shapes
     *  {@code qa40x.calibration}. */
    private List<Map<String, Object>> factorRows(int[] dbvValues) {
        List<Map<String, Object>> rows = new ArrayList<>(dbvValues.length);
        for (int dbv : dbvValues) {
            rows.add(Map.of(NetFields.DBV, dbv, NetFields.LEFT, LEFT_FACTOR,
                    NetFields.RIGHT, RIGHT_FACTOR));
        }
        return rows;
    }
}
