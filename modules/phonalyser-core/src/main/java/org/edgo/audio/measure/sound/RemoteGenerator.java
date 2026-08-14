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

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.GeneratorControls;
import org.edgo.audio.measure.generator.SignalGenerator;

import lombok.Getter;

/**
 * The optional backend capability "the generator does NOT run in this process":
 * an {@link AudioDeviceManager} that implements it drives a DDS somewhere else
 * and is commanded instead of rendered into.
 *
 * <p><b>Why it IS {@link GeneratorControls}.</b>  The generator controller
 * already speaks that vocabulary - one setter per live parameter, each meaning
 * "apply this to whatever is playing now" - and every one of them has to keep
 * meaning exactly that when the DDS is a network away.  Sharing the interface
 * with {@link SignalGenerator} rather than restating it is what lets the
 * controller choose ONCE where its generator is: a setter that never got its
 * remote branch is then not a silently unsent command but a class that does not
 * compile.  Each call is one command; nothing here is a request for a value.
 *
 * <p><b>State comes back, it is never assumed.</b>  {@link #state()} answers the
 * LAST state the far end pushed and nothing else: a command that has been sent
 * is not a state that has been reached, and the two are deliberately not merged.
 * A reader can therefore be one push behind - by design, because the alternative
 * is a client that reports a frequency the hardware never emitted.  Every
 * consumer of the emitted frequency (the FFT and scope hint plumbing above all)
 * reads it from here rather than recomputing what it believes was commanded.
 *
 * <p><b>Fixed at open.</b>  The dither depth and the output-lane gate are
 * arguments of {@link #openGenerator}, not live setters: they belong to the
 * playback line, which the far end opens once.  A change to either reaches the
 * hardware at the next open, exactly as reopening a local line would.  The
 * per-lane scale is NOT one of them - see {@link #setRightLaneScale(double)}.
 */
public interface RemoteGenerator extends GeneratorControls {

    /**
     * What the far end last said its generator was doing.
     *
     * <p>{@code emitHz} / {@code emit2Hz} are POST-snap, POST-trim - the
     * frequencies actually emitted.  {@code 0.0} is not a frequency: it means
     * this waveform emits no such tone (a sweep and the noise forms have no
     * single {@code emitHz}; every single-tone form has no {@code emit2Hz}), so
     * a consumer must not draw a hint for it.
     */
    record State(boolean running, double emitHz, double emit2Hz) {

        /** Nothing has been heard from the far end yet. */
        public static final State IDLE = new State(false, 0.0, 0.0);
    }

    /** Opens the far end's playback line on {@code device} and creates the
     *  generator, silent until {@link #startGenerator()}.
     *
     *  @return the sample rate the far end GRANTED, which spec 4.5 answers
     *          {@code gen.open} with and which need not be the one asked for -
     *          a DAC that can only run at 44.1 kHz gets the bin grid, the sweep
     *          length and every sample count computed against 44.1 kHz, or the
     *          caller would be measuring one rate and commanding another
     *  @throws IllegalStateException when the far end refuses, with the reason
     *          in the message - a bench that will not give up its DAC is
     *          something the operator has to be told, not a silent no-op */
    int openGenerator(DeviceRef device, int sampleRate, int bitDepth, double ditherBits,
            OutputChannels channels);

    /**
     * Whether this object holds an open generator session on the far end.
     *
     * <p>A LOCAL fact, not a question asked over the wire: it says whether
     * {@link #openGenerator} has succeeded and {@link #closeGenerator} has not
     * yet run, which is exactly what decides whether a {@code gen.*} command has
     * a lane to name.  Playing a file needs one and the tone's start is not the
     * only way to get there, so the caller has to be able to ask.
     */
    boolean isGeneratorOpen();

    /** Emission begins; a sweep starts from sample 0. */
    void startGenerator();

    /** Emission stops.  The line stays open until {@link #closeGenerator()}. */
    void stopGenerator();

    /** Stops and gives the far end's playback line back.  Idempotent: every
     *  teardown path calls it blindly. */
    void closeGenerator();

    /* -------------------- what only a far-away lane needs told -------------------- */

    /**
     * The RIGHT lane's output scale - {@code fsLeft/fsRight}, so a card whose two
     * DAC full-scales differ emits the same physical level on both lanes.
     *
     * <p>Not part of {@link GeneratorControls} because locally it is not a
     * generator setting at all: the amplitude is computed against the LEFT
     * full-scale and the ratio is pushed to the playback line's quantizer, which
     * on a remote bench is the far end's.  It is live rather than an argument of
     * {@link #openGenerator} for the same reason the DAC full-scale itself is -
     * recalibrating a card must take effect on the tone that is playing.
     */
    void setRightLaneScale(double scale);

    /**
     * The TPDF dither depth in bits, live-applied to the far end's quantizer.
     *
     * <p>Not part of {@link GeneratorControls} for the same reason as the lane
     * scale above: locally dither is a playback-line setting, pushed to the
     * quantizer rather than to the generator - and on a remote bench that
     * quantizer is the far end's.  {@link #openGenerator} carries the initial
     * depth; this is the live half, so the operator's dither edit reaches the
     * tone that is playing instead of the next open.
     */
    void setDitherBits(double bits);

    /**
     * The silence a sweep emits BEFORE its first chirp sample, in samples of the
     * lane's own clock.
     *
     * <p>Not part of {@link GeneratorControls} for the same reason as the lane
     * scale above: locally it is not a live setting at all but a constructor
     * argument of {@link SignalGenerator}, because changing it means rebuilding
     * the sweep.  A far-away lane has no constructor to pass it to, so it travels
     * as a command - and it has to travel, because a frequency-response record is
     * deconvolved against a reference that starts with exactly this much silence:
     * a bench that emitted none would put the chirp where the analyzer looks for
     * the lead-in and smear the whole response.
     */
    void setSweepLeadInSamples(int samples);

