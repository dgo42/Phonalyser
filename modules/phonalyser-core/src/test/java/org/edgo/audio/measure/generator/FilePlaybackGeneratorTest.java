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

package org.edgo.audio.measure.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file player's loop boundary and its end of stream - the two seams the
 * render thread crosses, and the two that failed in real playback.
 */
class FilePlaybackGeneratorTest {

    private static final int RATE_HZ = 48_000;
    private static final short BITS = 16;
    /** Deliberately larger than the buffer {@code AudioSystem}'s stream keeps
     *  (4-8 KiB): a fixture that fits inside it is never asked to refill past the
     *  mark, so the mark is never invalidated and {@code reset()} SUCCEEDS - the
     *  reopen fallback would go untested.  16 KiB of audio. */
    private static final int FRAMES = 8192;
    /** Small enough to sit entirely inside that buffer, so its mark survives. */
    private static final int SMALL_FRAMES = 64;
    private static final int WAV_HEADER_BYTES = 44;
    private static final int WAV_RIFF_OVERHEAD = 36;
    private static final int WAV_FMT_CHUNK_BYTES = 16;
    private static final short WAV_PCM = 1;
    private static final short WAV_MONO = 1;
    private static final int BITS_PER_BYTE = 8;

    @TempDir
    private Path tempDir;

    @Test
    void aWavLoopsAcrossItsEndThroughTheReopenFallback() throws Exception {
        // WAV comes back from AudioSystem on a BufferedInputStream, which honours
        // a mark by BUFFERING - so the mark is deliberately tiny, is invalidated
        // the first time the stream must refill past it, and reset() throws.
        // That makes the REOPEN path load-bearing for every WAV and AIFF loop.
        FilePlaybackGenerator player = new FilePlaybackGenerator(wavFile(FRAMES), true);
        try {
            for (int i = 0; i < FRAMES * 3; i++) {
                player.nextSample();
            }
            assertFalse(player.isFinished(),
                    "a looping file never finishes - it crossed each end");
            // The evidence, not the inference: this fixture is bigger than the
            // buffered stream's own buffer, so the mark really was invalidated
            // and each boundary really was a reopen.
            assertEquals(2, player.reopens,
                    "two ends crossed, two reopens - the fallback is the path a "
                            + "WAV loop takes");
        } finally {
            player.close();
        }
    }

    @Test
    void aWavSmallEnoughToStayBufferedRewindsWithoutReopening() throws Exception {
        // The other side of the same coin, and the reason the fixture above had
        // to grow: a file that fits inside the buffered stream's buffer is never
        // asked to refill past the mark, so reset() SUCCEEDS and no reopen
        // happens.  A test written on such a fixture certifies a path it never
        // executes - which is exactly how the fallback shipped untested.
        FilePlaybackGenerator player = new FilePlaybackGenerator(wavFile(SMALL_FRAMES), true);
        try {
            for (int i = 0; i < SMALL_FRAMES * 3; i++) {
                player.nextSample();
            }
            assertFalse(player.isFinished(), "it loops either way");
            assertEquals(0, player.reopens,
                    "but by reset(), not by reopening - the fast path in-memory "
                            + "streams like a decoded FLAC always take");
        } finally {
            player.close();
        }
    }

    @Test
    void aWavThatDoesNotLoopFinishesAtItsEnd() throws Exception {
        FilePlaybackGenerator player = new FilePlaybackGenerator(wavFile(FRAMES), false);
        try {
            for (int i = 0; i < FRAMES * 3; i++) {
                player.nextSample();
            }
            assertTrue(player.isFinished(), "the end of a one-shot file is the end");
            assertEquals(0.0, player.nextSample(),
                    "and it answers silence afterwards - the caller stops it on "
                            + "isFinished(), it does not stop itself");
        } finally {
            player.close();
        }
    }

    @Test
    void theLoopFlagIsReadAtTheBoundaryNotCapturedAtTheStart() throws Exception {
        // Started NOT looping, then ticked mid-play: the lap that is ending must
        // repeat.  This is the contract gen.config's fileLoop rides on.
        FilePlaybackGenerator player = new FilePlaybackGenerator(wavFile(FRAMES), false);
        try {
            player.setLoop(true);
            for (int i = 0; i < FRAMES * 3; i++) {
                player.nextSample();
            }
            assertFalse(player.isFinished(),
                    "the flag is re-read at the end of stream, so a toggle reaches "
                            + "the lap that is ending");

            // And the other way: unticking lets the current lap FINISH rather
            // than cutting playback off where it stands.
            player.setLoop(false);
            assertFalse(player.isFinished(), "not stopped where it stands");
            for (int i = 0; i < FRAMES * 2; i++) {
                player.nextSample();
            }
            assertTrue(player.isFinished(), "it ended at the next boundary");
        } finally {
            player.close();
        }
    }

    /** A mono 16-bit WAV of {@code frames} samples, written by hand so the test
     *  owns every byte - the size is what decides which rewind path is taken. */
    private File wavFile(int frames) throws IOException {
        int dataBytes = frames * BITS / BITS_PER_BYTE;
        ByteBuffer wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(WAV_RIFF_OVERHEAD + dataBytes);
        wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(WAV_FMT_CHUNK_BYTES);
        wav.putShort(WAV_PCM);
        wav.putShort(WAV_MONO);
        wav.putInt(RATE_HZ);
        wav.putInt(RATE_HZ * BITS / BITS_PER_BYTE);
        wav.putShort((short) (BITS / BITS_PER_BYTE));
        wav.putShort(BITS);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(dataBytes);
        for (int i = 0; i < frames; i++) {
            wav.putShort((short) (i * 100));
        }
        Path file = tempDir.resolve("loop-probe-" + frames + ".wav");
        Files.write(file, wav.array());
        return file.toFile();
    }
}
