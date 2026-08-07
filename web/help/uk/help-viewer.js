/*
 * Phonalyser web help - search-term highlighter.
 *
 * Faithful port of org.edgo.audio.measure.gui.helpviewer.HelpViewer.HIGHLIGHT_SCRIPT.
 * The desktop HelpViewer (a WebView2 browser) INJECTED this into every loaded page; the
 * static web help has no such injector, so import-help.mjs copies this file into each
 * language and adds a <script src> to every page. When the URL carries
 * ?hl=<space-separated terms> (added by the search page to its result links), it wraps
 * every occurrence of those terms in <mark> and - when no #anchor steers the scroll -
 * brings the first match into view. ES5/IE11-safe (no arrow functions, 4-arg
 * createTreeWalker), guarded per location so an in-page anchor jump doesn't double-wrap.
 * GNU AGPL v3 or later.
 */
(function () {
  function run() {
    try {
      var m = /[?&]hl=([^#&]+)/.exec(location.search || location.href);
      if (!m || !document.body) { return; }
      var key = location.pathname + location.search;
      if (window.__phHlFor === key) { return; }
      window.__phHlFor = key;
      var raw = decodeURIComponent(m[1]).toLowerCase().split(/\s+/);
      var terms = [];
      for (var i = 0; i < raw.length; i++) { if (raw[i].length > 1) terms.push(raw[i]); }
      if (!terms.length) { return; }
      function esc(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
      var pat = terms.map(esc).join('|');
      var reTest = new RegExp(pat, 'i');
      var reRepl = new RegExp('(' + pat + ')', 'ig');
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
      var nodes = [], n;
      while ((n = walker.nextNode())) {
        var p = n.parentNode; if (!p) { continue; }
        var tag = p.nodeName.toLowerCase();
        if (tag === 'script' || tag === 'style' || tag === 'mark') { continue; }
        if (reTest.test(n.nodeValue)) { nodes.push(n); }
      }
      var first = null;
      for (var k = 0; k < nodes.length; k++) {
        var node = nodes[k];
        var span = document.createElement('span');
        span.innerHTML = node.nodeValue.replace(reRepl, '<mark>$1</mark>');
        node.parentNode.replaceChild(span, node);
        if (!first) { first = span.getElementsByTagName('mark')[0]; }
      }
      if (first && !location.hash && first.scrollIntoView) {
        first.scrollIntoView();
        if (window.scrollBy) { window.scrollBy(0, -60); }
      }
    } catch (e) { /* never break the page */ }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
