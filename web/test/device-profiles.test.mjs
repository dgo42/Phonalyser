/*
 * Phonalyser web - device-profile + per-channel-calibration contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/device-profiles.test.mjs` (or via `npm test`).
 *
 * Ports the desktop invariants of the per-card device-profile model + store and the
 * per-channel full-scale into the web port, mirroring:
 *   DeviceProfileRoundTripTest, DeviceStoreTest, PerChannelAdcFsTest, PerChannelDacFsTest.
 *
 * The desktop stores each profile in devices.yaml; the web keeps it in localStorage
 * (one JSON document per storageKey). The store constructor establishes the store
 * (seed-if-absent + once-per-contentVersion merge), so a "reload" is a second store on
 * the SAME key, a "custom bundle" is an injected catalog, and an "existing devices.yaml"
 * is a pre-written localStorage document - the web equivalents of the desktop tests'
 * devicesPathOverride / seedPathOverride / TempDir plumbing.
 *
 * preferences.js + device-profiles.js touch localStorage / navigator, so this installs
 * minimal shims on globalThis BEFORE importing them.
 */

import { readFileSync } from 'node:fs';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
const EPS = 1e-9;
const near = (a, b) => Math.abs(a - b) <= EPS;

// --- shims: an in-memory localStorage + a navigator for defaultUiFont ---
function installShims() {
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    clear: () => { store.clear(); },
  };
  if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };
}
installShims();

const { Preferences } = await import('../js/store/preferences.js');
const { DeviceProfileStore, AudioDeviceProfile, DeviceRange, DeviceEndpointConfig } =
  await import('../js/store/device-profiles.js');
// The catalog is the shipped devices.yaml (the Java single source of truth, copied verbatim into
// web/), read from disk + parsed by the SAME parser the app uses - no hardcoded literals that can
// drift from the file. CATALOG is the store-document shape { formatVersion, contentVersion,
// audioDevices } the DeviceProfileStore `catalog` option expects.
const { parseDevicesYaml } = await import('../js/store/device-catalog.js');
const CATALOG = parseDevicesYaml(readFileSync(new URL('../devices.yaml', import.meta.url), 'utf8'));
const CATALOG_CONTENT_VERSION = CATALOG.contentVersion;
const { DeviceChannelMode } = await import('../js/enums/device-channel-mode.js');
const { OutputChannels } = await import('../js/enums/output-channels.js');
const { Capability, capabilityOf, seedIsMono, nameTaken, droppingCalibrated, parseMatch, assembleProfile } =
  await import('../js/store/card-editor-logic.js');

const SQRT2 = Math.sqrt(2.0);
const EMPTY_CATALOG = { formatVersion: 1, contentVersion: 0, audioDevices: [] };

let keyCounter = 0;
/** A fresh (transient, non-persisting) Preferences + a store on a unique key. */
function fresh(catalog = EMPTY_CATALOG) {
  const storageKey = 'phonalyser.devices.test.' + (++keyCounter);
  const prefs = new Preferences(true);   // detached -> never touches PREFS_KEY
  const store = new DeviceProfileStore(prefs, { storageKey, catalog });
  return { store, prefs, storageKey };
}
/** Pre-writes a store document to localStorage, then opens a store over it. */
function openOver(doc, catalog = EMPTY_CATALOG) {
  const storageKey = 'phonalyser.devices.test.' + (++keyCounter);
  localStorage.setItem(storageKey, JSON.stringify(doc));
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, { storageKey, catalog });
  return { store, prefs, storageKey };
}
const readDoc = (key) => JSON.parse(localStorage.getItem(key));

// --- model builders (mirror the Java test builders) ---
function mkRange(label, l, r, calibrated = false) {
  const x = new DeviceRange();
  x.label = label; x.fsLeft = l; x.fsRight = r; x.calibrated = calibrated;
  return x;
}
function inputProfile(name, deviceName, mode, activeLeft, activeRight, rows) {
  const p = new AudioDeviceProfile();
  p.name = name; p.match = [deviceName];
  p.input.channels = mode;
  for (const r of rows) p.input.ranges.push(r);
  p.input.activeRange = activeLeft; p.input.activeRangeRight = activeRight;
  return p;
}
function outputProfile(name, deviceName, mode, activeLeft, activeRight, rows) {
  const p = new AudioDeviceProfile();
  p.name = name; p.match = [deviceName];
  p.output.channels = mode;
  for (const r of rows) p.output.ranges.push(r);
  p.output.activeRange = activeLeft; p.output.activeRangeRight = activeRight;
  return p;
}
// ---------------------------------------------------------------------------
// EXEMPLAR SAFETY - the same MODEL on both ends is two devices
//
// Maintainer invariant: "server can have same device as client, but this are different
// exemplars and they can have different calibration!". A binding is therefore keyed by ORIGIN,
// not by the bare device name, or the CB5 on this desk and the CB5 on the bench would share one
// card - and one operator's calibration would silently become the other's.
// ---------------------------------------------------------------------------

function sampleProfile() {
  const p = new AudioDeviceProfile();
  p.name = 'Cosmos ADC';
  p.match = ['Line (E1DA Cosmos ADC)', 'E1DA Cosmos ADC', 'Speakers (E1DA Cosmos ADC)'];
  p.input.channels = DeviceChannelMode.INDEPENDENT;
  p.input.ranges.push(mkRange('DIP 1.7 Vrms', 1.7931, 1.7902), mkRange('DIP 4.5 Vrms', 4.4812, 4.4812));
  p.input.activeRange = 'DIP 1.7 Vrms';
  p.input.activeRangeRight = 'DIP 4.5 Vrms';
  p.output.channels = DeviceChannelMode.LINKED;
  p.output.ranges.push(mkRange('direct', 1.975, 1.975));
  p.output.activeRange = 'direct';
  return p;
}

// =========================================================================
// A. Enums
// =========================================================================
ok(DeviceChannelMode.MONO === 'MONO' && DeviceChannelMode.LINKED === 'LINKED'
  && DeviceChannelMode.INDEPENDENT === 'INDEPENDENT', 'DeviceChannelMode tokens');
ok(OutputChannels.BOTH === 'BOTH' && OutputChannels.LEFT === 'LEFT' && OutputChannels.RIGHT === 'RIGHT',
  'OutputChannels tokens');

// =========================================================================
// B. Round-trip + resolution (DeviceProfileRoundTripTest)
// =========================================================================

// profile_survivesRoundTrip
{
  const a = fresh();
  a.store.putAudioDeviceProfile(sampleProfile());
  const b = new DeviceProfileStore(new Preferences(true), { storageKey: a.storageKey, catalog: EMPTY_CATALOG });
  const back = b.getAudioDeviceProfiles();
  ok(back.length === 1, 'round-trip: exactly one card');
  const p = back[0];
  ok(p.name === 'Cosmos ADC', 'round-trip: name');
  ok(JSON.stringify(p.match) === JSON.stringify(['Line (E1DA Cosmos ADC)', 'E1DA Cosmos ADC', 'Speakers (E1DA Cosmos ADC)']),
    'round-trip: match list order');
  ok(p.input.channels === DeviceChannelMode.INDEPENDENT, 'round-trip: input INDEPENDENT');
  ok(p.input.activeRange === 'DIP 1.7 Vrms' && p.input.activeRangeRight === 'DIP 4.5 Vrms', 'round-trip: active ranges');
  ok(p.input.ranges.length === 2, 'round-trip: two input rows');
  ok(near(p.input.ranges[0].fsLeft, 1.7931) && near(p.input.ranges[0].fsRight, 1.7902), 'round-trip: per-channel row values');
  ok(near(p.input.ranges[1].fsLeft, 4.4812) && near(p.input.ranges[1].fsRight, 4.4812), 'round-trip: equal-channel row values');
  ok(p.output.channels === DeviceChannelMode.LINKED && p.output.activeRange === 'direct', 'round-trip: output LINKED');
  ok(near(p.output.ranges[0].fsLeft, 1.975), 'round-trip: output value');
}

// everyRange_writesLeftRightPair (persisted JSON shape)
{
  const a = fresh();
  a.store.putAudioDeviceProfile(sampleProfile());
  const doc = readDoc(a.storageKey);
  const ranges = doc.audioDevices[0].input.ranges;
  const perCh = ranges[0].fsVrms, shared = ranges[1].fsVrms;
  ok(perCh && typeof perCh === 'object' && near(perCh.left, 1.7931) && near(perCh.right, 1.7902),
    'per-channel fsVrms is a {left,right} pair');
  ok(shared && typeof shared === 'object' && near(shared.left, 4.4812) && near(shared.right, 4.4812),
    'equal-channel fsVrms is ALSO a {left,right} pair, never a scalar');
}

// activeRange_independentWritesMapForm_linkedStaysScalar
{
  const a = fresh();
  a.store.putAudioDeviceProfile(sampleProfile());
  const doc = readDoc(a.storageKey);
  const inActive = doc.audioDevices[0].input.activeRange;
  ok(inActive && typeof inActive === 'object' && inActive.left === 'DIP 1.7 Vrms' && inActive.right === 'DIP 4.5 Vrms',
    'INDEPENDENT activeRange is a {left,right} map');
  ok(doc.audioDevices[0].output.activeRange === 'direct', 'LINKED activeRange stays a scalar label');
  ok(!('activeRangeRight' in doc.audioDevices[0].input), 'the legacy activeRangeRight key is never written');
}

