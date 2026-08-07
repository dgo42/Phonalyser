/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// A fake `navigator.usb` carrying one QA403, for headless runs that must reach the QA40x backend
// without hardware: the help-screenshot capture and any end-to-end pass over the backend switch.
//
// It answers the REGISTER protocol only - a 5-byte big-endian frame on endpoint 1, a read being the
// same frame with the address MSB set and a 4-byte big-endian reply - which is everything the app
// needs to enumerate, open, read the factory calibration page, read the telemetry panel and drive
// the range/rate/I2S registers. AUDIO transfers (endpoint 2) are accepted and parked forever, since
// nothing that needs this fake streams: a screenshot never records, and a lane that never completes
// is exactly what an idle analyzer looks like.
//
// The returned source is a STRING evaluated by page.addInitScript, so it must be self-contained.

/** Cal-page geometry, mirroring qa40x-calibration.js (ADC base 24, DAC base 120, 2 records of 6). */
const CAL = { bytes: 512, record: 6, stride: 12, adcBase: 24, dacBase: 120 };

/**
 * The init script: installs the fake before any app code runs.
 *
 * @param {Object} [opts]
 * @param {number} [opts.productId] 0x4E39 = QA403 (default), 0x4E37 = QA402
 * @param {number} [opts.firmware] the firmware-version register's value
 * @returns {string} source for page.addInitScript
 */
export function fakeQa40xUsbInit({ productId = 0x4E39, firmware = 60 } = {}) {
  return `(() => {
  const CAL = ${JSON.stringify(CAL)};

  // A plausible factory page: every range's linear factor is 1.0 (0 dB), so the card the manager
  // builds carries the analyzer's NOMINAL full scales - the honest thing for a documentation shot,
  // where a per-unit trim would only be noise.
  const page = new Uint8Array(CAL.bytes);
  const view = new DataView(page.buffer);
  for (const base of [CAL.adcBase, CAL.dacBase]) {
    for (let code = 0; code < 8; code++) {
      for (const half of [0, CAL.record]) {
        // Each record is [level:u16][dB:float32 LE]; 0 dB => a linear factor of 1.
        view.setFloat32(base + code * CAL.stride + half + 2, 0.0, true);
      }
    }
  }

  const REG_CAL_PAGE_SELECT = 0x0D, REG_CAL_READ = 0x19;
  const TELEMETRY = {
    0x10: ${firmware},      // firmware version
    0x11: 4952,             // USB volts, mV
    0x12: 788,              // USB current, mA
    0x13: 120,              // ISO current, mA (QA402 only; harmless here)
    0x16: 250,              // board temperature, tenths of a degree
    0x1B: 0x40000040,       // capability
    0x1C: 0x7F31BD30,       // capability 2
    0x1D: 0x51A40001,       // serial number
  };

  let calWordIndex = 0;     // advances per REG_CAL_READ, as the real page read does
  let pending = null;       // the value the next endpoint-1 IN must return

  const device = {
    vendorId: 0x16C0, productId: ${productId},
    manufacturerName: 'QuantAsylum', productName: 'QA403',
    serialNumber: '51A40001', opened: false,
    configuration: { configurationValue: 1 },
    async open() { this.opened = true; },
    async close() { this.opened = false; },
    async selectConfiguration() {},
    async claimInterface() {},
    async releaseInterface() {},
    async reset() {},
    async transferOut(endpoint, data) {
      const bytes = new Uint8Array(data.buffer ?? data);
      if (endpoint !== 1) return { status: 'ok', bytesWritten: bytes.length };   // audio: accepted
      const reg = bytes[0];
      if ((reg & 0x80) !== 0) {                       // a READ request - stage the reply
        const address = reg & 0x7F;
        if (address === REG_CAL_READ) {
          const offset = (calWordIndex++ * 4) % CAL.bytes;
          pending = view.getUint32(offset, false);
        } else {
          pending = TELEMETRY[address] ?? 0;
        }
      } else if (reg === REG_CAL_PAGE_SELECT) {
        calWordIndex = 0;                             // page select restarts the word walk
      }
      return { status: 'ok', bytesWritten: bytes.length };
    },
    async transferIn(endpoint, length) {
      if (endpoint !== 1) return new Promise(() => {});   // audio IN: never completes (idle)
      const out = new Uint8Array(4);
      new DataView(out.buffer).setUint32(0, (pending ?? 0) >>> 0, false);
      pending = null;
      return { status: 'ok', data: new DataView(out.buffer, 0, Math.min(4, length)) };
    },
    addEventListener() {}, removeEventListener() {},
  };

  Object.defineProperty(navigator, 'usb', {
    configurable: true,
    value: {
      async getDevices() { return [device]; },
      async requestDevice() { return device; },
      addEventListener() {}, removeEventListener() {},
    },
  });
})()`;
}
