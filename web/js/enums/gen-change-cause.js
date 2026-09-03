/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.GenChangeCause - the payload of
// GENERATOR_SIGNAL_CHANGED. Lets subscribers tell a user-initiated generator change from a
// closed-loop FLL trim: USER_INPUT means "the signal really changed - drop everything; restart",
// while FLL_TRIM is a sub-Hz alignment tweak that MUST keep any averaging / accumulated
// statistics alive.

export const GenChangeCause = Object.freeze({ USER_INPUT: 'USER_INPUT', FLL_TRIM: 'FLL_TRIM' });
