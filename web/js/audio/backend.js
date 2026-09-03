/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// AudioBackend - the web's sound.AudioBackend: the ACTIVE backend (Preferences ▸ Audio) decides
// WHICH device the two seams are given - Web Audio (getUserMedia + the worklets), the
// QA402/QA403's one always-duplex WebUSB session, the software loopback, or a bench on another
// machine. Java switches on its `active` field inside openCapture() / openPlayback() /
// listInputDevices(); those three switches are scanDevices(), _newCaptureSource() and
// openPlayback() here, and SharedCapture / GeneratorController stay backend-agnostic - neither
// picks its own device. The engine that composes the controllers over these seams is
// shell/audio-engine.js (Java gui.UIEngines).

import { WebAudioCaptureSource } from './web-audio-capture-source.js';
import { DeviceFailureReason } from '../enums/device-failure-reason.js';
import { NetRefusal } from '../net/net-connection.js';
import { NetCaptureSource } from '../net/net-capture-source.js';
import { NetPlaybackSink } from '../net/net-playback-sink.js';
import { remoteBackendOf } from '../net/net-device-ref.js';
import { EMBEDDED } from '../shell/build-profile.js';
import { WebAudioPlaybackSink } from '../generator/web-audio-playback-sink.js';
import { Qa40xCaptureSource } from '../qa40x/qa40x-capture-source.js';
import { Qa40xPlaybackSink } from '../qa40x/qa40x-playback-sink.js';
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { LOOPBACK_BACKEND } from '../loopback/loopback-device-ref.js';
import { scanDevicesForBackend } from './devices.js';

/** AudioBackendType.WEB_AUDIO's name - the backend every browser has, and the one assumed when
 *  no Preferences were injected (the behaviour that predates the backend selector). */
const WEB_AUDIO_BACKEND = 'WEB_AUDIO';

export class AudioBackend {
  /**
   * @param deps {prefs, qa40xManager, qa40xFinder, netManager, loopbackManager, status}
   *   - prefs: the live Preferences. Read ONLY for the active backend (`prefs.backend`).
   *     Omitted -> WEB_AUDIO.
   *   - qa40xManager: the Qa40xDeviceManager the QA40X backend runs on. Built by the shell - it
   *     needs the device-profile store and the settings-dialog opener, neither of which belongs
   *     in the audio layer - and injected here, so nothing in this module reaches a global. Its
   *     finder MUST be the one the Scan click drives: WebUSB's chooser needs that click's user
   *     activation. Omitted -> the QA40X backend has no device and says so when selected.
   *   - qa40xFinder: THAT finder, handed in a second time as the grant seam - the manager keeps
   *     its finder private and enumerates with it (getDevices(), which never prompts), while a
   *     Scan that carries user activation needs the same instance to raise the chooser once
   *     (see {@link #scanDevices}). Omitted -> an analyzer this origin has never been granted can
   *     never become visible, so the QA40X backend stays empty forever.
   *   - netManager: the net backend's session + remote catalogue (doc/NET-PROTOCOL.md). Injected
   *     exactly as the QA40x manager is, and for the same reason: the shell owns the server list
   *     and the dial, neither of which belongs in the audio layer. Omitted -> selecting a server
   *     backend fails loudly instead of silently answering "no devices".
   *   - loopbackManager: the LoopbackDeviceManager the LOOPBACK backend runs on. Injected for the
   *     same reason the QA40x one is - it OWNS the crossing its two lanes meet on, so both lanes
   *     of one session must come from the same instance, and a module-level one would join lanes
   *     across every engine in the page. It carries no device and no permission, so the shell
   *     builds it unconditionally. Omitted -> the LOOPBACK backend has no crossing and says so
   *     when selected.
   *   - status: (text) => void - the status sink the scan and the Web Audio capture report on.
   */
  constructor({ prefs = null, qa40xManager = null, qa40xFinder = null, netManager = null,
    loopbackManager = null, status = null } = {}) {
    this._prefs = prefs;
    this._qa40x = qa40xManager;
    this._qa40xFinder = qa40xFinder;
    this._net = netManager;
    this._loopback = loopbackManager;
    this._status = status || (() => {});
    // The backend the CURRENT wiring runs on, as opposed to the one Preferences now holds: the
    // Backend combo commits its selection LIVE (before OK), so the prefs bracket compares the two
    // to recognise a switch (switchPending), and commitSwitch records the new one once the
    // engine has re-wired.
    this._appliedBackend = this.activeBackend();

    // App-exit teardown of the active backend's manager (Java AudioBackend.shutdown(), called from
    // the explicit exit code): the QA40x is the one backend whose device carries state across
    // process death, so it must be left parked at maximum input attenuation. Both events are hooked
    // because neither fires reliably alone - a tab discard or a mobile background gives only
    // pagehide, some desktop flows give only beforeunload - and a double call is harmless: the
    // manager's shutdown() serializes and a parked/never-opened device is a no-op. BEST EFFORT by
    // nature: unload does not wait for promises, so the USB writes may not complete. The switch
    // AWAY from QA40X (commitSwitch) is the path that parks it deterministically.
    if (this._qa40x != null && typeof window !== 'undefined' && window.addEventListener) {
      const park = () => { this._qa40x.shutdown(); };
      window.addEventListener('pagehide', park);
      window.addEventListener('beforeunload', park);
    }
  }

