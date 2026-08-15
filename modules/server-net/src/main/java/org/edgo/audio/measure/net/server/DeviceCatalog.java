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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sound.sampled.AudioFormat;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.RequiredArgsConstructor;

/**
 * What the server has to offer: the {@code devices.list} payload of spec 4.3,
 * built from every backend this build serves, with each device's formats inlined
 * ("to avoid per-device round-trips") and the live lock state overlaid.
 *
 * <p><b>One entry per device AND direction.</b>  A backend's index space is
 * per-direction - {@code AudioDeviceManager#listInputDevices()} documents an
 * index as "the slot in THIS list" - and spec 4.3 keys a lock on
 * {@code (backend, index, direction)}.  A duplex interface therefore appears
 * twice, once in each list, each with the index that direction uses, and the
 * entry's {@code input}/{@code output} pair says WHICH list its index belongs
 * to.  A client can copy those four fields straight into a device ref; reporting
 * the hardware's duplex capability instead would hand it an index that means
 * nothing in the other direction.
 *
 * <p><b>{@code hasBitDepth} is derived, not declared.</b>  It answers "may the
 * operator choose a sample width here?", and the honest source is the format
 * list this very entry carries: more than one distinct depth means there is a
 * choice.  That needs no per-backend table and it tracks the QA40x by itself -
 * its capture path always delivers 24 bits (one depth, no choice), while its
 * output offers the front-panel I2S frame widths whenever that port is on, which
 * its manager already reflects in the formats it reports.
 *
 * <p><b>Two ways to read it.</b>  {@link #scan()} re-enumerates the hardware;
 * {@link #lastScan()} re-uses the last enumeration and refreshes only the lock
 * overlay.  A lock change cannot move a device, and the
 * {@code ev.devices.changed} broadcast it triggers runs on threads that must not
 * block on a native device call - the keepalive scheduler declaring a client
 * dead, or the transport's close callback.  Enumeration therefore happens where
 * a client asked for it, on that client's own thread.
 *
 * <p><b>It also owns the {@code cal} of spec 4.3</b> - the full-scale RMS volts
 * this server stores for every device plugged into it (v1.1: calibration lives
 * where the device is connected, so a client arrives already calibrated).  Read
 * AND write are here, side by side, because the two have to agree exactly: what
 * {@code device.setCalibration} writes is what the next payload must report, and
 * a round trip through two types that resolved the card differently would drift
 * without anything failing.  Like the lock overlay it is read at PAYLOAD time,
 * not at enumeration time - so {@link #lastScan()}, which is what the broadcast
 * after a calibration write carries, reports the values that were just stored
 * without touching the hardware to find that out.
 */
@RequiredArgsConstructor
public final class DeviceCatalog {

    private final AudioBackend audio;
    private final LockRegistry locks;
    private final JsonCodec codec;
    /** The backends this server offers, decided once at start-up: the module is
     *  on the class path AND the backend can run on this host.  Injected rather
     *  than computed here so the composition root keeps that policy - and so a
     *  test can serve exactly the backends it stubs. */
    private final List<AudioBackendType> served;
    /** This server's device cards - where the calibration of everything plugged
     *  into this machine is kept.  Injected rather than reached through the
     *  singleton so a test can serve a card store it wrote itself, and so the
     *  process-wide store is named once, in the composition root. */
    private final Preferences cards;

    /** Guards the ENUMERATION alone - see {@link #scanned()}.  A private object
     *  rather than the catalog itself, and never held while the lock overlay is
     *  read, so this monitor and the {@link LockRegistry}'s can never be taken
     *  in two different orders. */
    private final Object scanLock = new Object();

    /** The last enumeration - what every client answer is served from.
     *  Written by the start-up priming {@link #scan()} and by the hot-plug
     *  {@link #rescan()}, read by request threads and by whichever thread a
     *  lock change arrives on, hence volatile; the list itself is never
     *  mutated after publication. */
    private volatile List<DeviceEntry> lastDevices = List.of();

