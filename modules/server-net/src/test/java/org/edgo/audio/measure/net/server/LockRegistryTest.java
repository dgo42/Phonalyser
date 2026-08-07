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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 4.3: a lock is per {@code (backend, index, direction)}, held by exactly
 * one connection.  These are the contention cases two clients on one bench
 * actually hit - the second client asking for the busy ADC, the first one
 * dying, and the duplex device that can serve both at once.
 */
class LockRegistryTest {

    private static final int INDEX = 0;
    private static final DeviceLock ADC_IN =
            new DeviceLock(AudioBackendType.QA40X, INDEX, true);
    private static final DeviceLock ADC_OUT =
            new DeviceLock(AudioBackendType.QA40X, INDEX, false);

    private final LockRegistry locks = new LockRegistry();
    private final ServerConfig config = new ServerConfig(new String[0]);
    private final JsonCodec codec = new JsonCodec();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), new StubCardStore().getPrefs());

    @Test
    void aLockIsGrantedToOneConnectionAndRefusedToTheNext() {
        ClientSession first = session();
        ClientSession second = session();

        assertNull(locks.acquire(ADC_IN, first), "the first client gets the device");
        assertSame(first, locks.acquire(ADC_IN, second),
                "the second client is told who holds it, for DEVICE_LOCKED{by}");
        assertSame(first, locks.owner(ADC_IN));
        assertEquals(1, locks.size(), "a refused acquire takes nothing");
    }

    @Test
    void bothDirectionsOfOneDeviceAreSeparateLocks() {
        ClientSession capturing = session();
        ClientSession driving = session();

        assertNull(locks.acquire(ADC_IN, capturing));
        assertNull(locks.acquire(ADC_OUT, driving),
                "one client may capture while another drives the generator");
        assertEquals(2, locks.size());
    }

    @Test
    void reAcquiringOnesOwnLockIsGranted() {
        ClientSession session = session();
        locks.acquire(ADC_IN, session);

        assertNull(locks.acquire(ADC_IN, session), "an idempotent acquire is not a clash");
        assertEquals(1, locks.size());
    }

    @Test
    void releaseFreesTheDeviceForTheNextClient() {
        ClientSession first = session();
        ClientSession second = session();
        locks.acquire(ADC_IN, first);

        assertTrue(locks.release(ADC_IN, first));
        assertNull(locks.owner(ADC_IN));
        assertNull(locks.acquire(ADC_IN, second), "the waiting client can take it now");
    }

    @Test
    void releaseByAConnectionThatNeverHeldItIsRefused() {
        ClientSession holder = session();
        ClientSession stranger = session();
        locks.acquire(ADC_IN, holder);

        assertFalse(locks.release(ADC_IN, stranger), "the answer is NOT_LOCKED");
        assertSame(holder, locks.owner(ADC_IN), "and the holder keeps the device");
    }

    @Test
    void releaseAllDropsEveryLockOfOneConnectionOnly() {
        ClientSession dying = session();
        ClientSession survivor = session();
        locks.acquire(ADC_IN, dying);
        locks.acquire(ADC_OUT, survivor);

        assertEquals(1, locks.releaseAll(dying));
        assertNull(locks.owner(ADC_IN));
        assertSame(survivor, locks.owner(ADC_OUT), "another client's lock is untouched");
    }

    @Test
    void everyOwnershipChangeNotifiesTheListeners() {
        AtomicInteger changes = new AtomicInteger();
        locks.addChangeListener(changes::incrementAndGet);
        ClientSession first = session();
        ClientSession second = session();

        locks.acquire(ADC_IN, first);
        assertEquals(1, changes.get(), "a taken lock grays the device out for everyone");

        locks.acquire(ADC_IN, second);
        assertEquals(1, changes.get(), "a refused acquire changed nothing");

        locks.release(ADC_IN, second);
        assertEquals(1, changes.get(), "so did a release by a non-owner");

        locks.release(ADC_IN, first);
        assertEquals(2, changes.get());

        locks.acquire(ADC_OUT, first);
        locks.releaseAll(first);
        assertEquals(4, changes.get(), "a whole teardown is one change, not one per lock");
    }

    /** A session is only an identity here - the registry never calls back into
     *  it except for the name it logs. */
    private ClientSession session() {
        FakeChannel channel = new FakeChannel();
        Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
        CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(), catalog,
                codec, channel, new FakeWorker(), qa40x);
        GeneratorSession generator = new GeneratorSession(AudioBackend.instance(), catalog,
                captures, new FileStore(), qa40x, codec, channel, new FakeWorker(),
                new FakeTicker(), () -> 0L);
        return new ClientSession(config, locks, qa40x, catalog, captures, generator,
                new Qa40xSession(AudioBackend.instance(), codec,
                        List.of(AudioBackendType.QA40X)), codec, channel,
                new FakeTicker(), new FakeWorker());
    }
}
