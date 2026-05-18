# Security policy

This document covers the threat model, the API-key rotation runbook, and how to report vulnerabilities. It is the single source of truth for the security posture of the codebase.

## Threat-model snapshot

VeriSphere V1 is an Android app that calls the public Gemini API and persists session data locally. The security posture is intentionally minimal:

- **Bundled `BuildConfig.GEMINI_API_KEY`** is treated as a **known-extractable secret**. An attacker who downloads the APK and runs `apktool` or `strings` will recover the key. The architecture accepts this trade-off (NFR9) on the basis that obfuscation theatre would not change the threat model. Mitigation is on two axes:
    1. **Per-device daily rate limiting** (30 captures / device / UTC-day) caps the abuse a single extracted key can do (architecture decision D3.7).
    2. **Documented rotation runbook** (below) propagates a new key via a new build + the in-app version-info banner (architecture decision D2.3).
- **Encryption at rest** for everything persisted: `EncryptedSharedPreferences` backed by an Android Keystore master key (AES-256-GCM, non-extractable). Covers session history, OCR text, rate-limit counters, and bubble position (architecture decision D1.6).
- **No cloud backup.** `android:allowBackup="false"` in the manifest blocks Auto Backup from copying the encrypted preferences to Google Drive (architecture decision D1.5). The encrypted prefs would be unreadable on another device anyway (the master key stays in the originating device's keystore), but the manifest declaration removes the privacy-leak signal.
- **HTTPS only**, enforced by `network_security_config.xml` with `cleartextTrafficPermitted="false"` globally (architecture decision D2.5).
- **No certificate pinning in V1.** Operational trade-off — the Gemini endpoint is on Google infrastructure and pinning would break legitimate certificate rotation. Re-evaluated when V2 ships.
- **No remote logging, no telemetry, no analytics.** The only outbound HTTPS traffic is to `generativelanguage.googleapis.com` (Gemini API) and `raw.githubusercontent.com` (version-info JSON fetch). See PRD NFR7 / architecture decision D5.12.
- **CI secret exposure on `pull_request` builds — accepted posture.** GitHub Actions PR runs on this repo expose `GEMINI_API_KEY` to any code executable during the build (`build.gradle.kts`, Gradle plugins, dependencies) because Story 1.1's fail-fast logic forces the key to be present at Gradle configuration time. A hostile branch on this repo could exfiltrate the key via a one-line build-script change. Mitigation is the same as for the bundled key in shipped APKs: **rate-limiting (D3.7) + rotation runbook (D2.3)**, not obfuscation. External fork PRs do **not** receive repository secrets (GitHub default — workflow runs from forks have an empty `secrets` context for protected workflows). Branch-push access on this repo is restricted to the maintainer; collaborator additions go through GitHub's standard repository-permissions flow.

## Reporting a vulnerability

Please **do not** open a public issue for a security finding. Use one of these channels:

1. **Preferred — GitHub Security Advisories.** Open a private vulnerability report on the repo's [Security tab](https://github.com/Hernat/VeriSphere/security/advisories/new). This is end-to-end private to the maintainers.
2. **Email fallback.** `tokyhernat@gmail.com`.

**Response expectations:** VeriSphere is a side-project run by a solo founder. Acknowledgement is best-effort, typically within seven days. There is no contractual SLA. Critical findings will be addressed before any unaffected story merges.

When reporting, please include:

- A clear description of the vulnerability and its potential impact.
- Steps to reproduce, ideally on the latest published APK or `main` branch.
- Your suggested severity (Critical / High / Medium / Low) and any mitigating factors.
- Whether you would like to be credited in the [CHANGELOG](./CHANGELOG.md) when the fix ships.

## API-key rotation runbook

This is the operational procedure for the day the bundled `BuildConfig.GEMINI_API_KEY` needs to be rotated — typically because the key is being burned by an extracted-and-abused copy of the APK in the wild. It maps to architecture decision D2.3 and cross-cutting concern CC#7 ("API-key rotation pathway").

1. **Detect abuse.** Signal sources, in order of likelihood:
    - The Google AI Studio dashboard for the project shows quota exhaustion or an unusual call pattern.
    - Founder usage starts hitting the daily rate limit on a device that has not been used 30 times that day (suggests the key is being shared).
    - A user reports a `Verdict.Failure.ApiQuotaExhausted` flash within minutes of cold-starting on a fresh install.
2. **Revoke the key.** In [Google AI Studio](https://aistudio.google.com/apikey), delete the compromised key. This stops all in-flight abuse immediately, but also stops every existing app install from working.
3. **Re-issue.** Create a new API key in AI Studio. Restrict it to the Generative Language API (Gemini) and apply any per-key quota limits the founder considers prudent.
4. **Bump `version.properties`.** Increment `PATCH` (or `MINOR` if other unreleased changes are bundled). Pre-V1 alphas stay on `0.x.y`; **do not jump to `1.0.0`** until the founder-substitution success criterion is validated for ≥ 1 week of personal usage (architecture D5.11).
5. **Rebuild and sign.** `./gradlew :app:assembleRelease` with the new key in `local.properties` for a local build, or push a `v<MAJOR>.<MINOR>.<PATCH>` tag to trigger the `release.yml` workflow which signs with the production keystore via the `RELEASE_KEYSTORE_*` GitHub Secrets ([RELEASING.md → Keystore generation](./RELEASING.md#keystore-generation-one-time)).
6. **Publish the APK.** Upload to the Drive folder and create a GitHub Release for the new tag with the signed APK attached.
7. **Update `version-info.json`.** Edit the file at the repo root: bump `latestVersion`, set `downloadUrl` to the new Drive link, set `releasedAt` to today's `YYYY-MM-DD`. Commit and push to `main`. The file is fetched from `https://raw.githubusercontent.com/Hernat/VeriSphere/main/version-info.json` (architecture decision D3.9). The repo path uses the canonical `Hernat/VeriSphere` casing.
8. **Banner propagation.** On their next app launch, every existing user sees the in-app update banner (FR22–24) and follows the link to install the new APK with the new key.
9. **Verify recovery.** Confirm the AI Studio dashboard shows new traffic on the new key and zero (or minimal residual) traffic on the old key. The legacy key is fully dead in 24–48 hours as users update.

Cross-reference: the release procedure that wraps this rotation is documented in [RELEASING.md](./RELEASING.md).

## Anti-injection corpus runner — quota note

The pre-release manual injection-corpus runner (`scripts/run_injection_corpus.sh`) talks **directly** to the Gemini API from a developer machine — it does **not** go through the app's `RateLimitRepository`. Each run consumes roughly:

```
~30 calls per run × N revisions of the corpus
```

Where N is the number of corpus iterations the test suite covers (initially N = 1, but the corpus expands over time). Implications:

- Running the corpus casually during development burns the founder's daily quota.
- The corpus runner consumes Gemini quota on the developer's personal AI Studio key, **not** on the bundled key in the APK — but on the same Google account if both keys are issued from there.
- The corpus runner is therefore a **pre-release gate**, not a per-PR check. CI does not run it (architecture decision D2.4); the founder runs it locally before tagging a release ([RELEASING.md](./RELEASING.md) covers this).

## Disclosure timeline (best-effort)

Once a valid finding is confirmed:

| Severity                                           | Triage             | Fix in main    | Public disclosure |
| -------------------------------------------------- | ------------------ | -------------- | ----------------- |
| Critical (RCE, key leak, encryption broken)        | within 7 days      | within 14 days | after fix ships   |
| High (privilege escalation, persistent injection)  | within 14 days     | within 30 days | after fix ships   |
| Medium (information disclosure, denial-of-service) | within 30 days     | next release   | with the fix      |
| Low (hardening opportunity)                        | next release cycle | next release   | with the fix      |

These are best-effort targets. The maintainers will communicate concrete dates per finding once triaged.

## Out of scope

- **Resistance to a determined APK-extraction attack on the bundled API key.** Accepted per NFR9 and the architecture's API-key posture.
- **Resistance to a malicious app on the same device with `SYSTEM_ALERT_WINDOW`.** Same-device adversary is out of V1's threat model.
- **Resistance to a state-level adversary on the network path.** No certificate pinning in V1; HTTPS plus the OS root-store is the V1 ceiling.
- **Vulnerabilities in the Gemini API itself.** Report those directly to Google.
