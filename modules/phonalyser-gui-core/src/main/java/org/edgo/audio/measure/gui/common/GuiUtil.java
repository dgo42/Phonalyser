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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import lombok.Setter;
import lombok.experimental.UtilityClass;

/**
 * The ONE worker->SWT marshal, instead of a private twin in every class that
 * runs a worker thread.  Anything that may run off the display thread and has
 * to touch a widget - or write a preference, whose bindings are plain UI-only
 * listeners - goes through here.
 */
@UtilityClass
public class GuiUtil {

    /** The application's main shell - the marshal context for code that has no
     *  widget of its own at hand.  Set once by MainWindow at startup. */
    @Setter
    private volatile Shell mainShell;

    /** Runnables {@link #marshal} and {@link #postAsync} queued on the UI
     *  thread that have not started yet. */
    private final AtomicInteger pending = new AtomicInteger();

    /** The count of runnables {@link #marshal} and {@link #postAsync} queued
     *  on the UI thread that have not started yet.  SWT runs queued runnables
     *  only once the native event queue is empty; the main loop's render tick
     *  reads this to leave it empty while any is waiting. */
    public int pendingMarshals() {
        return pending.get();
    }

    /**
     * Marshals {@code r} onto the UI thread, gated on {@code context}:
     * nothing runs when the widget is already disposed - at submit time or by
     * the time the display gets to it.  Already on the UI thread, no
     * marshalling is necessary and {@code r} runs in place - a deliberate
     * SAME-thread deferral is not a marshal and goes through
     * {@link #postAsync}.
     *
     * @return {@code true} when {@code r} ran in place or was queued;
     *         {@code false} when {@code context} was missing or already
     *         disposed at submit time and nothing was done.  A queued
     *         {@code r} can still be skipped later, when {@code context} is
     *         disposed by the time the display gets to it.
     */
    private boolean marshal(Shell context, Runnable r) {
        if (context == null || context.isDisposed()) return false;
        Display display = context.getDisplay();
        if (display.getThread() == Thread.currentThread()) {
            r.run();
            return true;
        }
        pending.incrementAndGet();
        display.asyncExec(() -> {
            pending.decrementAndGet();
            if (context.isDisposed()) return;
            r.run();
        });
        return true;
    }

    /**
     * The no-shell variant for code with no widget of its own at hand (a
     * worker deep below the panes): marshals {@code r} onto the UI thread,
     * gated on the application's {@link #mainShell} - nothing runs before
     * MainWindow published it (headless runs) or once it is disposed, at
     * submit time or by the time the display gets to it.  Already on the UI
     * thread, {@code r} runs in place, like the {@link #marshal(Shell,
     * Runnable)} it mirrors.
     *
     * @return {@code true} when {@code r} ran in place or was queued;
     *         {@code false} when there was no live main shell at submit time
     *         ({@link #isHeadless}) and nothing was done.  A queued {@code r}
     *         can still be skipped later, when the main shell is disposed by
     *         the time the display gets to it.
     */
    public boolean marshal(Runnable r) {
        return marshal(mainShell, r);
    }

    /**
     * {@link #marshal(Runnable)}, then wakes the UI thread from
     * {@code Display.sleep()}: a queued runnable already wakes it, but on
     * Windows the message queue can drop that wake under load.  For a worker
     * handing the UI thread something it waits for.
     *
     * @return {@code true} when {@code r} ran in place or was queued;
     *         {@code false} when there was no live main shell at submit time
     *         ({@link #isHeadless}) and nothing was done.  A queued {@code r}
     *         can still be skipped later, when the main shell is disposed by
     *         the time the display gets to it.
     */
    public boolean marshalAndWake(Runnable r) {
        boolean scheduled = marshal(r);
        Shell context = mainShell;
        if (context == null || context.isDisposed()) return false;
        context.getDisplay().wake();
        return scheduled;
    }

    /** Whether no live main shell exists - before MainWindow published it
     *  (headless runs: tests, a worker never wired to a window) or once it is
     *  disposed.  While this holds, {@link #marshal(Runnable)},
     *  {@link #marshalAndWake} and {@link #postAsync} do nothing, so a caller
     *  holding a resource for the UI thread releases it itself instead of
     *  handing it over.  Any thread; the answer can change right after it is
     *  given, when the main shell is disposed. */
    public boolean isHeadless() {
        Shell context = mainShell;
        return (context == null || context.isDisposed());
    }

    /** {@link #postAsync(Runnable)}, gated on {@code context}. */
    private boolean postAsync(Shell context, Runnable r) {
        if (context == null || context.isDisposed()) return false;
        Display display = context.getDisplay();
        pending.incrementAndGet();
        display.asyncExec(() -> {
            pending.decrementAndGet();
            if (context.isDisposed()) return;
            r.run();
        });
        return true;
    }

    /**
     * Queues {@code r} on the UI thread from any thread - the UI thread
     * included, where it runs behind everything already queued instead of in
     * place - gated on the application's {@link #mainShell} like
     * {@link #marshal(Runnable)}: nothing runs before MainWindow published it
     * (headless runs) or once it is disposed, at submit time or by the time
     * the display gets to it.  Counted in {@link #pendingMarshals} like a
     * queued marshal, so the render heartbeat gives way until it has run.
     * For a hand-over whose ORDER matters: one made on the UI thread still
     * runs behind everything another thread queued before it.
     *
     * @return {@code true} when {@code r} was queued; {@code false} when
     *         there was no live main shell at submit time ({@link #isHeadless})
     *         and nothing was queued.  A queued {@code r} can still be skipped
     *         later, when the main shell is disposed by the time the display
     *         gets to it.
     */
    public boolean postAsync(Runnable r) {
        return postAsync(mainShell, r);
    }


}
