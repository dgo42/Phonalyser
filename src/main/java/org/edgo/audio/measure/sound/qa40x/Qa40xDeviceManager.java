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

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xDevice;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;

import lombok.extern.log4j.Log4j2;

/**
 * Discovery and the single always-duplex session for the {@link
 * AudioBackendType#QA40X} backend — constructed and owned by {@code AudioBackend}
 * (the singleton factory); do not instantiate directly.  Mirrors the WDM-KS /
 * CoreAudio device-manager split (doc §10 "Backend family").
 *
 * <p>Enumeration degrades gracefully: with no device attached, or no
 * {@code libusb-1.0} binding, {@link #listInputDevices()} / {@link
 * #listOutputDevices()} return empty lists and never throw.  Each attached
 * QA402/QA403 is one duplex {@link Qa40xDeviceRef} ({@code isInput() &&
 * isOutput()}), reported identically on both directions.
 *
 * <p><b>Session model (doc §10).</b> On first open the manager does the {@code
 * reset_device} + {@code claim_interface(0)} (via {@link Qa40xDeviceFinder}),
 * reads the factory calibration page once, creates-or-refreshes the device card,
 * and builds ONE {@link Qa40xDuplexEngine}.  {@link Qa40xRecorder} and {@link
 * Qa40xGenerator} are thin clients that {@code attach}/{@code detach} lanes on
 * that one engine: the engine starts on the first attach and stops on the last
 * detach, and is NEVER torn down between back-to-back captures while another
 * client stays attached.  Only a sample-rate change restarts the session (one
 * shared reg-9 clock — the app's input/output rates are constrained equal for
 * this backend, wired in the Preferences dialog).
 *
 * <p>Supported formats are 32-bit, stereo, little-endian in both directions, at
 * 48/96/192&nbsp;kHz on either model plus 384&nbsp;kHz on the QA403 (reg-9 code 3,
 * which the QA402 does not have — doc §4).
 */
@Log4j2
public class Qa40xDeviceManager implements AudioDeviceManager {

    /** Bit depth advertised to the UI and delivered by {@code Qa40xRecorder}: the
     *  wire container is 32-bit LE, but only the 24 MSBs carry signal (the low byte
     *  is zero padding, doc §5, maintainer bench 2026-07-17).  The recorder drops
     *  the pad byte so the delivered sample width equals this depth — the shared
     *  capture path strides and scales by it, so advertised MUST equal delivered
     *  (a mismatch reads every sample at the wrong offset).  The Preferences depth
     *  combo derives its choices from the advertised format list, so this is the
     *  only offered depth — except while the front-panel I2S port is on, when the
     *  list carries that port's 16 / 32-bit frame widths instead. */
    private static final int EFFECTIVE_BITS = Qa40xProtocol.ANALYZER_BITS;
    private static final int CHANNELS      = 2;

    /**
     * Default active ranges when a card is first created — the vendor PyQa40x
     * defaults (input 0&nbsp;dBV, output +18&nbsp;dBV), a deliberate pick per
     * doc §9 item 11 (no hardware power-on default; drivers disagree).
     */
    private static final int DEFAULT_INPUT_DBV  = 0;
    private static final int DEFAULT_OUTPUT_DBV = 18;

    /** Real settle clock for the engine's ABA rate-write delay (doc §8); instant in tests. */
    private static final Qa40xDuplexEngine.Sleeper PRODUCTION_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private final Qa40xDeviceFinder finder;
    private final Qa40xDuplexEngine.Sleeper sleeper;

    private Qa40xTransport    transport;      // null until the device is opened
    private Qa40xCalibration  calibration;    // read once at open
    private Qa40xModel        model;
    private Qa40xDuplexEngine engine;         // the one duplex session
    private int currentRateHz;                // 0 = engine not yet built
    private int inputRangeDbv  = DEFAULT_INPUT_DBV;
    private int outputRangeDbv = DEFAULT_OUTPUT_DBV;
    /** This backend's own settings ({@code custom.qa40x} in preferences.yaml),
     *  registered with Preferences by the production constructor. */
    private final Qa40xPreferences settings = new Qa40xPreferences();

