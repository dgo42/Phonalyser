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

package org.edgo.audio.measure.gui.scope.gl;

import org.edgo.audio.measure.gui.common.MeasurementPainter;

/**
 * The scope's per-frame draw callback: given a {@link MeasurementPainter} bound to
 * an open frame and the frame's logical size, draw the scope content (the same call
 * the CPU path makes — {@code view.paintCanvas(painter, w, h)}).  A
 * {@link GlScopeSurface} invokes this between its {@code nvgBeginFrame} /
 * {@code nvgEndFrame} so the same render code drives the GPU backend.
 */
@FunctionalInterface
public interface GlScopeRenderer {

    void render(MeasurementPainter painter, int width, int height);
}
