package org.edgo.audio.measure.cli.util;

import lombok.Value;

/**
 * One harmonic row parsed from an applied_compensation CSV.
 * {@code re}/{@code im} are in the rotated frame where fundamental → (0, −1),
 * so they are already the harmonic complex value relative to the fundamental.
 */
@Value
public class CompHarmonic {
    int    h;
    double freqHz;
    double re;
    double im;
}
