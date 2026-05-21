package org.edgo.audio.measure.cli.util;

/**
 * Stereo pair of frequency-response calibrations — one per ADC channel.
 * Used by {@code FreqRespCalibrationStore} so cal-L can be divided into
 * measured-L and cal-R into measured-R independently.  Both fields are
 * non-null and share the same {@code freqs} array length (the loader and
 * the analyzer always produce paired curves on a common frequency grid).
 */
public record StereoFreqRespCalibration(FreqRespCalibration left, FreqRespCalibration right) {}
