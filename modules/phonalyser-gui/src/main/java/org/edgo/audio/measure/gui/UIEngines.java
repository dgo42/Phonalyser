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

package org.edgo.audio.measure.gui;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.fft.FftAnalyzerWorker;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.gui.scope.ScopeController;
import org.edgo.audio.measure.gui.sound.SharedCapture;

import lombok.Getter;

/**
 * Application-lifetime bundle of the three audio engines — the generator
 * playback ({@link GeneratorController}), the oscilloscope's capture
 * handshake ({@link ScopeController}) and the FFT analyser
 * ({@link FftController} with its {@link FftAnalyzerWorker}).
 *
 * <p>Built once by {@link MainWindow} and injected down through
 * {@link MainTab} / {@link MultifunctionalTab} into the panes, so the
 * engines survive the in-place content rebuilds (language / UI-font
 * changes): the rebuilt panes re-attach to the still-running engines
 * instead of stopping and restarting them.  {@link #shutdown()} runs once,
 * from the shell's dispose listener at application exit.
 */
public final class UIEngines {

    @Getter private final GeneratorController generatorController;
    @Getter private final ScopeController     scopeController;
    @Getter private final FftController       fftController;

    public UIEngines(Display display) {
        // Eager init of the SharedCapture singleton so its MessageBus
        // responder is registered BEFORE any pane fires a CAPTURE_ACQUIRE
        // request — otherwise the request returns null and the user
        // thinks the device failed to open.
        SharedCapture.instance();
        generatorController = new GeneratorController();
        scopeController     = new ScopeController();
        fftController       = new FftController(new FftAnalyzerWorker(display));
    }

    /** Stops every engine and releases their capture / playback resources. */
    public void shutdown() {
        fftController.shutdown();
        scopeController.shutdown();
        generatorController.shutdown();
    }
}
