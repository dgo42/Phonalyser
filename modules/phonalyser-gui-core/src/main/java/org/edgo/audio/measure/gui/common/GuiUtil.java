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

    /**
     * Marshals {@code r} onto the UI thread, gated on {@code context}:
     * nothing runs when the widget is already disposed - at submit time or by
     * the time the display gets to it.  Already on the UI thread, no
     * marshalling is necessary and {@code r} runs in place - a deliberate
     * SAME-thread deferral is not a marshal and stays on
     * {@code Display.asyncExec} directly.
     */
    public void marshal(Shell context, Runnable r) {
        if (context == null || context.isDisposed()) return;
        Display display = context.getDisplay();
        if (display.getThread() == Thread.currentThread()) {
            r.run();
            return;
        }
        display.asyncExec(() -> {
            if (context.isDisposed()) return;
            r.run();
        });
    }

    /**
     * The no-shell variant for code with no widget of its own at hand (a
     * worker deep below the panes): marshals {@code r} onto the UI thread,
     * gated on the application's {@link #mainShell} - nothing runs before
     * MainWindow published it (headless runs) or once it is disposed, at
     * submit time or by the time the display gets to it.  Already on the UI
     * thread, {@code r} runs in place, like the {@link #marshal(Shell,
     * Runnable)} it mirrors.
     */
    public void marshal(Runnable r) {
        marshal(mainShell, r);
    }

}
