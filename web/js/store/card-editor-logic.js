/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// The SWT-free decision brain of gui/preferences/CardEditorDialog — the pure functions the card
// create/edit dialog runs (capability derivation, mono detection, name-uniqueness, drop-calibrated
// detection, match-list parsing, and the OK profile-assembly build path). The DOM dialog
// (shell/card-editor-dialog.js) owns only the widgets + i18n and delegates every decision here, so
// the model behaviour can be tested headlessly. Java line references are cited per function.

import { AudioDeviceProfile, DeviceRange } from './device-profiles.js';
import { DeviceChannelMode } from './device-enums.js';

/** Which endpoint blocks a card carries ranges for — the direction radios
 *  (CardEditorDialog.Capability). */
export const Capability = Object.freeze({
  INPUT_ONLY: 'INPUT_ONLY',
  OUTPUT_ONLY: 'OUTPUT_ONLY',
  BOTH: 'BOTH',
});

/** Derives the capability of an existing profile from which endpoints actually carry
 *  ranges — the edit-form initial selection (Capability.of). */
export function capabilityOf(p) {
  const inHas = p.input.ranges.length > 0;
  const outHas = p.output.ranges.length > 0;
  if (inHas && outHas) return Capability.BOTH;
  if (outHas) return Capability.OUTPUT_ONLY;
  return Capability.INPUT_ONLY;
}

/** Whether the seed profile is a mono card — any endpoint declared MONO. A stereo card
 *  has both endpoints on a stereo mode (LINKED / INDEPENDENT) (CardEditorDialog.seedIsMono). */
export function seedIsMono(p) {
  return p.input.channels === DeviceChannelMode.MONO
    || p.output.channels === DeviceChannelMode.MONO;
}

/** True when {@code name} clashes (case-insensitive) with a card OTHER than the one being
 *  edited — so an unchanged edit name is allowed but a rename onto another card is rejected
 *  (CardEditorDialog.nameTaken). */
export function nameTaken(name, existingNames, originalName) {
  if (originalName != null && name.toLowerCase() === originalName.toLowerCase()) return false;
  const lower = name.toLowerCase();
  for (const e of existingNames) {
    if (e != null && String(e).toLowerCase() === lower) return true;
  }
  return false;
}

/** True when turning off {@code want} would delete an endpoint that carries at least one
 *  real (calibrated) range (CardEditorDialog.droppingCalibrated). */
export function droppingCalibrated(ep, want) {
  if (want) return false;
  for (const r of ep.ranges) {
    if (r.calibrated) return true;
  }
  return false;
}

/** The match list as trimmed, non-blank, case-insensitively de-duplicated entries in typed
 *  order — the recognition/binding list of the built card (CardEditorDialog.parseMatch). */
export function parseMatch(text) {
  const entries = [];
  for (const line of String(text == null ? '' : text).split(/\r\n?|\n/)) {
    const entry = line.trim();
    if (entry === '') continue;
    if (entries.some((s) => s.toLowerCase() === entry.toLowerCase())) continue;
    entries.push(entry);
  }
  return entries;
}

/** Enforces the chosen direction on one endpoint: an off direction drops its ranges and active
 *  labels; an on direction with no ranges is seeded with a single {@code default} range at
 *  {@code seedFs} (CardEditorDialog.applyCapability). */
function applyCapability(ep, want, seedFs, defaultLabel) {
  if (!want) {
    ep.ranges.length = 0;
    ep.activeRange = null;
    ep.activeRangeRight = null;
    return;
  }
  if (ep.ranges.length === 0) {
    const range = new DeviceRange();
    range.label = defaultLabel;
    range.fsLeft = seedFs;
    range.fsRight = seedFs;
    ep.ranges.push(range);
    ep.activeRange = range.label;
    ep.activeRangeRight = range.label;
  }
}

/**
 * Assembles a fresh profile from the seed + the editor's choices — the OK build path
 * (CardEditorDialog.onOk after validation). Never mutates {@code seed}: the endpoints are
 * deep-copied so the seed's calibrated ranges survive. Mono → MONO on every enabled endpoint;
 * Stereo → each direction's own coupling (LINKED / INDEPENDENT). A newly enabled direction is
 * seeded with one {@code default} range from the passed-in full-scale; a direction turned off
 * has its ranges dropped.
 *
 * The device-owned {@code calibrationFromDevice} flag is pure data with no web UI (its checkbox
 * was removed): the deep-copy carries it from the seed endpoint into the built profile unchanged,
 * so an edit round-trips it (Java sets it from a real checkbox — the web has no such control).
 *
 * @param {AudioDeviceProfile} seed
 * @param {{name:string, match:string[], wantInput:boolean, wantOutput:boolean, mono:boolean,
 *          inputCoupling:string, outputCoupling:string, inputSeedFs:number, outputSeedFs:number,
 *          defaultRangeLabel:string}} o
 * @returns {AudioDeviceProfile}
 */
export function assembleProfile(seed, o) {
  const out = new AudioDeviceProfile();
  out.name = o.name;
  out.match = o.match.slice();
  const inputEp = seed.input.deepCopy();
  const outputEp = seed.output.deepCopy();
  inputEp.channels = o.mono ? DeviceChannelMode.MONO : o.inputCoupling;
  outputEp.channels = o.mono ? DeviceChannelMode.MONO : o.outputCoupling;
  applyCapability(inputEp, o.wantInput, o.inputSeedFs, o.defaultRangeLabel);
  applyCapability(outputEp, o.wantOutput, o.outputSeedFs, o.defaultRangeLabel);
  out.input = inputEp;
  out.output = outputEp;
  return out;
}
