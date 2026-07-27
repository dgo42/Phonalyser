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

package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.graphics.PaletteData;

/**
 * Caller-owned scratch for {@link GcMeasurementPainter#drawAlphaImage}: the reusable
 * byte arrays that back the per-frame {@code ImageData} of the CPU digital-phosphor
 * blit, so a steady-state frame churns ZERO arrays.
 *
 * <p>{@code GcMeasurementPainter} is constructed fresh per paint, so the pool cannot
 * live there — the caller ({@code PhosphorRenderer}) owns ONE instance across frames
 * and passes it in.  The NanoVG backend ignores it (it stages into its own native
 * buffer).
 *
 * <p><b>The SWT {@code Image} handle is still created and disposed on every blit</b> —
 * an SWT {@code Image} cannot be pixel-updated in place, so only the backing arrays
 * (and the palette) are pooled here.  The arrays are sized EXACTLY to the request and
 * reallocated only when the size changes (the plot size is stable, so a resize
 * reallocates once and every subsequent same-size frame reuses them) — exact length so
 * the {@code rgb} array can back an {@code ImageData} with no length ambiguity.
 */
public final class AlphaImageScratch {

    /** 24-bit direct-RGB palette (red high byte) matching the packed {@link #rgb}
     *  layout — created once so the blit allocates no palette per frame. */
    private final PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
    /** Packed 24-bit RGB backing array (R, G, B per pixel), sized exactly {@code pixels·3}. */
    private byte[] rgb;
    /** Per-pixel coverage backing array, sized exactly {@code pixels}. */
    private byte[] alpha;

    public PaletteData palette() {
        return palette;
    }

    /** The packed-RGB backing array, sized EXACTLY {@code pixels·3} bytes (reallocated only
     *  when {@code pixels} changes).  Caller fills all {@code pixels·3} bytes with the tint. */
    public byte[] rgb(int pixels) {
        int need = pixels * 3;
        if (rgb == null || rgb.length != need) rgb = new byte[need];
        return rgb;
    }

    /** The per-pixel coverage backing array, sized EXACTLY {@code pixels} bytes (reallocated
     *  only when {@code pixels} changes). */
    public byte[] alpha(int pixels) {
        if (alpha == null || alpha.length != pixels) alpha = new byte[pixels];
        return alpha;
    }
}
