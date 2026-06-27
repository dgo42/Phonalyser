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

package org.edgo.audio.measure.gui.scope;

/**
 * The oscilloscope's pan/zoom engine — the single home for every horizontal and
 * vertical move/zoom transform and the viewport mapping, as pure SWT-/Preferences-free
 * logic so it is fully unit-testable.  Callers (the view, pane, tab control) read the
 * persisted state, hand it to one of these methods, and write the result back.
 *
 * <h2>One buffer, three modes</h2>
 * All three modes render the SAME signal buffer; only its growth differs — LIVE keeps
 * receiving samples, FROZEN (stopped) and FILE never do.  Horizontal position is one
 * model in every mode: a {@code displaySamples}-wide window whose left edge is
 * <pre>  viewLeftAbs = anchorAbs − displaySamples · offsetFrac  </pre>
 * where the <em>anchor</em> is the trigger (live/frozen) or the view centre (file, with
 * {@code offsetFrac = 0.5}).  {@code offsetFrac} is the trigger-position fraction and may
 * leave {@code [0,1]} (a <em>virtual</em> offset) — the handle pins to the screen edge but
 * the window, and the time-offset readout, follow the real value.  Out-of-buffer columns
 * are simply not drawn (the renderer blanks them); the zoom/pan anchor never moves to
 * compensate.
 *
 * <h2>Vertical is independent</h2>
 * Vertical move/zoom never touch any horizontal state, and vice-versa.  Both channels
 * move/zoom together: a manual (off-1-2-5) channel keeps its proportion to the channel
 * that steps the ladder; the move stops when ±FS/2 reaches the canvas middle and zoom-out
 * stops when ±FS fills the grid height.
 */
public final class ScopeNav {

    /** Where the display window lands inside the read buffer (feeds {@code renderTraces}). */
    public record Viewport(int dispStart, double subSampleOffset, int dispCount, double viewLeftAbs) {}

    /**
     * The file/scroll view-window mapping: the read back-offsets for the main +
     * condensed views derived from the scroll centre, plus the centre range the
     * nav-slider widget needs to position its thumb.  Pure — the caller (pane)
     * applies {@code mainBackOffset}/{@code condensedBackOffset} to its views and
     * the centre figures to its scrollbar.
     */
    public record ViewWindow(long mainBackOffset, long condensedBackOffset,
                             long minCentre, long maxCentre,
                             double clampedCentre, boolean followLatest) {}

    private final int      divisionsX;
    private final int      divisionsY;
    private final double[] vDivLadder;

    /**
     * @param divisionsX horizontal grid divisions (the window spans this many)
     * @param divisionsY vertical grid divisions
     * @param vDivLadder ascending 1-2-5 V/div ladder (e.g. {@code OscParse.voltsPerDivTargets()})
     */
    public ScopeNav(int divisionsX, int divisionsY, double[] vDivLadder) {
        this.divisionsX = divisionsX;
        this.divisionsY = divisionsY;
        this.vDivLadder = vDivLadder;
    }

    // =====================================================================
    // Horizontal — viewport mapping
    // =====================================================================

    /** Absolute (fractional) sample at the left edge of the display window. */
    public double viewLeftAbs(double anchorAbs, double offsetFrac, int displaySamples) {
        return anchorAbs - (double) displaySamples * offsetFrac;
    }

    /**
     * Maps the window onto a read buffer that starts at absolute sample
     * {@code bufStartAbs}.  {@code dispCount} is always the full {@code displaySamples};
     * the renderer blanks any column whose sample index falls outside the buffer, so the
     * window can hang off either end without stretching.
     */
    public Viewport viewport(double anchorAbs, double offsetFrac, int displaySamples, long bufStartAbs) {
        double viewLeft = viewLeftAbs(anchorAbs, offsetFrac, displaySamples);
        double local    = viewLeft - bufStartAbs;
        int    dispStart = (int) Math.floor(local);
        return new Viewport(dispStart, local - dispStart, displaySamples, viewLeft);
    }

