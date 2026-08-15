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

import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unplug the analyzer, plug it back in, and the bench keeps working.
 *
 * <p><b>What went wrong.</b>  The manager opens the device once and keeps the
 * handle; nothing tied "this device is no longer the one I opened" to "give the
 * session up".  So after a detach and re-attach the scan found the analyzer
 * again - the finder enumerates the bus fresh every time - while every
 * operation still wrote the OLD handle and failed with {@code LIBUSB_ERROR_IO},
 * until the server process was restarted - every attempt logging
 * "net gen 6: the output lane on QA40X[0] output failed".
 *
 * <p>The device is identified by model plus USB bus/address, and a re-attached
 * one comes back at a NEW address - which is what makes the scan able to tell.
 */
class Qa40xReattachTest {

    private static final int RATE_HZ = 48_000;
    private static final int BUS = 1;
    private static final int ADDRESS_BEFORE = 5;
    /** Where the analyzer comes back after being unplugged: a different address,
     *  which is what a re-enumeration looks like. */
    private static final int ADDRESS_AFTER = 9;

    private final MovingFinder bus = new MovingFinder();
    private boolean previousTransient;

    @BeforeEach
    void keepTheStoreOutOfIt() {
        // ensureOpen refreshes this device's card from the calibration page it
        // reads; transient mode makes the persist a no-op, so a unit test never
        // writes the real devices.yaml.
        Preferences prefs = Preferences.instance();
        previousTransient = prefs.isTransientMode();
        prefs.setTransientMode(true);
        bus.attach(ADDRESS_BEFORE);
    }

    @AfterEach
    void restoreTheStore() {
        Preferences.instance().setTransientMode(previousTransient);
    }

    /**
     * The failure end to end: a session is open, the analyzer goes away
     * and comes back at another address, and the next scan gives the dead handle
     * up so the following open runs the finder again.
     */
    @Test
    void anAnalyzerThatCameBackAtAnotherAddressCostsOnlyItsSession() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        manager.acquireEngine(RATE_HZ);
        FakeTransport first = bus.lastOpened();
        assertEquals(1, bus.opens(), "the session opened once");

        bus.detach();
        bus.attach(ADDRESS_AFTER);
        manager.listInputDevices();          // the scan the operator's re-plug triggers

