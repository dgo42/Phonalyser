/*
 * Phonalyser web - a Bootstrap modal opened on top of another one.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * WHY THIS EXISTS. Bootstrap gives every modal the same z-index and every backdrop the same
 * one below it - `$zindex-modal: 1055`, `$zindex-modal-backdrop: 1050` (scss/_variables.scss).
 * A dialog opened while another modal is up therefore gets a backdrop that CANNOT cover the
 * dialog underneath: 1050 is below 1055 whatever the DOM order. The second dialog paints on top
 * (equal z-index, later in the document), but the first one stays lit and fully clickable - the
 * operator sees a dialog that is plainly not modal, and a click on the window behind it takes
 * the focus with it (observed with the Servers... list opened from Preferences).
 *
 * It is not a bug in one dialog. Every dialog this app opens from inside Preferences - the card
 * editor, the confirm and alert modals, the QA40x settings - is stacked the same way and would
 * read the same if the operator looked; the server list is where it was noticed because it is a
 * table one STUDIES, and because its `data-bs-backdrop="static"` means a click outside does not
 * even dismiss it. The construction path is NOT the difference (a modal built with
 * `new bootstrap.Modal(el)` and one built with `getOrCreateInstance(el)` are the same single
 * instance for that element, and every shared modal in this app is built the first way).
 *
 * THE FOCUS HALF is Bootstrap's too, and is deliberately NOT worked around here: FocusTrap's
 * `deactivate()` runs `EventHandler.off(document, '.bs.focustrap')`, which removes whatever trap
 * is installed - so when the upper dialog closes, the one underneath is left with no focus trap
 * at all and its own `_isActive` still true, meaning it can never re-arm. Re-arming it would
 * mean reaching into a private field of another library. What this does instead is put the
 * focus back where it belongs when the upper dialog goes away, which is the visible half of it.
 */

/** What a dialog can put the keyboard focus on when it opens - the controls SWT would give a tab
 *  stop to. A hidden input is not one of them, and neither is the title-bar close button: it is
 *  window chrome (Escape does the same thing), and being first in the markup it would otherwise
 *  take the focus in every dialog that marks nothing. */
const FOCUSABLE = 'input:not([type=hidden]), select, textarea, button:not(.btn-close)';

/** Bootstrap's own two layers (scss/_variables.scss). */
export const BACKDROP_Z = 1050;
export const MODAL_Z = 1055;

/** One stacking level. Ten lifts a second dialog clear of the first AND its backdrop while
 *  staying under `$zindex-popover` (1070) and `$zindex-tooltip` (1080), so a tooltip raised
 *  inside the stacked dialog still shows above it. */
export const STACK_STEP = 10;

/**
 * The z-index pair a dialog takes when `below` modals are ALREADY open.
 *
 * @param {number} below how many modals are open underneath (0 -> Bootstrap's own numbers, i.e.
 *        nothing is changed for the ordinary single-dialog case)
 * @returns {{modal: number, backdrop: number}}
 */
export function stackedZ(below) {
  const level = Math.max(0, below | 0);
  return { modal: MODAL_Z + level * STACK_STEP, backdrop: BACKDROP_Z + level * STACK_STEP };
}

/**
 * The control a dialog opens with the keyboard focus on: the one marked `data-initial-focus`
 * (the desktop's default button), else the first usable control in DOM order. Disabled and
 * hidden controls are skipped in both cases - a focus() on either does nothing and would leave
 * the caret outside the dialog.
 *
 * @param {HTMLElement} element the modal element
 * @returns {?HTMLElement} the control to focus, or null when the dialog has none
 */
export function initialFocusTarget(element) {
  const usable = [...element.querySelectorAll(FOCUSABLE)]
    .filter((c) => !c.disabled && c.offsetParent !== null);
  return usable.find((c) => c.hasAttribute('data-initial-focus')) || usable[0] || null;
}

/**
 * Makes `element` stack properly whenever it is opened over another modal: its backdrop covers
 * everything below it (so the dialog underneath really is out of reach), and the focus goes
 * back to that dialog when this one closes.
 *
 * It also puts the focus INTO the dialog as it opens - Bootstrap focuses the modal ROOT and
 * stops there, so a dialog raised over Preferences opened with the keyboard on nothing the
 * operator can type into, while the desktop opens on the default button.
 * That half applies stacked or not: it is the same dialog either way.
 *
 * A no-op for the ordinary case - opened with nothing else up, the element keeps Bootstrap's
 * own z-index and behaves exactly as it always did.
 *
 * @param {HTMLElement} element the modal element (already in the document)
 * @param {Document} [doc] injected for the test; the page's own document otherwise
 */
export function stackOverOpenModals(element, doc = document) {
  if (element == null) return;
  element.addEventListener('show.bs.modal', () => {
    const below = doc.querySelectorAll('.modal.show').length;
    if (below === 0) {
      element.style.zIndex = '';       // nothing underneath: Bootstrap's own layer, untouched
      return;
    }
    const z = stackedZ(below);
    element.style.zIndex = String(z.modal);
    // Bootstrap creates the backdrop and appends it to <body> INSIDE the same show() call, just
    // after this event returns - so it can only be reached once that call is over. A microtask
    // is the first moment it exists, and runs before the browser paints; the last backdrop in
    // the document is the one that was just added for this dialog.
    queueMicrotask(() => {
      const backdrops = doc.querySelectorAll('.modal-backdrop');
      const own = backdrops[backdrops.length - 1];
      if (own) own.style.zIndex = String(z.backdrop);
    });
  });
  // 'shown', not 'show': Bootstrap focuses the modal root itself at the end of its own show(),
  // just before this event, so anything set earlier is overwritten. Its focus trap allows this -
  // the target is INSIDE the dialog - and re-arms per modal, so Escape and Tab are untouched.
  element.addEventListener('shown.bs.modal', () => {
    const target = initialFocusTarget(element);
    if (target) target.focus();
  });
  element.addEventListener('hidden.bs.modal', () => {
    element.style.zIndex = '';
    // The dialog underneath has just lost its focus trap to Bootstrap's own teardown (see the
    // header): hand it the focus back explicitly, or it stays wherever the last click left it.
    const under = doc.querySelectorAll('.modal.show');
    const top = under[under.length - 1];
    if (top && typeof top.focus === 'function') top.focus();
  });
}
