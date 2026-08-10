/*
 * Phonalyser web - the audio backend vocabulary.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of the web-reachable half of org.edgo.audio.measure.enums.AudioBackendType:
 * the enum NAME a preference stores and the human-readable name a dialog shows. The browser has
 * only the two paths it can actually drive (Web Audio always, the QA40x over WebUSB); the
 * desktop's OS backends keep their names here because a store written by an older build may
 * still carry one, and a message that names the backend must not print a bare enum constant.
 */

/** Enum name -> the name shown to the operator (Java AudioBackendType.getDisplayName). */
const DISPLAY_NAMES = Object.freeze({
  WASAPI: 'WASAPI',
  WDMKS: 'WDM-KS',
  COREAUDIO: 'CoreAudio',
  JAVASOUND: 'JavaSound',
  LOOPBACK: 'Loopback',
  WEB_AUDIO: 'Web Audio',
  QA40X: 'QA40x',
});

/**
 * The human-readable name of a backend, for a message the operator reads. An unknown name is
 * returned unchanged - a backend this build does not know is still better named than blanked.
 *
 * @param {?string} typeName the AudioBackendType enum name (e.g. 'WEB_AUDIO')
 * @returns {string} the display name (e.g. 'Web Audio')
 */
export function backendDisplayName(typeName) {
  if (!typeName) return '';
  return DISPLAY_NAMES[typeName] || typeName;
}
