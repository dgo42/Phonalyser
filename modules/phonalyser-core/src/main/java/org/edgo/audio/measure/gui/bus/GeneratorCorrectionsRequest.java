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
package org.edgo.audio.measure.gui.bus;

/**
 * Payload of {@link Events#GENERATOR_CORRECTIONS}: what the generator is to do
 * with its live correction tables.  A distortion measurement that computes a
 * correction set never holds the generator - it publishes one of these and the
 * generator's owner applies it to the running tone.
 *
 * <p>{@link Kind#CLEAR} carries no arrays.  {@link Kind#HARMONIC} carries the
 * single-tone set the generator's harmonic apply takes ({@code ampRatios},
 * {@code harmonics}, {@code phiInits}; the product coefficients {@code null}).
 * {@link Kind#INTERMOD} carries the dual-tone set ({@code ampRatios},
 * {@code coefA}, {@code coefB}, {@code phiInits}; {@code harmonics}
 * {@code null}).
 *
 * @param kind      what to do with the tables
 * @param ampRatios per-product amplitude ratios against the fundamental
 * @param harmonics the harmonic numbers of a single-tone set, else {@code null}
 * @param coefA     the tone-1 coefficients of a dual-tone set, else {@code null}
 * @param coefB     the tone-2 coefficients of a dual-tone set, else {@code null}
 * @param phiInits  per-product initial phases, radians
 */
public record GeneratorCorrectionsRequest(Kind kind, double[] ampRatios, int[] harmonics,
                                          int[] coefA, int[] coefB, double[] phiInits) {

    /** The three things a request can ask of the correction tables. */
    public enum Kind { CLEAR, HARMONIC, INTERMOD }
}
