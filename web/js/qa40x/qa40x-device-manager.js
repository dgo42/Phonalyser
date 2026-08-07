/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager - QA40x
// discovery plus the single always-duplex session (doc/QA40X-PROTOCOL.md §10
// "Session model").
//
// Enumeration degrades gracefully: with no device attached, or no WebUSB at all,
// listInputDevices() / listOutputDevices() return empty lists and never throw.
// Each attached QA402/QA403 is ONE duplex Qa40xDeviceRef (isInput() && isOutput()),
// reported identically on both directions.
//
// On first open the manager claims the device (through Qa40xDeviceFinder), reads
// the factory calibration page once, creates-or-refreshes the device card, and
// builds ONE Qa40xDuplexEngine. The lane clients (§3.5 capture source / §3.6
// playback sink) are thin clients that attach/detach lanes on that one engine; only
// a sample-rate change restarts the session, because reg 9 is a single shared clock
// (the app's input/output rates are constrained equal for this backend -
// Qa40xRateConstraint).
//
// WEB DEVIATIONS, all forced by the platform rather than chosen:
//   - ASYNC. The web transport rides WebUSB, whose transfers are promises (the Java
//     ones are blocking bulk transfers), so every entry point that can touch the
//     device returns a promise. Same accommodation as Qa40xCalibration.fromTransport.
//   - Java's `synchronized` -> #serialize(). One JS thread means no data race, but an
//     async section YIELDS: two lane clients acquiring the engine at once would both
//     see a null transport and open (and calibrate, and card-refresh) the device
//     twice. The chain is the monitor's analogue, exactly as the engine's #ioChain is
//     for its register sequences. A promise chain is NOT reentrant like a Java
//     monitor, so the public methods serialize and the private #-prefixed twins run
//     INSIDE the chained section (applyActiveRangeChange -> #setInputRange would
//     otherwise deadlock against the monitor it already holds).
//   - COLLABORATORS ARE INJECTED. Java reads the Preferences singleton for both the
//     preference values and the device-profile store; on the web those are two
//     objects (Preferences + DeviceProfileStore), and both arrive through the
//     constructor so nothing here reaches a global. Same for the range controller:
//     Java arms an eagerly-created singleton whose constructor subscribes, the web
//     holds ONE instance in a field - armed at the same moment, but injected.
//   - The QA40x settings dialog arrives as an OPENER function. Java constructs the
//     SWT dialog in place; a shell dialog must not be constructed inside an audio
//     module, and the seam keeps openCustomPreferences testable.
//   - openCapture() / openPlayback() are absent until the two lane clients land
//     (spec §3.5 / §3.6): they exist in Java only to `new` those clients, and a
//     forward import of a module that does not exist yet would break this one.

import { DeviceChannelMode } from '../store/device-enums.js';
import { AudioDeviceProfile, DeviceEndpointConfig, DeviceRange } from '../store/device-profiles.js';
import { debug } from '../util/debug.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { DeviceFailureReason } from '../audio/device-failure-reason.js';
import { t } from '../i18n/i18n.js';
import { remoteBackendOf } from '../net/net-device-ref.js';
import { MessageType, NetFields } from '../net/net-proto.js';
import { Qa40xCalibration } from './qa40x-calibration.js';
import { Qa40xDeviceFinder, Qa40xModel } from './qa40x-device-finder.js';
import { Qa40xDeviceInfo } from './qa40x-device-info.js';
import { Qa40xDuplexEngine } from './qa40x-duplex-engine.js';
import { inputFullScaleRmsVolts, outputFullScaleRmsVolts } from './qa40x-levels.js';
import { Qa40xPreferences } from './qa40x-preferences.js';
import {
  ANALYZER_BITS,
  REG_CAPABILITY,
  REG_CAPABILITY2,
  REG_FIRMWARE_VERSION,
  REG_INPUT_FS,
  REG_OUTPUT_FS,
  REG_RUN,
  REG_SERIAL_NUMBER,
  REG_TELEM_ISO_CURRENT,
  REG_TELEM_TEMPERATURE,
  REG_TELEM_USB_CURRENT,
  REG_TELEM_USB_VOLTAGE,
  RUN_STOP,
  SAFE_INPUT_DBV,
  SAFE_OUTPUT_DBV,
  formatCapability,
  formatCurrent,
  formatSerialNumber,
  formatTemperature,
  formatUsbVoltage,
  i2sBitDepths,
  inputRangeCode,
  inputRangeDbvValues,
  outputRangeCode,
  outputRangeDbvValues,
  rangeDbv,
  rangeLabel,
  sampleRatesHz,
  verboseInputLabel,
} from './qa40x-protocol.js';
import { Qa40xRangeController } from './qa40x-range-controller.js';
import { QA40X_BACKEND, Qa40xRateConstraint } from './qa40x-rate-constraint.js';

/**
 * Bit depth advertised to the UI: the wire container is 32-bit LE, but only the 24
 * MSBs carry signal (the low byte is zero padding, §5). Unlike the desktop - whose
 * shared capture strides RAW BYTES, so advertised had to equal delivered - the web
 * capture path carries normalised floats and has no byte stride at all; this is the
 * analyzer's real resolution, reported for the UI's benefit. It is the only offered
 * depth except while the front-panel I2S port is on, when the list carries that
 * port's 16 / 32-bit frame widths instead.
 */
const EFFECTIVE_BITS = ANALYZER_BITS;
const CHANNELS = 2;
const BITS_PER_BYTE = 8;

/**
 * Default active ranges when a card is first created - the vendor PyQa40x defaults
 * (input 0 dBV, output +18 dBV), a deliberate pick per doc §9 item 11 (no hardware
 * power-on default; drivers disagree).
 */
const DEFAULT_INPUT_DBV = 0;
const DEFAULT_OUTPUT_DBV = 18;

/** Real settle clock for the engine's ABA rate-write delay (§8); instant in tests. */
const PRODUCTION_SLEEPER = (millis) => new Promise((resolve) => setTimeout(resolve, millis));