  /** The AudioBackendType name currently in force (Java AudioBackend.active()). */
  activeBackend() {
    return this._prefs != null ? this._prefs.backend.get() : WEB_AUDIO_BACKEND;
  }

  /** True while the ACTIVE backend runs the generator (and the ADC) on another machine - the one
   *  fact a sweep consumer still needs, because its record then anchors at the bench's in-band
   *  mark instead of the local ready instant (Java GeneratorLane.isRemoteSession, which likewise
   *  asks the BACKEND rather than an open lane: the operator switches backends between
   *  measurements). */
  isRemoteSession() {
    return remoteBackendOf(this.activeBackend()) != null;
  }

  /** True while Preferences holds a different backend than the one the engine is wired for - in
   *  which case BOTH prefs brackets bounce BOTH directions, whatever the per-direction flags say.
   *  The per-direction gating exists only because Web Audio has a separate input and output
   *  device; the QA40x is ONE always-duplex device whose ADC will not stream unless the DAC is
   *  fed (the duplex engine primes the output past its start threshold to start the stream at
   *  all), so a single-direction bounce there is meaningless: it would leave the other lane
   *  attached to the old backend's device, or the new device half-started. It is also the moment
   *  the device the app is leaving has to be parked, which needs both lanes down. Java has no
   *  gating at all here (MultifunctionalTab bounces all three on any audio change) - this is that
   *  behaviour, restored for exactly the change that needs it. */
  switchPending() {
    return this.activeBackend() !== this._appliedBackend;
  }

  /** Records the Preferences backend as the applied one and parks the backend being left
   *  (Java AudioBackend.setActive shuts the deactivated manager down at the switch precisely so
   *  its device never sits in a live state while another backend measures - for the QA40x that
   *  is the safe-state park: input +42 dBV, output −12 dBV, transport released). Called by the
   *  engine's second prefs bracket AFTER the lanes are detached and BEFORE the new backend opens
   *  anything. A no-op when no switch is pending. */
  async commitSwitch() {
    const left = this._appliedBackend;
    const switched = this.switchPending();
    this._appliedBackend = this.activeBackend();
    if (switched && left === QA40X_BACKEND && this._qa40x != null) await this._qa40x.shutdown();
  }