    /**
     * Derives the file/scroll {@link ViewWindow} from the scroll {@code centreFrames}
     * (absolute frame under the canvas centre; {@code < 0} = follow latest), the
     * window width, and the buffer.  The main view's read ends at the window's right
     * edge; the condensed strip shows a 1&nbsp;s window centred on the same frame.
     */
    public ViewWindow fileViewWindow(double centreFrames, int displaySamples,
                                     long writePos, long capacity, int sampleRate) {
        long oldest    = Math.max(0L, writePos - capacity);
        long minCentre = oldest   + displaySamples / 2;
        long maxCentre = writePos - displaySamples / 2;
        boolean followLatest = centreFrames < 0 || maxCentre < minCentre;
        double clampedCentre;
        long   mainOffset;
        if (followLatest) {
            clampedCentre = maxCentre;     // slider pinned rightmost
            mainOffset    = 0;
        } else {
            clampedCentre = Math.max((double) minCentre, Math.min((double) maxCentre, centreFrames));
            long viewEndAbs = Math.round(clampedCentre + displaySamples / 2.0);
            mainOffset = Math.max(0L, writePos - viewEndAbs);
        }
        long mainCentre = writePos - mainOffset - displaySamples / 2;
        long condEnd    = mainCentre + sampleRate / 2;
        condEnd = Math.min(condEnd, writePos);
        condEnd = Math.max(condEnd, Math.min(writePos, oldest + sampleRate));
        long condOffset = Math.max(0L, writePos - condEnd);
        return new ViewWindow(mainOffset, condOffset, minCentre, maxCentre, clampedCentre, followLatest);
    }

    // =====================================================================
    // Horizontal — move
    // =====================================================================

    /** One ½-division horizontal move tick in offsetFrac units (live/frozen). */
    public double halfDivOffsetStep() {
        return 0.5 / divisionsX;
    }

    /** New trigger offset after one ½-div move tick.  {@code dir} > 0 matches the live
     *  trigger-offset wheel sign; the result is unclamped (it may go virtual). */
    public double moveTriggerOffset(double offsetFrac, int dir) {
        return offsetFrac + dir * halfDivOffsetStep();
    }

    /**
     * Clamps a (possibly virtual) trigger offset so the window still overlaps the
     * captured buffer {@code [oldest, latest)} by at least {@code minOverlap} samples —
     * used on a STOPPED scope so a pan can roam the whole buffer but not vanish past it.
     */
    public double clampFrozenOffset(double offsetFrac, double anchorAbs, int displaySamples,
                                    long oldest, long latest, double minOverlap) {
        double offMin = (anchorAbs - (latest - minOverlap)) / displaySamples;
        double offMax = (anchorAbs - (oldest - displaySamples + minOverlap)) / displaySamples;
        if (offsetFrac < offMin) return offMin;
        if (offsetFrac > offMax) return offMax;
        return offsetFrac;
    }

    /** One ½-division move tick of the file-mode view centre (absolute samples), clamped
     *  so the full window stays inside the file.  {@code dir} matches the wheel sign. */
    public double moveFileCentre(double centreAbs, int dir, int displaySamples, long oldest, long latest) {
        double step = (double) displaySamples / (2.0 * divisionsX);   // ½ div
        return clampFileCentre(centreAbs - dir * step, displaySamples, oldest, latest);
    }

    /**
     * New file view centre after a horizontal zoom around the mouse: the sample
     * under the pointer (screen fraction {@code mouseFrac}) stays put as the window
     * resizes from {@code dispOld} to {@code dispNew} samples.  Caller clamps the
     * result with {@link #clampFileCentre} — once the whole file fits the width the
     * clamp centres it, so the anchor is free to move (per the spec's file limit).
     */
    public double zoomFileCentre(double centreAbs, double mouseFrac, int dispOld, int dispNew) {
        return centreAbs + (mouseFrac - 0.5) * (dispOld - dispNew);
    }

    /** Clamps a file view centre so the whole window stays within {@code [oldest, latest]};
     *  if the window is wider than the file the centre snaps to the file's midpoint. */
    public double clampFileCentre(double centreAbs, int displaySamples, long oldest, long latest) {
        double minC = oldest + displaySamples / 2.0;
        double maxC = latest - displaySamples / 2.0;
        if (maxC < minC) return (oldest + latest) / 2.0;
        if (centreAbs < minC) return minC;
        if (centreAbs > maxC) return maxC;
        return centreAbs;
    }

    // =====================================================================
    // Horizontal — zoom
    // =====================================================================