/** Java's AudioFormat.Encoding.PCM_SIGNED, as the advertised format's encoding tag. */
const PCM_SIGNED = 'PCM_SIGNED';
/** Audio samples are little-endian (§5) - the AudioFormat bigEndian flag is false. */
const BIG_ENDIAN = false;

/**
 * One advertised format of a QA40x endpoint - the web stand-in for
 * javax.sound.sampled.AudioFormat, carrying exactly the fields Java's constructor
 * is given: signed PCM, `bits` per sample, stereo, little-endian, frame rate equal
 * to the sample rate.
 *
 * @typedef {Object} Qa40xAudioFormat
 * @property {string}  encoding   always PCM_SIGNED
 * @property {number}  sampleRate frames per second
 * @property {number}  bits       bits per sample
 * @property {number}  channels   always 2
 * @property {number}  frameBytes bytes per frame, (bits / 8) · channels
 * @property {number}  frameRate  frames per second (equals sampleRate)
 * @property {boolean} bigEndian  always false (§5)
 */

/**
 * Handle to one attached QA402/QA403 - a single DUPLEX endpoint, so it is reported
 * unchanged on both the input and the output listing. Port of Java's nested
 * Qa40xDeviceRef record; `displayName()` is the sound.DeviceRef interface's default
 * method, which the web has no interface to hold, so it lives on the record.
 */
export class Qa40xDeviceRef {

  /**
   * @param {number} index index within this backend's listing (the CLI `--device i`)
   * @param {string} name  the model name, e.g. `QA403`
   * @param {string} model a Qa40xModel name
   */
  constructor(index, name, model) {
    /** @type {number} */
    this.index = index;
    /** @type {string} */
    this.name = name;
    /** @type {string} */
    this.model = model;
    Object.freeze(this);
  }

  /** @returns {string} the product family, as shown next to the name. */
  description() {
    return 'QuantAsylum QA40x';
  }

  /** @returns {string} the vendor. */
  vendor() {
    return 'QuantAsylum';
  }

  /** @returns {string} the AudioBackendType name this handle belongs to. */
  backend() {
    return QA40X_BACKEND;
  }

  /** @returns {boolean} always true - the analyzer is one duplex device. */
  isInput() {
    return true;
  }

  /** @returns {boolean} always true - the analyzer is one duplex device. */
  isOutput() {
    return true;
  }

  /** @returns {string} `[index] name (description) - vendor`. */
  displayName() {
    return `[${this.index}] ${this.name} (${this.description()}) - ${this.vendor()}`;
  }

  /** @returns {string} the display name. */
  toString() {
    return this.displayName();
  }
}

export class Qa40xDeviceManager {

  /** @type {Qa40xDeviceFinder} */
  #finder;
  /** @type {import('./qa40x-duplex-engine.js').Sleeper} */
  #sleeper;
  /** The live Preferences - the active backend and the output bit depth. */
  #prefs;
  /** The live DeviceProfileStore - where the device card is created / refreshed. */
  #deviceStore;
  /** @type {?function(*, Qa40xDeviceInfo, boolean): (Promise<boolean>|boolean)} */
  #openSettingsDialog;
  /** This backend's own settings block (`custom.qa40x` in the persisted document). */
  #settings;
  /**
   * The DEVICE_ACTIVE_RANGE_CHANGED bridge. Held in a field rather than discarded
   * because the field IS the ownership: constructing it subscribes it to the bus,
   * and the manager is what keeps it alive for as long as the backend exists.
   * @type {?Qa40xRangeController}
   */
  #rangeController = null;
  /**
   * The remote-backend request seam (NetDeviceManager's call/callLocked), or null in a build with
   * no net client - how the QA40x on a SERVER is read and written (spec 4.6).
   * @type {?Object}
   */
  #bench = null;
  /** The remote selection whose I2S port the operator changed, or null when nothing is pending -
   *  the port belongs to the BENCH, so it is a pending WRITE and never a preference block. */
  #pendingBench = null;
  /** That pending port state. @type {boolean} */
  #pendingI2s = false;

  /** @type {?Object} the claimed transport; null until the device is opened. */
  #transport = null;
  /** @type {?Qa40xCalibration} read once at open. */
  #calibration = null;
  /** @type {?string} a Qa40xModel name; null until the device is opened. */
  #model = null;
  /** @type {?Qa40xDuplexEngine} the one duplex session. */
  #engine = null;
  /** 0 = engine not yet built. */
  #currentRateHz = 0;
  #inputRangeDbv = DEFAULT_INPUT_DBV;
  #outputRangeDbv = DEFAULT_OUTPUT_DBV;

  /**
   * Java's `synchronized`: the tail of the serialized chain. Open / re-range /
   * telemetry / shutdown sequences run one after another, never interleaved.
   * @type {Promise<void>}
   */
  #ioChain = Promise.resolve();

