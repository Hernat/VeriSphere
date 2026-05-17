# Releasing VeriSphere

This document covers the release procedure for VeriSphere — keystore handling, the seven-step distribution flow, the `version-info.json` format, and the pre-release Gemini model GA-status check.

> [!IMPORTANT]
> **Pre-V1 release policy (architecture decision D5.11).** The version `1.0.0` is **reserved** for the moment the founder substitution success criterion (PRD success criterion #1) is validated for ≥ 1 week of personal usage. Until then, all releases are pre-V1 alphas: `0.1.0`, `0.1.1`, `0.2.0`, …. Do not tag `v1.0.0` for tidiness — it would destroy the meaning of the V1 ship-gate.

> [!NOTE]
> **Status of this document.** The keystore handling section is a placeholder during pre-V1 development. The full keystore-generation steps, the GitHub Actions release workflow, and the signed-APK upload procedure are finalised in **Story 7.3**. The 7-step distribution flow below is canonical and will not change.

## Keystore handling (placeholder — full procedure in Story 7.3)

The release keystore is **founder-managed**, kept **outside** the repo, and backed up locally. CI uses a base64-encoded copy passed via the `RELEASE_KEYSTORE_BASE64` GitHub Secret (architecture decision D2.10).

The full procedure — generating the keystore with the right alias and password parameters, encoding it for the GitHub Secret, recovering from a lost keystore — lands with **Story 7.3** alongside the `release.yml` workflow. Until then, the release builds run unsigned (`./gradlew :app:assembleRelease` produces `app-release-unsigned.apk`).

## GitHub Secrets

GitHub Secrets used by the CI workflows. Set them once via the web UI (`Settings → Secrets and variables → Actions → New repository secret`) or via the `gh` CLI:

```sh
gh secret set <NAME> -R Hernat/VeriSphere
# Pipe a value from a file (POSIX):
gh secret set <NAME> -R Hernat/VeriSphere < path/to/secret.txt

# Or pass the file content as the body argument:
gh secret set <NAME> -R Hernat/VeriSphere --body "$(cat path/to/secret.txt)"
```

> [!NOTE]
> `gh secret set` does **not** support `@file` interpolation — passing `--body @path/to/secret.txt` would store the literal string `@path/to/secret.txt` as the secret value. The `@file` shorthand belongs to `gh api -F` only.

Verify with `gh secret list -R Hernat/VeriSphere`.

| Secret | Used by | Description |
|---|---|---|
| `GEMINI_API_KEY` | `pr.yml`, `main.yml`, `release.yml` | The bundled Gemini API key consumed by `BuildConfig.GEMINI_API_KEY` (D2.2). Without it, every build fails fast with a clear `GradleException` — Story 1.1's intentional behaviour. |
| `RELEASE_KEYSTORE_BASE64` | `release.yml` | Base64-encoded JKS keystore used to sign release APKs (D2.10). Lands with **Story 7.3**. Until then, `release.yml` produces an unsigned APK. |
| `RELEASE_KEYSTORE_PASSWORD` | `release.yml` | Keystore password. Lands with **Story 7.3**. |
| `RELEASE_KEY_ALIAS` | `release.yml` | Signing key alias inside the keystore. Lands with **Story 7.3**. |
| `RELEASE_KEY_PASSWORD` | `release.yml` | Signing key password. Lands with **Story 7.3**. |

**Encoding the keystore (Story 7.3 reference).** Once the production keystore exists, encode it for the GitHub Secret:

```sh
# macOS / Linux
base64 -w 0 release.keystore > release.keystore.b64

# Windows PowerShell — IMPORTANT: PowerShell's `>` redirection writes UTF-16 LE
# with a BOM by default; the resulting file would upload garbage to GitHub.
# Use [System.IO.File]::WriteAllText with explicit UTF-8 (no BOM) instead:
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
[System.IO.File]::WriteAllText("$PWD\release.keystore.b64", $b64, [System.Text.UTF8Encoding]::new($false))

# Set the secret from the encoded file (stdin redirect — works on both POSIX and PowerShell):
gh secret set RELEASE_KEYSTORE_BASE64 -R Hernat/VeriSphere < release.keystore.b64
```

## Release procedure (7-step distribution flow)

The canonical procedure is the project's distribution flow. Steps marked *(once Story 7.3 lands)* are automated by the CI workflow but documented here for reference.

1. **Bump `version.properties`.** Edit `version.properties` at the project root:
   - `PATCH` increments for fixes and small additions: `0.1.0` → `0.1.1`.
   - `MINOR` increments for noteworthy progress: `0.1.x` → `0.2.0`.
   - `MAJOR` is reserved: do not tag `1.0.0` until the founder substitution success criterion is validated for ≥ 1 week.
2. **Run the release build.** `./gradlew :app:assembleRelease`. Output lands at `app/build/outputs/apk/release/app-release.apk` (signed once the keystore is wired in Story 7.3) or `app-release-unsigned.apk` (during pre-V1 development).
3. **Verify APK size ≤ 10 MB (NFR2).** macOS / Linux: `wc -c < app/build/outputs/apk/release/app-release.apk`. Windows: `(Get-Item app/build/outputs/apk/release/app-release.apk).Length`. If the APK exceeds the budget, **stop** and audit the recent dependency additions before proceeding.
4. **Run the injection-corpus regression.** Execute `./scripts/run_injection_corpus.sh` and verify zero regressions against the curated injection corpus. This consumes ~30 Gemini Flash calls per run × N corpus revisions — see the [SECURITY.md quota note](./SECURITY.md#anti-injection-corpus-runner--quota-note). Mandatory before tagging a release. *(Pre-Story-7.1 releases skip this step; document the skip in the CHANGELOG.)* On Windows, run from Git Bash with ImageMagick installed (`choco install imagemagick`) and jq (`choco install jq`).
5. **Tag the release.** Once `version.properties` and `CHANGELOG.md` are committed (step 1):
   ```sh
   git tag -a v<MAJOR>.<MINOR>.<PATCH> -m "Release v<MAJOR>.<MINOR>.<PATCH>"
   git push --follow-tags
   ```
   The tag triggers the `release.yml` workflow *(once Story 7.3 lands)* which signs and uploads the APK to a GitHub Release auto-created for the tag.
6. **Mirror to Drive.** Download the signed APK from the GitHub Release. Upload the same APK to the Drive folder. Copy the share link.
7. **Update `version-info.json`.** Edit the file at the repo root with the schema below. Commit and push to `main`. On the next app launch, every existing user sees the in-app update banner (FR22–24) pointing to the new build.

## `version-info.json` format

The file lives at the repo root and is fetched by the in-app `VersionChecker` (architecture decision D3.9, lands with **Story 6.1**) from:

```
https://raw.githubusercontent.com/Hernat/VeriSphere/main/version-info.json
```

> [!NOTE]
> The repo path uses the canonical `Hernat/VeriSphere` casing matching the actual GitHub remote. `raw.githubusercontent.com` happens to be case-insensitive, but always **write the URL in the canonical casing** so that contributors copy-pasting it into case-sensitive contexts don't get burned.

Schema:

```json
{
  "latestVersion": "<MAJOR.MINOR.PATCH>",
  "downloadUrl": "<drive-share-link-or-github-release-asset-url>",
  "releasedAt": "<YYYY-MM-DD>"
}
```

Field rules:

- `latestVersion` — exact match against `BuildConfig.VERSION_NAME` parsed by the in-app checker. Must match the `version.properties` source-of-truth from step 1.
- `downloadUrl` — the Drive share link for the primary distribution channel. The GitHub Release asset URL works as a mirror but the architecture (D5.10) specifies Drive as the primary channel.
- `releasedAt` — ISO-8601 `YYYY-MM-DD` (architecture format pattern). Today's date.

## Branch protection on `main`

The `main` branch requires three CI status checks to pass before any merge: `lint`, `unit-tests`, `assemble-debug` (defined in `.github/workflows/pr.yml`). Configuration lives in GitHub server-state, not in the repo — recreate it from a fresh checkout with:

```sh
# JSON payload kept as a file because PowerShell's stdin redirection adds a UTF-16 BOM
cat > /tmp/branch-protection.json <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["lint", "unit-tests", "assemble-debug"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}
EOF

gh api -X PUT repos/Hernat/VeriSphere/branches/main/protection --input /tmp/branch-protection.json
```

Verify with:

```sh
gh api repos/Hernat/VeriSphere/branches/main/protection --jq '.required_status_checks'
```

Expected output: `{"contexts":["lint","unit-tests","assemble-debug"], "strict":true, ...}`.

`enforce_admins: false` lets the maintainer bypass the gate in emergencies; flip to `true` once external contributors are routine.

## Gemini model GA-status check (pre-release gate)

> [!IMPORTANT]
> Before tagging any release, verify the model configured in `GeminiClient` (per architecture decision D3.3 — `gemini-3-flash-preview` at planning time) has not been deprecated, restricted, or moved to a GA-only / preview-revoked status.
>
> Source of truth: [https://ai.google.dev/gemini-api/docs/models](https://ai.google.dev/gemini-api/docs/models).
>
> This is architecture validation Gap #5. If the planned model is no longer available on the free tier or has been deprecated:
> 1. Stop the release.
> 2. Adjust `GeminiClient` to the next-best Flash-tier model that maintains < 2 s P95 latency.
> 3. Re-run the injection corpus (step 4 above) against the new model.
> 4. Then resume the release procedure.

## Pre-tag checklist

Before pushing the tag, verify all of the following on a clean checkout:

- [ ] `version.properties` bumped per the Pre-V1 Release Policy.
- [ ] `CHANGELOG.md` updated — move entries from `[Unreleased]` to a new `[X.Y.Z] - YYYY-MM-DD` section.
- [ ] Gemini model GA-status checked at https://ai.google.dev/gemini-api/docs/models.
- [ ] `./scripts/run_injection_corpus.sh` exited 0.
- [ ] `./gradlew :app:assembleRelease` succeeded *(with the production keystore once Story 7.3 lands; produces `app-release-unsigned.apk` during pre-V1 development)*.
- [ ] APK size ≤ 10 MB.
- [ ] Drive folder share link copied.

## Post-tag checklist

- [ ] GitHub Release auto-created with the signed APK attached *(Story 7.3+)*.
- [ ] Drive mirror uploaded.
- [ ] `version-info.json` updated and pushed to `main` with the new `latestVersion`, `downloadUrl`, `releasedAt`.
- [ ] One previous-version device verified: cold-launching VeriSphere shows the update banner pointing to the new release.

## API-key rotation

The cross-cutting concern of rotating the bundled `GEMINI_API_KEY` mid-V1 is documented in [SECURITY.md → API-key rotation runbook](./SECURITY.md#api-key-rotation-runbook). It reuses steps 1, 2, 5, 6, 7 of the release procedure above.

## References

- Architecture document — Step 6 distribution flow (kept private during pre-V1; a public summary will land at `docs/architecture.md` before V1 ships).
- PRD — FR22–24 update channel (kept private during pre-V1).
- [SECURITY.md → API-key rotation runbook](./SECURITY.md#api-key-rotation-runbook)
- [CHANGELOG.md](./CHANGELOG.md)
