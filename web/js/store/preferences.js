/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.preferences.Preferences (plus its
// helper POJOs BackendPrefs, OscPreset, FftPreset, FreqRespPreset and
// CalibrationEntry).
//
// Every setting keeps the EXACT default value, the EXACT serialised key, and the
// EXACT load/save semantics of the Java toMap/fromMap pair - including the
// conditional omissions (only-when-non-null / only-when-positive), the colour
// hex round-trip (#RRGGBB), the DAC full-scale ampl↔RMS conversion (× / ÷ √2 at
// the serialisation boundary), the cached dBV offset and √(bin bandwidth)
// constants, the migration of the legacy fftAlignGenToFreqDiff checkbox, and the
// load-time clamps (freqRespFftSize power-of-two snap, freqRespNyquistFraction
// band, freqRespCompareSmoothWindow range, freqRespNotchBaseHz 50/60 snap).
//
// Differences from the desktop original, all behaviour-preserving:
//   * Persistence target is localStorage (one JSON string under PREFS_KEY)
//     instead of a YAML file in the working directory. The in-memory shape that
//     is (de)serialised is identical to the Java root map.
//   * The desktop audio-backend POJO settings keep their inputDeviceName /
//     outputDeviceName slots, which on the web map to Web Audio device IDs
//     (MediaDeviceInfo.deviceId) - same string field, web meaning.
//   * The single-thread debounced save daemon becomes a setTimeout coalescer
//     (SAVE_COALESCE_MS) writing to localStorage; flush() forces it out (hook it
//     to 'pagehide'/'beforeunload' at the call site, mirroring the JVM shutdown
//     hook).
//   * Property change listeners use a tiny local observable (mirrors
//     org.edgo.audio.measure.bind.Property: get/set/addListener, fires only on a
//     real change).

const SQRT2 = Math.sqrt(2.0);

/** localStorage key the whole JSON document lives under. */
export const PREFS_KEY = 'phonalyser.preferences';

/** formatVersion stamped into the document (FileVersions.PREFERENCES_YAML). */
export const PREFERENCES_FORMAT_VERSION = 1;

/** Debounce window for auto-save (Preferences.SAVE_COALESCE_MS). */
const SAVE_COALESCE_MS = 250;

/** Factory-default ADC full-scale RMS voltage (Preferences.DEFAULT_ADC_FS_VRMS). */
const DEFAULT_ADC_FS_VRMS = 1.7931;

/** Sentinel "unset" value for the rate-dependent FreqResp defaults
 *  (stop = Nyquist, points = FS/2). A fresh install with no saved value keeps
 *  this until _seedRateDependentFreqRespDefaults resolves it from the current
 *  device sample rate; any saved value is a real number and overrides it
 *  (Preferences.FREQRESP_RATE_DEFAULT_SENTINEL). */
const FREQRESP_RATE_DEFAULT_SENTINEL = 0;

/**
 * Per-OS default UI font as a {@code "family|size|style"} string (faithful port of
 * Preferences.defaultUiFont): Consolas 9 on Windows, Menlo 11 on macOS, DejaVu Sans
 * Mono 11 elsewhere (Linux) - each platform's standard monospace face. `sizeBump`
 * enlarges the channel-button font above the base size.
 * @param {string} style
 * @param {number} sizeBump
 * @returns {string}
 */
function defaultUiFont(style, sizeBump) {
  const uaPlatform = (navigator.userAgentData && navigator.userAgentData.platform) || '';
  const os = (uaPlatform || navigator.platform || navigator.userAgent || '').toLowerCase();
  let family, size;
  if (os.includes('mac')) {
    family = 'Menlo';            size = 11;
  } else if (os.includes('win')) {
    family = 'Consolas';         size = 9;
  } else {
    family = 'DejaVu Sans Mono'; size = 11;
  }
  return family + '|' + (size + sizeBump) + '|' + style;
}

import { FreqRespFilterTypeParams } from './freqresp-filter-type-params.js';
import { fromNameOr as persistenceFromNameOr } from '../scope/scope-enums.js';
import { quarantineStoreEntry } from './store-quarantine.js';
import { remoteBackendOf } from '../net/net-device-ref.js';

// --- enum value sets (the legal serialised names, mirroring the Java enums) ---
const E = {
  // WEB_AUDIO and QA40X are the backends a BROWSER actually has (Java's OS backends have no
  // counterpart here). The four OS names stay legal so a value persisted by an earlier build still
  // loads - enumOr() gates both `backend` and every perBackend map key, and an unknown name is
  // silently dropped, which would orphan that backend's saved device selections.
  AudioBackendType: ['WASAPI', 'WDMKS', 'COREAUDIO', 'JAVASOUND', 'WEB_AUDIO', 'QA40X'],
  Channel: ['L', 'R'],
  TriggerEdge: ['RISE', 'FALL'],
  TriggerType: ['EDGE', 'GLITCH'],
  TriggerMode: ['AUTO', 'NORMAL', 'SINGLE'],
  MainsSuppression: ['NONE', 'IIR_COMB', 'SYNC_SUBTRACT', 'LMS'],
  LpfMode: ['NONE', 'HZ_80', 'DESPIKE'],
  GenSignalForm: ['SINE', 'SINE_COMP', 'TRIANGLE', 'RECTANGLE', 'WHITE_NOISE',
    'PINK_NOISE', 'PINK_NOISE_LINEAR', 'LINEAR_SWEEP', 'LOG_SWEEP',
    'DUAL_TONE', 'DUAL_TONE_COMP'],
  WindowType: ['RECT', 'HANN', 'BH4', 'BH7', 'FT', 'HFT144D', 'HFT248D',
    'KB24', 'KB38', 'DC150', 'DC200', 'DC250', 'DC300'],
  FftOverlap: ['PCT_0', 'PCT_50', 'PCT_75', 'PCT_87_5', 'PCT_93_75'],
  // DBR appended LAST on purpose: the combo binds by ordinal and presets persist the name,
  // so appending is the only position that keeps old presets loading (Java MagnitudeUnit).
  MagnitudeUnit: ['V', 'V_SQRT_HZ', 'DBV', 'DBFS', 'DBR'],
  AlignGenerator: ['NONE', 'FLL'],
  TabOrientation: ['TOP', 'LEFT'],
  FilterType: ['LOW_PASS', 'HIGH_PASS', 'BAND_PASS', 'NOTCH'],
  FilterResponse: ['BESSEL', 'BUTTERWORTH', 'CHEBYSHEV', 'ELLIPTIC', 'INV_CHEBYSHEV'],
  UnevenMode: ['OFF', 'LEVEL', 'RANGE'],
  OutputChannels: ['BOTH', 'LEFT', 'RIGHT'],
};

/** Forms whose name ends in DUAL_TONE / DUAL_TONE_COMP (GenSignalForm.isDualTone). */
const DUAL_TONE_FORMS = new Set(['DUAL_TONE', 'DUAL_TONE_COMP']);

/** Enum-name validity check returning the stored value or a fallback
 *  (mirrors Preferences.enumOr - invalid name keeps the current value). */
function enumOr(setName, name, fallback) {
  return E[setName].includes(name) ? name : fallback;
}

/**
 * The same check for a BACKEND SELECTION, which is not only an enum name: a server's backend is
 * stored as `net:<its name>` (net-device-ref.js), and this client is not the authority on what a
 * bench may offer - the name past the prefix is the SERVER's enum, not this build's.
 *
 * Gating those values against the local enum is what made a bench's whole settings block vanish
 * on every reload: `net:JAVASOUND` matched nothing, the key was dropped, and prefsFor() then
 * built a fresh default block - device, rates and widths all gone. Both the selection itself
 * and every perBackend key go through here, so the two can no longer disagree about which
 * values are storable.
 */
function backendKeyOr(name, fallback) {
  if (E.AudioBackendType.includes(name)) return name;
  return remoteBackendOf(name) != null ? name : fallback;
}

/** Formats a packed 0xRRGGBB int as '#RRGGBB' (Preferences.formatHtmlColor). */
function formatHtmlColor(rgb) {
  return '#' + ((rgb & 0xffffff) >>> 0).toString(16).toUpperCase().padStart(6, '0');
}

/** Parses '#RRGGBB' (or bare 'RRGGBB') into a packed int, else fallback
 *  (Preferences.parseHtmlColor). */
function parseHtmlColor(s, fallback) {
  if (s == null) return fallback;
  let h = String(s).trim();
  if (h.startsWith('#')) h = h.substring(1);
  if (h.length !== 6) return fallback;
  const v = parseInt(h, 16);
  return Number.isNaN(v) ? fallback : (v & 0xffffff);
}

// --- type guards matching the Java `instanceof Number/Boolean/String` gates ---
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isBool = (v) => typeof v === 'boolean';
const isStr = (v) => typeof v === 'string';
const asMap = (v) => (v && typeof v === 'object' && !Array.isArray(v)) ? v : null;
const trunc = Math.trunc;

/**
 * Minimal observable cell mirroring org.edgo.audio.measure.bind.Property:
 * holds a value, notifies listeners only on a real change (Object.is compare).
 *
 * @template T
 */
class Property {
  /** @param {T} initial */
  constructor(initial) {
    this._value = initial;
    /** @type {Array<(v: T) => void>} */
    this._listeners = [];
  }

  /** @returns {T} */
  get() { return this._value; }

  /** @param {T} v */
  set(v) {
    if (Object.is(this._value, v)) return;
    this._value = v;
    for (const l of this._listeners) l(v);
  }

  /** @param {(v: T) => void} fn */
  addListener(fn) { this._listeners.push(fn); }
}

/**
 * Per-backend audio settings (BackendPrefs). Device slots are Web Audio device
 * IDs on the web (the desktop stored OS device names in the same fields).
 */
export class BackendPrefs {
  constructor() {
    /** @type {?string} */ this.inputDeviceName = null;
    /** @type {?string} */ this.outputDeviceName = null;
    this.inputSampleRate = 384000;
    this.inputBitDepth = 24;
    this.outputSampleRate = 384000;
    this.outputBitDepth = 24;
  }

  /** Copies every field from {@code src} (BackendPrefs.copyFrom). */
  copyFrom(src) {
    this.inputDeviceName = src.inputDeviceName;
    this.outputDeviceName = src.outputDeviceName;
    this.inputSampleRate = src.inputSampleRate;
    this.inputBitDepth = src.inputBitDepth;
    this.outputSampleRate = src.outputSampleRate;
    this.outputBitDepth = src.outputBitDepth;
  }

  /** Returns a restorable snapshot (BackendPrefs.snapshot). */
  snapshot() {
    const c = new BackendPrefs();
    c.copyFrom(this);
    return c;
  }
}

/** Saved oscilloscope preset (OscPreset). Plain mutable POJO. */
export class OscPreset {
  constructor() {
    this.leftChannelEnabled = true;
    this.rightChannelEnabled = true;
    this.leftAcMode = false;
    this.rightAcMode = false;
    this.leftSincInterpEnabled = true;
    this.rightSincInterpEnabled = true;
    this.leftResidualEnabled = false;
    this.rightResidualEnabled = false;
    this.leftMainsSuppression = 'NONE';
    this.rightMainsSuppression = 'NONE';
    this.leftLpf = 'NONE';
    this.rightLpf = 'NONE';
    this.leftVoltsPerDiv = 0.1;
    this.rightVoltsPerDiv = 0.1;
    this.leftOffsetFrac = 0.5;
    this.rightOffsetFrac = 0.5;
    this.timePerDiv = 1e-3;
    this.triggerPositionFrac = 0.5;
    this.triggerChannel = 'L';
    this.triggerEdge = 'RISE';
    this.triggerType = 'EDGE';
    this.triggerMode = 'AUTO';
    this.triggerLevelFrac = 0.5;
  }
}

/** Saved FFT-view preset (FftPreset). Plain mutable POJO. */
export class FftPreset {
  constructor() {
    this.channel = 'L';
    this.magUnit = 'DBV';
    this.logFreqAxis = true;
    this.freqMinHz = 20;
    this.freqMaxHz = 20000;
    this.magTop = 10;
    this.magBottom = -150;
    this.fftLength = 65536;
    this.averages = 4;
    this.stopAfterNEnabled = false;
    this.stopAfterN = 10;
    this.fundFromGenerator = false;
    this.window = 'HANN';
    this.overlap = 'PCT_0';
    this.coherentAveraging = true;
    this.distMinHz = 20;
    this.distMaxHz = 20000;
    this.distMinEnabled = false;
    this.distMaxEnabled = false;
    this.thdMaxHarmonic = 9;
    this.calcMaxHarmonic = 9;
    this.manualFundVrms = 1.0;
    this.manualFundDbvDisplay = false;
    this.manualFundEnabled = false;
  }
}

/** Saved Frequency-Response preset (FreqRespPreset). Plain mutable POJO. */
export class FreqRespPreset {
  constructor() {
    this.startHz = 20.0;
    this.stopHz = 20000.0;
    this.amplitudeVrms = 0.5;
    this.sweepPoints = 65536;
    this.fftSize = 524288;
    this.leadInSec = 0.2;
    this.ditherBits = 0;
    this.showRiaa = false;
    this.reverseRiaa = false;
    this.iecAmendment = false;
    this.compareMode = false;
    // Filter overlay
    this.showFilter = false;
    this.filterCompare = false;
    this.filterType = 'LOW_PASS';
    this.filterResponse = 'BUTTERWORTH';
    this.filterParams = FreqRespFilterTypeParams.fromType('LOW_PASS');
    // Unevenness
    this.unevenMode = 'OFF';
    this.unevenNotch = false;
    this.unevenDb = 3.0;
    this.unevenStartHz = 20.0;
    this.unevenStopHz = 20000.0;
  }
}

/**
 * One calibration row (CalibrationEntry): a .frc path plus two observable
 * toggles. {@code path} is plain mutable state (no change notification); active
 * and withNoise are observable so the row's checkbox can two-way bind, and so a
 * toggle persists through the debounced save when the entry is tracked.
 * {@code hash} is the SHA-256 hex of the original bytes (set by cal-store.js
 * putCal at load time); null when the file has not yet been stored.
 */
export class CalibrationEntry {
  /**
   * @param {?string} [path=null]
   * @param {boolean} [active=false]
   * @param {boolean} [withNoise=false]
   * @param {?string} [hash=null]
   */
  constructor(path = null, active = false, withNoise = false, hash = null) {
    /** @type {?string} */
    this.path = path;
    /** @type {?string} SHA-256 hex of the original .frc bytes; null until putCal. */
    this.hash = hash;
    this._active = new Property(active);
    this._withNoise = new Property(withNoise);
  }

  /** @returns {Property<boolean>} */
  active() { return this._active; }

  /** @returns {Property<boolean>} */
  withNoise() { return this._withNoise; }
}

/**
 * Process-wide GUI preferences (faithful port of
 * org.edgo.audio.measure.preferences.Preferences). Access the shared instance
 * via {@link Preferences.instance}; persistence is to localStorage under
 * {@link PREFS_KEY}.
 */
export class Preferences {
  constructor(detached = false) {
    // Detached copy (see copyForDialog): never loads from / writes to localStorage,
    // so a dialog can mutate it freely and drop it on close. Mirrors the Java
    // private Preferences(boolean detached) constructor.
    this.transientMode = !!detached;
    /** @type {Map<string, BackendPrefs>} keyed by AudioBackendType name. */
    this._perBackend = new Map();
    /** Component-owned preference blocks, keyed by their key() (Java: customPrefs).
     *  Registered by their owners (backend managers), which are built lazily - so
     *  this fills up long after load() has run.
     *  @type {Map<string, import('../qa40x/qa40x-preferences.js').SubPreferences>} */
    this._customPrefs = new Map();
    /** The `custom` section exactly as load() read it (Java: customRaw). Keeps the
     *  block of an extension that never registered this session alive across a save,
     *  and lets registerCustomPreferences replay a late registrant's saved values.
     *  @type {Map<string, *>} */
    this._customRaw = new Map();

    // ---- top-level scalar/enum properties (bound: a real change auto-saves) ---
    this.backend = this._bound('WASAPI');
    this.uiLanguage = this._bound('en');
    this.tabOrientation = this._bound('TOP');
    this.activeTabIndex = this._bound(0);
    this.smallIconsInMainTab = this._bound(false);
    this.uiFontNormal = this._bound(defaultUiFont('normal', 0));
    this.uiFontBold = this._bound(defaultUiFont('bold', 0));
    this.uiFontChannel = this._bound(defaultUiFont('bold', 3));
    this.checkForUpdatesOnStartup = this._bound(false);
    this.includeBetaInUpdateChecks = this._bound(false);
    this.showTipsAtStartup = this._bound(true);

    // ---- oscilloscope toolbar state ----
    this.oscLeftChannelEnabled = this._bound(true);
    this.oscRightChannelEnabled = this._bound(true);
    this.oscLeftAcMode = this._bound(false);
    this.oscRightAcMode = this._bound(false);
    this.oscLeftVoltsPerDiv = this._bound(0.1);
    this.oscRightVoltsPerDiv = this._bound(0.1);
    this.oscTimePerDiv = this._bound(1e-3);
    // Per-pane last-used screenshot size (0 = fall back to the pane's native size).
    // scope*/fft*/freqResp* keep the three panes independent (see fftScreenshotWidth
    // and freqRespScreenshotWidth below).
    this.scopeScreenshotWidth = this._bound(0);
    this.scopeScreenshotHeight = this._bound(0);
    this.oscTriggerChannel = this._bound('L');
    this.oscTriggerEdge = this._bound('RISE');
    // Trigger event type: EDGE = level crossing, GLITCH = dV/dt jump.
    this.oscTriggerType = this._bound('EDGE');
    this.oscTriggerMode = this._bound('AUTO');
    this.oscTriggerHysteresisDiv = this._bound(0.0);
    this.oscTriggerHysteresisEnabled = this._bound(false);
    this.oscShowReconstructedBeat = this._bound(false);
    this.oscLeftSincInterpEnabled = this._bound(true);
    this.oscRightSincInterpEnabled = this._bound(true);
    this.oscLeftResidualEnabled = this._bound(false);
    this.oscRightResidualEnabled = this._bound(false);
    this.oscLeftMainsSuppression = this._bound('NONE');
    this.oscRightMainsSuppression = this._bound('NONE');
    this.oscLeftLpf = this._bound('NONE');
    this.oscRightLpf = this._bound('NONE');
    this.oscLeftOffsetFrac = this._bound(0.5);
    this.oscRightOffsetFrac = this._bound(0.5);
    this.oscTriggerLevelFrac = this._bound(0.5);
    this.oscTriggerPositionFrac = this._bound(0.5);
    this.oscMeasurementAverageSeconds = this._bound(5.0);
    this.oscLineWidth = this._bound(2.0);
    this.oscDotDiameter = this._bound(5);
    // Display persistence ("digital phosphor") - GPU path only. Mode stored by enum name
    // (PersistenceMode), manual-seconds used only when mode == MANUAL.
    this.oscPersistenceMode = this._bound('OFF');
    this.oscPersistenceManualSeconds = this._bound(1.0);
    this.oscLeftChannelColor = this._bound(0x00d7ff);
    this.oscRightChannelColor = this._bound(0xffd700);

    this.screenshotFolder = this._bound(null);
    this.screenshotCommentFont = this._bound(null);
    this.oscMeasurementChannel = this._bound('L');
    this.oscShowStats = this._bound(true);
    this.oscShowMeasurementTable = this._bound(true);
    /** Amplitude-histogram window open state. */
    this.oscShowHistogram = this._bound(false);
    /** Bars the histogram DRAWS. Display resolution only: changing it re-aggregates the collected
     *  micro-bins, and never discards a count. Dialog range 10...200, step 5. */
    this.oscHistogramBins = this._bound(50);
    /** Channel the histogram shows. DELIBERATELY separate from oscMeasurementChannel: that one's
     *  subscriber clears the measurement statistics, so sharing it would make a histogram channel
     *  pick wipe the table's avg / min / max / σ. */
    this.oscHistogramChannel = this._bound('L');
    this.adcFsVoltageRms = this._bound(DEFAULT_ADC_FS_VRMS);
    // RIGHT-channel ADC full-scale - the per-channel sibling of adcFsVoltageRms;
    // defaults to the same legacy value and mirrors it until a profile / calibration
    // sets it (Preferences.adcFsVoltageRmsRight).
    this.adcFsVoltageRmsRight = this._bound(DEFAULT_ADC_FS_VRMS);
    // In-memory PEAK amplitude; persisted as RMS (÷√2 on save, ×√2 on load).
    this.dacFsVoltageAmpl = this._bound(2.79351);
    // RIGHT-channel DAC full-scale (PEAK amplitude) - the per-channel sibling of
    // dacFsVoltageAmpl; defaults to the same value (Preferences.dacFsVoltageAmplRight).
    this.dacFsVoltageAmplRight = this._bound(2.79351);

    this.oscSavePath = this._bound(null);
    this.oscSaveFolder = this._bound(null);
    this.oscSaveDurationSeconds = this._bound(5.0);
    this.oscPlayFromPath = this._bound(null);
    this.oscPlayFromFolder = this._bound(null);
    this.oscPlayFromLoop = this._bound(false);

    // ---- generator pane ----
    this.genSignalForm = this._bound('SINE');
    this.genFrequencyHz = this._bound(1000.0);
    this.genDualToneFreq1Hz = this._bound(1000.0);
    this.genDualToneFreq2Hz = this._bound(1300.0);
    this.genDualToneSplitPct = this._bound(50.0);
    this.genAmplitudeVrms = this._bound(0.5);
    this.genAmplitudeDbvDisplay = this._bound(false);
    // Dither depth in bits (may be fractional); 0 = Off. Mirrors Java Preferences.genDitherBits (double).
    this.genDitherBits = this._bound(0.0);
    // True = the dither field displays in dBV (the user typed an explicit dBV suffix); persisted so a
    // restart keeps the choice. Mirrors Java Preferences.genDitherDbvDisplay.
    this.genDitherDbvDisplay = this._bound(false);
    // Which output lane(s) the generator drives - the encoder gate ('BOTH' by
    // default = pre-feature behaviour). Applied at the interleave seam (the DDS
    // worklet for live playback, the genSave export path), like Java's
    // Preferences.genOutputChannels feeding PcmQuantizer / SignalFileExporter.
    this.genOutputChannels = this._bound('BOTH');
    this.genDpd = this._bound(null);
    this.genDpdDual = this._bound(null);
    this.genDpdFolder = this._bound(null);
    // The original .dpd basename per compensated slot, shown in the corrections row after a
    // reload (the web stores the .dpd TEXT in genDpd/genDpdDual, not an OS path, so without this
    // there's nothing but a bare checkmark to show - the full OS path stays unavailable).
    this.genDpdName = this._bound(null);
    this.genDpdDualName = this._bound(null);
    this.predistortionAverages = this._bound(64);
    this.predistortionTargetPct = this._bound(0.000001);
    this.genRectangleDuty = this._bound(0.5);
    this.genTriangleDuty = this._bound(0.5);
    this.genSweepFreqStartHz = this._bound(20.0);
    this.genSweepFreqEndHz = this._bound(20000.0);
    this.genSweepDurationSec = this._bound(1.0);
    this.genSweepLoop = this._bound(true);
    this.genSweepFadeInSec = this._bound(0.01);
    this.genSweepFadeOutSec = this._bound(0.01);
    this.genSnapToFftBin = this._bound(false);
    this.genWavDurationSeconds = this._bound(5.0);
    this.genWavPath = this._bound(null);
    this.genWavFolder = this._bound(null);
    this.genPlayFromPath = this._bound(null);
    this.genPlayFromFolder = this._bound(null);
    this.genPlayFromLoop = this._bound(false);

    // ---- window geometry / multifunctional layout ----
    this.windowWidth = this._bound(0);
    this.windowHeight = this._bound(0);
    this.genPaneWidth = this._bound(0);
    /** @type {?number[]} plain mutable (not bound); see toMap/fromMap. */
    this.multiVSplitWeights = null;
    this.genPaneCollapsed = this._bound(false);
    this.oscPaneCollapsed = this._bound(false);
    this.fftPaneCollapsed = this._bound(true);

    /** @type {Map<string, OscPreset>} insertion-ordered. */
    this.oscPresets = new Map();

    // ---- FFT pane ----
    this.fftLength = this._bound(65536);
    this.fftAverages = this._bound(4.0);
    // Web-only: FFT worker-pool size (#threads select). No Java counterpart -
    // the desktop parallelises automatically.
    this.fftThreads = this._bound(1);
    this.fftStopAfterNEnabled = this._bound(false);
    this.fftStopAfterN = this._bound(10);
    this.fftFundFromGenerator = this._bound(false);
    this.fftLogFreqAxis = this._bound(true);
    // Time-domain discontinuity gate toggle (Java Preferences.fftDetectTimeDiscontinuity,
    // default ON). Uncheck to keep computing the FFT for a small / non-sinusoidal signal the
    // gate would otherwise reject every block of. The worker reads it live per tick.
    this.fftDetectTimeDiscontinuity = this._bound(true);
    this.fftWindow = this._bound('HANN');
    this.fftOverlap = this._bound('PCT_0');
    this.fftCoherentAveraging = this._bound(true);
    this.fftMainsSuppression = this._bound('NONE');
    this.fftAlignGenerator = this._bound('NONE');
    this.fftDistMinHz = this._bound(20.0);
    this.fftDistMaxHz = this._bound(20000.0);
    this.fftDistMinEnabled = this._bound(false);
    this.fftDistMaxEnabled = this._bound(false);
    this.fftThdMaxHarmonic = this._bound(9);
    this.fftCalcMaxHarmonic = this._bound(9);
    this.fftStrongToneRelDb = this._bound(100.0);
    this.fftManualFundVrms = this._bound(1.0);
    this.fftManualFundDbvDisplay = this._bound(false);
    this.fftManualFundEnabled = this._bound(false);
    this.fftChannel = this._bound('L');
    this.fftMagUnit = this._bound('DBV');
    // Unit the distortion table's absolute-level cells render in - independent of the
    // magnitude axis above: 'DBV' (level + ADC offset), 'DBFS' (the measured level as
    // stored) or 'DBR' (level minus the reference the table's percentages are computed
    // against, shown as plain "dB").
    this.fftDistortionUnit = this._bound('DBV');
    // Per-pane last-used screenshot size (0 = fall back to the pane's native size); mirrors
    // scopeScreenshotWidth/Height so the FFT shot remembers its OWN size independently.
    this.fftScreenshotWidth = this._bound(0);
    this.fftScreenshotHeight = this._bound(0);
    this.fftDistortionTableVisible = this._bound(true);
    this.fftFreqMinHz = this._bound(20.0);
    this.fftFreqMaxHz = this._bound(20000.0);
    this.fftMagTop = this._bound(10.0);
    this.fftMagBottom = this._bound(-150.0);
    this.fftSavePath = this._bound(null);
    this.fftSaveFolder = this._bound(null);
    this.fftLoadPath = this._bound(null);
    this.fftLoadFolder = this._bound(null);
    /** @type {CalibrationEntry[]} */
    this.fftCalibrations = [];
    this.fftBeforeCalDotColor = this._bound(0x000080);
    this.fftCalOverlayColor = this._bound(0x009600);
    this.fftLineWidth = this._bound(1.0);
    this.fftHarmonicDotDiameter = this._bound(9);
    this.fftLineColor = this._bound(0x0064c8);
    this.fftChartBackgroundColor = this._bound(0xffffff);
    this.fftHarmonicDotColor = this._bound(0xff0000);
    this.fftFreqRespColor = this._bound(0x009600);

    /** @type {Map<string, FftPreset>} */
    this.fftPresets = new Map();
    /** @type {Map<string, FreqRespPreset>} */
    this.freqRespPresets = new Map();

    // ---- Frequency Response pane ----
    this.freqRespStartHz = this._bound(1.0);
    // Sentinel 0 = "unset": resolved to the device Nyquist (rate/2) on a fresh
    // install by _seedRateDependentFreqRespDefaults; a saved value overrides.
    this.freqRespStopHz = this._bound(FREQRESP_RATE_DEFAULT_SENTINEL);
    this.freqRespAmplitudeVrms = this._bound(1.0);
    this.freqRespAmplitudeDbvDisplay = this._bound(false);
    // Sentinel 0 = "unset": resolved to the FS/2 point count (rate/2) on a fresh
    // install by _seedRateDependentFreqRespDefaults; a saved value overrides.
    this.freqRespSweepPoints = this._bound(FREQRESP_RATE_DEFAULT_SENTINEL);
    this.freqRespDurationSec = this._bound(5.5);
    this.freqRespFftSize = this._bound(4194304);
    this.freqRespDitherBits = this._bound(0);
    this.freqRespLeadInSec = this._bound(0.05);
    // Which output lane(s) the sweep drives - the encoder gate ('BOTH' by default,
    // the only behaviour before per-channel output existed). LEFT / RIGHT write
    // digital silence to the un-driven lane; both channels are still deconvolved
    // (Java Preferences.freqRespOutputChannels feeding CaptureWithGenerator).
    this.freqRespOutputChannels = this._bound('BOTH');
    // Tune-notch wizard fields, persisted independently of the main FreqResp pane.
    this.tuneNotchStartHz = this._bound(900.0);
    this.tuneNotchStopHz = this._bound(1100.0);
    this.tuneNotchAmplitudeVrms = this._bound(1.0);
    this.tuneNotchTargetHz = this._bound(1000.0);
    // Tune-notch output-lane gate ('BOTH' by default), persisted independently of the
    // other tuneNotch* fields (Java Preferences.tuneNotchOutputChannels).
    this.tuneNotchOutputChannels = this._bound('BOTH');
    this.freqRespLeftVisible = this._bound(true);
    this.freqRespRightVisible = this._bound(false);
    this.freqRespPhaseVisible = this._bound(false);
    this.freqRespFreqMinHz = this._bound(20.0);
    this.freqRespFreqMaxHz = this._bound(20000.0);
    this.freqRespMagTopDb = this._bound(20.0);
    this.freqRespMagBotDb = this._bound(-140.0);
    this.freqRespNyquistFraction = this._bound(1.0);
    this.freqRespCompareSmoothWindow = this._bound(6);
    this.freqRespNotchEnabled = this._bound(false);
    this.freqRespNotchBaseHz = this._bound(50);
    this.freqRespSignalColor = this._bound(0x0064c8);
    this.freqRespLineWidth = this._bound(2.0);
    this.freqRespPhaseColor = this._bound(0xff0000);
    this.freqRespReferenceColor = this._bound(0x009600);
    this.freqRespBackgroundColor = this._bound(0xffffff);
    this.freqRespShowRiaa = this._bound(false);   // never persisted (see toMap/fromMap)
    this.freqRespReverseRiaa = this._bound(false);
    this.freqRespIecAmendment = this._bound(false);
    this.freqRespCompareMode = this._bound(false);
    this.freqRespShowFilter = this._bound(false);   // never persisted (see toMap/fromMap)
    this.freqRespFilterCompare = this._bound(false);
    this.freqRespFilterType = this._bound('LOW_PASS');
    this.freqRespFilterResponse = this._bound('BUTTERWORTH');
    this.freqRespUnevenMode = this._bound('OFF');
    this.freqRespUnevenNotch = this._bound(false);
    this.freqRespUnevenDb = this._bound(3.0);
    this.freqRespUnevenStartHz = this._bound(20.0);
    this.freqRespUnevenStopHz = this._bound(20000.0);
    /** @type {Map<string, FreqRespFilterTypeParams>} keyed by FilterType name (freqRespFilterParamsByType). */
    this.freqRespFilterParamsByType = new Map();
    this.freqRespApplyCalibration = this._bound(true);
    /** @type {CalibrationEntry[]} */
    this.freqRespCalibrations = [];
    this.freqRespSaveFolder = this._bound(null);
    this.freqRespSavePath = this._bound(null);
    this.freqRespLoadFolder = this._bound(null);
    this.freqRespLoadPath = this._bound(null);
    this.freqRespActiveTabIndex = this._bound(0);
    // Per-pane last-used screenshot size (0 = fall back to the pane's native size); mirrors
    // scopeScreenshotWidth/Height so the FreqResp shot remembers its OWN size independently.
    this.freqRespScreenshotWidth = this._bound(0);
    this.freqRespScreenshotHeight = this._bound(0);

    // ---- cached constants (recomputed on the relevant changes / on load) ----
    /** dBV = dBFS + dbvOffsetDb (= 20·log10(adcFsVoltageRms)). LEFT / LINKED / legacy offset. */
    this.dbvOffsetDb = 20.0 * Math.log10(DEFAULT_ADC_FS_VRMS);
    /** Cached RIGHT-channel dBV↔dBFS offset (= 20·log10(adcFsVoltageRmsRight)) -
     *  the per-channel sibling of dbvOffsetDb (Preferences.dbvOffsetDbRight). */
    this.dbvOffsetDbRight = 20.0 * Math.log10(DEFAULT_ADC_FS_VRMS);
    /** √(bin bandwidth) = √(inputSampleRate / fftLength); the V->V/√Hz divisor. */
    this.binBwSqrt = 1.0;
    /** dBr reference: the DISPLAYED fundamental level in dBFS, so DBR readings come out as
     *  dB relative to the fundamental (which therefore sits at exactly 0 dBr). A live cached
     *  value like binBwSqrt - NOT persisted; the FFT paint path re-stamps it every frame
     *  (Java Preferences.fftDbrRefDbFs). */
    this.fftDbrRefDbFs = 0.0;
    // transientMode was already set from the `detached` ctor arg at the top of the
    // constructor. When true, save() is a no-op and load()/seed are skipped, so a
    // dialog copy (copyForDialog) never touches localStorage. Do NOT reset it here -
    // an unconditional `= false` clobbered the detached flag, making the wizard's copy
    // non-transient so its save() overwrote the main pane's persisted range/channel.

    // ---- save coalescing / load-suppression flags ----
    this._loading = false;
    this._pendingSaveTimer = null;

    // fftLength / backend changes invalidate the bin-bandwidth cache, exactly
    // as the desktop listeners do (bidi-bound edits bypass the setters).
    this.fftLength.addListener(() => this._recomputeBinBw());
    this.backend.addListener(() => this._recomputeBinBw());

    if (!this.transientMode) this.load();
    // Covers the no-document case AND the per-backend sample rate, a plain POJO
    // write the listeners cannot observe.
    this._recomputeBinBw();
    // Resolve the rate-dependent FreqResp defaults (stop = Nyquist,
    // points = FS/2) on a fresh install where load() left the sentinels.
    // Only the live (non-detached) instance seeds; a detached copy receives the
    // already-resolved values through copyForDialog's _fromMap.
    if (!this.transientMode) this._seedRateDependentFreqRespDefaults();
  }

  /** Resolves the rate-dependent FreqResp defaults left as sentinels after
   *  load(): on a fresh install the stop frequency becomes the current device
   *  Nyquist (rate/2) and the sweep-points count becomes the FS/2 point count
   *  (rate/2). A user with a stored value never hits the sentinel, so their
   *  choice is preserved (Preferences.seedRateDependentFreqRespDefaults). */
  _seedRateDependentFreqRespDefaults() {
    const rate = this.current().inputSampleRate;
    if (!(rate > 0)) return;
    const nyquist = rate / 2.0;
    if (this.freqRespStopHz.get() === FREQRESP_RATE_DEFAULT_SENTINEL) {
      this.freqRespStopHz.set(nyquist);
    }
    if (this.freqRespSweepPoints.get() === FREQRESP_RATE_DEFAULT_SENTINEL) {
      this.freqRespSweepPoints.set(trunc(nyquist));
    }
  }

  /** Faithful port of Preferences.copyForDialog(): a DETACHED copy seeded with the
   *  current values that never loads from / writes to localStorage. The tune-notch
   *  wizard hands this to its embedded FreqRespView so every range / auto-fit /
   *  zoom edit stays in the copy and the shared main-pane view is never touched. */
  copyForDialog() {
    const c = new Preferences(true);
    c._loading = true;
    try { c._fromMap(this._toMap()); } finally { c._loading = false; }
    c._recomputeBinBw();
    return c;
  }

  /** Shared singleton (Preferences.instance). */
  static instance() {
    if (!Preferences._instance) Preferences._instance = new Preferences();
    return Preferences._instance;
  }

  // -------------------------------------------------------------------------
  // observable-property plumbing
  // -------------------------------------------------------------------------

  /**
   * Creates an observable, auto-saving property: a real change requests a save.
   * @template T
   * @param {T} initial
   * @returns {Property<T>}
   */
  _bound(initial) {
    const p = new Property(initial);
    p.addListener(() => this._requestSave());
    return p;
  }

  /** Wires a calibration entry's toggles to the debounced save (trackCalibration). */
  _trackCalibration(entry) {
    entry.active().addListener(() => this._requestSave());
    entry.withNoise().addListener(() => this._requestSave());
  }

  /** Per-backend prefs, lazily created on first access (prefsFor). */
  prefsFor(type) {
    let p = this._perBackend.get(type);
    if (!p) {
      p = new BackendPrefs();
      this._perBackend.set(type, p);
    }
    return p;
  }

  /** Shorthand for prefsFor(getBackend()) (current). */
  current() {
    return this.prefsFor(this.backend.get());
  }

  // -------------------------------------------------------------------------
  // calibration-list mutators (each requests a save)
  // -------------------------------------------------------------------------

  /** Appends an FFT calibration row and wires + persists it (addFftCalibration). */
  addFftCalibration(entry) {
    this.fftCalibrations.push(entry);
    this._trackCalibration(entry);
    this._requestSave();
  }

  /** Removes an FFT calibration row (removeFftCalibration). */
  removeFftCalibration(entry) {
    const i = this.fftCalibrations.indexOf(entry);
    if (i >= 0) {
      this.fftCalibrations.splice(i, 1);
      this._requestSave();
    }
  }

  /** Appends a FreqResp calibration row (addFreqRespCalibration). */
  addFreqRespCalibration(entry) {
    this.freqRespCalibrations.push(entry);
    this._trackCalibration(entry);
    this._requestSave();
  }

  /** Removes a FreqResp calibration row (removeFreqRespCalibration). */
  removeFreqRespCalibration(entry) {
    const i = this.freqRespCalibrations.indexOf(entry);
    if (i >= 0) {
      this.freqRespCalibrations.splice(i, 1);
      this._requestSave();
    }
  }

  /** Sets entry-0's FreqResp calibration path, creating row 0 if absent
   *  (setFreqRespPrimaryCalibrationPath). */
  setFreqRespPrimaryCalibrationPath(path) {
    if (this.freqRespCalibrations.length === 0) {
      this.addFreqRespCalibration(new CalibrationEntry());
    }
    this.freqRespCalibrations[0].path = path;
    this._requestSave();
  }

  // -------------------------------------------------------------------------
  // preset mutators (immediate save)
  // -------------------------------------------------------------------------

  /** Inserts/replaces an osc preset and persists (putOscPreset). */
  putOscPreset(name, preset) {
    if (!name || !preset) return;
    this.oscPresets.set(name, preset);
    this.save();
  }

  /** Removes the named osc preset and persists (removeOscPreset). */
  removeOscPreset(name) {
    if (name == null) return;
    if (this.oscPresets.delete(name)) this.save();
  }

  /** Inserts/replaces an FFT preset and persists (putFftPreset). */
  putFftPreset(name, preset) {
    if (!name || !preset) return;
    this.fftPresets.set(name, preset);
    this.save();
  }

  /** Removes the named FFT preset and persists (removeFftPreset). */
  removeFftPreset(name) {
    if (name == null) return;
    if (this.fftPresets.delete(name)) this.save();
  }

  /** Inserts/replaces a FreqResp preset and persists (putFreqRespPreset). */
  putFreqRespPreset(name, preset) {
    if (!name || !preset) return;
    this.freqRespPresets.set(name, preset);
    this.save();
  }

  /** Removes the named FreqResp preset and persists (removeFreqRespPreset). */
  removeFreqRespPreset(name) {
    if (name == null) return;
    if (this.freqRespPresets.delete(name)) this.save();
  }

  /** Current filter scalars for a type (getFreqRespFilterParams); fromType defaults if none. */
  getFreqRespFilterParams(type) {
    if (type == null) return FreqRespFilterTypeParams.fromType('LOW_PASS');
    const p = this.freqRespFilterParamsByType.get(type);
    return p != null ? p : FreqRespFilterTypeParams.fromType(type);
  }

  /** Writes back a type's filter scalars and persists (putFreqRespFilterParams). */
  putFreqRespFilterParams(type, params) {
    if (type == null || params == null) return;
    this.freqRespFilterParamsByType.set(type, params);
    this.save();
  }

  /** Serialises one FreqRespFilterTypeParams to its map - the one serialization
   *  shape, shared by the per-type map and every preset's filterParams. */
  _writeFilterParams(p) {
    return {
      modeOrder: p.modeOrder,
      rippleDb: p.rippleDb,
      stopAttenDb: p.stopAttenDb,
      centerHz: p.centerHz,
      passHz: p.passHz,
      stopHz: p.stopHz,
      orderPassHz: p.orderPassHz,
      orderRippleDb: p.orderRippleDb,
      order: p.order,
      q: p.q,
    };
  }

  /** Deserialises one FreqRespFilterTypeParams, seeded by type's defaults and clamped. */
  _readFilterParams(type, pm) {
    const p = FreqRespFilterTypeParams.fromType(type);
    if (isBool(pm.modeOrder)) p.modeOrder = pm.modeOrder;
    if (isNum(pm.rippleDb)) p.rippleDb = Math.max(0.001, Math.min(20.0, pm.rippleDb));
    if (isNum(pm.stopAttenDb)) p.stopAttenDb = Math.max(0.0, Math.min(200.0, pm.stopAttenDb));
    if (isNum(pm.centerHz)) p.centerHz = Math.max(0.0, pm.centerHz);
    if (isNum(pm.passHz)) p.passHz = Math.max(0.0, pm.passHz);
    if (isNum(pm.stopHz)) p.stopHz = Math.max(0.0, pm.stopHz);
    if (isNum(pm.orderPassHz)) p.orderPassHz = Math.max(0.0, pm.orderPassHz);
    if (isNum(pm.orderRippleDb)) p.orderRippleDb = Math.max(0.001, Math.min(20.0, pm.orderRippleDb));
    if (isNum(pm.order)) p.order = Math.max(1, Math.min(32, trunc(pm.order)));
    if (isNum(pm.q)) p.q = Math.max(0.1, Math.min(100.0, pm.q));
    return p;
  }

  // -------------------------------------------------------------------------
  // validated setters that have side effects in the Java original
  // -------------------------------------------------------------------------

  /** Sets ADC full-scale Vrms; rejects ≤0 and recomputes the dBV offset
   *  (setAdcFsVoltageRms). */
  setAdcFsVoltageRms(v) {
    if (!(v > 0.0)) return;
    this.adcFsVoltageRms.set(v);
    this._recomputeDbvOffset(v);
  }

  /** Sets the RIGHT ADC full-scale Vrms; rejects ≤0 and recomputes the right dBV
   *  offset (setAdcFsVoltageRmsRight). */
  setAdcFsVoltageRmsRight(v) {
    if (!(v > 0.0)) return;
    this.adcFsVoltageRmsRight.set(v);
    this._recomputeDbvOffsetRight(v);
  }

  /** Sets DAC full-scale peak amplitude; rejects ≤0 (setDacFsVoltageAmpl). */
  setDacFsVoltageAmpl(v) {
    if (!(v > 0.0)) return;
    this.dacFsVoltageAmpl.set(v);
  }

  /** Sets the RIGHT DAC full-scale peak amplitude; rejects ≤0 (setDacFsVoltageAmplRight). */
  setDacFsVoltageAmplRight(v) {
    if (!(v > 0.0)) return;
    this.dacFsVoltageAmplRight.set(v);
  }

  /** The ADC full-scale RMS voltage of {@code ch}: 'R' -> the right scalar, else the
   *  left / LINKED / legacy scalar (Preferences.getAdcFsVoltageRms(Channel)). */
  getAdcFsVoltageRms(ch = 'L') {
    return ch === 'R' ? this.adcFsVoltageRmsRight.get() : this.adcFsVoltageRms.get();
  }

  /** The ±full-scale PEAK volts of {@code ch} (= fs(ch)·√2) -
   *  Preferences.getAdcPeakVolts(Channel). */
  getAdcPeakVolts(ch = 'L') {
    return this.getAdcFsVoltageRms(ch) * SQRT2;
  }

  /** The cached dBV↔dBFS offset of {@code ch}: 'R' -> dbvOffsetDbRight, else
   *  dbvOffsetDb (Preferences.getDbvOffsetDb(Channel)). */
  getDbvOffsetDb(ch = 'L') {
    return ch === 'R' ? this.dbvOffsetDbRight : this.dbvOffsetDb;
  }

  /** The DAC full-scale PEAK amplitude of {@code ch}: 'R' -> the right scalar, else
   *  the left / MONO-mirror / legacy scalar (Preferences.getDacFsVoltageAmpl(Channel)). */
  getDacFsVoltageAmpl(ch = 'L') {
    return ch === 'R' ? this.dacFsVoltageAmplRight.get() : this.dacFsVoltageAmpl.get();
  }

  /** The per-lane RIGHT output scale - fsLeft/fsRight so a card with distinct DAC
   *  full-scales emits the same physical level on both lanes; 1.0 when the right
   *  full-scale is non-positive (Preferences.dacRightLaneScale). */
  dacRightLaneScale() {
    const leftFs = this.dacFsVoltageAmpl.get();
    const rightFs = this.dacFsVoltageAmplRight.get();
    return rightFs > 0.0 ? leftFs / rightFs : 1.0;
  }

  /** The .dpd path matching {@code form} (getGenDpd(form)). */
  getGenDpd(form) {
    return DUAL_TONE_FORMS.has(form) ? this.genDpdDual.get() : this.genDpd.get();
  }

  /** Stores {@code path} under the .dpd slot matching {@code form} (setGenDpd). */
  setGenDpd(form, path) {
    if (DUAL_TONE_FORMS.has(form)) this.genDpdDual.set(path);
    else this.genDpd.set(path);
  }

  /** The original .dpd basename matching {@code form} (shown in the corrections row). */
  getGenDpdName(form) {
    return DUAL_TONE_FORMS.has(form) ? this.genDpdDualName.get() : this.genDpdName.get();
  }

  /** Stores the original .dpd basename under the slot matching {@code form}. */
  setGenDpdName(form, name) {
    if (DUAL_TONE_FORMS.has(form)) this.genDpdDualName.set(name);
    else this.genDpdName.set(name);
  }

  // -------------------------------------------------------------------------
  // cached-constant recompute + dBFS conversion
  // -------------------------------------------------------------------------

  /** Recomputes dbvOffsetDb; non-positive full-scale falls back to 0 dB
   *  (recomputeDbvOffset / dbvOffsetFor). */
  _recomputeDbvOffset(fsVrms) {
    this.dbvOffsetDb = (fsVrms > 0.0) ? 20.0 * Math.log10(fsVrms) : 0.0;
  }

  /** Recomputes dbvOffsetDbRight; non-positive full-scale falls back to 0 dB
   *  (the right sibling of _recomputeDbvOffset / dbvOffsetFor). */
  _recomputeDbvOffsetRight(fsVrms) {
    this.dbvOffsetDbRight = (fsVrms > 0.0) ? 20.0 * Math.log10(fsVrms) : 0.0;
  }

  /** Recomputes binBwSqrt from the live capture config (recomputeBinBw). */
  _recomputeBinBw() {
    const rate = this.current().inputSampleRate;
    const len = this.fftLength.get();
    this.binBwSqrt = (rate > 0 && len > 0) ? Math.sqrt(rate / len) : 1.0;
  }

  /**
   * Converts an analyser dBFS magnitude into {@code unit} for display
   * (convertFromDbFs). dBV is a constant offset from dBFS; V reads that value
   * linearly; V/√Hz additionally divides by √(bin bandwidth).
   *
   * @param {number} dbFs
   * @param {string} unit  MagnitudeUnit name (V | V_SQRT_HZ | DBV | DBFS).
   * @param {?number} [binBwSqrt=null]  √(bin bandwidth) of the spectrum being
   *        converted; null uses the cached live config.
   * @param {string} [ch='L']  the analysed channel: 'R' uses dbvOffsetDbRight, else
   *        the left / LINKED / legacy offset (Preferences.convertFromDbFs(..., Channel)).
   * @returns {number}
   */
  convertFromDbFs(dbFs, unit, binBwSqrt = null, ch = 'L') {
    const off = this.getDbvOffsetDb(ch);
    switch (unit) {
      case 'DBFS': return dbFs;
      case 'DBR': return dbFs - this.fftDbrRefDbFs;
      case 'DBV': return dbFs + off;
      case 'V': return Math.pow(10.0, (dbFs + off) / 20.0);
      case 'V_SQRT_HZ':
        return Math.pow(10.0, (dbFs + off) / 20.0)
          / (binBwSqrt != null ? binBwSqrt : this.binBwSqrt);
      default: return dbFs;
    }
  }

  // -------------------------------------------------------------------------
  // persistence
  // -------------------------------------------------------------------------

  /**
   * Registers a component-owned preference block, and immediately hands it whatever
   * the loaded document held under its key(). That replay is the point: backend
   * managers are built lazily, so they register long after load() ran and would
   * otherwise never see their saved values. Registering the same key twice replaces
   * the earlier entry (a manager rebuilt after a backend switch is not a second
   * block). Mirrors Preferences.registerCustomPreferences.
   *
   * @param {?import('../qa40x/qa40x-preferences.js').SubPreferences} sub
   */
  registerCustomPreferences(sub) {
    if (sub == null) return;
    this._customPrefs.set(sub.key(), sub);
    const stored = this._customRaw.get(sub.key());
    if (asMap(stored)) sub.fromMap(stored);
  }

  /** Seeds every registered block's edit values from its live ones - the Preferences
   *  dialog calls this as it opens, so an edit abandoned by a previous Cancel cannot
   *  leak into this session (beginCustomPreferencesEdit). */
  beginCustomPreferencesEdit() {
    for (const sub of this._customPrefs.values()) sub.beginEdit();
  }

  /** Commits every registered block's edit values into its live ones - called only
   *  when the Preferences dialog is closed with OK, alongside the ordinary
   *  apply-from-dialog and before the save (commitCustomPreferencesEdit). */
  commitCustomPreferencesEdit() {
    for (const sub of this._customPrefs.values()) sub.commitEdit();
  }

  /** Persists after a bound change - no-op while loading; debounced into a
   *  single write SAVE_COALESCE_MS after the last change (requestSave). */
  _requestSave() {
    if (this.transientMode || this._loading) return;
    if (this._pendingSaveTimer != null) clearTimeout(this._pendingSaveTimer);
    this._pendingSaveTimer = setTimeout(() => {
      this._pendingSaveTimer = null;
      this.save();
    }, SAVE_COALESCE_MS);
  }

  /** Flushes a still-pending debounced save immediately (flush). Hook this to
   *  'pagehide'/'beforeunload' so a last-instant change is not lost. */
  flush() {
    if (this._pendingSaveTimer != null) {
      clearTimeout(this._pendingSaveTimer);
      this._pendingSaveTimer = null;
      this.save();
    }
  }

  /** Writes the current preferences to localStorage. No-op in transient mode
   *  (save). */
  save() {
    if (this.transientMode) return;
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(this._toMap()));
    } catch (e) {
      // Quota / disabled storage - match the desktop "log and continue".
      console.warn('Failed to save preferences:', e && e.message);
    }
  }

  /** Loads preferences from localStorage if present; no-op if missing/unreadable
   *  (load). */
  load() {
    let raw;
    try {
      raw = localStorage.getItem(PREFS_KEY);
    } catch (e) {
      return;
    }
    if (raw == null) return;
    let root;
    try {
      root = JSON.parse(raw);
    } catch (e) {
      quarantineStoreEntry(PREFS_KEY, raw, e.toString());
      return;
    }
    if (!asMap(root)) {
      quarantineStoreEntry(PREFS_KEY, raw, 'its content is not a map (empty or truncated entry)');
      return;
    }
    this._loading = true;
    try {
      this._fromMap(root);
    } finally {
      this._loading = false;
    }
  }

  /**
   * The LIVE document as a plain object - exactly what {@link #save} would write, without
   * writing it. The JSON config editor's read half (store/config-port.js), and the only
   * public way to obtain it.
   *
   * @returns {Object} a fresh plain object; mutating it does not touch these preferences
   */
  configDocument() {
    return this._toMap();
  }

  /**
   * Applies an edited document to the LIVE preferences and persists it - the JSON config
   * editor's write half (store/config-port.js).
   *
   * DELIBERATELY NOT {@link #load}. load() reads the document from localStorage, so applying
   * an edit through it would round-trip the text through storage and hand the live object
   * whatever storage echoed back - a save failure (quota, private mode) would then silently
   * discard the edit while the dialog reported success. This takes the document it was given.
   *
   * `_loading` is raised for the same reason {@link #copyForDialog} raises it: it suppresses
   * only the DEBOUNCED auto-save, so setting several hundred properties costs one write
   * instead of a storm of them. It does not suppress change notification, so every consumer
   * still reacts as the values land - which is what makes the edit take effect live.
   *
   * @param {Object} root the parsed document, already known to be valid JSON
   */
  applyConfigDocument(root) {
    this._loading = true;
    try {
      this._fromMap(root);
    } finally {
      this._loading = false;
    }
    this.save();
  }

  /** Builds the serialisable document, mirroring Preferences.toMap key-for-key
   *  (including the conditional omissions and colour-hex formatting). */
  _toMap() {
    const root = {};
    root.formatVersion = PREFERENCES_FORMAT_VERSION;
    root.backend = this.backend.get();
    if (this.uiLanguage.get() != null) root.uiLanguage = this.uiLanguage.get();
    root.tabOrientation = this.tabOrientation.get();
    root.uiFontNormal = this.uiFontNormal.get();
    root.uiFontBold = this.uiFontBold.get();
    root.uiFontChannel = this.uiFontChannel.get();
    root.activeTabIndex = this.activeTabIndex.get();
    root.smallIconsInMainTab = this.smallIconsInMainTab.get();
    root.checkForUpdatesOnStartup = this.checkForUpdatesOnStartup.get();
    root.includeBetaInUpdateChecks = this.includeBetaInUpdateChecks.get();
    root.showTipsAtStartup = this.showTipsAtStartup.get();
    root.windowWidth = this.windowWidth.get();
    root.windowHeight = this.windowHeight.get();
    if (this.genPaneWidth.get() > 0) root.genPaneWidth = this.genPaneWidth.get();
    if (this.multiVSplitWeights != null) {
      root.multiVSplitWeights = Array.from(this.multiVSplitWeights);
    }
    root.genPaneCollapsed = this.genPaneCollapsed.get();
    root.oscPaneCollapsed = this.oscPaneCollapsed.get();
    root.fftPaneCollapsed = this.fftPaneCollapsed.get();
    root.oscLeftChannelEnabled = this.oscLeftChannelEnabled.get();
    root.oscRightChannelEnabled = this.oscRightChannelEnabled.get();
    root.oscLeftAcMode = this.oscLeftAcMode.get();
    root.oscRightAcMode = this.oscRightAcMode.get();
    root.oscLeftVoltsPerDiv = this.oscLeftVoltsPerDiv.get();
    root.oscRightVoltsPerDiv = this.oscRightVoltsPerDiv.get();
    root.oscTimePerDiv = this.oscTimePerDiv.get();
    root.scopeScreenshotWidth = this.scopeScreenshotWidth.get();
    root.scopeScreenshotHeight = this.scopeScreenshotHeight.get();
    root.oscTriggerChannel = this.oscTriggerChannel.get();
    root.oscTriggerEdge = this.oscTriggerEdge.get();
    root.oscTriggerType = this.oscTriggerType.get();
    root.oscTriggerMode = this.oscTriggerMode.get();
    root.oscTriggerHysteresisDiv = this.oscTriggerHysteresisDiv.get();
    root.oscTriggerHysteresisEnabled = this.oscTriggerHysteresisEnabled.get();
    root.oscShowReconstructedBeat = this.oscShowReconstructedBeat.get();
    root.oscLeftSincInterpEnabled = this.oscLeftSincInterpEnabled.get();
    root.oscRightSincInterpEnabled = this.oscRightSincInterpEnabled.get();
    root.oscLeftResidualEnabled = this.oscLeftResidualEnabled.get();
    root.oscRightResidualEnabled = this.oscRightResidualEnabled.get();
    root.oscLeftMainsSuppression = this.oscLeftMainsSuppression.get();
    root.oscRightMainsSuppression = this.oscRightMainsSuppression.get();
    root.oscLeftLpf = this.oscLeftLpf.get();
    root.oscRightLpf = this.oscRightLpf.get();
    root.oscLeftOffsetFrac = this.oscLeftOffsetFrac.get();
    root.oscRightOffsetFrac = this.oscRightOffsetFrac.get();
    root.oscTriggerLevelFrac = this.oscTriggerLevelFrac.get();
    root.oscTriggerPositionFrac = this.oscTriggerPositionFrac.get();
    root.oscMeasurementAverageSeconds = this.oscMeasurementAverageSeconds.get();
    root.oscMeasurementChannel = this.oscMeasurementChannel.get();
    root.oscShowStats = this.oscShowStats.get();
    root.oscShowHistogram = this.oscShowHistogram.get();
    root.oscHistogramBins = this.oscHistogramBins.get();
    root.oscHistogramChannel = this.oscHistogramChannel.get();
    root.oscShowMeasurementTable = this.oscShowMeasurementTable.get();
    // NOT WRITTEN - the deprecated shared full-scale scalars (adcFsVoltageRms / dacFsVoltageRms)
    // are RUNTIME-ONLY since 1.2 (Java Preferences.toMap): they are the fallback for a
    // device with no calibrated card, and a card that HAS its own calibration pushes its values
    // into the very same scalars (applyInputDeviceProfile and siblings). Persisting them would
    // therefore save the last selected card's calibration as the machine-wide default and hand it
    // to the next uncalibrated device as if it were measured there. fromMap() still READS the
    // keys so a pre-1.2 store seeds the fallback once; nothing writes them back, so the entry
    // disappears with the first save.
    root.genSignalForm = this.genSignalForm.get();
    root.genFrequencyHz = this.genFrequencyHz.get();
    root.genDualToneFreq1Hz = this.genDualToneFreq1Hz.get();
    root.genDualToneFreq2Hz = this.genDualToneFreq2Hz.get();
    root.genDualToneSplitPct = this.genDualToneSplitPct.get();
    root.genAmplitudeVrms = this.genAmplitudeVrms.get();
    root.genAmplitudeDbvDisplay = this.genAmplitudeDbvDisplay.get();
    root.genDitherBits = this.genDitherBits.get();
    root.genDitherDbvDisplay = this.genDitherDbvDisplay.get();
    root.genOutputChannels = this.genOutputChannels.get();   // persisted by enum name (Java toMap)
    if (this.genDpd.get() != null) root.genDpd = this.genDpd.get();
    if (this.genDpdDual.get() != null) root.genDpdDual = this.genDpdDual.get();
    if (this.genDpdFolder.get() != null) root.genDpdFolder = this.genDpdFolder.get();
    if (this.genDpdName.get() != null) root.genDpdName = this.genDpdName.get();
    if (this.genDpdDualName.get() != null) root.genDpdDualName = this.genDpdDualName.get();
    root.predistortionAverages = this.predistortionAverages.get();
    root.predistortionTargetPct = this.predistortionTargetPct.get();
    root.genRectangleDuty = this.genRectangleDuty.get();
    root.genTriangleDuty = this.genTriangleDuty.get();
    root.genSweepFreqStartHz = this.genSweepFreqStartHz.get();
    root.genSweepFreqEndHz = this.genSweepFreqEndHz.get();
    root.genSweepDurationSec = this.genSweepDurationSec.get();
    root.genSweepLoop = this.genSweepLoop.get();
    root.genSweepFadeInSec = this.genSweepFadeInSec.get();
    root.genSweepFadeOutSec = this.genSweepFadeOutSec.get();
    root.genSnapToFftBin = this.genSnapToFftBin.get();
    root.genWavDurationSeconds = this.genWavDurationSeconds.get();
    if (this.genWavPath.get() != null) root.genWavPath = this.genWavPath.get();
    if (this.genWavFolder.get() != null) root.genWavFolder = this.genWavFolder.get();
    if (this.genPlayFromPath.get() != null) root.genPlayFromPath = this.genPlayFromPath.get();
    if (this.genPlayFromFolder.get() != null) root.genPlayFromFolder = this.genPlayFromFolder.get();
    root.genPlayFromLoop = this.genPlayFromLoop.get();
    if (this.oscSavePath.get() != null) root.oscSavePath = this.oscSavePath.get();
    if (this.oscSaveFolder.get() != null) root.oscSaveFolder = this.oscSaveFolder.get();
    root.oscSaveDurationSeconds = this.oscSaveDurationSeconds.get();
    if (this.oscPlayFromPath.get() != null) root.oscPlayFromPath = this.oscPlayFromPath.get();
    if (this.oscPlayFromFolder.get() != null) root.oscPlayFromFolder = this.oscPlayFromFolder.get();
    root.oscPlayFromLoop = this.oscPlayFromLoop.get();
    root.oscLineWidth = this.oscLineWidth.get();
    root.oscDotDiameter = this.oscDotDiameter.get();
    root.oscPersistenceMode = this.oscPersistenceMode.get();
    root.oscPersistenceManualSeconds = this.oscPersistenceManualSeconds.get();
    root.oscLeftChannelColor = formatHtmlColor(this.oscLeftChannelColor.get());
    root.oscRightChannelColor = formatHtmlColor(this.oscRightChannelColor.get());
    if (this.screenshotFolder.get() != null) root.screenshotFolder = this.screenshotFolder.get();
    if (this.screenshotCommentFont.get() != null) root.screenshotCommentFont = this.screenshotCommentFont.get();

    if (this.oscPresets.size > 0) {
      const presetsMap = {};
      for (const [key, p] of this.oscPresets) {
        presetsMap[key] = {
          leftChannelEnabled: p.leftChannelEnabled,
          rightChannelEnabled: p.rightChannelEnabled,
          leftAcMode: p.leftAcMode,
          rightAcMode: p.rightAcMode,
          leftSincInterpEnabled: p.leftSincInterpEnabled,
          rightSincInterpEnabled: p.rightSincInterpEnabled,
          leftResidualEnabled: p.leftResidualEnabled,
          rightResidualEnabled: p.rightResidualEnabled,
          leftMainsSuppression: p.leftMainsSuppression,
          rightMainsSuppression: p.rightMainsSuppression,
          leftLpf: p.leftLpf,
          rightLpf: p.rightLpf,
          leftVoltsPerDiv: p.leftVoltsPerDiv,
          rightVoltsPerDiv: p.rightVoltsPerDiv,
          leftOffsetFrac: p.leftOffsetFrac,
          rightOffsetFrac: p.rightOffsetFrac,
          timePerDiv: p.timePerDiv,
          triggerPositionFrac: p.triggerPositionFrac,
          triggerChannel: p.triggerChannel,
          triggerEdge: p.triggerEdge,
          triggerType: p.triggerType,
          triggerMode: p.triggerMode,
          triggerLevelFrac: p.triggerLevelFrac,
        };
      }
      root.oscPresets = presetsMap;
    }

    // ---- FFT pane state ----
    root.fftLength = this.fftLength.get();
    // The averages series ends in the forever entry, and JSON has no Infinity -
    // JSON.stringify would silently write null, which the loader ignores, so the one
    // series entry that never survived a reload was the forever averaging. Round-trip
    // it as the string 'Infinity' instead.
    {
      const avg = this.fftAverages.get();
      root.fftAverages = Number.isFinite(avg) ? avg : 'Infinity';
    }
    root.fftThreads = this.fftThreads.get();
    root.fftStopAfterNEnabled = this.fftStopAfterNEnabled.get();
    root.fftStopAfterN = this.fftStopAfterN.get();
    root.fftFundFromGenerator = this.fftFundFromGenerator.get();
    root.fftLogFreqAxis = this.fftLogFreqAxis.get();
    root.fftDetectTimeDiscontinuity = this.fftDetectTimeDiscontinuity.get();
    root.fftWindow = this.fftWindow.get();
    root.fftOverlap = this.fftOverlap.get();
    root.fftCoherentAveraging = this.fftCoherentAveraging.get();
    root.fftMainsSuppression = this.fftMainsSuppression.get();
    root.fftAlignGenerator = this.fftAlignGenerator.get();
    root.fftDistMinHz = this.fftDistMinHz.get();
    root.fftDistMaxHz = this.fftDistMaxHz.get();
    root.fftDistMinEnabled = this.fftDistMinEnabled.get();
    root.fftDistMaxEnabled = this.fftDistMaxEnabled.get();
    root.fftThdMaxHarmonic = this.fftThdMaxHarmonic.get();
    root.fftCalcMaxHarmonic = this.fftCalcMaxHarmonic.get();
    root.fftStrongToneRelDb = this.fftStrongToneRelDb.get();
    root.fftManualFundVrms = this.fftManualFundVrms.get();
    root.fftManualFundDbvDisplay = this.fftManualFundDbvDisplay.get();
    root.fftManualFundEnabled = this.fftManualFundEnabled.get();
    root.fftChannel = this.fftChannel.get();
    root.fftMagUnit = this.fftMagUnit.get();
    root.fftDistortionUnit = this.fftDistortionUnit.get();
    if (this.fftScreenshotWidth.get() > 0) root.fftScreenshotWidth = this.fftScreenshotWidth.get();
    if (this.fftScreenshotHeight.get() > 0) root.fftScreenshotHeight = this.fftScreenshotHeight.get();
    root.fftDistortionTableVisible = this.fftDistortionTableVisible.get();
    root.fftFreqMinHz = this.fftFreqMinHz.get();
    root.fftFreqMaxHz = this.fftFreqMaxHz.get();
    root.fftMagTop = this.fftMagTop.get();
    root.fftMagBottom = this.fftMagBottom.get();
    if (this.fftSavePath.get() != null) root.fftSavePath = this.fftSavePath.get();
    if (this.fftSaveFolder.get() != null) root.fftSaveFolder = this.fftSaveFolder.get();
    if (this.fftLoadPath.get() != null) root.fftLoadPath = this.fftLoadPath.get();
    if (this.fftLoadFolder.get() != null) root.fftLoadFolder = this.fftLoadFolder.get();
    if (this.fftCalibrations.length > 0) {
      root.fftCalibrations = this.fftCalibrations.map((e) => {
        const m = {};
        if (e.path != null) m.path = e.path;
        if (e.hash != null) m.hash = e.hash;
        m.active = e.active().get();
        m.withNoise = e.withNoise().get();
        return m;
      });
    }
    root.fftBeforeCalDotColor = this.fftBeforeCalDotColor.get();
    root.fftCalOverlayColor = this.fftCalOverlayColor.get();
    root.fftLineWidth = this.fftLineWidth.get();
    root.freqRespLineWidth = this.freqRespLineWidth.get();
    root.fftHarmonicDotDiameter = this.fftHarmonicDotDiameter.get();
    root.fftLineColor = formatHtmlColor(this.fftLineColor.get());
    root.fftChartBackgroundColor = formatHtmlColor(this.fftChartBackgroundColor.get());
    root.fftHarmonicDotColor = formatHtmlColor(this.fftHarmonicDotColor.get());
    root.fftFreqRespColor = formatHtmlColor(this.fftFreqRespColor.get());

    // ---- Frequency Response pane ----
    root.freqRespStartHz = this.freqRespStartHz.get();
    root.freqRespStopHz = this.freqRespStopHz.get();
    root.freqRespAmplitudeVrms = this.freqRespAmplitudeVrms.get();
    root.freqRespAmplitudeDbvDisplay = this.freqRespAmplitudeDbvDisplay.get();
    root.freqRespSweepPoints = this.freqRespSweepPoints.get();
    root.freqRespDurationSec = this.freqRespDurationSec.get();
    root.freqRespFftSize = this.freqRespFftSize.get();
    root.freqRespDitherBits = this.freqRespDitherBits.get();
    root.freqRespLeadInSec = this.freqRespLeadInSec.get();
    root.freqRespOutputChannels = this.freqRespOutputChannels.get();   // persisted by enum name (Java toMap)
    root.tuneNotchStartHz = this.tuneNotchStartHz.get();
    root.tuneNotchStopHz = this.tuneNotchStopHz.get();
    root.tuneNotchAmplitudeVrms = this.tuneNotchAmplitudeVrms.get();
    root.tuneNotchTargetHz = this.tuneNotchTargetHz.get();
    root.tuneNotchOutputChannels = this.tuneNotchOutputChannels.get();   // persisted by enum name (Java toMap)
    root.freqRespLeftVisible = this.freqRespLeftVisible.get();
    root.freqRespRightVisible = this.freqRespRightVisible.get();
    root.freqRespPhaseVisible = this.freqRespPhaseVisible.get();
    root.freqRespFreqMinHz = this.freqRespFreqMinHz.get();
    root.freqRespFreqMaxHz = this.freqRespFreqMaxHz.get();
    root.freqRespMagTopDb = this.freqRespMagTopDb.get();
    root.freqRespMagBotDb = this.freqRespMagBotDb.get();
    root.freqRespNyquistFraction = this.freqRespNyquistFraction.get();
    root.freqRespCompareSmoothWindow = this.freqRespCompareSmoothWindow.get();
    root.freqRespNotchEnabled = this.freqRespNotchEnabled.get();
    root.freqRespNotchBaseHz = this.freqRespNotchBaseHz.get();
    root.freqRespSignalColor = this.freqRespSignalColor.get();
    root.freqRespPhaseColor = this.freqRespPhaseColor.get();
    root.freqRespReferenceColor = this.freqRespReferenceColor.get();
    root.freqRespBackgroundColor = this.freqRespBackgroundColor.get();
    // freqRespShowRiaa is intentionally NOT persisted (fresh-session default).
    root.freqRespReverseRiaa = this.freqRespReverseRiaa.get();
    root.freqRespIecAmendment = this.freqRespIecAmendment.get();
    root.freqRespCompareMode = this.freqRespCompareMode.get();
    // freqRespShowFilter is intentionally NOT persisted (fresh-session default).
    root.freqRespFilterCompare = this.freqRespFilterCompare.get();
    root.freqRespFilterType = this.freqRespFilterType.get();
    root.freqRespFilterResponse = this.freqRespFilterResponse.get();
    root.freqRespUnevenMode = this.freqRespUnevenMode.get();
    root.freqRespUnevenNotch = this.freqRespUnevenNotch.get();
    root.freqRespUnevenDb = this.freqRespUnevenDb.get();
    root.freqRespUnevenStartHz = this.freqRespUnevenStartHz.get();
    root.freqRespUnevenStopHz = this.freqRespUnevenStopHz.get();
    root.freqRespApplyCalibration = this.freqRespApplyCalibration.get();
    if (this.freqRespCalibrations.length > 0) {
      root.freqRespCalibrations = this.freqRespCalibrations.map((e) => {
        const m = {};
        if (e.path != null) m.path = e.path;
        if (e.hash != null) m.hash = e.hash;
        m.active = e.active().get();
        return m;
      });
    }
    if (this.freqRespSaveFolder.get() != null) root.freqRespSaveFolder = this.freqRespSaveFolder.get();
    if (this.freqRespSavePath.get() != null) root.freqRespSavePath = this.freqRespSavePath.get();
    if (this.freqRespLoadFolder.get() != null) root.freqRespLoadFolder = this.freqRespLoadFolder.get();
    if (this.freqRespLoadPath.get() != null) root.freqRespLoadPath = this.freqRespLoadPath.get();
    root.freqRespActiveTabIndex = this.freqRespActiveTabIndex.get();
    if (this.freqRespScreenshotWidth.get() > 0) root.freqRespScreenshotWidth = this.freqRespScreenshotWidth.get();
    if (this.freqRespScreenshotHeight.get() > 0) root.freqRespScreenshotHeight = this.freqRespScreenshotHeight.get();

    if (this.fftPresets.size > 0) {
      const fpMap = {};
      for (const [key, p] of this.fftPresets) {
        fpMap[key] = {
          channel: p.channel,
          magUnit: p.magUnit,
          logFreqAxis: p.logFreqAxis,
          freqMinHz: p.freqMinHz,
          freqMaxHz: p.freqMaxHz,
          magTop: p.magTop,
          magBottom: p.magBottom,
          fftLength: p.fftLength,
          averages: p.averages,
          stopAfterNEnabled: p.stopAfterNEnabled,
          stopAfterN: p.stopAfterN,
          fundFromGenerator: p.fundFromGenerator,
          window: p.window,
          overlap: p.overlap,
          coherentAveraging: p.coherentAveraging,
          distMinHz: p.distMinHz,
          distMaxHz: p.distMaxHz,
          distMinEnabled: p.distMinEnabled,
          distMaxEnabled: p.distMaxEnabled,
          thdMaxHarmonic: p.thdMaxHarmonic,
          calcMaxHarmonic: p.calcMaxHarmonic,
          manualFundVrms: p.manualFundVrms,
          manualFundDbvDisplay: p.manualFundDbvDisplay,
          manualFundEnabled: p.manualFundEnabled,
        };
      }
      root.fftPresets = fpMap;
    }

    if (this.freqRespPresets.size > 0) {
      const frMap = {};
      for (const [key, p] of this.freqRespPresets) {
        frMap[key] = {
          startHz: p.startHz,
          stopHz: p.stopHz,
          amplitudeVrms: p.amplitudeVrms,
          sweepPoints: p.sweepPoints,
          fftSize: p.fftSize,
          leadInSec: p.leadInSec,
          ditherBits: p.ditherBits,
          showRiaa: p.showRiaa,
          reverseRiaa: p.reverseRiaa,
          iecAmendment: p.iecAmendment,
          compareMode: p.compareMode,
          showFilter: p.showFilter,
          filterCompare: p.filterCompare,
          filterType: p.filterType,
          filterResponse: p.filterResponse,
          filterParams: this._writeFilterParams(p.filterParams),
          unevenMode: p.unevenMode,
          unevenNotch: p.unevenNotch,
          unevenDb: p.unevenDb,
          unevenStartHz: p.unevenStartHz,
          unevenStopHz: p.unevenStopHz,
        };
      }
      root.freqRespPresets = frMap;
    }

    if (this.freqRespFilterParamsByType.size > 0) {
      const fptMap = {};
      for (const [type, v] of this.freqRespFilterParamsByType) {
        fptMap[type] = this._writeFilterParams(v);
      }
      root.freqRespFilterParamsByType = fptMap;
    }

    const perBackendMap = {};
    for (const [type, v] of this._perBackend) {
      perBackendMap[type] = {
        inputDeviceName: v.inputDeviceName,
        outputDeviceName: v.outputDeviceName,
        inputSampleRate: v.inputSampleRate,
        inputBitDepth: v.inputBitDepth,
        outputSampleRate: v.outputSampleRate,
        outputBitDepth: v.outputBitDepth,
      };
    }
    root.perBackend = perBackendMap;
    // Component-owned blocks. Start from what the document held so an extension that
    // never registered this session (its backend was never selected) keeps its saved
    // settings instead of losing them on the next save.
    const customMap = Object.fromEntries(this._customRaw);
    for (const sub of this._customPrefs.values()) {
      customMap[sub.key()] = sub.toMap();
    }
    if (Object.keys(customMap).length > 0) root.custom = customMap;
    return root;
  }

  /** Applies a loaded document, mirroring Preferences.fromMap field-for-field
   *  (same type gates, same clamps/migrations, same colour parsing). */
  _fromMap(root) {
    const g = (k) => root[k];

    // Component-owned blocks: keep the raw section so a block whose owner has not
    // registered yet (or never will this session) survives the next save, and feed
    // anything already registered right away.
    this._customRaw.clear();
    if (asMap(g('custom'))) {
      for (const [key, block] of Object.entries(g('custom'))) {
        this._customRaw.set(key, block);
        const sub = this._customPrefs.get(key);
        if (sub != null && asMap(block)) sub.fromMap(block);
      }
    }

    if (isStr(g('uiLanguage'))) this.uiLanguage.set(g('uiLanguage'));
    if (isStr(g('tabOrientation'))) this.tabOrientation.set(enumOr('TabOrientation', g('tabOrientation'), this.tabOrientation.get()));
    if (isStr(g('uiFontNormal'))) this.uiFontNormal.set(g('uiFontNormal'));
    if (isStr(g('uiFontBold'))) this.uiFontBold.set(g('uiFontBold'));
    if (isStr(g('uiFontChannel'))) this.uiFontChannel.set(g('uiFontChannel'));
    if (isNum(g('activeTabIndex'))) this.activeTabIndex.set(trunc(g('activeTabIndex')));
    if (isBool(g('smallIconsInMainTab'))) this.smallIconsInMainTab.set(g('smallIconsInMainTab'));
    if (isBool(g('checkForUpdatesOnStartup'))) this.checkForUpdatesOnStartup.set(g('checkForUpdatesOnStartup'));
    if (isBool(g('includeBetaInUpdateChecks'))) this.includeBetaInUpdateChecks.set(g('includeBetaInUpdateChecks'));
    if (isBool(g('showTipsAtStartup'))) this.showTipsAtStartup.set(g('showTipsAtStartup'));
    if (isStr(g('backend'))) this.backend.set(backendKeyOr(g('backend'), this.backend.get()));
    if (isNum(g('windowWidth'))) this.windowWidth.set(trunc(g('windowWidth')));
    if (isNum(g('windowHeight'))) this.windowHeight.set(trunc(g('windowHeight')));
    if (isNum(g('genPaneWidth'))) this.genPaneWidth.set(trunc(g('genPaneWidth')));
    if (Array.isArray(g('multiVSplitWeights'))) this.multiVSplitWeights = this._listToIntArray(g('multiVSplitWeights'));
    if (isBool(g('genPaneCollapsed'))) this.genPaneCollapsed.set(g('genPaneCollapsed'));
    if (isBool(g('oscPaneCollapsed'))) this.oscPaneCollapsed.set(g('oscPaneCollapsed'));
    if (isBool(g('fftPaneCollapsed'))) this.fftPaneCollapsed.set(g('fftPaneCollapsed'));
    if (isBool(g('oscLeftChannelEnabled'))) this.oscLeftChannelEnabled.set(g('oscLeftChannelEnabled'));
    if (isBool(g('oscLeftAcMode'))) this.oscLeftAcMode.set(g('oscLeftAcMode'));
    if (isBool(g('oscRightAcMode'))) this.oscRightAcMode.set(g('oscRightAcMode'));
    if (isBool(g('oscRightChannelEnabled'))) this.oscRightChannelEnabled.set(g('oscRightChannelEnabled'));
    if (isNum(g('oscLeftVoltsPerDiv'))) this.oscLeftVoltsPerDiv.set(g('oscLeftVoltsPerDiv'));
    if (isNum(g('oscRightVoltsPerDiv'))) this.oscRightVoltsPerDiv.set(g('oscRightVoltsPerDiv'));
    if (isNum(g('oscTimePerDiv'))) this.oscTimePerDiv.set(g('oscTimePerDiv'));
    // Scope screenshot size: new scope* key, migrating the legacy osc* and the even
    // older shared screenshot* keys so saved prefs still restore the scope's size.
    if (isNum(g('scopeScreenshotWidth'))) this.scopeScreenshotWidth.set(trunc(g('scopeScreenshotWidth')));
    else if (isNum(g('oscScreenshotWidth'))) this.scopeScreenshotWidth.set(trunc(g('oscScreenshotWidth')));
    else if (isNum(g('screenshotWidth'))) this.scopeScreenshotWidth.set(trunc(g('screenshotWidth')));
    if (isNum(g('scopeScreenshotHeight'))) this.scopeScreenshotHeight.set(trunc(g('scopeScreenshotHeight')));
    else if (isNum(g('oscScreenshotHeight'))) this.scopeScreenshotHeight.set(trunc(g('oscScreenshotHeight')));
    else if (isNum(g('screenshotHeight'))) this.scopeScreenshotHeight.set(trunc(g('screenshotHeight')));
    if (isStr(g('oscTriggerChannel'))) this.oscTriggerChannel.set(enumOr('Channel', g('oscTriggerChannel'), this.oscTriggerChannel.get()));
    if (isStr(g('oscTriggerEdge'))) this.oscTriggerEdge.set(enumOr('TriggerEdge', g('oscTriggerEdge'), this.oscTriggerEdge.get()));
    if (isStr(g('oscTriggerType'))) this.oscTriggerType.set(enumOr('TriggerType', g('oscTriggerType'), this.oscTriggerType.get()));
    if (isStr(g('oscTriggerMode'))) this.oscTriggerMode.set(enumOr('TriggerMode', g('oscTriggerMode'), this.oscTriggerMode.get()));
    if (isNum(g('oscTriggerHysteresisDiv'))) this.oscTriggerHysteresisDiv.set(g('oscTriggerHysteresisDiv'));
    if (isBool(g('oscTriggerHysteresisEnabled'))) this.oscTriggerHysteresisEnabled.set(g('oscTriggerHysteresisEnabled'));
    if (isBool(g('oscShowReconstructedBeat'))) this.oscShowReconstructedBeat.set(g('oscShowReconstructedBeat'));
    if (isBool(g('oscLeftSincInterpEnabled'))) this.oscLeftSincInterpEnabled.set(g('oscLeftSincInterpEnabled'));
    if (isBool(g('oscRightSincInterpEnabled'))) this.oscRightSincInterpEnabled.set(g('oscRightSincInterpEnabled'));
    if (isBool(g('oscLeftResidualEnabled'))) this.oscLeftResidualEnabled.set(g('oscLeftResidualEnabled'));
    if (isBool(g('oscRightResidualEnabled'))) this.oscRightResidualEnabled.set(g('oscRightResidualEnabled'));
    if (isStr(g('oscLeftMainsSuppression'))) this.oscLeftMainsSuppression.set(enumOr('MainsSuppression', g('oscLeftMainsSuppression'), this.oscLeftMainsSuppression.get()));
    if (isStr(g('oscRightMainsSuppression'))) this.oscRightMainsSuppression.set(enumOr('MainsSuppression', g('oscRightMainsSuppression'), this.oscRightMainsSuppression.get()));
    if (isStr(g('oscLeftLpf'))) this.oscLeftLpf.set(enumOr('LpfMode', g('oscLeftLpf'), this.oscLeftLpf.get()));
    if (isStr(g('oscRightLpf'))) this.oscRightLpf.set(enumOr('LpfMode', g('oscRightLpf'), this.oscRightLpf.get()));
    if (isNum(g('oscLeftOffsetFrac'))) this.oscLeftOffsetFrac.set(g('oscLeftOffsetFrac'));
    if (isNum(g('oscRightOffsetFrac'))) this.oscRightOffsetFrac.set(g('oscRightOffsetFrac'));
    if (isNum(g('oscTriggerLevelFrac'))) this.oscTriggerLevelFrac.set(g('oscTriggerLevelFrac'));
    if (isNum(g('oscTriggerPositionFrac'))) this.oscTriggerPositionFrac.set(g('oscTriggerPositionFrac'));
    if (isNum(g('oscMeasurementAverageSeconds'))) this.oscMeasurementAverageSeconds.set(g('oscMeasurementAverageSeconds'));
    if (isStr(g('oscMeasurementChannel'))) this.oscMeasurementChannel.set(enumOr('Channel', g('oscMeasurementChannel'), this.oscMeasurementChannel.get()));
    if (isBool(g('oscShowStats'))) this.oscShowStats.set(g('oscShowStats'));
    if (isBool(g('oscShowHistogram'))) this.oscShowHistogram.set(g('oscShowHistogram'));
    if (isNum(g('oscHistogramBins'))) this.oscHistogramBins.set(g('oscHistogramBins'));
    if (isStr(g('oscHistogramChannel'))) {
      this.oscHistogramChannel.set(enumOr('Channel', g('oscHistogramChannel'), this.oscHistogramChannel.get()));
    }
    if (isBool(g('oscShowMeasurementTable'))) this.oscShowMeasurementTable.set(g('oscShowMeasurementTable'));
    // DEPRECATED shared full-scale fallback (unbound devices) - RUNTIME-ONLY since 1.2: read
    // here so a PRE-1.2 store seeds the fallback ONCE, never written back by _toMap(),
    // so the entry disappears with the first save. The setters validate and refresh the cached
    // dBV offsets. ONLY the left/shared scalars are read: adcFsVoltageRms and dacFsVoltageRms.
    // The never-released Right siblings are NOT read - the Right scalars keep their constructor
    // defaults until a device profile / calibration sets them (Java performs no left->right mirror
    // here either).
    if (isNum(g('adcFsVoltageRms'))) this.setAdcFsVoltageRms(g('adcFsVoltageRms'));
    // Stored as RMS; the setter takes the in-memory peak amplitude (× √2).
    if (isNum(g('dacFsVoltageRms'))) this.setDacFsVoltageAmpl(g('dacFsVoltageRms') * SQRT2);
    if (isStr(g('genSignalForm'))) this.genSignalForm.set(enumOr('GenSignalForm', g('genSignalForm'), this.genSignalForm.get()));
    if (isNum(g('genFrequencyHz'))) this.genFrequencyHz.set(g('genFrequencyHz'));
    if (isNum(g('genDualToneFreq1Hz'))) this.genDualToneFreq1Hz.set(g('genDualToneFreq1Hz'));
    if (isNum(g('genDualToneFreq2Hz'))) this.genDualToneFreq2Hz.set(g('genDualToneFreq2Hz'));
    if (isNum(g('genDualToneSplitPct'))) this.genDualToneSplitPct.set(g('genDualToneSplitPct'));
    if (isNum(g('genAmplitudeVrms'))) this.genAmplitudeVrms.set(g('genAmplitudeVrms'));
    if (isBool(g('genAmplitudeDbvDisplay'))) this.genAmplitudeDbvDisplay.set(g('genAmplitudeDbvDisplay'));
    // Fractional double now (Java int->double): NO trunc, so a fractional dither round-trips.
    if (isNum(g('genDitherBits'))) this.genDitherBits.set(g('genDitherBits'));
    if (isBool(g('genDitherDbvDisplay'))) this.genDitherDbvDisplay.set(g('genDitherDbvDisplay'));
    // Absent / invalid key keeps the current value (default BOTH) - old files stay on BOTH
    // (mirrors Java enumOr(OutputChannels.class, s, genOutputChannels.get())).
    if (isStr(g('genOutputChannels'))) this.genOutputChannels.set(enumOr('OutputChannels', g('genOutputChannels'), this.genOutputChannels.get()));
    if (isStr(g('genDpd'))) this.genDpd.set(g('genDpd'));
    if (isStr(g('genDpdDual'))) this.genDpdDual.set(g('genDpdDual'));
    if (isStr(g('genDpdFolder'))) this.genDpdFolder.set(g('genDpdFolder'));
    if (isStr(g('genDpdName'))) this.genDpdName.set(g('genDpdName'));
    if (isStr(g('genDpdDualName'))) this.genDpdDualName.set(g('genDpdDualName'));
    if (isNum(g('predistortionAverages'))) this.predistortionAverages.set(trunc(g('predistortionAverages')));
    if (isNum(g('predistortionTargetPct'))) this.predistortionTargetPct.set(g('predistortionTargetPct'));
    if (isNum(g('genRectangleDuty'))) this.genRectangleDuty.set(g('genRectangleDuty'));
    if (isNum(g('genTriangleDuty'))) this.genTriangleDuty.set(g('genTriangleDuty'));
    if (isNum(g('genSweepFreqStartHz'))) this.genSweepFreqStartHz.set(g('genSweepFreqStartHz'));
    if (isNum(g('genSweepFreqEndHz'))) this.genSweepFreqEndHz.set(g('genSweepFreqEndHz'));
    if (isNum(g('genSweepDurationSec'))) this.genSweepDurationSec.set(g('genSweepDurationSec'));
    if (isBool(g('genSweepLoop'))) this.genSweepLoop.set(g('genSweepLoop'));
    if (isNum(g('genSweepFadeInSec'))) this.genSweepFadeInSec.set(g('genSweepFadeInSec'));
    if (isNum(g('genSweepFadeOutSec'))) this.genSweepFadeOutSec.set(g('genSweepFadeOutSec'));
    if (isBool(g('genSnapToFftBin'))) this.genSnapToFftBin.set(g('genSnapToFftBin'));
    if (isNum(g('genWavDurationSeconds'))) this.genWavDurationSeconds.set(g('genWavDurationSeconds'));
    if (isStr(g('genWavPath'))) this.genWavPath.set(g('genWavPath'));
    if (isStr(g('genWavFolder'))) this.genWavFolder.set(g('genWavFolder'));
    if (isStr(g('genPlayFromPath'))) this.genPlayFromPath.set(g('genPlayFromPath'));
    if (isStr(g('genPlayFromFolder'))) this.genPlayFromFolder.set(g('genPlayFromFolder'));
    if (isBool(g('genPlayFromLoop'))) this.genPlayFromLoop.set(g('genPlayFromLoop'));
    if (isStr(g('oscSavePath'))) this.oscSavePath.set(g('oscSavePath'));
    if (isStr(g('oscSaveFolder'))) this.oscSaveFolder.set(g('oscSaveFolder'));
    if (isNum(g('oscSaveDurationSeconds'))) this.oscSaveDurationSeconds.set(g('oscSaveDurationSeconds'));
    if (isStr(g('oscPlayFromPath'))) this.oscPlayFromPath.set(g('oscPlayFromPath'));
    if (isStr(g('oscPlayFromFolder'))) this.oscPlayFromFolder.set(g('oscPlayFromFolder'));
    if (isBool(g('oscPlayFromLoop'))) this.oscPlayFromLoop.set(g('oscPlayFromLoop'));
    if (isNum(g('oscLineWidth'))) this.oscLineWidth.set(g('oscLineWidth'));
    if (isNum(g('oscDotDiameter'))) this.oscDotDiameter.set(trunc(g('oscDotDiameter')));
    if (isStr(g('oscPersistenceMode'))) this.oscPersistenceMode.set(persistenceFromNameOr(g('oscPersistenceMode'), this.oscPersistenceMode.get()));
    if (isNum(g('oscPersistenceManualSeconds'))) this.oscPersistenceManualSeconds.set(g('oscPersistenceManualSeconds'));
    this._loadColor(g('oscLeftChannelColor'), this.oscLeftChannelColor);
    this._loadColor(g('oscRightChannelColor'), this.oscRightChannelColor);
    if (isStr(g('screenshotFolder'))) this.screenshotFolder.set(g('screenshotFolder'));
    if (isStr(g('screenshotCommentFont'))) this.screenshotCommentFont.set(g('screenshotCommentFont'));

    if (asMap(g('oscPresets'))) {
      this.oscPresets.clear();
      for (const [key, pm] of Object.entries(g('oscPresets'))) {
        if (!isStr(key) || !asMap(pm)) continue;
        const p = new OscPreset();
        if (isBool(pm.leftChannelEnabled)) p.leftChannelEnabled = pm.leftChannelEnabled;
        if (isBool(pm.rightChannelEnabled)) p.rightChannelEnabled = pm.rightChannelEnabled;
        if (isBool(pm.leftAcMode)) p.leftAcMode = pm.leftAcMode;
        if (isBool(pm.rightAcMode)) p.rightAcMode = pm.rightAcMode;
        if (isBool(pm.leftSincInterpEnabled)) p.leftSincInterpEnabled = pm.leftSincInterpEnabled;
        if (isBool(pm.rightSincInterpEnabled)) p.rightSincInterpEnabled = pm.rightSincInterpEnabled;
        if (isBool(pm.leftResidualEnabled)) p.leftResidualEnabled = pm.leftResidualEnabled;
        if (isBool(pm.rightResidualEnabled)) p.rightResidualEnabled = pm.rightResidualEnabled;
        if (isStr(pm.leftMainsSuppression)) p.leftMainsSuppression = enumOr('MainsSuppression', pm.leftMainsSuppression, p.leftMainsSuppression);
        if (isStr(pm.rightMainsSuppression)) p.rightMainsSuppression = enumOr('MainsSuppression', pm.rightMainsSuppression, p.rightMainsSuppression);
        if (isStr(pm.leftLpf)) p.leftLpf = enumOr('LpfMode', pm.leftLpf, p.leftLpf);
        if (isStr(pm.rightLpf)) p.rightLpf = enumOr('LpfMode', pm.rightLpf, p.rightLpf);
        if (isNum(pm.leftVoltsPerDiv)) p.leftVoltsPerDiv = pm.leftVoltsPerDiv;
        if (isNum(pm.rightVoltsPerDiv)) p.rightVoltsPerDiv = pm.rightVoltsPerDiv;
        if (isNum(pm.leftOffsetFrac)) p.leftOffsetFrac = pm.leftOffsetFrac;
        if (isNum(pm.rightOffsetFrac)) p.rightOffsetFrac = pm.rightOffsetFrac;
        if (isNum(pm.timePerDiv)) p.timePerDiv = pm.timePerDiv;
        if (isNum(pm.triggerPositionFrac)) p.triggerPositionFrac = pm.triggerPositionFrac;
        if (isStr(pm.triggerChannel)) p.triggerChannel = enumOr('Channel', pm.triggerChannel, p.triggerChannel);
        if (isStr(pm.triggerEdge)) p.triggerEdge = enumOr('TriggerEdge', pm.triggerEdge, p.triggerEdge);
        if (isStr(pm.triggerType)) p.triggerType = enumOr('TriggerType', pm.triggerType, p.triggerType);
        if (isStr(pm.triggerMode)) p.triggerMode = enumOr('TriggerMode', pm.triggerMode, p.triggerMode);
        if (isNum(pm.triggerLevelFrac)) p.triggerLevelFrac = pm.triggerLevelFrac;
        this.oscPresets.set(key, p);
      }
    }

    // ---- FFT pane state ----
    if (isNum(g('fftLength'))) this.fftLength.set(trunc(g('fftLength')));
    // Two spellings: a plain number, or 'Infinity' - the forever entry's JSON-safe
    // form (see _toMap). A null from a pre-fix store is neither and keeps the default.
    if (isNum(g('fftAverages'))) this.fftAverages.set(g('fftAverages'));
    else if (g('fftAverages') === 'Infinity') this.fftAverages.set(Infinity);
    if (isNum(g('fftThreads'))) this.fftThreads.set(Math.max(1, Math.min(16, trunc(g('fftThreads')))));
    if (isBool(g('fftStopAfterNEnabled'))) this.fftStopAfterNEnabled.set(g('fftStopAfterNEnabled'));
    if (isNum(g('fftStopAfterN'))) this.fftStopAfterN.set(trunc(g('fftStopAfterN')));
    if (isBool(g('fftFundFromGenerator'))) this.fftFundFromGenerator.set(g('fftFundFromGenerator'));
    if (isBool(g('fftLogFreqAxis'))) this.fftLogFreqAxis.set(g('fftLogFreqAxis'));
    if (isBool(g('fftDetectTimeDiscontinuity'))) this.fftDetectTimeDiscontinuity.set(g('fftDetectTimeDiscontinuity'));
    if (isStr(g('fftWindow'))) this.fftWindow.set(enumOr('WindowType', g('fftWindow'), this.fftWindow.get()));
    if (isStr(g('fftOverlap'))) this.fftOverlap.set(enumOr('FftOverlap', g('fftOverlap'), this.fftOverlap.get()));
    if (isBool(g('fftCoherentAveraging'))) this.fftCoherentAveraging.set(g('fftCoherentAveraging'));
    if (isStr(g('fftMainsSuppression'))) this.fftMainsSuppression.set(enumOr('MainsSuppression', g('fftMainsSuppression'), this.fftMainsSuppression.get()));
    if (isStr(g('fftAlignGenerator'))) {
      this.fftAlignGenerator.set(enumOr('AlignGenerator', String(g('fftAlignGenerator')).trim().toUpperCase(), 'NONE'));
    } else if (isBool(g('fftAlignGenToFreqDiff'))) {
      // Migrate the legacy checkbox.
      this.fftAlignGenerator.set(g('fftAlignGenToFreqDiff') ? 'FLL' : 'NONE');
    }
    if (isNum(g('fftDistMinHz'))) this.fftDistMinHz.set(g('fftDistMinHz'));
    if (isNum(g('fftDistMaxHz'))) this.fftDistMaxHz.set(g('fftDistMaxHz'));
    if (isBool(g('fftDistMinEnabled'))) this.fftDistMinEnabled.set(g('fftDistMinEnabled'));
    if (isBool(g('fftDistMaxEnabled'))) this.fftDistMaxEnabled.set(g('fftDistMaxEnabled'));
    if (isNum(g('fftThdMaxHarmonic'))) this.fftThdMaxHarmonic.set(trunc(g('fftThdMaxHarmonic')));
    if (isNum(g('fftCalcMaxHarmonic'))) this.fftCalcMaxHarmonic.set(trunc(g('fftCalcMaxHarmonic')));
    if (isNum(g('fftStrongToneRelDb'))) this.fftStrongToneRelDb.set(g('fftStrongToneRelDb'));
    if (isNum(g('fftManualFundVrms'))) this.fftManualFundVrms.set(g('fftManualFundVrms'));
    if (isBool(g('fftManualFundDbvDisplay'))) this.fftManualFundDbvDisplay.set(g('fftManualFundDbvDisplay'));
    if (isBool(g('fftManualFundEnabled'))) this.fftManualFundEnabled.set(g('fftManualFundEnabled'));
    if (isStr(g('fftChannel'))) this.fftChannel.set(enumOr('Channel', g('fftChannel'), this.fftChannel.get()));
    if (isStr(g('fftMagUnit'))) this.fftMagUnit.set(enumOr('MagnitudeUnit', g('fftMagUnit'), this.fftMagUnit.get()));
    if (isStr(g('fftDistortionUnit'))) this.fftDistortionUnit.set(enumOr('MagnitudeUnit', g('fftDistortionUnit'), this.fftDistortionUnit.get()));
    if (isNum(g('fftScreenshotWidth'))) this.fftScreenshotWidth.set(g('fftScreenshotWidth'));
    if (isNum(g('fftScreenshotHeight'))) this.fftScreenshotHeight.set(g('fftScreenshotHeight'));
    if (isBool(g('fftDistortionTableVisible'))) this.fftDistortionTableVisible.set(g('fftDistortionTableVisible'));
    if (isNum(g('fftFreqMinHz'))) this.fftFreqMinHz.set(g('fftFreqMinHz'));
    if (isNum(g('fftFreqMaxHz'))) this.fftFreqMaxHz.set(g('fftFreqMaxHz'));
    if (isNum(g('fftMagTop'))) this.fftMagTop.set(g('fftMagTop'));
    if (isNum(g('fftMagBottom'))) this.fftMagBottom.set(g('fftMagBottom'));
    if (isStr(g('fftSavePath'))) this.fftSavePath.set(g('fftSavePath'));
    if (isStr(g('fftSaveFolder'))) this.fftSaveFolder.set(g('fftSaveFolder'));
    if (isStr(g('fftLoadPath'))) this.fftLoadPath.set(g('fftLoadPath'));

    // ---- Frequency Response pane ----
    if (isNum(g('freqRespStartHz'))) this.freqRespStartHz.set(g('freqRespStartHz'));
    if (isNum(g('freqRespStopHz'))) this.freqRespStopHz.set(g('freqRespStopHz'));
    if (isNum(g('freqRespAmplitudeVrms'))) this.freqRespAmplitudeVrms.set(g('freqRespAmplitudeVrms'));
    if (isBool(g('freqRespAmplitudeDbvDisplay'))) this.freqRespAmplitudeDbvDisplay.set(g('freqRespAmplitudeDbvDisplay'));
    if (isNum(g('freqRespSweepPoints'))) this.freqRespSweepPoints.set(trunc(g('freqRespSweepPoints')));
    if (isNum(g('freqRespDurationSec'))) this.freqRespDurationSec.set(g('freqRespDurationSec'));
    if (isNum(g('freqRespFftSize'))) {
      // Snap to the nearest legal power of two between 64k and 16M.
      let v = trunc(g('freqRespFftSize'));
      v = Math.max(1 << 16, Math.min(1 << 24, v));
      let p = 1 << 16;
      while (p < v) p <<= 1;
      this.freqRespFftSize.set(p);
    }
    if (isNum(g('freqRespDitherBits'))) this.freqRespDitherBits.set(trunc(g('freqRespDitherBits')));
    if (isNum(g('freqRespLeadInSec'))) this.freqRespLeadInSec.set(g('freqRespLeadInSec'));
    // Absent / invalid key keeps the current value (default BOTH) - old files stay on BOTH
    // (mirrors Java enumOr(OutputChannels.class, s, freqRespOutputChannels.get())).
    if (isStr(g('freqRespOutputChannels'))) this.freqRespOutputChannels.set(enumOr('OutputChannels', g('freqRespOutputChannels'), this.freqRespOutputChannels.get()));
    if (isNum(g('tuneNotchStartHz'))) this.tuneNotchStartHz.set(g('tuneNotchStartHz'));
    if (isNum(g('tuneNotchStopHz'))) this.tuneNotchStopHz.set(g('tuneNotchStopHz'));
    if (isNum(g('tuneNotchAmplitudeVrms'))) this.tuneNotchAmplitudeVrms.set(g('tuneNotchAmplitudeVrms'));
    if (isNum(g('tuneNotchTargetHz'))) this.tuneNotchTargetHz.set(g('tuneNotchTargetHz'));
    if (isStr(g('tuneNotchOutputChannels'))) this.tuneNotchOutputChannels.set(enumOr('OutputChannels', g('tuneNotchOutputChannels'), this.tuneNotchOutputChannels.get()));
    if (isBool(g('freqRespLeftVisible'))) this.freqRespLeftVisible.set(g('freqRespLeftVisible'));
    if (isBool(g('freqRespRightVisible'))) this.freqRespRightVisible.set(g('freqRespRightVisible'));
    if (isBool(g('freqRespPhaseVisible'))) this.freqRespPhaseVisible.set(g('freqRespPhaseVisible'));
    if (isNum(g('freqRespFreqMinHz'))) this.freqRespFreqMinHz.set(g('freqRespFreqMinHz'));
    if (isNum(g('freqRespFreqMaxHz'))) this.freqRespFreqMaxHz.set(g('freqRespFreqMaxHz'));
    if (isNum(g('freqRespMagTopDb'))) this.freqRespMagTopDb.set(g('freqRespMagTopDb'));
    if (isNum(g('freqRespMagBotDb'))) this.freqRespMagBotDb.set(g('freqRespMagBotDb'));
    if (isNum(g('freqRespNyquistFraction'))) {
      const v = g('freqRespNyquistFraction');
      this.freqRespNyquistFraction.set(Math.max(0.83, Math.min(1.0, v < 0.83 ? 1.0 : v)));
    }
    if (isNum(g('freqRespCompareSmoothWindow'))) {
      this.freqRespCompareSmoothWindow.set(Math.max(0, Math.min(100, trunc(g('freqRespCompareSmoothWindow')))));
    }
    if (isBool(g('freqRespNotchEnabled'))) this.freqRespNotchEnabled.set(g('freqRespNotchEnabled'));
    if (isNum(g('freqRespNotchBaseHz'))) {
      const v = trunc(g('freqRespNotchBaseHz'));
      this.freqRespNotchBaseHz.set(v === 60 ? 60 : 50);
    }
    this._loadColor(g('freqRespSignalColor'), this.freqRespSignalColor);
    this._loadColor(g('freqRespPhaseColor'), this.freqRespPhaseColor);
    this._loadColor(g('freqRespReferenceColor'), this.freqRespReferenceColor);
    this._loadColor(g('freqRespBackgroundColor'), this.freqRespBackgroundColor);
    // freqRespShowRiaa is intentionally not loaded (fresh-session default).
    if (isBool(g('freqRespReverseRiaa'))) this.freqRespReverseRiaa.set(g('freqRespReverseRiaa'));
    if (isBool(g('freqRespIecAmendment'))) this.freqRespIecAmendment.set(g('freqRespIecAmendment'));
    if (isBool(g('freqRespCompareMode'))) this.freqRespCompareMode.set(g('freqRespCompareMode'));
    // freqRespShowFilter is intentionally not loaded (fresh-session default).
    if (isBool(g('freqRespFilterCompare'))) this.freqRespFilterCompare.set(g('freqRespFilterCompare'));
    if (isStr(g('freqRespFilterType'))) this.freqRespFilterType.set(enumOr('FilterType', g('freqRespFilterType'), this.freqRespFilterType.get()));
    if (isStr(g('freqRespFilterResponse'))) this.freqRespFilterResponse.set(enumOr('FilterResponse', g('freqRespFilterResponse'), this.freqRespFilterResponse.get()));
    if (isStr(g('freqRespUnevenMode'))) this.freqRespUnevenMode.set(enumOr('UnevenMode', g('freqRespUnevenMode'), this.freqRespUnevenMode.get()));
    if (isBool(g('freqRespUnevenNotch'))) this.freqRespUnevenNotch.set(g('freqRespUnevenNotch'));
    if (isNum(g('freqRespUnevenDb'))) this.freqRespUnevenDb.set(Math.max(0.001, Math.min(20.0, g('freqRespUnevenDb'))));
    if (isNum(g('freqRespUnevenStartHz'))) this.freqRespUnevenStartHz.set(g('freqRespUnevenStartHz'));
    if (isNum(g('freqRespUnevenStopHz'))) this.freqRespUnevenStopHz.set(g('freqRespUnevenStopHz'));
    if (this.freqRespUnevenStartHz.get() >= this.freqRespUnevenStopHz.get()) {
      this.freqRespUnevenStartHz.set(20.0);
      this.freqRespUnevenStopHz.set(20000.0);
    }
    if (isBool(g('freqRespApplyCalibration'))) this.freqRespApplyCalibration.set(g('freqRespApplyCalibration'));
    if (Array.isArray(g('freqRespCalibrations'))) {
      this.freqRespCalibrations.length = 0;
      for (const o of g('freqRespCalibrations')) {
        if (!asMap(o)) continue;
        const path = isStr(o.path) ? o.path : null;
        const active = isBool(o.active) && o.active;
        const hash = isStr(o.hash) ? o.hash : null;
        const e = new CalibrationEntry(path, active, false, hash);
        this.freqRespCalibrations.push(e);
        this._trackCalibration(e);
      }
    }
    if (isStr(g('freqRespSaveFolder'))) this.freqRespSaveFolder.set(g('freqRespSaveFolder'));
    if (isStr(g('freqRespSavePath'))) this.freqRespSavePath.set(g('freqRespSavePath'));
    if (isStr(g('freqRespLoadFolder'))) this.freqRespLoadFolder.set(g('freqRespLoadFolder'));
    if (isStr(g('freqRespLoadPath'))) this.freqRespLoadPath.set(g('freqRespLoadPath'));
    if (isNum(g('freqRespActiveTabIndex'))) this.freqRespActiveTabIndex.set(trunc(g('freqRespActiveTabIndex')));
    if (isNum(g('freqRespScreenshotWidth'))) this.freqRespScreenshotWidth.set(g('freqRespScreenshotWidth'));
    if (isNum(g('freqRespScreenshotHeight'))) this.freqRespScreenshotHeight.set(g('freqRespScreenshotHeight'));
    if (isStr(g('fftLoadFolder'))) this.fftLoadFolder.set(g('fftLoadFolder'));
    if (Array.isArray(g('fftCalibrations'))) {
      this.fftCalibrations.length = 0;
      for (const o of g('fftCalibrations')) {
        if (!asMap(o)) continue;
        const path = isStr(o.path) ? o.path : null;
        const active = isBool(o.active) && o.active;
        const withNoise = isBool(o.withNoise) && o.withNoise;
        const hash = isStr(o.hash) ? o.hash : null;
        const e = new CalibrationEntry(path, active, withNoise, hash);
        this.fftCalibrations.push(e);
        this._trackCalibration(e);
      }
    }
    this._loadColor(g('fftBeforeCalDotColor'), this.fftBeforeCalDotColor);
    this._loadColor(g('fftCalOverlayColor'), this.fftCalOverlayColor);
    if (isNum(g('fftLineWidth'))) this.fftLineWidth.set(g('fftLineWidth'));
    if (isNum(g('fftStrongToneRelDb'))) this.fftStrongToneRelDb.set(g('fftStrongToneRelDb'));
    if (isNum(g('freqRespLineWidth'))) this.freqRespLineWidth.set(g('freqRespLineWidth'));
    if (isNum(g('fftHarmonicDotDiameter'))) this.fftHarmonicDotDiameter.set(trunc(g('fftHarmonicDotDiameter')));
    this._loadColor(g('fftLineColor'), this.fftLineColor);
    this._loadColor(g('fftChartBackgroundColor'), this.fftChartBackgroundColor);
    this._loadColor(g('fftHarmonicDotColor'), this.fftHarmonicDotColor);
    this._loadColor(g('fftFreqRespColor'), this.fftFreqRespColor);

    if (asMap(g('fftPresets'))) {
      this.fftPresets.clear();
      for (const [key, pm] of Object.entries(g('fftPresets'))) {
        if (!isStr(key) || !asMap(pm)) continue;
        const p = new FftPreset();
        if (isStr(pm.channel)) p.channel = enumOr('Channel', pm.channel, p.channel);
        if (isStr(pm.magUnit)) p.magUnit = enumOr('MagnitudeUnit', pm.magUnit, p.magUnit);
        if (isBool(pm.logFreqAxis)) p.logFreqAxis = pm.logFreqAxis;
        if (isNum(pm.freqMinHz)) p.freqMinHz = pm.freqMinHz;
        if (isNum(pm.freqMaxHz)) p.freqMaxHz = pm.freqMaxHz;
        if (isNum(pm.magTop)) p.magTop = pm.magTop;
        if (isNum(pm.magBottom)) p.magBottom = pm.magBottom;
        if (isNum(pm.fftLength)) p.fftLength = trunc(pm.fftLength);
        if (isNum(pm.averages)) p.averages = pm.averages;
        if (isBool(pm.stopAfterNEnabled)) p.stopAfterNEnabled = pm.stopAfterNEnabled;
        if (isNum(pm.stopAfterN)) p.stopAfterN = trunc(pm.stopAfterN);
        if (isBool(pm.fundFromGenerator)) p.fundFromGenerator = pm.fundFromGenerator;
        if (isStr(pm.window)) p.window = enumOr('WindowType', pm.window, p.window);
        if (isStr(pm.overlap)) p.overlap = enumOr('FftOverlap', pm.overlap, p.overlap);
        if (isBool(pm.coherentAveraging)) p.coherentAveraging = pm.coherentAveraging;
        if (isNum(pm.distMinHz)) p.distMinHz = pm.distMinHz;
        if (isNum(pm.distMaxHz)) p.distMaxHz = pm.distMaxHz;
        if (isBool(pm.distMinEnabled)) p.distMinEnabled = pm.distMinEnabled;
        if (isBool(pm.distMaxEnabled)) p.distMaxEnabled = pm.distMaxEnabled;
        if (isNum(pm.thdMaxHarmonic)) p.thdMaxHarmonic = trunc(pm.thdMaxHarmonic);
        if (isNum(pm.calcMaxHarmonic)) p.calcMaxHarmonic = trunc(pm.calcMaxHarmonic);
        if (isNum(pm.manualFundVrms)) p.manualFundVrms = pm.manualFundVrms;
        if (isBool(pm.manualFundDbvDisplay)) p.manualFundDbvDisplay = pm.manualFundDbvDisplay;
        if (isBool(pm.manualFundEnabled)) p.manualFundEnabled = pm.manualFundEnabled;
        this.fftPresets.set(key, p);
      }
    }

    if (asMap(g('freqRespPresets'))) {
      this.freqRespPresets.clear();
      for (const [key, pm] of Object.entries(g('freqRespPresets'))) {
        if (!isStr(key) || !asMap(pm)) continue;
        const p = new FreqRespPreset();
        if (isNum(pm.startHz)) p.startHz = pm.startHz;
        if (isNum(pm.stopHz)) p.stopHz = pm.stopHz;
        if (isNum(pm.amplitudeVrms)) p.amplitudeVrms = pm.amplitudeVrms;
        if (isNum(pm.sweepPoints)) p.sweepPoints = trunc(pm.sweepPoints);
        if (isNum(pm.fftSize)) p.fftSize = trunc(pm.fftSize);
        if (isNum(pm.leadInSec)) p.leadInSec = pm.leadInSec;
        if (isNum(pm.ditherBits)) p.ditherBits = trunc(pm.ditherBits);
        if (isBool(pm.showRiaa)) p.showRiaa = pm.showRiaa;
        if (isBool(pm.reverseRiaa)) p.reverseRiaa = pm.reverseRiaa;
        if (isBool(pm.iecAmendment)) p.iecAmendment = pm.iecAmendment;
        if (isBool(pm.compareMode)) p.compareMode = pm.compareMode;
        if (isBool(pm.showFilter)) p.showFilter = pm.showFilter;
        if (isBool(pm.filterCompare)) p.filterCompare = pm.filterCompare;
        if (isStr(pm.filterType)) p.filterType = enumOr('FilterType', pm.filterType, p.filterType);
        if (isStr(pm.filterResponse)) p.filterResponse = enumOr('FilterResponse', pm.filterResponse, p.filterResponse);
        if (asMap(pm.filterParams)) p.filterParams = this._readFilterParams(p.filterType, pm.filterParams);
        if (isStr(pm.unevenMode)) p.unevenMode = enumOr('UnevenMode', pm.unevenMode, p.unevenMode);
        if (isBool(pm.unevenNotch)) p.unevenNotch = pm.unevenNotch;
        if (isNum(pm.unevenDb)) p.unevenDb = pm.unevenDb;
        if (isNum(pm.unevenStartHz)) p.unevenStartHz = pm.unevenStartHz;
        if (isNum(pm.unevenStopHz)) p.unevenStopHz = pm.unevenStopHz;
        this.freqRespPresets.set(key, p);
      }
    }

    if (asMap(g('freqRespFilterParamsByType'))) {
      this.freqRespFilterParamsByType.clear();
      for (const [key, pm] of Object.entries(g('freqRespFilterParamsByType'))) {
        const type = enumOr('FilterType', key, null);
        if (type == null || !asMap(pm)) continue;
        this.freqRespFilterParamsByType.set(type, this._readFilterParams(type, pm));
      }
    }

    if (asMap(g('perBackend'))) {
      for (const [key, bpMap] of Object.entries(g('perBackend'))) {
        if (!isStr(key)) continue;
        const type = backendKeyOr(key, null);
        if (type == null) continue;
        const bp = this.prefsFor(type);
        if (asMap(bpMap)) {
          if (isStr(bpMap.inputDeviceName)) bp.inputDeviceName = bpMap.inputDeviceName;
          if (isStr(bpMap.outputDeviceName)) bp.outputDeviceName = bpMap.outputDeviceName;
          if (this._isInt(bpMap.inputSampleRate)) bp.inputSampleRate = bpMap.inputSampleRate;
          if (this._isInt(bpMap.inputBitDepth)) bp.inputBitDepth = bpMap.inputBitDepth;
          if (this._isInt(bpMap.outputSampleRate)) bp.outputSampleRate = bpMap.outputSampleRate;
          if (this._isInt(bpMap.outputBitDepth)) bp.outputBitDepth = bpMap.outputBitDepth;
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // small load helpers
  // -------------------------------------------------------------------------

  /** Applies a colour value to {@code prop}: '#RRGGBB' string or a packed int,
   *  else leaves the current value (mirrors the fromMap colour branches). */
  _loadColor(obj, prop) {
    if (isStr(obj)) prop.set(parseHtmlColor(obj, prop.get()));
    else if (isNum(obj)) prop.set(trunc(obj));
  }

  /** SnakeYAML's per-backend ints arrived as Integer in Java; here we accept any
   *  integer-valued number (matches the `instanceof Integer` gate). */
  _isInt(v) {
    return isNum(v) && Number.isInteger(v);
  }

  /** Copies a loaded numeric list into an int array, dropping non-numbers
   *  (listToIntArray); returns null for an empty/unusable list. */
  _listToIntArray(list) {
    if (!list || list.length === 0) return null;
    const out = new Array(list.length).fill(0);
    for (let i = 0; i < list.length; i++) {
      if (isNum(list[i])) out[i] = trunc(list[i]);
    }
    return out;
  }
}
