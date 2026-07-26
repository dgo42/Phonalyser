/*
 * Phonalyser web — the QA402/QA403 backend's own settings dialog.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xSettingsDialog — the modal behind the
 * per-backend button on the Preferences dialog's Audio tab (Qa40xDeviceManager.openCustomPreferences).
 * These settings exist on no other backend, which is why they live with the backend rather than on
 * the shared Preferences pages. Same shape as the desktop, in the same order: the wrapped
 * provenance note, the EIGHT read-only device rows (deviceInfoRows), the I2S toggle, then the
 * OK / Cancel bar at the trailing edge with OK as the default button.
 *
 * Edits reach the caller only on OK; Cancel returns the SEED unchanged. Java seeds
 * `accepted = i2sEnabled` inside build() precisely so a Cancel — which never fires the OK listener
 * — is a no-op, and this port seeds the same field for the same reason rather than leaning on "no
 * callback fired": Escape, the backdrop and the title-bar close all take that path too.
 *
 * WEB DEVIATIONS, forced by the platform rather than chosen:
 *   • SWT's blocking modal loop (`while (!dialog.isDisposed()) readAndDispatch()`) has no browser
 *     counterpart, so open() returns a PROMISE resolved from Bootstrap's hidden.bs.modal — the
 *     same lifecycle CardEditorDialog.open uses.
 *   • The markup is built here rather than living in index.html, because a fresh element per
 *     open() is what Java does (a new Shell each time) and it keeps the dialog in the CURRENT
 *     locale without a re-translate pass.
 *   • Java's getContent() exists only so the in-process help capture can snapshot a Composite (a
 *     top-level Shell prints blank on Windows). The web help capture drives Playwright with a
 *     selector, so #qa40xSettingsModal replaces it and no getter is needed.
 */
import { t } from '../i18n/i18n.js';

// The note carries NO width of its own. In SWT, NOTE_WIDTH_HINT both wrapped the note AND set the
// dialog's width; here .modal-dialog fixes the width (app.css), so the note simply fills the body —
// capping it separately would end its text short of the fields' right edge and reintroduce the
// lopsided look the fixed value column already caused.

/**
 * The device panel's rows, label key → value, in Java build()'s exact order: firmwareVersion,
 * serialNumber, usbVoltage, usbCurrent, isoCurrent, temperature, capability, capability2. Pure and
 * exported because that order (and the count) is the contract the desktop dialog fixes — a reader
 * quoting a serial off a screenshot must find it in the same place in both apps.
 *
 * @param {import('../qa40x/qa40x-device-info.js').Qa40xDeviceInfo} info the telemetry snapshot.
 * @returns {{labelKey: string, value: string}[]} the rows, top to bottom.
 */
export function deviceInfoRows(info) {
  return [
    { labelKey: 'qa40x.settings.firmwareVersion', value: info.firmwareVersion },
    { labelKey: 'qa40x.settings.serialNumber', value: info.serialNumber },
    { labelKey: 'qa40x.settings.usbVoltage', value: info.usbVoltage },
    { labelKey: 'qa40x.settings.usbCurrent', value: info.usbCurrent },
    { labelKey: 'qa40x.settings.isoCurrent', value: info.isoCurrent },
    { labelKey: 'qa40x.settings.temperature', value: info.temperature },
    { labelKey: 'qa40x.settings.capability', value: info.capability },
    { labelKey: 'qa40x.settings.capability2', value: info.capability2 },
  ];
}