    /* ---------------------------- the analyzer's grid ---------------------------- */

    /** Tells the far end the analyzer's FFT grid, so it snaps the emitted
     *  frequency onto {@code k·rate/fftSize} with the SAME math the local snap
     *  uses - two implementations of one rounding rule would disagree in the
     *  last bin and the frequency-lock loop would spend the difference chasing
     *  its own tail. */
    void fftGrid(int fftSize, boolean snapEnabled);

    /** The frequency-lock loop's actuator: the ABSOLUTE corrected frequency for
     *  tone 1.  The servo stays in the analyzer; only the correction travels. */
    void trim(double hz);

    /** The same for tone 2 of a two-tone signal. */
    void trim2(double hz);

    /** Drops both trims - the tones slide back onto their snapped nominals. */
    void trimReset();

    /** The last state the far end pushed; never null, {@link State#IDLE} until
     *  the first push. */
    State state();

    /* ------------------------- playing a file on the bench ------------------------- */

    /**
     * What the far end last said its FILE playback was doing - the file twin of
     * {@link State}, and read the same way: the last pushed truth, never an
     * assumption about a command that has been sent.
     *
     * <p>{@code playing} is the bench's own answer, so the operator's indicator
     * follows the hardware rather than the click.  {@code finished} is the
     * end-of-file edge a NON-looping file reaches on its own - the remote twin of
     * the local play loop simply returning, and what lets the controller clear its
     * running flag without the operator pressing stop.
     */
    record FileState(boolean playing, boolean finished) {

        /** Nothing has been heard from the far end yet. */
        public static final FileState IDLE = new FileState(false, false);
    }

    /**
     * Uploads {@code content} to the bench and plays it through the generator
     * lane this object already holds open.
     *
     * <p><b>Upload-and-play, not streaming.</b>  There is no client->server PCM in
     * this protocol version (spec §7), so the bytes travel once over {@code PUT
     * /files} and the far end's own generator lane renders them.  The file is
     * owned by nobody until this call references it, and the bench drops it when
     * this connection closes - so a caller never has to clean up after itself.
     *
     * @param content  the whole encoded file (WAV / AIFF / FLAC - whatever the
     *                 bench can decode), at most
     *                 {@code NetProto.MAX_UPLOAD_BYTES}
     * @param mimeType the file's audio MIME type, or null when the caller cannot
     *                 tell.  Sent with the upload so the far end picks its decoder
     *                 from what the file IS rather than from what its first bytes
     *                 resemble - the two differ for a FLAC behind an ID3 tag, and
     *                 nothing in the bytes distinguishes an AIFF the sniff would
     *                 have to guess at.  Null leaves the bench on its own content
     *                 sniff, which is what it did before this travelled.
     * @param loop     whether the bench repeats the file at end-of-file
     * @throws FileTooLargeException when {@code content} is over the protocol's
     *         upload limit - raised BEFORE anything is sent, and carrying both
     *         numbers so the caller can say them in the operator's language
     * @throws IllegalStateException when the upload or the command is refused,
     *         with the reason in the message - the same contract
     *         {@link #openGenerator} carries, for the same reason: a bench that
     *         will not play the file is something the operator has to be told
     */
    void playFile(byte[] content, String mimeType, boolean loop);

    /**
     * The file is bigger than the wire protocol accepts.
     *
     * <p>Declared HERE rather than in the backend that raises it because the
     * caller is the generator controller, which sees only this interface: an
     * over-size file is an ordinary thing an operator does, and telling them the
     * two numbers is the whole point - a refusal they cannot read the size out of
     * is a refusal they cannot act on.
     */
    final class FileTooLargeException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        @Getter
        private final long bytes;
        @Getter
        private final long limitBytes;

        public FileTooLargeException(String message, long bytes, long limitBytes) {
            super(message);
            this.bytes = bytes;
            this.limitBytes = limitBytes;
        }
    }

    /** Stops file playback on the bench.  Idempotent: every teardown path calls
     *  it blindly, exactly as {@link #closeGenerator()} is called. */
    void stopFile();

    /**
     * The repeat flag of the file that is playing NOW - live, like every other
     * setter on this interface.
     *
     * <p>{@link #playFile} carries the flag the file started with; this is how a
     * toggle mid-play reaches the far end.  The bench re-reads it at each end of
     * stream, so ticking it makes the lap that is ending loop and unticking it
     * lets that lap FINISH rather than cutting it off - which is exactly what the
     * local player does with the same toggle.  A no-op when no file is playing.
     */
    void setFileLoop(boolean loop);

    /** The last file-playback state the far end pushed; never null,
     *  {@link FileState#IDLE} until the first push. */
    FileState fileState();

    /**
     * Claims the file lane's from-below end for the ONE operator report - the
     * file twin of {@link #takeEndedFromBelowForReport()}: non-null exactly once
     * after the bench failed a file it had accepted, {@code null} while it plays,
     * after a commanded stop, and for every caller after the first.
     */
    Throwable takeFileErrorForReport();

    /**
     * Claims the remote lane's from-below end for the ONE operator report -
     * the far twin of the local playback lane's claim: non-null exactly once
     * after the bench confessed its output lane died ({@code ev.device.error})
     * or the session ended with the tone still up; {@code null} while the
     * lane is healthy, after a commanded stop/close, and for every caller
     * after the first.  The pane's visual sync points consult this through
     * the lane, exactly as they consult the local lane - the bottom-up path,
     * no event.
     */
    Throwable takeEndedFromBelowForReport();
}
