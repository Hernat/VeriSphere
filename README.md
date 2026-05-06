# VeriSphere

> Android floating bubble that verifies social-media posts via Gemini in one long-press.

> [!WARNING]
> **Pre-V1 alpha — not yet ready for everyday use.** The current version is `0.1.0`. The first public release tag `1.0.0` is reserved for the moment the founder-substitution success criterion is validated for at least one week of personal usage. Until then, expect breaking changes between commits and no published APK.

## What it is

VeriSphere compresses a tedious manual workflow into a single gesture. Today, the founder reading social media goes: *screenshot → power-button Gemini → import the screenshot → type "is this true?" → wait → switch back.* Five context switches, ~30 seconds, every time.

VeriSphere replaces that with: *long-press the floating bubble that follows the user across every app → wait ~2 seconds → read a 4-label flash verdict (✅ True / ❌ False / ⚠️ Doubtful / ⚪ Non-verifiable) with a one-line headline beside the bubble → tap to expand the detail panel with sources, OCR text, and context.*

V1 is **deliberately minimal**: no account, no Play Store listing, no analytics, no notifications, no settings screen, no chat input. The product disappears between long-presses and reappears on demand. See the [architecture](#architecture) section below for the scoping rationale; a public PRD summary will follow once V1 ships.

## Screenshots

<!-- Screenshots forthcoming once V1 ships (Story 7.3). Do not invent images here. -->
*Screenshots forthcoming once V1 ships.*

## Install (end users)

> [!NOTE]
> **Releases are pending until the V1.0.0 ship-gate.** During the pre-V1 alpha phase, no signed APK is published — **build from source** if you want to try it (see [CONTRIBUTING.md](./CONTRIBUTING.md)). The instructions below describe how the install will work once V1 ships; a Drive mirror of the APK lands alongside Story 7.3 for users on networks where GitHub is filtered.

Requirements:

- **Android 8.0+** (API 26 or later)
- **"Install unknown apps" allowed** for the source app you'll use to open the APK (your browser or file manager). On Android 8+ this is per-source-app; the OS prompts the first time you tap the APK.

Install steps (once a release is available):

1. Open the [GitHub Releases page](https://github.com/Hernat/VeriSphere/releases) and download the latest signed APK.
2. Tap the downloaded `.apk` file. Android will prompt you to allow installs from the source app — accept once.
3. After install, launch VeriSphere. The first-launch tutorial walks you through granting the *Display over other apps* and *Screen capture* permissions.

## Build from source

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the full setup, including the `local.properties` Gemini API key configuration and the Gradle commands.

## Architecture

The technical architecture, decisions, and patterns are documented separately. *(A repo-readable summary will land at `docs/architecture.md` before V1 ships.)*

## Privacy posture

- **Encrypted at rest.** All persisted data — sessions, history, OCR text, rate-limit counters — is encrypted via AndroidX `EncryptedSharedPreferences` with a master key from the Android Keystore (AES-256-GCM, non-extractable).
- **Zero telemetry.** No analytics SDK, no remote crash reporting, no remote logging. Outbound HTTPS traffic from the device is limited to two destinations:
  1. The Gemini API (`generativelanguage.googleapis.com`) — for the verification call.
  2. The version-info JSON (`raw.githubusercontent.com`) — fetched once per launch to check for new releases.
- **No cloud backup.** `android:allowBackup="false"` in the manifest prevents Auto Backup from copying encrypted preferences to Google Drive.

See [SECURITY.md](./SECURITY.md) for the threat model, the API-key rotation runbook, and how to report vulnerabilities.

## Built with Gemini

VeriSphere uses Google's [Gemini API](https://ai.google.dev/) for the verification call (Vision + Search Grounding). A "Built with Gemini" attribution is shown in the in-app detail panel and the launch tutorial as the trust-transfer signal at the user surface.

VeriSphere is an unaffiliated third-party app that uses the Gemini API. It is not endorsed by, sponsored by, or otherwise associated with Google LLC.

## Contributing

We accept issues and pull requests. See [CONTRIBUTING.md](./CONTRIBUTING.md) for the local setup, code style, and PR conventions.

## Releasing

The release procedure (keystore handling, version-info update, distribution flow) is documented in [RELEASING.md](./RELEASING.md). The full procedure is finalised in Story 7.3.

## Changelog

See [CHANGELOG.md](./CHANGELOG.md) for the per-version history.

## Licence

MIT — see [LICENSE](./LICENSE).
