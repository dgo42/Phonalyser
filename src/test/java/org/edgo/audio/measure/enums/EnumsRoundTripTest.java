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
