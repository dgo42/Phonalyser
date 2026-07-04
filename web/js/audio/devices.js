/*
 * Phonalyser web — audio input/output device enumeration + native sample-rate probe.
 *
 * The device layer is kept OUT of the audio engine (mirrors the Java desktop, where device
 * enumeration is its own concern, not part of capture/generate). Rate verification is
 * frame-accurate per docs/htmls/audio-devices.html: a device is opened unconstrained and its
 * TRUE rate read from the captured audio (getSettings() can report the requested rate even when
 * the OS silently resampled).
 * GNU Affero General Public License v3 or later.
 */

/** Ground-truth sample rate of a live track: pulls one decoded audio frame and reads its real
 *  rate via MediaStreamTrackProcessor (Chromium only); null on other engines / timeout / no frame. */
export async function frameRate(track, timeoutMs = 600) {
  if (typeof MediaStreamTrackProcessor === 'undefined') return null;
  let reader, timedOut = false;
  try {
    reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
    const timeout = new Promise((res) => setTimeout(() => { timedOut = true; res(null); }, timeoutMs));
    // Close the AudioData even when the timeout wins the race — read() may still resolve later
    // with a real frame, whose native memory must be explicitly close()d (cancel() won't).
    const readP = reader.read().then((r) => { if (timedOut) { if (r.value) r.value.close(); return null; } return r.value; });
    const frame = await Promise.race([readP, timeout]);
    if (!frame) return null;
    const rate = frame.sampleRate;
    frame.close();
    return rate;
  } catch (_) { return null; }
  finally { if (reader) { try { await reader.cancel(); } catch (_) { /* ignore */ } } }
}

/** Opens one input device with NO rate constraint and reads its NATIVE rate — the only truly
 *  meaningful input rate. Frame-verified where supported, else getSettings(). Returns the rate,
 *  or null when the device can't be opened or its rate can't be determined. */
export async function nativeInputRate(deviceId) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: {
      deviceId: deviceId ? { exact: deviceId } : undefined, channelCount: { ideal: 2 },
      echoCancellation: false, noiseSuppression: false, autoGainControl: false } });
    const tr = stream.getAudioTracks()[0];
    const measured = await frameRate(tr);
    const reported = tr.getSettings ? (tr.getSettings().sampleRate || null) : null;
    return measured || reported || null;
  } catch (_) { return null; }
  finally { if (stream) stream.getTracks().forEach((t) => t.stop()); }
}

/**
 * Enumerates audio devices; each INPUT carries its determined native (maximal meaningful) rate.
 * Inputs whose rate can't be determined are DROPPED — but if NONE can be (an engine with neither
 * MediaStreamTrackProcessor nor a populated getSettings().sampleRate) every input is listed so the
 * user is never stranded. Outputs aren't per-device probeable in a browser tab (id + label only).
 *
 * @param {(t:string)=>void} [status] progress callback (probing each device takes ~½ s).
 */
export async function scanDevices(status = () => {}) {
  try { (await navigator.mediaDevices.getUserMedia({ audio: true })).getTracks().forEach((t) => t.stop()); }
  catch (e) { status('mic permission denied — ' + e.name); }
  const devs = await navigator.mediaDevices.enumerateDevices();
  const audioIn = devs.filter((d) => d.kind === 'audioinput');
  const determined = [];
  for (const d of audioIn) {
    status(`probing "${d.label || d.deviceId}" …`);
    const rate = await nativeInputRate(d.deviceId);
    if (rate) determined.push({ id: d.deviceId, label: d.label || d.deviceId || 'Default', nativeRate: rate });
  }
  const inputs = determined.length > 0 ? determined
    : audioIn.map((d) => ({ id: d.deviceId, label: d.label || d.deviceId || 'Default', nativeRate: null }));
  return {
    inputs,
    outputs: devs.filter((d) => d.kind === 'audiooutput').map((d) => ({ id: d.deviceId, label: d.label || d.deviceId })),
  };
}
