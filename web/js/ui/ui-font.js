/*
 * Phonalyser web - the UI font for canvas-drawn text.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The Look & Feel preferences land in the --ui-font* CSS variables (set by
 * PreferencesDialog.applyLookAndFeel); DOM text picks them up through the
 * stylesheet, but canvas text sets ctx.font itself and would otherwise stay
 * hard-coded - the desktop applies uiFontNormal/uiFontBold to those readouts
 * (FftView), so the canvas side must resolve the same preference. This is the
 * one place the variables are turned into a canvas font string, so a canvas
 * consumer can never drift from what the stylesheet shows.
 */

/**
 * The preference-driven UI font, resolved from the live CSS variables.
 *
 * @param {boolean} bold the emphasised variant (uiFontBold) instead of the base one
 * @returns {{css: string, px: number}} css: a ctx.font value; px: the resolved size
 *          in pixels, for line-height math
 */
export function uiFont(bold = false) {
  const cs = getComputedStyle(document.documentElement);
  const v = (name, fallback) => (cs.getPropertyValue(name).trim() || fallback);
  const suffix = bold ? '-bold' : '';
  const family = v(`--ui-font${suffix}`, 'Consolas');
  const px = parseFloat(v(`--ui-font${suffix}-size`, '12px')) || 12;
  const weight = v(`--ui-font${suffix}-weight`, bold ? 'bold' : 'normal');
  const style = v(`--ui-font${suffix}-style`, 'normal');
  return { css: `${style} ${weight} ${px}px "${family}", monospace`, px };
}
