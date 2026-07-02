/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Browser file open/save glue. No Java counterpart — the desktop app used a
// java.io.File chosen via an SWT FileDialog; in the browser the equivalent is
// the File System Access API (window.showSaveFilePicker / showOpenFilePicker),
// with a graceful fallback to <a download> for saving and a hidden <input
// type=file> for opening when that API is unavailable (Firefox, Safari, non-
// secure contexts).
//
// These helpers move bytes only; the format encoders/decoders live in
// wav.js / frc.js / fft-spectrum.js / scope-capture.js.

/** True when the File System Access API is usable (Chromium, secure context). */
const HAS_FS_ACCESS =
  typeof window !== 'undefined'
  && typeof window.showSaveFilePicker === 'function'
  && typeof window.showOpenFilePicker === 'function';

/**
 * A file-type filter entry for the pickers.
 * @typedef {Object} FileTypeSpec
 * @property {string} description           Human label, e.g. "WAV audio".
 * @property {string} accept                MIME type, e.g. "audio/wav".
 * @property {string[]} extensions          e.g. [".wav"]. First is the default suffix.
 */

/** Normalises bytes to a Blob. */
function toBlob(data, mime) {
  if (data instanceof Blob) return data;
  const u8 = data instanceof Uint8Array ? data
    : typeof data === 'string' ? new TextEncoder().encode(data)
    : new Uint8Array(data);
  return new Blob([u8], { type: mime || 'application/octet-stream' });
}

/**
 * Prompts the user to save {@code data} under {@code suggestedName}. Uses the
 * File System Access API when present (a real Save dialog, returns the chosen
 * name), otherwise triggers an `<a download>` and returns the suggested name.
 *
 * @param {Uint8Array|ArrayBuffer|string|Blob} data
 * @param {string} suggestedName        Default file name (with extension).
 * @param {FileTypeSpec[]} [types=[]]    Filters for the native dialog.
 * @returns {Promise<{name:string, saved:boolean}>}
 *          {@code saved} is false only when the user cancels the native dialog.
 */
export async function saveFile(data, suggestedName, types = []) {
  const mime = types[0] ? types[0].accept : 'application/octet-stream';
  const blob = toBlob(data, mime);

  if (HAS_FS_ACCESS) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName,
        types: types.map((t) => ({
          description: t.description,
          accept: { [t.accept]: t.extensions },
        })),
      });
      const w = await handle.createWritable();
      await w.write(blob);
      await w.close();
      return { name: handle.name, saved: true };
    } catch (err) {
      if (err && err.name === 'AbortError') return { name: suggestedName, saved: false };
      throw err;
    }
  }

  // Fallback: anchor download (no real dialog, no cancel signal).
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = suggestedName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Revoke after the click has been dispatched.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return { name: suggestedName, saved: true };
}

/**
 * Prompts the user to choose a SAVE target up-front (before the bytes exist), so
 * the caller can derive the file format from the chosen extension and only then
 * encode. Uses the File System Access API when present (returns the writable
 * handle plus its real name); otherwise returns just the suggested name and the
 * caller falls back to {@link writeToTarget}'s anchor-download path.
 *
 * @param {string} suggestedName        Default file name (with extension).
 * @param {FileTypeSpec[]} [types=[]]    Filters for the native dialog.
 * @returns {Promise<?{name:string, handle:?FileSystemFileHandle}>}
 *          null when the user cancels the native dialog.
 */
export async function pickSaveTarget(suggestedName, types = []) {
  if (HAS_FS_ACCESS) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName,
        types: types.map((t) => ({
          description: t.description,
          accept: { [t.accept]: t.extensions },
        })),
      });
      return { name: handle.name, handle };
    } catch (err) {
      if (err && err.name === 'AbortError') return null;
      throw err;
    }
  }
  // No FS Access API → no real pre-pick; the caller derives the format from the
  // suggested name and writeToTarget triggers an anchor download with that name.
  return { name: suggestedName, handle: null };
}

/**
 * Writes {@code data} to a target previously chosen by {@link pickSaveTarget}:
 * the FS Access handle when present, otherwise an anchor download named
 * {@code name}.
 *
 * @param {{name:string, handle:?FileSystemFileHandle}} target
 * @param {Uint8Array|ArrayBuffer|string|Blob} data
 * @param {string} [mime='application/octet-stream']
 * @returns {Promise<{name:string, saved:boolean}>}
 */
export async function writeToTarget(target, data, mime = 'application/octet-stream') {
  const blob = toBlob(data, mime);
  if (target.handle) {
    const w = await target.handle.createWritable();
    await w.write(blob);
    await w.close();
    return { name: target.name, saved: true };
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = target.name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return { name: target.name, saved: true };
}

/**
 * Prompts the user to open a file and reads its bytes. Uses the File System
 * Access API when present, otherwise a hidden `<input type=file>`.
 *
 * @param {FileTypeSpec[]} [types=[]]   Filters / accept list.
 * @returns {Promise<?{name:string, bytes:Uint8Array}>} null when the user cancels.
 */
export async function openFile(types = []) {
  if (HAS_FS_ACCESS) {
    try {
      const [handle] = await window.showOpenFilePicker({
        multiple: false,
        types: types.map((t) => ({
          description: t.description,
          accept: { [t.accept]: t.extensions },
        })),
      });
      const file = await handle.getFile();
      const bytes = new Uint8Array(await file.arrayBuffer());
      return { name: file.name, bytes };
    } catch (err) {
      if (err && err.name === 'AbortError') return null;
      throw err;
    }
  }

  // Fallback: hidden file input.
  return new Promise((resolve, reject) => {
    const input = document.createElement('input');
    input.type = 'file';
    const accept = types.flatMap((t) => t.extensions).join(',');
    if (accept) input.accept = accept;
    input.style.display = 'none';
    // 'cancel' fires in modern browsers when the dialog is dismissed.
    input.addEventListener('cancel', () => { input.remove(); resolve(null); });
    input.addEventListener('change', async () => {
      const file = input.files && input.files[0];
      input.remove();
      if (!file) { resolve(null); return; }
      try {
        resolve({ name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) });
      } catch (err) {
        reject(err);
      }
    });
    document.body.appendChild(input);
    input.click();
  });
}

/** Reads an opened file's bytes as UTF-8 text (for .frc / .fft). */
export function bytesToText(bytes) {
  return new TextDecoder('utf-8').decode(bytes);
}
