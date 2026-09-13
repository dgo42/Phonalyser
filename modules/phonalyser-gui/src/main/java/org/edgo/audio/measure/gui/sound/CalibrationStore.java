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

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * What the calibrate dialogs and the card chooser ask about the calibration of
 * the SELECTED device, resolved the same way whether that device is on this
 * machine or on a bench: is it the device's own, is there one at all - and the
 * operator-confirmed copy of a local card's values onto a bench that has none.
 *
 * <p>A MEASURED calibration does not pass through here.  It goes to the backend
 * the device is on ({@link AudioBackend#calibrate}), which is what decides
 * where it lives: this machine's cards for a local device, the bench's for a
 * device across the network - and for a bench device nothing is written on this
 * machine, not the card and not the live scalars either, because this
 * installation may hold the same model locally with a calibration of its own.
 * The scalars the dBV axis and the generator are computed against follow the
 * bench's broadcast of what it stored ({@code ev.devices.changed}).
 *
 * <p>The questions live here rather than at the three call sites (the scope's
 * and the FFT's calibrate buttons, the generator's) because each would be the
 * same local-or-bench decision three times, and the one that was forgotten would
 * answer a bench selection from this machine's cards.
 */
@Log4j2
@RequiredArgsConstructor
public final class CalibrationStore {

    private final Preferences prefs;

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
     * <p>Two writes, one per channel: spec 4.3's {@code device.setCalibration}
     * carries one channel's full scale, so the card's two values go up one
     * after the other, and the copy is reported stored only when both landed.
     *
     * @return true when the bench stored both channels; false when this
     *         machine has no calibrated card for the device, or the bench would
     *         not take it
     */
    public boolean copyCardCalibrationToBench(BackendKey bench, DeviceRef device) {
        AudioDeviceProfile local = prefs.resolveDeviceProfile(device.name());
        if (local == null) {
            return false;
        }
        DeviceCalibration cal = (device.isInput() ? local.getInput() : local.getOutput())
                .calibration();
        if (cal == null) {
            return false;
        }
        return send(bench, device, Channel.L, cal.fsRmsLeft())
                && send(bench, device, Channel.R, cal.fsRmsRight());
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
     * Spec 4.3's {@code device.setCalibration} for the copy, sent under the
     * device lock the bench requires for it - a write changes what every
     * measurement on that device means, so it fails honestly while another
     * client is measuring on it instead of moving the ground under them.
     *
     * <p>A refusal is a warning and nothing else.  The alternative would be to
     * fall back to a card of our own, which is the one thing this must never do:
     * the bench would go on serving its old calibration to every client including
     * this one, and the local card would shadow it by name from then on.
     *
     * @return whether the bench stored the channel's value, which the
     *         operator-confirmed copy reports back to the operator
     */
    private boolean send(BackendKey bench, DeviceRef device, Channel ch, double fsRms) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return false;
        }
        String deviceName = device.name();
        boolean input = device.isInput();
        String scope = ch == Channel.L ? NetFields.LEFT : NetFields.RIGHT;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NetFields.BACKEND, bench.type().name());
        fields.put(NetFields.INDEX, device.index());
        fields.put(NetFields.INPUT, input);
        fields.put(NetFields.NAME, device.name());
        fields.put(NetFields.CHANNEL, scope);
        fields.put(NetFields.FS_RMS, fsRms);
        // DIRECT on the caller's thread - a locked write is acquire + write +
        // release, bounded by the wire timeouts.
        Map<String, Object> answer = remote.callLocked(bench,
                MessageType.DEVICE_SET_CALIBRATION.getWire(), fields);
        if (answer == null) {
            if (log.isWarnEnabled()) {
                log.warn("Calibration: {} did not store {} Vrms ({}) for '{}'",
                        bench.key(), fsRms, scope, deviceName);
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("Calibration: {} stored {} Vrms ({}) for '{}' ({})", bench.key(),
                    fsRms, scope, deviceName, input ? "input" : "output");
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
