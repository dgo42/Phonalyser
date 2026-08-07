/*
 * Phonalyser web - which PACKAGING of the app this is.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * ONE source tree, two packagings - never a forked copy:
 *
 *   - the FULL app (docs/web, and the unbundled web/ served in dev): every backend this browser
 *     can reach, local ones included;
 *   - the EMBEDDED app, which a Phonalyser server serves at `/`: the net backend and nothing
 *     else. Hiding the local backends there is HONEST, not cosmetic - that page arrives over
 *     plain http:// from a LAN host, which is not a secure context, so getUserMedia and WebUSB
 *     do not exist on it at all. Offering Web Audio or a QA40x would offer a choice whose every
 *     open must fail.
 *
 * The flag is a BUILD-TIME define (esbuild `define:`, build.mjs --embedded), so the full build
 * carries `false` as a literal and every embedded-only branch is dead code the minifier drops.
 * The `typeof` guard is what lets the same source run unbundled - in web/ served straight from
 * disk, and in the node test suite, where the identifier is simply not there and the answer is
 * the full profile.
 */

/* global __PHONALYSER_EMBEDDED__ */
const declared = typeof __PHONALYSER_EMBEDDED__ === 'undefined' ? false : __PHONALYSER_EMBEDDED__;

/** True only in the bundle a server serves: the net backend is the only one that exists. */
export const EMBEDDED = declared === true;
