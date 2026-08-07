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

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.ActiveRange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;

import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xProtocol;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The subscriber that turns the generic
 * {@link Events#DEVICE_ACTIVE_RANGE_CHANGED} bus event into a physical re-range of
 * the open QA402/QA403 - a bus-wired singleton, eagerly armed by the
 * {@link Qa40xDeviceManager} constructor so the subscription is live from the first
 * QA40x dispatch, before any Preferences OK can publish a range change.
 *
 * <p>Lives in the one QA40x package: ALL QA40x specifics belong in
 * {@code sound.qa40x} and the bus stays generic infrastructure.  The generic event
 * names no card and no backend, so this subscriber consults CURRENT state itself
 * - and the event is published AFTER the Preferences commit, so that state is the
 * committed one.
 *
 * <p><b>The bench, not the cable.</b>  A QA403 wired to this machine and one
 * reached on a Phonalyser server are the same analyzer with the same attenuator,
 * so both are re-ranged, exactly as {@link Qa40xRateConstraint} constrains both
 * clocks.  Locally that means {@link AudioBackendType#QA40X} is the active backend
 * and the device is open, and the change goes to the open device's card - the
 * manager applies it (a live session restart, or the stored range for the next
 * open).  Remotely the selected bench is a remote QA40x, and the change goes over
 * the net protocol's range commands (spec 4.6).
 */
@Log4j2
public final class Qa40xRangeController {

    /** Not a valid full-scale dBV - a range label the QA40x code maps don't
     *  recognise (e.g. a non-QA40x device card's label) resolves to this and is
     *  ignored, since the event is generic and may describe any device. */
    private static final int NO_RANGE = Integer.MIN_VALUE;

    private static volatile Qa40xRangeController instance;

    public static Qa40xRangeController instance() {
        Qa40xRangeController local = instance;
        if (local != null) return local;
        synchronized (Qa40xRangeController.class) {
            if (instance == null) instance = new Qa40xRangeController();
            return instance;
        }
    }

    private final Consumer<ActiveRange> rangeListener = this::onActiveRangeChanged;

    /** Wires the singleton into the {@link MessageBus} on first construction. */
    private Qa40xRangeController() {
        MessageBus.instance().subscribe(Events.DEVICE_ACTIVE_RANGE_CHANGED, rangeListener);
    }

    private void onActiveRangeChanged(ActiveRange change) {
        if (change == null) return;
        int[] candidates = change.isInput()
                ? Qa40xProtocol.inputRangeDbvValues()
                : Qa40xProtocol.outputRangeDbvValues();
        int dbv = Qa40xProtocol.rangeDbv(change.getActiveRangeLabel(), candidates, NO_RANGE);
        if (dbv == NO_RANGE) return;                              // not a QA40x range label
        BackendKey selected = Preferences.instance().getSelectedBackend();
        if (selected != null && selected.remote() && selected.type() == AudioBackendType.QA40X) {
            sendToBench(selected, change.isInput(), dbv);
            return;
        }
        AudioBackend backend = AudioBackend.instance();
        if (backend.active() != AudioBackendType.QA40X) return;   // only QA40x re-ranges hardware
        Qa40xDeviceManager manager = (Qa40xDeviceManager) backend.qa40xManager();
        String cardName = manager.cardName();
        if (cardName == null) return;                             // device not open - next open reads the store
        manager.applyActiveRangeChange(backend.active(), cardName, change.isInput(), dbv);
    }

    /**
     * The same re-range on an analyzer that is not in this room: net protocol 4.6
     * makes {@code qa40x.setInputRange} / {@code qa40x.setOutputRange} the
     * attenuator, and the write is LOCKED because the bench requires the
     * analyzer's device lock for it (nothing here holds one - the modules are
     * stopped while Preferences is open).
     *
     * <p>There is no card to match against, unlike the local branch: a remote
     * selection names ONE backend of ONE server, so the analyzer this event
     * belongs to is the analyzer that selection reaches.  The label having
     * resolved to a QA40x range dBV at all is what says the row describes this
     * hardware.
     */
    private void sendToBench(BackendKey bench, boolean input, int dbv) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) return;
        MessageType request = input
                ? MessageType.QA40X_SET_INPUT_RANGE
                : MessageType.QA40X_SET_OUTPUT_RANGE;
        // A locked write is three round trips (acquire, the write, release),
        // DIRECT on the caller's thread and bounded by the wire timeouts.
        Map<String, Object> answer = remote.callLocked(bench, request.getWire(),
                Map.of(NetFields.DBV, dbv));
        if (answer == null && log.isWarnEnabled()) {
            log.warn("QA40x range: {} did not take the {} range {} dBV", bench.key(),
                    input ? "input" : "output", dbv);
        }
    }
}
