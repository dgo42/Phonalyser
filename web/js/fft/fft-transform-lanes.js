/*
 * Phonalyser web - the divided transform's lane protocol (the shared control block).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * ONE definition of how a coordinator and its lanes agree on a stage, used by both sides
 * (fft-transform-pool.js coordinates, fft-pool-worker.js runs a lane), so the two can never
 * drift apart.
 *
 * WHY A CONTROL BLOCK AND NOT MESSAGES. A radix-2 transform of a million points is twenty
 * stages, and every stage must finish everywhere before the next may start anywhere - the next
 * stage reads what this one wrote. Twenty message round trips per transform would cost more than
 * they save; twenty Atomics barriers on a shared Int32Array cost microseconds. Atomics.wait is
 * forbidden on a browser's main thread and allowed in a worker, which is where every participant
 * here runs.
 *
 * The block carries only what a lane cannot derive: the stage length, the transform size, how
 * many lanes there are, and the two counters that make the barrier. Everything else - the
 * twiddle, the group count, which slice is this lane's - each lane derives from those, so no
 * per-lane slot exists to get out of step (dsp/fft.js runStageSlice).
 */

/** Bumped once per stage. A lane sleeps until it changes, which is its signal to run. */
export const CTRL_GENERATION = 0;
/** Lanes that have finished the current stage. The coordinator waits for it to reach LANES. */
export const CTRL_DONE = 1;
/** What the current generation asks for - a stage, or the end of the transform. */
export const CTRL_MODE = 2;
/** The stage length (2, 4, 8, ... n). */
export const CTRL_LEN = 3;
/** The transform size. */
export const CTRL_SIZE = 4;
/** How many lanes are taking part. */
export const CTRL_LANES = 5;
/** Lanes that have entered the loop and captured the generation they start from. The coordinator
 *  waits for this before the first stage: a lane that had not yet captured it would take the
 *  first bump for one already seen and sleep through the stage. */
export const CTRL_READY = 6;
/** 1 while the last stages - the ones with fewer groups than lanes - may be divided by chunking
 *  their k-loop, 0 while they may not. Off, every stage stays bit-identical to the serial
 *  transform and the last stages are divided only as far as their group count allows; the seam
 *  exists so that choice can be made without reworking anything. It travels in the block because
 *  the lanes must apply the SAME rule the coordinator does. */
export const CTRL_KCHUNK = 7;
/** Int32 slots in the control block. */
export const CTRL_LENGTH = 8;

/** This generation is a stage to run. */
export const MODE_STAGE = 0;
/** This generation ends the transform - the lane leaves the loop and answers messages again. */
export const MODE_END = 1;
