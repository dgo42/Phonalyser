/*
 * Phonalyser web - AudioWorklet capture processor.
 * Runs on the realtime audio thread of the INPUT AudioContext: buffers BOTH
 * captured channels (L = inputs[0][0], R = inputs[0][1]||inputs[0][0]) and posts
 * fixed-size Float32 stereo blocks (transferred, zero-copy) to the main thread,
 * decoupling the realtime thread from the shared ring + the scope/FFT pipelines.
 * Mirrors the Java SharedCapture PCM batch listener, which stages L and R into
 * the SignalBuffer (ch1 = R is the calibrated/attenuated measured channel).
 * GNU AGPL v3 or later.
 */
class CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    // Post one batch per display frame (~60 Hz) so the ring - and therefore the scope/FFT
    // refresh - runs at a steady rate REGARDLESS of sample rate. A fixed sample-COUNT block
    // made the refresh rate = sampleRate/block (≈47 fps @ 384 kHz but only ≈5.8 fps @ 48 kHz,
    // independent of time/div). `sampleRate` is the AudioWorkletGlobalScope global = the
    // input context's actual rate. A caller can still force a block via processorOptions.
    const opt = options && options.processorOptions && options.processorOptions.block;
    const block = opt || Math.max(256, Math.min(Math.round(sampleRate / 60), 8192));
    this._l = new Float32Array(block);
    this._r = new Float32Array(block);
    this._w = 0;
  }
  process(inputs) {
    const inp = inputs[0];
    if (!inp || !inp[0]) return true;
    const l = inp[0];                        // ch0
    const r = inp[1] || inp[0];              // ch1 = the calibrated/attenuated channel (Java baseline); ch0 fallback if mono
    for (let i = 0; i < l.length; i++) {
      this._l[this._w] = l[i];
      this._r[this._w] = r[i];
      this._w++;
      if (this._w >= this._l.length) {
        const block = this._l.length;
        this.port.postMessage({ l: this._l, r: this._r, n: block }, [this._l.buffer, this._r.buffer]);
        this._l = new Float32Array(block);
        this._r = new Float32Array(block);
        this._w = 0;
      }
    }
    return true;
  }
}
registerProcessor('capture-processor', CaptureProcessor);
