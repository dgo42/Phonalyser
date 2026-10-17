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

package org.edgo.audio.measure.gui.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuiUtil}'s ways onto the UI thread and their gate on the main shell:
 * {@link GuiUtil#marshal(Runnable)} runs in place on the UI thread and, from
 * another thread, queues - counted, in the order queued;
 * {@link GuiUtil#marshalAndWake} does the same and then wakes the display;
 * {@link GuiUtil#postAsync} always queues - on the UI thread too, behind
 * everything queued before, whichever thread queued it - and is counted.
 * Without a live main shell none of them does anything, and a runnable queued
 * before the main shell is disposed is skipped.
 *
 * <p>The test thread is the display's thread, so a queued runnable runs only
 * when the test drains the display's queue.  A host without a display skips
 * the cases that need one.
 */
class GuiUtilTest {

    /** How long the helper thread may take to queue its runnable, ms. */
    private static final long   JOIN_MS = 5_000L;
    private static final String FIRST   = "first";
    private static final String SECOND  = "second";
    private static final String UI      = "ui";

    private Display display;
    private Shell   mainShell;

    /** Creates the display and its main shell and publishes the shell; skips
     *  the case on a host without a display. */
    private void liveMainShell() {
        try {
            display = new Display();
        } catch (SWTError | UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "SWT display unavailable: " + e.getMessage());
        }
        mainShell = new Shell(display);
        GuiUtil.setMainShell(mainShell);
    }

    /** Runs every runnable queued on the display so far. */
    private void drain() {
        while (display.readAndDispatch()) {
            // keep draining
        }
    }

    /** Runs {@code r} on a fresh thread and waits for it; returns what it
     *  threw, {@code null} when nothing. */
    private Throwable onAnotherThread(Runnable r) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        t.join(JOIN_MS);
        return thrown.get();
    }

    @AfterEach
    void mainShellCleared() {
        GuiUtil.setMainShell(null);
        if (mainShell != null && !mainShell.isDisposed()) mainShell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
    }

    @Test
    void withoutAMainShell_nothingIsMarshalledOrPosted() {
        GuiUtil.setMainShell(null);
        AtomicInteger runs = new AtomicInteger();
        assertTrue(GuiUtil.isHeadless());
        assertFalse(GuiUtil.marshal(runs::incrementAndGet));
        assertFalse(GuiUtil.marshalAndWake(runs::incrementAndGet));
        assertFalse(GuiUtil.postAsync(runs::incrementAndGet));
        assertEquals(0, runs.get());
    }

    @Test
    void onTheUiThreadBothRunInPlace() {
        liveMainShell();
        List<String> order = new ArrayList<>();
        assertFalse(GuiUtil.isHeadless());
        assertTrue(GuiUtil.marshal(() -> order.add(UI)));
        assertTrue(GuiUtil.marshalAndWake(() -> order.add(UI)));
        assertEquals(List.of(UI, UI), order, "both ran before any drain");
    }

    @Test
    void fromAnotherThreadBothQueueInTheOrderTheyWereHandedOver() throws InterruptedException {
        liveMainShell();
        List<String> order = new ArrayList<>();
        Throwable thrown = onAnotherThread(() -> {
            assertTrue(GuiUtil.marshalAndWake(() -> order.add(FIRST)));
            assertTrue(GuiUtil.marshal(() -> order.add(SECOND)));
        });
        assertNull(thrown, "both are callable off the UI thread and queue");
        assertTrue(order.isEmpty(), "nothing ran before the drain");
        drain();
        assertEquals(List.of(FIRST, SECOND), order);
    }

    @Test
    void aPostAsyncOnTheUiThreadQueuesInsteadOfRunningInPlace() {
        liveMainShell();
        List<String> order = new ArrayList<>();
        assertTrue(GuiUtil.postAsync(() -> order.add(UI)));
        assertTrue(order.isEmpty(), "not run in place");
        drain();
        assertEquals(List.of(UI), order);
    }

    @Test
    void aPostAsyncOnTheUiThreadRunsBehindWhatAnotherThreadQueuedBefore() throws InterruptedException {
        liveMainShell();
        List<String> order = new ArrayList<>();
        assertNull(onAnotherThread(() -> assertTrue(GuiUtil.marshalAndWake(() -> order.add(FIRST)))));
        assertTrue(GuiUtil.postAsync(() -> order.add(SECOND)));
        drain();
        assertEquals(List.of(FIRST, SECOND), order);
    }

    @Test
    void everyQueuedRunnableIsCountedAsPending() throws InterruptedException {
        liveMainShell();
        int before = GuiUtil.pendingMarshals();
        assertTrue(GuiUtil.marshal(() -> { }));
        assertTrue(GuiUtil.marshalAndWake(() -> { }));
        assertEquals(before, GuiUtil.pendingMarshals(), "on the UI thread both run in place");
        assertNull(onAnotherThread(() -> GuiUtil.marshal(() -> { })));
        assertEquals(before + 1, GuiUtil.pendingMarshals(), "a marshal from another thread waits counted");
        assertNull(onAnotherThread(() -> GuiUtil.marshalAndWake(() -> { })));
        assertEquals(before + 2, GuiUtil.pendingMarshals(), "and so does a marshalAndWake");
        assertTrue(GuiUtil.postAsync(() -> { }));
        assertEquals(before + 3, GuiUtil.pendingMarshals(), "a postAsync on the UI thread waits counted");
        drain();
        assertEquals(before, GuiUtil.pendingMarshals(), "and each leaves the count once it ran");
    }

    @Test
    void whatWasQueuedBeforeTheMainShellIsDisposedIsSkipped() throws InterruptedException {
        liveMainShell();
        AtomicInteger runs = new AtomicInteger();
        assertNull(onAnotherThread(() -> GuiUtil.marshal(runs::incrementAndGet)));
        assertNull(onAnotherThread(() -> GuiUtil.marshalAndWake(runs::incrementAndGet)));
        assertTrue(GuiUtil.postAsync(runs::incrementAndGet));
        mainShell.dispose();
        assertTrue(GuiUtil.isHeadless());
        assertFalse(GuiUtil.marshalAndWake(runs::incrementAndGet));
        assertFalse(GuiUtil.postAsync(runs::incrementAndGet));
        drain();
        assertEquals(0, runs.get());
    }
}
