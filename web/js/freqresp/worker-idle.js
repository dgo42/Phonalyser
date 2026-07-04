/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Shared device-idle wait extracted from FreqRespHost (Java
// FreqRespAnalyzerWorker.waitForOtherWorkersStopped) so BOTH the Frequency-
// Response sweep and the Tune-notch wizard can reuse it without duplicating the
// poll loop. It has no state of its own — a plain module function that reads the
// engine's live idle flags and the bus-resolved generator state.

import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

/** Idle poll cadence (ms) — re-check the capture refcount + generator state. */
const POLL_MS = 20;

/**
 * Polls the shared capture + the bus-resolved generator state until BOTH are
 * idle, or the timeout expires (Java FreqRespAnalyzerWorker
 * .waitForOtherWorkersStopped): the FREQRESP_MEASUREMENT_STARTED publish asked
 * every consumer to stop, but their teardowns are async — a measurement must not
 * open the device until the capture refcount hits 0 and the DAC is free.
 *
 * @param {import('../audio/backend.js').AudioEngine} engine the live engine
 *   (engine.running = generator on OR the shared capture holds any reference)
 * @param {number} timeoutMs give-up deadline in ms
 * @param {() => boolean} [isCancelled] optional cooperative-cancel probe; polled
 *   each iteration so a caller's mid-wait cancel aborts the wait early
 * @returns {Promise<boolean>} true once idle; false on timeout / cancel
 */
export async function waitForWorkersIdle(engine, timeoutMs, isCancelled) {
  const bus = MessageBus.instance();
  const deadline = performance.now() + timeoutMs;
  while (performance.now() < deadline) {
    if (isCancelled && isCancelled()) return false;
    // engine.deviceIdle = the shared capture holds NO reference AND its input context has fully
    // CLOSED, the generator is idle AND no output context (tone / file / sweep) is still open —
    // i.e. the OS devices are genuinely released, not merely their flags flipped (stop*() flip
    // the flags synchronously but ctx.close() resolves tens of ms later). The bus responder is
    // an additional guard: it covers any generator-side signal / open-context state and, in a
    // headless harness where the engine stub lacks deviceIdle, keeps the original behaviour.
    const gen = bus.request(Events.GENERATOR_RUNNING) === true;
    const idle = (engine.deviceIdle !== undefined) ? engine.deviceIdle : !engine.running;
    if (idle && !gen) return true;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return false;
}