  /** Enumerate the ACTIVE backend's input/output devices, each input carrying its native rate
   *  (Java AudioBackend.listInput/OutputDevices, which switch on the active backend). Device
   *  enumeration is its own layer (devices.js), not part of the capture/generate engine.
   *
   *  @param fromUserGesture true ONLY from the Preferences ▸ Scan click, whose user activation is
   *    what lets the QA40x path raise the WebUSB device chooser - the one way an analyzer this
   *    origin has never been granted can become visible at all. Every other enumeration (app load,
   *    a backend rollback) leaves it false and so lists only already-granted devices: a chooser
   *    without activation would throw, and prompting behind the user's back is user-hostile. */
  async scanDevices(fromUserGesture = false) {
    // `async` so an unwired QA40x manager arrives as a REJECTION, not a synchronous throw: this
    // method has always handed its caller a promise, and a bare .catch() must keep working.
    const backend = this.activeBackend();
    const manager = (backend === QA40X_BACKEND) ? this._requireQa40x() : null;
    // Resolved synchronously, and devices.js spends no await before the chooser either: user
    // activation is a 5-second budget the Web Audio probe (getUserMedia + ~½ s per device) would
    // blow outright, which is why the QA40x branch grants FIRST and never probes.
    const granter = fromUserGesture ? this._qa40xGranter(backend) : null;
    // The net manager is handed on so devices.js can take its net branch: without it a scan on a
    // server backend falls through to the LOCAL getUserMedia probe and fills the combos with THIS
    // machine's devices while the backend is remote - and the first open then fails on a device
    // name the bench never offered. The loopback manager travels for exactly the same reason: its
    // branch must not fall through to the microphone probe either.
    return scanDevicesForBackend(backend, manager, (t) => this._status(t), granter, this._net,
      backend === LOOPBACK_BACKEND ? this._requireLoopback() : null);
  }

  /** The WebUSB grant seam for a scan that carries user activation: the injected finder on the
   *  QA40X backend, null on any other. A QA40X scan with no finder wired is exactly the regression
   *  this exists to prevent - the chooser unreachable, so a never-granted analyzer stays invisible
   *  - and it is a wiring mistake, not a device state, hence the warning rather than a throw: an
   *  already-granted analyzer still enumerates fine without it. */
  _qa40xGranter(backend) {
    if (backend !== QA40X_BACKEND) {
      return null;
    }
    if (this._qa40xFinder == null) {
      console.warn('QA40x scan carries user activation but no Qa40xDeviceFinder was injected into'
        + ' the AudioBackend - the WebUSB chooser cannot be raised, so only analyzers this origin'
        + ' was already granted can be listed');
      return null;
    }
    return this._qa40xFinder;
  }

  /** The injected QA40x device manager, or a loud failure. Enumerating, capturing and playing on
   *  a backend whose manager the shell never wired must fail visibly (the scan surfaces it, a
   *  start reports it as a device error) - never silently as "no devices". */
  _requireQa40x() {
    if (this._qa40x == null) {
      throw new Error('QA40x backend selected but no Qa40xDeviceManager was injected into the AudioBackend');
    }
    return this._qa40x;
  }

  /** The ACTIVE backend's capture device (Java AudioBackend.openCapture): the Web Audio
   *  getUserMedia + worklet pipeline, or a client of the QA40x duplex engine's capture lane.
   *  Both implement the one CaptureSource contract SharedCapture consumes.
   *
   *  WHERE THE LANE CLIENT IS BORN. Java takes two steps: AudioBackend.openCapture() switches on
   *  the active backend, then Qa40xDeviceManager.openCapture() - whose entire body is
   *  `new Qa40xRecorder(this, sampleRate)` - constructs the client. The web collapses both into
   *  this one method, because the second step carries no decision: the client is built here with
   *  the manager it drives (Java's `this`) and reads the rate at open() instead of at construction.
   *  So Qa40xDeviceManager has NO openCapture/openPlayback, deliberately and permanently - not
   *  pending some later module.
   *
   *  @param backend the backend name the source is built for
   *  @param deviceId the configured input device - a browser deviceId locally, the device NAME
   *    on a bench (a remote device has no browser id to key on) */
  _newCaptureSource(backend, deviceId) {
    if (remoteBackendOf(backend) != null) return this._newNetCaptureSource(deviceId);
    this._requireNotEmbedded(backend);
    if (backend === LOOPBACK_BACKEND) {
      // The manager builds the lane, unlike the two branches around it, because the crossing the
      // lane meets its playback twin on is the MANAGER's state (loopback-device-manager.js).
      return this._requireLoopback().openCapture(null);
    }
    return (backend === QA40X_BACKEND)
      ? new Qa40xCaptureSource(this._requireQa40x())
      : new WebAudioCaptureSource({ status: (t) => this._status(t) });
  }

