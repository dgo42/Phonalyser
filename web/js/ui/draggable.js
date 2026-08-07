/*
 * Phonalyser web - dragging a floating window by its title bar.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * A browser cannot open a native always-on-top window without a popup, so every "window" this
 * app floats is a positioned element inside the page - and each one needs the same three
 * handlers to be movable. This is that code, extracted from ScopePane.makeMeasWindowDraggable
 * when the JSON config editor became the second user; the alternative was a third copy of the
 * same eight lines with its own small differences.
 *
 * THE ELEMENT MUST BE POSITIONED IN VIEWPORT COORDINATES - `position: fixed` (or absolute
 * against the viewport). The maths reads `offsetLeft`/`offsetTop` and writes `left`/`top`, so
 * on a `position: relative` element `left` would be applied as a DELTA from the laid-out
 * position and the window would jump by its own offset on the first drag.
 *
 * Buttons inside the handle keep working: a mousedown that started on one is ignored, so a
 * title-bar close button clicks instead of dragging the window a pixel.
 */

/** How much of the window must stay reachable when {@link makeDraggable} is asked to keep it
 *  on screen - enough of the title bar to grab it back with. */
const MIN_VISIBLE_PX = 80;

/**
 * Makes `win` draggable by `handle`.
 *
 * @param {HTMLElement} win the floating window (must be viewport-positioned - see above)
 * @param {HTMLElement} handle the grab area, normally the title bar
 * @param {Object} [options]
 * @param {boolean} [options.keepOnScreen=false] clamp so a strip of the window always stays
 *        within the viewport. OFF by default, which is the behaviour the scope pop-out has
 *        always had and which this extraction must not change.
 * @returns {{clampIntoView: function(): void}} `clampIntoView` re-applies the clamp - worth
 *          calling when a window is re-opened at a position saved from a larger viewport.
 */
export function makeDraggable(win, handle, options = {}) {
  const keepOnScreen = options.keepOnScreen === true;
  if (win == null || handle == null) return { clampIntoView: () => {} };

  const place = (x, y) => {
    if (keepOnScreen) {
      // Left may go negative - the window can hang off the left edge - but never so far that
      // less than a grab's worth of it is left; and the top never goes above 0, because a
      // title bar above the viewport cannot be reached at all.
      const w = win.offsetWidth;
      x = Math.min(Math.max(x, MIN_VISIBLE_PX - w), window.innerWidth - MIN_VISIBLE_PX);
      y = Math.min(Math.max(y, 0), window.innerHeight - MIN_VISIBLE_PX);
    }
    win.style.left = x + 'px';
    win.style.top = y + 'px';
  };

  let dragging = false, ox = 0, oy = 0;
  handle.addEventListener('mousedown', (e) => {
    if (e.target.closest('button')) return;   // let the title-bar buttons click through
    dragging = true;
    ox = e.clientX - win.offsetLeft;
    oy = e.clientY - win.offsetTop;
    e.preventDefault();
  });
  document.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    place(e.clientX - ox, e.clientY - oy);
  });
  document.addEventListener('mouseup', () => { dragging = false; });

  return {
    clampIntoView: () => { if (keepOnScreen) place(win.offsetLeft, win.offsetTop); },
  };
}
