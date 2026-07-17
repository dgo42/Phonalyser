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

package org.edgo.audio.measure.sound.qa40x;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.ActiveRangeChange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.sound.AudioBackend;

import java.util.function.Consumer;

/**
 * The subscriber that turns the generic
 * {@link Events#DEVICE_ACTIVE_RANGE_CHANGED} bus event into a physical re-range of
 * the open QA402/QA403 — a bus-wired singleton, eagerly armed by the
 * {@link Qa40xDeviceManager} constructor so the subscription is live from the first
 * QA40x dispatch, before any Preferences OK can publish a range change.
 *
 * <p>Lives in the one QA40x package (maintainer decision: ALL QA40x specifics in
 * {@code sound.qa40x}; the bus stays generic infrastructure).  The generic event
 * names no card and no backend, so this subscriber consults CURRENT state itself:
 * it acts only while {@link AudioBackendType#QA40X} is the active backend and the
 * device is open, and routes the change to the open device's card — the manager
 * applies it (a live session restart, or the stored range for the next open).
 */
@Log4j2
public final class Qa40xRangeController {

    /** Not a valid full-scale dBV — a range label the QA40x code maps don't
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

    private final Consumer<ActiveRangeChange> rangeListener = this::onActiveRangeChanged;

    /** Wires the singleton into the {@link MessageBus} on first construction. */
    private Qa40xRangeController() {
        MessageBus.instance().subscribe(Events.DEVICE_ACTIVE_RANGE_CHANGED, rangeListener);
    }

    private void onActiveRangeChanged(ActiveRangeChange change) {
        if (change == null) return;
        AudioBackend backend = AudioBackend.instance();
        if (backend.active() != AudioBackendType.QA40X) return;   // only QA40x re-ranges hardware
        Qa40xDeviceManager manager = backend.qa40xManager();
        String cardName = manager.cardName();
        if (cardName == null) return;                             // device not open — next open reads the store
        int[] candidates = change.input()
                ? Qa40xProtocol.inputRangeDbvValues()
                : Qa40xProtocol.outputRangeDbvValues();
        int dbv = Qa40xProtocol.rangeDbv(change.activeRangeLabel(), candidates, NO_RANGE);
        if (dbv == NO_RANGE) return;                              // not a QA40x range label
        manager.applyActiveRangeChange(backend.active(), cardName, change.input(), dbv);
    }
}