// --- displayLabel: the DIALOG's verbose row text (Java DeviceRange.displayLabel) ---
// The QA40x input row's key stays the plain "0 dBV" the protocol decodes back into a
// register value, while the table shows the analyzer's real levels. It is display-only:
// device builds re-derive it, the wire may carry it for a bench card, and NOTHING here
// ever writes it out.
// ---------------------------------------------------------------------------
// EXEMPLAR SAFETY - the same MODEL on both ends is two devices
//
// Maintainer invariant: "server can have same device as client, but this are different
// exemplars and they can have different calibration!". A binding is keyed by ORIGIN, not by the
// bare device name, or the CB5 on this desk and the CB5 on the bench would share one card - and
// one operator's calibration would silently become the other's.
// ---------------------------------------------------------------------------
{
  const { store } = fresh();
  store.putAudioDeviceProfile(inputProfile('Local CB5', 'CB5', DeviceChannelMode.LINKED,
    'default', null, [mkRange('default', 1.0, 1.0, true)]));
  store.putAudioDeviceProfile(inputProfile('Bench CB5', 'bench-only', DeviceChannelMode.LINKED,
    'default', null, [mkRange('default', 4.25, 4.25, true)]));
  const local = store.deviceBindingKey(null, 'CB5 [plughw:1,1]');
  const remote = store.deviceBindingKey('uuid-bench', 'CB5 [plughw:1,1]');
  ok(local !== remote, 'R2: one device NAME, two origins, two binding keys');
  store.bindDeviceToCard(local, 'Local CB5');
  store.bindDeviceToCard(remote, 'Bench CB5');
  ok(store.boundCardName(local) === 'Local CB5', 'R2: the local exemplar keeps its own card');
  ok(store.boundCardName(remote) === 'Bench CB5',
    'R2: the bench exemplar keeps its own card although the device name is identical');

  // Forgetting one must not uncalibrate the other.
  store.bindDeviceToCard(remote, '');
  ok(store.boundCardName(local) === 'Local CB5',
    'R2: unbinding the bench choice leaves the device on this desk bound');
  ok(store.boundCardName(remote) === null, 'R2: and the bench binding is gone');
}

// The LOCAL resolution takes a bare device name and must never answer with a server-scoped
// binding: that key names a card in the SERVER's store, and resolving it here would hand a
// foreign bench's device to whatever local card happens to share the name.
{
  const { store } = fresh();
  store.putAudioDeviceProfile(inputProfile('Bench CB5', 'no-local-match',
    DeviceChannelMode.LINKED, 'default', null, [mkRange('default', 4.25, 4.25, true)]));
  store.bindDeviceToCard(store.deviceBindingKey('uuid-bench', 'CB5 [plughw:1,1]'), 'Bench CB5');
  ok(store.resolveDeviceProfile('CB5 [plughw:1,1]') === null,
    'R2: a bench binding is not the local exemplar\'s - the local device stays unbound');
}

{
  const row = mkRange('0 dBV', 1.0, 1.0);
  ok(row.displayLabel === null, 'displayLabel: declared, and null on an ordinary row');
  ok(row.displayLabelOrKey() === '0 dBV', 'displayLabelOrKey: falls back to the key');
  row.displayLabel = '0 "dBV" real 0 dBFS or -9 dBV';
  ok(row.displayLabelOrKey() === '0 "dBV" real 0 dBFS or -9 dBV',
    'displayLabelOrKey: the verbose text wins when set');
  const copy = row.deepCopy();
  ok(copy.displayLabel === '0 "dBV" real 0 dBFS or -9 dBV',
    'deepCopy carries displayLabel - a working copy that dropped it would print the '
    + 'bare key in the dialog');
  ok(copy.displayLabelOrKey() === row.displayLabelOrKey(), 'deepCopy: same shown text');
}

// The wire's card content may carry displayLabel (the desktop codec emits it); the
// reader takes it so a BENCH card's rows read exactly as they do on the desktop.
{
  const { store } = fresh();
  const read = store._readRange({
    label: '0 dBV', fsVrms: { left: 1.0, right: 1.0 },
    displayLabel: '0 "dBV" real 0 dBFS or -9 dBV',
  });
  ok(read.displayLabel === '0 "dBV" real 0 dBFS or -9 dBV',
    'the range reader parses displayLabel from card content');
  ok(read.label === '0 dBV', 'and the KEY is untouched - everything functional keys on it');
  const plain = store._readRange({ label: '0 dBV', fsVrms: 1.0 });
  ok(plain.displayLabel === null, 'a row without one reads as null, never undefined');
  const garbled = store._readRange({ label: '0 dBV', fsVrms: 1.0, displayLabel: 42 });
  ok(garbled.displayLabel === null, 'and a non-string is ignored like every other field');
}

// A web client NEVER writes displayLabel anywhere persistent: the verbose text is the
// dialog's, the store keeps the key (maintainer amendment).
{
  const a = fresh();
  const p = inputProfile('QA403', 'QA403', DeviceChannelMode.LINKED, '0 dBV', null,
    [mkRange('0 dBV', 1.0, 1.0, true)]);
  p.input.ranges[0].displayLabel = '0 "dBV" real 0 dBFS or -9 dBV';
  a.store.putAudioDeviceProfile(p);
  const persisted = readDoc(a.storageKey).audioDevices[0].input.ranges[0];
  ok(!('displayLabel' in persisted), 'the persisted row carries no displayLabel');
  ok(persisted.label === '0 dBV', 'only the short key is stored');
}

// normalizeDeviceName_foldsBackendWrappers
{
  const { store } = fresh();
  ok(store.normalizeDeviceName('Line (E1DA Cosmos ADC)') === 'E1DA Cosmos ADC', 'normalize: Line wrapper');
  ok(store.normalizeDeviceName('Speakers (E1DA Cosmos ADC)') === 'E1DA Cosmos ADC', 'normalize: Speakers wrapper');
  ok(store.normalizeDeviceName('Microphone (E1DA Cosmos ADC)') === 'E1DA Cosmos ADC', 'normalize: Microphone wrapper');
  ok(store.normalizeDeviceName('Headphones (E1DA Cosmos ADC)') === 'E1DA Cosmos ADC', 'normalize: Headphones wrapper');
  ok(store.normalizeDeviceName('E1DA Cosmos ADC') === 'E1DA Cosmos ADC', 'normalize: bare name unchanged');
  ok(store.normalizeDeviceName('Cosmos ADC [plughw:1,0]') === 'Cosmos ADC', 'normalize: ALSA suffix stripped');
  ok(store.normalizeDeviceName('  Cosmos ADC  ') === 'Cosmos ADC', 'normalize: trimmed');
  ok(store.normalizeDeviceName(null) === null, 'normalize: null -> null');
}

// resolveDeviceProfile_matchesBySubstring
{
  const { store } = fresh();
  const p = new AudioDeviceProfile();
  p.name = 'E1DA Cosmos ADC Interface';
  p.match = ['Cosmos ADC'];
  store.putAudioDeviceProfile(p);
  ok(store.resolveDeviceProfile('Line (E1DA Cosmos ADC Interface)').name === 'E1DA Cosmos ADC Interface', 'resolve: substring match');
  ok(store.resolveDeviceProfile('Cosmos ADC [plughw:1,0]').name === 'E1DA Cosmos ADC Interface', 'resolve: across name forms');
  ok(store.resolveDeviceProfile('Focusrite Scarlett 2i2') === null, 'resolve: no match -> null');
  ok(store.resolveDeviceProfile('Interface') === null, 'resolve: logical name is not a match entry');
}

// resolveDeviceProfile_longestMatchWins
{
  const { store } = fresh();
  const g = new AudioDeviceProfile(); g.name = 'Generic'; g.match = ['USB']; store.putAudioDeviceProfile(g);
  const s = new AudioDeviceProfile(); s.name = 'Cosmos'; s.match = ['Cosmos USB Audio']; store.putAudioDeviceProfile(s);
  ok(store.resolveDeviceProfile('Line (Cosmos USB Audio)').name === 'Cosmos', 'resolve: longest match wins');
  ok(store.resolveDeviceProfile('Some USB Widget').name === 'Generic', 'resolve: generic-only device -> generic');
}

// storeAdcCalibration_autoCreatesBoundProfile_andSetsGlobalFs
{
  const { store, prefs } = fresh();
  prefs.current().inputDeviceName = 'Generic USB Codec';
  store.storeAdcCalibration(2.5);
  ok(near(prefs.getAdcFsVoltageRms(), 2.5), 'storeAdc: global left scalar took the value');
  const created = store.resolveDeviceProfile('Generic USB Codec');
  ok(created != null && created.name === 'Generic USB Codec', 'storeAdc: auto-created bound card');
  ok(JSON.stringify(created.match) === JSON.stringify(['Generic USB Codec']), 'storeAdc: exact device name in match');
  ok(created.input.channels === DeviceChannelMode.LINKED, 'storeAdc: new card LINKED');
  ok(created.input.ranges.length === 1 && created.input.ranges[0].label === 'default', 'storeAdc: one default row');
  ok(created.input.activeRange === 'default', 'storeAdc: default row active');
  ok(near(created.input.ranges[0].fsLeft, 2.5) && near(created.input.ranges[0].fsRight, 2.5), 'storeAdc: row values');
  store.storeAdcCalibration(3.1);
  ok(store.getAudioDeviceProfiles().length === 1, 'storeAdc: recalibrate does not fork a card');
  ok(near(store.resolveDeviceProfile('Generic USB Codec').input.ranges[0].fsLeft, 3.1), 'storeAdc: recalibrate updates the row');
}

// storeDacCalibration_storesRmsOnDisk_andSetsGlobalAmpl
{
  const { store, prefs } = fresh();
  prefs.current().outputDeviceName = 'E1DA Cosmos DAC';
  const fsAmpl = 2.79351;
  store.storeDacCalibration(fsAmpl);
  ok(near(prefs.getDacFsVoltageAmpl(), fsAmpl), 'storeDac: global peak-amplitude scalar');
  const created = store.resolveDeviceProfile('E1DA Cosmos DAC');
  ok(near(created.output.ranges[0].fsLeft, fsAmpl / SQRT2), 'storeDac: stored row is the RMS form (ampl/√2)');
  prefs.setDacFsVoltageAmpl(1.0);
  store.applyOutputDeviceProfile('E1DA Cosmos DAC');
  ok(near(prefs.getDacFsVoltageAmpl(), fsAmpl), 'storeDac: applyOutput round-trips RMS->ampl onto the scalar');
}

