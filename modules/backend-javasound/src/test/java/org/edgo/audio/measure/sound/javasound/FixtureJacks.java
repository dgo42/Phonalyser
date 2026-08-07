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

package org.edgo.audio.measure.sound.javasound;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * An {@link AlsaJacks} that answers a saved {@code amixer contents} dump
 * instead of running the command - so the jack state can be driven on a host
 * with neither a sound card nor alsa-utils.
 *
 * <p>Only the card the dump belongs to answers anything; every other card
 * reads as a machine that cannot sense its sockets, which is the case the
 * degradation has to be right for.
 */
class FixtureJacks extends AlsaJacks {

    private final int card;
    private final Path dump;
    private int reads;

    FixtureJacks(int card, Path dump) {
        this.card = card;
        this.dump = dump;
    }

    /** How often the command WOULD have run - what proves a reading is not
     *  taken twice inside one enumeration, nor kept across two. */
    int reads() {
        return reads;
    }

    @Override
    List<String> readContents(int forCard) {
        reads++;
        if (forCard != card) return List.of();
        try {
            return Files.readAllLines(dump);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
