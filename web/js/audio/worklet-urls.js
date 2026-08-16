/*
 * Phonalyser web - worklet module locations.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * AudioWorklet modules are NOT bundled into app.js - addModule() fetches them at
 * runtime (build.mjs emits them as self-contained scripts under worklets/). Such
 * a URL must resolve in BOTH layouts: in the unbundled dev tree relative to THIS
 * file (js/audio/worklets/ beside it), and in the production bundle relative to
 * app.js (worklets/ beside it). `new URL('./worklets/<name>.js', import.meta.url)`
 * evaluated in a js/audio/ module is the only form that satisfies both - the same
 * expression built anywhere else in the tree resolves outside the deployed app
 * the moment it is bundled, and the module 404s.
 */

/** @returns {URL} the DDS generator processor module (generator playback). */
export const ddsProcessorUrl = () => new URL('./worklets/dds-processor.js', import.meta.url);
