/*
 * Phonalyser web - keeping a broken store instead of overwriting it.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of Preferences.quarantine - the localStorage variant. The desktop renames a
 * store FILE it cannot parse to <name>.corrupt; the web copies the ENTRY to <key>.corrupt,
 * which is the same act: the run continues from DEFAULTS, and the next save can no longer
 * cement a defaults-only document over the evidence of what went wrong.
 */

/**
 * Keeps the unreadable value of `key` aside and reports it. Never throws: a broken store must
 * not stop the app starting, and a storage that refuses the copy (quota, private mode) is
 * reported and left alone - the caller proceeds from defaults either way.
 *
 * @param {string} key the localStorage key whose value would not parse
 * @param {string} raw its raw text, already read
 * @param {string} why what was wrong with it (a parse error, or "not a map")
 */
export function quarantineStoreEntry(key, raw, why) {
  const kept = key + '.corrupt';
  let recovered;
  try {
    localStorage.setItem(kept, raw);
    localStorage.removeItem(key);
    recovered = 'the broken value is kept as ' + kept;
  } catch (e) {
    recovered = 'and it could not be kept aside (' + (e && e.message)
      + ') - it is left in place and the next save will overwrite it';
  }
  console.error(`${key} could not be read - ${why}. This run starts from DEFAULTS; ${recovered}.`);
}
