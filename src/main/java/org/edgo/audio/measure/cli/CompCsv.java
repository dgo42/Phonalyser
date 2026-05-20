package org.edgo.audio.measure.cli;

import lombok.Value;
import org.edgo.audio.measure.generator.SignalGenerator;

import java.util.List;

/** Parsed applied_compensation CSV: header metadata + h=1 dBFS/freq + list of harmonic rows (h ≥ 2). */
@Value
public class CompCsv {
    SignalGenerator.Metadata meta;
    double                   fundamentalDbFs;
    double                   fundamentalHz;
    List<CompHarmonic>       harmonics;
}
