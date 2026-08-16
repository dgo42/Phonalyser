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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>The DETACH goes through this manager ({@link #detachCapture()} /
 * {@link #detachGenerator()}), and the last one RELEASES the analyzer: the
 * exclusive USB claim is held while the device is in use and given back the
 * moment it is not, so a second session on the same machine can have it without
 * waiting for this process to exit.  See {@link #releaseIfIdle()}.
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
     * Default active ranges while the operator has never chosen one - the
     * PROTECTED state (maximum input attenuation, low output range), the same
     * ranges every teardown parks to.  A hotter silent default would leave an
     * unconfigured analyzer's input at full sensitivity - locally after a fresh
     * install, and on a server whose card has never had a range saved, where
     * the client dialog then shows a default indistinguishable from a real
     * choice and sends no range write at all.
     */
    private static final int DEFAULT_INPUT_DBV  = Qa40xProtocol.SAFE_INPUT_DBV;
    private static final int DEFAULT_OUTPUT_DBV = Qa40xProtocol.SAFE_OUTPUT_DBV;

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
    /** The analyzers the last scan could not have - held by another process, or
     *  gone while it asked.  Not offered as local devices: nothing here can use
     *  them, and the next scan is what lets them back in. */
    private final Set<Qa40xDevice> unusable = new HashSet<>();
    /** Which analyzer sits where: the USB device the enumeration reports, to the
     *  serial that device answered when it was opened.  This is what lets a scan
     *  recognise a unit it already knows WITHOUT opening it - the serial itself
     *  lives in a register, and asking for it every two seconds is a USB open
     *  every two seconds. */
    private final Map<Qa40xDevice, String> serialAt = new LinkedHashMap<>();
    /**
     * What each analyzer this process has opened told about itself, BY SERIAL
     * NUMBER - the factory page and the last reading, per unit.
     *
     * <p>Keyed by the serial and not by the bus identity, because that is what
     * the data belongs to.  The same analyzer moved to another port is the same
     * factory factors and nothing to re-read; a same-model unit swapped onto the
     * same port is a different page, and what the device calls itself is the only
     * thing that can say so.  A bench with two analyzers on it therefore keeps
     * two entries and neither can ever be answered with the other's factors.
     *
     * <p>Entries live for the process: a page is half a kilobyte of factory data
     * that cannot change, so there is nothing to expire and nothing an eviction
     * rule could make more correct than the key already does.
     */
    private final Map<String, CachedUnit> units = new LinkedHashMap<>();
    /**
     * The entry of the analyzer this manager has IN HAND - the one it has open,
     * or the last one it had open once that is released.
     *
     * <p>This is what the readings are answered from, and it is set only from a
     * serial the device itself answered: the enumeration cannot supply one (a
     * serial lives in a register, and reading a register needs an open), so
     * nothing here is ever guessed from a bus address.  A unit swapped in while
     * this manager holds nothing open is therefore noticed at the next open,
     * which is where the serial is read again.
     *
     * <p>Null when this manager has never had an analyzer open, and again when a
     * scan finds the bus empty - a reading belongs to a device that is present.
     */
    private CachedUnit        inHand;
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
        if (!unusable.isEmpty()) {
            attached.removeAll(unusable);
        }
        List<DeviceRef> out = new ArrayList<>();
        int index = 0;
        for (Qa40xDevice device : attached) {
            out.add(new Qa40xDeviceRef(index++, device.model().name(), device.model(),
                    device.toString()));
        }
        return out;
    }

    /**
     * The scan is what fills the caches, so nothing that ASKS ever has to wait
     * for a USB open.
     *
     * <p>Three cases, in the order they are tested.  A bus with no analyzer on it
     * has nothing to warm.  A LIVE session needs no open at all - the transport
     * is up, so the reading is eight free register reads and the page is already
     * here.  Otherwise, a cold cache on an attached analyzer is worth exactly one
     * atomic cycle: open, page, park, release - after which the panels and the
     * clients across the network are answered from memory.
     *
     * <p>A cycle that cannot have the analyzer is not an error and is not retried
     * here: the attempt is a SINGLE pass with no settle, because an analyzer
     * another process holds refuses at {@code libusb_open}, BEFORE the reset in
     * the finder's open dance - there is no race to wait out, and the retry loop
     * would spend its settles on this thread every scan.  Such a device is
     * reported back as unavailable, and the next scan asks again.
     *
     * @return the analyzer another process is holding, which this scan must not
     *         offer, or {@code null} when there is none
     */
    /** The serial an entry is filed under. */
    private String keyOf(CachedUnit unit) {
        for (Map.Entry<String, CachedUnit> entry : units.entrySet()) {
            if (entry.getValue() == unit) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The operator's device scan: what is on the bus decides what this manager
     * knows.  Every analyzer it cannot yet name is opened once - the serial lives
     * in a register, so that is the only way to ask - and every entry whose unit
     * is no longer there is dropped, page and reading with it.
     *
     * <p>Deliberately NOT part of listing devices: a client asking what is
     * attached pays the enumeration and nothing else.
     */
    @Override
    public boolean refreshDeviceList() {
        scanUnits(new ArrayList<>(finder.list()));
        return true;
    }

    /**
     * Always: the hot-plug tick is what keeps this backend's picture of the bench
     * current, and it costs an enumeration - a device already known is recognised
     * by where it sits and is not touched at all.  Only an analyzer that has just
     * appeared is opened, once, to ask which unit it is.
     */
    @Override
    public boolean deviceListStale() {
        return true;
    }

    private synchronized void scanUnits(List<Qa40xDevice> attached) {
        unusable.clear();
        for (Qa40xDevice device : attached) {
            if (serialAt.containsKey(device)) {
                continue;                    // known where it is: nothing to ask
            }
            String serial = readSerialOf(device);
            if (serial != null) {
                serialAt.put(device, serial);
            } else {
                // Held by another process, or gone between the enumeration and
                // this line: either way it is not a device this process has.
                unusable.add(device);
            }
        }
        serialAt.keySet().retainAll(attached);
        units.keySet().retainAll(serialAt.values());
        if (inHand != null && !units.containsValue(inHand)) {
            inHand = null;
        }
    }

    /**
     * What the analyzer at this address calls itself - the one thing a scan has
     * to ask the device for, since the serial lives in a register and no
     * descriptor carries it.
     *
     * <p>An analyzer this manager already has open answers for free.  Otherwise
     * it is opened - no reset, so this costs the claim and one register read -
     * and released again at once; the first such open of a unit also reads its
     * factory page, every later one finds the page already filed under that
     * serial and reads nothing more.
     *
     * <p>Null means the scan could not name the device: another process holds it
     * or it would not answer at all.  Either way it keeps no entry and is not a
     * device this process has.
     */
    private String readSerialOf(Qa40xDevice device) {
        if (transport != null && device.equals(openSession)) {
            refreshTelemetry();              // free: the device is already open
            return keyOf(inHand);
        }
        try {
            onTheDevice(() -> {
                ensureOpen(false);
                return null;
            });
            return keyOf(inHand);
        } catch (Throwable t) {
            if (classifyFailure(t) == DeviceFailureReason.DEVICE_IN_USE) {
                if (log.isInfoEnabled()) {
                    log.info("QA40x {} is held by another process - not offered as a "
                            + "local device until it is free", device);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("QA40x {} did not answer this scan ({}); the next one asks again",
                        device, t.toString());
            }
            return null;
        } finally {
            releaseIfIdle();
        }
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

    /**
     * Gives the capture lane back - and with it the analyzer, when that lane was
     * the last one.
     *
     * <p>The lanes detach THROUGH the manager rather than on the engine they were
     * handed, because what happens after the last detach is not the engine's to
     * decide: the transport claim is this manager's, and only it knows there is
     * nothing left to keep it for.
     *
     * @see #releaseIfIdle()
     */
    synchronized void detachCapture() {
        if (engine == null) {
            return;                          // nothing was ever attached to
        }
        engine.detachCapture();
        releaseIfIdle();
    }

    /** Gives the generator lane back, and the analyzer with it when that lane was
     *  the last one - see {@link #detachCapture()}. */
    synchronized void detachGenerator() {
        if (engine == null) {
            return;
        }
        engine.detachGenerator();
        releaseIfIdle();
    }

    /**
     * Releases the analyzer once no lane is attached any more: the safe-state
     * write, the transport close and the session state, exactly as an orderly
     * exit does them.
     *
     * <p><b>Held claims are what this exists to end.</b>  A USB analyzer is
     * claimed exclusively, so a host that keeps the claim after the last
     * measurement stopped owns a device nobody is using: a second session on the
     * same machine - a desktop beside a running server, a second application -
     * is then refused with {@code LIBUSB_ERROR_ACCESS} until the holder exits.
     * The device is not needed between measurements, so it is not held between
     * them.
     *
     * <p>While ANY lane is still attached this does nothing: the back-to-back
     * session model (doc §10) keeps one engine across a capture that closes and
     * reopens while the generator plays, and releasing there would stop a
     * measurement that is running.
     *
     * <p>The next {@link #acquireEngine} opens lazily again - the finder re-runs,
     * the calibration page is re-read and the card refreshed - which is what
     * makes a released analyzer indistinguishable from one that was never opened.
     *
     * <p>Two callers, one rule: the last lane's detach, and each control read
     * that had to open the analyzer to answer ({@link #readDeviceInfo()},
     * {@link #calibration(boolean)}).  Both mean the same thing - this process
     * has nothing left it needs the device for right now.
     */
    private void releaseIfIdle() {
        if (engine != null && engine.anyLaneAttached()) {
            return;
        }
        parkAndRelease();
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
     *
     * <p><b>And given back before the answer leaves.</b>  A read is ATOMIC:
     * connected, read out, disconnected.  What opened the analyzer only to
     * describe it has no further use for it, and a claim held past that is a
     * device the operator cannot open anywhere else.  While a lane IS attached
     * the read rides the open session and releases nothing - see
     * {@link #releaseIfIdle()}.
     *
     * <p>The release is in a finally OUTSIDE {@link #onTheDevice}: a read that
     * failed has already given the session up there, with the reason it failed,
     * and this must neither repeat that nor hide it.  It costs a reopen per
     * call, which is the price of not holding hardware nobody is measuring with.
     */
    @Override
    public synchronized List<CalibrationRow> calibration(boolean input) {
        if (inHand != null) {
            // Warm: the page is already here and cannot have changed, so this is
            // arithmetic over it - no open, no claim, no wait.  The far end of a
            // network asks for both directions as two calls, and a cycle each was
            // more than a client dialog waits before it gives up on the answer.
            return calibrationRows(input);
        }
        try {
            return onTheDevice(() -> {
                ensureOpen();                // reads the page and caches it
                return calibrationRows(input);
            });
        } finally {
            releaseIfIdle();
        }
    }

    /** The cached cal page as rows - pure arithmetic over the factors, so the
     *  caller decides whether the device has to be opened for it first. */
    private List<CalibrationRow> calibrationRows(boolean input) {
        Qa40xCalibration page = inHand.page;
        List<CalibrationRow> rows = new ArrayList<>();
        for (int dbv : input ? Qa40xProtocol.inputRangeDbvValues()
                : Qa40xProtocol.outputRangeDbvValues()) {
            rows.add(new CalibrationRow(dbv,
                    input ? page.adcLinearFactor(dbv, false)
                          : page.dacLinearFactor(dbv, false),
                    input ? page.adcLinearFactor(dbv, true)
                          : page.dacLinearFactor(dbv, true)));
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
     *
     * <p><b>Answered from the last reading when there is one.</b>  The registers
     * are a display: firmware, serial, supply voltage and temperature, asked for
     * by a panel that opens and by a client across a network, neither of which
     * may wait for a USB open.  A reading is taken whenever the analyzer is open
     * anyway - a scan tick during a session, the park that ends a cycle - so what
     * this answers is at worst as old as the last time the device was in use.
     *
     * <p>Cold - nothing has ever read this analyzer - it falls back to the atomic
     * cycle: opened for the readout and released again when no lane is attached
     * (see {@link #releaseIfIdle()}).  The release is in a finally, after the
     * failure path below has had its say - that path discards the session itself,
     * and this one then finds nothing left to release.
     */
    @Override
    public synchronized Qa40xDeviceInfo readDeviceInfo() {
        Qa40xDeviceInfo lastReading = inHand == null ? null : inHand.reading;
        if (lastReading != null) {
            return lastReading;
        }
        try {
            // Opening the device is what makes the registers readable at all: the
            // session opens lazily on the first capture, so without this the
            // panel would only ever show values after a measurement had run.
            // Same open the session uses - idempotent when one is already live.
            ensureOpen();                    // which is also what identifies the unit
            Qa40xDeviceInfo reading = readTelemetry();
            inHand.reading = reading;
            return reading;
        } catch (Throwable t) {
            log.warn("QA40x device info read failed: {}", t.toString());
            // Dashes are the right answer for the panel, but they are not a reason
            // to keep writing a handle that just refused a read: the session goes,
            // and the next open finds whatever is really on the bus.
            discardSession("a telemetry read failed: " + t);
            return Qa40xDeviceInfo.NONE;
        } finally {
            releaseIfIdle();
        }
    }

    /** The eight identity and telemetry registers, decoded as the panel and spec
     *  4.6 show them.  The caller has an open transport; the ISO-supply current
     *  exists on the QA402 only. */
    private Qa40xDeviceInfo readTelemetry() {
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
    }

    /**
     * Takes a reading through a transport that is ALREADY open - eight register
     * round trips, no open, no claim, nothing the running stream notices.
     *
     * <p>Best effort, and that is deliberate: a reading is a display value, so a
     * register that will not answer keeps the previous one rather than costing
     * the caller the session it was doing something else with.  The paths that
     * MUST notice a dead handle (the engine build, the range writes, the cold
     * reads) all still run through {@link #onTheDevice}.
     *
     * <p>Every register READ in this backend runs on this monitor - this method,
     * {@link #readTelemetry()} and the calibration page - so two readers can
     * never interleave one's request with the other's reply.  The engine only
     * WRITES registers, and a write consumes no reply.
     */
    private void refreshTelemetry() {
        if (transport == null || inHand == null) {
            return;
        }
        try {
            inHand.reading = readTelemetry();
        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("QA40x telemetry refresh failed, keeping the last reading: {}",
                        t.toString());
            }
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
     *  {@link #setup()}, the exit {@link #shutdown()} and the last lane's detach
     *  ({@link #releaseIfIdle()}) - the analyzer is left in the same protected
     *  state whichever of them reaches it.  No-op when the device was never
     *  opened, or when it has already been released: the transport is what says
     *  so, and it is null in both cases. */
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
        // The device is still open and already safe, so the reading costs eight
        // register round trips and is what the panels answer from for as long as
        // the analyzer stays released.  After the safe state and in a guard of its
        // own: a device that will not answer a telemetry read must not be the
        // reason the park did not finish.
        refreshTelemetry();
        try {
            transport.close();
        } catch (Throwable t) {
            log.warn("QA40x transport close failed: {}", t.toString());
        }
        transport     = null;
        model         = null;
        engine        = null;
        currentRateHz = 0;
        openSession   = null;
        // The cached entries - and the one in hand - deliberately survive: what a
        // release ends is the CLAIM, not what the analyzer told about itself.
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

    /** The open an OPERATOR is waiting behind - it survives the re-enumeration
     *  race with the finder's retry loop. */
    private void ensureOpen() {
        ensureOpen(true);
    }

    /**
     * @param retry whether to use the finder's retrying open.  The scan passes
     *              false: it is only asking, and a device held by another process
     *              refuses before any reset, so waiting out a race that cannot
     *              happen would spend the settle delays of three passes on a
     *              background thread holding this monitor.
     */
    private void ensureOpen(boolean retry) {
        if (transport != null) {
            return;                          // already open (or injected for tests)
        }
        List<Qa40xDevice> devices = finder.list();
        if (devices.isEmpty()) {
            throw new IllegalStateException("No QA402/QA403 attached (or libusb-1.0 unavailable)");
        }
        openSession = devices.get(0);
        model = openSession.model();
        // reset + claim; enforces the single-device rule
        transport = retry ? finder.open() : finder.openWithoutRetry();
        readCalibrationPageIfStale();
        refreshDeviceCard();
        log.info("QA40x session open: {} (input {} dBV, output {} dBV)", model, inputRangeDbv, outputRangeDbv);
    }

    /**
     * Reads the factory calibration page - unless this manager already holds the
     * page of the analyzer it has just opened.
     *
     * <p>The page is the slowest thing in an open: a page select plus a hundred
     * and twenty-eight register round trips, which is what made every reopen
     * expensive enough to be felt at the far end of a network.  It is also
     * IMMUTABLE - factory data, written once per unit - so a page already read
     * from THIS analyzer is the page this analyzer would answer with again.
     *
     * <p>WHICH unit it is comes from the device itself - the serial register, the
     * first thing an open reads.  A bus address cannot answer that question: two
     * QA403s look alike on it, so a unit swapped onto the same port would be
     * described by the previous one's factors and every full scale would be
     * silently wrong.  The serial also makes the ordinary case free: the same
     * analyzer on another port keeps its page, because it is the same analyzer.
     *
     * <p>An analyzer that will not say its serial is trusted with nothing: its
     * page is read for this session and filed under no key, so the next open
     * reads it again rather than guessing which unit answered.
     */
    private void readCalibrationPageIfStale() {
        String serial = readSerial();
        CachedUnit known = serial == null ? null : units.get(serial);
        if (known != null) {
            inHand = known;                  // this unit's own page, already here
            return;
        }
        // A unit this process has not read yet - or one that would not say which
        // unit it is, whose page is therefore used for this session and kept out
        // of the collection, since there is no key to file it under.
        inHand = new CachedUnit(Qa40xCalibration.fromTransport(transport));
        if (serial != null) {
            units.put(serial, inHand);
        }
    }

    /** What the analyzer calls itself, or null when it would not say.
     *
     *  <p>Best effort on purpose: an unreadable serial must not pass the identity
     *  check, and it must not throw here either - the page read below is the one
     *  that reports a device which cannot answer its registers, with the failure
     *  handling every other device path already has. */
    private String readSerial() {
        try {
            return Qa40xProtocol.formatSerialNumber(
                    transport.registerRead(Qa40xProtocol.REG_SERIAL_NUMBER));
        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("QA40x serial read failed, so the cached page is not trusted: {}",
                        t.toString());
            }
            return null;
        }
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
        model         = null;
        engine        = null;
        currentRateHz = 0;
        openSession   = null;
        // The cached entries stay: a transfer that failed says the HANDLE is
        // spent, not that the analyzer's factory data changed.  The next open
        // reads the serial again and picks the entry that unit belongs to.
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
        if (open != null && !attached.contains(open)) {
            discardSession(attached.isEmpty() ? "the analyzer was detached"
                    : "the analyzer came back at another address (was " + open + ")");
        }
        // The cached PAGE is not touched here: it is keyed by serial, so the unit
        // that comes back at another address is still the unit its factors were
        // read from, and the next open re-validates that by asking the device.
        // What DOES go, once nothing is on the bus at all, is the analyzer in
        // hand: a supply voltage and a temperature belong to a device that is
        // present, and answering them for one that is gone is a display that
        // lies.  The entries stay - each is still the page of the unit that
        // answered that serial, and the unit that comes back is re-identified at
        // its next open.
        if (attached.isEmpty()) {
            inHand = null;
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
        if (inHand == null || model == null) {
            return;
        }
        String cardName = model.name();
        Preferences prefs = Preferences.instance();
        AudioDeviceProfile card = buildProfile(cardName, inHand.page,
                prefs.findAudioDeviceProfile(cardName));
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
    /**
     * What one analyzer told about itself, held under its serial in
     * {@link #units}.
     *
     * <p>A type rather than two maps side by side: the page and the reading are
     * one unit's answers, they are filed and selected together, and two
     * collections keyed the same way are two chances to answer with one unit's
     * page beside another unit's temperature.
     */
    private static final class CachedUnit {

        /** Factory data - read once per unit and never re-read while this process
         *  lives, because it cannot change. */
        private final Qa40xCalibration page;
        /** The last decoded reading, or null until one is taken.  Mutable where
         *  the page is final: it is a measurement of the moment, refreshed
         *  whenever this unit happens to be open. */
        private Qa40xDeviceInfo reading;

        private CachedUnit(Qa40xCalibration page) {
            this.page = page;
        }
    }

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
