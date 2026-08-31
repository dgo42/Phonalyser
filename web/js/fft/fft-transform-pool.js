/*
 * Phonalyser web - the divided FFT transform: shared buffers, lanes, and the per-stage barrier.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The web's answer to Fft.java's daemon pool. The desktop splits each butterfly stage across
 * availableProcessors-1 threads that share the array by reference and meet at a barrier between
 * stages; two web workers can only share a Float64Array when it is backed by a SharedArrayBuffer,
 * and that is allowed only in a cross-origin-isolated page. So this class owns the shared pair,
 * hands it to its lanes once, and drives the same stage-by-stage barrier through an atomic
 * control block.
 *
 * WHY THE TRANSFORM IS STAGED THROUGH ITS OWN BUFFERS rather than the analyser allocating shared
 * ones: the analyser allocates a frame pair per prelude, and a fresh SharedArrayBuffer per tick
 * would be tens of megabytes of allocation a second. This holds ONE pair for the current size and
 * copies the frame in and out - four passes over the arrays, a few milliseconds against a
 * hundred-odd for the transform itself - which also keeps the analyser free of any knowledge that
 * threads exist.
 *
 * NOT the frame pool of fft-worker.js, deliberately. That one exists to split several averaging
 * frames across workers, is rebuilt whenever its width changes, and is torn down by a watchdog
 * when a sub-worker wedges. Lanes parked in Atomics.wait must not be terminated underneath a
 * transform, so the two keep their own workers; in practice only one of them is ever alive,
 * since a tick of one or two frames has nothing to split.
 */
import { fft, bitReverse, runStageSlice, PARALLEL_THRESHOLD } from '../dsp/fft.js';
import {
  CTRL_GENERATION, CTRL_DONE, CTRL_MODE, CTRL_LEN, CTRL_SIZE, CTRL_LANES, CTRL_READY,
  CTRL_KCHUNK, CTRL_LENGTH, MODE_STAGE,
} from './fft-transform-lanes.js';
import { describeErrorEvent } from '../util/debug.js';

/** Bytes per Float64Array element - the shared pair is sized in them. */
const BYTES_PER_SAMPLE = 8;

/** How long a barrier waits for a lane before giving up on it. A lane that never reports - a
 *  worker that failed to load, a context torn down under it - would otherwise hold the
 *  coordinator in Atomics.wait forever and stall every further tick, which is the one failure
 *  this must not have. On expiry the lanes are dropped and the transform is completed serially,
 *  the same self-heal the frame pool's watchdog performs. */
const LANE_WATCHDOG_MS = 5000;
/** warmUp calls (one per analysis tick) before a WEDGED lane set is rebuilt and division retried.
 *  Roughly five seconds of ticks at the large-FFT geometries: long enough that a page still
 *  throttled in the background does not pay a lane watchdog per tick, short enough that a page
 *  returning to the foreground gets its lanes back within moments. */
const WEDGE_RETRY_TICKS = 20;

/**
 * Whether the last stages - the ones holding fewer groups than there are lanes - may be divided
 * by chunking their k-loop.
 *
 * ON is the desktop's own trade (Fft.java): those stages carry the largest fifth of the work, and
 * leaving them to as many lanes as they have groups caps the whole transform however many cores
 * there are. The price is that they no longer reproduce the serial transform's last bits, because
 * a chunk seeds its twiddle partway along - which is what the desktop's parallel transform has
 * always done above its own size threshold, and is why its result already depends on the core
 * count of the machine it ran on.
 *
 * OFF makes EVERY stage bit-identical to the serial transform - each lane still runs whole
 * groups, seeded at 1 + 0i - at a speedup capped by the group count of the last stages. It is one
 * constant, and both paths are exercised by the partition test, so the choice can be made on
 * measurements rather than on a rewrite.
 */
const DIVIDE_LAST_STAGES = true;

/** Whether this context may divide a transform at all: shared memory needs cross-origin
 *  isolation, which a page served without the two COOP/COEP headers does not have. Everything
 *  keeps working without it - the transform simply stays serial, which is also what the desktop
 *  does below its own size threshold and on small machines. */
export function canDivideTransforms() {
  return typeof SharedArrayBuffer === 'function' && globalThis.crossOriginIsolated === true;
}

/**
 * One line saying which regime this session actually runs in - divided across how many lanes, or
 * serial and WHICH condition made it serial. Every one of those conditions is invisible from the
 * screen: a page served without the two isolation headers, a browser without shared memory, a
 * Threads setting of one and an FFT length below the threshold all produce the same picture, a
 * serial transform, and the operator has no way to tell them apart or to know a setting had no
 * effect. It is a warning rather than a debug line because it is the answer to "why is this
 * slow", and it must be in the console of a session that was not started with debugging on.
 *
 * @param {number} lanes the Threads setting this session dispatches with.
 * @param {number} n the transform size of the first tick.
 */