// applyInputDeviceProfile_unresolved_isLegacyNoOp
{
  const { store, prefs } = fresh();
  prefs.setAdcFsVoltageRms(1.234);
  store.applyInputDeviceProfile('Some Unbound Card');
  ok(near(prefs.getAdcFsVoltageRms(), 1.234), 'applyInput: unresolved device is a legacy no-op');
}

// findAudioDeviceProfile_returnsLiveObject
{
  const { store } = fresh();
  store.putAudioDeviceProfile(sampleProfile());
  const a1 = store.findAudioDeviceProfile('cosmos adc');
  const a2 = store.findAudioDeviceProfile('Cosmos ADC');
  ok(a1 != null && a1 === a2, 'findAudioDeviceProfile: case-insensitive, returns the live object');
}

// =========================================================================
// C. Store seeding + merge (DeviceStoreTest)
// =========================================================================

// loadDevices_seedsBundle_whenFileAbsent (REAL catalog)
{
  const { store, storageKey } = fresh(CATALOG);
  ok(localStorage.getItem(storageKey) != null, 'seed: an absent store is written from the catalog');
  const cosmos = store.findAudioDeviceProfile('E1DA Cosmos ADC');
  ok(cosmos != null && JSON.stringify(cosmos.match) === JSON.stringify(['E1DA Cosmos ADC']), 'seed: Cosmos card + match');
  ok(cosmos.input.channels === DeviceChannelMode.INDEPENDENT, 'seed: Cosmos INDEPENDENT');
  ok(cosmos.input.activeRange === '1.7V' && cosmos.input.activeRangeRight === '1.7V', 'seed: Cosmos active ranges');
  ok(cosmos.input.ranges.length === 9, 'seed: Cosmos has 9 ranges');
  // Durable: the seeded store rows must EQUAL the parsed devices.yaml values (no drift-prone
  // literals). fsVrms in the parsed document is the { left, right } pair; the seeded row's fsLeft
  // is the exact same double.
  const cosmosSeed = CATALOG.audioDevices.find((d) => d.name === 'E1DA Cosmos ADC');
  ok(cosmos.input.ranges[0].fsLeft === cosmosSeed.input.ranges[0].fsVrms.left
    && cosmos.input.ranges[8].fsLeft === cosmosSeed.input.ranges[8].fsVrms.left,
    'seed: Cosmos range values equal the parsed devices.yaml');
  const i2s = store.findAudioDeviceProfile('I2SoverUSB');
  ok(i2s != null && JSON.stringify(i2s.match) === JSON.stringify(['I2SoverUSB', 'JLsounds']), 'seed: I2S card + match');
  const i2sSeed = CATALOG.audioDevices.find((d) => d.name === 'I2SoverUSB');
  ok(i2s.output.ranges[0].fsLeft === i2sSeed.output.ranges[0].fsVrms.left,
    'seed: I2S output value equals the parsed devices.yaml');
}

// loadDevices_doesNotReseed_whenFilePresent (store already on the catalog version)
{
  const doc = { formatVersion: 1, contentVersion: CATALOG_CONTENT_VERSION, audioDevices: [{ name: 'My Card' }] };
  const { store } = openOver(doc, CATALOG);
  ok(store.getAudioDeviceProfiles().length === 1, 'no-reseed: a present on-version store is loaded as-is');
  ok(store.findAudioDeviceProfile('My Card') != null, 'no-reseed: the user card survives');
  ok(store.findAudioDeviceProfile('E1DA Cosmos ADC') === null, 'no-reseed: the bundle does not overwrite');
}

// resolveDeviceProfile_recognisesSeededCardsByMatch
{
  const { store } = fresh(CATALOG);
  ok(store.resolveDeviceProfile('Line (E1DA Cosmos ADC)').name === 'E1DA Cosmos ADC', 'recognise: Line form');
  ok(store.resolveDeviceProfile('E1DA Cosmos ADC PCM32/384').name === 'E1DA Cosmos ADC', 'recognise: suffix form');
  ok(store.resolveDeviceProfile('5- I2SoverUSB').name === 'I2SoverUSB', 'recognise: I2SoverUSB entry');
  ok(store.resolveDeviceProfile('Speakers (JLsounds Hi-Rez Audio 2.0)').name === 'I2SoverUSB', 'recognise: JLsounds entry');
  ok(store.resolveDeviceProfile('Focusrite Scarlett 2i2') === null, 'recognise: unknown card -> null');
}

// bindDeviceName_appendsOnlyWhenNoEntryAlreadyMatches (model unit test)
{
  const p = new AudioDeviceProfile();
  p.match = ['Cosmos ADC'];
  ok(p.bindDeviceName('Line (E1DA Cosmos ADC)') === false, 'bind: no append when an entry already matches');
  ok(JSON.stringify(p.match) === JSON.stringify(['Cosmos ADC']), 'bind: match unchanged');
  ok(p.bindDeviceName('Focusrite Scarlett 2i2') === true, 'bind: append when nothing matches');
  ok(JSON.stringify(p.match) === JSON.stringify(['Cosmos ADC', 'Focusrite Scarlett 2i2']), 'bind: name appended');
  ok(p.bindDeviceName('Focusrite Scarlett 2i2 (2- USB)') === false, 'bind: no append when a longer name still contains an entry');
}

// storeAdcCalibration_onRecognisedDevice_bindsSeededProfileInPlace + marksActiveRow_linkedPath variant
{
  const { store, prefs } = fresh(CATALOG);
  const before = store.getAudioDeviceProfiles().length;
  prefs.current().inputDeviceName = 'Line (E1DA Cosmos ADC)';
  store.storeAdcCalibration(1.82);
  ok(near(prefs.getAdcFsVoltageRms(), 1.82), 'recognised-store: global left scalar');
  ok(near(prefs.getAdcFsVoltageRms('R'), 1.82), 'recognised-store: legacy single-arg keeps both scalars equal');
  ok(store.getAudioDeviceProfiles().length === before, 'recognised-store: binds the seed, no fork');
  const bound = store.resolveDeviceProfile('Line (E1DA Cosmos ADC)');
  ok(bound.name === 'E1DA Cosmos ADC', 'recognised-store: bound to the seeded card');
  ok(JSON.stringify(bound.match) === JSON.stringify(['E1DA Cosmos ADC']), 'recognised-store: nothing appended (already matched)');
  const active = bound.input.ranges[0];
  ok(active.label === '1.7V' && near(active.fsLeft, 1.82) && near(active.fsRight, 1.82), 'recognised-store: active row took the value');
  ok(active.calibrated === true, 'recognised-store: active row marked calibrated');
  ok(bound.input.ranges[1].calibrated === false, 'recognised-store: a non-active row stays uncalibrated');
  ok(near(bound.input.ranges[1].fsLeft, 2.7), 'recognised-store: the non-active row keeps its nominal');
}

// store_roundTripsThroughItsOwnFile
{
  const a = fresh();
  const prof = new AudioDeviceProfile();
  prof.name = 'Widget DAC'; prof.match = ['Widget', 'Widget DAC'];
  prof.output.channels = DeviceChannelMode.LINKED;
  prof.output.ranges.push(mkRange('default', 2.0, 2.0));
  prof.output.activeRange = 'default';
  a.store.putAudioDeviceProfile(prof);
  const b = new DeviceProfileStore(new Preferences(true), { storageKey: a.storageKey, catalog: EMPTY_CATALOG });
  const back = b.getAudioDeviceProfiles();
  ok(back.length === 1 && back[0].name === 'Widget DAC', 'file round-trip: card name');
  ok(JSON.stringify(back[0].match) === JSON.stringify(['Widget', 'Widget DAC']), 'file round-trip: match');
  ok(near(back[0].output.ranges[0].fsLeft, 2.0), 'file round-trip: value');
}

// userCard_matchOmitted_fromDisk
{
  const a = fresh();
  const userCard = new AudioDeviceProfile(); userCard.name = 'Bare Card';
  a.store.putAudioDeviceProfile(userCard);
  const doc = readDoc(a.storageKey);
  ok(!('match' in doc.audioDevices[0]), 'a user card with no patterns persists no match key');
}

// calibratedFlag_roundTrips_onlyWhenTrue
{
  const a = fresh();
  const prof = new AudioDeviceProfile();
  prof.name = 'Two Row Card';
  prof.input.channels = DeviceChannelMode.LINKED;
  prof.input.ranges.push(mkRange('A', 1.5, 1.5, true), mkRange('B', 2.5, 2.5, false));
  prof.input.activeRange = 'A';
  a.store.putAudioDeviceProfile(prof);
  const doc = readDoc(a.storageKey);
  ok(doc.audioDevices[0].input.ranges[0].calibrated === true, 'calibrated row serialises the flag');
  ok(!('calibrated' in doc.audioDevices[0].input.ranges[1]), 'a nominal row stays clean');
  const b = new DeviceProfileStore(new Preferences(true), { storageKey: a.storageKey, catalog: EMPTY_CATALOG });
  const r = b.findAudioDeviceProfile('Two Row Card');
  ok(r.input.ranges[0].calibrated === true && r.input.ranges[1].calibrated === false, 'calibrated flag round-trips');
}

// storeAdcCalibration_marksActiveRow_perChannelIndependentPath
{
  const card = new AudioDeviceProfile();
  card.name = 'Indep Card'; card.match = ['Indep In'];
  card.input.channels = DeviceChannelMode.INDEPENDENT;
  card.input.ranges.push(mkRange('A', 1.0, 1.0));
  card.input.activeRange = 'A'; card.input.activeRangeRight = 'A';
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(card);
  prefs.current().inputDeviceName = 'Indep In';
  store.storeAdcCalibrationChannel('R', 3.3);
  const bound = store.findAudioDeviceProfile('Indep Card');
  const row = bound.input.ranges[0];
  ok(row.calibrated === true, 'per-channel calibrate marks its active row');
  ok(near(row.fsRight, 3.3), 'per-channel calibrate: only fsRight moved');
  ok(near(row.fsLeft, 1.0), 'per-channel calibrate: fsLeft untouched');
}

