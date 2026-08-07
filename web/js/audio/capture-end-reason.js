/*
 * Phonalyser web - WHY a capture ended on its own.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.sound.CaptureEndReason - the machine-readable value
 * the bottom layer hands up when a running capture dies, so silence is no longer
 * indistinguishable from measuring.
 *
 * The UI localizes the VALUE (the pane builds "capture.error.ended." + reason.name); the English
 * text here is for the LOG only, never for the operator - i18n lives in the UI layer and nowhere
 * below it: no operator-facing string ever originates in a bottom layer.
 */

/**
 * The end reasons, as value objects carrying their log text (Java: the enum constant + logText()).
 * @enum {{name: string, logText: string}}
 */
export const CaptureEndReason = Object.freeze({
  /** The device was unplugged, invalidated, or its stream failed in a way the backend cannot
   *  recover from. */
  DEVICE_LOST: Object.freeze({
    name: 'DEVICE_LOST',
    logText: 'the capture device was unplugged or its stream failed',
  }),
  /** A started stream delivered nothing for longer than its deadline - the loss signal for
   *  devices that tell us nothing themselves. */
  DELIVERY_STALLED: Object.freeze({
    name: 'DELIVERY_STALLED',
    logText: 'the capture delivered nothing within its deadline',
  }),
});
