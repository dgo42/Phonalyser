/*
 * Phonalyser web — AudioWorklet generator processor.
 * Runs on the realtime audio thread of the OUTPUT AudioContext. A phase
 * accumulator emits a sine; the frequency increment `inc` (rad/sample) is
 * retuned live by the main thread (the FLL steers it onto the captured tone).
 * NOTE: this is the minimal sine generator that drives the loopback null test;
 * the full DDS (triangle / rect / dual-tone / pink noise) lands with the
 * generator module port.
 * GNU AGPL v3 or later.
 */
const TWO_PI = 2 * Math.PI;

class GeneratorProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this._ph = 0; this._inc = 0; this._amp = 0;
    this.port.onmessage = (e) => {
      if (e.data.inc != null) this._inc = e.data.inc;
      if (e.data.amp != null) this._amp = e.data.amp;
    };
  }
  process(_inputs, outputs) {
    const out = outputs[0];
    const n = out[0].length;
    for (let i = 0; i < n; i++) {
      const s = this._amp * Math.sin(this._ph);
      for (let c = 0; c < out.length; c++) out[c][i] = s;
      this._ph += this._inc;
      if (this._ph >= TWO_PI) this._ph -= TWO_PI;
    }
    return true;
  }
}
registerProcessor('generator-processor', GeneratorProcessor);