// --- once-per-content-version seed merge ---
const linkedCard = (name, label, fsVrms, calibrated, matchArr) => {
  const c = { name, input: { channels: 'LINKED', ranges: [{ label, fsVrms }], activeRange: label } };
  if (calibrated) c.input.ranges[0].calibrated = true;
  if (matchArr) c.match = matchArr;
  return c;
};

// mergeSeed_addsNewCard
{
  const storeDoc = { formatVersion: 1, audioDevices: [linkedCard('My Card', 'default', 1.0, true)] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [linkedCard('Brand New Card', '1V', 1.1, false, ['Brand New'])] };
  const { store } = openOver(storeDoc, bundle);
  ok(store.findAudioDeviceProfile('My Card') != null, 'merge/addCard: the user card survives');
  const added = store.findAudioDeviceProfile('Brand New Card');
  ok(added != null && JSON.stringify(added.match) === JSON.stringify(['Brand New']), 'merge/addCard: new card + match added');
  ok(near(added.input.ranges[0].fsLeft, 1.1) && added.input.ranges[0].calibrated === false, 'merge/addCard: seeded row is nominal');
}

// mergeSeed_unionsNewSeedMatchEntries
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'Widget', match: ['Widget', 'Line (Widget Pro)'], input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 1.0, calibrated: true }], activeRange: 'R1' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'Widget', match: ['Widget', 'Extra Seed Pattern'], input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 2.0 }], activeRange: 'R1' } }] };
  const { store } = openOver(storeDoc, bundle);
  ok(JSON.stringify(store.findAudioDeviceProfile('Widget').match) ===
    JSON.stringify(['Widget', 'Line (Widget Pro)', 'Extra Seed Pattern']),
    'merge/union: seed match entry unioned on (add-only, order kept)');
}

// mergeSeed_addsNewRangeToExistingCard + keeps the calibrated row
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'E1DA Cosmos ADC', match: ['Cosmos'], input: { channels: 'LINKED', ranges: [{ label: '1.7V', fsVrms: 1.7931, calibrated: true }], activeRange: '1.7V' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'E1DA Cosmos ADC', match: ['Cosmos'], input: { channels: 'LINKED', ranges: [{ label: '1.7V', fsVrms: 1.7 }, { label: '43V', fsVrms: 43 }], activeRange: '1.7V' } }] };
  const { store } = openOver(storeDoc, bundle);
  const c = store.findAudioDeviceProfile('E1DA Cosmos ADC');
  ok(c.input.ranges.length === 2, 'merge/addRange: the new range is added');
  const added = c.input.ranges[1];
  ok(added.label === '43V' && near(added.fsLeft, 43.0) && added.calibrated === false, 'merge/addRange: added row is nominal');
  const kept = c.input.ranges[0];
  ok(kept.label === '1.7V' && near(kept.fsLeft, 1.7931) && kept.calibrated === true, 'merge/addRange: calibrated row keeps its measured FS');
}

// mergeSeed_uncalibratedRowUpdated_userAddedRowSurvives
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'Card X', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 9.99 }, { label: 'Rextra', fsVrms: 5.5 }], activeRange: 'R1' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'Card X', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 2.22 }], activeRange: 'R1' } }] };
  const { store } = openOver(storeDoc, bundle);
  const ranges = store.findAudioDeviceProfile('Card X').input.ranges;
  const find = (l) => ranges.find((r) => r.label === l);
  ok(ranges.length === 2, 'merge/uncal: hand-added row kept (merge never deletes)');
  ok(near(find('R1').fsLeft, 2.22) && near(find('R1').fsRight, 2.22) && find('R1').calibrated === false, 'merge/uncal: uncalibrated row takes the release value');
  ok(near(find('Rextra').fsLeft, 5.5), 'merge/uncal: hand-added row value untouched');
}

// mergeSeed_neverTouchesCalibratedRow
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'Card Y', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 1.7931, calibrated: true }], activeRange: 'R1' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'Card Y', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 1.7 }], activeRange: 'R1' } }] };
  const { store } = openOver(storeDoc, bundle);
  const row = store.findAudioDeviceProfile('Card Y').input.ranges[0];
  ok(near(row.fsLeft, 1.7931) && row.calibrated === true, 'merge/cal: a calibrated row survives the merge');
}

// mergeSeed_reconcilesRangesOnly_keepsUserStructure (maintainer store shape)
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'E1DA Cosmos ADC', match: ['Line (E1DA Cosmos ADC)', 'E1DA Cosmos ADC'],
      input: { channels: 'INDEPENDENT', ranges: [
        { label: '1.7V', fsVrms: { left: 1.7931, right: 1.7902 }, calibrated: true },
        { label: '3.4V', fsVrms: 3.4 },
        { label: '9.9V', fsVrms: { left: 9.9, right: 9.8 }, calibrated: true }],
        activeRange: { left: '1.7V', right: '1.7V' } } },
    { name: 'My Custom Rig', match: ['Line (My Custom Rig)'],
      input: { channels: 'LINKED', ranges: [{ label: 'default', fsVrms: 2.0, calibrated: true }], activeRange: 'default' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'E1DA Cosmos ADC', match: ['E1DA Cosmos ADC'],
      input: { channels: 'LINKED', ranges: [
        { label: '1.7V', fsVrms: 1.7 }, { label: '2.7V', fsVrms: 2.7 },
        { label: '3.5V', fsVrms: 3.5 }, { label: '4.5V', fsVrms: 4.5 }], activeRange: '1.7V' } }] };
  const { store } = openOver(storeDoc, bundle);
  const cosmos = store.findAudioDeviceProfile('E1DA Cosmos ADC');
  const find = (l) => cosmos.input.ranges.find((r) => r.label === l);
  ok(cosmos.input.channels === DeviceChannelMode.INDEPENDENT, 'maintainer: user channel mode untouched');
  ok(JSON.stringify(cosmos.match) === JSON.stringify(['Line (E1DA Cosmos ADC)', 'E1DA Cosmos ADC']), 'maintainer: match unchanged (seed entry already present)');
  ok(cosmos.input.ranges.length === 6, 'maintainer: 3 store rows + 3 seed-only rows = 6');
  ok(near(find('3.4V').fsLeft, 3.4), 'maintainer: hand-added uncalibrated row survives');
  ok(find('1.7V').calibrated === true && near(find('1.7V').fsLeft, 1.7931) && near(find('1.7V').fsRight, 1.7902), 'maintainer: distinct calibrated 1.7V survives');
  ok(near(find('2.7V').fsLeft, 2.7) && find('2.7V').calibrated === false, 'maintainer: seed-only row is nominal');
  ok(find('9.9V').calibrated === true && near(find('9.9V').fsLeft, 9.9) && near(find('9.9V').fsRight, 9.8), 'maintainer: dropped-by-release calibrated row kept');
  ok(cosmos.input.activeRange === '1.7V' && cosmos.input.activeRangeRight === '1.7V', 'maintainer: active-range selection untouched');
  const mine = store.findAudioDeviceProfile('My Custom Rig');
  ok(mine != null && mine.input.channels === DeviceChannelMode.LINKED && mine.input.ranges.length === 1
    && near(mine.input.ranges[0].fsLeft, 2.0) && mine.input.ranges[0].calibrated === true, 'maintainer: user-created card untouched');
}

// mergeSeed_findsRenamedCard_byMatchOverlap
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'My Renamed ADC', match: ['Cosmos ADC'], input: { channels: 'LINKED', ranges: [{ label: '1.7V', fsVrms: 1.79, calibrated: true }], activeRange: '1.7V' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'E1DA Cosmos ADC', match: ['Cosmos ADC'], input: { channels: 'LINKED', ranges: [{ label: '1.7V', fsVrms: 1.7 }, { label: '43V', fsVrms: 43 }], activeRange: '1.7V' } }] };
  const { store } = openOver(storeDoc, bundle);
  ok(store.getAudioDeviceProfiles().length === 1, 'renamed: match overlap -> no second card');
  const renamed = store.findAudioDeviceProfile('My Renamed ADC');
  ok(renamed != null && renamed.input.ranges.length === 2, 'renamed: keeps its name, seed range added');
  const r17 = renamed.input.ranges.find((r) => r.label === '1.7V');
  ok(near(r17.fsLeft, 1.79) && r17.calibrated === true, 'renamed: calibrated row keeps its value');
  ok(renamed.input.ranges.find((r) => r.label === '43V') != null, 'renamed: seed range landed');
}

// mergeSeed_scalarCalibratedRow_keepsValue_onPairFormatUpdate
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'Card Z', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: 1.75, calibrated: true }], activeRange: 'R1' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'Card Z', input: { channels: 'LINKED', ranges: [{ label: 'R1', fsVrms: { left: 2.0, right: 2.1 } }], activeRange: 'R1' } }] };
  const { store, storageKey } = openOver(storeDoc, bundle);
  const row = store.findAudioDeviceProfile('Card Z').input.ranges[0];
  ok(row.calibrated === true, 'scalar-cal: flag carried across the format change');
  ok(near(row.fsLeft, 1.75) && near(row.fsRight, 1.75), 'scalar-cal: scalar value lands on both channels (never the seed pair)');
  const doc = readDoc(storageKey);
  ok(doc.audioDevices[0].input.ranges[0].fsVrms.left != null && doc.audioDevices[0].input.ranges[0].fsVrms.right != null,
    'scalar-cal: rewritten store emits the {left,right} pair form');
}

