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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xControl;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceInfo;

import lombok.Getter;
import lombok.Setter;

/**
 * A bench that is not there: two capture devices and one playback device with
 * fixed names and formats, and a {@link StubCapture} that delivers exactly the
 * bytes a test hands it.
 *
 * <p>It is a real {@code AudioDeviceManager} reached through the real
 * {@code AudioBackend}, so the server under test uses the production path from
 * enumeration to batch delivery.  The device names are the ones the session
 * tests quote in their device refs - the name validation of spec 4.3 is part of
 * every acquire, so a stub that named its devices differently would fail them
 * all.
 *
 * <p>The two directions carry different format sets on purpose: the input offers
 * one sample width (the analyzer's own 24-bit capture path) and the output two
 * (the front-panel I2S frame widths), which is the difference {@code
 * hasBitDepth} has to report.
 */
final class StubDeviceManager implements AudioDeviceManager, Qa40xControl {

    /** The name the session tests put in their device refs. */
    static final String FIRST_INPUT = "QA403";
    /** The logical name of this bench's device card - the model name, exactly as
     *  the real analyzer's manager names it. */
    static final String CARD_NAME = "QA403";
    /** How a range row is labelled in the card, as {@code Qa40xProtocol} spells
     *  it: this module cannot see that class, and a label that drifted from it
     *  would make the stub prove something production does not do. */
    static final String RANGE_LABEL_SUFFIX = " dBV";
    /** A second unit, so a test can contend two devices of one backend. */
    static final String SECOND_INPUT = "QA403 second unit";
    static final String OUTPUT = "QA403";
    static final int RATE_48K = 48_000;
    static final int RATE_96K = 96_000;
    static final int BITS_24 = 24;
    static final int BITS_32 = 32;

    /** The analyzer's attenuator positions, shortened to the two ends: what
     *  {@code qa40x.ranges} offers and what a range change is checked against. */
    static final int[] INPUT_RANGES_DBV = {0, 42};
    static final int[] OUTPUT_RANGES_DBV = {-12, 18};
    static final int DEFAULT_INPUT_DBV = 0;
    static final int DEFAULT_OUTPUT_DBV = 18;
    /** Deliberately unequal, so a test that crossed the two channels on the wire
     *  fails on the values rather than passing on symmetry. */
    static final double CAL_LEFT = 1.25;
    static final double CAL_RIGHT = 1.5;
    static final String FIRMWARE = "23";
    static final String SERIAL = "QA403-0001";

    private static final int CHANNELS = 2;
    private static final String DESCRIPTION = "QuantAsylum analyzer";
    private static final String VENDOR = "QuantAsylum";
    /** A fixed snapshot with every field distinct, so a payload that put a value
     *  in the wrong field is caught by its content. */
    private static final Qa40xDeviceInfo DEVICE_INFO = new Qa40xDeviceInfo(FIRMWARE,
            "5.01 V", "0.42 A", Qa40xDeviceInfo.UNAVAILABLE, "31.5 °C", "0x0003",
            "0x0001", SERIAL);

    /** Which backend this bench answers as.  The server serves ALL its backends
     *  at once, so the tests that select between them need more than one - and a
     *  device ref must name the backend it belongs to, or the dispatch would
     *  route an open at the wrong manager. */
    private final AudioBackendType backend;

    /** The most recently opened capture - what a test feeds bytes into.
     *  Volatile because the loopback run opens it on a session's own thread and
     *  reads it from the test thread. */
    @Getter
    private volatile StubCapture lastCapture;
    /** The most recently opened output line - what a test reads the emitted
     *  waveform back out of.  Volatile for the same reason as
     *  {@link #lastCapture}. */
    @Getter
    private volatile StubPlayback lastPlayback;
    /** How often the hardware was parked (spec 4.1: "park hardware (QA40x
     *  attenuator safe)"), which on the real analyzer is the safe-state write.
     *  The manager is the process-wide one every test class shares, so a test
     *  asserts on the DELTA around what it drove, never on the total. */
    @Getter
    private int shutdownCount;
    /** The analyzer's own state (spec 4.6), which really does survive a call -
     *  a stub that forgot a range change would let a server that dropped it
     *  pass.  Read back through the interface's own accessors below. */
    private int activeInputRangeDbv = DEFAULT_INPUT_DBV;
    private int activeOutputRangeDbv = DEFAULT_OUTPUT_DBV;
    /** Where this bench's analyzers currently sit on the USB - see
     *  {@link #reattachAtAnotherAddress()}. */
    private volatile int usbAddress = 5;
    /** The card store this bench records its range in, or null when a test does
     *  not care.  The REAL analyzer's manager writes the range now in force into
     *  its card's active row, because that row is where every full scale - the
     *  {@code cal} of spec 4.3 included - is read from; a stub that only moved a
     *  field would let a server that never told anyone pass.  Settable rather than
     *  constructed with, because this manager is the process-wide one the service
     *  loader built long before any test chose a store. */
    @Setter
    private volatile Preferences cards;
    @Getter
    @Setter
    private boolean i2sEnabled;

