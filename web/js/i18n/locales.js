/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the language-discovery / display-label logic in
// org.edgo.audio.measure.gui.MainWindow (discoverLanguageTags / tagFromFileName
// / displayLabel) - it enumerates the bundled messages_*.properties files,
// turns each filename into a BCP-47 tag (messages_zh_TW.properties -> "zh-TW",
// i.e. '_' -> '-'), and labels it with the language's own endonym, first
// letter upper-cased (Locale.forLanguageTag(tag).getDisplayName(loc)).
//
// In Java the endonym comes from the JRE's CLDR data at runtime.  The browser
// has no equivalent guaranteed source, so the endonyms below are baked in to
// match what the desktop app renders.  The base "en" bundle (messages.properties,
// no suffix) is the default locale and is listed first.

/**
 * @typedef {Object} LocaleEntry
 * @property {string} tag      BCP-47 language tag (e.g. "de", "zh-TW").
 * @property {string} file     `messages_<...>.properties` suffix tag, '-' -> '_'
 *                             (e.g. "zh_TW"), or "" for the base English bundle.
 * @property {string} endonym  Native-language label, first letter upper-cased.
 */

/**
 * Full list of UI locales, in the same order the desktop Language menu builds
 * them: base English first, then the per-locale bundles sorted by file tag.
 * Built from the actual `messages_<tag>.properties` filenames under
 * `src/main/resources/i18n/`.
 *
 * @type {ReadonlyArray<LocaleEntry>}
 */
export const LOCALES = Object.freeze([
  { tag: 'en',    file: '',      endonym: 'English' },
  { tag: 'bg',    file: 'bg',    endonym: 'Български' },
  { tag: 'ca',    file: 'ca',    endonym: 'Català' },
  { tag: 'cs',    file: 'cs',    endonym: 'Čeština' },
  { tag: 'da',    file: 'da',    endonym: 'Dansk' },
  { tag: 'de',    file: 'de',    endonym: 'Deutsch' },
  { tag: 'el',    file: 'el',    endonym: 'Ελληνικά' },
  { tag: 'es',    file: 'es',    endonym: 'Español' },
  { tag: 'et',    file: 'et',    endonym: 'Eesti' },
  { tag: 'fi',    file: 'fi',    endonym: 'Suomi' },
  { tag: 'fr',    file: 'fr',    endonym: 'Français' },
  { tag: 'ga',    file: 'ga',    endonym: 'Gaeilge' },
  { tag: 'he',    file: 'he',    endonym: 'עברית' },
  { tag: 'hr',    file: 'hr',    endonym: 'Hrvatski' },
  { tag: 'hu',    file: 'hu',    endonym: 'Magyar' },
  { tag: 'is',    file: 'is',    endonym: 'Íslenska' },
  { tag: 'it',    file: 'it',    endonym: 'Italiano' },
  { tag: 'ja',    file: 'ja',    endonym: '日本語' },
  { tag: 'lt',    file: 'lt',    endonym: 'Lietuvių' },
  { tag: 'lv',    file: 'lv',    endonym: 'Latviešu' },
  { tag: 'mt',    file: 'mt',    endonym: 'Malti' },
  { tag: 'nb',    file: 'nb',    endonym: 'Norsk bokmål' },
  { tag: 'nl',    file: 'nl',    endonym: 'Nederlands' },
  { tag: 'pl',    file: 'pl',    endonym: 'Polski' },
  { tag: 'pt',    file: 'pt',    endonym: 'Português' },
  { tag: 'ro',    file: 'ro',    endonym: 'Română' },
  { tag: 'sk',    file: 'sk',    endonym: 'Slovenčina' },
  { tag: 'sl',    file: 'sl',    endonym: 'Slovenščina' },
  { tag: 'sv',    file: 'sv',    endonym: 'Svenska' },
  { tag: 'tr',    file: 'tr',    endonym: 'Türkçe' },
  { tag: 'uk',    file: 'uk',    endonym: 'Українська' },
  { tag: 'zh-TW', file: 'zh_TW', endonym: '繁體中文' },
]);

/**
 * Look up a locale entry by BCP-47 tag (case-insensitive on the tag).
 * @param {string} tag BCP-47 language tag (e.g. "de", "zh-TW").
 * @returns {LocaleEntry|undefined}
 */
export function localeByTag(tag) {
  if (!tag) return undefined;
  const t = String(tag).toLowerCase();
  return LOCALES.find((e) => e.tag.toLowerCase() === t);
}