  /** The injected loopback manager, or a loud failure - the same rule the QA40x manager follows:
   *  a backend whose manager the shell never wired must fail visibly, never silently as "no
   *  devices". */
  _requireLoopback() {
    if (this._loopback == null) {
      throw new Error('LOOPBACK backend selected but no LoopbackDeviceManager was injected into the AudioBackend');
    }
    return this._loopback;
  }

  /** The EMBEDDED packaging has no local devices at all - the page a server serves is not a
   *  secure context, so getUserMedia and WebUSB are not there to be called. A local backend can
   *  only be reached here through a STORED preference that predates the packaging, and opening
   *  one would fail deep inside the browser with a sentence about permissions. It fails here
   *  instead, saying what is actually true (build-profile.js). */
  _requireNotEmbedded(backend) {
    if (EMBEDDED) {
      throw new Error(`this build serves only Phonalyser server backends - '${backend}' is not one`);
    }
  }

  /** The bench's input lane (doc/NET-PROTOCOL.md §4.4) as a CaptureSource - the same contract
   *  the two local devices implement, which is why nothing above this line changes when the ADC
   *  is on another machine. The device ref comes from the manager's catalogue, resolved by the
   *  configured device NAME: a remote device has no browser deviceId to key on. */
  _newNetCaptureSource(deviceName) {
    const manager = this._requireNet();
    const device = manager.getDeviceByName(deviceName, false);
    return new NetCaptureSource({ connection: manager.connection, device, owner: manager });
  }

  /** The manager, or a loud failure: capturing or playing on a net backend whose session the
   *  shell never wired must fail visibly, never silently as "no devices". */
  _requireNet() {
    const manager = this._net;
    if (manager == null || manager.connection == null) {
      // A refusal that KNOWS what it is: the session to the bench is gone, which the operator
      // reads as "the connection was lost" rather than as an unexplained failure. A plain Error
      // here is what made a start after a dropped session report UNKNOWN.
      throw new NetRefusal('the session to the bench is gone - reconnect to the server',
        DeviceFailureReason.DEVICE_DISCONNECTED);
    }
    return manager;
  }

  /** The ACTIVE backend's playback device (Java AudioBackend.openPlayback(device, ditherBits)):
   *  the output AudioContext + dds-processor worklet, or a client of the QA40x duplex engine's
   *  generator lane. The QA40x sink takes no `deps` - it has no async device-loss channel to
   *  report on (a wedged transport surfaces as a rejected start) and its status goes to the
   *  debug log.
   *
   *  Same collapse as {@link #_newCaptureSource}: Java's AudioBackend.openPlayback() switch plus
   *  Qa40xDeviceManager.openPlayback()'s `new Qa40xGenerator(this, sampleRate, ditherBits)` are
   *  this one line. The rate is not an argument here - the sink reads it from the start spec.
   *
   *  @param deps {deviceId, ditherBits, status} - deviceId: the configured output device (the
   *    device NAME on a bench); ditherBits: the configured dither, which opens the loopback lane
   *    exactly as Java's openPlayback argument does (the start spec then carries the live value);
   *    status: (text) => void for the sinks that report. */
  openPlayback(deps) {
    const backend = this.activeBackend();
    if (remoteBackendOf(backend) != null) {
      const manager = this._requireNet();
      return new NetPlaybackSink({
        connection: manager.connection,
        device: manager.getDeviceByName(deps && deps.deviceId, true),
        owner: manager,
        status: deps && deps.status,
      });
    }
    this._requireNotEmbedded(backend);
    if (backend === LOOPBACK_BACKEND) {
      // A zero falls back to the lane's last-bit default - it is never undithered
      // (loopback-playback.js states the rule).
      return this._requireLoopback().openPlayback(null, deps && deps.ditherBits);
    }
    return (backend === QA40X_BACKEND)
      ? new Qa40xPlaybackSink(this._requireQa40x())
      : new WebAudioPlaybackSink(deps);
  }