    /** What is PHYSICALLY on this bench right now - the live truth a device list
     *  is checked against, and what a test moves when it unplugs something. */
    private final Set<String> attached = ConcurrentHashMap.newKeySet();
    /** What the ENUMERATION this manager answers from was taken of.  A snapshot
     *  backend (PortAudio) lists from one of these and cannot see past it, which
     *  is the whole reason a server has to notice and rebuild it. */
    private volatile Set<String> snapshot;
    /** How often the snapshot was rebuilt - a rebuild is a full re-initialisation
     *  of a native library, so what a test asserts about it is that it happens
     *  when the bench moved and NOT on a tick that found nothing wrong. */
    @Getter
    private int refreshCount;
    /** The replug wound: the device is back on the bench, but the list entry
     *  still points at where it used to be, so every open on it fails until the
     *  list is rebuilt.  This is what macOS answers with an internal PortAudio
     *  error for the rest of the process's life. */
    private volatile boolean openFailsUntilRebuilt;

    StubDeviceManager(AudioBackendType backend) {
        this.backend = backend;
        attached.addAll(List.of(FIRST_INPUT, SECOND_INPUT, OUTPUT));
        snapshot = Set.copyOf(attached);
    }

    /** Pulls one device off the bench.  It stays in the LIST - a snapshot cannot
     *  notice by itself - which is exactly the state the server has to detect. */
    void unplug(String name) {
        attached.remove(name);
    }

    /** Plugs everything back in and rebuilds the list over it: how a test hands
     *  the shared bench back the way it found it. */
    void replugAll() {
        attached.addAll(List.of(FIRST_INPUT, SECOND_INPUT, OUTPUT));
        refreshDeviceList();
    }

    /** The device came back, the list entry did not: opens fail until a rebuild,
     *  and the list is stale until then. */
    void replugBehindAStaleList() {
        openFailsUntilRebuilt = true;
    }

    @Override
    public boolean deviceListStale() {
        return openFailsUntilRebuilt || !snapshot.equals(attached);
    }

    @Override
    public boolean refreshDeviceList() {
        refreshCount++;
        openFailsUntilRebuilt = false;
        snapshot = Set.copyOf(attached);
        return true;
    }

    @Override
    public void shutdown() {
        shutdownCount++;
    }

    /** How often the host brought this backend up (spec: {@code setup()} is the
     *  opening half of the lifecycle pair).  Counted, not acted on: what a real
     *  backend does here is its own business - the server's promise is only that
     *  it makes the call, on every backend it serves and before a client can ask
     *  for anything.  Asserted as a DELTA, like {@link #shutdownCount}: the
     *  manager is the process-wide one every test class shares. */
    @Getter
    private int setupCount;

    @Override
    public boolean setup() {
        setupCount++;
        return true;
    }

