# Phonalyser web — build targets

All commands run from `web/`. Output goes to `html/web/` (a static site you can serve from
any web server).

## Build targets

| Command | What it does | When |
|---|---|---|
| `npm run build` | Production build: bundles the app + workers (minified, linked source maps), copies static assets (`css`, `i18n`, `assets`, favicon, manifest) and the minimal vendor files, and stamps the version from `package.json`. **Does NOT touch the help** (kept fast). | Normal deploys. |
| `npm run build:debug` | Same as `build` but **unminified with inline source maps**, so DevTools → Sources shows the real module source and breakpoints hit. | Debugging the bundled app. |
| `npm run build:copy-help` | Regenerates the help search index (`window.HELP_DOCS` per language, via `scripts/build-help-index.mjs`) and copies `web/help` → `html/web/help`. Writes **into the existing `html/web/`**, so run it **after** `npm run build`. | Only when the help changed. |

A full deploy with help: `npm run build && npm run build:copy-help`.

## Help workflow (supporting scripts)

The desktop (Java) help under `../src/main/resources/help` is the source of truth for help
structure + theory text; `web/help/` is the web's own copy that diverges only in screenshots
and the build-time-regenerated index. See the **sync-help** skill for the full loop.

| Command | What it does |
|---|---|
| `npm run import-help` | (Re-)copies the Java help into `web/help` (preserves web-captured `img/`). |
| `npm run capture-help` | Captures help screenshots from the running **web** app at a fixed **1280×768** window into `web/help/<lang>/img/`. `--list` shows specs + readiness; pass spec ids to capture a subset. |
| `npm run build:copy-help` | Regenerate index + copy help to `html/web` (see above). |

## Other

| Command | What it does |
|---|---|
| `npm run dev` | Serves the unbundled `web/` source (no build, `Cache-Control: no-store`) for breakpoint debugging. |
| `npm run e2e` | Headless scope smoke test. |
| `npm run vendor` | Re-extracts the browser-servable vendor files from `node_modules` (also runs on `npm install`). |
