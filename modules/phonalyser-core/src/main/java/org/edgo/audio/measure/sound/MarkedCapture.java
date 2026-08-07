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

package org.edgo.audio.measure.sound;

/**
 * The optional capture capability "this stream carries IN-BAND MARKS": an
 * {@link AudioCapture} whose bytes arrive interleaved with position marks and
 * with confessions of audio that was lost before it ever reached this process.
 *
 * <p><b>Why it is a capability and not part of {@link AudioCapture}.</b>  A local
 * line has neither: its bytes come from an ADC this process is driving, so there
 * is nothing between the samples to announce and nothing that could go missing
 * unnoticed.  A stream that arrives from ANOTHER machine has both - the bench
 * marks where its generator started, and it says so when it dropped data rather
 * than splicing across the hole.  Same shape as {@link RemoteGenerator}: the
 * ordinary implementations do not implement it, and a caller that needs it asks
 * with {@code instanceof} instead of every backend growing a method it has no
 * meaning for.
 *
 * <p><b>Who needs it.</b>  Exactly one kind of caller: something assembling ONE
 * bounded record out of a running stream - the frequency-response sweep, whose
 * record must start at the generator's sample 0 and must not be handed audio
 * with a hole in it.  A scope or an FFT ignores the capability entirely.
 */
public interface MarkedCapture {

    /**
     * Told about the in-band frames a record assembler needs.  ONE listener, not
     * a list: a mark bounds a single measurement's record, and two assemblers on
     * one stream would be two measurements sharing an ADC.
     *
     * <p><b>Threading:</b> called on whichever thread delivers the capture
     * batches, in stream order and interleaved with them - so a listener does not
     * block and does not touch a widget; it marshals.  Both callbacks report a
     * POSITION in the same bytes the batches carry, counted as what the pipeline
     * has already been given, so a caller can place them exactly between two
     * batches it has seen.
     *
     * <p><b>The positions are PER-OPEN.</b>  Opening the stream starts the count
     * at zero again, so a listener that outlives a reopen must never compare a
     * position with one it was told before - the two are measured from different
     * origins, and the difference would read as audio that never existed.
     */
    @FunctionalInterface
    interface Listener {

        /**
         * One position mark.
         *
         * @param markerKind        which mark it is, passed through unread so a
         *                          kind this build does not know reaches the
         *                          caller rather than being dropped as
         *                          unrecognised.  {@code 1} = the generator's
         *                          sweep started, and the first byte AFTER this
         *                          call is aligned with its output sample 0
         * @param pcmBytesDelivered how many PCM payload bytes this stream had
         *                          handed to the pipeline BEFORE the mark - the
         *                          boundary itself, so an assembler knows exactly
         *                          how much of what it has already seen to
         *                          discard
         */
        void marker(int markerKind, long pcmBytesDelivered);

        /**
         * Audio that was lost before this process saw it - an explicit
         * confession, so a measurement in flight fails honestly instead of
         * splicing across the hole and reporting the splice as a result.
         *
         * <p>Default no-op: a caller that only wants the mark says so by not
         * overriding this, and the loss still reaches whoever owns the stream's
         * faults.  It is reported HERE as well because that owner surfaces it to
         * the operator, which is a different question from "is the record this
         * assembler is building still valid".
         *
         * @param lostFrames        how many stereo frames went missing
         * @param pcmBytesDelivered where in the stream the hole falls, in the
         *                          same bytes {@link #marker} counts
         */
        default void gap(long lostFrames, long pcmBytesDelivered) {
            // Nothing: an assembler that does not care is not made to.
        }
    }

    /**
     * Registers the one listener, or {@code null} to stop listening - then the
     * marks are counted and dropped, which is what every stream but a
     * record-bounded one wants.
     */
    void setMarkerListener(Listener listener);
}
