/*
 * Phonalyser web - the JSON editor for the app's own configuration.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * IT KNOWS NOTHING ABOUT PREFERENCES OR DEVICES. It is handed a PORT
 * (store/config-port.js) and edits whatever that port describes: the port supplies the title,
 * the schema, the live document and the way to apply it back. That is the whole point of the
 * seam - there is exactly ONE connection to the running program for loading preferences and
 * devices out of it and saving them back into it - and this file is deliberately on
 * the far side of it. A third editable store later costs a port, not a line in here.
 *
 * TWO MODES, and the difference is not cosmetic:
 *   live    - the current document, editable, OK + Cancel. OK parses first and only calls
 *             port.applyLive() on success, so a typo cannot reach the running app.
 *   corrupt - the quarantined copy (<key>.corrupt), READ-ONLY, Cancel only. It exists to be
 *             read and salvaged from; nothing here may write it back, because the whole
 *             reason it was kept is that the app could not parse it.
 */
import { EditorState, EditorView, basicSetup, json, jsonSchema } from '../../vendor/codemirror/codemirror.js';
import { t } from '../i18n/i18n.js';
import { stackOverOpenModals } from '../ui/modal-stack.js';
import { makeDraggable } from '../ui/draggable.js';

/** How the document is pretty-printed on open - two spaces, the same as the app's own sources. */
const INDENT = 2;

/**
 * Pretty-prints a QUARANTINED value when it can be, and otherwise hands back exactly what was
 * stored.
 *
 * A kept copy is kept because the app could not use it, and there are two ways to earn that:
 * the text would not PARSE, or it parsed and was not a map. The second kind is perfectly good
 * JSON and reads as one long line unless it is formatted - which is the common case, since a
 * store written by this application is always minified. The first kind is broken text, and it
 * is EVIDENCE: it is the only record of what went wrong, so a formatting attempt must never
 * rewrite it, truncate it or silently swallow part of it. Hence try, and on any failure show
 * the raw text untouched.
 *
 * @param {string} raw the stored text
 * @returns {string} the same value, formatted when that is possible without changing it
 */
function prettyIfPossible(raw) {
  try {
    return JSON.stringify(JSON.parse(raw), null, INDENT);
  } catch (e) {
    return raw;
  }
}

export class JsonConfigDialog {

  /**
   * @param {Object} deps
   * @param {Object} [deps.modal] the bootstrap Modal for #jsonConfigModal; absent in tests
   */
  constructor({ modal } = {}) {
    this._modal = modal || null;
    /** The CodeMirror instance for the window currently open, destroyed when it closes. */
    this._view = null;
    /** The port being edited, and whether it is editable - set by {@link #open}. */
    this._port = null;
    this._readOnly = false;
    this.$ = window.jQuery;
  }

  /** Wires the two buttons once. The editor itself is built per open, because its content,
   *  its schema and its read-only-ness all come from the port. */
  bind() {
    this.$('#jsonConfigOk').on('click', () => this.commit());
    const el = document.getElementById('jsonConfigModal');
    if (el) {
      stackOverOpenModals(el);
      // Movable by its HEADER, never by the editor surface - CodeMirror needs its own mouse
      // handling for selection. Clamped, unlike the scope pop-out: this window is opened from
      // a menu rather than lived in, so one dragged off the edge and left there has to be
      // retrievable the next time it opens.
      // .modal-content, not .modal-dialog: the content element is the one that is
      // viewport-positioned and resizable (see css/app.css), and the drag helper needs the
      // element whose left/top are viewport coordinates.
      const win = el.querySelector('.modal-content');
      this._drag = makeDraggable(win, el.querySelector('.modal-header'), { keepOnScreen: true });
      // Destroy the editor with the window. A CodeMirror view left attached keeps its DOM,
      // its listeners and the whole document alive; the next open would then stack a second
      // editor inside the same host.
      el.addEventListener('hidden.bs.modal', () => this._destroyEditor());
    }
    return this;
  }

