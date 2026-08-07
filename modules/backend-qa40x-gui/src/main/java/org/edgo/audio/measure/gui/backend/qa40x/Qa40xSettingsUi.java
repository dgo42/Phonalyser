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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xCalibration;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceInfo;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xPreferences;
import org.edgo.audio.measure.sound.qa40x.Qa40xProtocol;

import lombok.extern.log4j.Log4j2;

/**
 * Registers the QA40x settings panel with the Preferences dialog, and owns the
 * pending-edit round trip that {@code Qa40xDeviceManager} used to do.
 *
 * <p>A thin adapter rather than {@link Qa40xSettingsDialog} implementing the
 * service directly: the service loader needs a public no-argument constructor and
 * instantiates every provider when the registry is first indexed, whereas the
 * dialog needs its parent shell and a live device reading before it can exist.
 * Keeping them apart means discovery costs nothing and no widget is built until
 * the user actually asks for the panel.
 *
 * <h2>The same panel for a QA403 that is not in this room</h2>
 * A QA403 reached on a Phonalyser server has the same settings as one plugged in
 * here - it is the same analyzer - so it gets the same dialog rather than a
 * second one built beside it.  What differs is only WHERE the values come from:
 * the local device manager, or the net protocol's QA40x extension (spec 4.6)
 * over whichever session reaches that bench.  {@link BackendKey#remote()} on the
 * selection the Preferences dialog hands in is the whole of the decision.
 *
 * <h2>How the remote value is staged</h2>
 * The staging rule is the same for both benches: nothing the operator accepts in
 * this panel may take effect until the Preferences dialog itself is closed with
 * OK.  Locally that is what {@link Qa40xPreferences}' edit copy is for.  A remote
 * bench has no local copy to stage in - the value has to travel - so the accepted
 * value is held here and SENT from {@link #commitEdit()}, the settings-panel hook
 * that means "the operator pressed OK".  Nothing of it is persisted: which port a
 * server's analyzer runs is that server's state, not this installation's - which
 * is exactly why this is not a {@code SubPreferences} block.  A persistence
 * interface implemented for its lifecycle callbacks would have this panel storing
 * an empty section in every user's preferences file to learn one UI event.
 */
@Log4j2
public final class Qa40xSettingsUi implements BackendSettingsUi {

