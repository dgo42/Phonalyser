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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.Channel;

import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Where a measured full scale goes when the operator presses Calibrate: into
 * this machine's device cards, or onto the bench that owns the device.
 *
 * <p><b>Calibration lives where the device is connected.</b>  A device on this
 * machine is calibrated here, exactly as it always was -
 * {@link Preferences#storeAdcCalibration(Channel, double)} and its siblings
 * do the whole job, card creation included.  A device on a Phonalyser server is
 * the SERVER's to calibrate: the two full scales are sent as spec 4.3's
 * {@code device.setCalibration}, stored in that machine's {@code devices.yaml},
 * and broadcast back to every client - so the next client to connect finds the
 * bench already calibrated, and nothing about a foreign device is ever written
 * into this installation's store, where it could only ever be found again by a
 * name collision.
 *
 * <p>The live scalars move either way and immediately.  They are what the dBV
 * axis and the generator's amplitude are computed against, and the operator
 * pressed Calibrate to make the reading on screen correct - which must not wait
 * for a round trip, and must still hold if the bench refused the write.
 *
 * <p><b>Why the branch is a type of its own.</b>  Three panes commit a
 * calibration (the scope's and the FFT's ADC buttons, the generator's DAC one)
 * and each has its own scale arithmetic before it; only the destination is
 * shared.  Written at the call sites it would be the same decision three times,
 * and the one that was forgotten would quietly write a bench's calibration into
 * the wrong machine's file.
 */
@Log4j2
@RequiredArgsConstructor
public final class CalibrationStore {

    private final Preferences prefs;

    // -------------------------------------------------------------------------
    // ADC - the scope's and the FFT's calibrate buttons
    // -------------------------------------------------------------------------

    /** The shared both-channels ADC full scale: a MONO card, or a device with no
     *  card at all (the local path creates one).
     *
     *  @return whether the calibration was stored - see {@link #applyOnSuccess} */
    public boolean storeAdcCalibration(double fsVrms) {
        BackendKey bench = remoteSelection();
        if (bench == null) {
            prefs.storeAdcCalibration(fsVrms);
            return true;
        }
        if (!sendAdc(bench, fsVrms, fsVrms)) {
            return false;
        }
        prefs.setAdcFsVoltageRms(fsVrms);
        prefs.setAdcFsVoltageRmsRight(fsVrms);
        return true;
    }

    /** One channel's ADC full scale, on a bound stereo card. */
    public boolean storeAdcCalibration(Channel ch, double fsVrms) {
        BackendKey bench = remoteSelection();
        if (bench == null) {
            prefs.storeAdcCalibration(ch, fsVrms);
            return true;
        }
        // The OTHER channel travels unchanged - read before anything moves, so a
        // refused write leaves this client exactly as the bench still has it.
        double left  = ch == Channel.R ? prefs.getAdcFsVoltageRms(Channel.L) : fsVrms;
        double right = ch == Channel.R ? fsVrms : prefs.getAdcFsVoltageRms(Channel.R);
        if (!sendAdc(bench, left, right)) {
            return false;
        }
        if (ch == Channel.R) {
            prefs.setAdcFsVoltageRmsRight(fsVrms);
        } else {
            prefs.setAdcFsVoltageRms(fsVrms);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // DAC - the generator's calibrate button
    // -------------------------------------------------------------------------

    /** The shared both-channels DAC full scale, as a PEAK amplitude - the form
     *  the generator is driven at and the panes measure against. */
    public boolean storeDacCalibration(double fsAmpl) {
        BackendKey bench = remoteSelection();
        if (bench == null) {
            prefs.storeDacCalibration(fsAmpl);
            return true;
        }
        if (!sendDac(bench, fsAmpl, fsAmpl)) {
            return false;
        }
        prefs.setDacFsVoltageAmpl(fsAmpl);
        prefs.setDacFsVoltageAmplRight(fsAmpl);
        return true;
    }

    /** One channel's DAC full scale (peak amplitude), on a bound stereo card. */
    public boolean storeDacCalibration(Channel ch, double fsAmpl) {
        BackendKey bench = remoteSelection();
        if (bench == null) {
            prefs.storeDacCalibration(ch, fsAmpl);
            return true;
        }
        double left  = ch == Channel.R ? prefs.getDacFsVoltageAmpl(Channel.L) : fsAmpl;
        double right = ch == Channel.R ? fsAmpl : prefs.getDacFsVoltageAmpl(Channel.R);
        if (!sendDac(bench, left, right)) {
            return false;
        }
        if (ch == Channel.R) {
            prefs.setDacFsVoltageAmplRight(fsAmpl);
        } else {
            prefs.setDacFsVoltageAmpl(fsAmpl);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Copying an existing local calibration onto a bench
    // -------------------------------------------------------------------------

    /**
     * Sends the calibration THIS machine's card store holds for {@code device}
     * to the bench that owns it - the operator-confirmed copy: when the server
     * has no calibration and this client does have a card for that name, the
     * operator is asked whether to copy those values up.  The copy is never
     * silent.
     *
     * <p>The VALUES alone, which since v1.1 is the FALLBACK: the whole card goes up
     * first ({@code BenchCards.propagateLocalCard}), and this is what still reaches
     * the bench when it will not take the card - a name it already has. No
     * measurement happens here and no scalar moves: the values are already this
     * installation's, and the bench is simply told what they are so it can answer
     * them to every client from then on (spec 4.3 {@code cal}).  The caller asks
     * first - this is never reached without an operator's yes.
     *
     * @return true when the bench stored the pair; false when this machine has no
     *         calibrated card for the device, or the bench would not take it
     */
    public boolean copyCardCalibrationToBench(BackendKey bench, DeviceRef device) {
        DeviceCalibration cal = prefs.deviceCalibration(device.name(), device.isInput());
        if (cal == null) {
            return false;
        }
        return send(bench, device, cal.fsRmsLeft(), cal.fsRmsRight());
    }

    // -------------------------------------------------------------------------
    // The bench
    // -------------------------------------------------------------------------

    /** The selected backend when it lives on a server, else null - the one test
     *  for "is this device somebody else's to calibrate". */
    private BackendKey remoteSelection() {
        BackendKey selected = prefs.getSelectedBackend();
        return selected != null && selected.remote() ? selected : null;
    }

    /**
     * <a id="applyOnSuccess"></a><b>Apply on SUCCESS, never before.</b>  The pair
     * goes to the bench first and this client's live scalars move only when the
     * bench has stored it.
     *
     * <p>They used to move first, on the reasoning that the reading on screen must
     * not wait for a round trip.  But a refusal - the analyzer owns its own values,
     * another client holds the device, the wire is down - left the typed number
     * standing as this client's dBV reference while the bench kept its own, with
     * nothing to put it back: the two disagreed silently until the next device
     * open.  A calibration that did not happen must not look like one that did.
     */
    private boolean sendAdc(BackendKey bench, double left, double right) {
        return send(bench, prefs.current().getInputDeviceName(), true, left, right);
    }

    /** The same for the output lane.  The wire carries RMS in both directions
     *  (spec 4.3), and a DAC full scale is held here as a peak amplitude - so it
     *  is divided by √2 on the way out, the exact conversion the local card write
     *  makes before it stores the value. */
    private boolean sendDac(BackendKey bench, double leftAmpl, double rightAmpl) {
        return send(bench, prefs.current().getOutputDeviceName(), false,
                leftAmpl / Constants.SQRT2, rightAmpl / Constants.SQRT2);
    }

    /**
     * Whether the full scales of the device in force are the DEVICE's own, so the
     * Calibrate dialog opens READ-ONLY instead of offering an edit nobody may
     * make.
     *
     * <p>For a bench device the answer is the bench's, and it travels with the
     * device (spec 4.3 {@code calFromDevice}): the local card store knows nothing
     * about a remote analyzer, and since its rendered card is no longer kept
     * here there is nothing local left to ask.  A local device is exactly
     * the question it always was.
     */
    public boolean isCalibrationFromDevice(boolean input) {
        BackendKey bench = remoteSelection();
        if (bench == null) {
            return input ? prefs.isAdcCalibrationFromDevice()
                         : prefs.isDacCalibrationFromDevice();
        }
        String deviceName = input ? prefs.current().getInputDeviceName()
                                  : prefs.current().getOutputDeviceName();
        DeviceRef device = deviceOf(bench, deviceName, input);
        return device != null && device.calFromDevice();
    }

    /**
     * Whether the CURRENT selection would be measured with no real calibration in
     * this direction - {@link Preferences#isUncalibrated(DeviceRef, boolean)} asked
     * of whatever is selected right now, resolved the same way every other question
     * in this type is.
     *
     * <p>For a bench device the answer is the BENCH's and travels with the device
     * (spec 4.3 {@code cal}): this machine's card of the same name describes a
     * different exemplar of that model and says nothing about it, so a bench that
     * holds no calibration for it is uncalibrated however well the name matches
     * here.  A local device is the name lookup it always was.  A bench selection
     * whose device is not in the catalogue at all counts as uncalibrated too -
     * there is nothing left that could vouch for it.
     */
    public boolean isUncalibrated(boolean input) {
        String deviceName = input ? prefs.current().getInputDeviceName()
                                  : prefs.current().getOutputDeviceName();
        BackendKey bench = remoteSelection();
        if (bench == null) {
            return prefs.isUncalibrated(deviceName, input);
        }
        DeviceRef device = deviceOf(bench, deviceName, input);
        return device == null || prefs.isUncalibrated(device, input);
    }

    /**
     * Spec 4.3's {@code device.setCalibration}, sent under the device lock the
     * bench requires for it - a write changes what every measurement on that
     * device means, so it fails honestly while another client is measuring on it
     * instead of moving the ground under them.
     *
     * <p>A refusal is a warning and nothing else.  The alternative would be to
     * fall back to a card of our own, which is the one thing this must never do:
     * the bench would go on serving its old calibration to every client including
     * this one, and the local card would shadow it by name from then on.
     *
     * @return whether the bench stored the pair - the calibrate buttons ignore it
     *         (their scalars have already moved and the warning above is the whole
     *         report), the operator-confirmed copy reports it back to the operator
     */
    private boolean send(BackendKey bench, String deviceName, boolean input,
            double fsRmsLeft, double fsRmsRight) {
        DeviceRef device = deviceOf(bench, deviceName, input);
        if (device == null) {
            if (log.isWarnEnabled()) {
                log.warn("Calibration: '{}' is not on {}, so the bench keeps its own "
                        + "values", deviceName, bench.key());
            }
            return false;
        }
        return send(bench, device, fsRmsLeft, fsRmsRight);
    }

    /** The same write once the device is known - the one place the request is
     *  built, so the two callers cannot name the device differently. */
    private boolean send(BackendKey bench, DeviceRef device, double fsRmsLeft,
            double fsRmsRight) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return false;
        }
        String deviceName = device.name();
        boolean input = device.isInput();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NetFields.BACKEND, bench.type().name());
        fields.put(NetFields.INDEX, device.index());
        fields.put(NetFields.INPUT, input);
        fields.put(NetFields.NAME, device.name());
        fields.put(NetFields.FS_RMS_LEFT, fsRmsLeft);
        fields.put(NetFields.FS_RMS_RIGHT, fsRmsRight);
        // DIRECT on the caller's thread - a locked write is acquire + write +
        // release, bounded by the wire timeouts.
        Map<String, Object> answer = remote.callLocked(bench,
                MessageType.DEVICE_SET_CALIBRATION.getWire(), fields);
        if (answer == null) {
            if (log.isWarnEnabled()) {
                log.warn("Calibration: {} did not store {} / {} Vrms for '{}'",
                        bench.key(), fsRmsLeft, fsRmsRight, deviceName);
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("Calibration: {} stored {} / {} Vrms for '{}' ({})", bench.key(),
                    fsRmsLeft, fsRmsRight, deviceName, input ? "input" : "output");
        }
        return true;
    }

    /** The remote device the committed name refers to, whose index the request
     *  has to carry (spec 4.3 keys a device on {@code backend, index, input,
     *  name}), or null when the bench no longer offers it. */
    private DeviceRef deviceOf(BackendKey bench, String deviceName, boolean input) {
        if (deviceName == null) {
            return null;
        }
        AudioBackend audio = AudioBackend.instance();
        List<DeviceRef> devices = input
                ? audio.listInputDevices(bench.carrier())
                : audio.listOutputDevices(bench.carrier());
        for (DeviceRef candidate : devices) {
            if (deviceName.equals(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }
}