// higherContentVersion_firesMerge / sameContentVersion_doesNotMerge
{
  const storeDoc = { formatVersion: 1, contentVersion: 1, audioDevices: [linkedCard('My Card', 'R1', 1.0, true)] };
  const bundle = { formatVersion: 1, contentVersion: 2, audioDevices: [linkedCard('New Seed', 'R1', 2.0, false, ['New'])] };
  const { store } = openOver(storeDoc, bundle);
  ok(store.findAudioDeviceProfile('New Seed') != null, 'version: higher contentVersion fires the merge');
  ok(store.findAudioDeviceProfile('My Card') != null, 'version: user card survives the merge');
}
{
  const storeDoc = { formatVersion: 1, contentVersion: 1, audioDevices: [linkedCard('My Card', 'R1', 1.0, true)] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [linkedCard('Seed Card', 'R1', 3.0, false)] };
  const { store } = openOver(storeDoc, bundle);
  ok(store.findAudioDeviceProfile('Seed Card') === null, 'version: same contentVersion -> no merge');
  ok(store.getAudioDeviceProfiles().length === 1, 'version: only the user card present');
}

// legacyStore_mergesOnce_recordsContentVersion (no contentVersion -> recorded 0 -> merges once)
{
  const storeDoc = { formatVersion: 1, audioDevices: [linkedCard('Only Card', 'R1', 1.0, true)] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [linkedCard('New One', 'R1', 2.0, false)] };
  const { store, storageKey } = openOver(storeDoc, bundle);
  ok(store.findAudioDeviceProfile('New One') != null, 'legacy: version-less store merges the bundle once');
  ok(store.findAudioDeviceProfile('Only Card') != null, 'legacy: user card survives');
  ok(readDoc(storageKey).contentVersion === 1, 'legacy: store records the bundle contentVersion after merge');
  // A second open on the recorded version must not merge again.
  const b = new DeviceProfileStore(new Preferences(true), { storageKey, catalog: bundle });
  ok(b.getAudioDeviceProfiles().length === 2, 'legacy: recorded version keeps the merge dormant next start');
}

// writtenStore_copiesFormatVersionFromSeed
{
  const bundle7 = { formatVersion: 7, contentVersion: 1, audioDevices: [] };
  const { store, storageKey } = fresh(bundle7);
  store.putAudioDeviceProfile((() => { const c = new AudioDeviceProfile(); c.name = 'My Card'; return c; })());
  ok(readDoc(storageKey).formatVersion === 7, 'formatVersion: copied from the seed (7)');
  const bundle1 = { formatVersion: 1, contentVersion: 1, audioDevices: [] };
  const s2 = fresh(bundle1);
  s2.store.putAudioDeviceProfile((() => { const c = new AudioDeviceProfile(); c.name = 'My Card'; return c; })());
  ok(readDoc(s2.storageKey).formatVersion === 1, 'formatVersion: seed is the single source of truth (1)');
}

// writtenStore_formatVersion_fallsBackToLoadedStore_whenSeedUnreadable
{
  const storeDoc = { formatVersion: 5, contentVersion: 1, audioDevices: [linkedCard('My Card', 'R1', 1.0, true)] };
  const unreadable = { formatVersion: 0, contentVersion: 0, audioDevices: [] };   // catalog "unreadable"
  const { store, storageKey } = openOver(storeDoc, unreadable);
  store.saveDevices();
  ok(readDoc(storageKey).formatVersion === 5, 'formatVersion: falls back to the loaded store when the seed is unreadable');
}

// --- QA40x-shaped card: the removed device-calibration guard means writes now SUCCEED ---
// There is no vendor-USB protocol path in the browser, so a QA40x-shaped card is an ordinary
// card: a calibration write lands on the active row and the seed merge reconciles it by the
// normal calibrated-survival rule.
function qa40xInputCard() {
  const p = new AudioDeviceProfile();
  p.name = 'QA40x'; p.match = ['QA40x Input'];
  p.input.channels = DeviceChannelMode.LINKED;
  p.input.ranges.push(mkRange('0 dB', 7.0, 7.0));
  p.input.activeRange = '0 dB';
  return p;
}

// storeAdcCalibration_writesActiveRow_andScalars
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(qa40xInputCard());
  prefs.current().inputDeviceName = 'QA40x Input';
  store.storeAdcCalibration(5.0);
  const row = store.findAudioDeviceProfile('QA40x').input.ranges[0];
  ok(near(row.fsLeft, 5.0) && near(row.fsRight, 5.0), 'store ADC: active row takes the calibrated value');
  ok(row.calibrated === true, 'store ADC: row marked calibrated');
  ok(near(prefs.getAdcFsVoltageRms(), 5.0) && near(prefs.getAdcFsVoltageRms('R'), 5.0), 'store ADC: both scalars set');
}

// storeAdcCalibration_channelAware_writesOnlyThatChannel (LINKED shared row)
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(qa40xInputCard());
  prefs.current().inputDeviceName = 'QA40x Input';
  store.storeAdcCalibrationChannel('R', 9.0);
  const row = store.findAudioDeviceProfile('QA40x').input.ranges[0];
  ok(near(row.fsRight, 9.0) && near(row.fsLeft, 7.0) && row.calibrated === true, 'store ADC (per-channel): only right of the shared row moved');
  ok(near(prefs.getAdcFsVoltageRms('R'), 9.0), 'store ADC (per-channel): right scalar set');
}

// storeDacCalibration_writesActiveRow_andScalar
{
  const { store, prefs } = fresh();
  const p = new AudioDeviceProfile();
  p.name = 'QA40x'; p.match = ['QA40x Output'];
  p.output.channels = DeviceChannelMode.LINKED;
  p.output.ranges.push(mkRange('0 dB', 3.0, 3.0));
  p.output.activeRange = '0 dB';
  store.putAudioDeviceProfile(p);
  prefs.current().outputDeviceName = 'QA40x Output';
  store.storeDacCalibration(9.9);
  const row = store.findAudioDeviceProfile('QA40x').output.ranges[0];
  ok(near(row.fsLeft, 9.9 / SQRT2) && row.calibrated === true, 'store DAC: active row takes the RMS form, marked calibrated');
  ok(near(prefs.getDacFsVoltageAmpl(), 9.9), 'store DAC: scalar set');
}

// mergeSeed_qa40xCard_reconcilesByNormalRule (calibrated rows keep their measured FS)
{
  const storeDoc = { formatVersion: 1, audioDevices: [
    { name: 'QA40x', match: ['QA40x Input'], input: { channels: 'LINKED', ranges: [
      { label: '0dB', fsVrms: 1.111, calibrated: true }, { label: '18dB', fsVrms: 2.222, calibrated: true }], activeRange: '18dB' } }] };
  const bundle = { formatVersion: 1, contentVersion: 1, audioDevices: [
    { name: 'QA40x', match: ['QA40x'], input: { channels: 'LINKED', ranges: [
      { label: '0dB', fsVrms: 9.9 }, { label: '18dB', fsVrms: 8.8 }], activeRange: '0dB' } }] };
  const { store } = openOver(storeDoc, bundle);
  const inp = store.findAudioDeviceProfile('QA40x').input;
  const find = (l) => inp.ranges.find((r) => r.label === l);
  ok(inp.ranges.length === 2, 'qa40x merge: no user row appended (labels already present)');
  ok(near(find('0dB').fsLeft, 1.111) && find('0dB').calibrated === true, 'qa40x merge: calibrated row keeps its measured FS (normal rule)');
  ok(near(find('18dB').fsLeft, 2.222) && find('18dB').calibrated === true, 'qa40x merge: second calibrated row also kept');
  ok(inp.activeRange === '18dB', 'qa40x merge: user active-range selection survives');
}

// =========================================================================
// D. Per-channel ADC full-scale (PerChannelAdcFsTest)
// =========================================================================

// applyInputDeviceProfile_linked_readsPerChannelFromSharedRow
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Linked Card', 'Line (Linked Card)', DeviceChannelMode.LINKED, 'gain', null, [mkRange('gain', 2.5, 2.7)]));
  store.applyInputDeviceProfile('Line (Linked Card)');
  ok(near(prefs.getAdcFsVoltageRms('L'), 2.5), 'applyInput/LINKED: LEFT from active row fsLeft');
  ok(near(prefs.getAdcFsVoltageRms('R'), 2.7) && near(prefs.getAdcFsVoltageRms('R'), 2.7), 'applyInput/LINKED: RIGHT from the SAME row fsRight');
}

// applyInputDeviceProfile_mono_fillsBothScalarsFromOneValue
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Mono Card', 'Line (Mono Card)', DeviceChannelMode.MONO, 'gain', null, [mkRange('gain', 1.9, 9.9)]));
  store.applyInputDeviceProfile('Line (Mono Card)');
  ok(near(prefs.getAdcFsVoltageRms('L'), 1.9) && near(prefs.getAdcFsVoltageRms('R'), 1.9), 'applyInput/MONO: one value into both scalars (stray fsRight ignored)');
}

// applyInputDeviceProfile_independent_fillsPairFromDistinctRows
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Dual Card', 'Line (Dual Card)', DeviceChannelMode.INDEPENDENT, '1.7V', '3.4V', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  store.applyInputDeviceProfile('Line (Dual Card)');
  ok(near(prefs.getAdcFsVoltageRms('L'), 1.70), 'applyInput/INDEP: LEFT from activeRange row fsLeft');
  ok(near(prefs.getAdcFsVoltageRms('R'), 3.42), 'applyInput/INDEP: RIGHT from activeRangeRight row fsRight');
}

// applyInputDeviceProfile_independent_danglingRight_fallsBackToActiveRow
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Dangling Card', 'Line (Dangling Card)', DeviceChannelMode.INDEPENDENT, '1.7V', 'NOPE', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  store.applyInputDeviceProfile('Line (Dangling Card)');
  ok(near(prefs.getAdcFsVoltageRms('L'), 1.70), 'applyInput/dangling: LEFT stands');
  ok(near(prefs.getAdcFsVoltageRms('R'), 1.71), 'applyInput/dangling: RIGHT falls back to the activeRange row fsRight');
}