    public Qa40xDeviceManager() {
        this.finder    = new Qa40xDeviceFinder();
        this.sleeper   = PRODUCTION_SLEEPER;
        // Arm the range bridge together with the machinery it serves: the manager
        // is built on the first QA40x dispatch (device enumeration included), so
        // the bus subscription is live before any Preferences OK can publish a
        // range change.  The test-seam constructor stays bus-free.
        Qa40xRangeController.instance();
        // Same for the equal-rate constraint (one shared reg-9 clock, doc §10): its
        // subscription must be live before the Preferences dialog can move a rate combo.
        Qa40xRateConstraint.instance();
        // Hand this backend's own settings block to Preferences, which replays
        // whatever the file held for it — the manager is built lazily, long
        // after load() ran, so registration is where the saved values arrive.
        Preferences.instance().registerCustomPreferences(settings);
    }

    /**
     * Headless test seam: enumeration against an injected (stub) finder — no
     * {@code libusb}, no device, no bus wiring, so it runs on any CI agent.
     */
    Qa40xDeviceManager(Qa40xDeviceFinder finder) {
        this.finder  = finder;
        this.sleeper = PRODUCTION_SLEEPER;
    }

    /**
     * Headless test seam: a pre-opened {@code transport} and an instant
     * {@code sleeper}, so the session lifecycle can be driven without a real
     * device or {@code libusb}.  The card refresh (which needs a calibration
     * page) is skipped.
     */
    Qa40xDeviceManager(Qa40xTransport transport, Qa40xDuplexEngine.Sleeper sleeper) {
        this.finder    = new Qa40xDeviceFinder();
        this.sleeper   = sleeper;
        this.transport = transport;
        this.model     = Qa40xModel.QA403;
    }

    // --- enumeration ---------------------------------------------------------

    @Override
    public List<DeviceRef> listInputDevices() {
        return listDevices();
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return listDevices();
    }

