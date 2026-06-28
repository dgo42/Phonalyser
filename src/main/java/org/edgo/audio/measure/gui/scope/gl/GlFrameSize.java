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

/**
 * One GPU frame's dimensions across the logical / physical split: NanoVG draws in
 * <b>logical</b> points ({@code logicalW × logicalH}) at a {@code pixelRatio}, while
 * the GL viewport, framebuffer objects and textures are sized in <b>physical</b>
 * pixels ({@code pixelW × pixelH}).  On Windows / Linux the two are equal and the
 * ratio is {@code 1}; on a macOS Retina display the physical size is the scaled
 * (e.g. 2×) framebuffer.
 */
public record GlFrameSize(int logicalW, int logicalH, int pixelW, int pixelH, float pixelRatio) {
}
