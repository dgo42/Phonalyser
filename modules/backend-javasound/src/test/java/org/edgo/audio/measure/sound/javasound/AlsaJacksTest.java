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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AlsaJacks} against a real {@code amixer -c 1 contents} dump, so
 * the parsing is testable on a machine with neither a sound card nor alsa-utils.
 *
 * <p>The fixture is the bench interface this work was built for: a line pair
 * with both plugs in, two microphone sockets and a headphone socket with none.
 */
class AlsaJacksTest {

    /** The bench card's dump - see {@link AlsaPortsTest} for the same card's
     *  {@code /proc} and descriptor fixtures. */
    static final Path CARD1_CONTENTS =
            Paths.get("src", "test", "resources", "amixer", "card1-contents.txt");

    private static final int CARD = 1;

    private final AlsaJacks jacks = new AlsaJacks();

    private Map<String, Boolean> fixture() throws IOException {
        return jacks.parse(Files.readAllLines(CARD1_CONTENTS));
    }

    @Test
    void readsEveryJackAndItsState() throws IOException {
        Map<String, Boolean> found = fixture();
        assertEquals(Boolean.TRUE,  found.get("Line - Input Jack"));
        assertEquals(Boolean.TRUE,  found.get("Line - Output Jack"));
        assertEquals(Boolean.FALSE, found.get("Mic - Input Jack"));
        assertEquals(Boolean.FALSE, found.get("Headphone - Output Jack"));
    }

    @Test
    void readsNothingButJacks() throws IOException {
        Map<String, Boolean> found = fixture();
        assertEquals(4, found.size(),
                "the volumes, the switches and the channel map are not sockets - a control "
                        + "whose name does not end in ' Jack' says nothing about a plug");
        for (String name : found.keySet()) {
            assertTrue(name.endsWith(" Jack"), name);
        }
    }

    @Test
    void twoSocketsOfOneNameAgreeOnlyWhenBothAreEmpty() throws IOException {
        // The dump carries 'Mic - Input Jack' twice (the second with index=1) and
        // nothing in it says which socket serves which PCM device.  Both are out,
        // so the answer is out; had one been in, the answer would be in - the
        // direction that lists a device rather than hiding one.
        assertEquals(Boolean.FALSE, fixture().get("Mic - Input Jack"));
        assertEquals(Boolean.TRUE, jacks.parse(List.of(
                "numid=6,iface=CARD,name='Mic - Input Jack'",
                "  ; type=BOOLEAN,access=r-------,values=1",
                "  : values=off",
                "numid=15,iface=CARD,name='Mic - Input Jack',index=1",
                "  ; type=BOOLEAN,access=r-------,values=1",
                "  : values=on")).get("Mic - Input Jack"));
    }

    @Test
    void aValueThatIsNeitherOnNorOffIsNoAnswerAtAll() {
        assertNull(jacks.parse(List.of(
                "numid=6,iface=CARD,name='Mic - Input Jack'",
                "  : values=13")).get("Mic - Input Jack"),
                "a control that reads as a number is not a connector - and a device must "
                        + "never be hidden on a value nobody understood");
    }

    @Test
    void aMachineWithoutAmixerAnswersNothingRatherThanEmptySockets() {
        // No alsa-utils, no ALSA, no card: the command fails and every query
        // answers null, which the caller must read as "list the device".
        assertNull(jacks.connected(99, "Line - Input Jack"));
        assertNull(jacks.connected(-1, "Line - Input Jack"));
        assertNull(jacks.connected(CARD, null));
    }

    @Test
    void aCardIsReadOnceUntilTheReadingIsForgotten() {
        // The plug is what changes while the program runs, so a reading must not
        // survive an enumeration - this is what makes the server's 2 s rescan
        // notice a cable being pulled - while one enumeration asking about four
        // devices must not run the command four times.
        FixtureJacks bench = new FixtureJacks(CARD, CARD1_CONTENTS);
        assertEquals(Boolean.TRUE,  bench.connected(CARD, "Line - Input Jack"));
        assertEquals(Boolean.FALSE, bench.connected(CARD, "Mic - Input Jack"));
        assertEquals(1, bench.reads(), "one reading serves a whole enumeration");
        bench.forget();
        assertEquals(Boolean.TRUE, bench.connected(CARD, "Line - Input Jack"));
        assertEquals(2, bench.reads());
    }

    @Test
    void aSocketTheCardDoesNotSenseIsNotAnEmptySocket() {
        FixtureJacks bench = new FixtureJacks(CARD, CARD1_CONTENTS);
        assertNull(bench.connected(CARD, "IEC958 In - Input Jack"),
                "the card senses no S/PDIF connector, which is not the same as an empty one");
        assertNull(bench.connected(0, "Line - Input Jack"),
                "and a card whose controls could not be read senses nothing at all");
    }
}
