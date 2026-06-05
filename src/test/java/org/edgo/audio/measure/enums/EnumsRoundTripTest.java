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

package org.edgo.audio.measure.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round-trip tests for every enum: each value's {@code name()} must
 * resolve back to the same constant via {@code valueOf}.  Catches the
 * common "added a new value but forgot the persistence path" regression
 * before it leaks into the YAML preferences file.
 */
class EnumsRoundTripTest {

    @Test
    void amplitudeUnit_valuesRoundTrip()       { check(AmplitudeUnit.values()); }
    @Test
    void audioBackendType_valuesRoundTrip()    { check(AudioBackendType.values()); }
    @Test
    void audioFileFormat_valuesRoundTrip()     { check(AudioFileFormat.values()); }
    @Test
    void channel_valuesRoundTrip()             { check(Channel.values()); }
    @Test
    void fftMagnitudeUnit_valuesRoundTrip()    { check(FftMagnitudeUnit.values()); }
    @Test
    void fftOverlap_valuesRoundTrip()          { check(FftOverlap.values()); }
    @Test
    void genSignalForm_valuesRoundTrip()       { check(GenSignalForm.values()); }
    @Test
    void oscSliderId_valuesRoundTrip()         { check(OscSliderId.values()); }
    @Test
    void triggerEdge_valuesRoundTrip()         { check(TriggerEdge.values()); }
    @Test
    void triggerMode_valuesRoundTrip()         { check(TriggerMode.values()); }
    @Test
    void windowType_valuesRoundTrip()          { check(WindowType.values()); }

    private static <E extends Enum<E>> void check(E[] values) {
        assertNotNull(values);
        for (E v : values) {
            E back = Enum.valueOf(v.getDeclaringClass(), v.name());
            assertEquals(v, back, "round-trip via valueOf(name()) for " + v);
        }
    }
}
