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

package org.edgo.audio.measure.gui.backend.loopback;

import java.util.function.Consumer;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;
import org.edgo.audio.measure.preferences.BackendKey;

/**
 * The subscriber that enforces the digital loopback's single format: its playback
 * lane's samples ARE its capture lane's samples, so input and output can differ in
 * neither sample rate NOR bit depth.  When one direction is edited in Preferences
 * the other must follow in both fields.  A bus-wired singleton, eagerly armed by
 * this module's settings-UI service so the subscription is live from the first
 * dispatch, before any combo can move.
 *
 * <p>The analyzer's constraint is the sibling of this one and covers the rate
 * alone: a QA40x's two directions share one reg-9 clock but keep their own widths.
 * The loopback shares the SAMPLES, which is a stricter thing - a block quantised
 * at one depth cannot be delivered as another - so the depth is coupled here as
 * well.
 *
 * <p>The Preferences dialog edits an UNCOMMITTED working copy, so this subscriber
 * cannot read the change off live state - it gates on the
 * {@link SampleRateChange#backend()} the payload carries, which is what keeps the
 * backends' constraints apart on one shared event.
 *
 * <p>It records the last format seen per direction and, when a change makes the
 * two differ, answers with {@link Events#PREFS_SAMPLE_RATE_SET} for the OTHER
 * direction AND updates its own record of that direction to match.  The pair is
 * then equal in its state, so the follow-up change for the corrected direction is
 * a no-op: the round-trip terminates by construction (equal records => nothing
 * published), independently of the dialog's programmatic-select-fires-no-event
 * behaviour.  A change on any other backend forgets both formats, so a stale pair
 * can never mis-fire once the loopback is chosen again.
 */
@Log4j2
public final class LoopbackFormatConstraint {

    /** Nothing recorded yet for a direction - the first change there only syncs
     *  the other; it never compares against a stale value. */
    private static final int UNSET = -1;

    private static volatile LoopbackFormatConstraint instance;

    public static LoopbackFormatConstraint instance() {
        LoopbackFormatConstraint local = instance;
        if (local != null) return local;
        synchronized (LoopbackFormatConstraint.class) {
            if (instance == null) instance = new LoopbackFormatConstraint();
            return instance;
        }
    }

    private final MessageBus bus;
    private final Consumer<SampleRateChange> formatListener = this::onFormatChanged;

    /** WHICH selection the recorded pair below was seen on.  The loopback is a
     *  software bench, and a server's loopback is that server's own - so a pair
     *  recorded on the one says nothing about the other. */
    private BackendKey lastSelection;
    private int lastInputHz    = UNSET;
    private int lastOutputHz   = UNSET;
    private int lastInputBits  = UNSET;
    private int lastOutputBits = UNSET;

    /** Wires the singleton into the {@link MessageBus} on first construction. */
    private LoopbackFormatConstraint() {
        this.bus = MessageBus.instance();
        bus.subscribe(Events.PREFS_SAMPLE_RATE_CHANGED, formatListener);
    }

    void onFormatChanged(SampleRateChange change) {
        if (change == null) return;
        BackendKey selected = change.backend();
        if (selected == null || selected.type() != AudioBackendType.LOOPBACK) {
            forget(null);   // left the constrained backend - drop the stale pair
            return;
        }
        if (!selected.equals(lastSelection)) {
            forget(selected);   // another bench - its format is its own
        }
        int rate = change.sampleRateHz();
        int bits = change.bitDepth();
        String card = change.card();   // echo the card back so a card-scoped listener can match it
        if (change.input()) {
            lastInputHz   = rate;
            lastInputBits = bits;
            if (lastOutputHz != rate || lastOutputBits != bits) {
                // Pair now equal in our state -> the follow-up output change is a no-op.
                lastOutputHz   = rate;
                lastOutputBits = bits;
                bus.publish(Events.PREFS_SAMPLE_RATE_SET,
                        new SampleRateChange(false, rate, bits, selected, card));
            }
        } else {
            lastOutputHz   = rate;
            lastOutputBits = bits;
            if (lastInputHz != rate || lastInputBits != bits) {
                lastInputHz   = rate;
                lastInputBits = bits;
                bus.publish(Events.PREFS_SAMPLE_RATE_SET,
                        new SampleRateChange(true, rate, bits, selected, card));
            }
        }
    }

    /** Drops the recorded pair and adopts {@code selection} as the bench the next
     *  one will belong to ({@code null} when the change left the loopback
     *  altogether).  A pair kept across a bench switch would compare the new
     *  bench's format against the old one's and skip the correction the new one
     *  still needs. */
    private void forget(BackendKey selection) {
        lastSelection  = selection;
        lastInputHz    = UNSET;
        lastOutputHz   = UNSET;
        lastInputBits  = UNSET;
        lastOutputBits = UNSET;
    }
}
