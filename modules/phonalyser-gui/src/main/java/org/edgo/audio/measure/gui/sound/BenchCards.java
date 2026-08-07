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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The card choices a Phonalyser server offers, the operator's pick among them, and
 * the cards this client hands UP to it - the remote half of two rules: the card a
 * device uses (QA402 vs QA403, and any other device) is the USER's selection and
 * the device&lt;-&gt;card binding is persisted, and a bench with no card for a
 * device it owns may be given THIS machine's card for it.
 *
 * <p><b>The card lives where the device lives</b>, for the same reason its
 * calibration does (spec 4.3): the card decides which calibration is in force, so
 * a choice kept only on this client would leave the bench answering a different
 * card to everyone else - including this client's next session.  The BINDING is
 * mirrored locally under the server's id as well, which is what lets the chooser
 * show the remembered pick before, or without, an answer from the bench; the
 * card's VALUES never are.
 *
 * <p>Distinct from {@link CalibrationStore} on purpose: that one decides where a
 * measured VALUE goes, this one owns the CARDS - which exist, which is chosen,
 * which row of one is active.  The protocol draws the same line -
 * {@code device.setCard} is accepted for a {@code calibrationFromDevice} card,
 * which {@code device.setCalibration} and {@code cards.put} are not.
 */
@Log4j2
@RequiredArgsConstructor
public final class BenchCards {

    private final Preferences prefs;
    /** Where a bare pair of full scales goes when a whole card will not fit -
     *  {@link #propagateLocalCard}'s fallback.  Injected rather than built here so
     *  both types work on the SAME working copy the dialog edits. */
    private final CalibrationStore calibration;

    /** Picks made in the dialog but not yet committed, keyed by bench and device
     *  NAME.  NOTHING here has reached the bench: the Preferences dialog applies on
     *  OK and only on OK, so Cancel has to mean that nothing happened anywhere -
     *  including on another machine, where it could not be taken back.
     *
     *  <p><b>Keyed per BOX, not per direction</b> (spec 4.3: "a card describes a
     *  physical box ... one box, one card").  The server keys the binding on the
     *  device name alone, so a backend that names both directions alike - the
     *  QA40x, the very device {@code device.setCard} exists for - must not be
     *  stageable to two different cards: the second write would silently win and
     *  the other combo would go on showing a card that is not bound. */
    private final Map<String, Pick> staged = new LinkedHashMap<>();

    /** The last {@code cards.list} answer per bench, by card name - the CONTENT of
     *  every card the bench offers (spec 4.3 v1.1), which is what lets the ranges
     *  table show a bench card's rows and {@code device.setActiveRange} name one.
     *  A cache of the last answer, never a store: it is replaced wholesale by the
     *  next {@link #list} and dropped with the dialog. */
    private final Map<String, Map<String, AudioDeviceProfile>> content = new LinkedHashMap<>();

    /** Active-range moves made in the dialog but not yet sent - same apply-on-OK
     *  rule as {@link #staged}, and keyed per bench, device, DIRECTION and channel
     *  because a card's two endpoints have their own range tables and an
     *  INDEPENDENT endpoint's two channels their own markers. */
    private final Map<String, RangePick> stagedRanges = new LinkedHashMap<>();

    /** One staged card choice: the card, and the direction whose combo made it -
     *  the direction is not part of the choice (a card covers the box) but it is
     *  how the commit finds a device ref to send the write with. */
    private record Pick(String cardName, boolean input) { }

    /** One staged active-range move: which device, which row, on which side, in
     *  which direction's endpoint. */
    private record RangePick(String deviceName, String label, Channel side, boolean input) { }

