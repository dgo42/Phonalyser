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

package org.edgo.audio.measure.sound;

import java.util.Map;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * What every backend whose devices are plugged into THIS machine shares.
 *
 * <p>The calibration of such a device lives in this machine's device cards:
 * a card is the profile the store recognises the device by, and the full
 * scales in force are its active range row's.  This manager reads them out of
 * that profile and writes a measured one into it, then hands the profile back
 * to the store to persist - it holds no full scale itself.  The one manager
 * NOT under this type is the net backend: its devices are plugged into a bench
 * across the network and are calibrated there.
 *
 * <p>Read and write take the store's own monitor, the same one its mutators
 * hold: a MONO write and a fresh card's seed land on two row fields, and a
 * reader that slipped between them (a server building the {@code cal} of
 * spec 4.3 from the same live profile) would publish a half-updated pair - the
 * new left against the old right, a plausible-looking channel imbalance no
 * client could detect.
 */
@Log4j2
public abstract class AbstractLocalDeviceManager implements AudioDeviceManager {

    protected AbstractLocalDeviceManager() {
    }

    @Override
    public DeviceCalibration calibration(DeviceRef device, boolean input) {
        Preferences prefs = Preferences.instance();
        synchronized (prefs) {
            AudioDeviceProfile p = prefs.resolveDeviceProfile(device.name());
            if (p == null) {
                return null;
            }
            return (input ? p.getInput() : p.getOutput()).calibration();
        }
    }

    @Override
    public boolean calibrationFromDevice(DeviceRef device, boolean input) {
        Preferences prefs = Preferences.instance();
        synchronized (prefs) {
            AudioDeviceProfile p = prefs.resolveDeviceProfile(device.name());
            return p != null && (input ? p.getInput() : p.getOutput()).isCalibrationFromDevice();
        }
    }

    /**
     * Writes {@code ch}'s full scale into the card that recognises {@code device}
     * (created when none does), persists it, and tells every subscriber of
     * {@link Events#ADC_CALIBRATION_CHANGED} / {@link Events#DAC_CALIBRATION_CHANGED}.
     * One operation on purpose: a
     * card that changed on disk while the consumers went on with the old value
     * is a calibration the operator watched succeed and the screen never showed.
     *
     * @return {@code false}, with nothing written, when the endpoint's
     *         calibration is the device's own - a QA40x reads its full scales
     *         from its EEPROM, and those are not a caller's to set
     */
    @Override
    public boolean storeCalibration(DeviceRef device, boolean input, Channel ch, double fsRms) {
        String deviceName = device.name();
        Preferences prefs = Preferences.instance();
        synchronized (prefs) {
            AudioDeviceProfile p = prefs.profileFor(deviceName, input);
            DeviceEndpointConfig ep = input ? p.getInput() : p.getOutput();
            if (ep.isCalibrationFromDevice()) {
                if (log.isWarnEnabled()) {
                    log.warn("Ignoring calibration write - calibration is device-provided for {}",
                            p.getName());
                }
                return false;
            }
            ep.storeFullScale(ch, fsRms);
            prefs.putAudioDeviceProfile(p);
        }
        if (log.isInfoEnabled()) {
            log.info("Calibration: stored {} Vrms for '{}' ({}, {}) in this machine's cards",
                    fsRms, deviceName, input ? "input" : "output", ch);
        }
        MessageBus.instance().publish(
                input ? Events.ADC_CALIBRATION_CHANGED : Events.DAC_CALIBRATION_CHANGED, device);
        return true;
    }

    /** A sound card's card is the operator's: chosen, authored and edited here. */
    @Override
    public boolean staticDeviceCard() {
        return false;
    }

    /** The card edit lands in this machine's cards: the card {@code name} is
     *  replaced by {@code card} - under a new name when the editor renamed it,
     *  the old name dropped and every device binding that named it following -
     *  and persisted.  Refused for a name this machine does not have, a
     *  nameless card, or a new name another card holds. */
    @Override
    public boolean updateCard(String name, AudioDeviceProfile card) {
        Preferences prefs = Preferences.instance();
        synchronized (prefs) {
            if (name == null || card == null || card.getName() == null || card.getName().isBlank()
                    || prefs.findAudioDeviceProfile(name) == null) {
                return false;
            }
            boolean renamed = !name.equalsIgnoreCase(card.getName());
            if (renamed && prefs.findAudioDeviceProfile(card.getName()) != null) {
                return false;
            }
            if (renamed) {
                prefs.removeAudioDeviceProfile(name);
                for (Map.Entry<String, String> binding : prefs.getDeviceCardBindings().entrySet()) {
                    if (name.equalsIgnoreCase(binding.getValue())) {
                        prefs.bindDeviceToCard(binding.getKey(), card.getName());
                    }
                }
            }
            prefs.putAudioDeviceProfile(card);
        }
        if (log.isInfoEnabled()) {
            log.info("Card: '{}' replaced by '{}' in this machine's cards", name, card.getName());
        }
        return true;
    }

    /** The rename lands in the card this machine holds for the device - the
     *  endpoint renames its own row and marker, the store persists.  A device
     *  with no card, a device-provided endpoint and a label that is missing,
     *  blank or taken refuse; no full scale moves, so nothing is announced. */
    @Override
    public boolean renameRange(DeviceRef device, boolean input, String label, String newLabel) {
        String deviceName = device.name();
        Preferences prefs = Preferences.instance();
        synchronized (prefs) {
            AudioDeviceProfile p = prefs.resolveDeviceProfile(deviceName);
            if (p == null) {
                return false;
            }
            DeviceEndpointConfig ep = input ? p.getInput() : p.getOutput();
            if (ep.isCalibrationFromDevice() || !ep.renameRange(label, newLabel)) {
                return false;
            }
            prefs.putAudioDeviceProfile(p);
        }
        if (log.isInfoEnabled()) {
            log.info("Card range: renamed '{}' to '{}' for '{}' ({}) in this machine's cards",
                    label, newLabel, deviceName, input ? "input" : "output");
        }
        return true;
    }
}
