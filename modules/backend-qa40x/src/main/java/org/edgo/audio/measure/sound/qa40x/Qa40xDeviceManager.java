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

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xDevice;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Discovery and the single always-duplex session for the {@link
 * AudioBackendType#QA40X} backend - constructed and owned by {@code AudioBackend}
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
 * shared reg-9 clock - the app's input/output rates are constrained equal for
 * this backend, wired in the Preferences dialog).
 *
 * <p>Supported formats are 32-bit, stereo, little-endian in both directions, at
 * 48/96/192&nbsp;kHz on either model plus 384&nbsp;kHz on the QA403 (reg-9 code 3,
 * which the QA402 does not have - doc §4).
 */
@Log4j2
public class Qa40xDeviceManager implements AudioDeviceManager, Qa40xControl {

    /** Bit depth advertised to the UI and delivered by {@code Qa40xRecorder}: the
     *  wire container is 32-bit LE, but only the 24 MSBs carry signal (the low byte
     *  is zero padding, doc §5).  The recorder drops
     *  the pad byte so the delivered sample width equals this depth - the shared
     *  capture path strides and scales by it, so advertised MUST equal delivered
     *  (a mismatch reads every sample at the wrong offset).  The Preferences depth
     *  combo derives its choices from the advertised format list, so this is the
     *  only offered depth - except while the front-panel I2S port is on, when the
     *  list carries that port's 16 / 32-bit frame widths instead. */
    private static final int EFFECTIVE_BITS = Qa40xProtocol.ANALYZER_BITS;
    private static final int CHANNELS      = 2;

    /**
     * Default active ranges when a card is first created - the vendor PyQa40x
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
    /** WHICH physical analyzer the open session is bound to - the finder's model
     *  plus USB bus/address.  Null while nothing is open.  Kept because a handle
     *  says nothing about the device still being there: unplug the analyzer and
     *  plug it back in and it re-enumerates at a NEW address, while this manager
     *  goes on writing the old one and every register write fails with
     *  {@code LIBUSB_ERROR_IO} until the process is restarted.  See
     *  {@link #dropSessionIfMoved}. */
    private Qa40xDevice       openSession;
    private int currentRateHz;                // 0 = engine not yet built
    private int inputRangeDbv  = DEFAULT_INPUT_DBV;
    private int outputRangeDbv = DEFAULT_OUTPUT_DBV;
    /** This backend's own settings ({@code custom.qa40x} in preferences.yaml),
     *  registered with Preferences by the production constructor.  Exposed so the
     *  settings UI in the module above can read and edit them without this module
     *  knowing that a UI exists. */
    @Getter
    private final Qa40xPreferences settings = new Qa40xPreferences();

    public Qa40xDeviceManager() {
        this.finder    = new Qa40xDeviceFinder();
        this.sleeper   = PRODUCTION_SLEEPER;
        // The range bridge and the equal-rate constraint used to be armed here.
        // Both are bus listeners that belong to the UI layer, and they now live in
        // the backend-qa40x-gui module, which sits ABOVE this one - so naming them
        // here would be a cycle.  They are started once, from the UI side, through
        // the backend-startup service (see Qa40xUiStartup in that module).
        // Hand this backend's own settings block to Preferences, which replays
        // whatever the file held for it - the manager is built lazily, long
        // after load() ran, so registration is where the saved values arrive.
        Preferences.instance().registerCustomPreferences(settings);
    }