    /**
     * New trigger offset after a horizontal (t/div) zoom.
     *
     * @param aroundMouse {@code true} for ctrl+shift+wheel (anchor the sample under the
     *        mouse — the offset moves, possibly off-screen); {@code false} for the
     *        resolution control (anchor the trigger — the offset is unchanged).
     */
    public double zoomTriggerOffset(double offsetOld, int dispOld, int dispNew,
                                    double mouseFrac, boolean aroundMouse) {
        // Use the ACTUAL displaySamples ratio (not the t/div ratio): the render rounds
        // displaySamples to whole samples, so at fine time bases (few samples per screen)
        // the t/div ratio diverges from the rendered ratio and the anchor would drift.
        if (!aroundMouse || dispNew <= 0 || dispOld <= 0) return offsetOld;
        return mouseFrac - (mouseFrac - offsetOld) * ((double) dispOld / dispNew);
    }

    // =====================================================================
    // Vertical — move (both channels together, ±FS/2-at-middle limit)
    // =====================================================================

    /** One ½-division vertical move tick in offsetFrac units. */
    public double halfDivOffsetStepY() {
        return 0.5 / divisionsY;
    }

    /**
     * Moves both active channels by one ½-div tick, locked together, clamped so neither
     * channel's zero line passes its ±FS/2-at-middle limit.  {@code dir} > 0 = wheel up =
     * signal up = offset decreases (the established sign).  An inactive channel
     * ({@code on == false}) is ignored for both the clamp and the result.
     *
     * @return {@code {newLeftOffsetFrac, newRightOffsetFrac}}
     */
    public double[] moveVertical(double leftOff, double leftVdiv, boolean leftOn,
                                 double rightOff, double rightVdiv, boolean rightOn,
                                 int dir, double peakVolts) {
        double delta = -dir * halfDivOffsetStepY();
        if (leftOn)  delta = ScopeFormat.clampOffsetDelta(delta, leftOff,  leftVdiv,  peakVolts, divisionsY);
        if (rightOn) delta = ScopeFormat.clampOffsetDelta(delta, rightOff, rightVdiv, peakVolts, divisionsY);
        return new double[] { leftOn  ? leftOff  + delta : leftOff,
                              rightOn ? rightOff + delta : rightOff };
    }

    // =====================================================================
    // Vertical — zoom (coupled V/div + re-anchored offsets)
    // =====================================================================

    /** Largest V/div allowed when zooming out: the smallest 1-2-5 rung at which the ADC
     *  full scale (±peak = 2·peak p-p) still FITS the grid height.  The exact-fill value
     *  2·peak/Ydiv usually lands between rungs (e.g. 0.506 V/div for a 2.53 V peak), so
     *  rounding UP makes the rung that shows all of FS without clipping reachable (1 V/div
     *  there) — 500 mV/div would clip the last sliver of FS. */
    public double zoomOutVoltsPerDivCeiling(double peakVolts) {
        return ScopeFormat.ceilToStep(2.0 * peakVolts / divisionsY, vDivLadder);
    }

    /**
     * Zooms both active channels' V/div one tick (coupled per the 1-2-5 proportional
     * rule) and re-anchors each channel's offset so the voltage under {@code anchorFrac}
     * stays put — {@code anchorFrac = 0.5} for the V/div control (canvas middle),
     * {@code mouseY/h} for ctrl+wheel.  Zoom-out is capped at the FS-fills-height ceiling.
     *
     * @param dir {@code -1} zoom in (smaller V/div), {@code +1} zoom out (larger)
     * @return {@code {newLeftVdiv, newRightVdiv, newLeftOffset, newRightOffset}}
     */
    public double[] zoomVertical(double leftVdiv, double leftOff, boolean leftOn,
                                 double rightVdiv, double rightOff, boolean rightOn,
                                 int dir, double anchorFrac, double peakVolts) {
        double lv = leftOn  ? leftVdiv  : -1.0;
        double rv = rightOn ? rightVdiv : -1.0;
        double[] v = ScopeFormat.coupleVoltsPerDivZoom(lv, rv, dir, vDivLadder,
                zoomOutVoltsPerDivCeiling(peakVolts));
        double newLv = leftOn  ? v[0] : leftVdiv;
        double newRv = rightOn ? v[1] : rightVdiv;
        double newLo = (leftOn  && newLv != leftVdiv)
                ? ScopeFormat.anchorOffsetAfterZoom(leftOff,  leftVdiv,  newLv, anchorFrac)  : leftOff;
        double newRo = (rightOn && newRv != rightVdiv)
                ? ScopeFormat.anchorOffsetAfterZoom(rightOff, rightVdiv, newRv, anchorFrac) : rightOff;
        return new double[] { newLv, newRv, newLo, newRo };
    }
}