        assertTrue(first.ops.contains("close"),
                "the handle to the analyzer that is no longer there was given up");
        manager.acquireEngine(RATE_HZ);
        assertEquals(2, bus.opens(),
                "and the next open ran the FINDER again - without this the manager "
                        + "goes on writing a handle the device no longer answers to, "
                        + "which is every operation failing until the process restarts");
        assertNotSame(first, bus.lastOpened(), "on the analyzer that is actually there");
    }

    /**
     * A unit moved to another port keeps its calibration page: the SESSION goes,
     * the DATA stays.
     *
     * <p>The two identities are different things.  A handle is bound to the bus
     * address it was opened on, so an analyzer that re-enumerated elsewhere makes
     * that handle dead - which is what the discard above is for.  The factory
     * page is bound to the ANALYZER, and the analyzer is what its serial number
     * says it is; the same unit on another port has the same factors, and reading
     * the page again would be a hundred and twenty-eight register round trips to
     * learn what was already known.
     */
    @Test
    void aReplugAtAnotherPortKeepsTheCachedPage() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        manager.refreshDeviceList();                 // warms: one cycle on this unit
        assertEquals(1, bus.opens());

        bus.detach();
        bus.attach(ADDRESS_AFTER);
        manager.listInputDevices();                  // asking what is attached

        assertEquals(1, bus.opens(),
                "nothing had to be opened: a client's device list is the enumeration "
                        + "and nothing more");

        manager.acquireEngine(RATE_HZ);              // the first real use afterwards

        assertEquals(2, bus.opens(), "which does open it, at its new address");
        assertTrue(bus.lastOpened().ops.size() < Qa40xCalibration.CAL_READ_COUNT,
                "and the page was NOT read again - the serial says it is the same "
                        + "analyzer, whatever port it hangs on");
    }

    /** And with nothing on the bus at all: the session goes, and the next open
     *  says so honestly instead of writing into a handle that leads nowhere. */
    @Test
    void anAnalyzerThatIsSimplyGoneLetsGoOfItsSessionToo() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        manager.acquireEngine(RATE_HZ);
        FakeTransport first = bus.lastOpened();

        bus.detach();
        assertTrue(manager.listInputDevices().isEmpty(), "nothing is attached");

        assertTrue(first.ops.contains("close"));
        assertThrows(IllegalStateException.class, () -> manager.acquireEngine(RATE_HZ),
                "the next open looks at the bus rather than at what it remembers");
    }

    /**
     * The other way to learn the same thing, and the one the bench hit first: a
     * transfer that fails.
     *
     * <p>Everything below the manager retries what is worth retrying, so a failure
     * that reaches it has outlived those retries and the handle is spent.
     *
     * <p>Every path this manager runs ON the device gives the session up when it
     * throws - the engine build and the rate change (where the bench's generator
     * lane died), the range writes, the cal page, and this telemetry read.  The
     * read is the one that can be provoked without a streaming lane, because the
     * engine writes its registers at a session boundary and there is none here;
     * it is also the quiet one, answering dashes rather than throwing, which is
     * exactly why the discard has to be explicit in it.
     */
    @Test
    void aTelemetryReadThatFailsGivesUpTheSessionSoTheNextOpenIsFresh() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        manager.acquireEngine(RATE_HZ);
        FakeTransport first = bus.lastOpened();
        first.setFailTransfers(true);

        assertEquals(Qa40xDeviceInfo.NONE, manager.readDeviceInfo(),
                "the panel still gets an answer it can show");

        assertTrue(first.ops.contains("close"), "and the dead handle is gone");
        manager.acquireEngine(RATE_HZ);
        assertEquals(2, bus.opens());
    }

    /**
     * The bench's actual failure, in the form it actually arrived in: not an
     * error code, but a NATIVE FAULT.
     *
     * <p>A QA40x unplugged mid-generation does not make {@code libusb} answer
     * {@code LIBUSB_ERROR_IO} - it makes the invocation itself fault, and JNA
     * raises an Error for that ("Invalid memory access" in
     * {@code libusb_bulk_transfer}).  An Error walks straight through
     * {@code catch (RuntimeException)}, so the session discard - the one thing
     * that makes the next open honest - was skipped for exactly the failure it
     * was written for, and the manager went on writing the dead handle until the
     * server was restarted.
     */
    @Test
    void aNativeFaultGivesTheSessionUpTheSameWayAnErrorCodeDoes() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        bus.setNativeFault(true);

        assertThrows(Error.class, () -> manager.acquireEngine(RATE_HZ),
                "the caller is still told the analyzer stopped answering");

        assertTrue(bus.lastOpened().ops.contains("close"),
                "and the handle it faulted on was given up");
        bus.setNativeFault(false);
        manager.acquireEngine(RATE_HZ);
        assertEquals(2, bus.opens(),
                "so the next open runs the finder again instead of writing a corpse");
    }

    /** The other half: a scan that finds the analyzer exactly where it was must
     *  cost nothing.  A manager that churned its session on every enumeration
     *  would re-read the calibration page and rebuild the card behind every
     *  device combo that opens. */
    @Test
    void aScanThatFindsTheAnalyzerWhereItWasLeavesTheSessionAlone() {
        Qa40xDeviceManager manager = new Qa40xDeviceManager(bus);
        manager.acquireEngine(RATE_HZ);
        FakeTransport first = bus.lastOpened();

        manager.listInputDevices();
        manager.listOutputDevices();
        manager.acquireEngine(RATE_HZ);

        assertFalse(first.ops.contains("close"), "the live session was not disturbed");
        assertEquals(1, bus.opens(), "and nothing was re-opened");
    }

    /**
     * The bus, as the finder reports it: which analyzer is attached and where,
     * and a fresh transport for every open - so a test can count opens and see
     * which handle a manager is holding.
     */
    private static final class MovingFinder extends Qa40xDeviceFinder {

        private final List<Qa40xDevice> attached = new ArrayList<>();
        private final List<FakeTransport> opened = new ArrayList<>();
        /** Whether the analyzer this finder hands out is one whose cable has just
         *  been pulled: every register transfer on it faults natively. */
        private boolean nativeFault;

        @Override
        public List<Qa40xDevice> list() {
            return new ArrayList<>(attached);
        }

        @Override
        public Qa40xTransport open() {
            FakeTransport transport = new FakeTransport();
            if (nativeFault) {
                transport.failNextWithNativeError(Integer.MAX_VALUE);
            }
            opened.add(transport);
            return transport;
        }

        /** The scan's single-attempt open reaches the same bus: this one has no
         *  other process on it, so one pass and three are the same answer. */
        @Override
        public Qa40xTransport openWithoutRetry() {
            return open();
        }

        private void setNativeFault(boolean fault) {
            nativeFault = fault;
        }

        private void attach(int address) {
            attached.add(new Qa40xDevice(Qa40xModel.QA403, BUS, address));
        }

        private void detach() {
            attached.clear();
        }

        private int opens() {
            return opened.size();
        }

        private FakeTransport lastOpened() {
            return opened.get(opened.size() - 1);
        }
    }
}
