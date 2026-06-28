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
 *
 * <p>The frame is split into {@link Phase phases} so the persistence ("digital
 * phosphor") surface can render only the {@link Phase#TRACE trace} into a decayed
 * off-screen framebuffer while drawing the {@link Phase#BACKDROP grid} and
 * {@link Phase#OVERLAY overlay} fresh each frame.  Surfaces without persistence
 * just call {@link Phase#ALL}.
 */
public interface GlScopeRenderer {

    /** Which slice of the frame to draw. */
    enum Phase {
        /** The whole frame: backdrop + trace + overlay, in order. */
        ALL,
        /** Background fill + graticule (never persists). */
        BACKDROP,
        /** The waveforms only — the layer that accumulates in the phosphor buffer. */
        TRACE,
        /** Sliders, edge labels, measurement table, header (drawn fresh on top). */
        OVERLAY;

        private Phase() {}
    }

    void renderGl(MeasurementPainter painter, int width, int height, Phase phase);

    /** Whether the most recent {@link Phase#TRACE} render produced genuinely new captured
     *  content (a fresh trigger / free-run frame) rather than a held or re-rendered one.
     *  The persistence surface consults this to decide whether to decay+accumulate (new)
     *  or merely re-composite (held). */
    boolean isLastFrameNew();
}