    /** One duplex {@link DeviceRef} per attached QA402/QA403; empty when none / no libusb. */
    private List<DeviceRef> listDevices() {
        List<DeviceRef> out = new ArrayList<>();
        int index = 0;
        for (Qa40xDevice device : finder.list()) {
            out.add(new Qa40xDeviceRef(index++, device.model().name(), device.model()));
        }
        return out;
    }

    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        List<DeviceRef> all = listDevices();
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("QA40x device index out of range: " + index
                    + " (have " + all.size() + " device" + (all.size() == 1 ? "" : "s") + ")");
        }
        return all.get(index);
    }

    /**
     * The QA402/QA403 format set — 32-bit, stereo, little-endian, identical for
     * both directions.  The rate list follows the model: 48/96/192&nbsp;kHz on
     * both, plus 384&nbsp;kHz on the QA403 (reg-9 code 3, which the QA402 lacks).
     */
    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof Qa40xDeviceRef ref)) {
            return new ArrayList<>();
        }
        // OUTPUT only: while the front-panel I2S port is on, the output depth IS
        // that port's frame width (16 or 32) and is what reg 0x0B is written from
        // at session start.  The INPUT is the analyzer's own capture path and
        // always delivers 24 — the I2S port is an output and cannot change it.
        // Read from the EDIT value so the depth combo reacts to the I2S toggle
        // while the Preferences dialog is still open; outside a dialog session
        // edit and live are equal.
        int[] depths = (output && settings.isI2sEnabledEdit())
                ? Qa40xProtocol.i2sBitDepths()
                : new int[] { EFFECTIVE_BITS };
        List<AudioFormat> formats = new ArrayList<>();
        for (int rate : Qa40xProtocol.sampleRatesHz(ref.model())) {
            for (int bits : depths) {
                formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        rate, bits, CHANNELS, (bits / 8) * CHANNELS, rate, false));
            }
        }
        return formats;
    }

    // --- session open + lane clients -----------------------------------------

    /** Opens a capture client bound to this manager's one duplex engine. */
    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return new Qa40xRecorder(this, sampleRate);
    }

    /** Opens a playback client bound to this manager's one duplex engine. */
    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        return new Qa40xGenerator(this, sampleRate, ditherBits);
    }

    /**
     * Returns the one duplex engine, building it on first use (opening the device,
     * reading calibration and refreshing the card) and restarting it if the shared
     * sample rate changed.  Package-private — the lane clients acquire the engine
     * here, then {@code attach}/{@code detach}.
     */
    synchronized Qa40xDuplexEngine acquireEngine(int sampleRateHz) {
        ensureOpen();
        if (engine == null) {
            // The engine reads both at each session boundary, so a Preferences OK
            // between sessions needs no push down to it.  The I2S frame width IS
            // the output bit depth from Preferences ▸ Audio — while the port is
            // on, that combo offers 16 / 32 instead of the analyzer's 24.
            engine = new Qa40xDuplexEngine(transport, sleeper, inputRangeDbv, outputRangeDbv,
                    sampleRateHz, settings::isI2sEnabled,
                    () -> Preferences.instance().current().getOutputBitDepth());
            currentRateHz = sampleRateHz;
        } else if (currentRateHz != sampleRateHz) {
            engine.changeSampleRate(sampleRateHz);   // one shared clock → restart (doc §10)
            currentRateHz = sampleRateHz;
        }
        return engine;
    }

    /** Live input full-scale range change; restarts the running session (doc §10). */
    public synchronized void setInputRange(int dbv) {
        Qa40xProtocol.inputRangeCode(dbv);   // validate
        inputRangeDbv = dbv;
        if (engine != null) {
            engine.changeInputRange(dbv);
        }
    }

    /** Live output full-scale range change; restarts the running session (doc §10). */
    public synchronized void setOutputRange(int dbv) {
        Qa40xProtocol.outputRangeCode(dbv);  // validate
        outputRangeDbv = dbv;
        if (engine != null) {
            engine.changeOutputRange(dbv);
        }
    }

    /**
     * Routes a Preferences-committed active-range change to the device — the sink
     * for the {@code DEVICE_ACTIVE_RANGE_CHANGED} bus event (maintainer order: "if
     * QA40x card exists and active it will receive it and send to device").  A no-op
     * unless {@code activeBackend} is {@link AudioBackendType#QA40X} and {@code
     * cardName} is THIS open device's card; otherwise defers to {@link #setInputRange}
     * / {@link #setOutputRange}, which restart a running session or store the range for
     * the next open when idle.  {@code activeBackend} and {@code cardName} arrive from
     * the caller so the whole decision is one testable step.
     */
    public synchronized void applyActiveRangeChange(AudioBackendType activeBackend,
                                                    String cardName, boolean input, int dbv) {
        if (activeBackend != AudioBackendType.QA40X || model == null || !model.name().equals(cardName)) {
            return;
        }
        if (input) {
            setInputRange(dbv);
        } else {
            setOutputRange(dbv);
        }
    }

    /** Logical name of this open device's card (the model name, e.g. {@code "QA403"}),
     *  or {@code null} before the device is opened — the card the range routing targets. */
    public synchronized String cardName() {
        return model == null ? null : model.name();
    }

    /** The QA40x has settings no other backend shares (the front-panel I2S
     *  expansion port), so the Preferences dialog offers a button for them. */
    @Override
    public boolean hasCustomPreferences() {
        return true;
    }

    /**
     * Reads the identity + telemetry registers and decodes them for display
     * (doc §4 / §6), opening the device first if no session has done so yet.
     * Returns {@link Qa40xDeviceInfo#NONE} when nothing is attached, and
     * degrades the same way if a read fails part-way: this feeds a read-only
     * panel, so an absent or wedged analyzer must show dashes rather than break
     * the dialog it is on.  The ISO-supply current exists on the QA402 only; a
     * QA403 reports it as unavailable.
     */
    public synchronized Qa40xDeviceInfo readDeviceInfo() {
        try {
            // Opening the device is what makes the registers readable at all: the
            // session opens lazily on the first capture, so without this the
            // panel would only ever show values after a measurement had run.
            // Same open the session uses — idempotent when one is already live.
            ensureOpen();
            boolean hasIso = model == Qa40xModel.QA402;
            return new Qa40xDeviceInfo(
                    Integer.toString(transport.registerRead(Qa40xProtocol.REG_FIRMWARE_VERSION)),
                    Qa40xProtocol.formatUsbVoltage(transport.registerRead(Qa40xProtocol.REG_TELEM_USB_VOLTAGE)),
                    Qa40xProtocol.formatCurrent(transport.registerRead(Qa40xProtocol.REG_TELEM_USB_CURRENT)),
                    hasIso ? Qa40xProtocol.formatCurrent(transport.registerRead(Qa40xProtocol.REG_TELEM_ISO_CURRENT))
                           : Qa40xDeviceInfo.UNAVAILABLE,
                    Qa40xProtocol.formatTemperature(transport.registerRead(Qa40xProtocol.REG_TELEM_TEMPERATURE)),
                    Qa40xProtocol.formatCapability(transport.registerRead(Qa40xProtocol.REG_CAPABILITY)),
                    Qa40xProtocol.formatCapability(transport.registerRead(Qa40xProtocol.REG_CAPABILITY2)),
                    Qa40xProtocol.formatSerialNumber(transport.registerRead(Qa40xProtocol.REG_SERIAL_NUMBER)));
        } catch (Throwable t) {
            log.warn("QA40x device info read failed: {}", t.toString());
            return Qa40xDeviceInfo.NONE;
        }
    }

    /** Opens the QA40x settings dialog.  What the user accepts stays PENDING —
     *  it reaches the live settings (and the file) only when the Preferences
     *  dialog itself is closed with OK, so its Cancel discards this too. */
    @Override
    public void openCustomPreferences(Shell parent) {
        settings.setI2sEnabledEdit(
                new Qa40xSettingsDialog(parent, readDeviceInfo()).open(settings.isI2sEnabledEdit()));
    }

    /** Opens the settings dialog for a HELP CAPTURE — fully populated from the
     *  live device, but without the blocking modal loop, so the automation can
     *  snapshot it and dispose it.  {@link #openCustomPreferences} is the normal
     *  entry; this one never touches the settings. */
    public Qa40xSettingsDialog openCustomPreferencesForCapture(Shell parent) {
        Qa40xSettingsDialog capture = new Qa40xSettingsDialog(parent, readDeviceInfo());
        capture.showForCapture(settings.isI2sEnabledEdit());
        return capture;
    }

    /**
     * App-exit teardown: leaves the analyzer in its protected idle state
     * (doc §7 Teardown steps 8–9) — stream stopped, input +42 dBV (maximum
     * attenuation), output −12 dBV — then releases the transport.  Without
     * this the device kept whatever range the last measurement used: a
     * 0 dBV session left the input at maximum sensitivity, unprotected,
     * after the app quit.  No-op when the device was never opened.  Never
     * throws: the app exit path must not be blocked by an unplugged or
     * wedged device.
     */
    @Override
    public synchronized void shutdown() {
        if (transport == null) {
            return;
        }
        try {
            transport.registerWrite(Qa40xProtocol.REG_RUN, Qa40xProtocol.RUN_STOP);
            transport.registerWrite(Qa40xProtocol.REG_INPUT_FS,
                    Qa40xProtocol.inputRangeCode(Qa40xProtocol.SAFE_INPUT_DBV));
            transport.registerWrite(Qa40xProtocol.REG_OUTPUT_FS,
                    Qa40xProtocol.outputRangeCode(Qa40xProtocol.SAFE_OUTPUT_DBV));
            log.info("QA40x safe-state close: input +{} dBV (attenuator engaged), output {} dBV",
                    Qa40xProtocol.SAFE_INPUT_DBV, Qa40xProtocol.SAFE_OUTPUT_DBV);
        } catch (Throwable t) {
            log.warn("QA40x safe-state write failed (device unplugged?): {}", t.toString());
        }
        try {
            transport.close();
        } catch (Throwable t) {
            log.warn("QA40x transport close failed: {}", t.toString());
        }
        transport     = null;
        calibration   = null;
        model         = null;
        engine        = null;
        currentRateHz = 0;
    }

    private void ensureOpen() {
        if (transport != null) {
            return;                          // already open (or injected for tests)
        }
        List<Qa40xDevice> devices = finder.list();
        if (devices.isEmpty()) {
            throw new IllegalStateException("No QA402/QA403 attached (or libusb-1.0 unavailable)");
        }
        model = devices.get(0).model();
        transport = finder.open();           // reset + claim; enforces the single-device rule
        calibration = Qa40xCalibration.fromTransport(transport);
        refreshDeviceCard();
        log.info("QA40x session open: {} (input {} dBV, output {} dBV)", model, inputRangeDbv, outputRangeDbv);
    }

    // --- device card (doc §10 dBV/cal plumbing; device-profile policy) --------

    /**
     * Creates-or-refreshes this device's card in the store from the freshly read
     * calibration page, preserving the user's active-range selections (the
     * profile-policy survival rules), and PERSISTS it to {@code devices.yaml}.  Both
     * endpoints are marked {@code calibrationFromDevice} so calibrate flows refuse
     * with a WARN and the seed merge takes the values wholesale.
     *
     * <p>Runs once per session — {@link #ensureOpen()} opens the device (and calls
     * this) only on the first {@link #acquireEngine}, i.e. a user-started capture /
     * playback, so the app is already running.  The persist is guarded by
     * {@link Preferences#saveDevices()}, a no-op in transient (CLI) mode and on a
     * detached copy, so a headless run or a test never writes the store — only a
     * live GUI session records the device's calibration.
     */
    private void refreshDeviceCard() {
        if (calibration == null || model == null) {
            return;
        }
        String cardName = model.name();
        Preferences prefs = Preferences.instance();
        AudioDeviceProfile card = buildProfile(cardName, calibration, prefs.findAudioDeviceProfile(cardName));
        prefs.putAudioDeviceProfile(card);
        prefs.saveDevices();     // persist the device-read calibration; no-op in CLI / tests
        inputRangeDbv  = Qa40xProtocol.rangeDbv(card.getInput().getActiveRange(),  Qa40xProtocol.inputRangeDbvValues(),  DEFAULT_INPUT_DBV);
        outputRangeDbv = Qa40xProtocol.rangeDbv(card.getOutput().getActiveRange(), Qa40xProtocol.outputRangeDbvValues(), DEFAULT_OUTPUT_DBV);
    }

    /**
     * Builds the QA40x device profile: a LINKED stereo endpoint per direction, one
     * range row per attenuator/gain position with per-channel full-scale RMS volts
     * derived from {@link Qa40xLevels} and the device's own cal-page factors, both
     * endpoints {@code calibrationFromDevice}.  When an {@code existing} card is
     * present its active-range selections survive the refresh.
     */
    AudioDeviceProfile buildProfile(String cardName, Qa40xCalibration cal, AudioDeviceProfile existing) {
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName(cardName);
        card.getMatch().add(cardName);
        if (existing != null) {
            for (String entry : existing.getMatch()) {
                if (!card.getMatch().contains(entry)) {
                    card.getMatch().add(entry);
                }
            }
        }
        card.setInput(buildEndpoint(true,  cal, existing != null ? existing.getInput()  : null));
        card.setOutput(buildEndpoint(false, cal, existing != null ? existing.getOutput() : null));
        return card;
    }

    private DeviceEndpointConfig buildEndpoint(boolean input, Qa40xCalibration cal, DeviceEndpointConfig existing) {
        DeviceEndpointConfig ep = new DeviceEndpointConfig();
        ep.setChannels(DeviceChannelMode.LINKED);
        ep.setCalibrationFromDevice(true);
        int[] dbvValues = input ? Qa40xProtocol.inputRangeDbvValues() : Qa40xProtocol.outputRangeDbvValues();
        for (int dbv : dbvValues) {
            DeviceRange row = new DeviceRange();
            row.setLabel(Qa40xProtocol.rangeLabel(dbv));   // plain "N dBV" — the persisted key
            if (input) {
                // The input "N dBV" range is really an N-dBFS (Vpp-differential)
                // reference; show its real levels in the ranges table while the key
                // stays plain (doc §6 cheat-sheet).
                row.setDisplayLabel(Qa40xProtocol.verboseInputLabel(dbv));
                row.setFsLeft(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, false)));
                row.setFsRight(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, true)));
            } else {
                row.setFsLeft(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, false)));
                row.setFsRight(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, true)));
            }
            row.setCalibrated(true);         // device-owned values — never seed-refreshed away
            ep.getRanges().add(row);
        }
        String defaultActive = Qa40xProtocol.rangeLabel(input ? DEFAULT_INPUT_DBV : DEFAULT_OUTPUT_DBV);
        String active = (existing != null && hasRow(ep, existing.getActiveRange()))
                ? existing.getActiveRange() : defaultActive;
        ep.setActiveRange(active);
        if (existing != null && hasRow(ep, existing.getActiveRangeRight())) {
            ep.setActiveRangeRight(existing.getActiveRangeRight());
        }
        return ep;
    }

    private boolean hasRow(DeviceEndpointConfig ep, String label) {
        if (label == null) {
            return false;
        }
        for (DeviceRange row : ep.getRanges()) {
            if (label.equals(row.getLabel())) {
                return true;
            }
        }
        return false;
    }

    /** {@link DeviceRef} for one attached QA402/QA403 — a single duplex endpoint. */
    public record Qa40xDeviceRef(int index, String name, Qa40xModel model) implements DeviceRef {
        @Override
        public String description() {
            return "QuantAsylum QA40x";
        }
        @Override
        public String vendor() {
            return "QuantAsylum";
        }
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.QA40X;
        }
        @Override
        public boolean isInput() {
            return true;
        }
        @Override
        public boolean isOutput() {
            return true;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }
}