// storeAdcCalibration_right/left on INDEPENDENT writes only that side
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Dual Card', 'Line (Dual Card)', DeviceChannelMode.INDEPENDENT, '1.7V', '3.4V', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  prefs.current().inputDeviceName = 'Line (Dual Card)';
  prefs.setAdcFsVoltageRms(1.70); prefs.setAdcFsVoltageRmsRight(3.42);
  store.storeAdcCalibrationChannel('R', 3.99);
  ok(near(prefs.getAdcFsVoltageRms(), 1.70) && near(prefs.getAdcFsVoltageRms('R'), 3.99), 'storeAdc/INDEP R: only right scalar moved');
  const bound = store.resolveDeviceProfile('Line (Dual Card)');
  ok(near(bound.input.ranges[1].fsRight, 3.99) && near(bound.input.ranges[1].fsLeft, 3.40), 'storeAdc/INDEP R: wrote fsRight of activeRangeRight row, fsLeft untouched');
  ok(near(bound.input.ranges[0].fsLeft, 1.70) && near(bound.input.ranges[0].fsRight, 1.71), 'storeAdc/INDEP R: LEFT active row untouched');

  const s2 = fresh();
  s2.store.putAudioDeviceProfile(inputProfile('Dual Card', 'Line (Dual Card)', DeviceChannelMode.INDEPENDENT, '1.7V', '3.4V', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  s2.prefs.current().inputDeviceName = 'Line (Dual Card)';
  s2.prefs.setAdcFsVoltageRms(1.70); s2.prefs.setAdcFsVoltageRmsRight(3.42);
  s2.store.storeAdcCalibrationChannel('L', 1.99);
  ok(near(s2.prefs.getAdcFsVoltageRms(), 1.99) && near(s2.prefs.getAdcFsVoltageRms('R'), 3.42), 'storeAdc/INDEP L: left scalar moved, right untouched');
  const b2 = s2.store.resolveDeviceProfile('Line (Dual Card)');
  ok(near(b2.input.ranges[0].fsLeft, 1.99) && near(b2.input.ranges[0].fsRight, 1.71), 'storeAdc/INDEP L: wrote fsLeft of activeRange row, fsRight untouched');
}

// storeAdcCalibration_right on LINKED writes only right of the shared row
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Linked Card', 'Line (Linked Card)', DeviceChannelMode.LINKED, 'gain', null, [mkRange('gain', 2.5, 2.7)]));
  prefs.current().inputDeviceName = 'Line (Linked Card)';
  prefs.setAdcFsVoltageRms(2.5); prefs.setAdcFsVoltageRmsRight(2.7);
  store.storeAdcCalibrationChannel('R', 3.99);
  ok(near(prefs.getAdcFsVoltageRms(), 2.5) && near(prefs.getAdcFsVoltageRms('R'), 3.99), 'storeAdc/LINKED R: only right scalar moved');
  const shared = store.resolveDeviceProfile('Line (Linked Card)').input.ranges[0];
  ok(near(shared.fsLeft, 2.5) && near(shared.fsRight, 3.99), 'storeAdc/LINKED R: shared row fsRight moved, fsLeft untouched');
}

// storeAdcCalibration on MONO keeps both equal
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Mono Card', 'Line (Mono Card)', DeviceChannelMode.MONO, 'gain', null, [mkRange('gain', 1.9, 1.9)]));
  prefs.current().inputDeviceName = 'Line (Mono Card)';
  store.storeAdcCalibrationChannel('R', 2.22);
  ok(near(prefs.getAdcFsVoltageRms(), 2.22) && near(prefs.getAdcFsVoltageRms('R'), 2.22), 'storeAdc/MONO: both scalars move together');
  const active = store.resolveDeviceProfile('Line (Mono Card)').input.ranges[0];
  ok(near(active.fsLeft, 2.22) && near(active.fsRight, 2.22), 'storeAdc/MONO: both row fields equal');
}

// storeAdcCalibration legacy (single-arg) keeps both equal, even on INDEPENDENT
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Dual Card', 'Line (Dual Card)', DeviceChannelMode.INDEPENDENT, '1.7V', '3.4V', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  prefs.current().inputDeviceName = 'Line (Dual Card)';
  store.storeAdcCalibration(2.22);
  ok(near(prefs.getAdcFsVoltageRms(), 2.22) && near(prefs.getAdcFsVoltageRms('R'), 2.22), 'storeAdc/legacy: both scalars equal');
  const active = store.resolveDeviceProfile('Line (Dual Card)').input.ranges[0];
  ok(near(active.fsLeft, 2.22) && near(active.fsRight, 2.22), 'storeAdc/legacy: both row fields equal (activeRange row)');
}

// dbvOffset_isConsistentPerChannelAfterSetters + convertFromDbFs channel-aware
{
  const p = new Preferences(true);
  p.setAdcFsVoltageRms(1.7931);
  p.setAdcFsVoltageRmsRight(3.5862);
  ok(near(p.getDbvOffsetDb('L'), 20 * Math.log10(1.7931)), 'dbvOffset: LEFT');
  ok(near(p.getDbvOffsetDb(), 20 * Math.log10(1.7931)), 'dbvOffset: channel-less getter is the LEFT/legacy offset');
  ok(near(p.getDbvOffsetDb('R'), 20 * Math.log10(3.5862)), 'dbvOffset: RIGHT');
  const dbFs = -6.0;
  ok(near(p.convertFromDbFs(dbFs, 'DBV', null, 'L'), dbFs + p.getDbvOffsetDb('L')), 'convertFromDbFs: LEFT offset');
  ok(near(p.convertFromDbFs(dbFs, 'DBV', null, 'R'), dbFs + p.getDbvOffsetDb('R')), 'convertFromDbFs: RIGHT offset');
}

// peakVolts_isChannelAware
{
  const p = new Preferences(true);
  p.setAdcFsVoltageRms(2.0); p.setAdcFsVoltageRmsRight(3.0);
  ok(near(p.getAdcPeakVolts('L'), 2.0 * SQRT2), 'peakVolts: LEFT × √2');
  ok(near(p.getAdcPeakVolts('R'), 3.0 * SQRT2), 'peakVolts: RIGHT × √2');
}

// per-channel setter validation (reject ≤0, keep the old value)
{
  const p = new Preferences(true);
  p.setAdcFsVoltageRmsRight(2.0);
  p.setAdcFsVoltageRmsRight(0);
  p.setAdcFsVoltageRmsRight(-1);
  ok(near(p.getAdcFsVoltageRms('R'), 2.0), 'setter validation: ADC right rejects ≤0, keeps last good');
  p.setDacFsVoltageAmplRight(2.0);
  p.setDacFsVoltageAmplRight(0);
  ok(near(p.getDacFsVoltageAmpl('R'), 2.0), 'setter validation: DAC right rejects ≤0, keeps last good');
}

// =========================================================================
// E. Per-channel DAC full-scale (PerChannelDacFsTest) - RMS on disk, ampl in memory
// =========================================================================

// applyOutputDeviceProfile MONO / LINKED / INDEPENDENT / dangling
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Mono DAC', 'Speakers (Mono DAC)', DeviceChannelMode.MONO, 'default', null, [mkRange('default', 2.0, 9.0)]));
  store.applyOutputDeviceProfile('Speakers (Mono DAC)');
  ok(near(prefs.getDacFsVoltageAmpl('L'), 2.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), 2.0 * SQRT2), 'applyOutput/MONO: one value × √2 into both scalars');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Linked DAC', 'Speakers (Linked DAC)', DeviceChannelMode.LINKED, 'default', null, [mkRange('default', 2.0, 2.2)]));
  store.applyOutputDeviceProfile('Speakers (Linked DAC)');
  ok(near(prefs.getDacFsVoltageAmpl('L'), 2.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), 2.2 * SQRT2), 'applyOutput/LINKED: per-channel from the shared row × √2');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Dual DAC', 'Speakers (Dual DAC)', DeviceChannelMode.INDEPENDENT, 'lo', 'hi', [mkRange('lo', 1.0, 1.1), mkRange('hi', 3.0, 3.3)]));
  store.applyOutputDeviceProfile('Speakers (Dual DAC)');
  ok(near(prefs.getDacFsVoltageAmpl('L'), 1.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), 3.3 * SQRT2), 'applyOutput/INDEP: pair from distinct rows × √2');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Dangling DAC', 'Speakers (Dangling DAC)', DeviceChannelMode.INDEPENDENT, 'lo', 'NOPE', [mkRange('lo', 1.0, 1.1), mkRange('hi', 3.0, 3.3)]));
  store.applyOutputDeviceProfile('Speakers (Dangling DAC)');
  ok(near(prefs.getDacFsVoltageAmpl('L'), 1.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), 1.1 * SQRT2), 'applyOutput/dangling: right falls back to activeRange row');
}