    /**
     * Spec 4.3's {@code cards.list}, filtered to the cards that can serve
     * {@code input} - a card with no row for a direction cannot calibrate
     * anything there, and offering it would only invite a binding that measures
     * nothing.  Empty when the bench cannot be reached, which leaves the chooser
     * showing nothing rather than a stale list from another server.
     *
     * <p>The whole answer's CONTENT is cached on the way past (see
     * {@link #content}) - the same round trip that fills the chooser is the one
     * that has the rows in its hands.
     */
    public List<String> list(BackendKey bench, boolean input) {
        List<String> names = new ArrayList<>();
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return names;
        }
        // DIRECT on the caller's thread, bounded by the wire timeouts.
        Map<String, Object> answer =
                remote.call(bench, MessageType.CARDS_LIST.getWire(), Map.of());
        if (answer == null || !(answer.get(NetFields.CARDS) instanceof List<?> cards)) {
            if (log.isWarnEnabled()) {
                log.warn("Card binding: {} did not answer its card list", bench.key());
            }
            return names;
        }
        Map<String, AudioDeviceProfile> byName = new LinkedHashMap<>();
        for (Object entry : cards) {
            if (!(entry instanceof Map<?, ?> card
                    && card.get(NetFields.NAME) instanceof String name)) {
                continue;
            }
            if (card.get(NetFields.CONTENT) instanceof Map<?, ?> body) {
                AudioDeviceProfile parsed = prefs.cardFromMap(body);
                if (parsed != null) {
                    byName.put(name, parsed);
                }
            }
            if (Boolean.TRUE.equals(card.get(input ? NetFields.INPUT : NetFields.OUTPUT))) {
                names.add(name);
            }
        }
        content.put(bench.key(), byName);
        return names;
    }

    /** One bench card as the bench described it, or null when this client has not
     *  read that card (no {@code cards.list} yet, a bench too old to send the
     *  content, or a name it does not offer).  The ranges table's source for a
     *  device on a bench - the CARD's own rows, never a local card that merely
     *  shares its name. */
    public AudioDeviceProfile card(BackendKey bench, String cardName) {
        Map<String, AudioDeviceProfile> byName = content.get(bench.key());
        return byName == null || cardName == null ? null : byName.get(cardName);
    }

    /** Records a pick without sending anything - see {@link #staged}. */
    public void stage(BackendKey bench, boolean input, String deviceName, String cardName) {
        staged.put(key(bench, deviceName), new Pick(cardName, input));
    }

    /** The pick staged for this device, or null when the operator has not chosen
     *  one in this dialog - what BOTH direction combos show for a device that is
     *  listed under one name in both. */
    public String stagedCard(BackendKey bench, String deviceName) {
        Pick pick = staged.get(key(bench, deviceName));
        return pick == null ? null : pick.cardName();
    }

    /** Every pick staged for one bench, device name -> pick - what the OK commit
     *  walks, ONCE per box.  Empty when nothing was chosen, which is the usual
     *  case and the one where OK must send nothing at all. */
    private Map<String, Pick> stagedFor(BackendKey bench) {
        Map<String, Pick> out = new LinkedHashMap<>();
        String prefix = key(bench, "");
        for (Map.Entry<String, Pick> entry : staged.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                out.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Sends every staged pick to {@code bench} - the OK half of {@link #stage}, and
     * the ONE walk over the staged map: one physical box, one
     * {@code device.setCard}.
     *
     * @param inputs  the bench's input devices, where a pick made on the input
     *        combo finds its write ticket
     * @param outputs the same for the output combo
     * @return the cards the bench would not take, for the caller's one error
     *         dialog; a refusal writes no local mirror either, so what this machine
     *         remembers is only ever what the bench accepted
     */
    public List<String> commitStagedBindings(BackendKey bench, List<DeviceRef> inputs,
            List<DeviceRef> outputs) {
        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Pick> entry : stagedFor(bench).entrySet()) {
            Pick pick = entry.getValue();
            DeviceRef device = refFor(pick.input() ? inputs : outputs, entry.getKey());
            if (!bind(bench, device, pick.cardName())) {
                refused.add(pick.cardName());
            }
        }
        return refused;
    }

    /** Records an active-range move without sending anything - the range twin of
     *  {@link #stage}, and the only thing a bench card's radios do until OK. */
    public void stageActiveRange(BackendKey bench, boolean input, String deviceName,
            String label, Channel side) {
        stagedRanges.put(key(bench, deviceName) + "|" + (input ? "in" : "out")
                + "|" + channelScope(side),
                new RangePick(deviceName, label, side, input));
    }

    /** Sends every staged active-range move to {@code bench} - the OK half of
     *  {@link #stageActiveRange}.
     *
     *  @return the range labels the bench would not take */
    public List<String> commitStagedRanges(BackendKey bench, List<DeviceRef> inputs,
            List<DeviceRef> outputs) {
        List<String> refused = new ArrayList<>();
        String prefix = key(bench, "");
        for (Map.Entry<String, RangePick> entry : stagedRanges.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            RangePick pick = entry.getValue();
            DeviceRef device = refFor(pick.input() ? inputs : outputs, pick.deviceName());
            if (!setActiveRange(bench, device, pick.label(), pick.side())) {
                refused.add(pick.label());
            }
        }
        return refused;
    }

    /** The enumerated ref behind a device NAME, or null when the list no longer
     *  offers it. */
    private DeviceRef refFor(List<DeviceRef> refs, String deviceName) {
        if (refs == null) {
            return null;
        }
        for (DeviceRef ref : refs) {
            if (ref.name().equals(deviceName)) {
                return ref;
            }
        }
        return null;
    }

    private String key(BackendKey bench, String deviceName) {
        return bench.key() + "|" + deviceName;
    }

    /**
     * Spec 4.3's {@code device.setCard}: tells the bench which of its cards the
     * operator chose for {@code device}, then mirrors the choice locally under the
     * server's id.  A null / blank {@code cardName} unbinds.
     *
     * <p>Sent under the device lock the bench requires for it - the binding
     * changes what every measurement on that device means, so it fails honestly
     * while another client is measuring on it rather than moving the ground under
     * them.  The mirror is written ONLY after the bench accepted: a local record
     * of a choice the bench never took would show the operator a pick that is not
     * in force anywhere.
     *
     * @return whether the bench stored the binding
     */
    public boolean bind(BackendKey bench, DeviceRef device, String cardName) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null || device == null) {
            return false;
        }
        Map<String, Object> fields = deviceFields(bench, device);
        fields.put(NetFields.CARD, cardName == null || cardName.isEmpty() ? null : cardName);
        // DIRECT, one locked write per staged binding - acquire, set, release -
        // bounded by the wire timeouts.
        if (remote.callLocked(bench, MessageType.DEVICE_SET_CARD.getWire(),
                fields) == null) {
            if (log.isWarnEnabled()) {
                log.warn("Card binding: {} did not bind '{}' to card '{}'", bench.key(),
                        device.name(), cardName);
            }
            return false;
        }
        prefs.bindDeviceToCard(prefs.deviceBindingKey(bench.serverId(), device.name()),
                cardName);
        if (log.isInfoEnabled()) {
            log.info("Card binding: {} bound '{}' to card '{}'", bench.key(),
                    device.name(), cardName);
        }
        return true;
    }

    /**
     * Spec 4.3's {@code cards.put}: hands {@code card} to the bench, which stores
     * it in the {@code devices.yaml} of the machine the device is plugged into.
     *
     * <p>No lock is taken and none is needed: a card nothing is bound to is in
     * force nowhere, so it cannot move a measurement under anybody.  The
     * {@link #bind} that follows is the write that does.
     *
     * @return whether the bench stored it - false covers every refusal alike (a
     *         name it already has, a card it will not take, an unreachable bench),
     *         because a refusal reaches this client as a plain "no"
     */
    public boolean create(BackendKey bench, AudioDeviceProfile card) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null || card == null || card.getName() == null) {
            return false;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NetFields.CONTENT, prefs.cardToMap(card));
        if (remote.call(bench, MessageType.CARDS_PUT.getWire(), fields) == null) {
            if (log.isWarnEnabled()) {
                log.warn("Card: {} would not take a card named '{}'", bench.key(),
                        card.getName());
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("Card: '{}' stored on {}", card.getName(), bench.key());
        }
        return true;
    }

    /**
     * {@link #create} then {@link #bind}: the card exists on the bench AND the
     * device uses it, which is the only state that changes what the operator
     * measures.  Used by both card-to-the-bench flows - the operator-authored card
     * and the propagation of a local card.
     *
     * <p>The half-done case is answered as itself.  A create that lands and a bind
     * that does not (another client holds the device) leaves a real card on the
     * bench that nothing uses - reporting that as "nothing happened" would send the
     * operator round the same flow next time, where the create now fails on the
     * name it made itself.  {@link Copied#CARD_UNBOUND} is what the caller tells
     * them instead, and the card is deliberately NOT unwound: it is correct, it is
     * theirs, and binding it is one retry away.
     */
    public Copied createAndBind(BackendKey bench, DeviceRef device, AudioDeviceProfile card) {
        if (!create(bench, card)) {
            return Copied.NOTHING;
        }
        return bind(bench, device, card.getName()) ? Copied.CARD : Copied.CARD_UNBOUND;
    }

    /**
     * The bench has no calibration for a device it owns and THIS machine has a
     * card that recognises it - so the card goes UP, whole.
     *
     * <p>The whole card, not the pair of numbers it happens to hold at this
     * moment: the range table, the channel mode and the recognition list are what
     * make the values mean something, and a bench told only "1.9 / 1.9 Vrms" would
     * store them on a bare one-row card that no longer describes the box.  The
     * device's own name is added to the copy's match list on the way, so the
     * bench recognises the device even before the binding lands.
     *
     * <p><b>The name goes as it is.</b>  A suffixed retry would put a second card
     * describing one box on the bench under a name the operator never chose;
     * instead a refusal (the bench already has that name) falls back to the values
     * alone through {@code device.setCalibration}, which is the pre-v1.1 behaviour
     * and still leaves the device calibrated.
     *
     * @return what actually reached the bench, for the caller to tell the operator
     */
    public Copied propagateLocalCard(BackendKey bench, DeviceRef device, String deviceName,
            boolean input) {
        AudioDeviceProfile local = localCopy(deviceName);
        if (local == null || prefs.deviceCalibration(deviceName, input) == null) {
            return Copied.NOTHING_TO_COPY;
        }
        local.bindDeviceName(deviceName);
        Copied put = createAndBind(bench, device, local);
        if (put != Copied.NOTHING) {
            // CARD, or CARD_UNBOUND - either way the card IS on the bench with its
            // values in it, so there is nothing left for the values-only fallback
            // to add; what is missing is a binding, and the caller says so.
            return put;
        }
        if (calibration.copyCardCalibrationToBench(bench, device)) {
            if (log.isInfoEnabled()) {
                log.info("Card: {} would not take the whole card '{}' - its full scales "
                        + "went up on their own", bench.key(), local.getName());
            }
            return Copied.VALUES_ONLY;
        }
        return Copied.NOTHING;
    }

    /** A DETACHED copy of the local card that recognises {@code deviceName} -
     *  {@code getAudioDeviceProfiles} hands out copies, so binding the device name
     *  into the one we send cannot touch this machine's store. */
    private AudioDeviceProfile localCopy(String deviceName) {
        AudioDeviceProfile live = prefs.resolveDeviceProfile(deviceName);
        if (live == null) {
            return null;
        }
        for (AudioDeviceProfile copy : prefs.getAudioDeviceProfiles()) {
            if (copy.getName().equalsIgnoreCase(live.getName())) {
                return copy;
            }
        }
        return null;
    }

    /**
     * Spec 4.3's {@code device.setActiveRange}: the operator moved the active row
     * of the card in force for a bench device, so the bench's own store moves it -
     * which is what changes the full scale every client is told for that device.
     *
     * <p>Sent under the device lock the bench requires, exactly like a calibration
     * write and for the same reason: the active row IS the calibration in force.
     *
     * @param side null (or {@link Channel#L}) for a card whose channels share one
     *        marker; {@link Channel#R} moves the right channel's own marker on an
     *        INDEPENDENT card
     */
    public boolean setActiveRange(BackendKey bench, DeviceRef device, String label,
            Channel side) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null || device == null || label == null) {
            return false;
        }
        Map<String, Object> fields = deviceFields(bench, device);
        fields.put(NetFields.RANGE, label);
        fields.put(NetFields.CHANNEL, channelScope(side));
        if (remote.callLocked(bench, MessageType.DEVICE_SET_ACTIVE_RANGE.getWire(),
                fields) == null) {
            if (log.isWarnEnabled()) {
                log.warn("Card range: {} did not move '{}' to range '{}'", bench.key(),
                        device.name(), label);
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("Card range: {} moved '{}' to range '{}'", bench.key(),
                    device.name(), label);
        }
        return true;
    }

    /** The {@code channel} scope one active-range write names: the two per-channel
     *  words for an INDEPENDENT card's own markers, else the one both channels
     *  read. */
    private String channelScope(Channel side) {
        if (side == Channel.R) {
            return NetFields.RIGHT;
        }
        return side == Channel.L ? NetFields.LEFT : NetFields.BOTH;
    }

    /** The device ref of spec 4.3 as request fields - the four that name a device,
     *  written in one place so every per-device write names it the same way. */
    private Map<String, Object> deviceFields(BackendKey bench, DeviceRef device) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NetFields.BACKEND, bench.type().name());
        fields.put(NetFields.INDEX, device.index());
        fields.put(NetFields.INPUT, device.isInput());
        fields.put(NetFields.NAME, device.name());
        return fields;
    }

    /**
     * Which card the chooser should show as bound: the BENCH's own answer (spec
     * 4.3 {@code card}, which every {@code ev.devices.changed} refreshes), else
     * the local mirror of the last pick made from this installation.
     *
     * <p>The bench wins because it is authoritative - another operator may have
     * re-bound the device since - and the mirror only covers the moment before
     * the catalogue has been read, or a bench too old to send the field.
     */
    public String boundCard(BackendKey bench, DeviceRef device, String deviceName) {
        if (device != null && device.boundCard() != null) {
            return device.boundCard();
        }
        return prefs.boundCardName(prefs.deviceBindingKey(bench.serverId(), deviceName));
    }

    /** What {@link #propagateLocalCard} - or {@link #createAndBind} - managed to
     *  put on the bench.  Every value but {@link #NOTHING} is a settled answer the
     *  operator can be told and the offer need not be repeated for. */
    public enum Copied {
        /** The whole card, and the device is bound to it. */
        CARD,
        /** The card is on the bench but the device is NOT bound to it - the
         *  binding was refused (another client holds the device). */
        CARD_UNBOUND,
        /** The bench would not take the card, but its full scales landed. */
        VALUES_ONLY,
        /** Nothing reached the bench - an unreachable bench, or a refusal all the
         *  way down.  The only outcome worth offering again. */
        NOTHING,
        /** This machine had nothing worth sending - no card for the name, or one
         *  whose row was never calibrated. */
        NOTHING_TO_COPY
    }
}