    /**
     * Headless test seam: enumeration against an injected (stub) finder - no
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
        List<Qa40xDevice> attached = finder.list();
        // Every scan is also the check that the session this manager holds is
        // still the analyzer on the bus - see dropSessionIfMoved for why a scan
        // is the right place and what it costs not to.
        dropSessionIfMoved(attached);
        List<DeviceRef> out = new ArrayList<>();
        int index = 0;
        for (Qa40xDevice device : attached) {
            out.add(new Qa40xDeviceRef(index++, device.model().name(), device.model(),
                    device.toString()));
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
     * The QA402/QA403 format set - 32-bit, stereo, little-endian, identical for
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
        // always delivers 24 - the I2S port is an output and cannot change it.
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
     * This backend's own reading of its own failures - capture and playback
     * alike, because both lanes ride the one USB transport and fail with the
     * same words.
     *
     * <p>The vocabulary is libusb's: {@code LibUsb.errorName} puts
     * {@code LIBUSB_ERROR_*} into the message and {@code transferStatusName}
     * puts {@code NO_DEVICE} / {@code TIMED_OUT} there, so both spellings of the
     * same fact are matched.  The two prose refusals are this module's own -
     * nothing on the bus and no libusb at all - and neither can be answered by
     * a card that is not there.
     */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        if (failure == null) {
            return DeviceFailureReason.UNKNOWN;
        }
        String text = failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (text.contains("no_device")) {
            return DeviceFailureReason.DEVICE_DISCONNECTED;
        }
        if (text.contains("timed_out") || text.contains("libusb_error_timeout")) {
            return DeviceFailureReason.DEVICE_NOT_ANSWERING;
        }
        if (text.contains("libusb_error_access") || text.contains("libusb_error_busy")) {
            return DeviceFailureReason.DEVICE_IN_USE;
        }
        if (text.contains("libusb_error_not_found")
                || text.contains("no qa402/qa403")
                || text.contains("libusb-1.0 not available")) {
            return DeviceFailureReason.DEVICE_NOT_FOUND;
        }
        return DeviceFailureReason.UNKNOWN;
    }

    /**
     * Returns the one duplex engine, building it on first use (opening the device,
     * reading calibration and refreshing the card) and restarting it if the shared
     * sample rate changed.  Package-private - the lane clients acquire the engine
     * here, then {@code attach}/{@code detach}.
     */
    synchronized Qa40xDuplexEngine acquireEngine(int sampleRateHz) {
        return onTheDevice(() -> buildEngine(sampleRateHz));
    }

    /** The engine build itself - every register write in it is on the device, so
     *  it runs inside {@link #onTheDevice}. */
    private Qa40xDuplexEngine buildEngine(int sampleRateHz) {
        ensureOpen();
        if (engine == null) {
            // The engine reads both at each session boundary, so a Preferences OK
            // between sessions needs no push down to it.  The I2S frame width IS
            // the output bit depth from Preferences ▸ Audio - while the port is
            // on, that combo offers 16 / 32 instead of the analyzer's 24.
            engine = new Qa40xDuplexEngine(transport, sleeper, inputRangeDbv, outputRangeDbv,
                    sampleRateHz, settings::isI2sEnabled,
                    () -> Preferences.instance().current().getOutputBitDepth());
            currentRateHz = sampleRateHz;
        } else if (currentRateHz != sampleRateHz) {
            engine.changeSampleRate(sampleRateHz);   // one shared clock -> restart (doc §10)
            currentRateHz = sampleRateHz;
        }
        return engine;
    }

    // --- Qa40xControl: what the settings panel and the net server read --------

    /** {@inheritDoc}  The vendor's attenuator positions (doc §6). */
    @Override
    public int[] inputRangesDbv() {
        return Qa40xProtocol.inputRangeDbvValues();
    }

    /** {@inheritDoc}  The vendor's output gain positions (doc §6). */
    @Override
    public int[] outputRangesDbv() {
        return Qa40xProtocol.outputRangeDbvValues();
    }

    /** {@inheritDoc}  The value a new session opens at: the card's active range
     *  once the device has been opened, the vendor default before that. */
    @Override
    public synchronized int activeInputRangeDbv() {
        return inputRangeDbv;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized int activeOutputRangeDbv() {
        return outputRangeDbv;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Built from the SAME cal-page factors and range list the device card is
     * built from (see {@link #buildEndpoint}), so a client reading the page over
     * the net gets exactly what a locally attached analyzer would give it.  The
     * device is opened if no session has done so yet - the page lives on the
     * hardware and there is no honest answer without it.
     */
    @Override
    public synchronized List<CalibrationRow> calibration(boolean input) {
        return onTheDevice(() -> calibrationRows(input));
    }

    /** The cal page as rows - on the device, so it runs inside
     *  {@link #onTheDevice}: reading it is what opens the session. */
    private List<CalibrationRow> calibrationRows(boolean input) {
        ensureOpen();
        List<CalibrationRow> rows = new ArrayList<>();
        for (int dbv : input ? Qa40xProtocol.inputRangeDbvValues()
                : Qa40xProtocol.outputRangeDbvValues()) {
            rows.add(new CalibrationRow(dbv,
                    input ? calibration.adcLinearFactor(dbv, false)
                          : calibration.dacLinearFactor(dbv, false),
                    input ? calibration.adcLinearFactor(dbv, true)
                          : calibration.dacLinearFactor(dbv, true)));
        }
        return rows;
    }

    /** {@inheritDoc}  The LIVE value - what the engine reads at each session
     *  boundary - not the settings dialog's pending edit. */
    @Override
    public boolean isI2sEnabled() {
        return settings.isI2sEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the live value AND the edit value: this is the committed path (a
     * net client's {@code qa40x.settings} has already been through that client's
     * own OK), and leaving the edit copy behind would let the next local dialog
     * session open showing the old state and commit it straight back.
     */
    @Override
    public void setI2sEnabled(boolean enabled) {
        settings.setI2sEnabledEdit(enabled);
        settings.commitEdit();
    }

    /** Live input full-scale range change; restarts the running session (doc §10)
     *  and records the new range as the card's active row (see
     *  {@link #storeActiveRange}). */
    @Override
    public synchronized void setInputRange(int dbv) {
        Qa40xProtocol.inputRangeCode(dbv);   // validate
        inputRangeDbv = dbv;
        onTheDevice(() -> {
            if (engine != null) {
                engine.changeInputRange(dbv);
            }
            return null;
        });
        storeActiveRange(true, dbv);
    }

    /** Live output full-scale range change; restarts the running session (doc §10)
     *  and records the new range as the card's active row (see
     *  {@link #storeActiveRange}). */
    @Override
    public synchronized void setOutputRange(int dbv) {
        Qa40xProtocol.outputRangeCode(dbv);  // validate
        outputRangeDbv = dbv;
        onTheDevice(() -> {
            if (engine != null) {
                engine.changeOutputRange(dbv);
            }
            return null;
        });
        storeActiveRange(false, dbv);
    }

    /**
     * Records the range now IN FORCE as this device's card active row, and
     * persists it.
     *
     * <p><b>Why the store has to follow the register.</b>  Which range the
     * attenuator sits at is DEVICE state, and every full-scale the rest of the
     * application resolves - the dBV axis, the generator's amplitude, and the
     * {@code cal} a net server publishes for its own devices (spec 4.3) - is read
     * out of the card's ACTIVE row.  Before this, a range change moved the
     * register and the in-memory {@code inputRangeDbv} only: the card went on
     * naming the row the last card refresh selected, so anything reading the
     * store was told the full scale of a range the analyzer is no longer on.
     * Locally the ranges dialog happened to commit the same label on OK and hid
     * it; on a headless server there is no dialog to do that, and calibration
     * lives where the device is connected - so
     * the server's own store IS the answer every client gets.
     *
     * <p><b>It must work with the analyzer CLOSED</b>, because closed is the
     * normal state when the write arrives.  A range change is committed from a
     * Preferences OK, local or across the net, and nothing streams at that moment
     * - the session opens lazily, at the first capture or playback.  While this
     * only moved {@link #inputRangeDbv} the operator's choice was answered "ok",
     * broadcast to every client, and then silently discarded: the next
     * {@link #ensureOpen()} runs {@link #refreshDeviceCard()}, which reads the
     * ranges back OUT of the card, so the field went straight back to the row the
     * card still named.  With the row moved instead, that same read-back is what
     * carries the new range into the registers.
     *
     * <p>Only an existing card is updated.  A card is created by
     * {@link #refreshDeviceCard()} from the device's own calibration page when the
     * session opens; there is no range to record before there is a card, and
     * inventing one here would write a card with no calibration in it.
     *
     * <p>The live store object is mutated and saved rather than re-put: it is
     * already in the list, so an add/replace would only move it to the end and
     * churn {@code devices.yaml} on every range change.  {@code saveDevices()} is
     * a no-op in transient (CLI) mode and on a detached copy, so a headless run
     * and a test never write the file.
     */
    private void storeActiveRange(boolean input, int dbv) {
        Preferences prefs = Preferences.instance();
        AudioDeviceProfile card = rangeCard(prefs);
        if (card == null) {
            return;
        }
        DeviceEndpointConfig endpoint = input ? card.getInput() : card.getOutput();
        endpoint.setActiveRange(Qa40xProtocol.rangeLabel(dbv));
        prefs.saveDevices();
    }

    /**
     * The card this manager's ranges belong to - OWNERSHIP: the manager decides
     * which analyzer card its attenuator positions are recorded in, because it is
     * the only thing that knows which analyzer it drives.
     *
     * <p>An open session names it outright ({@link #model} is this device's
     * model, and the card is named after it).  With the analyzer closed there is
     * no model - {@link #shutdown()} clears it, and before the first open there
     * never was one - so the operator's SAVED CHOICE is asked instead:
     * a QA40x device is named after its model, so the binding for that name is the
     * card they picked for it.
     *
     * <p>That is the answer to the two-model case this used to guess at.  A bench
     * that has had both a QA402 and a QA403 plugged into it holds a card for each,
     * and the first one found in the store is a coin toss - it recorded the
     * operator's attenuator position into whichever card happened to come first,
     * for an analyzer they might not even have on the table.  Nobody but the
     * operator can resolve that, and now nobody has to: they chose, the choice is
     * in {@code devices.yaml}, and it survives a restart on a headless server
     * exactly as it does here.
     *
     * <p>The store scan REMAINS as the last resort, for an installation that has
     * never chosen: there is no information to disambiguate with then, and the
     * single-analyzer bench it serves - every bench until a second model is
     * plugged in - is the common case.  A live session still wins outright.
     */
    private AudioDeviceProfile rangeCard(Preferences prefs) {
        if (model != null) {
            // Ahead of the binding on purpose: an OPEN analyzer has identified
            // ITSELF, and its model card is the one built from the EEPROM it just
            // answered.  The binding exists to say which box a CLOSED one is, and
            // letting it overrule a device that is on the wire right now would put
            // a live analyzer's attenuator position into another one's card.
            return prefs.findAudioDeviceProfile(model.name());
        }
        for (Qa40xModel candidate : Qa40xModel.values()) {
            String chosen = prefs.boundCardName(candidate.name());
            if (chosen != null) {
                AudioDeviceProfile card = prefs.findAudioDeviceProfile(chosen);
                if (card != null) {
                    return card;
                }
            }
        }
        for (Qa40xModel candidate : Qa40xModel.values()) {
            AudioDeviceProfile card = prefs.findAudioDeviceProfile(candidate.name());
            if (card != null) {
                return card;
            }
        }
        return null;
    }

    /**
     * Routes a Preferences-committed active-range change to the device - the sink
     * for the {@code DEVICE_ACTIVE_RANGE_CHANGED} bus event: when a QA40x card
     * exists and is the active one, it takes the change and writes it to the
     * device.  A no-op unless {@code activeBackend} is
     * {@link AudioBackendType#QA40X} and {@code
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
     *  or {@code null} before the device is opened - the card the range routing targets. */
    public synchronized String cardName() {
        return model == null ? null : model.name();
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
    @Override
    public synchronized Qa40xDeviceInfo readDeviceInfo() {
        try {
            // Opening the device is what makes the registers readable at all: the
            // session opens lazily on the first capture, so without this the
            // panel would only ever show values after a measurement had run.
            // Same open the session uses - idempotent when one is already live.
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
            // Dashes are the right answer for the panel, but they are not a reason
            // to keep writing a handle that just refused a read: the session goes,
            // and the next open finds whatever is really on the bus.
            discardSession("a telemetry read failed: " + t);
            return Qa40xDeviceInfo.NONE;
        }
    }

    // The two openCustomPreferences* methods used to live here and construct the
    // settings dialog directly.  The dialog is SWT and lives in the module above
    // this one, so it now reaches the manager rather than the other way round: it
    // reads {@link #getSettings()} and {@link #readDeviceInfo()} and owns the
    // pending-edit round trip itself.

    /**
     * App-exit teardown: leaves the analyzer in its protected idle state
     * (doc §7 Teardown steps 8-9) - stream stopped, input +42 dBV (maximum
     * attenuation), output −12 dBV - then releases the transport.  Without
     * this the device kept whatever range the last measurement used: a
     * 0 dBV session left the input at maximum sensitivity, unprotected,
     * after the app quit.  No-op when the device was never opened.  Never
     * throws: the app exit path must not be blocked by an unplugged or
     * wedged device.
     */
    @Override
    public synchronized void shutdown() {
        parkAndRelease();
    }

    /** The safe-state write followed by the release, shared by the start-up
     *  {@link #setup()} and the exit {@link #shutdown()} - the analyzer is left
     *  in the same protected state whichever end of the application's life it
     *  is reached from.  No-op when the device was never opened. */
    private void parkAndRelease() {
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
        openSession   = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>For an analyzer, "known, safe, idle" is the parked attenuator</b> -
     * input at maximum attenuation, output at its low range, nothing streaming.
     * Which matters at start-up because a QA40x's input sensitivity SURVIVES the
     * host that set it: the unit can be moved between two machines without ever
     * losing USB power, and the machine that had it last never released it - it
     * simply stopped being the host.  So the analyzer this process has just
     * enumerated may be live at whatever range someone else's session left it at,
     * and nothing would put it right until a client happened to acquire and
     * release it.
     *
     * <p>So it opens the analyzer if no session has, writes the safe state, and
     * RELEASES it again - the device is left exactly as a fresh connect finds it,
     * and the next host to want it can have it.
     *
     * <p>Nothing attached, no {@code libusb}, or an open that faulted is not an
     * error here - there is simply nothing to set up, and this answers false.
     * The open goes through {@link #onTheDevice} like every other device path, so
     * a handle that faulted half-way through is discarded rather than kept.
     */
    @Override
    public synchronized boolean setup() {
        if (transport != null) {
            // A session is already live - this process is measuring with the
            // analyzer, and parking it now would pull the range out from under
            // whatever is running.  Start-up is the case this exists for.
            return false;
        }
        try {
            onTheDevice(() -> {
                ensureOpen();
                return null;
            });
        } catch (Throwable t) {
            if (log.isInfoEnabled()) {
                log.info("QA40x setup: no analyzer to bring up ({})", t.toString());
            }
            return false;
        }
        parkAndRelease();                    // safe-state writes, then release
        return true;
    }

    private void ensureOpen() {
        if (transport != null) {
            return;                          // already open (or injected for tests)
        }
        List<Qa40xDevice> devices = finder.list();
        if (devices.isEmpty()) {
            throw new IllegalStateException("No QA402/QA403 attached (or libusb-1.0 unavailable)");
        }
        openSession = devices.get(0);
        model = openSession.model();
        transport = finder.open();           // reset + claim; enforces the single-device rule
        calibration = Qa40xCalibration.fromTransport(transport);
        refreshDeviceCard();
        log.info("QA40x session open: {} (input {} dBV, output {} dBV)", model, inputRangeDbv, outputRangeDbv);
    }

    /**
     * Gives up the open session - transport, engine, calibration and card state -
     * so the next {@link #ensureOpen()} runs the finder again, re-reads the
     * calibration page and refreshes the card.  The self-heal, and the whole of
     * it: everything this manager knows about the analyzer was read from the
     * analyzer, so throwing it away is what makes the next open honest.
     *
     * <p><b>It does NOT park the device first,</b> which is what separates it from
     * {@link #shutdown()}.  This runs when the handle is already dead - the device
     * was unplugged, or a transfer failed with an IO error - and the safe-state
     * register writes would only fail again, one exception per register, on a path
     * whose whole job is to recover quietly.  The park belongs to an orderly exit,
     * where the device is still there to hear it.
     *
     * <p>Lanes attached to the discarded engine are not stopped from here: a lane
     * on a device that has gone is already broken, and the failure has its own way
     * to the operator (the server surfaces {@code ev.device.error} once and closes
     * the streams on it).  What this guarantees is only that nothing stale is
     * reused - the next acquire builds a new engine on a new handle.
     */
    private synchronized void discardSession(String why) {
        if (transport == null && engine == null) {
            return;
        }
        log.warn("QA40x session discarded ({}): the next open re-runs the finder", why);
        try {
            // Null-checked, not assumed: this also runs for a session whose engine
            // outlived its transport, and a teardown that threw an NPE on the way
            // out would leave the rest of the state behind - which is the whole
            // thing this method exists to clear.
            if (transport != null) {
                transport.close();
            }
        } catch (Throwable t) {
            log.debug("QA40x transport close after {} failed (expected on a device that "
                    + "is gone): {}", why, t.toString());
        }
        transport     = null;
        calibration   = null;
        model         = null;
        engine        = null;
        currentRateHz = 0;
        openSession   = null;
    }

    /**
     * Checks a fresh enumeration against the session this manager holds, and gives
     * that session up when the analyzer it was opened on is no longer there.
     *
     * <p>This is the path that heals a detach / re-attach without a restart.  The
     * operator unplugs the analyzer and plugs it back in; it re-enumerates at a
     * new USB address; the next scan - the device combo, a server's catalogue
     * rescan, {@code ev.devices.changed} - comes through here and the dead handle
     * goes with it.  Without this the device LISTS (the finder sees the new one)
     * while every operation still writes the old handle: the scan finds the
     * analyzer and nothing works.
     *
     * @param attached what the finder just saw, so the enumeration and this check
     *                 can never disagree about what is on the bus
     */
    private synchronized void dropSessionIfMoved(List<Qa40xDevice> attached) {
        Qa40xDevice open = openSession;
        if (open == null) {
            return;                          // nothing to lose
        }
        if (!attached.contains(open)) {
            discardSession(attached.isEmpty() ? "the analyzer was detached"
                    : "the analyzer came back at another address (was " + open + ")");
        }
    }

    /**
     * Runs {@code work} on the open device, and gives the session up when it
     * fails.
     *
     * <p>Everything below this manager retries what is worth retrying - the finder
     * re-opens across a reset, the transport bounds its own transfers - so an
     * exception that reaches here has already outlived them, and the handle it
     * came from is not one to keep writing to.  Discarding costs a re-open and an
     * EEPROM read; keeping it cost every subsequent operation until the server
     * was restarted.
     *
     * <p><b>Throwable, and that is the whole point.</b>  The failure this exists
     * for arrives as an Error: JNA raises one out of a native invocation that
     * faulted, which is what a QA40x unplugged mid-generation does to the very
     * next register write ("Invalid memory access" in {@code libusb_bulk_transfer}).
     * A {@code catch (RuntimeException)} let exactly that case through, so the
     * dead handle was KEPT - the one failure the discard was
     * written for.  It is also the reason the enumeration check
     * ({@link #dropSessionIfMoved}) cannot be the only self-heal: nothing
     * re-enumerates on a headless server between measurements, so the FAILURE path
     * has to be the one that gives the session up.
     */
    private <T> T onTheDevice(Supplier<T> work) {
        try {
            return work.get();
        } catch (Throwable t) {
            discardSession("a transfer failed: " + t);
            throw t;
        }
    }

    // --- device card (doc §10 dBV/cal plumbing; device-profile policy) --------

    /**
     * Creates-or-refreshes this device's card in the store from the freshly read
     * calibration page, preserving the user's active-range selections (the
     * profile-policy survival rules), and PERSISTS it to {@code devices.yaml}.  Both
     * endpoints are marked {@code calibrationFromDevice} so calibrate flows refuse
     * with a WARN and the seed merge takes the values wholesale.
     *
     * <p>Runs once per session - {@link #ensureOpen()} opens the device (and calls
     * this) only on the first {@link #acquireEngine}, i.e. a user-started capture /
     * playback, so the app is already running.  The persist is guarded by
     * {@link Preferences#saveDevices()}, a no-op in transient (CLI) mode and on a
     * detached copy, so a headless run or a test never writes the store - only a
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
     * Builds the QA40x device profile - see {@link Qa40xCalibration#toProfile},
     * where the construction lives so the net bridge can rebuild the same card
     * for a remote analyzer.  This wrapper only supplies the local defaults.
     */
    AudioDeviceProfile buildProfile(String cardName, Qa40xCalibration cal, AudioDeviceProfile existing) {
        return cal.toProfile(cardName, existing, DEFAULT_INPUT_DBV, DEFAULT_OUTPUT_DBV);
    }

    /**
     * {@link DeviceRef} for one attached QA402/QA403 - a single duplex endpoint.
     *
     * <p>{@code identity} is the finder's own "model @ bus/address" and exists
     * for {@link DeviceRef#identity()}: every other field of this ref is the
     * SAME across an unplug and a re-attach (there is one analyzer, it sits at
     * index 0, and it is named after its model), so nothing but the USB address
     * can tell a hot-plug comparison that the device on the bus is a new one.
     */
    public record Qa40xDeviceRef(int index, String name, Qa40xModel model, String identity)
            implements DeviceRef {
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