    /** The stub's {@link DeviceRef}: a slot in one direction's list, exactly as
     *  the SPI defines an index.  {@code identity} is what the real QA40x ref
     *  carries - its USB bus and address - and the only thing about an analyzer
     *  that changes when it is unplugged and plugged back in. */
    record StubDeviceRef(AudioBackendType backend, int index, String name, boolean isInput,
            String identity) implements DeviceRef {

        @Override
        public String description() {
            return DESCRIPTION;
        }

        @Override
        public String vendor() {
            return VENDOR;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }

    @Override
    public List<DeviceRef> listInputDevices() {
        return listed(true, FIRST_INPUT, SECOND_INPUT);
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return listed(false, OUTPUT);
    }

    /** One direction's list, read off the SNAPSHOT rather than the bench - a
     *  device pulled out is still in it, and one plugged in is not, until
     *  something rebuilds it.  The index is the slot in this list, as the SPI
     *  defines it, so it closes up when a device leaves. */
    private List<DeviceRef> listed(boolean input, String... names) {
        List<DeviceRef> devices = new ArrayList<>();
        for (String name : names) {
            if (snapshot.contains(name)) {
                devices.add(new StubDeviceRef(backend, devices.size(), name, input,
                        identityOf(name)));
            }
        }
        return devices;
    }

    /** The analyzer's model plus where it currently sits on the bus - the shape
     *  the real finder reports and the ref carries. */
    private String identityOf(String name) {
        return name + " @ bus 1 addr " + usbAddress;
    }

    /** Unplugs the analyzers and plugs them back in: they re-enumerate at a NEW
     *  USB address while their index and their model name stay exactly what they
     *  were, which is what a hot-plug look has to be able to tell. */
    void reattachAtAnotherAddress() {
        usbAddress++;
    }

    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        return (isOutput ? listOutputDevices() : listInputDevices()).get(index);
    }

    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        List<AudioFormat> formats = new ArrayList<>();
        int[] depths = output ? new int[] {BITS_24, BITS_32} : new int[] {BITS_24};
        for (int rate : new int[] {RATE_48K, RATE_96K}) {
            for (int bits : depths) {
                formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits,
                        CHANNELS, bits / 8 * CHANNELS, rate, false));
            }
        }
        return formats;
    }

    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        requireOpenable(device);
        lastCapture = new StubCapture(sampleRate, bitDepth);
        return lastCapture;
    }

    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits) {
        requireOpenable(device);
        lastPlayback = new StubPlayback(sampleRate, bitDepth);
        return lastPlayback;
    }

    /** What a snapshot backend answers when the list it published no longer
     *  describes the bench: the device is gone, or it is back at a place the list
     *  does not know.  Either way the open fails and the driver's own code is all
     *  the caller gets. */
    private void requireOpenable(DeviceRef device) {
        if (openFailsUntilRebuilt || !attached.contains(device.name())) {
            throw new IllegalStateException("Pa_IsFormatSupported(output) failed: "
                    + "Internal PortAudio error (-9986)");
        }
    }

    // --- the analyzer surface of spec 4.6 ------------------------------------
    // Fixed answers with the SHAPE the real analyzer produces - eight formatted
    // strings, two range lists, a calibration row per range - so a test can prove
    // what the server puts on the wire without an analyzer to read it from.

    @Override
    public Qa40xDeviceInfo readDeviceInfo() {
        return DEVICE_INFO;
    }

    @Override
    public int[] inputRangesDbv() {
        return INPUT_RANGES_DBV.clone();
    }

    @Override
    public int[] outputRangesDbv() {
        return OUTPUT_RANGES_DBV.clone();
    }

    @Override
    public int activeInputRangeDbv() {
        return activeInputRangeDbv;
    }

    @Override
    public int activeOutputRangeDbv() {
        return activeOutputRangeDbv;
    }

    @Override
    public void setInputRange(int dbv) {
        activeInputRangeDbv = requireRange(INPUT_RANGES_DBV, dbv);
        storeActiveRange(true, dbv);
    }

    @Override
    public void setOutputRange(int dbv) {
        activeOutputRangeDbv = requireRange(OUTPUT_RANGES_DBV, dbv);
        storeActiveRange(false, dbv);
    }

    /** Records the range now in force as the card's active row, exactly as the
     *  real analyzer's manager does - see {@link #cards}.  No store, or no card in
     *  it, and there is nothing to record. */
    private void storeActiveRange(boolean input, int dbv) {
        Preferences store = cards;
        if (store == null) {
            return;
        }
        AudioDeviceProfile card = store.findAudioDeviceProfile(CARD_NAME);
        if (card == null) {
            return;
        }
        (input ? card.getInput() : card.getOutput())
                .setActiveRange(dbv + RANGE_LABEL_SUFFIX);
        store.saveDevices();
    }

    /** One row per range, with the two channels a hair apart so a test that
     *  crossed left and right on the wire fails on the values. */
    @Override
    public List<CalibrationRow> calibration(boolean input) {
        List<CalibrationRow> rows = new ArrayList<>();
        for (int dbv : input ? INPUT_RANGES_DBV : OUTPUT_RANGES_DBV) {
            rows.add(new CalibrationRow(dbv, CAL_LEFT, CAL_RIGHT));
        }
        return rows;
    }

    /** The analyzer refuses a position it does not have, which is what makes the
     *  server's {@code BAD_REQUEST} for one reachable. */
    private int requireRange(int[] offered, int dbv) {
        for (int candidate : offered) {
            if (candidate == dbv) {
                return dbv;
            }
        }
        throw new IllegalArgumentException("no such QA40x range: " + dbv + " dBV");
    }
}