// storeDacCalibration_right on INDEPENDENT / LINKED / MONO / legacy
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Dual DAC', 'Speakers (Dual DAC)', DeviceChannelMode.INDEPENDENT, 'lo', 'hi', [mkRange('lo', 1.0, 1.1), mkRange('hi', 3.0, 3.3)]));
  prefs.current().outputDeviceName = 'Speakers (Dual DAC)';
  prefs.setDacFsVoltageAmpl(1.0 * SQRT2); prefs.setDacFsVoltageAmplRight(3.3 * SQRT2);
  const newAmpl = 4.0 * SQRT2;
  store.storeDacCalibrationChannel('R', newAmpl);
  ok(near(prefs.getDacFsVoltageAmpl(), 1.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), newAmpl), 'storeDac/INDEP R: only right scalar moved');
  const hi = store.resolveDeviceProfile('Speakers (Dual DAC)').output.ranges[1];
  ok(near(hi.fsRight, 4.0) && near(hi.fsLeft, 3.0), 'storeDac/INDEP R: fsRight = ampl/√2, fsLeft untouched');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Linked DAC', 'Speakers (Linked DAC)', DeviceChannelMode.LINKED, 'default', null, [mkRange('default', 2.0, 2.2)]));
  prefs.current().outputDeviceName = 'Speakers (Linked DAC)';
  prefs.setDacFsVoltageAmpl(2.0 * SQRT2); prefs.setDacFsVoltageAmplRight(2.2 * SQRT2);
  const newAmpl = 3.5 * SQRT2;
  store.storeDacCalibrationChannel('R', newAmpl);
  ok(near(prefs.getDacFsVoltageAmpl(), 2.0 * SQRT2) && near(prefs.getDacFsVoltageAmpl('R'), newAmpl), 'storeDac/LINKED R: only right scalar moved');
  const shared = store.resolveDeviceProfile('Speakers (Linked DAC)').output.ranges[0];
  ok(near(shared.fsLeft, 2.0) && near(shared.fsRight, 3.5), 'storeDac/LINKED R: shared row fsRight = ampl/√2');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Mono DAC', 'Speakers (Mono DAC)', DeviceChannelMode.MONO, 'default', null, [mkRange('default', 2.0, 2.0)]));
  prefs.current().outputDeviceName = 'Speakers (Mono DAC)';
  const newAmpl = 2.5 * SQRT2;
  store.storeDacCalibrationChannel('R', newAmpl);
  ok(near(prefs.getDacFsVoltageAmpl(), newAmpl) && near(prefs.getDacFsVoltageAmpl('R'), newAmpl), 'storeDac/MONO: both scalars together');
  const active = store.resolveDeviceProfile('Speakers (Mono DAC)').output.ranges[0];
  ok(near(active.fsLeft, 2.5) && near(active.fsRight, 2.5), 'storeDac/MONO: both row fields equal (RMS)');
}
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(outputProfile('Linked DAC', 'Speakers (Linked DAC)', DeviceChannelMode.LINKED, 'default', null, [mkRange('default', 2.0, 2.2)]));
  prefs.current().outputDeviceName = 'Speakers (Linked DAC)';
  const newAmpl = 2.79351;
  store.storeDacCalibration(newAmpl);
  ok(near(prefs.getDacFsVoltageAmpl(), newAmpl) && near(prefs.getDacFsVoltageAmpl('R'), newAmpl), 'storeDac/legacy: both scalars equal');
  const active = store.resolveDeviceProfile('Speakers (Linked DAC)').output.ranges[0];
  ok(near(active.fsLeft, newAmpl / SQRT2) && near(active.fsRight, newAmpl / SQRT2), 'storeDac/legacy: both row fields equal (RMS)');
}

// dacFsVoltageAmpl_isChannelAware + dacRightLaneScale
{
  const p = new Preferences(true);
  p.setDacFsVoltageAmpl(2.0); p.setDacFsVoltageAmplRight(3.0);
  ok(near(p.getDacFsVoltageAmpl('L'), 2.0) && near(p.getDacFsVoltageAmpl('R'), 3.0), 'dacFs: channel-aware');
  ok(near(p.getDacFsVoltageAmpl(), 2.0), 'dacFs: channel-less getter is LEFT/legacy');
  ok(near(p.dacRightLaneScale(), 2.0 / 3.0), 'dacRightLaneScale: fsLeft/fsRight');
  const q = new Preferences(true);
  q.setDacFsVoltageAmpl(2.0);   // right stays at default (positive) - force non-positive to exercise the 1.0 branch
  q.dacFsVoltageAmplRight.set(0);
  ok(near(q.dacRightLaneScale(), 1.0), 'dacRightLaneScale: 1.0 when right ≤ 0');
}

// =========================================================================
// F. Calibration-write device LABEL threading (web accommodation)
//    The web's inputDeviceName slot holds a Web Audio deviceId, not a resolvable
//    label - the calibrate dialog threads the human LABEL in so resolve/seed work.
// =========================================================================

// storeAdcCalibration(fsVrms, deviceLabel) resolves/seeds by the LABEL, not the id slot
{
  const { store, prefs } = fresh();
  prefs.current().inputDeviceName = 'opaque-web-deviceId';       // NOT a resolvable label
  store.storeAdcCalibration(2.5, 'Line (Generic USB Codec)');    // pass the human label
  ok(store.findAudioDeviceProfile('Generic USB Codec') != null, 'label threading (ADC): card created under the normalized label name');
  ok(store.resolveDeviceProfile('Line (Generic USB Codec)') != null, 'label threading (ADC): resolvable by the passed label');
  ok(store.resolveDeviceProfile('opaque-web-deviceId') === null, 'label threading (ADC): the id slot created nothing');
  ok(near(prefs.getAdcFsVoltageRms(), 2.5), 'label threading (ADC): scalar set');
}

// storeDacCalibration(fsAmpl, deviceLabel) uses the output LABEL
{
  const { store, prefs } = fresh();
  prefs.current().outputDeviceName = 'opaque-out-id';
  store.storeDacCalibration(2.79351, 'Speakers (Widget DAC)');
  ok(store.findAudioDeviceProfile('Widget DAC') != null, 'label threading (DAC): card created under the normalized label name');
  ok(store.resolveDeviceProfile('opaque-out-id') === null, 'label threading (DAC): the id slot created nothing');
}

// storeAdcCalibrationChannel(ch, fsVrms, deviceLabel) threads the label too
{
  const { store, prefs } = fresh();
  store.putAudioDeviceProfile(inputProfile('Dual Card', 'Line (Dual Card)', DeviceChannelMode.INDEPENDENT, '1.7V', '3.4V', [mkRange('1.7V', 1.70, 1.71), mkRange('3.4V', 3.40, 3.42)]));
  prefs.current().inputDeviceName = 'opaque-id';                 // id slot must be ignored
  store.storeAdcCalibrationChannel('R', 3.99, 'Line (Dual Card)');
  const bound = store.resolveDeviceProfile('Line (Dual Card)');
  ok(near(bound.input.ranges[1].fsRight, 3.99), 'label threading (ADC per-channel): wrote via the passed label');
  ok(near(prefs.getAdcFsVoltageRms('R'), 3.99), 'label threading (ADC per-channel): right scalar moved');
}

// omitting deviceLabel still falls back to the id slot (the existing single-arg contract)
{
  const { store, prefs } = fresh();
  prefs.current().inputDeviceName = 'Generic USB Codec';         // a label in the slot (test convention)
  store.storeAdcCalibration(2.5);                                // no label arg
  ok(store.findAudioDeviceProfile('Generic USB Codec') != null, 'label threading: omitted label falls back to the device-name slot');
}

// =========================================================================
// G. isBoundStereo - the per-channel-vs-shared calibrate-write decision
// =========================================================================
{
  const { store } = fresh();
  store.putAudioDeviceProfile(inputProfile('Stereo In', 'Line (Stereo In)', DeviceChannelMode.INDEPENDENT, 'a', 'a', [mkRange('a', 1, 1)]));
  store.putAudioDeviceProfile(inputProfile('Mono In', 'Line (Mono In)', DeviceChannelMode.MONO, 'a', null, [mkRange('a', 1, 1)]));
  ok(store.isBoundStereo('Line (Stereo In)', true) === true, 'isBoundStereo: INDEPENDENT input -> true (per-channel write)');
  ok(store.isBoundStereo('Line (Mono In)', true) === false, 'isBoundStereo: MONO input -> false (shared write)');
  ok(store.isBoundStereo('Unbound Device', true) === false, 'isBoundStereo: unbound device -> false (shared write)');
  const s2 = fresh();
  s2.store.putAudioDeviceProfile(outputProfile('Linked Out', 'Speakers (Linked Out)', DeviceChannelMode.LINKED, 'd', null, [mkRange('d', 1, 1)]));
  ok(s2.store.isBoundStereo('Speakers (Linked Out)', false) === true, 'isBoundStereo: LINKED output -> true (per-channel write)');
  // A card's other-direction endpoint defaults to LINKED (channels-only check, no ranges gate) -
  // faithful to Java isInputBoundStereo which tests only getChannels() != MONO.
  ok(s2.store.isBoundStereo('Speakers (Linked Out)', true) === true, 'isBoundStereo: channels-only check -> default LINKED input endpoint is stereo');
  const s3 = fresh();
  s3.store.putAudioDeviceProfile(outputProfile('Mono Out', 'Speakers (Mono Out)', DeviceChannelMode.MONO, 'd', null, [mkRange('d', 1, 1)]));
  ok(s3.store.isBoundStereo('Speakers (Mono Out)', false) === false, 'isBoundStereo: MONO output -> false (shared write)');
}

// =========================================================================
// H. Card-editor decision logic (CardEditorDialog pure brain)
// =========================================================================

// capabilityOf - derived from which endpoints carry ranges
{
  const inOnly = inputProfile('A', 'a', DeviceChannelMode.LINKED, 'r', null, [mkRange('r', 1, 1)]);
  const outOnly = outputProfile('B', 'b', DeviceChannelMode.LINKED, 'r', null, [mkRange('r', 1, 1)]);
  const both = inputProfile('C', 'c', DeviceChannelMode.LINKED, 'r', null, [mkRange('r', 1, 1)]);
  both.output.channels = DeviceChannelMode.LINKED; both.output.ranges.push(mkRange('r', 2, 2)); both.output.activeRange = 'r';
  ok(capabilityOf(inOnly) === Capability.INPUT_ONLY, 'capabilityOf: input-only');
  ok(capabilityOf(outOnly) === Capability.OUTPUT_ONLY, 'capabilityOf: output-only');
  ok(capabilityOf(both) === Capability.BOTH, 'capabilityOf: both');
}

// seedIsMono
{
  const mono = inputProfile('M', 'm', DeviceChannelMode.MONO, 'r', null, [mkRange('r', 1, 1)]);
  const stereo = inputProfile('S', 's', DeviceChannelMode.LINKED, 'r', null, [mkRange('r', 1, 1)]);
  ok(seedIsMono(mono) === true, 'seedIsMono: a MONO endpoint -> mono card');
  ok(seedIsMono(stereo) === false, 'seedIsMono: LINKED endpoints -> stereo card');
}

