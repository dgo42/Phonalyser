package org.edgo.audio.measure.cli.util;

/**
 * Both channels of a single ADC capture, already normalised to {@code [-1, +1]}.
 * Returned by the stereo variant of the capture path so callers can use both
 * channels from one playback instead of running playback twice and throwing
 * away the other channel each time.
 *
 * <p>The two arrays always have the same length — they're filled in lockstep
 * from the same {@code stereo[i]} frames inside the capture listener.
 */
public record StereoSamples(float[] left, float[] right) {}