  /**
   * @param {Object} deps
   * @param {Object} deps.prefs the live Preferences - `backend` (the active
   *        AudioBackendType) and `current().outputBitDepth` (the I2S frame width),
   *        plus the registry this backend's settings block registers with.
   * @param {Object} deps.deviceStore the live DeviceProfileStore - the device card
   *        is created / refreshed and persisted through it.
   * @param {Qa40xDeviceFinder} [deps.finder] the discovery seam. Defaults to a fresh
   *        finder over navigator.usb (Java's production constructor does the same),
   *        but the shell should pass the SAME instance its Scan click drives, since
   *        the WebUSB chooser needs that click's user activation.
   * @param {import('./qa40x-duplex-engine.js').Sleeper} [deps.sleeper] settle clock
   * @param {Qa40xPreferences} [deps.settings] this backend's settings block; Java
   *        owns it as a field, and injecting it lets a test drive the I2S toggle.
   * @param {?function(*, Qa40xDeviceInfo, boolean): (Promise<boolean>|boolean)}
   *        [deps.openSettingsDialog] opens the QA40x settings dialog over `parent`
   *        with the freshly read device info, seeded with the pending I2S state, and
   *        answers the state the user accepted (the seed itself on Cancel).
   * @param {?Object} [deps.bench] the remote-backend request seam (NetDeviceManager's
   *        call/callLocked): the QA40x on a SERVER is re-ranged over spec 4.6 by the same range
   *        controller this constructor arms, so the seam enters here and is forwarded to it.
   * @param {boolean} [deps.armBus] false for Java's bus-free test seam: no range
   *        controller and no rate constraint, so the class runs on any CI agent.
   */
  constructor({ prefs, deviceStore, finder = new Qa40xDeviceFinder(), sleeper = PRODUCTION_SLEEPER,
    settings = new Qa40xPreferences(), openSettingsDialog = null, bench = null, armBus = true } = {}) {
    this.#prefs = prefs;
    this.#deviceStore = deviceStore;
    this.#finder = finder;
    this.#sleeper = sleeper;
    this.#settings = settings;
    this.#openSettingsDialog = openSettingsDialog;
    this.#bench = bench;
    if (armBus) {
      // Arm the range bridge together with the machinery it serves: the manager is
      // built on the first QA40x dispatch (device enumeration included), so the bus
      // subscription is live before any Preferences OK can publish a range change.
      // The bench seam travels with it: the same event re-ranges an analyzer on a SERVER over
      // spec 4.6, and the controller is where that decision already lives (Java reaches the
      // RemoteBackendRegistry global there; the web injects).
      this.#rangeController = new Qa40xRangeController(this, bench);
      // Same for the equal-rate constraint (one shared reg-9 clock, §10): its
      // subscription must be live before the Preferences dialog can move a rate combo.
      Qa40xRateConstraint.instance();
    }
    // Hand this backend's own settings block to Preferences, which replays whatever
    // the document held for it - the manager is built lazily, long after load() ran,
    // so registration is where the saved values arrive.
    prefs.registerCustomPreferences(this.#settings);
    // Watch for the analyzer being UNPLUGGED. This is the ONLY signal a dead QA40x gives: WebUSB
    // has no per-transfer timeout, so mid-stream removal just means transfers stop completing -
    // the app went on showing a live scope with cap/s ticking and no error at all.
    // Not gated on armBus: the watch itself is passive, and a fake finder without
    // watchDisconnect (every existing test) is simply never armed.
    if (typeof this.#finder.watchDisconnect === 'function') {
      this.#finder.watchDisconnect(() => this.#onDeviceDisconnected());
    }
  }

  /**
   * The armed analyzer was unplugged. Surface it exactly as a dead Web Audio input is surfaced -
   * AUDIO_DEVICE_ERROR, direction 'input' - so the same shell alert appears and the same pane
   * subscribers stop the scope and the FFT; then clean up the device side. shutdown() detaches the
   * lanes (stopping the engine's pump), attempts the safe-state park - harmless against a gone
   * device, every write failure is swallowed - and closes the dead transport, so a REPLUG starts
   * from a clean slate: the next Scan or capture re-opens and re-reads calibration as a first open.
   *
   * A disconnect with nothing open is ignored: unplugging an idle, already-released analyzer is
   * routine, not an error.
   *
   * The reason is DEVICE_DISCONNECTED, not a sentence: this manager owns the analyzer's native
   * vocabulary and classifies it here, exactly as the Web Audio lanes classify their
   * DOMExceptions - so the shell renders one localized message and the raw detail stays in the
   * log (the AUDIO_DEVICE_ERROR contract, events.js).
   *
   * @returns {Promise<void>}
   */
  async #onDeviceDisconnected() {
    if (this.#transport == null) {
      return;
    }
    console.error('QA40x: the analyzer was disconnected');
    const reason = DeviceFailureReason.DEVICE_DISCONNECTED;
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR,
      { direction: 'input', reason: reason.name, message: t(reason.i18nKey) });
    await this.shutdown();
  }

  // --- enumeration ---------------------------------------------------------

  /** @returns {Promise<Qa40xDeviceRef[]>} the attached analyzers, as capture handles. */
  async listInputDevices() {
    return this.#listDevices();
  }

  /** @returns {Promise<Qa40xDeviceRef[]>} the same handles, as playback handles. */
  async listOutputDevices() {
    return this.#listDevices();
  }

  /**
   * One duplex handle per attached QA402/QA403; empty when none is attached or the
   * browser has no WebUSB (the finder never throws).
   * @returns {Promise<Qa40xDeviceRef[]>}
   */
  async #listDevices() {
    const out = [];
    let index = 0;
    for (const device of await this.#finder.list()) {
      // Java passes model.name() as the name and the enum as the model; the web
      // model IS its name, so both arguments are that one string.
      out.push(new Qa40xDeviceRef(index++, device.model, device.model));
    }
    return out;
  }

  /**
   * True - the analyzer has a real, selectable sample width, so Preferences shows the depth combos
   * and commits what is picked: the capture path is 24-bit and the front-panel I2S port runs 16 or
   * 32, which reg 0x0B is written from at each session start.
   *
   * The counterpart is Web Audio, which answers false (no manager at all today): its samples are
   * float32 taken post-mixer, so there is no depth to choose - and offering one would advertise a
   * control the platform can neither honour nor report (doc/htmls/audio-devices.html §4 measures
   * this). A backend added later declares its own answer here rather than the dialog guessing.
   *
   * @returns {boolean}
   */
  hasBitDepth() {
    return true;
  }

  /**
   * Opens the analyzer far enough to read its factory calibration page and write the
   * device card, WITHOUT starting a stream - the Preferences ▸ Scan path.
   *
   * Why this exists as its own entry point: #ensureOpen() is otherwise reached only
   * through #acquireEngine(), i.e. the first capture or playback. That is too late
   * for the dialog, which needs the card the moment the analyzer is picked: until it
   * exists the Ranges table and the full-scale readouts fall back to whatever card the
   * PREVIOUS device resolved to, so the user is shown another interface's ranges and
   * no device-derived levels at all. Java gets away
   * with the lazy open because its dialog re-enumerates on every change; the web must
   * not enumerate there, so the card is built here instead.
   *
   * Idempotent and cheap on a second call - #ensureOpen() returns immediately once the
   * transport is claimed, and a live session is left untouched.
   *
   * HANDS THE DEVICE BACK when it opened one and no session is running. WebUSB claims interface 0
   * EXCLUSIVELY, so a scan that left the claim standing locked the analyzer out of QuantAsylum's
   * own software and every other tab for the life of the page - even after the user pressed Cancel,
   * because the Cancel path rolls the backend back but parks nothing (verified: after Cancel the
   * backend read WEB_AUDIO while the manager still reported cardName() = QA403 with the interface
   * still claimed). Releasing here rather than in the dialog fixes every entry point at once - the
   * backend switch, the Scan click and a cancelled dialog alike - and matches the rule the session
   * teardown already follows: hold the analyzer only while something is actually using it.
   *
   * A LIVE session is never released: #engine is non-null exactly while lanes are attached, and
   * that session's own last detach calls #releaseAfterSession().
   *
   * @returns {Promise<void>}
   */
  async ensureDeviceCard() {
    await this.#serialize(async () => {
      await this.#ensureOpen();
      await this.#releaseIfIdle();
    });
  }

  /**
   * Hands the analyzer back if NOTHING is using it. The one test is `#engine == null`: the engine
   * exists exactly while a session does, so a null engine means no lane is attached and no stream
   * is running, and holding interface 0 then serves nobody - WebUSB claims it exclusively, so an
   * idle claim locks the analyzer out of QuantAsylum's own software and every other tab.
   *
   * Deliberately NOT conditioned on "did I open it just now": an earlier entry point may already
   * have left the device claimed with no session (that is precisely the bug this closes), so any
   * idle moment is a chance to clean up rather than to preserve a leak.
   *
   * @returns {Promise<void>}
   */
  async #releaseIfIdle() {
    if (this.#transport == null) {
      return;
    }
    // An engine OBJECT is not a session: acquireEngine() builds it before the first attach, so a
    // lane client whose start() threw between open() and attach leaves a non-null engine with
    // nothing running. Ask the engine whether anything is actually using the analyzer.
    if (this.#engine != null && this.#engine.inUse) {
      return;
    }
    await this.#releaseAfterSession();
  }

  /**
   * The public form, for a lane client closing down: if its open() succeeded but its start() never
   * did, it has NO lane to detach, so the engine's last-detach release can never fire and nothing
   * else would hand the device back. Both lane clients call this from close(); it is a no-op while
   * a session holds the engine, so a normal stop still releases through the engine as before.
   *
   * @returns {Promise<void>}
   */
  releaseIfIdle() {
    return this.#serialize(() => this.#releaseIfIdle());
  }

  /**
   * The handle at `index` in this backend's listing (the CLI `--device i`).
   * `isOutput` is ignored - the analyzer is one duplex device, so both directions
   * enumerate identically - and is kept for signature parity with the other backends.
   *
   * @param {number} index
   * @param {boolean} isOutput
   * @returns {Promise<Qa40xDeviceRef>}
   */
  async getDeviceByIndex(index, isOutput) {
    const all = await this.#listDevices();
    if (index < 0 || index >= all.length) {
      throw new Error(`QA40x device index out of range: ${index}`
        + ` (have ${all.length} device${all.length === 1 ? '' : 's'})`);
    }
    return all[index];
  }

  /**
   * The QA402/QA403 format set - signed PCM, stereo, little-endian, identical for
   * both directions. The rate list follows the model: 48/96/192 kHz on both, plus
   * 384 kHz on the QA403 (reg-9 code 3, which the QA402 lacks).
   *
   * @param {Qa40xDeviceRef} device
   * @param {boolean} output true for the playback direction
   * @returns {Qa40xAudioFormat[]} empty for a handle from another backend
   */
  listSupportedFormats(device, output) {
    if (!(device instanceof Qa40xDeviceRef)) {
      return [];
    }
    // OUTPUT only: while the front-panel I2S port is on, the output depth IS that
    // port's frame width (16 or 32) and is what reg 0x0B is written from at session
    // start. The INPUT is the analyzer's own capture path and always delivers 24 -
    // the I2S port is an output and cannot change it. Read from the EDIT value so
    // the depth choices react to the I2S toggle while the Preferences dialog is
    // still open; outside a dialog session edit and live are equal.
    const depths = (output && this.#settings.i2sEnabledEdit())
      ? i2sBitDepths()
      : [EFFECTIVE_BITS];
    const formats = [];
    for (const rate of sampleRatesHz(device.model)) {
      for (const bits of depths) {
        formats.push(Object.freeze({
          encoding: PCM_SIGNED,
          sampleRate: rate,
          bits,
          channels: CHANNELS,
          frameBytes: (bits / BITS_PER_BYTE) * CHANNELS,
          frameRate: rate,
          bigEndian: BIG_ENDIAN,
        }));
      }
    }
    return formats;
  }

  // --- session open + lane clients -----------------------------------------

  /**
   * The one duplex engine, built on first use (opening the device, reading
   * calibration and refreshing the card) and restarted if the shared sample rate
   * changed. The lane clients acquire the engine here, then attach / detach.
   *
   * @param {number} sampleRateHz
   * @returns {Promise<Qa40xDuplexEngine>}
   */
  acquireEngine(sampleRateHz) {
    return this.#serialize(() => this.#acquireEngine(sampleRateHz));
  }

  async #acquireEngine(sampleRateHz) {
    await this.#ensureOpen();
    if (this.#engine == null) {
      // The engine reads both suppliers at each session boundary, so a Preferences
      // OK between sessions needs no push down to it. The I2S frame width IS the
      // output bit depth from Preferences ▸ Audio.
      this.#engine = new Qa40xDuplexEngine(this.#transport, this.#sleeper,
        this.#inputRangeDbv, this.#outputRangeDbv, sampleRateHz,
        () => this.#settings.i2sEnabled(),
        () => this.#prefs.current().outputBitDepth,
        () => this.#releaseAfterSession());
      this.#currentRateHz = sampleRateHz;
    } else if (this.#currentRateHz !== sampleRateHz) {
      await this.#engine.changeSampleRate(sampleRateHz);   // one shared clock -> restart (§10)
      this.#currentRateHz = sampleRateHz;
    }
    return this.#engine;
  }

  /**
   * Hands the analyzer back once the last lane has detached and the engine has parked it - the
   * engine's onSessionEnd hook.
   *
   * WEB-ONLY, and deliberately unlike the Java: WebUSB claims interface 0 EXCLUSIVELY, so holding
   * the claim while idle makes the analyzer unavailable to the vendor software and to any other tab
   * even though no module is using it. libusb on the desktop is
   * claimed to app exit and nobody notices. The engine is dropped with the transport because it
   * holds that transport: the next acquireEngine() re-opens the device, re-reads calibration and
   * rebuilds it, which is the same path a first open takes.
   *
   * NOT routed through #serialize: it runs INSIDE the engine's own serialized stop, and the manager
   * chain may be occupied by the very call that is stopping - going through it would deadlock.
   *
   * @returns {Promise<void>}
   */
  async #releaseAfterSession() {
    const transport = this.#transport;
    this.#engine = null;
    this.#transport = null;
    this.#calibration = null;
    this.#currentRateHz = 0;
    if (transport == null) {
      return;
    }
    try {
      await transport.close();
      debug('[qa40x] session ended - device released');
    } catch (error) {
      // An unplugged analyzer throws here; the claim is gone either way, which is the point.
      debug(`[qa40x] release after session failed: ${error.message}`);
    }
  }

  /**
   * Live input full-scale range change; restarts the running session (§10).
   * @param {number} dbv one of inputRangeDbvValues()
   * @returns {Promise<void>}
   */
  setInputRange(dbv) {
    return this.#serialize(() => this.#setInputRange(dbv));
  }

  async #setInputRange(dbv) {
    inputRangeCode(dbv);   // validate
    this.#inputRangeDbv = dbv;
    if (this.#engine != null) {
      await this.#engine.changeInputRange(dbv);
    }
  }

  /**
   * Live output full-scale range change; restarts the running session (§10).
   * @param {number} dbv one of outputRangeDbvValues()
   * @returns {Promise<void>}
   */
  setOutputRange(dbv) {
    return this.#serialize(() => this.#setOutputRange(dbv));
  }

  async #setOutputRange(dbv) {
    outputRangeCode(dbv);   // validate
    this.#outputRangeDbv = dbv;
    if (this.#engine != null) {
      await this.#engine.changeOutputRange(dbv);
    }
  }

  /**
   * Routes a Preferences-committed active-range change to the device - the sink for
   * the DEVICE_ACTIVE_RANGE_CHANGED bus event. A no-op unless `activeBackend` is
   * QA40X and `cardName` is THIS open device's card; otherwise it defers to
   * setInputRange / setOutputRange, which restart a running session or store the
   * range for the next open when idle. Both `activeBackend` and `cardName` arrive
   * from the caller so the whole decision is one testable step.
   *
   * @param {string} activeBackend the AudioBackendType name currently in force
   * @param {?string} cardName the card the change describes
   * @param {boolean} input true for the capture direction
   * @param {number} dbv the newly active range, in dBV
   * @returns {Promise<void>}
   */
  applyActiveRangeChange(activeBackend, cardName, input, dbv) {
    return this.#serialize(() => {
      if (activeBackend !== QA40X_BACKEND || this.#model == null || this.#model !== cardName) {
        return undefined;
      }
      return input ? this.#setInputRange(dbv) : this.#setOutputRange(dbv);
    });
  }

  /**
   * Logical name of this open device's card (the model name, e.g. `QA403`), or null
   * before the device is opened - the card the range routing targets. The web model
   * IS its name, so there is nothing to unwrap.
   * @returns {?string}
   */
  cardName() {
    return this.#model;
  }

  /**
   * The AudioBackendType name currently in force. WEB SEAM: Java's range controller
   * reads AudioBackend.instance().active() itself; the web controller asks its
   * manager, so nothing reaches through a global.
   * @returns {string}
   */
  activeBackend() {
    return this.#prefs.backend.get();
  }

  /** The QA40x has settings no other backend shares (the front-panel I2S expansion
   *  port), so the Preferences dialog offers a button for them.
   *  @returns {boolean} */
  hasCustomPreferences() {
    return true;
  }

  /**
   * Reads the identity + telemetry registers and decodes them for display (§4 / §6),
   * opening the device first if no session has done so yet. Returns
   * Qa40xDeviceInfo.NONE when nothing is attached, and degrades the same way if a
   * read fails part-way: this feeds a read-only panel, so an absent or wedged
   * analyzer must show dashes rather than break the dialog it is on. The ISO-supply
   * current exists on the QA402 only; a QA403 reports it as unavailable.
   *
   * @returns {Promise<Qa40xDeviceInfo>}
   */
  readDeviceInfo() {
    return this.#serialize(() => this.#readDeviceInfo());
  }

  async #readDeviceInfo() {
    try {
      // Opening the device is what makes the registers readable at all: the session
      // opens lazily on the first capture, so without this the panel would only ever
      // show values after a measurement had run. Same open the session uses -
      // idempotent when one is already live.
      await this.#ensureOpen();
      const hasIso = this.#model === Qa40xModel.QA402;
      const transport = this.#transport;
      // Each reply is awaited in turn, so the reads keep Java's argument-evaluation
      // ORDER; a decoder applied to the wrong register's word yields a plausible
      // number, not a failure.
      const firmwareVersion = String(await transport.registerRead(REG_FIRMWARE_VERSION));
      const usbVoltage = formatUsbVoltage(await transport.registerRead(REG_TELEM_USB_VOLTAGE));
      const usbCurrent = formatCurrent(await transport.registerRead(REG_TELEM_USB_CURRENT));
      const isoCurrent = hasIso
        ? formatCurrent(await transport.registerRead(REG_TELEM_ISO_CURRENT))
        : Qa40xDeviceInfo.UNAVAILABLE;
      const temperature = formatTemperature(await transport.registerRead(REG_TELEM_TEMPERATURE));
      const capability = formatCapability(await transport.registerRead(REG_CAPABILITY));
      const capability2 = formatCapability(await transport.registerRead(REG_CAPABILITY2));
      const serialNumber = formatSerialNumber(await transport.registerRead(REG_SERIAL_NUMBER));
      return new Qa40xDeviceInfo(firmwareVersion, usbVoltage, usbCurrent, isoCurrent,
        temperature, capability, capability2, serialNumber);
    } catch (error) {
      console.warn(`QA40x device info read failed: ${error}`);
      return Qa40xDeviceInfo.NONE;
    } finally {
      // Hand the analyzer back. This panel is READ-ONLY telemetry: opening Preferences ▸ QA40x
      // Settings must not cost the user their analyzer for the life of the page. Without this the
      // dialog was the ONE open path with no pairing release at all - the observed symptom, on the
      // plain happy path. In `finally`, so a read that fails part-way
      // releases too; #releaseIfIdle is a no-op while a session holds the engine.
      await this.#releaseIfIdle();
    }
  }

  /**
   * Opens the QA40x settings dialog. What the user accepts stays PENDING - it
   * reaches the live settings (and the document) only when the Preferences dialog
   * itself is closed with OK, so its Cancel discards this too. A no-op when no
   * dialog opener was injected (nothing to open, and the pending value must not
   * change behind the user's back).
   *
   * @param {*} parent the dialog's parent (a Java Shell; the shell's host element here)
   * @returns {Promise<void>}
   */
  async openCustomPreferences(parent) {
    if (this.#openSettingsDialog == null) {
      return;
    }
    const selection = this.#prefs.backend.get();
    if (remoteBackendOf(selection) === QA40X_BACKEND) {
      await this.#openRemoteSettings(parent, selection);
      return;
    }
    const info = await this.readDeviceInfo();
    this.#settings.setI2sEnabledEdit(
      await this.#openSettingsDialog(parent, info, this.#settings.i2sEnabledEdit()));
  }

  /**
   * The SAME dialog, filled from the bench across the network (spec 4.6, faithful port of
   * Qa40xSettingsUi.openRemote): its port state from qa40x.settings and its telemetry from
   * qa40x.info, both lock-free reads.
   *
   * A server that will not answer costs the READ, not the panel: the fields then show what an
   * unread register shows anyway (Qa40xDeviceInfo.UNAVAILABLE) - exactly what a locally attached
   * analyzer that could not be read does.
   *
   * The accepted state does NOT go into this backend's preference block: the port belongs to the
   * bench, which stores it, so it is held as a pending WRITE instead and sent on the Preferences
   * OK ({@link #commitCustomPreferencesEdit}). Only a real CHANGE becomes one - the dialog answers
   * the seed when it is cancelled, and scheduling a write of the value the bench already has would
   * take the analyzer's lock for nothing.
   */
  async #openRemoteSettings(parent, selection) {
    const bench = this.#bench;
    if (bench == null) return;
    const settings = await bench.call(selection, MessageType.QA40X_SETTINGS);
    const seed = (this.#pendingBench === selection)
      ? this.#pendingI2s : (settings != null && settings[NetFields.I2S_ENABLED] === true);
    const accepted = !!await this.#openSettingsDialog(parent, await this.#remoteInfo(selection), seed);
    if (accepted !== seed) {
      this.#pendingBench = selection;
      this.#pendingI2s = accepted;
    }
  }

  /** The remote analyzer's identity and telemetry as spec 4.6 formats them - strings the SERVER
   *  already decoded, so nothing is re-interpreted here. A bench that did not answer, or a field a
   *  newer server knows and this build does not, reads as UNAVAILABLE rather than a blank row. */
  async #remoteInfo(selection) {
    const info = await this.#bench.call(selection, MessageType.QA40X_INFO);
    if (info == null) return Qa40xDeviceInfo.NONE;
    const text = (field) => (info[field] == null ? Qa40xDeviceInfo.UNAVAILABLE : String(info[field]));
    return new Qa40xDeviceInfo(text(NetFields.FIRMWARE_VERSION), text(NetFields.USB_VOLTAGE),
      text(NetFields.USB_CURRENT), text(NetFields.ISO_CURRENT), text(NetFields.TEMPERATURE),
      text(NetFields.CAPABILITY), text(NetFields.CAPABILITY2), text(NetFields.SERIAL_NUMBER));
  }

  /**
   * The bench's analyzer just became the selection - the moment its calibration reaches this
   * client (faithful port of Qa40xSettingsUi.onSelected). qa40x.calibration and qa40x.ranges are
   * read (both lock-free) and rendered into the SAME calibrationFromDevice card the local backend
   * builds from the USB cal page, so everything downstream - generator full scale, the dBV axis,
   * the ranges table - resolves it by the device's NAME and needs no remote special case.
   *
   * The card's ACTIVE ranges are what the bench reports IN FORCE, not what the stored card said:
   * the analyzer is already running under them, and a card claiming otherwise would re-create
   * exactly the display lie this sync exists to end.
   *
   * A bench that will not answer costs the sync, not the dialog - the card, and with it the
   * full-scale math, simply stays as it was.
   *
   * RECORDED DIVERGENCE (web/.java-sync-baseline: "Card/range edits apply LIVE (no detached
   * staging)"). Java writes the card into the Preferences dialog's WORKING COPY, because its
   * staged dialog cannot show a live write until it is reopened; the web's card section renders
   * from the LIVE store and refreshes on its events, so the reason evaporates and the card is
   * written live - no rollback on Cancel, consistent with every other web card operation.
   *
   * @param {string} selection the selected "net:QA40X" backend
   * @returns {Promise<?Object>} the card written, or null when the bench did not answer
   */
  async syncRemoteCard(selection) {
    const bench = this.#bench;
    if (bench == null || this.#deviceStore == null) return null;
    const cal = await bench.call(selection, MessageType.QA40X_CALIBRATION);
    const ranges = await bench.call(selection, MessageType.QA40X_RANGES);
    const cardName = typeof bench.deviceName === 'function' ? bench.deviceName() : null;
    const activeIn = ranges == null ? null : ranges[NetFields.ACTIVE_INPUT_DBV];
    const activeOut = ranges == null ? null : ranges[NetFields.ACTIVE_OUTPUT_DBV];
    if (cal == null || cardName == null
        || typeof activeIn !== 'number' || typeof activeOut !== 'number') {
      console.warn(`QA40x card sync: ${selection} did not answer calibration/ranges - the `
        + 'full-scale card stays as stored');
      return null;
    }
    const store = this.#deviceStore;
    const factors = Qa40xCalibration.fromFactors(cal[NetFields.ADC], cal[NetFields.DAC]);
    const card = this.buildProfile(cardName, factors, store.findAudioDeviceProfile(cardName));
    card.input.activeRange = rangeLabel(activeIn);
    card.output.activeRange = rangeLabel(activeOut);
    // TRANSIENT, never stored: a remote QA40x card has no business in devices storage. It is
    // another machine's device, and persisting it is unnecessary anyway - the dialog re-syncs
    // on every backend settle / scan, so a reload re-renders it.
    // The overlay also keeps a LOCAL card of the same name intact behind it.
    store.putTransientProfile(card);
    console.info(`QA40x card sync: '${cardName}' calibrated from the bench, `
      + `in ${activeIn} dBV / out ${activeOut} dBV`);
    return card;
  }

  /**
   * The LOCAL analyzer just became the selection: read its cal page and build its card NOW,
   * instead of waiting for the first capture to open the device - selecting a local QA40x has
   * to produce its ranges and its card there and then.
   *
   * With an empty store the dialog had nothing to draw: the ranges block renders only from a
   * device-provided card (card-section rangeCard), and that card was created by
   * {@link #ensureOpen}'s refresh, which nothing before a user-started capture reaches. The
   * settle tail now asks for it, exactly as the bench one does.
   *
   * The card IS persisted, unlike a transient bench card: this is THIS installation's own
   * device, which is what devices storage is for - desktop parity, where the local QA40x's
   * card is written to devices.yaml at open.
   *
   * FAIL SOFT, like the remote sync: WebUSB may have no permission yet, the analyzer may be
   * unplugged, and another tab may hold the interface. Any of those costs the sync, not the
   * dialog - the card simply stays as it was and the operator sees the selection they made.
   *
   * @returns {Promise<?AudioDeviceProfile>} the card, or null when the device did not open
   */
  async syncLocalCard() {
    if (this.#deviceStore == null) return null;
    try {
      // The OPEN path's own read + build + persist (#refreshDeviceCard), not a second builder:
      // one place derives a QA40x card from a cal page, whichever moment asks for it.
      await this.#serialize(() => this.#ensureOpen());
    } catch (e) {
      console.warn(`QA40x card sync: the local analyzer did not open - the full-scale card `
        + `stays as stored (${e && e.message})`);
      return null;
    }
    return this.#model == null ? null : this.#deviceStore.findAudioDeviceProfile(this.#model);
  }

  /** A new Preferences session: whatever a previous one left pending was discarded with its Cancel
   *  and must not reach the bench now (Java Qa40xSettingsUi.beginEdit). */
  beginCustomPreferencesEdit() {
    this.#pendingBench = null;
  }

  /**
   * The operator pressed OK - the one moment a remote analyzer may be told to switch its
   * front-panel port (Java Qa40xSettingsUi.commitEdit).
   *
   * It goes through the LOCKED seam because the port is hardware: spec 4.6 requires the analyzer's
   * lock for a write, and a settings panel holds nothing of its own, so an ordinary call was
   * answered NOT_LOCKED and the port never moved. Whether the lock has to be TAKEN - the modules
   * may well be streaming from this bench while the dialog is up - is the seam's business, not
   * this one's.
   */
  async commitCustomPreferencesEdit() {
    const selection = this.#pendingBench;
    this.#pendingBench = null;
    if (selection == null || this.#bench == null) return;
    const answer = await this.#bench.callLocked(selection, MessageType.QA40X_SETTINGS,
      { [NetFields.I2S_ENABLED]: this.#pendingI2s });
    if (answer == null) {
      console.warn(`QA40x settings: ${selection} did not accept the I2S port change`);
    }
  }

  /**
   * App-exit teardown: leaves the analyzer in its protected idle state (§7 Teardown
   * steps 8-9) - stream stopped, input +42 dBV (maximum attenuation), output
   * −12 dBV - then releases the transport. Without this the device kept whatever
   * range the last measurement used: a 0 dBV session left the input at maximum
   * sensitivity, unprotected, after the app quit. No-op when the device was never
   * opened. NEVER throws: the exit path must not be blocked by an unplugged or
   * wedged device.
   *
   * @returns {Promise<void>}
   */
  shutdown() {
    return this.#serialize(() => this.#shutdown());
  }

  async #shutdown() {
    const transport = this.#transport;
    if (transport == null) {
      return;
    }
    // STOP THE SESSION FIRST, here rather than in the caller. The backend-switch path stops the
    // lanes before calling this, but the pagehide / beforeunload hook does not - and closing the
    // transport under a still-`streaming` engine left its completion pump submitting transfers to a
    // dead handle for the rest of the teardown. Owning the order here makes every caller correct and
    // is idempotent for the one that already stopped: the engine simply has nothing left to do.
    const engine = this.#engine;
    if (engine != null) {
      try {
        await engine.detachCapture();
        await engine.detachGenerator();
      } catch (error) {
        // A wedged or unplugged device must not block the exit path; the park below still runs.
        debug(`[qa40x] shutdown: stopping the session failed: ${error.message}`);
      }
      // The LAST detach parks the analyzer and hands it back through #releaseAfterSession, which
      // nulls the transport. Everything below would then write to a closed handle and close it a
      // second time - so if the session already released it, the work is done.
      if (this.#transport == null) {
        return;
      }
    }
    try {
      await transport.registerWrite(REG_RUN, RUN_STOP);
      await transport.registerWrite(REG_INPUT_FS, inputRangeCode(SAFE_INPUT_DBV));
      await transport.registerWrite(REG_OUTPUT_FS, outputRangeCode(SAFE_OUTPUT_DBV));
      debug(`[qa40x] safe-state close: input +${SAFE_INPUT_DBV} dBV (attenuator engaged),`
        + ` output ${SAFE_OUTPUT_DBV} dBV`);
    } catch (error) {
      console.warn(`QA40x safe-state write failed (device unplugged?): ${error}`);
    }
    try {
      await transport.close();
    } catch (error) {
      console.warn(`QA40x transport close failed: ${error}`);
    }
    this.#transport = null;
    this.#calibration = null;
    this.#model = null;
    this.#engine = null;
    this.#currentRateHz = 0;
  }

  // --- register sequences (Java: under `synchronized`) ----------------------

  /**
   * Runs `action` after every previously queued sequence, so two callers can never
   * interleave device traffic. A failed sequence must NOT wedge the chain - the
   * rejection is reported to ITS caller and swallowed on the tail, because "no
   * device attached" is routine here and the next open has to retry.
   */
  #serialize(action) {
    const run = this.#ioChain.then(action);
    this.#ioChain = run.then(() => {}, () => {});
    return run;
  }

  async #ensureOpen() {
    if (this.#transport != null) {
      return;                          // already open (or injected for tests)
    }
    const devices = await this.#finder.list();
    if (devices.length === 0) {
      throw new Error('No QA402/QA403 attached (or WebUSB unavailable)');
    }
    this.#model = devices[0].model;
    this.#transport = await this.#finder.open();   // claims interface 0; enforces the single-device rule
    this.#calibration = await Qa40xCalibration.fromTransport(this.#transport);
    this.#refreshDeviceCard();
    debug(`[qa40x] session open: ${this.#model}`
      + ` (input ${this.#inputRangeDbv} dBV, output ${this.#outputRangeDbv} dBV)`);
  }

  // --- device card (§10 dBV/cal plumbing; device-profile policy) ------------

  /**
   * Creates-or-refreshes this device's card in the store from the freshly read
   * calibration page, preserving the user's active-range selections (the
   * profile-policy survival rules), and PERSISTS it. Both endpoints are marked
   * calibrationFromDevice so calibrate flows refuse with a warning and the seed
   * merge takes the values wholesale.
   *
   * Runs once per session - #ensureOpen() opens the device (and calls this) only on
   * the first acquireEngine, i.e. a user-started capture / playback, so the app is
   * already running.
   */
  #refreshDeviceCard() {
    if (this.#calibration == null || this.#model == null) {
      return;
    }
    const cardName = this.#model;
    const store = this.#deviceStore;
    const card = this.buildProfile(cardName, this.#calibration, store.findAudioDeviceProfile(cardName));
    store.putAudioDeviceProfile(card);
    // Java persists explicitly here as well (its put already saves, as the web's
    // does); the write is a no-op on a transient store, so a headless run or a test
    // never records the device's calibration.
    store.saveDevices();
    this.#inputRangeDbv = rangeDbv(card.input.activeRange, inputRangeDbvValues(), DEFAULT_INPUT_DBV);
    this.#outputRangeDbv = rangeDbv(card.output.activeRange, outputRangeDbvValues(), DEFAULT_OUTPUT_DBV);
  }

  /**
   * Builds the QA40x device profile: a LINKED stereo endpoint per direction, one
   * range row per attenuator/gain position with per-channel full-scale RMS volts
   * derived from Qa40xLevels and the device's own cal-page factors, both endpoints
   * calibrationFromDevice. When an `existing` card is present its match aliases and
   * active-range selections survive the refresh. Package-private in Java - public
   * here so it can be driven as the pure function it is.
   *
   * @param {string} cardName the logical card name (the model name)
   * @param {Qa40xCalibration} cal the freshly read cal page
   * @param {?AudioDeviceProfile} existing the card already in the store, or null
   * @returns {AudioDeviceProfile}
   */
  buildProfile(cardName, cal, existing) {
    const card = new AudioDeviceProfile();
    card.name = cardName;
    card.match.push(cardName);
    if (existing != null) {
      for (const entry of existing.match) {
        if (!card.match.includes(entry)) {
          card.match.push(entry);
        }
      }
    }
    card.input = this.#buildEndpoint(true, cal, existing != null ? existing.input : null);
    card.output = this.#buildEndpoint(false, cal, existing != null ? existing.output : null);
    return card;
  }

  #buildEndpoint(input, cal, existing) {
    const ep = new DeviceEndpointConfig();
    ep.channels = DeviceChannelMode.LINKED;
    ep.calibrationFromDevice = true;
    const dbvValues = input ? inputRangeDbvValues() : outputRangeDbvValues();
    for (const dbv of dbvValues) {
      const row = new DeviceRange();
      row.label = rangeLabel(dbv);   // plain "N dBV" - the persisted key
      if (input) {
        // The input "N dBV" range is really an N-dBFS (Vpp-differential) reference;
        // show its real levels in the ranges table while the key stays plain (§6
        // cheat-sheet). displayLabel is DISPLAY ONLY and this client never writes it
        // out: it is re-derived on every device build (here), and parsed from a BENCH
        // card's content rows by the range reader - while devices.yaml, localStorage
        // and every keyed form keep the short key alone.
        row.displayLabel = verboseInputLabel(dbv);
        row.fsLeft = inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, false));
        row.fsRight = inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, true));
      } else {
        row.fsLeft = outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, false));
        row.fsRight = outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, true));
      }
      row.calibrated = true;         // device-owned values - never seed-refreshed away
      ep.ranges.push(row);
    }
    const defaultActive = rangeLabel(input ? DEFAULT_INPUT_DBV : DEFAULT_OUTPUT_DBV);
    ep.activeRange = (existing != null && this.#hasRow(ep, existing.activeRange))
      ? existing.activeRange : defaultActive;
    if (existing != null && this.#hasRow(ep, existing.activeRangeRight)) {
      ep.activeRangeRight = existing.activeRangeRight;
    }
    return ep;
  }

  #hasRow(ep, label) {
    if (label == null) {
      return false;
    }
    for (const row of ep.ranges) {
      if (label === row.label) {
        return true;
      }
    }
    return false;
  }
}