    /** Non-null only between {@link #showForCapture} and {@link #close} - the
     *  screenshot automation needs the instance to survive across calls.  The
     *  modal {@link #open} path keeps nothing. */
    private Qa40xSettingsDialog capture;
    /** The remote bench whose panel was accepted, and the value it was accepted
     *  with - null when there is nothing pending, which is every case but "the
     *  operator changed a remote analyzer's I2S port and has not pressed OK yet". */
    private BackendKey pendingBench;
    private boolean pendingI2s;
    /** The working copy {@link #onSelected} rendered a remote analyzer's card
     *  into, so {@link #commitEdit()} can take it back out again - see
     *  {@link #syncedCards}.  One dialog, one working copy. */
    private Preferences syncedInto;
    /** Every remote analyzer card this dialog session put into that working copy,
     *  by name -> the card that name held BEFORE (null when the name was free).
     *
     *  <p>The calibration of a device on a server is stored on THAT
     *  server and nowhere else.  The card is rendered into the working copy so the
     *  ranges table can show the analyzer's own attenuator positions while the
     *  dialog is open - but the dialog's OK commits that whole copy to this
     *  installation's {@code devices.yaml}, which would leave a bench analyzer's
     *  full-scale table sitting here under a name a LOCAL QA403 would collide
     *  with.  So the card is undone at commit: restored to whatever the operator's
     *  own store held, or removed when it held nothing. */
    private final Map<String, AudioDeviceProfile> syncedCards = new LinkedHashMap<>();

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.QA40X;
    }

    /**
     * Arms the two loose singletons that make up this backend's UI-side wiring.
     *
     * <p>Both singletons are bus listeners with no other owner: the range bridge
     * routes a Preferences-committed active-range change to the open device, and
     * the rate constraint keeps input and output on the QA40x's one shared clock.
     *
     * <p>The device manager's constructor used to call these, which forced the
     * driver module to name classes that live here - a cycle once the two became
     * separate modules.  Starting them from the UI side is the whole point: a
     * headless build has no Preferences dialog to publish those events, so it
     * needs neither listener.
     */
    @Override
    public void start() {
        Qa40xRangeController.instance();
        Qa40xRateConstraint.instance();
    }

    /**
     * The Preferences dialog is showing a QA40x selection.  For the analyzer on
     * a SERVER this is the moment its calibration reaches this client: the
     * bench's factory factors ({@code qa40x.calibration}) and its ranges in
     * force ({@code qa40x.ranges}, spec 4.6) are read - both lock-free reads -
     * and rendered into the same {@code calibrationFromDevice} device card the
     * local backend builds from the USB cal page.  Everything downstream
     * (generator full-scale, the dBV axis, the ranges table the dialog is about
     * to draw) resolves that card by the device's name and needs no remote
     * special case.
     *
     * <p>The card's ACTIVE ranges are set to what the bench reports in force:
     * the analyzer is already running under them, and a card that said
     * otherwise would re-create exactly the display lie this sync exists to end
     * (the attenuator shown as 18 dBV while the output was driven to its full
     * scale).  Committing a DIFFERENT range stays the operator's move, on the
     * dialog's OK, through the range controller - unchanged.
     *
     * <p>A local selection is left alone: its card is built from the EEPROM by
     * the device manager when the analyzer opens.  A bench that will not answer
     * costs the sync, not the dialog - the card, and with it the full-scale
     * math, then simply stays as it was.
     *
     * <p><b>The card goes into the dialog's WORKING COPY.</b>  It used to be
     * written into the live {@link Preferences} singleton and saved there, while
     * the dialog read a snapshot taken before this ran - so the ranges the bench
     * had just described could not appear until the dialog was closed and opened
     * again, and in practice stayed stale even through that OK-and-reopen
     * cycle.  Written here instead, the card is visible to the card section
     * that fills right after, is persisted by the dialog's own OK, and is
     * dropped by its Cancel - the staging rule this panel follows everywhere
     * else.
     */
    @Override
    public void onSelected(BackendKey selection, Preferences editCopy) {
        if (selection == null || !selection.remote() || editCopy == null) {
            return;
        }
        RemoteBackendUi bench = RemoteBackendRegistry.instance().getUi();
        if (bench == null) {
            return;
        }
        Map<String, Object> cal = offDisplayThread(() -> bench.call(selection,
                MessageType.QA40X_CALIBRATION.getWire(), Map.of()));
        Map<String, Object> ranges = offDisplayThread(() -> bench.call(selection,
                MessageType.QA40X_RANGES.getWire(), Map.of()));
        String cardName = remoteDeviceName();
        Integer activeIn  = intField(ranges, NetFields.ACTIVE_INPUT_DBV);
        Integer activeOut = intField(ranges, NetFields.ACTIVE_OUTPUT_DBV);
        if (cal == null || cardName == null || activeIn == null || activeOut == null) {
            if (log.isWarnEnabled()) {
                log.warn("QA40x card sync: {} did not answer calibration/ranges - "
                        + "the full-scale card stays as stored", selection.key());
            }
            return;
        }
        Qa40xCalibration factors = Qa40xCalibration.fromFactors(
                factorRows(cal, NetFields.ADC), factorRows(cal, NetFields.DAC));
        AudioDeviceProfile card = factors.toProfile(cardName,
                editCopy.findAudioDeviceProfile(cardName), activeIn, activeOut);
        // The bench's state, not the stored card's: the analyzer is actually
        // running under these ranges right now.
        card.getInput().setActiveRange(Qa40xProtocol.rangeLabel(activeIn));
        card.getOutput().setActiveRange(Qa40xProtocol.rangeLabel(activeOut));
        // What this name held before the render, so OK can put it back: the
        // copy is the dialog's to show from, never this installation's to keep.
        // Re-selecting the same bench must not record the rendered card as the
        // displaced one, hence the first snapshot wins.
        syncedInto = editCopy;
        syncedCards.putIfAbsent(cardName, editCopy.findAudioDeviceProfile(cardName));
        editCopy.putAudioDeviceProfile(card);
        if (log.isInfoEnabled()) {
            log.info("QA40x card sync: '{}' calibrated from the bench, in {} dBV / out {} dBV",
                    cardName, activeIn, activeOut);
        }
    }

    /**
     * Opens the panel over the bench {@code selection} names: the analyzer wired
     * to this machine, or the one on a Phonalyser server.
     *
     * <p>Either way the accepted value stays PENDING until the Preferences dialog
     * is closed with OK - locally in the backend's own edit copy, remotely in
     * {@link #pendingI2s} until {@link #commitEdit()} sends it.
     */
    @Override
    public void open(Shell parent, BackendKey selection) {
        if (selection != null && selection.remote()) {
            openRemote(parent, selection);
            return;
        }
        Qa40xDeviceManager manager = manager();
        if (manager == null) return;
        Qa40xPreferences settings = manager.getSettings();
        // What the user accepts stays PENDING: it reaches the live settings (and
        // the file) only when the Preferences dialog itself is closed with OK, so
        // that dialog's Cancel discards this too.
        settings.setI2sEnabledEdit(
                new Qa40xSettingsDialog(parent, manager.readDeviceInfo())
                        .open(settings.isI2sEnabledEdit()));
    }

    @Override
    public void showForCapture(Shell parent) {
        Qa40xDeviceManager manager = manager();
        if (manager == null) return;
        capture = new Qa40xSettingsDialog(parent, manager.readDeviceInfo());
        // Never writes the settings back - a help capture must not mutate prefs.
        capture.showForCapture(manager.getSettings().isI2sEnabledEdit());
    }

    @Override
    public Control getContent() {
        return capture == null ? null : capture.getContent();
    }

    @Override
    public void close() {
        if (capture != null) {
            Control content = capture.getContent();
            if (content != null && !content.isDisposed()) {
                content.getShell().close();
            }
            capture = null;
        }
    }

    // -------------------------------------------------------------------------
    // The analyzer on a server - net protocol 4.6
    // -------------------------------------------------------------------------

    /**
     * The same dialog, filled from the bench across the network: its telemetry
     * from {@code qa40x.info} and its port state from {@code qa40x.settings}.
     *
     * <p>A server that will not answer costs the read, not the panel: the fields
     * show what an unread register shows anyway
     * ({@link Qa40xDeviceInfo#UNAVAILABLE}), which is exactly what a locally
     * attached analyzer that could not be read does.
     */
    private void openRemote(Shell parent, BackendKey selection) {
        RemoteBackendUi bench = RemoteBackendRegistry.instance().getUi();
        if (bench == null) {
            return;
        }
        Map<String, Object> settings = offDisplayThread(() -> bench.call(selection,
                MessageType.QA40X_SETTINGS.getWire(), Map.of()));
        boolean i2s = pendingBench != null && pendingBench.equals(selection)
                ? pendingI2s : flag(settings, NetFields.I2S_ENABLED);
        boolean accepted = new Qa40xSettingsDialog(parent,
                remoteInfo(bench, selection)).open(i2s);
        // Only a real CHANGE becomes a pending write.  The dialog answers the seed
        // when it is cancelled, so a panel that was opened and dismissed would
        // otherwise schedule a write of the value the bench already has - where
        // the local path simply re-writes its own edit copy and nothing happens.
        if (accepted != i2s) {
            pendingBench = selection;
            pendingI2s = accepted;
        }
    }

    /**
     * Runs a bench read DIRECTLY on the caller's thread - bounded by the wire
     * timeouts: the border between UI and workers belongs to the controllers,
     * and a browse-time read simply answers when the wire does.
     */
    private <T> T offDisplayThread(Supplier<T> work) {
        return work.get();
    }

    /** The remote analyzer's identity and telemetry, as spec 4.6 formats them -
     *  strings the server already decoded, so nothing is re-interpreted here. */
    private Qa40xDeviceInfo remoteInfo(RemoteBackendUi bench, BackendKey selection) {
        Map<String, Object> info = offDisplayThread(() -> bench.call(selection,
                MessageType.QA40X_INFO.getWire(), Map.of()));
        if (info == null) {
            return Qa40xDeviceInfo.NONE;
        }
        return new Qa40xDeviceInfo(text(info, NetFields.FIRMWARE_VERSION),
                text(info, NetFields.USB_VOLTAGE), text(info, NetFields.USB_CURRENT),
                text(info, NetFields.ISO_CURRENT), text(info, NetFields.TEMPERATURE),
                text(info, NetFields.CAPABILITY), text(info, NetFields.CAPABILITY2),
                text(info, NetFields.SERIAL_NUMBER));
    }

    /** One field of a spec-4.6 payload, or the "could not be read" text - a
     *  server that knows a field this build does not is not a reason to show a
     *  blank row. */
    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? Qa40xDeviceInfo.UNAVAILABLE : value.toString();
    }

    private boolean flag(Map<String, Object> payload, String field) {
        return payload != null && Boolean.TRUE.equals(payload.get(field));
    }

    // -------------------------------------------------------------------------
    // The pending remote write - see the class comment
    // -------------------------------------------------------------------------

    /** A new Preferences session: whatever a previous one left pending was
     *  discarded with its Cancel and must not reach the bench now. */
    @Override
    public void beginEdit() {
        pendingBench = null;
        // A new working copy - whatever the previous session rendered into the old
        // one went away with it.
        syncedInto = null;
        syncedCards.clear();
    }

    /**
     * Takes every rendered bench-analyzer card back out of the working copy before
     * the dialog commits it - restoring the operator's own card of that
     * name when there was one, removing the name when there was not.
     *
     * <p>Called from {@link #commitEdit()}, which the Preferences dialog runs
     * BEFORE it hands the working copy to the live store, so nothing of a remote
     * analyzer's calibration ever reaches this installation's {@code devices.yaml}.
     * Cancel needs no counterpart: the whole copy is dropped.
     */
    private void undoRenderedCards() {
        Preferences copy = syncedInto;
        if (copy == null) {
            return;
        }
        for (Map.Entry<String, AudioDeviceProfile> entry : syncedCards.entrySet()) {
            if (entry.getValue() == null) {
                copy.removeAudioDeviceProfile(entry.getKey());
            } else {
                copy.putAudioDeviceProfile(entry.getValue());
            }
        }
        if (log.isDebugEnabled() && !syncedCards.isEmpty()) {
            log.debug("QA40x card sync: {} bench card(s) left the working copy - a "
                    + "server's analyzer is calibrated on that server", syncedCards.size());
        }
        syncedInto = null;
        syncedCards.clear();
    }

    /** The operator pressed OK - the one moment a remote analyzer may be told to
     *  switch its front-panel port.  The write is a request like any other: a
     *  bench that has gone away in the meantime simply does not get it, and the
     *  panel reads the real state again the next time it opens.
     *
     *  <p>It goes through the LOCKED seam because the port is hardware: spec 4.6
     *  requires the analyzer's lock for a write, and a settings panel holds
     *  nothing of its own, so an ordinary call was answered {@code NOT_LOCKED}
     *  and the port never moved.  Whether the lock has to be TAKEN - the modules
     *  may well be streaming from this bench while the dialog is up - is the
     *  seam's business, not this panel's. */
    @Override
    public void commitEdit() {
        // FIRST, whatever else this commit does: the rendered bench cards leave the
        // working copy before it is handed to the live store.
        undoRenderedCards();
        BackendKey bench = pendingBench;
        pendingBench = null;
        if (bench == null) {
            return;
        }
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return;
        }
        Map<String, Object> answer = offDisplayThread(() -> remote.callLocked(bench,
                MessageType.QA40X_SETTINGS.getWire(),
                Map.of(NetFields.I2S_ENABLED, pendingI2s)));
        if (answer == null && log.isWarnEnabled()) {
            log.warn("QA40x settings: {} did not accept the I2S port change", bench.key());
        }
    }

    /** The remote QA40x's device name - the card's key.  First input, else first
     *  output: the catalogue names the same analyzer both ways. */
    private String remoteDeviceName() {
        AudioBackend audio = AudioBackend.instance();
        if (!audio.isAvailable(AudioBackendType.NET)) {
            return null;
        }
        AudioDeviceManager remote = audio.manager(AudioBackendType.NET);
        for (DeviceRef device : remote.listInputDevices()) {
            return device.name();
        }
        for (DeviceRef device : remote.listOutputDevices()) {
            return device.name();
        }
        return null;
    }

    /** One direction's {@code {dbv,left,right}} rows of a spec-4.6 calibration
     *  payload; anything shaped differently is skipped. */
    private List<Qa40xCalibration.RangeFactor> factorRows(Map<String, Object> payload,
            String field) {
        List<Qa40xCalibration.RangeFactor> rows = new ArrayList<>();
        if (payload.get(field) instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> row
                        && row.get(NetFields.DBV) instanceof Number dbv
                        && row.get(NetFields.LEFT) instanceof Number left
                        && row.get(NetFields.RIGHT) instanceof Number right) {
                    rows.add(new Qa40xCalibration.RangeFactor(dbv.intValue(),
                            left.doubleValue(), right.doubleValue()));
                }
            }
        }
        return rows;
    }

    private Integer intField(Map<String, Object> payload, String field) {
        return payload != null && payload.get(field) instanceof Number value
                ? value.intValue() : null;
    }

    /** The QA40x manager, or {@code null} when this build ships no QA40x backend
     *  - the UI module can be present without its driver. */
    private Qa40xDeviceManager manager() {
        if (!AudioBackend.instance().isAvailable(AudioBackendType.QA40X)) {
            return null;
        }
        AudioDeviceManager m = AudioBackend.instance().manager(AudioBackendType.QA40X);
        return (m instanceof Qa40xDeviceManager qa40x) ? qa40x : null;
    }
}
