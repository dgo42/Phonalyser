# How to cut a release

Releasing is **tag-driven**: pushing a version tag runs
[.github/workflows/release.yml](.github/workflows/release.yml), which builds
every OS artifact and attaches them to a **draft** GitHub release.  You then
review and publish that draft by hand.

For build internals (Maven profiles, jpackage, per-OS natives) see
[PACKAGING.md](PACKAGING.md).  This file is just the release procedure.

## What a tag produces

A tag matching `v*` or `[0-9]*` (e.g. `v1.0-RC2`, `1.0.0`) triggers a build
matrix and a draft release with these assets:

| OS                    | Runner       | Installer                       | Fat JAR                          |
| --------------------- | ------------ | ------------------------------- | -------------------------------- |
| Windows x64           | windows-latest | `Phonalyser-<ver>.msi`        | `phonalyser-<ver>-windows.jar`   |
| Linux x64             | ubuntu-latest  | `Phonalyser-<ver>.deb`        | `phonalyser-<ver>-linux.jar`     |
| macOS Intel (x86_64)  | macos-13       | `Phonalyser-<ver>-x64.dmg`    | `phonalyser-<ver>-macos-x64.jar` |
| macOS Apple Silicon   | macos-14       | `Phonalyser-<ver>-arm64.dmg`  | `phonalyser-<ver>-macos.jar`     |

> **macOS ships two DMGs on purpose.** jpackage bundles a JRE that matches the
> *build* machine's CPU. An arm64-only build is rejected by Intel Macs with
> *"…is not supported on this Mac."*, and an x64 build runs on Apple Silicon
> only through Rosetta. So both arches are built natively and shipped.
> Never collapse the macOS job back to a single `macos-latest` runner.

## Release steps

1. **Finish and commit the code** you want in the release. The tag must point
   at a commit that already contains the release workflow — verify the macOS
   matrix job is present:
   ```bash
   git grep -l "macos-13" .github/workflows/release.yml   # must print the file
   ```

2. **Decide the version.** Tags follow the existing `v<major>.<minor>[-RCn]`
   style (last tag: `v1.0-RC1`). Keep `app.version` in
   [pom.xml](pom.xml) in sync with the tag if you bump it.

3. **Push the branch, then the tag** (annotated):
   ```bash
   git push origin <branch>            # e.g. develop or main — the tagged commit must be on the remote
   git tag -a v1.0-RC2 -m "v1.0-RC2"
   git push origin v1.0-RC2            # this is what triggers the build
   ```

4. **Watch the run** under the repo's **Actions → Build installers** tab.
   All five build legs (Windows, Linux, macOS×2) must go green; the `release`
   job runs last and only on a real tag ref.

5. **Verify the draft release** (Releases → the new draft). Confirm all assets
   attached — in particular **both** macOS DMGs:
   `Phonalyser-…-x64.dmg` **and** `Phonalyser-…-arm64.dmg`.

6. **Publish** the draft when satisfied. The release is created as a draft
   (`draft: true` in the workflow), so nothing is public until you click
   *Publish release*.

## Notes & gotchas

- **Windows natives must be committed.** The Windows leg fails loudly if
  `lib/windows/{csjsound-provider.jar,csjsound_amd64.dll,portaudio_x64.dll}`
  are missing. See [lib/windows/README.md](lib/windows/README.md).
- **Dry run without releasing.** Use **Actions → Build installers → Run
  workflow** (`workflow_dispatch`). It builds the whole matrix but the
  `release` job is skipped (it is gated on a tag ref), so you can download the
  artifacts from the run and test them before tagging for real.
- **Re-tagging.** Avoid moving an existing tag. If you must rebuild the same
  version, delete the tag locally and remotely first
  (`git push origin :refs/tags/<tag>`) and re-create it — but prefer a new
  `-RCn` instead.
- **Local one-off build.** To produce a single installer on your own machine
  (e.g. test the Intel DMG on an Intel Mac), just run `mvn -DskipTests package`
  there — the Maven profile auto-selects the host arch. See
  [PACKAGING.md](PACKAGING.md) §3.
