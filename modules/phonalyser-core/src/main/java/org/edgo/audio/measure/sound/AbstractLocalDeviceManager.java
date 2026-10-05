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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
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

    /** The capture streams this long before the pulse is written, so its line
     *  is running and its noise baseline is taken on silence. */
    private static final long DELAY_CAPTURE_WARM_MS = 25;
    /** How long each step of the pulses - the low level, each edge's answer,
     *  each edge - may take to arrive. */
    private static final long DELAY_EDGE_TIMEOUT_MS = 2_000;
    /** How many pulses one measurement sends; every edge of every pulse is
     *  measured and the smallest delay is the reading. */
    private static final int DELAY_PULSES = 5;

    protected AbstractLocalDeviceManager() {
    }

    @Override
    public float measureDelay(DeviceRef out, DeviceRef in, DelayProbe probe) throws InterruptedException {
        DeviceCalibration dac = calibration(out, false);
        if (dac == null) {
            dac = DeviceCalibration.DEFAULT;
        }
        double levelLeft  = probe.lanes() != OutputChannels.RIGHT
                ? probe.amplitudeVrms() / dac.peakVolts(Channel.L) : 0.0;
        double levelRight = probe.lanes() != OutputChannels.LEFT
                ? probe.amplitudeVrms() / dac.peakVolts(Channel.R) : 0.0;
        // The capture is this backend's own; the playback goes through the
        // render-path policy, which renders a WASAPI device on the JavaSound
        // line - the one that can time a write.  A local device renders on a
        // local manager, so the route is one of this family and opens the
        // pulse's own line.
        AudioCapture  capture  = openCapture(in, probe.inputRate(), probe.inputBits());
        AbstractLocalDeviceManager renderer =
                (AbstractLocalDeviceManager) AudioBackend.instance().playbackManager(out);
        AudioPlayback playback = renderer.openPulsePlayback(out, probe.outputRate(), probe.outputBits());
        try {
            capture.open();
            playback.open();
            PulseHandshake handshake = new PulseHandshake(DELAY_PULSES);
            AtomicBoolean captured = new AtomicBoolean();
            Thread captureThread = new Thread(() -> {
                try {
                    captured.set(capture.capturePulse(probe.edgeChannel(), handshake,
                            DELAY_EDGE_TIMEOUT_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "delay-capture");
            // Both threads stamp the exchange; a time slice waited for under
            // load would enter the reading.
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            Thread.sleep(DELAY_CAPTURE_WARM_MS);
            Thread pulseThread = new Thread(() -> {
                try {
                    playback.pulse(levelLeft, levelRight, handshake);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    if (log.isWarnEnabled()) {
                        log.warn("Delay measurement: the pulse was not written", ex);
                    }
                } finally {
                    handshake.abandon();
                }
            }, "delay-pulse");
            pulseThread.setPriority(Thread.MAX_PRIORITY);
            pulseThread.start();
            captureThread.join();
            pulseThread.join();
            // One delay per edge; the reading is the smallest, every other
            // edge having waited for something on the way.
            double best  = Double.NaN;
            int    valid = 0;
            StringBuilder perEdge = new StringBuilder();
            for (PulseHandshake.Edge edge : handshake.getEdges()) {
                double ms = edge.delayMs();
                perEdge.append(perEdge.length() == 0 ? "" : ", ")
                       .append(edge.isRising() ? "rise " : "fall ")
                       .append(Double.isNaN(ms) ? "-" : String.format(Locale.ROOT, "%.3f", ms));
                if (Double.isNaN(ms)) {
                    continue;
                }
                valid++;
                best = Double.isNaN(best) ? ms : Math.min(best, ms);
            }
            if (valid == 0) {
                if (log.isInfoEnabled()) {
                    log.info("Delay measurement: no result (edges: {}; all captured: {})",
                            perEdge, captured.get());
                }
                return Float.NaN;
            }
            float delayMs = (float) best;
            if (log.isInfoEnabled()) {
                log.info("Delay measured: {} ms - the smallest of {} edges ({} ms; {} V RMS, out {}, in {};"
                        + " all captured: {})", delayMs, valid, perEdge, probe.amplitudeVrms(),
                        probe.lanes(), probe.edgeChannel(), captured.get());
            }
            return delayMs;
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("Delay measurement failed", ex);
            }
            return Float.NaN;
        } finally {
            capture.stopRecording();
            capture.close();
            playback.close();
        }
    }

    /**
     * The playback line the delay measurement sends its pulses on: the render
     * line as {@link #openPlayback} opens it, without dither.  A backend whose
     * line can be opened on a shorter queue overrides this, so the pulses'
     * edges, each of which waits out the queue, follow each other faster.
     */
    protected AudioPlayback openPulsePlayback(DeviceRef device, int sampleRate, int bitDepth) {
        return openPlayback(device, sampleRate, bitDepth, 0.0, 0.0);
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
