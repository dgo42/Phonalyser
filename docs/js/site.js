/* Phonalyser landing site - shared menu behaviour (no framework, no build step).
 *
 *  1. The <nav class="menu"> block is byte-identical on every page; this marks the
 *     current page's link `active` and opens its Download / Documentation submenu.
 *  2. Below 760px the sidebar becomes an off-canvas drawer opened by the fixed
 *     three-dots (kebab) button - toggle, backdrop, Esc and close-on-navigate. */
(function () {
  var body = document.body;
  var toggle = document.querySelector('.menu-toggle');
  var backdrop = document.querySelector('.menu-backdrop');

  function setOpen(open) {
    body.classList.toggle('nav-open', open);
    if (toggle) toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  if (toggle) toggle.addEventListener('click', function () {
    setOpen(!body.classList.contains('nav-open'));
  });
  if (backdrop) backdrop.addEventListener('click', function () { setOpen(false); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') setOpen(false); });

  // Active link + auto-open its submenu, keyed off the current file name.
  var here = location.pathname.split('/').pop() || 'index.html';

  // Theory-of-operation chapters aren't menu entries themselves - light up their
  // parent "Theory of operation" link (and open Documentation) while on one.
  var THEORY = ['audio-backend.html', 'ring-buffer.html', 'generator.html',
    'oscilloscope.html', 'fft.html', 'derotation-accuracy.html',
    'dac-predistortion.html', 'freq-resp.html', 'tune-notch.html', 'algorithms.html'];
  if (THEORY.indexOf(here) >= 0) here = 'theory.html';

  document.querySelectorAll('.menu a[href]').forEach(function (a) {
    if (a.getAttribute('href') === here) {
      a.classList.add('active');
      var det = a.closest('details.submenu');
      if (det) det.open = true;
    }
    // On the mobile drawer, tapping a real nav link closes it.
    a.addEventListener('click', function () { if (window.innerWidth <= 760) setOpen(false); });
  });
})();

/* Image / diagram lightbox (self-contained, no external library).
 *  Click any content screenshot or theory SVG diagram to enlarge it over a
 *  dimmed page; step through the page's figures with the ‹ › buttons or the
 *  <- -> arrow keys; close with ×, Esc or a backdrop click. */
(function () {
  var items = Array.prototype.slice.call(
    document.querySelectorAll('.content img, .content .diagram svg'));
  if (!items.length) return;

  var box = document.createElement('div');
  box.className = 'lightbox';
  box.hidden = true;
  box.innerHTML =
    '<div class="lb-count"></div>' +
    '<button class="lb-close" aria-label="Close">×</button>' +
    '<button class="lb-prev" aria-label="Previous image">‹</button>' +
    '<figure class="lb-stage"></figure>' +
    '<button class="lb-next" aria-label="Next image">›</button>' +
    '<div class="lb-caption"></div>';
  document.body.appendChild(box);

  var stage = box.querySelector('.lb-stage');
  var caption = box.querySelector('.lb-caption');
  var count = box.querySelector('.lb-count');
  var prevBtn = box.querySelector('.lb-prev');
  var nextBtn = box.querySelector('.lb-next');
  var many = items.length > 1;
  prevBtn.style.display = nextBtn.style.display = many ? '' : 'none';
  var cur = 0;

  function captionFor(el) {
    if (el.tagName.toLowerCase() === 'img' && el.alt) return el.alt;
    var fig = el.closest('figure');
    var cap = fig && fig.querySelector('figcaption');
    return cap ? cap.textContent.replace(/\s+/g, ' ').trim() : '';
  }

  function show(i) {
    cur = (i + items.length) % items.length;
    var src = items[cur];
    stage.innerHTML = '';
    var node;
    if (src.tagName.toLowerCase() === 'img') {
      node = new Image();
      node.src = src.currentSrc || src.src;
      node.alt = src.alt || '';
    } else {                                   // clone the inline SVG diagram
      node = src.cloneNode(true);
      node.removeAttribute('id');
      var vb = (src.getAttribute('viewBox') || '').split(/[\s,]+/);
      if (vb.length === 4 && +vb[3]) {
        node.style.width = 'min(92vw, ' + (88 * (+vb[2] / +vb[3])) + 'vh)';
        node.style.height = 'auto';
      }
    }
    stage.appendChild(node);
    caption.textContent = captionFor(src);
    count.textContent = many ? (cur + 1) + ' / ' + items.length : '';
  }

  function open(i) { show(i); box.hidden = false; document.body.style.overflow = 'hidden'; }
  function close() { box.hidden = true; stage.innerHTML = ''; document.body.style.overflow = ''; }

  items.forEach(function (el, i) { el.addEventListener('click', function () { open(i); }); });
  prevBtn.addEventListener('click', function () { show(cur - 1); });
  nextBtn.addEventListener('click', function () { show(cur + 1); });
  box.querySelector('.lb-close').addEventListener('click', close);
  box.addEventListener('click', function (e) { if (e.target === box) close(); });
  document.addEventListener('keydown', function (e) {
    if (box.hidden) return;
    if (e.key === 'Escape') close();
    else if (e.key === 'ArrowLeft' && many) show(cur - 1);
    else if (e.key === 'ArrowRight' && many) show(cur + 1);
  });
})();
