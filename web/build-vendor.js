/*
 * Phonalyser web — BUILD-ONLY vendor entry. esbuild bundles just the npm
 * libraries the app actually uses (jQuery + Bootstrap, with Popper, tree-shaken
 * and minified) into one dist/vendor.js, exposing the same globals the source
 * relies on (window.jQuery / window.$ / window.bootstrap). It is NOT part of the
 * unbundled dev tree — dev still loads the vendored <script>s directly.
 * GNU AGPL v3 or later.
 */
import jQuery from 'jquery';
import * as bootstrap from 'bootstrap';   // pulls @popperjs/core; registers the data-bs-* data-api

window.jQuery = window.$ = jQuery;
window.bootstrap = bootstrap;