/** One element with its class and optional text — the DOM half of `new Widget(parent, style)`. */
function el(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export class Qa40xSettingsDialog {
  /**
   * @param {?HTMLElement} parent the element the modal is mounted under (Java's parent Shell);
   *   document.body when null — Bootstrap owns modality and centring, so the parent is only a mount
   *   point and must NOT be another modal's element (that would nest two dialogs).
   * @param {import('../qa40x/qa40x-device-info.js').Qa40xDeviceInfo} info the identity + telemetry
   *   snapshot to show read-only (Qa40xDeviceManager.readDeviceInfo).
   */
  constructor(parent, info) {
    this._parent = parent;
    this._info = info;
    /** @type {?HTMLElement} the built modal root — non-null between _build and its removal. */
    this._dialog = null;
    /** @type {?object} the live Bootstrap Modal instance, disposed with the element. */
    this._modal = null;
    /** @type {boolean} the toggle's latch state, mirrored onto its `active` class. */
    this._i2sEnabled = false;
    /** @type {boolean} the value the OK handler hands back out of the modal. */
    this._accepted = false;
  }

  /**
   * Shows the dialog modally, seeded with {@code i2sEnabled}, and resolves with the value the user
   * accepted — or the seed unchanged when they cancelled (Java open).
   * @param {boolean} i2sEnabled the current front-panel I2S setting.
   * @returns {Promise<boolean>}
   */
  open(i2sEnabled) {
    return new Promise((resolve) => {
      this._build(i2sEnabled);
      this._modal = new window.bootstrap.Modal(this._dialog);
      // The hide has finished by the time this fires, so the element goes with it and a second
      // open() builds a fresh one — Java disposes its Shell and its modal loop returns here.
      this._dialog.addEventListener('hidden.bs.modal', () => {
        this._modal.dispose();
        this._dialog.remove();
        this._dialog = null;
        this._modal = null;
        resolve(this._accepted);
      });
      this._modal.show();
    });
  }

  /** Builds the modal element and its widgets, seeded with {@code i2sEnabled} (Java build). */
  _build(i2sEnabled) {
    // Seeded BEFORE any widget exists: Cancel, Escape, the backdrop and the title-bar close all
    // hide without touching _accepted, so the dialog hands back what it opened with.
    this._accepted = i2sEnabled;
    this._i2sEnabled = i2sEnabled;

    const root = el('div', 'modal fade');
    root.id = 'qa40xSettingsModal';
    root.setAttribute('tabindex', '-1');
    const dialog = el('div', 'modal-dialog modal-dialog-centered');
    const content = el('div', 'modal-content prefs');
    root.appendChild(dialog);
    dialog.appendChild(content);

    const dismiss = () => this._modal.hide();

    const header = el('div', 'modal-header py-2');
    header.appendChild(el('h6', 'modal-title', t('qa40x.settings.title')));
    const close = el('button', 'btn-close');
    close.type = 'button';
    close.addEventListener('click', dismiss);
    header.appendChild(close);
    content.appendChild(header);

    const body = el('div', 'modal-body');
    content.appendChild(body);

    // Provenance up front: this port is reverse-engineered, not documented by the vendor, so the
    // reader knows what they are switching on before they switch it on.
    const note = el('div', 'small mb-3', t('qa40x.settings.note'));
    body.appendChild(note);

    // Identity + live telemetry, read once as the dialog opens (doc §4/§6). Read-only: these are
    // the device's own values, not settings.
    // ONE grid for all eight rows, mirroring the desktop's GridLayout(2, false): a single
    // max-content column sizes itself to the WIDEST caption and every row shares it, so the labels
    // line up and none of them wraps. Per-row flex boxes (what this used to be) size each caption
    // independently — "Firmware version:" wrapped onto two lines while the value fields stretched
    // the dialog far wider than the desktop's.
    const grid = el('div', 'qa40x-settings-grid mb-2');
    for (const row of deviceInfoRows(this._info)) {
      this._addReadOnlyRow(grid, row.labelKey, row.value);
    }
    body.appendChild(grid);

    // SWT.TOGGLE → a latching button whose `active` class shows the state, as the scope pane's
    // toggles do (ScopeTabControl); the latch itself lives in the field, not in the class.
    const i2sToggle = el('button', 'btn btn-sm btn-outline-secondary mt-2', t('qa40x.settings.i2s'));
    i2sToggle.type = 'button';
    i2sToggle.id = 'qa40xSettingsI2s';
    i2sToggle.title = t('qa40x.settings.i2s.tooltip');
    i2sToggle.classList.toggle('active', i2sEnabled);
    i2sToggle.addEventListener('click', () => {
      this._i2sEnabled = !this._i2sEnabled;
      i2sToggle.classList.toggle('active', this._i2sEnabled);
    });
    body.appendChild(i2sToggle);

    const accept = () => {
      this._accepted = this._i2sEnabled;
      this._modal.hide();
    };

    // Bootstrap footers in this app read [Cancel][OK] (cardEditorModal); the desktop RowLayout
    // puts OK first. Same buttons at the same trailing edge, in the web app's own order.
    const footer = el('div', 'modal-footer py-2');
    const cancelButton = el('button', 'btn btn-sm btn-outline-secondary', t('common.cancel'));
    cancelButton.type = 'button';
    cancelButton.addEventListener('click', dismiss);
    const okButton = el('button', 'btn btn-sm btn-primary', t('common.ok'));
    okButton.type = 'button';
    okButton.id = 'qa40xSettingsOk';
    okButton.addEventListener('click', accept);
    footer.appendChild(cancelButton);
    footer.appendChild(okButton);
    content.appendChild(footer);

    // Java setDefaultButton(okButton): Enter accepts from anywhere in the dialog, including from a
    // value field the user was selecting text in.
    root.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      accept();
    });

    this._dialog = root;
    (this._parent != null ? this._parent : document.body).appendChild(root);
  }

  /** One {@code label → read-only value} row of the device panel. A read-only text INPUT rather
   *  than a plain label so the value can be selected and copied — handy when quoting a serial or a
   *  capability word (Java addReadOnlyRow). */
  _addReadOnlyRow(grid, labelKey, value) {
    const id = `qa40x-${labelKey.split('.').pop()}`;
    // The caption and the field are appended as SIBLINGS of the grid, not wrapped in a row: that is
    // what puts every caption in the same column track and every field in the same one.
    const caption = el('label', 'small mb-0', t(labelKey));
    caption.setAttribute('for', id);
    const field = el('input', 'form-control form-control-sm');
    field.type = 'text';
    field.id = id;
    field.readOnly = true;
    field.value = value;
    grid.appendChild(caption);
    grid.appendChild(field);
  }
}