// nameTaken
{
  const names = ['Cosmos ADC', 'Widget DAC'];
  ok(nameTaken('cosmos adc', names, null) === true, 'nameTaken: case-insensitive clash on create');
  ok(nameTaken('Cosmos ADC', names, 'Cosmos ADC') === false, 'nameTaken: unchanged edit name allowed');
  ok(nameTaken('Widget DAC', names, 'Cosmos ADC') === true, 'nameTaken: rename onto another card rejected');
  ok(nameTaken('Brand New', names, null) === false, 'nameTaken: a genuinely new name is free');
}

// droppingCalibrated
{
  const cal = new DeviceEndpointConfig(); cal.ranges.push(mkRange('r', 1, 1, true));
  const nom = new DeviceEndpointConfig(); nom.ranges.push(mkRange('r', 1, 1, false));
  ok(droppingCalibrated(cal, false) === true, 'droppingCalibrated: turning off a calibrated direction warns');
  ok(droppingCalibrated(cal, true) === false, 'droppingCalibrated: keeping the direction never warns');
  ok(droppingCalibrated(nom, false) === false, 'droppingCalibrated: an uncalibrated direction drops silently');
}

// parseMatch
{
  const out = parseMatch('  Cosmos ADC \n\nLine (Cosmos)\ncosmos adc\n  ');
  ok(JSON.stringify(out) === JSON.stringify(['Cosmos ADC', 'Line (Cosmos)']),
    'parseMatch: trims, drops blanks, de-dupes case-insensitively, keeps typed order');
}

// assembleProfile - the OK build path
{
  const seed = new AudioDeviceProfile();
  seed.name = 'seed';   // never carried into the result's identity here (name comes from o.name)
  const built = assembleProfile(seed, {
    name: 'My Card', match: ['Line (My Card)', 'My Card'],
    wantInput: true, wantOutput: false, mono: false,
    inputCoupling: DeviceChannelMode.INDEPENDENT, outputCoupling: DeviceChannelMode.LINKED,
    inputSeedFs: 1.7931, outputSeedFs: 1.975, defaultRangeLabel: 'default',
  });
  ok(built.name === 'My Card', 'assemble: name from the editor');
  ok(JSON.stringify(built.match) === JSON.stringify(['Line (My Card)', 'My Card']), 'assemble: match list');
  ok(built.input.channels === DeviceChannelMode.INDEPENDENT, 'assemble: stereo input coupling applied');
  ok(built.input.ranges.length === 1 && built.input.ranges[0].label === 'default'
    && near(built.input.ranges[0].fsLeft, 1.7931) && near(built.input.ranges[0].fsRight, 1.7931),
    'assemble: newly enabled input seeded with one default range at seedFs');
  ok(built.input.activeRange === 'default' && built.input.activeRangeRight === 'default', 'assemble: default row active both channels');
  ok(built.output.ranges.length === 0 && built.output.activeRange === null, 'assemble: the off direction is dropped');
}

// assembleProfile - mono forces MONO on every enabled endpoint; seed ranges survive (deep copy)
{
  const seed = inputProfile('X', 'x', DeviceChannelMode.LINKED, 'gain', null, [mkRange('gain', 2.5, 2.7, true)]);
  const built = assembleProfile(seed, {
    name: 'X', match: ['x'],
    wantInput: true, wantOutput: false, mono: true,
    inputCoupling: DeviceChannelMode.LINKED, outputCoupling: DeviceChannelMode.LINKED,
    inputSeedFs: 9.9, outputSeedFs: 9.9, defaultRangeLabel: 'default',
  });
  ok(built.input.channels === DeviceChannelMode.MONO, 'assemble/mono: enabled input -> MONO');
  ok(built.input.ranges.length === 1 && built.input.ranges[0].label === 'gain'
    && near(built.input.ranges[0].fsLeft, 2.5) && built.input.ranges[0].calibrated === true,
    'assemble/mono: existing calibrated range survives (not re-seeded)');
  // the seed must not have been mutated (still LINKED)
  ok(seed.input.channels === DeviceChannelMode.LINKED, 'assemble: the seed is never mutated');
}

// =========================================================================
// I. devices.yaml parser (parseDevicesYaml) - over the shipped web/devices.yaml
//    (the byte-verbatim copy of the Java src/main/resources/devices.yaml) plus the
//    documented vocabulary forms the file itself doesn't happen to exercise.
// =========================================================================

// the real shipped file. Its fsVrms values are round NOMINALS by design (the file's own header
// says so) - release 1.1.0 replaced four leaked bench-measured doubles with the nominals they
// were always meant to be, so nothing here may assert a measured full-scale. Full double
// precision is a parser property, not a catalog property: it is covered on synthetic YAML below.
{
  const doc = CATALOG;
  ok(doc.formatVersion === 1, 'parser: formatVersion 1');
  ok(doc.contentVersion === 2, 'parser: contentVersion 2');
  ok(Array.isArray(doc.audioDevices) && doc.audioDevices.length >= 3, 'parser: 3+ cards');
  const cosmos = doc.audioDevices.find((d) => d.name === 'E1DA Cosmos ADC');
  ok(cosmos != null, 'parser: Cosmos card present');
  ok(JSON.stringify(cosmos.match) === JSON.stringify(['E1DA Cosmos ADC']),
    'parser: match flow list of one quoted string');
  ok(cosmos.input.channels === 'INDEPENDENT', 'parser: INDEPENDENT channel mode (bare string)');
  ok(cosmos.input.ranges.length === 9, 'parser: Cosmos 9 range rows (block list of flow maps)');
  ok(cosmos.input.ranges[0].label === '1.7V', 'parser: quoted range label');
  ok(cosmos.input.ranges[0].fsVrms.left === 1.7 && cosmos.input.ranges[0].fsVrms.right === 1.7,
    'parser: fractional fsVrms 1.7 on both channels (nested flow map)');
  ok(cosmos.input.ranges[7].fsVrms.left === 10 && cosmos.input.ranges[8].fsVrms.left === 43,
    'parser: integer fsVrms values (10, 43)');
  ok(cosmos.input.activeRange && cosmos.input.activeRange.left === '1.7V'
    && cosmos.input.activeRange.right === '1.7V',
    'parser: activeRange { left, right } map form (INDEPENDENT)');
  const i2s = doc.audioDevices.find((d) => d.name === 'I2SoverUSB');
  ok(JSON.stringify(i2s.match) === JSON.stringify(['I2SoverUSB', 'JLsounds']),
    'parser: multi-entry match flow list');
  ok(i2s.output.channels === 'LINKED', 'parser: LINKED channel mode');
  ok(i2s.output.activeRange === 'default', 'parser: activeRange scalar form (LINKED)');
  ok(i2s.output.ranges[0].fsVrms.left === 2 && i2s.output.ranges[0].fsVrms.right === 2,
    'parser: I2S single default range at the nominal 2 V');
  const cub = doc.audioDevices.find((d) => d.name === 'CUBILUX CB5');
  ok(cub.input != null && cub.output != null, 'parser: CUBILUX has both input + output endpoints');
  ok(cub.input.ranges[0].fsVrms.left === 2 && cub.output.ranges[0].fsVrms.left === 1.2,
    'parser: CUBILUX per-direction nominals kept apart (in 2 V, out 1.2 V)');
}

// documented-vocabulary forms not present in the shipped file: scalar fsVrms shorthand,
// a boolean flag (calibrated), unquoted labels, trailing comments, INDEPENDENT activeRange map,
// full-precision doubles (a CALIBRATED store carries 17 significant digits even though the
// nominal seed does not - the parser must not round them)
{
  const y = [
    '# a full-line comment',
    'formatVersion: 1',
    'contentVersion: 3   # a trailing comment (must be stripped)',
    'audioDevices:',
    '  - name: Scalar Card',
    '    match: [ "Scalar" ]',
    '    input:',
    '      channels: LINKED',
    '      ranges:',
    '        - { label: "0 dB", fsVrms: 1.5, calibrated: true }',
    '      activeRange: "0 dB"',
    '    output:',
    '      channels: INDEPENDENT',
    '      ranges:',
    '        - { label: lo, fsVrms: { left: 2, right: 3 } }',
    '        - { label: hi, fsVrms: { left: 1.7910482002478434, right: 4.629020031592054 } }',
    '      activeRange: { left: lo, right: lo }',
    '',
  ].join('\n');
  const d = parseDevicesYaml(y);
  ok(d.contentVersion === 3, 'parser: int scalar with a trailing comment');
  const c = d.audioDevices[0];
  ok(c.input.ranges[0].calibrated === true, 'parser: boolean true');
  ok(c.input.ranges[0].fsVrms === 1.5, 'parser: scalar fsVrms shorthand (a bare number)');
  ok(c.output.ranges[0].fsVrms.left === 2 && c.output.ranges[0].fsVrms.right === 3,
    'parser: unquoted flow-map label + integer { left, right } pair');
  ok(c.output.ranges[1].fsVrms.left === 1.7910482002478434
    && c.output.ranges[1].fsVrms.right === 4.629020031592054,
    'parser: exact double round-trip of 17-digit measured values (nested flow map)');
  ok(c.output.activeRange.left === 'lo' && c.output.activeRange.right === 'lo',
    'parser: INDEPENDENT activeRange map with unquoted labels');
  // The parsed doc must drive the store cleanly (round-trips through the seed reader).
  const seeded = fresh(d).store;
  ok(seeded.findAudioDeviceProfile('Scalar Card') != null, 'parser: parsed doc seeds the store');
}

// malformed input throws with a line number
{
  let e1 = null;
  try { parseDevicesYaml('formatVersion: 1\naudioDevices:\n  - { label: "x", fsVrms: { left: 1, right: 2 }'); }
  catch (e) { e1 = e; }
  ok(e1 != null && /line \d+/.test(e1.message), 'parser: unterminated flow map throws with a line number');
  let e2 = null;
  try { parseDevicesYaml('formatVersion 1'); }   // a mapping line with no colon
  catch (e) { e2 = e; }
  ok(e2 != null && /line \d+/.test(e2.message), 'parser: a key without a colon throws with a line number');
}

console.log(`\ndevice-profiles: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
