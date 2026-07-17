/*
 * Phonalyser web — "Tip of the day" popup (browser port of gui/tips/TipOfTheDayDialog).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * Faithful port of org.edgo.audio.measure.gui.tips.TipOfTheDayDialog: a small,
 * title-less, NON-MODAL popup docked over the viewport's bottom-left corner (12px
 * margin). It floats on top of the app but never blocks it — the user keeps
 * working while it shows. Shown at startup (auto-closing after a 20 s countdown)
 * only when Preferences.showTipsAtStartup is set, and unconditionally from the
 * Help menu. "Next tip" cycles to the next tip and permanently cancels the
 * countdown (Close then reads plain "Close"); the "Don't show again" checkbox
 * reflects + clears the showTipsAtStartup preference (checked = don't show = pref
 * false); a click in any free space or Escape closes it. Each open() starts on a
 * random tip, so re-opening from the Help menu shows a fresh one. The tips come
 * from the i18n catalogue: tip.count (integer) and tip.N.title / tip.N.body.
 * GNU AGPL v3 or later.
 */

/** Auto-close countdown for the startup popup, in seconds (TipOfTheDayDialog.AUTO_CLOSE_SECS). */
const AUTO_CLOSE_SECS = 20;

export class TipDialog {
  /**
   * @param {object} opts
   * @param {import('../store/preferences.js').Preferences} opts.prefs shared preferences (showTipsAtStartup).
   * @param {(key: string, ...args: *[]) => string} opts.t translator (i18n t()).
   */
  constructor(opts) {
    this.prefs = opts.prefs;
    this.t = opts.t;
    /** @type {?HTMLElement} live popup root, null while closed. */
    this.root = null;
    /** @type {?HTMLElement} */ this.titleEl = null;
    /** @type {?HTMLElement} */ this.bodyEl = null;
    /** @type {?HTMLButtonElement} */ this.closeBtn = null;
    /** @type {?number} setInterval handle for the countdown, null when idle. */
    this.timer = null;
    /** @type {?(e: KeyboardEvent) => void} document Escape listener, removed on close. */
    this.onKeyDown = null;
    this.count = 1;
    this.index = 0;
    this.secondsLeft = 0;
    this.countdownActive = false;
  }

  /** Builds + shows the popup at a RANDOM tip, starting the auto-close countdown.
   *  Re-opening while one is up replaces it (Java opens a fresh Shell each time). */
  open() {
    this.close();   // drop any live popup so a second open() never stacks two
    const t = this.t;
    this.count = this._readCount();
    this.index = Math.floor(Math.random() * this.count);

    const root = document.createElement('div');
    root.className = 'tip-popup';

    const heading = document.createElement('div');
    heading.className = 'tip-heading';
    heading.textContent = t('tip.dialog.heading');

    this.titleEl = document.createElement('div');
    this.titleEl.className = 'tip-title';

    this.bodyEl = document.createElement('div');
    this.bodyEl.className = 'tip-body';

    // Footer: [Don't show again]  [Next tip]  [Close (Ns)]
    const footer = document.createElement('div');
    footer.className = 'tip-footer';

    // "Don't show again" reflects + sets the persisted choice (checked = don't show = pref false).
    const dontShowLabel = document.createElement('label');
    dontShowLabel.className = 'tip-dontshow';
    const dontShow = document.createElement('input');
    dontShow.type = 'checkbox';
    dontShow.checked = !this.prefs.showTipsAtStartup.get();
    dontShow.addEventListener('change', () => this.prefs.showTipsAtStartup.set(!dontShow.checked));
    const dontShowText = document.createElement('span');
    dontShowText.textContent = t('tip.dialog.dontShowAgain');
    dontShowLabel.appendChild(dontShow);
    dontShowLabel.appendChild(dontShowText);

    // "Next tip" advances and permanently cancels the countdown.
    const next = document.createElement('button');
    next.type = 'button';
    next.className = 'btn btn-sm btn-outline-secondary tip-next';
    next.textContent = t('tip.dialog.next');
    next.addEventListener('click', () => {
      this._stopCountdown();
      this.index = (this.index + 1) % this.count;
      this._showTip();
    });

    // The countdown rides the Close button's label; clicking it closes early.
    this.closeBtn = document.createElement('button');
    this.closeBtn.type = 'button';
    this.closeBtn.className = 'btn btn-sm btn-secondary tip-close';
    this.closeBtn.addEventListener('click', () => this.close());

    footer.appendChild(dontShowLabel);
    footer.appendChild(next);
    footer.appendChild(this.closeBtn);

    root.appendChild(heading);
    root.appendChild(this.titleEl);
    root.appendChild(this.bodyEl);
    root.appendChild(footer);

    // A click in any FREE space (not the checkbox / buttons) closes it — Java wires the
    // closer to the shell / labels / footer, but never to the controls.
    root.addEventListener('mousedown', (e) => {
      if (e.target.closest('button, label, input')) return;
      this.close();
    });

    this.root = root;
    document.body.appendChild(root);

    // Escape closes (document-level listener, removed on close — Java SWT.Traverse ESCAPE).
    this.onKeyDown = (e) => { if (e.key === 'Escape') this.close(); };
    document.addEventListener('keydown', this.onKeyDown);

    this.secondsLeft = AUTO_CLOSE_SECS;
    this.countdownActive = true;
    this._updateCloseButton();   // "Close (20s)" before showing so the button is sized right
    this._showTip();
    this.timer = setInterval(() => this._onTick(), 1000);
  }

  /** Renders the current tip's title + body (Java showTip). */
  _showTip() {
    const n = this.index + 1;
    this.titleEl.textContent = this.t('tip.' + n + '.title');
    this.bodyEl.textContent = this.t('tip.' + n + '.body');
  }

  /** One countdown tick: decrement, close at 0, else relabel (Java onTick). */
  _onTick() {
    if (!this.root || !this.countdownActive) return;
    this.secondsLeft--;
    if (this.secondsLeft <= 0) { this.close(); return; }
    this._updateCloseButton();
  }

  /** Cancels the auto-close; the Close button drops its countdown and reads plain
   *  "Close" (Java stopCountdown, called once the user picks "Next tip"). */
  _stopCountdown() {
    this.countdownActive = false;
    if (this.timer !== null) { clearInterval(this.timer); this.timer = null; }
    if (this.closeBtn) this.closeBtn.textContent = this.t('tip.dialog.close');
  }

  /** Sets the Close button's countdown label (Java updateCloseButton). */
  _updateCloseButton() {
    if (this.closeBtn) this.closeBtn.textContent = this.t('tip.dialog.closeCountdown', this.secondsLeft);
  }

  /** Reads the tip count from i18n (Java readCount): tip.count, floored at 1. */
  _readCount() {
    const n = parseInt(String(this.t('tip.count')).trim(), 10);
    return Number.isFinite(n) ? Math.max(1, n) : 1;
  }

  /** Tears the popup down: stop the countdown, drop the Escape listener, remove the
   *  DOM node. Idempotent (Java shell.dispose + its dispose listener). */
  close() {
    if (this.timer !== null) { clearInterval(this.timer); this.timer = null; }
    this.countdownActive = false;
    if (this.onKeyDown) { document.removeEventListener('keydown', this.onKeyDown); this.onKeyDown = null; }
    if (this.root) { this.root.remove(); this.root = null; }
    this.titleEl = null;
    this.bodyEl = null;
    this.closeBtn = null;
  }
}