  /**
   * One CaptureSource per SharedCapture that holds the device of whichever backend was active at
   * its last open(), and replaces it when the active backend has moved since. Java dispatches at
   * the same moment - its SharedCapture.acquire() calls AudioBackend.instance().openCapture(...)
   * per acquire - and resolving at open() is what lets a backend switch land WITHOUT replacing
   * the SharedCapture whose ring and reader cursors the scope/FFT consumers already hold.
   *
   * @returns {import('./shared-capture.js').CaptureSource}
   */
  openCapture() {
    let device = null;         // the concrete device currently held (null before the first open)
    let deviceBackend = null;
    return {
      open: (deviceId, sampleRateHz) => {
        const backend = this.activeBackend();
        // Rebuilt when the backend changed - and when the source has gone STALE, which is a
        // bench source bound to a session that has since died or been replaced. Without the
        // second test the cached source went on talking to a closed socket for the life of the
        // page: every start failed with "the connection to ws://... is closed" as UNKNOWN, and
        // only a reload cleared it. A source that cannot say (a local
        // device) is never stale.
        const stale = device != null && typeof device.isStale === 'function' && device.isStale();
        if (device == null || deviceBackend !== backend || stale) {
          device = this._newCaptureSource(backend, deviceId);
          deviceBackend = backend;
        }
        return device.open(deviceId, sampleRateHz);
      },
      // BOTH callbacks are forwarded: the second one is the capture-ended seam (Java's
      // PcmBatchListener.captureEnded). A wrapper that passed only the batches would leave the
      // ring unable to learn that its device died - the trace would go on drawing a flat line,
      // which is the very thing that seam exists to end.
      start: (onBatch, onCaptureEnded) => device.start(onBatch, onCaptureEnded),
      // Null-guarded because teardown() runs stop-then-close on a FAILED acquire too, and a
      // TypeError here would mask the open failure SharedCapture is in the middle of reporting.
      stop: () => (device != null ? device.stop() : undefined),
      close: () => (device != null ? device.close() : undefined),
      // The optional halves of the contract, forwarded only when the concrete device has them
      // (SharedCapture tests both the same way): the failure classifier of the backend that owns
      // the native error, and the in-band marker seam a bench-rendered sweep needs.
      // UNKNOWN rather than undefined when the concrete device cannot read its own errors: this
      // wrapper always HAS the method, so the caller's "does it have one" test can no longer be
      // the fallback - the SPI default has to be answered here (Java's default classifyFailure).
      classifyFailure: (err) => (device != null && typeof device.classifyFailure === 'function'
        ? device.classifyFailure(err) : DeviceFailureReason.UNKNOWN),
      // The sentence the failure came WITH, when the backend that owns it composed one (a bench's
      // spec-4.2 refusal). Null is the SPI default here for the same reason UNKNOWN is above.
      refusalText: (err) => (device != null && typeof device.refusalText === 'function'
        ? device.refusalText(err) : null),
      setMarkerListener: (listener) => {
        if (device != null && typeof device.setMarkerListener === 'function') {
          device.setMarkerListener(listener);
        }
      },
      /** The concrete per-backend device currently held - the dispatch's one observable. */
      get device() { return device; },
      get sampleRate() { return device != null ? device.sampleRate : 0; },
      get isOpen() { return device != null && device.isOpen; },
    };
  }
}