    /**
     * Re-enumerates every served backend and answers the {@code backends} array
     * of spec 4.3, lock state included.  The priming read: {@link
     * ServerMain} runs it once before the transports accept anything, so no
     * client ever sees an empty bench.  Requests are answered from {@link
     * #lastScan()} - enumeration stays off the request threads, and the
     * hot-plug {@link #rescan()} keeps the snapshot at most one tick old.
     */
    public JsonNode scan() {
        return payload(scanned(), served);
    }

    /**
     * The {@code backend.list} array of spec 4.3: every backend this build knows
     * of, named and flagged, with NO device detail - the payload that fills a
     * client's backend combo before it has committed to one.
     *
     * <p>It lists {@code AudioBackendType.values()}, not the served subset,
     * because the two flags are the whole point: {@code available} says a module
     * supplying the backend is in this build, {@code operational} says this server
     * can actually run it - it is the served list itself, so the flag can never
     * promise a backend {@code backend.select} would refuse.  Spec 4.3 names
     * exactly that pair to explain "CoreAudio on Windows".  A backend the server
     * could not serve would otherwise vanish silently, and the operator would be
     * left wondering where it went.
     *
     * <p>The one constant that is NOT a backend of this server is
     * {@link AudioBackendType#NET}: it is the CLIENT's carrier for reaching a
     * server, owns no hardware, and is never served (the composition root leaves
     * it out of {@code served} as well).  Listing it would put a
     * "&lt;server&gt; -&gt; Network" entry in the client's combo - the client
     * presents every listed backend as its own selectable entry (spec 4.3) - that
     * {@code backend.select} could only answer {@code BAD_REQUEST} to.
     *
     * <p>{@code hasBitDepth} is the same question {@code devices.list} answers per
     * device - "may the operator choose a sample width here?" - asked of the
     * backend as a whole: true when ANY of its devices offers more than one.  It
     * is read from an enumeration rather than declared per type because the
     * honest answer moves: the QA40x offers the front-panel I2S frame widths
     * while that port is on and a single 24-bit width while it is off.  A backend
     * this server does not serve has no devices at all and therefore no width to
     * choose.
     *
     * <p>The QA40x is {@code operational} only while an analyzer is actually on
     * this server's USB: the backend is dedicated to a physical QuantAsylum
     * device and is shown, local or remote, only where one is present.  The
     * sound-card backends stay policy-only - a served host API always has
     * endpoints.
     */
    public JsonNode backends() {
        List<DeviceEntry> devices = scanned();
        List<Map<String, Object>> backends = new ArrayList<>();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isDualLevel()) {
                continue;   // a carrier for reaching servers, not a backend of one
            }
            boolean operational = served.contains(type)
                    && (type != AudioBackendType.QA40X || hasDevices(devices, type));
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put(NetFields.BACKEND, type.name());
            backend.put(NetFields.DISPLAY_NAME, type.getDisplayName());
            backend.put(NetFields.AVAILABLE, audio.isAvailable(type));
            backend.put(NetFields.OPERATIONAL, operational);
            backend.put(NetFields.HAS_BIT_DEPTH, hasBitDepth(devices, type));
            backends.add(backend);
        }
        return codec.toNode(backends);
    }

    /**
     * One backend's entry in {@code devices.list} shape - what
     * {@code backend.select} answers, so a single round-trip fills the device
     * combos (spec 4.3).  Served from the hot-plug watcher's last enumeration,
     * like {@code devices.list}: the watcher re-scans every two seconds, so
     * the committing client is at most one tick behind the bench - and never
     * behind a multi-second native walk on its own selection.
     *
     * @throws NetException {@code BAD_REQUEST} when this server does not serve
     *         {@code type} - a selection that cannot be honoured must fail at the
     *         selection, not later at an open that looks unrelated
     */
    public JsonNode backendEntry(AudioBackendType type) {
        if (!served.contains(type)) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    type + " is not served by this server");
        }
        return payload(lastDevices, List.of(type)).get(0);
    }

    /**
     * The same array from the LAST enumeration, with the lock overlay rebuilt -
     * what the {@code ev.devices.changed} broadcast carries.  It NEVER
     * enumerates: this runs on whichever thread changed a lock, and those are
     * threads no single connection owns (the keepalive scheduler declaring a
     * client dead, the transport's close callback), where a native device scan
     * would stall every other session.
     *
     * <p>Before anything has been enumerated the answer is an empty
     * {@code backends} array - "nothing to tell you about the bench yet", which
     * a client can recognise as such, rather than a list of backends that would
     * claim their devices are gone.  {@link ServerMain#start()} enumerates once
     * before the transports accept anything, precisely so a client never sees
     * that empty payload and resets its combos on it; this fallback stays
     * because the method's promise is that it does not touch hardware, whoever
     * calls it.
     */
    public JsonNode lastScan() {
        List<DeviceEntry> devices = lastDevices;
        return devices.isEmpty() ? codec.toNode(List.of()) : payload(devices, served);
    }

    /**
     * The hot-plug look: re-enumerates and answers whether the bench itself
     * moved - a device appeared, vanished, or changed the index it sits at.
     * Spec 4.3 sends {@code ev.devices.changed} "on hot-plug and on any lock
     * change", and a broadcast on every rescan whether or not anything happened
     * would make that event meaningless (a client resets its combos on it).
     *
     * <p>What counts as "moved" is the device IDENTITY -
     * {@code (backend, index, direction, name)} plus whatever the backend itself
     * can add ({@link DeviceRef#identity()}).  The four ref fields alone are what
     * can invalidate a client's ref (spec 4.3), and for a sound card they are also
     * the whole truth.  They are NOT the whole truth for a QA40x: there is one
     * analyzer, it sits at index 0, and it is named after its model - so an
     * unplug and a re-attach changes none of them, and on Windows the finder
     * deliberately keeps a device that answers ACCESS/BUSY (this process's own
     * stale claim) in the list, which is why that server logged nothing at all
     * across a detach while Linux and macOS broadcast it.  The analyzer's USB bus
     * and address are what actually move, and its ref now carries them.
     *
     * <p>The formats deliberately count for nothing: they are compared by nothing
     * (the JDK's audio format has no value equality), and a width appearing
     * because the operator turned the QA40x front-panel I2S port on is a settings
     * change on a device that never went anywhere.
     *
     * <p>Called on the HTTP pool by {@link ServerMain}, never on a ticker
     * thread - it touches hardware.  Read-compare-write under the same monitor
     * the enumeration takes: a session enumerating in between would otherwise
     * publish the new list first, and this tick would report no change (a
     * hot-plug nobody is told about) or a spurious one.
     */
    public boolean rescan() {
        synchronized (scanLock) {
            List<String> before = identities(lastDevices);
            refreshStaleSnapshots();
            return !before.equals(identities(scanned()));
        }
    }

    /**
     * Rebuilds the enumeration of every served backend that says its own list has
     * gone stale, so the comparison above looks at the bench and not at a
     * start-up snapshot.  This is the ONLY snapshot-rebuild path: WDM-KS and
     * CoreAudio ride one process-lifetime PortAudio snapshot, and a card
     * replugged since start-up reappears only through this rebuild (a replug
     * reappears in WASAPI, never in WDM-KS, which is what flips the stale flag).
     *
     * <p>The guard is load-bearing: it rebuilds only when the backend itself
     * reports the hardware moved under it
     * ({@code AudioDeviceManager#deviceListStale}).
     * Without the guard a 2 s tick would re-initialise a native library twice a
     * second; without the rebuild a snapshot backend can never report a hot-plug
     * AT ALL - it would answer from the list it took at start-up, the comparison
     * would find the pulled device still sitting there, and no client would ever
     * be told it went (the macOS bench symptom: the HAL warns per tick that it
     * knows no such device while the served list keeps offering it).
     *
     * <p>A backend that refuses the rebuild - PortAudio does while any stream of
     * its own is open, because terminating the library would free those streams
     * under their owners - stays stale and is simply asked again on the next
     * tick, by which time the lost device's own stream has ended itself.
     */
    private void refreshStaleSnapshots() {
        for (AudioBackendType type : served) {
            if (audio.deviceListStale(type)) {
                audio.refreshDeviceLists(type);
            }
        }
    }

    /**
     * The device ref an open that just failed may be tried ONE more time with, or
     * null when nothing about the failure says a second attempt could help.
     *
     * <p>The failure this exists for is the stale-snapshot one: a device that was
     * unplugged and plugged back in sits at a new place in the driver's world
     * while a snapshot backend still lists it at the old one, so every open on it
     * fails - on macOS with an internal PortAudio error, for the rest of the
     * process's life - although the device is right there.  The desktop meets the
     * same wall and answers it the same way: rebuild the list, look again.
     *
     * <p>Three things must hold, and each can only make the answer null: the
     * backend must say its list is stale, the rebuild must actually happen, and
     * the ref must still resolve afterwards.  In every one of those cases the
     * caller's original refusal stands, which is the honest answer to an open
     * that failed - and a device that came back at ANOTHER index is carried to
     * the client by the {@code ev.devices.changed} of the next hot-plug tick,
     * which is also what tells it to re-read its list.
     *
     * <p>It deliberately does NOT publish a new {@link #lastScan()}: what the
     * rebuild changed has to stay visible to the next {@link #rescan()}, or that
     * comparison would find nothing moved and no client would be told anything.
     */
    public DeviceRef refreshedForRetry(DeviceLock device, String name) {
        synchronized (scanLock) {
            if (!audio.deviceListStale(device.backend())
                    || !audio.refreshDeviceLists(device.backend())) {
                return null;
            }
            try {
                return resolve(device, name);
            } catch (NetException e) {
                // The device is back but not where it was - there is nothing to
                // retry WITH.  The open's own refusal is what the client gets.
                return null;
            }
        }
    }

    /** What makes a device the same device between two enumerations, in list
     *  order - see {@link #rescan()}. */
    private List<String> identities(List<DeviceEntry> devices) {
        List<String> keys = new ArrayList<>();
        for (DeviceEntry entry : devices) {
            keys.add(entry.device() + " " + entry.identity());
        }
        return keys;
    }

    /**
     * The device a request's ref names, validated against the current
     * enumeration - spec 4.3: "the server validates {@code name} on every use
     * and answers {@code DEVICE_STALE} when the index moved (hot-plug
     * re-enumeration)".
     *
     * @throws NetException {@code DEVICE_STALE} when the backend is not served,
     *         the index is out of range, or the name at that index is somebody
     *         else's; {@code BAD_REQUEST} when the ref carries no name at all,
     *         because then there is nothing to validate against
     */
    public DeviceRef resolve(DeviceLock device, String name) {
        if (name == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "device ref needs the device name, so the server can tell "
                            + "whether the index still means the same device");
        }
        if (!served.contains(device.backend())) {
            throw new NetException(ErrorCode.DEVICE_STALE,
                    device.backend() + " is not served by this server");
        }
        List<DeviceRef> devices = devices(device.backend(), device.input());
        if (device.index() < 0 || device.index() >= devices.size()) {
            throw new NetException(ErrorCode.DEVICE_STALE,
                    device + " is out of range - the device list has "
                            + devices.size() + " entries now, re-read it");
        }
        DeviceRef found = devices.get(device.index());
        if (!name.equals(found.name())) {
            throw new NetException(ErrorCode.DEVICE_STALE,
                    device + " is now '" + found.name() + "', not '" + name
                            + "' - the device list moved, re-read it");
        }
        return found;
    }

    /**
     * The full-scale RMS volts this server stores for one device and direction,
     * or null when it has no card for it - the {@code cal} of spec 4.3, and the
     * value a lane opened on that device is driven at (spec 4.5's {@code gen.open}
     * defaults).
     *
     * <p>Read fresh on every call: an operator recalibrating a device from
     * another client must not leave a generator opening at yesterday's full
     * scale.  The name is the ref's own - a card is bound by device NAME, and the
     * index says nothing about which card recognises it.
     *
     * <p>CARD VALUES ONLY.  {@code deviceCalibration}
     * resolves a card and answers null for an unbound device, for a card with no
     * usable range row, and for a row whose full scales are not above zero - it
     * never falls back to the machine-wide {@code adcFsVoltageRms} /
     * {@code dacFsVoltageAmpl} scalars, which are this process's own runtime
     * fallback and say nothing about the device a remote client asked about.  So
     * the only two things this server can put on the wire are a measured pair and
     * a JSON null, and a client is told plainly that it must fall back to
     * something of its own rather than being handed a deprecated default dressed
     * up as a calibration.
     */
    public DeviceCalibration calibration(DeviceLock device, String name) {
        return cards.deviceCalibration(name, device.input());
    }

    /**
     * Spec 4.3's {@code device.setCalibration}: stores the two full-scale RMS
     * volts as this server's calibration of {@code name} in {@code device}'s
     * direction, creating the card when none recognises it, and persists.
     *
     * <p>The write goes to the SERVER's store because that is where the device
     * is plugged in - a client measuring across the room calibrates the bench's
     * card, and every other client's {@code cal} refreshes from the broadcast
     * that follows.
     *
     * @return false when the endpoint's calibration comes from the device itself
     *         (a QA40x reads its own EEPROM), which spec 4.3 refuses with
     *         {@code BAD_REQUEST}: those values are the analyzer's
     */
    public boolean storeCalibration(DeviceLock device, String name, double fsRmsLeft,
            double fsRmsRight) {
        return cards.storeDeviceCalibration(name, device.input(), fsRmsLeft, fsRmsRight);
    }

    /**
     * The logical name of the card IN FORCE for {@code name} on this server -
     * the {@code card} of spec 4.3: the user's binding when one exists, else the
     * name-match resolution, null only when NO card correlates.  It names the
     * card the {@code cal} beside it actually comes from, so a client's chooser
     * shows the truth instead of an empty combo for a device this server
     * recognises without an explicit binding.  Read fresh on every call for the
     * same reason the calibration is.
     *
     * <p>Keyed on the device NAME, which is what the card resolution itself is
     * keyed on: a card IS the physical box, and both its endpoints belong to it.
     * Where a backend gives the two directions different names (the usual case)
     * the binding is per-direction by construction; where it gives them the same
     * name, one box is one card and binding it once is the answer for both.
     */
    public String boundCard(String name) {
        AudioDeviceProfile inForce = cards.resolveDeviceProfile(name);
        return inForce == null ? null : inForce.getName();
    }

    /**
     * Whether the card in force for {@code name} says THIS direction's full scales
     * are the device's own - the {@code calFromDevice} of spec 4.3 (v1.1).
     *
     * <p>It travels for one reason: it is the difference between a client offering
     * a calibration edit and showing the analyzer's own numbers read-only.  Without
     * it a client can only find out by sending the write and being answered
     * {@code BAD_REQUEST} - after the operator has typed a value and been told
     * nothing was wrong with it.  False for a device no card recognises: there is
     * nothing to say the values are anyone's but the operator's.
     */
    public boolean calibrationFromDevice(DeviceLock device, String name) {
        AudioDeviceProfile inForce = cards.resolveDeviceProfile(name);
        if (inForce == null) {
            return false;
        }
        return (device.input() ? inForce.getInput() : inForce.getOutput())
                .isCalibrationFromDevice();
    }

    /**
     * Spec 4.3's {@code device.setCard}: records the user's card choice for
     * {@code name} on this server and persists it; a null / blank {@code card}
     * unbinds, putting the device back under the name-match rule.
     *
     * <p>Binding CHOOSES a card, it writes no values into one - so unlike
     * {@code device.setCalibration} it is allowed on a {@code calibrationFromDevice}
     * card.  That case is the reason the message exists: a bench that has had both
     * a QA402 and a QA403 plugged into it holds a card for each, both of them
     * device-calibrated, and only the operator knows which one is on the table.
     *
     * @return false when this server has no card of that name - a binding may
     *         only name a card that exists, or a typo would quietly uncalibrate
     *         the device (spec 4.3 answers {@code BAD_REQUEST})
     */
    public boolean storeCard(DeviceLock device, String name, String card) {
        if (card != null && !card.isEmpty() && cards.findAudioDeviceProfile(card) == null) {
            return false;
        }
        cards.bindDeviceToCard(name, card);
        return true;
    }

    /**
     * Spec 4.3's {@code cards.list}: this server's device cards by logical name,
     * each saying whether it holds at least one row for a direction - the same
     * filter a client applies to its own cards when it fills the chooser.  A card
     * with no row for a direction cannot calibrate anything there, so offering it
     * would only invite a binding that measures nothing.
     */
    public List<Map<String, Object>> cards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AudioDeviceProfile profile : cards.getAudioDeviceProfiles()) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put(NetFields.NAME, profile.getName());
            card.put(NetFields.INPUT,  !profile.getInput().getRanges().isEmpty());
            card.put(NetFields.OUTPUT, !profile.getOutput().getRanges().isEmpty());
            // The card itself, in the store's own vocabulary (v1.1): a client that
            // may only SEE names can show a chooser but not the range table behind
            // the choice - and `device.setActiveRange` asks it to name a row.
            card.put(NetFields.CONTENT, this.cards.cardToMap(profile));
            out.add(card);
        }
        return out;
    }

    /**
     * Spec 4.3's {@code cards.put}: takes a whole card - name, match list, channel
     * mode, range table and active markers - and stores it on THIS server, where
     * the device it describes is plugged in.
     *
     * <p><b>Create only.</b>  An existing name is refused rather than replaced: a
     * client cannot see what the bench's card holds before it writes, so a silent
     * overwrite would discard another operator's calibration of the same box with
     * nothing on screen to say so.  Re-shaping a card that exists is the bench
     * operator's move, in front of the device.
     *
     * <p>A {@code calibrationFromDevice} card is refused for the reason
     * {@code device.setCalibration} refuses to write one: those values are an
     * analyzer's own, read from its EEPROM where it is plugged in, and a client
     * inventing one would put numbers nobody measured under a device that reports
     * its own.
     *
     * <p>No lock and no broadcast: a card that nothing is bound to is not in force
     * anywhere, so it changes no measurement and no client's {@code cal} view.
     * The {@code device.setCard} that follows is where a binding - and its
     * broadcast - happens.
     *
     * @throws NetException {@code BAD_REQUEST} for a nameless card, a name this
     *         server already has, a device-calibrated card, or a card with no
     *         range row at all
     */
    public void createCard(Map<String, Object> content) {
        AudioDeviceProfile card = content == null ? null : cards.cardFromMap(content);
        if (card == null || card.getName() == null || card.getName().isBlank()) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "cards.put needs a card with a name");
        }
        if (cards.findAudioDeviceProfile(card.getName()) != null) {
            throw new NetException(ErrorCode.BAD_REQUEST, "this server already has a card "
                    + "named '" + card.getName() + "' - cards.put creates, it does not "
                    + "overwrite what somebody else calibrated");
        }
        if (card.getInput().isCalibrationFromDevice()
                || card.getOutput().isCalibrationFromDevice()) {
            throw new NetException(ErrorCode.BAD_REQUEST, "a device-calibrated card is the "
                    + "analyzer's own and is built where the analyzer is plugged in");
        }
        if (card.getInput().getRanges().isEmpty() && card.getOutput().getRanges().isEmpty()) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a card with no range row in either direction calibrates nothing");
        }
        cards.putAudioDeviceProfile(card);
    }

    /**
     * Spec 4.3's {@code device.setActiveRange}: moves the active marker of the
     * card in force for {@code name} to the row {@code label}, and persists.
     *
     * <p>Which row is active IS the full scale in force, so this is a calibration
     * change by another name - hence the same lock rule, and the broadcast its
     * caller sends afterwards.
     *
     * @return false when no card is in force for that device, or the card has no
     *         row of that label - a marker pointing at nothing would silently fall
     *         back to the first row and mis-scale everything measured after it
     */
    public boolean storeActiveRange(DeviceLock device, String name, String label,
            Channel channel) {
        return cards.storeActiveRange(name, device.input(), label, channel);
    }

    /**
     * A fresh enumeration of every served backend, kept for {@link #lastScan()}.
     * The one place hardware is touched, so the three fresh readers -
     * {@code devices.list}, {@code backend.list} and {@code backend.select} -
     * cannot drift on what "current" means.
     *
     * <p>ONE enumeration at a time.  Three independent lanes reach this: any
     * session's request thread, the HTTP pool serving {@code GET /devices}, and
     * the hot-plug rescan - and two of them calling a backend's
     * {@code listInputDevices}/{@code listSupportedFormats} at the same moment
     * would have two threads inside the same {@code AudioDeviceManager} (libusb,
     * for the QA40x).  The monitor is released before the caller builds its
     * payload, so nothing holds it while the lock overlay is read.
     */
    private List<DeviceEntry> scanned() {
        synchronized (scanLock) {
            List<DeviceEntry> devices = enumerate();
            lastDevices = devices;
            return devices;
        }
    }

    private List<DeviceEntry> enumerate() {
        List<DeviceEntry> devices = new ArrayList<>();
        for (AudioBackendType type : served) {
            collect(devices, type, true);
            collect(devices, type, false);
        }
        return devices;
    }

    /** True when any enumerated device of {@code type} offers a choice of sample
     *  width - the per-backend form of the per-device flag (see
     *  {@link #backends()}). */
    private boolean hasBitDepth(List<DeviceEntry> devices, AudioBackendType type) {
        for (DeviceEntry entry : devices) {
            if (entry.device().backend() == type && entry.hasBitDepth()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the enumeration found any device of {@code type} - the presence
     *  half of {@code operational} for a device-bound backend (the QA40x). */
    private boolean hasDevices(List<DeviceEntry> devices, AudioBackendType type) {
        for (DeviceEntry entry : devices) {
            if (entry.device().backend() == type) {
                return true;
            }
        }
        return false;
    }

    private void collect(List<DeviceEntry> devices, AudioBackendType type, boolean input) {
        for (DeviceRef ref : devices(type, input)) {
            List<AudioFormat> formats = input
                    ? audio.listSupportedInputFormats(type, ref)
                    : audio.listSupportedOutputFormats(type, ref);
            devices.add(new DeviceEntry(new DeviceLock(type, ref.index(), input),
                    ref.name(), ref.description(), ref.vendor(), ref.identity(), formats));
        }
    }

    private List<DeviceRef> devices(AudioBackendType type, boolean input) {
        return input ? audio.listInputDevices(type) : audio.listOutputDevices(type);
    }

    /** Groups the entries by backend and adds the two overlays that are NOT
     *  properties of the hardware - the lock, and this server's stored
     *  calibration - both read fresh on every call, which is the whole point of
     *  separating them from the enumeration.  {@code shown} is which backends get
     *  an entry: every served one for {@code devices.list}, exactly the selected
     *  one for {@code backend.select}. */
    private JsonNode payload(List<DeviceEntry> devices, List<AudioBackendType> shown) {
        List<Map<String, Object>> backends = new ArrayList<>();
        for (AudioBackendType type : shown) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (DeviceEntry entry : devices) {
                if (entry.device().backend() == type) {
                    entries.add(entry.toMap(locks.owner(entry.device()),
                            calibration(entry.device(), entry.name()),
                            boundCard(entry.name()),
                            calibrationFromDevice(entry.device(), entry.name())));
                }
            }
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put(NetFields.BACKEND, type.name());
            backend.put(NetFields.DEVICES, entries);
            backends.add(backend);
        }
        return codec.toNode(backends);
    }

    /**
     * One device in one direction, as enumerated: everything spec 4.3 puts in a
     * device object except the lock and the calibration, neither of which is a
     * property of the hardware - both are overlaid at payload time.
     *
     * <p>{@code identity} is the one field that is NOT on the wire: it is what
     * {@link #rescan()} compares two enumerations by (see {@link
     * DeviceRef#identity()}), and a client identifies a device by the ref fields
     * the spec gives it.
     */
    private record DeviceEntry(DeviceLock device, String name, String description,
            String vendor, String identity, List<AudioFormat> formats) {

        Map<String, Object> toMap(ClientSession owner, DeviceCalibration cal, String card,
                boolean calFromDevice) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(NetFields.INDEX, device.index());
            map.put(NetFields.NAME, name);
            map.put(NetFields.DESCRIPTION, description);
            map.put(NetFields.VENDOR, vendor);
            map.put(NetFields.INPUT, device.input());
            map.put(NetFields.OUTPUT, !device.input());
            map.put(NetFields.FORMATS, formatMaps());
            map.put(NetFields.HAS_BIT_DEPTH, hasBitDepth());
            map.put(NetFields.LOCK, owner == null
                    ? null : Map.of(NetFields.BY, owner.getClientName()));
            // JSON null, not an absent field: spec 4.3 makes "no card for it" an
            // answer a client acts on (it falls back to its own defaults), and a
            // missing key would be indistinguishable from an older server.
            map.put(NetFields.CAL, calMap(cal));
            // Same JSON-null convention as cal, for the same reason: "nobody has
            // chosen a card for this device" is an answer the chooser acts on.
            map.put(NetFields.CARD, card);
            // Plain false rather than an omission: a client reads it to decide
            // whether to OFFER a calibration edit at all, and "absent" would have
            // to be read as "editable" - which is the wrong way round for a device
            // whose values are its own.
            map.put(NetFields.CAL_FROM_DEVICE, calFromDevice);
            return map;
        }

        /** The {@code cal} object of spec 4.3, in the order the spec writes it -
         *  an ordered map rather than {@code Map.of} so the wire text is
         *  deterministic, like every other payload in this file. */
        private Map<String, Object> calMap(DeviceCalibration cal) {
            if (cal == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(NetFields.FS_RMS_LEFT, cal.fsRmsLeft());
            map.put(NetFields.FS_RMS_RIGHT, cal.fsRmsRight());
            return map;
        }

        private List<Map<String, Object>> formatMaps() {
            List<Map<String, Object>> maps = new ArrayList<>();
            for (AudioFormat format : formats) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(NetFields.RATE, Math.round(format.getSampleRate()));
                map.put(NetFields.BITS, format.getSampleSizeInBits());
                map.put(NetFields.CHANNELS, format.getChannels());
                maps.add(map);
            }
            return maps;
        }

        /** True when this direction offers more than one sample width, which is
         *  what makes a depth selector meaningful (see the class comment). */
        private boolean hasBitDepth() {
            Set<Integer> depths = new LinkedHashSet<>();
            for (AudioFormat format : formats) {
                depths.add(format.getSampleSizeInBits());
            }
            return depths.size() > 1;
        }
    }
}
