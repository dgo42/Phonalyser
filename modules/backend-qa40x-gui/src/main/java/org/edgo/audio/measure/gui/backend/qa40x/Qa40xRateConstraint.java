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
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;
import org.edgo.audio.measure.preferences.BackendKey;

import java.util.function.Consumer;

/**
 * The subscriber that enforces the QA402/QA403's single shared reg-9 sample-rate
 * clock: its input and output rates can never differ (doc/QA40X-PROTOCOL.md §10),
 * so when one direction is edited in Preferences the other must follow.  A
 * bus-wired singleton, eagerly armed by this module's settings-UI service
 * next to {@link Qa40xRangeController} so the subscription is live from the first
 * QA40x dispatch, before any rate combo can move.
 *
 * <p>Lives in the one QA40x package: ALL QA40x specifics belong in
 * {@code sound.qa40x} and the bus stays generic infrastructure.  The Preferences
 * dialog edits an UNCOMMITTED working copy, so this subscriber cannot read the
 * change off live state - it gates on the {@link SampleRateChange#backend()} the
 * payload carries.
 *
 * <p><b>The bench, not the cable.</b>  It acts whenever that selection's
 * {@link BackendKey#type()} is {@link AudioBackendType#QA40X} - a QA403 wired to
 * this machine and one reached on a Phonalyser server are the same analyzer with
 * the same single reg-9 clock, so the constraint follows the backend's
 * semantics, never where it happens to be plugged in.  The answer echoes the
 * selection it came from, so a dialog showing another server's QA40x ignores it.
 *
 * <p>It records the last rate seen per direction and, when a change makes the two
 * differ, answers with {@link Events#PREFS_SAMPLE_RATE_SET} for the OTHER
 * direction AND updates its own record of that direction to match.  The pair is
 * then equal in its state, so the follow-up change for the corrected direction is
 * a no-op: the round-trip terminates by construction (equal records => nothing
 * published), independently of the dialog's programmatic-select-fires-no-event
 * behaviour.  A change on any other backend forgets both rates, so a stale pair
 * can never mis-fire once QA40x is chosen again.
 *
 * <p><b>One pair, one bench.</b>  The recorded pair belongs to the selection it
 * was seen on and is dropped the moment another one arrives: a local QA403 and
 * one on a server (or two servers' analyzers) have their own reg-9 clock each, so
 * a rate remembered from the bench just left would make the correction the newly
 * chosen bench needs look unnecessary - and Preferences would commit an unequal
 * pair to an analyzer that cannot have one.
 */
@Log4j2
public final class Qa40xRateConstraint {

    /** No rate recorded yet for a direction - the first change there only syncs
     *  the other; it never compares against a stale value. */
    private static final int UNSET = -1;

    private static volatile Qa40xRateConstraint instance;

    public static Qa40xRateConstraint instance() {
        Qa40xRateConstraint local = instance;
        if (local != null) return local;
        synchronized (Qa40xRateConstraint.class) {
            if (instance == null) instance = new Qa40xRateConstraint();
            return instance;
        }
    }

    private final MessageBus bus;
    private final Consumer<SampleRateChange> rateListener = this::onSampleRateChanged;

    /** WHICH bench the recorded pair below was seen on.  Two QA40x analyzers -
     *  one on this machine and one on a server, or one on each of two servers -
     *  are two independent reg-9 clocks, so a pair recorded on the one says
     *  nothing about the other. */
    private BackendKey lastSelection;
    private int lastInputHz  = UNSET;
    private int lastOutputHz = UNSET;

    /** Wires the singleton into the {@link MessageBus} on first construction. */
    private Qa40xRateConstraint() {
        this.bus = MessageBus.instance();
        bus.subscribe(Events.PREFS_SAMPLE_RATE_CHANGED, rateListener);
    }

    void onSampleRateChanged(SampleRateChange change) {
        if (change == null) return;
        BackendKey selected = change.backend();
        if (selected == null || selected.type() != AudioBackendType.QA40X) {
            forget(null);   // left the constrained backend - drop stale rates
            return;
        }
        if (!selected.equals(lastSelection)) {
            forget(selected);   // another analyzer - its clock is its own
        }
        int rate = change.sampleRateHz();
        String card = change.card();   // echo the card back so a card-scoped listener can match it
        if (change.input()) {
            lastInputHz = rate;
            if (lastOutputHz != rate) {
                lastOutputHz = rate;   // pair now equal in our state -> the follow-up output change is a no-op
                bus.publish(Events.PREFS_SAMPLE_RATE_SET, new SampleRateChange(false, rate, selected, card));
            }
        } else {
            lastOutputHz = rate;
            if (lastInputHz != rate) {
                lastInputHz = rate;
                bus.publish(Events.PREFS_SAMPLE_RATE_SET, new SampleRateChange(true, rate, selected, card));
            }
        }
    }

    /** Drops the recorded pair and adopts {@code selection} as the bench the next
     *  one will belong to ({@code null} when the change left QA40x altogether).
     *  A pair kept across a bench switch would compare the new analyzer's rate
     *  against the old one's and skip the correction the new one still needs. */
    private void forget(BackendKey selection) {
        lastSelection = selection;
        lastInputHz   = UNSET;
        lastOutputHz  = UNSET;
    }
}
