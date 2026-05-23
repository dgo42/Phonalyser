package org.edgo.audio.measure.gui.freqresp;

/**
 * Bundle of both channels' Frequency Response results from a single
 * stereo capture + deconvolution pass.  Always carries both — neither
 * field is {@code null} for production runs.
 */
public record StereoFreqRespResult(FreqRespResult left, FreqRespResult right) {}