  /**
   * Opens the window on `port`.
   *
   * @param {Object} port a config port (store/config-port.js)
   * @param {'live'|'corrupt'} mode which half of it to show
   */
  open(port, mode) {
    this._port = port;
    this._readOnly = mode === 'corrupt';
    const text = this._readOnly
      ? prettyIfPossible(port.corruptEntry() ?? '')
      : JSON.stringify(port.loadLive(), null, INDENT);

    this.$('#jsonConfigTitle').text(this._readOnly
      ? t('jsonConfig.title.corrupt', t(port.titleKey))
      : t(port.titleKey));
    // The key is shown because it is what the operator would look for in the developer
    // tools, and because it is the one thing that says WHICH document this is.
    this.$('#jsonConfigKey').text(this._readOnly ? port.storageKey + '.corrupt' : port.storageKey);
    this._error('');
    // Read-only has nothing to apply, so it carries Cancel alone: a "Corrupt..." item opens the
    // editor in read-only mode with no OK button at all.
    this.$('#jsonConfigOk').toggleClass('d-none', this._readOnly);

    this._destroyEditor();
    this._view = this._createEditor(text, port.schema, this._readOnly);
    if (this._modal) this._modal.show();
    // CodeMirror measures itself against a laid-out box; built while the modal is still
    // display:none it comes up with a zero-height scroller. Re-measuring once the dialog is
    // on screen is what makes the editor fill the window.
    const el = document.getElementById('jsonConfigModal');
    if (el) {
      const onShown = () => {
        el.removeEventListener('shown.bs.modal', onShown);
        // Position and size persist across opens (they are inline styles the operator set by
        // dragging and resizing), so a window last left half off the edge - or moved while
        // the browser was wider - is pulled back into reach before it is shown again.
        if (this._drag) this._drag.clampIntoView();
        if (this._view) { this._view.requestMeasure(); this._view.focus(); }
      };
      el.addEventListener('shown.bs.modal', onShown);
    }
  }

  /**
   * OK: parse, and only then apply. A parse error KEEPS THE WINDOW OPEN and names the fault -
   * closing it would throw away the text the operator has just typed, which is the one thing
   * a config editor must never do.
   */
  commit() {
    if (this._readOnly || !this._port) return;
    let parsed;
    try {
      parsed = JSON.parse(this._view.state.doc.toString());
    } catch (e) {
      this._error(t('jsonConfig.error.parse', e && e.message ? e.message : String(e)));
      return;
    }
    // A JSON document that is not an object would sail through JSON.parse ("3", "null", a
    // bare array) and then quietly clear every setting, because the readers ask for keys and
    // find none. Refused here, where there is still something to refuse it to.
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      this._error(t('jsonConfig.error.notObject'));
      return;
    }
    try {
      this._port.applyLive(parsed);
    } catch (e) {
      // A refusal the store could NAME carries its own key (device-profiles.js's missing
      // audioDevices), and that sentence tells the operator what to do about it. Anything
      // else is unexpected, and the raw message is the most honest thing to show.
      this._error(e && e.i18nKey
        ? t(e.i18nKey)
        : t('jsonConfig.error.apply', e && e.message ? e.message : String(e)));
      return;
    }
    if (this._modal) this._modal.hide();
  }

  // ---------------------------------------------------------------------------
  // the editor
  // ---------------------------------------------------------------------------

  /** Builds a CodeMirror over the host element. `jsonSchema(schema)` is what supplies
   *  completion and the hover help - every description in the schema is help text the
   *  operator reads by pointing at a key. */
  _createEditor(text, schema, readOnly) {
    const host = document.getElementById('jsonConfigEditor');
    if (!host) return null;
    const extensions = [
      basicSetup,
      json(),
      jsonSchema(schema),
      EditorView.theme({ '&': { height: '100%' }, '.cm-scroller': { overflow: 'auto' } }),
    ];
    // BOTH, and they are not the same guard. EditorState.readOnly refuses the transactions a
    // user gesture would dispatch, but leaves the DOM contenteditable - so the quarantined
    // copy still took a caret and looked editable, which for the one document that must never
    // be written back is exactly the wrong impression. EditorView.editable(false) is what
    // makes the DOM itself non-editable; the state guard stays for everything that does not
    // arrive through the keyboard.
    if (readOnly) extensions.push(EditorState.readOnly.of(true), EditorView.editable.of(false));
    const view = new EditorView({ state: EditorState.create({ doc: text, extensions }), parent: host });
    // Reachable for the help-screenshot harness the way the panes are (window.__scopePane and
    // co.): the completion list is the half of this window a static shot cannot otherwise
    // show, and raising it needs the caret put somewhere sensible first.
    if (typeof window !== 'undefined') window.__jsonConfigView = view;
    return view;
  }

  _destroyEditor() {
    if (this._view) {
      this._view.destroy();
      this._view = null;
    }
    const host = document.getElementById('jsonConfigEditor');
    if (host) host.innerHTML = '';
  }

  _error(text) { this.$('#jsonConfigError').text(text || ''); }
}
