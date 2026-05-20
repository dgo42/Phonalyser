package org.edgo.audio.measure.cli;

import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Loaded ADC-correction coefficient table.  Each harmonic h ≥ 2 carries K
 * complex coefficients (one per order in {@link #orders}); the ADC's
 * predicted contribution to harmonic h's bin (in the rotated frame where
 * fund → −j) is {@code Σ_k (a_k_re + j·a_k_im) · L^{p_k(h)}}.
 */
@Value
public class AdcCorrection {
    List<OrderToken>       orders;
    Map<Integer, double[]> coeffs;   // h → 2·|orders| doubles (re, im pairs)
}
