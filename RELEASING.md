# Releasing VeriSphere

This document covers the release procedure for VeriSphere — keystore generation + handling, the seven-step distribution flow, the `version-info.json` format, the pre-release Gemini model GA-status check, and the R8 minify verification gate.

> [!IMPORTANT]
> **Pre-V1 release policy (architecture decision D5.11).** The version `1.0.0` is **reserved** for the moment the founder substitution success criterion (PRD success criterion #1) is validated for ≥ 1 week of personal usage. Until then, all releases are pre-V1 alphas: `0.1.0`, `0.1.1`, `0.2.0`, …. Do not tag `v1.0.0` for tidiness — it would destroy the meaning of the V1 ship-gate.

## Keystore generation (one-time)

The release keystore is **founder-managed**, kept **outside** the repo, and backed up locally. CI uses a base64-encoded copy passed via the `RELEASE_KEYSTORE_BASE64` GitHub Secret (architecture decision D2.10).

Generate the V1 production keystore once with `keytool` (ships with the JDK; available on Windows as `keytool.exe` from `$env:JAVA_HOME\bin`, on macOS / Linux as `keytool` from `$JAVA_HOME/bin`):

```sh
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias verisphere-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=VeriSphere, OU=, O=Hernat, L=Paris, S=, C=FR"
```

`keytool` prompts for the keystore password (≥ 6 chars) and the key password. Use a 24+ character random passphrase from a password manager — the same passphrase for both keystore and key is acceptable (and simplifies the 4-secret upload) but a distinct key password is fine too. The 10000-day validity (~27 years) matches the [Google Play recommended best practice](https://developer.android.com/studio/publish/app-signing#sign_release) — even though Play Store is not a V1 distribution channel, the same discipline applies.

**Backup discipline (mandatory).** The keystore is a single point of failure (see disaster-recovery callout below). Store the keystore + the passphrase together in:

1. **Primary** — the founder's machine in an encrypted home-directory location (recommended: `~/Documents/keystores/verisphere/`). Pair the keystore file with a `release.keystore.passphrase.txt` containing the password.
2. **Secondary** — an external encrypted drive (USB key OR encrypted cloud — but **NOT** the V1 distribution Drive folder, which is public-share-link territory per D5.10).

The repo `.gitignore` excludes `*.keystore` globally — the keystore must NEVER be committed.

> [!CAUTION]
> **Lost-keystore disaster recovery: there is none.** Android verifies APK signatures on every update. An APK signed with key A **cannot** update an APK signed with key B. The only "recovery" paths after losing the V1 keystore are:
>
> 1. **Bump `applicationId`** from `com.verisphere.app` to `com.verisphere.app.v2` (or similar). This creates a new app from Android's POV — every existing install must side-by-side OR uninstall+reinstall the new APK. The in-app history is **lost** because `EncryptedSharedPreferences` is rooted in `applicationId` per the Android Keystore master-key scoping.
> 2. **Accept user attrition** and re-ship without an upgrade pathway. Existing users keep their working install until they manually wipe data or change device.
>
> Play App Signing (which would let Google re-sign with a recovery key if the original is lost) is deferred to V2 alongside the Play Store distribution channel (architecture D5.10). The single-point-of-failure trade-off is **intentional** per D2.10 — discipline beats infrastructure.

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
| `RELEASE_KEYSTORE_BASE64` | `release.yml` | Base64-encoded JKS keystore used to sign release APKs (D2.10). Consumed by `release.yml` `Decode keystore` step, which writes it to `$RUNNER_TEMP/release.keystore`. Absent → `release.yml` falls back to producing an unsigned APK with a `::warning::` log line. |
| `RELEASE_KEYSTORE_PASSWORD` | `release.yml` | Keystore password. Consumed by `signingConfigs.release` in `app/build.gradle.kts` via the `RELEASE_KEYSTORE_PASSWORD` env var. |
| `RELEASE_KEY_ALIAS` | `release.yml` | Signing key alias inside the keystore (`verisphere-release` per the canonical `keytool` invocation in [Keystore generation](#keystore-generation-one-time) above). |
| `RELEASE_KEY_PASSWORD` | `release.yml` | Signing key password (may match the keystore password). |

> [!NOTE]
> The four `RELEASE_KEYSTORE_*` secrets are an all-or-nothing set. Either all 4 are set (signed release build) OR none of them are (unsigned-fallback build). Partial env-var sets are caught at Gradle configuration time by `resolvedReleaseKeystore` in [app/build.gradle.kts](./app/build.gradle.kts) with an operator-error `GradleException` — better to fail loud than ship a silently-unsigned APK.

**Encoding the keystore for the GitHub Secret.** Once the production keystore exists, encode it for the GitHub Secret:

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

The canonical procedure is the project's distribution flow. Steps 2–5 are automated by the `release.yml` workflow when you push a `v*.*.*` tag; steps 1, 6, and 7 remain manual.

1. **Bump `version.properties`.** Edit `version.properties` at the project root:
   - `PATCH` increments for fixes and small additions: `0.1.0` → `0.1.1`.
   - `MINOR` increments for noteworthy progress: `0.1.x` → `0.2.0`.
   - `MAJOR` is reserved: do not tag `1.0.0` until the founder substitution success criterion is validated for ≥ 1 week.
2. **Run the release build.** `./gradlew :app:assembleRelease`. Output lands at `app/build/outputs/apk/release/app-release.apk` when all 4 `RELEASE_KEYSTORE_*` env vars resolve at Gradle configuration time (CI: from GitHub Secrets; local: from your shell env) OR `app-release-unsigned.apk` when none are set (unsigned-fallback for local dev without a keystore). Partial env-var sets fail loud with a `GradleException` per `resolvedReleaseKeystore` in [app/build.gradle.kts](./app/build.gradle.kts).
3. **Verify APK size ≤ 10 MB (NFR2).** macOS / Linux: `wc -c < app/build/outputs/apk/release/app-release.apk`. Windows: `(Get-Item app/build/outputs/apk/release/app-release.apk).Length`. If the APK exceeds the budget, **stop** and audit the recent dependency additions before proceeding.
4. **Run the injection-corpus regression.** Execute `./scripts/run_injection_corpus.sh` against the curated injection corpus. This consumes ~30 Gemini Flash calls per run × N corpus revisions — see the [SECURITY.md quota note](./SECURITY.md#anti-injection-corpus-runner--quota-note). Two acceptance thresholds (Story 7.1 CDN #11):
   - **Pre-V1 patch releases (0.x.y):** pass rate ≥ 90% acceptable iff every FAIL is triaged in `_bmad-output/implementation-artifacts/deferred-work.md` per CDN #11 (a) tighten case / (b) remove case / (c) Story-7.x prompt-hardening follow-up. Override the strict gate via `EXIT_THRESHOLD=90 ./scripts/run_injection_corpus.sh`.
   - **V1.0.0-RC tag-time hardening (epics L939):** 100% pass mandatory. Default `./scripts/run_injection_corpus.sh` (no env var) exits 1 on ANY regression.

   On Windows, run from Git Bash with ImageMagick installed (`choco install imagemagick`) and jq (`choco install jq`).
5. **Tag the release.** Once `version.properties` and `CHANGELOG.md` are committed (step 1):
   ```sh
   git tag -a v<MAJOR>.<MINOR>.<PATCH> -m "Release v<MAJOR>.<MINOR>.<PATCH>"
   git push --follow-tags
   ```
   The tag triggers the `release.yml` workflow, which signs the APK with the production keystore, verifies the signed APK is ≤ 10 MB binary, extracts the relevant `## [<version>]` section from `CHANGELOG.md` as the Release body, and uploads the signed APK to a GitHub Release auto-created for the tag.
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

The `main` branch requires four CI status checks to pass before any merge: `lint`, `unit-tests`, `assemble-debug`, `version-info-guard` (defined in `.github/workflows/pr.yml`). Configuration lives in GitHub server-state, not in the repo — recreate it from a fresh checkout with:

```sh
# JSON payload kept as a file because PowerShell's stdin redirection adds a UTF-16 BOM
cat > /tmp/branch-protection.json <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["lint", "unit-tests", "assemble-debug", "version-info-guard"]
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

Expected output: `{"contexts":["lint","unit-tests","assemble-debug","version-info-guard"], "strict":true, ...}`.

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

## R8 minify verification (pre-release gate)

> [!IMPORTANT]
> Before tagging any release, verify the R8 minify pipeline did not regress the keep rules in `proguard-rules.pro` (architecture decision D5.5 / AR6 / NFR2).

The release APK is built with `isMinifyEnabled = true` + `isShrinkResources = true` + custom keep rules covering (1) `@Serializable` types in `com.verisphere.app.gemini.**` + `com.verisphere.app.storage.SessionRecord` + `com.verisphere.app.update.**`, and (2) `-assumenosideeffects` stripping `Log.d` / `Log.v` / `Log.i` at `proguard-rules.pro` L12-16. The `app/build.gradle.kts` `buildTypes.release` block further restricts the release APK to `arm64-v8a + armeabi-v7a` via `ndk { abiFilters }` (D5.6) — debug builds keep the wider ABI surface for emulator workflows.

> [!IMPORTANT]
> **R8 strips ALL `Log.*` calls by default — Log.w / Log.e / Log.wtf included.** Architecture L502-504 + D2.7 describe `Log.w` / `Log.e` as "the only severity levels available in release" — that description is INACCURATE for the current build configuration. The AGP-bundled `proguard-android-optimize.txt` (referenced via `getDefaultProguardFile(...)`) ships a SUPERSET `-assumenosideeffects` block covering `Log.v / i / w / d / e / isLoggable`, so the release DEX contains ZERO `android/util/Log` references regardless of source code. This is a STRONGER NFR7 posture than originally documented; future architecture amendment tracked in `_bmad-output/implementation-artifacts/deferred-work.md`.

**Tool resolution.** All commands below reference the latest SDK build-tools directory via `$BT_LATEST`. Resolve it once at the top of your session:

```sh
# Git Bash / Linux / macOS
export BT_LATEST="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
# PowerShell
$BT_LATEST = "$env:ANDROID_HOME\build-tools\$((Get-ChildItem "$env:ANDROID_HOME\build-tools" | Sort-Object Name | Select-Object -Last 1).Name)"
```

**Evidence (a) — `@Serializable` types preserved.** Locate `apkanalyzer` (ships in `$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer[.bat]`; on older SDKs at `$ANDROID_HOME/tools/bin/apkanalyzer`). Then probe the release DEX for the 3 narrowed-keep-rule `@Serializable` type families:

```sh
apkanalyzer dex packages app/build/outputs/apk/release/app-release-unsigned.apk \
  | grep -E 'com\.verisphere\.app\.(gemini\.GeminiVerdictResponse|storage\.SessionRecord|update\.VersionInfo)'
```

Expected: ≥ 9 lines — for each of `GeminiVerdictResponse`, `SessionRecord`, `VersionInfo`, the class itself + its `$Companion` object + its kotlinx.serialization `$$serializer` companion. Also spot-check the top-level `com.verisphere.app.gemini.SourceCitation` type (which is referenced by `GeminiVerdictResponse.sources` but lives as its own top-level `@Serializable` data class — verify via grep `'com\.verisphere\.app\.gemini\.SourceCitation'`). Missing references mean the narrowed keep rules in `proguard-rules.pro` L54-74 regressed — investigate before tagging.

**Evidence (b) — `Log.*` calls stripped.**

```sh
apkanalyzer dex packages app/build/outputs/apk/release/app-release-unsigned.apk \
  | grep -E 'android\.util\.Log'
```

Expected: **empty output** (R8's default optimize block strips every `Log.*` method call — see IMPORTANT callout above). Any non-empty match means the `-assumenosideeffects` directives regressed — the most common cause is removing the `proguard-android-optimize.txt` reference from `app/build.gradle.kts` `proguardFiles(...)`. **Do NOT add `--defined-only`** — `android.util.Log` lives in the Android framework JAR, never defined in the app DEX, and the `--defined-only` filter would mask all references regardless of strip status.

**Evidence (c) — release APK launches end-to-end.** Install + smoke the release APK on a Pixel-class device or Android-16 AVD.

> [!CAUTION]
> **Local dev without a release keystore:** `:app:assembleRelease` produces a truly unsigned APK (`app-release-unsigned.apk`) that `adb install` rejects with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`. If you want to install + smoke the release-config APK locally without setting up the production keystore, debug-sign it with the AGP-generated debug keystore (already present at `~/.android/debug.keystore` after any `assembleDebug` run). Resolve `$BT_LATEST` per the "Tool resolution" block above, then:
>
> ```sh
> "$BT_LATEST/zipalign" -p -f 4 \
>     app/build/outputs/apk/release/app-release-unsigned.apk \
>     /tmp/app-release-aligned.apk
> cp /tmp/app-release-aligned.apk \
>    app/build/outputs/apk/release/app-release-debug-signed.apk
> rm /tmp/app-release-aligned.apk
> "$BT_LATEST/apksigner" sign \
>     --ks ~/.android/debug.keystore --ks-pass pass:android \
>     --ks-key-alias androiddebugkey --key-pass pass:android \
>     app/build/outputs/apk/release/app-release-debug-signed.apk
> adb install -r -t app/build/outputs/apk/release/app-release-debug-signed.apk
> ```
>
> **Windows / Git Bash note:** `apksigner.bat` is a JVM wrapper — set `JAVA_HOME` to a JDK 11+ install (Android Studio ships one at `<Studio-install>/jbr/` on recent versions). Git Bash's `~` expands to a POSIX path that apksigner's JDK File constructor handles correctly on AGP 9.x; if you encounter `Cannot find keystore`, substitute `"$USERPROFILE/.android/debug.keystore"` (Windows-native form).
>
> On CI, the workflow signs with the production keystore consumed via the `RELEASE_KEYSTORE_*` GitHub Secrets — the install target there is the signed `app-release.apk`, not the unsigned variant.

Complete onboarding. Long-press the bubble over a screen containing a verifiable claim. Verify: (1) the bubble flips to Verdict state within ~10 s, (2) tapping the bubble opens `AnchoredDetailPanel` with the verdict + OCR + sources, (3) the History list reflects the new `SessionRecord` on next `MainActivity` open. This proves Compose runtime, OkHttp, kotlinx.serialization, and AndroidX Security all survived R8 + resource shrinking.

> [!NOTE]
> Verdict proof is captured via the UI dump (Compose `content-desc="Verdict: <label>. <ocr>..."` on the row + the AnchoredDetailPanel render) — **NOT via logcat**, because the IMPORTANT callout above explains that all `Log.*` calls are stripped from the release DEX. A `logcat -s VS.GeminiClient:*` probe will produce no output regardless of verdict outcome.

> [!NOTE]
> See [SECURITY.md → API-key rotation runbook](./SECURITY.md#api-key-rotation-runbook) for the framing of the `BuildConfig.GEMINI_API_KEY` constant — it is **intentionally** visible in the release APK's strings table (NFR9 known-extractable; mitigated by per-device rate limiting + rotation procedure, not obfuscation) and is NOT an R8 regression.

### Fallback when `apkanalyzer` is unavailable or broken

`apkanalyzer.bat` on Windows SDK installs that ship `cmdline-tools/` only (no legacy `tools/bin/`) throws `IllegalStateException: The tools directory property is not set`. Workaround via `dexdump` (lives in `$BT_LATEST/dexdump[.exe]` per the "Tool resolution" block above):

```sh
# Extract classes.dex from the APK (an APK is a ZIP)
unzip -o -q app/build/outputs/apk/release/app-release-unsigned.apk classes.dex -d /tmp/apk-extract/

# Evidence (a) via dexdump — single-quoted pattern so backslashes survive into grep
# (slash-format JVM-internal names, NOT dot-format apkanalyzer names)
"$BT_LATEST/dexdump" /tmp/apk-extract/classes.dex \
  | grep -E 'Class descriptor  : .L(com/verisphere/app/(gemini/(GeminiVerdictResponse|SourceCitation)|storage/SessionRecord|update/VersionInfo))(\$\$serializer|\$Companion)?;.$'

# Evidence (b) via dexdump
"$BT_LATEST/dexdump" /tmp/apk-extract/classes.dex \
  | grep -iE 'android/util/Log'
```

The R8 `app/build/outputs/mapping/release/usage.txt` strip log is a third fallback (lists every class / method / field R8 stripped). Evidence (c) (the smoke) remains the canonical functional proof regardless of which probe tool is available.

## Pre-tag checklist

Before pushing the tag, verify all of the following on a clean checkout:

- [ ] `version.properties` bumped per the Pre-V1 Release Policy.
- [ ] `CHANGELOG.md` updated — move entries from `[Unreleased]` to a new `[X.Y.Z] - YYYY-MM-DD` section.
- [ ] Gemini model GA-status checked at https://ai.google.dev/gemini-api/docs/models.
- [ ] `./scripts/run_injection_corpus.sh` exited 0 (V1.0.0-RC tag) OR every FAIL is logged in `_bmad-output/implementation-artifacts/deferred-work.md` with a Story-7.x or `system_prompt_v2.txt` follow-up (pre-V1 patch tags 0.x.y per Story 7.1 CDN #11).
- [ ] `./gradlew :app:assembleRelease` succeeded. Pre-tag verification runs on CI via `release.yml` — locally, set the 4 `RELEASE_KEYSTORE_*` env vars to produce the signed `app-release.apk` or leave them unset to produce the unsigned-fallback `app-release-unsigned.apk` (size verification gates either form at 10,485,760 bytes binary).
- [ ] APK size ≤ 10 MB (`10,485,760` bytes binary-MB). Fail-loud variants (PowerShell uses `Write-Error + exit 1` for non-zero exit parity with awk `exit 1`; both branches yield `$LASTEXITCODE == 1` on failure):
  - PowerShell: `$apk = 'app/build/outputs/apk/release/app-release-unsigned.apk'; if (-not (Test-Path $apk)) { Write-Error "APK missing: $apk"; exit 1 }; if ((Get-Item $apk).Length -le 10485760) { 'OK' } else { Write-Error 'APK exceeds 10 MB binary'; exit 1 }`
  - Git Bash / Linux: `stat --printf '%s\n' app/build/outputs/apk/release/app-release-unsigned.apk | awk '{ if ($1 <= 10485760) print "OK"; else { print "APK exceeds 10 MB binary" > "/dev/stderr"; exit 1 } }'`
  - macOS (BSD stat): `stat -f '%z' app/build/outputs/apk/release/app-release-unsigned.apk | awk '{ if ($1 <= 10485760) print "OK"; else { print "APK exceeds 10 MB binary" > "/dev/stderr"; exit 1 } }'`
- [ ] Drive folder share link copied.

## Post-tag checklist

- [ ] GitHub Release auto-created with the signed APK attached (verify via `gh release view v<MAJOR>.<MINOR>.<PATCH> -R Hernat/VeriSphere`).
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