export function describeTransformRegime(lanes, n) {
  const hasSab = typeof SharedArrayBuffer === 'function';
  const isolated = globalThis.crossOriginIsolated === true;
  const state = `crossOriginIsolated=${isolated} SharedArrayBuffer=${hasSab}`
    + ` threads=${lanes} fftSize=${n}`;
  if (!hasSab) return `FFT transform SERIAL: this browser has no SharedArrayBuffer (${state})`;
  if (!isolated) {
    return 'FFT transform SERIAL: the page is not cross-origin isolated, so the lanes cannot share'
      + ' the sample arrays - the server must send Cross-Origin-Opener-Policy: same-origin and'
      + ` Cross-Origin-Embedder-Policy: credentialless (${state})`;
  }
  if (lanes < 2) return `FFT transform SERIAL: one thread selected (${state})`;
  if (n < PARALLEL_THRESHOLD) {
    return `FFT transform SERIAL: ${n} points is below the ${PARALLEL_THRESHOLD}-point threshold`
      + ` where dividing pays (${state})`;
  }
  return `FFT transform DIVIDED across ${lanes} lanes (${state})`;
}

export class FftTransformPool {
  /**
   * @param {number} lanes how many workers to divide a stage across - the Threads setting, which
   *   is what finally gives that control something to do: it is the transform, not the frame
   *   count, that it now divides.
   * @param {() => Worker} newLane builds one lane worker. Injected so this class owns no URL and
   *   no module resolution, and so a test can hand it something else.
   * @param {string} [laneUrl] the resolved script URL a lane is built from, for the failure
   *   diagnostics ALONE - nothing here loads it. A lane set that never starts is learned from a
   *   timeout, not from an event, so without this the one warning that reports it can name no
   *   file, and "the lanes did not start" is unactionable.
   */
  constructor(lanes, newLane, laneUrl = '') {
    this._lanes = Math.max(1, lanes | 0);
    /** The count the next transform is to adopt - see setLanes. */
    this._wantLanes = this._lanes;
    this._newLane = newLane;
    this._laneUrl = laneUrl || '';
    this._workers = [];
    this._ctrl = null;      // Int32Array over a SharedArrayBuffer - the barrier + the stage
    this._re = null;        // the shared staging pair, sized for the current transform
    this._im = null;
    this._size = 0;
    this._laneFailed = false;   // a lane failed to LOAD (or shared memory was refused) - stay serial
    /** A lane stopped answering mid-transform - a WEDGE, not a load failure. A browser can
     *  throttle a backgrounded page's workers into exactly this, and the page then returns to the
     *  foreground with healthy lanes latched off; so unlike a load failure the wedge heals: after
     *  the cooldown below, warmUp rebuilds the set and division resumes. A set that wedges again
     *  pays one watchdog per cooldown, never one per tick. */
    this._laneWedged = false;
    this._wedgeCooldownTicks = 0;
    /** What the last lane failure said, or null. Held because the two BARRIER timeouts below
     *  learn only that nobody answered: the lane's own error - which arrives on this thread's
     *  event loop, and therefore not before a timed Atomics.wait has returned - is the half that
     *  names the cause. */
    this._laneError = null;
  }

  /** How many lanes a transform is divided across. */
  get lanes() { return this._lanes; }

  /** Records a changed Threads setting. NOTHING is torn down here: the new count is taken up by
   *  the next transform that starts, which is where a lane set can be replaced without abandoning
   *  work. Tearing the lanes down at the moment the setting changes left whatever was running to
   *  be given up on by a watchdog, so a thread change cost seconds of dead FFT - the desktop
   *  re-reads its own thread count per execution and tears nothing down, which is the reference.
   *
   *  The failure latch is deliberately NOT cleared. A lane set that could not start will not
   *  start because the operator moved a combo, and re-arming it made every thread change pay the
   *  watchdog again. */
  setLanes(lanes) {
    this._wantLanes = Math.max(1, lanes | 0);
  }

  /** Frees the lanes and the shared pair (a stopped analyser holds no worker). */
  terminate() {
    for (const w of this._workers) { try { w.terminate(); } catch (_) {} }
    this._workers = [];
    this._ctrl = null;
    this._re = null;
    this._im = null;
    this._size = 0;
    // The lanes that error described are gone; a set built after this must not be reported
    // through their failure text. Diagnostics only - nothing branches on it.
    this._laneError = null;
  }

