/*
 * Phonalyser web - WHY a device would not play or record.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The wording side of enums/device-failure-reason.js (the enum itself, Java
 * org.edgo.audio.measure.enums.DeviceFailureReason): the full sentence is produced only at the
 * wording boundaries - shared-capture.js for a lane that would not record,
 * generator-controller.js for one that would not play - which is what stops those call sites
 * from each inventing their own mapping; what lives here is the one DETAIL rule both of them
 * frame (failureDetailText), because a rule copied into two boundaries is a rule that drifts,
 * the shell's alert body, and the browser's own failure vocabulary mapped onto the reasons.
 */

import { t } from '../i18n/i18n.js';
import { DeviceFailureReason, deviceFailureReasonFromName } from '../enums/device-failure-reason.js';

/**
 * WHY a lane would not open, in the operator's words - the one detail both wording boundaries
 * frame in their own sentence.
 *
 * The localized reason word wins whenever the backend could classify the failure: it is the
 * operator's language, and the desktop shows the same word. It is only when nobody could
 * classify it - UNKNOWN - that "reason unknown" is all the reason has to say, and a failure
 * that arrived WITH a sentence of its own says more: a bench refusal carries spec 4.2's code,
 * the server's message and the holder of the lock ("... (held by Developer's laptop)"), which is
 * an answer the operator can act on. That sentence is composed from PROTOCOL fields by the
 * client itself (NetConnection.refusal), never a driver's raw text - a DOMException name or a
 * PortAudio code stays in the log, where the standing rule keeps it.
 *
 * @param {{name: string, i18nKey: string}} reason the backend's reading of its own failure
 * @param {?string} refusalText the sentence the failure came with, or null
 * @returns {string} the detail, never empty
 */
export function failureDetailText(reason, refusalText) {
  if (reason !== DeviceFailureReason.UNKNOWN || !refusalText) return t(reason.i18nKey);
  return refusalText;
}

/**
 * The body of the shell's device-error alert for a publisher that sent no sentence of its own -
 * composed the DESKTOP's way, from the desktop's own keys (the web used to keep three
 * pre-composed `web.audio.deviceError.*` sentences here, each hard-coding "in use by another
 * application" whatever had actually happened, so a device that was NOT FOUND was reported as
 * held by someone else).
 *
 * The reason word is the whole message on the capture side - which is what the net client
 * already publishes for a refused bench device - and on the playback side it fills the desktop's
 * own carrier, {@code generator.error.openDeviceFailed} ("Could not open the output device: ...",
 * GeneratorPane's wording). The capture side has no one-argument counterpart in the desktop
 * bundle (its opener names the device, rate and width, which a payload this thin does not
 * carry), so it states the reason alone rather than inventing a sentence.
 *
 * @param {?{direction: ?string, reason: ?string}} payload the AUDIO_DEVICE_ERROR payload
 * @returns {string} the sentence to show, never empty - "reason unknown" at worst
 */
export function deviceErrorText(payload) {
  const detail = t(deviceFailureReasonFromName(payload && payload.reason).i18nKey);
  return (payload && payload.direction === 'output')
    ? t('generator.error.openDeviceFailed', detail) : detail;
}

/**
 * The browser's device vocabulary -> a reason. This is the web's whole
 * AudioDeviceManager.classifyFailure table: the DOMException names getUserMedia and the
 * AudioContext constructor reject with are the only native error vocabulary a browser exposes,
 * and BOTH lanes (capture through getUserMedia, playback through the output AudioContext) speak
 * it - so the one table lives here and each lane's classifyFailure delegates to it, exactly as
 * the desktop's WDM-KS and CoreAudio backends delegate to the PortAudio message table.
 *
 * Anything else is UNKNOWN, which is always a legal answer. NotAllowedError / SecurityError are
 * deliberately NOT mapped: a permission denial is not a device failure and is handled
 * status-only by the caller.
 *
 * @param {?Error} err the rejection from getUserMedia / new AudioContext
 * @returns {{name: string, i18nKey: string}} the reason, never null
 */
export function classifyBrowserFailure(err) {
  switch (err && err.name) {
    case 'NotFoundError':        return DeviceFailureReason.DEVICE_NOT_FOUND;
    case 'NotReadableError':     return DeviceFailureReason.DEVICE_IN_USE;
    case 'OverconstrainedError': return DeviceFailureReason.FORMAT_UNSUPPORTED;
    default:                     return DeviceFailureReason.UNKNOWN;
  }
}
