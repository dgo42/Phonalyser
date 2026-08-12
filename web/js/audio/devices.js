/*
 * Phonalyser web - audio input/output device enumeration + native sample-rate probe.
 *
 * The device layer is kept OUT of the audio engine (mirrors the Java desktop, where device
 * enumeration is its own concern, not part of capture/generate). Rate verification is
 * frame-accurate per docs/htmls/audio-devices.html: a device is opened unconstrained and its
 * TRUE rate read from the captured audio (getSettings() can report the requested rate even when
 * the OS silently resampled).
 *
 * Enumeration is also where the BACKEND dispatch lives: {@link scanDevicesForBackend} is the web's
 * AudioBackend.listInputDevices() / listOutputDevices() switch on the active backend - Web Audio
 * probes getUserMedia below, the QA402/QA403 enumerates through Qa40xDeviceManager over WebUSB.
 * GNU Affero General Public License v3 or later.
 */
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { remoteBackendOf } from '../net/net-device-ref.js';

/** Ground-truth sample rate of a live track: pulls one decoded audio frame and reads its real
 *  rate via MediaStreamTrackProcessor (Chromium only); null on other engines / timeout / no frame. */
export async function frameRate(track, timeoutMs = 600) {
  if (typeof MediaStreamTrackProcessor === 'undefined') return null;
  let reader, timedOut = false;
  try {
    reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
    const timeout = new Promise((res) => setTimeout(() => { timedOut = true; res(null); }, timeoutMs));
    // Close the AudioData even when the timeout wins the race - read() may still resolve later
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

/** Opens one input device with NO rate constraint and reads its NATIVE rate - the only truly
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
 * Inputs whose rate can't be determined are DROPPED - but if NONE can be (an engine with neither
 * MediaStreamTrackProcessor nor a populated getSettings().sampleRate) every input is listed so the
 * user is never stranded. Outputs aren't per-device probeable in a browser tab (id + label only).
 *
 * @param {(t:string)=>void} [status] progress callback (probing each device takes ~½ s).
 */
export async function scanDevices(status = () => {}) {
  try { (await navigator.mediaDevices.getUserMedia({ audio: true })).getTracks().forEach((t) => t.stop()); }
  catch (e) { status('mic permission denied - ' + e.name); }
  const devs = await navigator.mediaDevices.enumerateDevices();
  const audioIn = devs.filter((d) => d.kind === 'audioinput');
  const determined = [];
  for (const d of audioIn) {
    status(`probing "${d.label || d.deviceId}" ...`);
    const rate = await nativeInputRate(d.deviceId);
    if (rate) determined.push({ id: d.deviceId, label: d.label || d.deviceId || 'Default', nativeRate: rate });
  }
  const inputs = determined.length > 0 ? determined
    : audioIn.map((d) => ({ id: d.deviceId, label: d.label || d.deviceId || 'Default', nativeRate: null }));
  const outputs = devs.filter((d) => d.kind === 'audiooutput').map((d) => ({ id: d.deviceId, label: d.label || d.deviceId }));
  // Firefox hides audiooutput entries until the page asks through selectAudioOutput()
  // (its speaker-selection permission; Chrome lists sinks right after the mic grant, so
  // there the picker must never appear). Asked only when enumeration came back BARE -
  // nothing, or the bare synthetic default - and only where the API exists: the Scan
  // click that got us here carries the user gesture the call requires. A dismissed
  // picker is a normal answer, not a failure.
  if (navigator.mediaDevices.selectAudioOutput
      && (outputs.length === 0 || (outputs.length === 1 && outputs[0].id === 'default'))) {
    try {
      const picked = await navigator.mediaDevices.selectAudioOutput();
      if (picked && !outputs.some((o) => o.id === picked.deviceId)) {
        outputs.push({ id: picked.deviceId, label: picked.label || picked.deviceId });
      }
    } catch (e) { status('output picker dismissed - ' + e.name); }
  }
  return { inputs, outputs };
}

/**
 * Enumerates the ACTIVE backend's devices - the web's AudioBackend.listInputDevices() /
 * listOutputDevices(), which switch on `active` in exactly one place. WEB_AUDIO (and any legacy
 * OS-backend name still in a saved document) keeps the getUserMedia probe above; QA40X enumerates
 * through the device manager.
 *
 * USER GESTURE: enumeration itself prompts for nothing, but the QA40x branch also carries the
 * WebUSB GRANT step, and navigator.usb.requestDevice()'s chooser is refused without user
 * activation. That step is therefore not a mode of this function but a COLLABORATOR: `qa40xGranter`
 * is handed in only by a caller that carries the activation (the Preferences ▸ Scan click), and is
 * null everywhere else - app load, a backend rollback, and any lazy device open on the audio path.
 * No such step exists in Java at all: libusb_get_device_list hands it every attached analyzer
 * whether or not the user ever picked one, so this is a WebUSB requirement, not ported behaviour.
 *
 * @param {string} activeBackend the AudioBackendType name currently in force
 * @param {?Object} qa40xManager the Qa40xDeviceManager - required only on the QA40X branch
 * @param {(t:string)=>void} [status] progress callback
 * @param {?import('../qa40x/qa40x-device-finder.js').Qa40xDeviceFinder} [qa40xGranter] the
 *        finder whose scan() may raise the WebUSB chooser; null = this caller has no user
 *        activation to spend, so only already-granted devices can appear
 * @returns {Promise<{inputs: {id: string, label: string, nativeRate: ?number}[],
 *          outputs: {id: string, label: string}[]}>}
 */
export async function scanDevicesForBackend(activeBackend, qa40xManager, status = () => {},
  qa40xGranter = null, netManager = null) {
  if (activeBackend === QA40X_BACKEND) return scanQa40xDevices(qa40xManager, status, qa40xGranter);
  if (netManager != null && netManager.isRemoteBackend(activeBackend)) {
    return scanNetDevices(netManager, activeBackend, status);
  }
  if (remoteBackendOf(activeBackend) != null) {
    // A REMEMBERED bench backend whose server is not connected (yet). It enumerates NOTHING -
    // falling through to the probe below would list THIS machine's microphones under a device
    // combo that says it is showing a server's, and the first open would then fail with "no
    // session is open" on a device the operator picked from a plausible-looking list.
    status('no Phonalyser server is connected - its devices cannot be listed');
    return { inputs: [], outputs: [] };
  }
  return scanDevices(status);
}

/**
 * The NET branch: the bench's own catalogue, already parsed by the net device manager (spec
 * 4.3 inlines the formats with each device, so nothing is asked here - backend.select filled
 * it in one round trip, and ev.devices.changed keeps it fresh).
 *
 * `id` is the device NAME, not an opaque handle: a remote device has no browser deviceId, the
 * wire identifies it by {backend,index,input,name}, and the engine's dispatch resolves the
 * configured id back to a ref by that same name. The name is also what the card resolution
 * matches on, exactly as the QA40x branch uses its model name.
 *
 * `label` is the ref's own displayName() - the DESKTOP's device label, all four wire fields
 * (Java DeviceRef.displayName). It used to be `description || name`, which on a bench whose
 * devices share a description printed the same sentence on every row and never showed the
 * device's real name at all.
 *
 * A device somebody else holds is still listed - spec 4.3's lock overview exists so a taken
 * device can be SHOWN as taken rather than discovered as a refusal at open time. `lockedBy` is
 * WHO holds it (null when nobody does), carried on the row because only this branch can see it;
 * the combo build turns it into the label and the graying, which is where the desktop keeps that
 * wording too (PreferencesDialog.deviceLabel). Without it the web operator got no hint at all
 * until the open was refused.
 */
async function scanNetDevices(manager, value, status) {
  status('reading the bench\'s device list...');
  const backend = remoteBackendOf(value);
  // The selection is per-session (spec 4.3): point the manager at the backend being scanned
  // before its catalogue is read, or the list would describe whichever backend was selected
  // last. Refused -> no devices, and the refusal is already on the status line.
  const refusal = await manager.selectBackend(backend);
  if (refusal != null) {
    status(refusal);
    return { inputs: [], outputs: [] };
  }
  const rateOf = (ref) => {
    let best = 0;
    for (const f of manager.listSupportedFormats(ref)) if (f.sampleRate > best) best = f.sampleRate;
    return best > 0 ? best : null;
  };
  return {
    inputs: manager.listInputDevices().map((ref) => ({
      id: ref.name, label: ref.displayName(), nativeRate: rateOf(ref),
      lockedBy: manager.lockedBy(ref),
    })),
    outputs: manager.listOutputDevices().map((ref) => ({
      id: ref.name, label: ref.displayName(), lockedBy: manager.lockedBy(ref),
    })),
  };
}

/**
 * The QA40X branch: one duplex handle per attached QA402/QA403, reported identically on both
 * directions (the analyzer is a single always-duplex device). The manager's finder degrades to an
 * empty list when nothing is attached or WebUSB is missing, so this shows zero devices rather than
 * failing - the same outcome the Web Audio probe gives a machine with no microphone.
 *
 * `id` is the model name (e.g. `QA403`), which is what the manager also names the device card, and
 * `label` is the handle's display name - which CONTAINS that model name, so the card resolution
 * (a substring match on the option text) finds the card the manager wrote from the device's own
 * calibration page.
 */
async function scanQa40xDevices(manager, status, granter) {
  status('enumerating QA40x analyzers over WebUSB...');
  // The GRANT step, FIRST - before any other await, so the Scan click's user activation is still
  // live when requestDevice() is reached (status() is synchronous and costs none of it). The
  // manager enumerates through navigator.usb.getDevices(), which sees ONLY what this origin was
  // already granted: without this an analyzer the user has never picked is invisible on every scan,
  // and the later open fails with "No QA402/QA403 found on USB" with no way to ever raise the
  // prompt. finder.scan() prompts only when nothing is granted yet, so a working analyzer never
  // nags. Its return value is discarded on purpose: the grant is the point, and the handles come
  // from the manager, which owns the ref/format mapping (Java's enumeration owner).
  if (granter != null) {
    await granter.scan();
  }
  const inputs = [];
  for (const ref of await manager.listInputDevices()) {
    inputs.push({ id: ref.name, label: ref.displayName(), nativeRate: highestRateHz(manager, ref) });
  }
  // With an analyzer present, read its calibration page NOW and write the device card, so the
  // dialog's Ranges table and full-scale readouts show the DEVICE's own levels the moment it is
  // picked. Without this the card appears only after the first capture (the lazy open inside
  // acquireEngine), and until then the section falls back to whatever card the previous device
  // resolved to - showing another interface's ranges. Failure is not fatal to a scan: the analyzer
  // is listed either way and the session open will retry, so report and carry on.
  if (inputs.length > 0) {
    try {
      await manager.ensureDeviceCard();
    } catch (error) {
      status(`QA40x calibration read failed: ${error.message}`);
    }
  }
  const outputs = [];
  for (const ref of await manager.listOutputDevices()) {
    outputs.push({ id: ref.name, label: ref.displayName() });
  }
  return { inputs, outputs };
}

/** The analyzer's highest offered rate - its full bandwidth, and the same "maximal meaningful
 *  rate" the Web Audio probe reports per input. Read off the manager's format list (the input
 *  direction: the I2S port can only widen the OUTPUT depth list, never the rates). */
function highestRateHz(manager, ref) {
  let highest = 0;
  for (const format of manager.listSupportedFormats(ref, false)) {
    if (format.sampleRate > highest) highest = format.sampleRate;
  }
  return highest || null;
}