  /** Whether a transform of `n` points is worth dividing here. Below the desktop's own threshold,
   *  or with a single lane, or in a page without shared memory, it is not - and neither is it
   *  while the lanes have not ANNOUNCED themselves yet: a lane set is spawned in the background
   *  and a transform never waits for it (see warmUp). */
  divides(n) {
    return n >= PARALLEL_THRESHOLD && this._lanes >= 2 && !this._laneFailed && !this._laneWedged
      && canDivideTransforms() && this._lanesAnnounced(n);
  }

  /** Whether the lane set for a transform of `n` points is built, holding the pair this size
   *  needs, and reported for duty. Read without blocking - the answer decides between the divided
   *  and the serial transform, never a wait. */
  _lanesAnnounced(n) {
    return this._ctrl != null && this._size === n
      && this._workers.length === this._lanes - 1
      && Atomics.load(this._ctrl, CTRL_READY) >= this._workers.length;
  }

  /**
   * Spawns the lane set for transforms of `n` points and leaves it to announce itself, WITHOUT
   * waiting: a lane worker takes a moment to load, and paying that inside the first transform is
   * what made a cold start cost most of a second at the head of a session. Called at the same
   * point the Threads setting is pushed, so the lanes are ready long before a transform asks for
   * them; a transform that arrives first simply runs serially and the next one asks again.
   * Costs nothing once the set stands: it builds only what is missing.
   */
  warmUp(n) {
    if (this._wantLanes !== this._lanes) { this._lanes = this._wantLanes; this.terminate(); }
    // A wedge heals here: once the cooldown has elapsed, the set is rebuilt and division resumes.
    if (this._laneWedged && --this._wedgeCooldownTicks <= 0) this._laneWedged = false;
    if (this._lanes < 2 || this._laneFailed || this._laneWedged || n < PARALLEL_THRESHOLD) return;
    if (!canDivideTransforms()) return;
    try {
      this._ensure(n);
    } catch (_) {
      // Shared memory refused at this size. Nothing is retried and nothing waits: transforms stay
      // serial, which is the same outcome a page without shared memory has.
      this.terminate();
      this._laneFailed = true;
    }
  }

  /**
   * Transforms `re` / `im` in place, dividing the work when that is worth it and running the
   * plain serial transform when it is not. The caller cannot tell the difference except in time:
   * this is the `transform` the analyser is constructed with, and it has the signature of
   * dsp/fft.js fft.
   */
  transform(re, im) {
    const n = re.length;
    // A changed Threads setting is adopted HERE, at the start of a transform, where replacing the
    // lane set abandons nothing that is running.
    if (this._wantLanes !== this._lanes) {
      this._lanes = this._wantLanes;
      this.terminate();
    }
    if (!this.divides(n)) { fft(re, im); return; }
    try {
      this._ensure(n);
    } catch (_) {
      // Shared memory refused at this size (an allocation failure, a policy this context does not
      // hold after all). The measurement matters more than the speed: fall back and stay serial.
      this.terminate();
      fft(re, im);
      return;
    }
    this._re.set(re);
    this._im.set(im);
    // Bit-reversal permutes across the whole array, so no lane can own a slice of it.
    bitReverse(this._re, this._im);
    if (!this._runStages(n)) {
      // A lane stopped answering. The input arrays were never written, so the transform is simply
      // taken over here, and the lanes are gone for good rather than wedging every later tick.
      console.warn('FFT transform lane stopped answering'
        + this._laneOrigin() + ' - transforms run on one thread until the lanes are rebuilt');
      this.terminate();
      this._laneWedged = true;                        // heals after the cooldown - see warmUp
      this._wedgeCooldownTicks = WEDGE_RETRY_TICKS;
      fft(re, im);
      return;
    }
    re.set(this._re);
    im.set(this._im);
  }

