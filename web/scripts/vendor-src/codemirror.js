/*
 * Phonalyser web - the CodeMirror surface the JSON config editor uses, and nothing more.
 *
 * WHY A BUNDLE AND NOT A `dist` COPY. Every other vendored library ships a browser-ready
 * file that scripts/vendor.mjs can copy verbatim. CodeMirror 6 does not: it is a dozen ESM
 * packages that import each other by BARE SPECIFIER ('@codemirror/state', '@lezer/common'),
 * which a browser cannot resolve without an import map - and codemirror-json-schema goes
 * further, importing its own internals WITHOUT file extensions, which not even Node's ESM
 * resolver accepts. A bundler resolves both. esbuild is already a devDependency, so
 * vendor.mjs bundles THIS file into vendor/codemirror/codemirror.js as one self-contained
 * ES module, offline and version-pinned exactly like the copies beside it.
 *
 * Keep this surface narrow: what is re-exported here is what the app may use.
 */
export { EditorState, Compartment } from '@codemirror/state';
export { EditorView, keymap } from '@codemirror/view';
export { basicSetup } from 'codemirror';
export { json } from '@codemirror/lang-json';
export { jsonSchema } from 'codemirror-json-schema';
