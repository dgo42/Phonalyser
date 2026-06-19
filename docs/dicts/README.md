# Translation dictionaries

Domain glossaries that pin Phonalyser's audio-measurement / DSP terminology to a
**canonical** translation in each language, so transcripts, docs and (later) the
i18n bundles all use the same words. Hand-curated — NOT auto-derived from the
i18n `.properties` files.

## Files

One file per English↔language pair, named `en-<lang>.tsv`, where `<lang>` is the
BCP-47 tag used by the app's i18n bundles (`uk`, `de`, `fr`, …).

## Format

UTF-8, tab-separated, three columns, one term per line:

```
english<TAB>translation<TAB>note
```

- **english** — the canonical English term (the pivot; the same English string is
  shared across every `en-<lang>` file).
- **translation** — the canonical term in the target language.
- **note** — optional: acceptable synonyms, the *wrong* form to avoid, or usage
  context. May be empty.

Lines beginning with `#` are comments. Entries are sorted by the English column.
The mapping is bidirectional (look up by either column).

## Conventions

- Proper nouns and established acronyms stay verbatim (`REW`, `FFT`, `THD`, `DDS`,
  `RMS`) — list them so a translator knows *not* to translate them, and record the
  local expansion in the note.
- When a colloquial/borrowed form is common but non-standard, put the correct term
  in `translation` and the form to avoid in `note` (e.g. uk duty cycle is
  **прогальність**, not the Russianism *скважність*).