  /** Every stage in turn, each divided across the lanes and closed by a barrier: the next stage
   *  reads what this one wrote, so none of it may start until all of it has finished.
   *  @returns {boolean} false when a lane stopped answering and the transform is unfinished. */
  _runStages(n) {
    const ctrl = this._ctrl;
    // The lane count and the barrier target are ONE quantity: the lanes that exist. Deriving both
    // from the worker list makes a mismatch unrepresentable - a count taken from the requested
    // size instead could name a slice no lane runs, and an unrun slice is a wrong spectrum, which
    // is worse than the timeout a missing lane would give.
    const lanes = this._workers.length + 1;   // + the coordinator, which is lane 0
    // The lanes are ALREADY parked in their stage loop - they were started and counted at warm-up
    // and they stay there for the life of the set, so a transform costs no handshake and waits for
    // no announcement. The generation therefore never restarts: a parked lane sleeps on the value
    // it last saw, and resetting the counter under it would wake every lane into a stage the
    // coordinator has not described yet.
    Atomics.store(ctrl, CTRL_DONE, 0);
    Atomics.store(ctrl, CTRL_SIZE, n);
    Atomics.store(ctrl, CTRL_LANES, lanes);
    Atomics.store(ctrl, CTRL_KCHUNK, DIVIDE_LAST_STAGES ? 1 : 0);
    Atomics.store(ctrl, CTRL_MODE, MODE_STAGE);
    for (let len = 2; len <= n; len <<= 1) {
      Atomics.store(ctrl, CTRL_LEN, len);
      Atomics.store(ctrl, CTRL_DONE, 0);
      Atomics.store(ctrl, CTRL_MODE, MODE_STAGE);
      Atomics.add(ctrl, CTRL_GENERATION, 1);
      Atomics.notify(ctrl, CTRL_GENERATION);
      // Lane 0 is this coordinator: it takes a share rather than idling through every stage. The
      // desktop's caller blocks instead, because its pool holds a thread per core besides it;
      // here the coordinator IS one of the lanes, so the same cores are used by one fewer worker.
      runStageSlice(this._re, this._im, n, len, 0, lanes, DIVIDE_LAST_STAGES);
      if (!this._await(CTRL_DONE, this._workers.length)) return false;
    }
    // No end message: the lanes stay parked for the next transform, which is what makes a warm
    // set free. They leave only with the set itself (terminate).
    return true;
  }

  /** The script a lane is built from, for a warning that has to name a file. Empty when the
   *  caller did not state one. */
  _laneScript() {
    return this._laneUrl ? ' [lane script ' + this._laneUrl + ']' : '';
  }

  /** Where a lane comes from and what the last one said - the two facts a barrier timeout cannot
   *  learn by itself, appended to the warning that reports one. Empty when neither is known. */
  _laneOrigin() {
    return this._laneScript()
      + (this._laneError ? ' [lane reported: ' + this._laneError + ']' : '');
  }

  /** Sleeps until `slot` reaches `target`, or until the watchdog expires. Re-reading between the
   *  load and the wait is what makes it race-free: if the value moved in that window the wait
   *  returns at once instead of sleeping on a condition that has already passed.
   *  @returns {boolean} false when the target was not reached in time. */
  _await(slot, target, budgetMs = LANE_WATCHDOG_MS) {
    const deadline = Date.now() + budgetMs;
    let v = Atomics.load(this._ctrl, slot);
    while (v < target) {
      const left = deadline - Date.now();
      if (left <= 0) return false;
      Atomics.wait(this._ctrl, slot, v, left);
      v = Atomics.load(this._ctrl, slot);
    }
    return true;
  }

  /** Shared pair and lanes for a transform of `n` points, built on the first use and whenever the
   *  size changes. The lanes outlive a transform - only a size change or a Threads change rebuilds
   *  them - so a tick pays for no worker startup. */
  _ensure(n) {
    // A parked lane holds the pair it was handed, so a size change cannot reuse the set: the old
    // lanes would go on writing into arrays nothing reads. They are dropped and rebuilt around the
    // new pair - which costs a start, asynchronously, exactly as the first one did.
    if (this._size !== n && this._workers.length > 0) this.terminate();
    if (this._ctrl == null) {
      this._ctrl = new Int32Array(new SharedArrayBuffer(CTRL_LENGTH * Int32Array.BYTES_PER_ELEMENT));
    }
    if (this._size !== n) {
      this._re = new Float64Array(new SharedArrayBuffer(n * BYTES_PER_SAMPLE));
      this._im = new Float64Array(new SharedArrayBuffer(n * BYTES_PER_SAMPLE));
      this._size = n;
    }
    // Lane 0 is the coordinator itself, so the worker count is one short of the lane count. Each
    // lane is STARTED as it is built - it enters its stage loop, counts itself in and parks there
    // until a transform bumps the generation. Nothing here waits for that to happen: a transform
    // that arrives before the count is complete runs serially and the next one asks again.
    while (this._workers.length < this._lanes - 1) {
      const w = this._newLane();
      // A lane that fails to load never reaches the barrier and never counts itself in, so the
      // set simply stays un-announced and every transform is serial. The failure is recorded so
      // that state is reported once, with its cause, rather than silently.
      w.onerror = (ev) => {
        this._laneError = describeErrorEvent(ev);
        console.warn('FFT transform lane failed: ' + this._laneError + this._laneScript());
        this._laneFailed = true;
      };
      this._workers.push(w);
      w.postMessage({
        type: 'transform', ctrl: this._ctrl.buffer,
        re: this._re.buffer, im: this._im.buffer, lane: this._workers.length,
      });
    }
  }
}
